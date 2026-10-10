package com.kiwyu.tapassist;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.List;

/**
 * Main automation loop (own thread). Each tick takes a screenshot and decides:
 *   ad showing            -> wait for the close X to appear, tap it
 *   Play Again / Watch Ad -> tap it (never Exit)
 *   puzzle showing        -> read tiles, find the road leprechaun -> pot, tap tiles
 */
public class GameLoop implements Runnable, BotLoop {
    final AutoPlayerService svc;
    final Config cfg;
    final Humanizer human;
    final TapLog log = new TapLog();
    volatile boolean running;
    Thread thread;

    long lastReplayTap = 0;
    long expectAdUntil = 0;
    long adEndedAt = 0;
    final Toucher touch;
    final AdHandler ads;
    Rect lastBoardRect;        // where the board was last seen (to keep the panel out of screenshots)
    int spawnR = -1, spawnC = -1;   // leprechaun cell of the puzzle being solved
    int unsolvedStreak = 0;
    long completeSince = 0;    // when the board first read as complete without the game reacting

    int[] lastTarget;          // masks we expect after our taps
    boolean solvedWaiting;     // board solved; wait for the result screen
    long solvedAt = 0;
    int attempts = 0;
    int solvedCount = 0;
    boolean dumped = false;

    public GameLoop(AutoPlayerService svc, Config cfg) {
        this.svc = svc;
        this.cfg = cfg;
        this.human = new Humanizer(cfg);
        this.touch = new Toucher(svc, cfg, human, log);
        this.ads = new AdHandler(svc, cfg, human, log, touch);
    }

    void tap(int cx, int cy, float hw, float hh, String kind, String detail) {
        touch.tap(cx, cy, hw, hh, kind, detail);
    }

    @Override public boolean isRunning() { return running; }

    @Override public void start() {
        running = true;
        thread = new Thread(this, "TapLoop");
        thread.start();
    }

    @Override public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        svc.status("Running");
        while (running) {
            try { step(); }
            catch (Exception e) { svc.status("Error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
            AutoPlayerService.sleep(human.loopDelay());
        }
        ads.finish(System.currentTimeMillis());
        String saved = cfg.logTaps ? log.save(svc) : null;
        svc.status("Stopped (solved " + solvedCount + ")" + (saved == null ? "" : "\nTap log: " + saved));
    }

    void step() {
        long now = System.currentTimeMillis();

        // A stray tap on an ad opened the Play Store / a browser: go back.
        if (cfg.handleAds && ads.active && ads.stage != 2 && isOutsideApp(svc.fgPackage)) {
            svc.status("Ad opened " + svc.fgPackage + " — going back");
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            AutoPlayerService.sleep(1500);
            return;
        }

        // The floating panel shows up in screenshots. If it sits over the board, capture without it.
        Rect panel = svc.panelRect();
        boolean hide = panel != null && lastBoardRect != null && Rect.intersects(panel, lastBoardRect);
        Frame f = hide ? svc.screenshotWithoutPanel() : svc.screenshot();
        if (f == null) { svc.status("Screenshot failed (retrying)"); return; }
        if (!running) return;

        Board board = Vision.findBoard(f, cfg);
        if (board != null) {
            Rect br = new Rect(board.left, board.top, board.left + board.size, board.top + board.size);
            if (!hide && panel != null && Rect.intersects(panel, br)) {
                lastBoardRect = br;
                f = svc.screenshotWithoutPanel();
                if (f == null) return;
                board = Vision.findBoard(f, cfg);
                if (board == null) return;
            }
            lastBoardRect = new Rect(board.left, board.top, board.left + board.size, board.top + board.size);
        }
        if (board != null) Vision.read(f, board, cfg);
        // A puzzle in progress (leprechaun at its spawn, road not connected) is never a result screen,
        // whatever the accessibility tree says: the game can keep a hidden Play Again button around.
        boolean inPlay = board != null && board.ready() && !Humanizer.connected(board, board.masks);
        boolean adFg = svc.adInForeground();
        int[] replay = adFg || inPlay ? null : findReplay(f, board);

        if (adFg || ads.busy(now) || (board == null && replay == null && now < expectAdUntil)) {
            if (cfg.handleAds) ads.tick(f, now, adFg);
            else svc.status("Ad showing — ad automation is off");
            return;
        }
        if (ads.active) { ads.finish(now); adEndedAt = now; }

        if (replay != null) {
            long since = now - Math.max(lastReplayTap, adEndedAt);
            if (since < cfg.replayGapSec * 1000L) {
                svc.status("Result screen — waiting " + (cfg.replayGapSec - since / 1000) + "s");
                return;
            }
            if (replay[4] == 1 && (!cfg.handleAds || ads.blockedThisSession)) {
                svc.status(ads.blockedThisSession ? "'Watch Ad' screen — real ads seen, tap it yourself"
                        : "'Watch Ad' screen — ad automation is off, tap it yourself");
                return;
            }
            long brk = human.breakDue();
            if (brk > 0 && !restFor(brk)) return;
            AutoPlayerService.sleep(human.reactionPause());
            svc.status("Tapping " + (replay[4] == 1 ? "Watch Ad" : "Play Again") + " (solved " + solvedCount + ")");
            log.event("replay tap " + (replay[4] == 1 ? "watch_ad" : "play_again") + " via " + (replay.length > 5 && replay[5] == 1 ? "accessibility" : "colour"));
            tap(replay[0], replay[1], replay[2] * 0.8f, replay[3] * 0.8f, "replay", replay[4] == 1 ? "watch_ad" : "play_again");
            lastReplayTap = System.currentTimeMillis();
            expectAdUntil = lastReplayTap + 12000;
            solvedWaiting = false;
            lastTarget = null;
            attempts = 0;
            dumped = false;
            AutoPlayerService.sleep(1500);
            return;
        }

        if (board != null) {
            if (solvedWaiting && now - solvedAt < 25000) {
                // if the same puzzle is clearly still unsolved, the "solved" call was wrong
                boolean sameSpawn = board.srcR == spawnR && board.srcC == spawnC;
                if (inPlay && sameSpawn) {
                    if (++unsolvedStreak >= 2) {
                        solvedWaiting = false;
                        solvedCount = Math.max(0, solvedCount - 1);
                        lastTarget = null;
                        log.event("solved call was wrong: board still unsolved, re-solving");
                        svc.status("Not actually solved — continuing");
                    }
                    if (solvedWaiting) return;
                } else {
                    unsolvedStreak = 0;
                    svc.status("Solved — waiting for result (" + solvedCount + " total)");
                    return;
                }
            }
            solvedWaiting = false;
            if (!board.ready()) {
                svc.status("Board found, waiting for leprechaun + pot");
                return;
            }
            solve(f, board);
            return;
        }

        svc.status("Waiting for the puzzle…");
    }

    /** Sleeps in 1 s steps with a countdown; false if stopped meanwhile. */
    boolean restFor(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return true;
            svc.status("Taking a break — " + (left / 1000) + "s");
            AutoPlayerService.sleep(Math.min(1000, left));
        }
        return false;
    }

    static final java.util.regex.Pattern TAPS_LEFT = java.util.regex.Pattern.compile("(\\d+)\\s*left");

    /** The game's "N left" tap counter if it is readable through accessibility, else -1. */
    int tapsLeft() {
        for (AccessibilityNodeInfo n : svc.findNodes(new String[]{"left"}, false)) {
            String t = (n.getText() == null ? "" : n.getText().toString())
                    + " " + (n.getContentDescription() == null ? "" : n.getContentDescription().toString());
            java.util.regex.Matcher m = TAPS_LEFT.matcher(t);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    // ----------------------------------------------------------------- states
    boolean isOutsideApp(String fg) {
        if (fg == null || fg.isEmpty()) return false;
        if (fg.equals(cfg.gamePackage) || fg.equals(svc.getPackageName())) return false;
        if (AutoPlayerService.isAdName(fg)) return false;
        return fg.equals("com.android.vending") || fg.equals("com.android.chrome")
                || fg.equals("com.sec.android.app.sbrowser") || fg.contains("browser");
    }

    /** {x,y} of the replay button, or null. Accessibility text first, then colour. */
    int[] findReplay(Frame f, Board board) {
        String[] labels = cfg.playTexts.split(",");
        for (int i = 0; i < labels.length; i++) labels[i] = labels[i].trim();
        List<AccessibilityNodeInfo> nodes = svc.findNodes(labels, false);
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.isEmpty()) continue;
            String t = (n.getText() == null ? "" : n.getText().toString())
                    + (n.getContentDescription() == null ? "" : n.getContentDescription().toString());
            String l = t.toLowerCase(java.util.Locale.US);
            int type = l.contains("watch") || l.contains("ad ") || l.contains("level") ? 1 : 0;
            // the node must actually be drawn as a button on screen (hidden elements still show up in the tree)
            if (!Vision.looksLikeButton(f, r)) continue;
            return new int[]{r.centerX(), r.centerY(), r.width() / 2, r.height() / 2, type, 1};
        }
        // Both result screens still show the board (in place, or moved up on level-up), so a colour
        // match is only trusted below a visible board — this keeps yellow/orange ads from matching.
        if (board == null) return null;
        return Vision.findReplayButton(f, Math.max(Math.round(f.h * 0.5f), board.top + board.size + 4));
    }

    // ----------------------------------------------------------------- puzzle
    void solve(Frame f, Board board) {
        int[] masks = board.masks;
        if (lastTarget != null && Arrays.equals(masks, lastTarget)) {
            if (completeSince == 0) completeSince = System.currentTimeMillis();
            if (System.currentTimeMillis() - completeSince > 10000) {
                log.event("board reads complete for 10s but the game hasn't reacted");
                String where = DebugDump.save(svc, f, board, "complete but not accepted");
                svc.status("Road looks complete but the game hasn't accepted it — saved " + where);
                lastTarget = null;
                completeSince = 0;
                AutoPlayerService.sleep(3000);
                return;
            }
            svc.status("Road complete — waiting (" + solvedCount + " solved)");
            return;
        }
        completeSince = 0;
        if (cfg.debugDump && !dumped) {
            DebugDump.save(svc, f, board, "read");
            dumped = true;
        }

        Solver solver = new Solver(board, cfg.tapDir);
        int[] turns = solver.solve();
        if (turns == null) {
            svc.status("No road found for the board as read — retrying");
            if (cfg.debugDump) DebugDump.save(svc, f, board, "no solution");
            AutoPlayerService.sleep(1000);
            return;
        }
        if (++attempts > 4) {
            svc.status("Board didn't end up as planned 4 times — pausing 5s");
            if (cfg.debugDump) DebugDump.save(svc, f, board, "retry limit");
            attempts = 0;
            AutoPlayerService.sleep(5000);
            return;
        }

        if (solver.bestTaps() == 0) {
            lastTarget = masks.clone();
            completeSince = System.currentTimeMillis();
            svc.status("Road already connected — waiting");
            return;
        }
        spawnR = board.srcR;
        spawnC = board.srcC;
        if (attempts == 1) human.newRound();
        int left = cfg.humanize ? tapsLeft() : -1;
        int cap = left < 0 ? Integer.MAX_VALUE : left - solver.bestTaps() - cfg.tapsLeftMargin;
        Humanizer.Plan plan = human.plan(board, turns, solver.road(), cfg.tapDir, attempts == 1, cap);
        int[] target = plan.finalMasks;
        if (attempts == 1) {
            long think = human.thinkPause(solver.bestTaps());
            if (think > 0) { svc.status("Reading the board…"); AutoPlayerService.sleep(think); }
        }
        svc.status("Solving: " + solver.bestTaps() + " taps"
                + (plan.badTaps > 0 ? " + " + plan.badTaps + " bad" : "")
                + (left >= 0 ? " (" + left + " left)" : "")
                + (plan.mistakes.isEmpty() ? "" : "\n" + String.join(", ", plan.mistakes)));
        float hw = board.pitchX / 2f, hh = board.pitchY / 2f;
        int prev = -1;
        int distractAt = plan.actions.isEmpty() ? -1 : human.rnd.nextInt(plan.actions.size());
        long distraction = human.distraction();
        for (int ai = 0; ai < plan.actions.size() && running; ai++) {
            Humanizer.Action act = plan.actions.get(ai);
            int r = act.cell / board.cols, c = act.cell % board.cols;
            if (ai == distractAt && distraction > 0 && ai > 0) AutoPlayerService.sleep(distraction);
            if (prev >= 0) {
                if (act.kind.equals("bounce")) AutoPlayerService.sleep(human.bounceGap());
                else if (act.cell == prev) AutoPlayerService.sleep(human.gapSameTile());
                else AutoPlayerService.sleep(human.gapMove(board, prev, act.cell));
                if (act.kind.equals("correction") || act.kind.equals("revisit")) AutoPlayerService.sleep(human.noticePause());
            }
            for (int t = 0; t < act.taps && running; t++) {
                if (t > 0) AutoPlayerService.sleep(human.gapSameTile());
                tap(board.cx(c), board.cy(r), hw, hh, act.kind, r + ":" + c);
            }
            prev = act.cell;
        }
        AutoPlayerService.sleep(cfg.tapDelayMs);
        lastTarget = target;
        AutoPlayerService.sleep(cfg.settleMs);

        verify(board);
    }

    /**
     * After tapping: only call the puzzle solved on real evidence — the road reads as connected, the
     * leprechaun has left its spawn, or a result button is showing. A missing leprechaun alone (detection
     * hiccup) is not enough; re-check a few times instead.
     */
    void verify(Board before) {
        for (int i = 0; i < 3 && running; i++) {
            if (i > 0) AutoPlayerService.sleep(600);
            Frame check = lastBoardRect != null && intersectsPanel() ? svc.screenshotWithoutPanel() : svc.screenshot();
            if (check == null) continue;
            Board b2 = Vision.findBoard(check, cfg);
            if (b2 != null) Vision.read(check, b2, cfg);
            if (b2 != null && b2.ready()) {
                boolean moved = b2.srcR != spawnR || b2.srcC != spawnC;
                if (Humanizer.connected(b2, b2.masks)) { markSolved("road connected"); return; }
                if (moved) { markSolved("leprechaun walking"); return; }
                log.event("after taps the road is not connected yet");
                return;   // still unsolved: the next tick re-solves
            }
            if (findReplay(check, b2) != null) { markSolved("result screen"); return; }
        }
        log.event("couldn't confirm the result after tapping; carrying on");
    }

    boolean intersectsPanel() {
        Rect p = svc.panelRect();
        return p != null && Rect.intersects(p, lastBoardRect);
    }

    void markSolved(String why) {
        log.event("solved (" + why + ")");
        solvedCount++;
        attempts = 0;
        solvedWaiting = true;
        solvedAt = System.currentTimeMillis();
        svc.status("Solved! (" + solvedCount + " total)");
    }
}
