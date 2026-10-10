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
public class GameLoop implements Runnable {
    final AutoPlayerService svc;
    final Config cfg;
    final Humanizer human;
    final TapLog log = new TapLog();
    volatile boolean running;
    Thread thread;

    long lastReplayTap = 0;
    long expectAdUntil = 0;
    long adStart = 0, adEndedAt = 0, lastAdTap = 0;
    int adFallbackTries = 0;
    long unknownSince = 0;
    Rect lastBoardRect;        // where the board was last seen (to keep the panel out of screenshots)

    int[] lastTarget;          // masks we expect after our taps
    boolean solvedWaiting;     // board solved; wait for the result screen
    long solvedAt = 0;
    int attempts = 0;
    int solvedCount = 0;
    boolean dumped = false;

    static final String[] CLOSE_LABELS = {"close", "close ad", "close advertisement", "skip", "skip ad",
            "skip video", "×", "✕", "✖", "dismiss"};

    public GameLoop(AutoPlayerService svc, Config cfg) {
        this.svc = svc;
        this.cfg = cfg;
        this.human = new Humanizer(cfg);
    }

    /** One tap through the humanizer, recorded in the tap log. Box = half-size of the target. */
    void tap(int cx, int cy, float hw, float hh, String kind, String detail) {
        int[] p = human.point(cx, cy, hw, hh);
        int press = human.pressMs();
        int[] d = human.drift();
        svc.tap(p[0], p[1], press, d[0], d[1]);
        if (cfg.logTaps) log.add(p[0], p[1], press, d, kind, detail);
    }

    void start() {
        running = true;
        thread = new Thread(this, "TapLoop");
        thread.start();
    }

    void stop() {
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
        String saved = cfg.logTaps ? log.save(svc) : null;
        svc.status("Stopped (solved " + solvedCount + ")" + (saved == null ? "" : "\nTap log: " + saved));
    }

    void step() {
        long now = System.currentTimeMillis();

        // A stray tap on an ad opened the Play Store / a browser: go back.
        if (cfg.handleAds && isOutsideApp(svc.fgPackage) && (adStart > 0 || now < expectAdUntil + 60000)) {
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
        boolean adFg = svc.adInForeground();
        int[] replay = adFg ? null : findReplay(f, board);

        if (adFg || (board == null && replay == null && (now < expectAdUntil || adStart > 0))) {
            handleAd(f, now, adFg);
            return;
        }
        if (adStart > 0) { adStart = 0; adEndedAt = now; adFallbackTries = 0; }

        if (replay != null) {
            unknownSince = 0;
            long since = now - Math.max(lastReplayTap, adEndedAt);
            if (since < cfg.replayGapSec * 1000L) {
                svc.status("Result screen — waiting " + (cfg.replayGapSec - since / 1000) + "s");
                return;
            }
            if (replay[4] == 1 && !cfg.handleAds) {
                svc.status("'Watch Ad' screen — ad automation is off, tap it yourself");
                return;
            }
            long brk = human.breakDue();
            if (brk > 0 && !restFor(brk)) return;
            AutoPlayerService.sleep(human.reactionPause());
            svc.status("Tapping " + (replay[4] == 1 ? "Watch Ad" : "Play Again") + " (solved " + solvedCount + ")");
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
            unknownSince = 0;
            Vision.read(f, board, cfg);
            if (solvedWaiting && now - solvedAt < 25000) {
                svc.status("Solved — waiting for result (" + solvedCount + " total)");
                return;
            }
            solvedWaiting = false;
            if (!board.ready()) {
                svc.status("Board found, waiting for leprechaun + pot");
                return;
            }
            solve(f, board);
            return;
        }

        // Unknown screen: if an X sits in a top corner for a while, it's probably an ad we didn't recognise.
        if (unknownSince == 0) unknownSince = now;
        if (cfg.handleAds && now - unknownSince > 6000) {
            int[] x = Vision.findCloseX(f);
            if (x[0] >= cfg.xThreshold * 2 && now - lastAdTap > 2000) {
                svc.status("Unknown screen with an X — tapping it");
                tap(x[1], x[2], 4, 4, "ad_close", "unknown_screen");
                lastAdTap = now;
                return;
            }
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
            return new int[]{r.centerX(), r.centerY(), r.width() / 2, r.height() / 2, type};
        }
        // Both result screens still show the board (in place, or moved up on level-up), so a colour
        // match is only trusted below a visible board — this keeps yellow/orange ads from matching.
        if (board == null) return null;
        return Vision.findReplayButton(f, Math.max(Math.round(f.h * 0.5f), board.top + board.size + 4));
    }

    // -------------------------------------------------------------------- ads
    void handleAd(Frame f, long now, boolean adFg) {
        if (adStart == 0) adStart = now;
        if (!cfg.handleAds) {
            svc.status("Ad showing — ad automation is off");
            return;
        }
        long secs = (now - adStart) / 1000;
        if (now - lastAdTap < 1500) return;

        int[] x = Vision.findCloseX(f);
        if (x[0] >= cfg.xThreshold) {
            svc.status("Ad: closing X at " + x[1] + "," + x[2] + " (" + secs + "s)");
            tap(x[1], x[2], 4, 4, "ad_close", "x_detected");
            lastAdTap = System.currentTimeMillis();
            return;
        }

        if (secs > 40) {
            AccessibilityNodeInfo best = null;
            int bestScore = Integer.MAX_VALUE;
            for (AccessibilityNodeInfo n : svc.findNodes(CLOSE_LABELS, true)) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (b.isEmpty()) continue;
                int score = b.centerY() + Math.min(b.centerX(), f.w - b.centerX());
                if (score < bestScore) { bestScore = score; best = n; }
            }
            if (best != null) {
                svc.status("Ad: clicking close button (" + secs + "s)");
                svc.clickNode(best);
                lastAdTap = System.currentTimeMillis();
                return;
            }
        }

        if (secs > cfg.adTimeoutSec) {
            int[][] cal = cfg.closePoints();
            int[][] defaults = {
                    {Math.round(f.w * 0.942f), Math.round(f.h * 0.067f)},  // where AdMob put it in the recordings
                    {Math.round(f.w * 0.058f), Math.round(f.h * 0.067f)},
            };
            int[][] all = Arrays.copyOf(cal, cal.length + defaults.length);
            System.arraycopy(defaults, 0, all, cal.length, defaults.length);
            if (adFallbackTries < all.length) {
                int[] p = all[adFallbackTries++];
                svc.status("Ad: no X found, trying " + p[0] + "," + p[1]);
                tap(p[0], p[1], 4, 4, "ad_close", "corner_fallback");
            } else if (cfg.backAsLastResort) {
                svc.status("Ad: pressing Back");
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                adFallbackTries = 0;
                adStart = now - (cfg.adTimeoutSec - 20) * 1000L;   // try again in ~20s
            }
            lastAdTap = System.currentTimeMillis();
            return;
        }
        svc.status("Ad: waiting for the X (" + secs + "s)" + (adFg ? "" : " ?"));
    }

    // ----------------------------------------------------------------- puzzle
    void solve(Frame f, Board board) {
        int[] masks = board.masks;
        if (lastTarget != null && Arrays.equals(masks, lastTarget)) {
            svc.status("Road complete — waiting (" + solvedCount + " solved)");
            return;
        }
        if (cfg.debugDump && !dumped) {
            DebugDump.save(svc, f, board, "read");
            dumped = true;
        }
        if (cfg.tapDir == 0 && !learnTapDirection(board)) return;

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
            svc.status("Road already connected — waiting");
            return;
        }
        if (attempts == 1) human.newRound();
        int left = cfg.mistakes || cfg.humanize ? tapsLeft() : -1;
        int cap = left < 0 ? Integer.MAX_VALUE : left - solver.bestTaps() - cfg.tapsLeftMargin;
        Humanizer.Plan plan = human.plan(board, turns, solver.road(), cfg.tapDir, attempts == 1, cap);
        int[] target = plan.finalMasks;
        if (attempts == 1) {
            long think = human.thinkPause(solver.bestTaps());
            if (think > 0) { svc.status("Reading the board…"); AutoPlayerService.sleep(think); }
        }
        svc.status("Solving: " + solver.bestTaps() + " taps"
                + (plan.badTaps > 0 ? " + " + plan.badTaps + " extra" : "")
                + (left >= 0 ? " (" + left + " left)" : "")
                + (plan.summary.isEmpty() ? "" : "\n" + plan.summary));
        if (cfg.logTaps) log.event("round: " + plan.summary + "; needed " + solver.bestTaps() + " taps, planned "
                + (solver.bestTaps() + plan.badTaps));
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

        Frame check = lastBoardRect != null && intersectsPanel() ? svc.screenshotWithoutPanel() : svc.screenshot();
        if (check == null) return;
        Board b2 = Vision.findBoard(check, cfg);
        if (b2 == null) { markSolved(); return; }   // screen already moved on
        Vision.read(check, b2, cfg);
        Solver verify = new Solver(b2, cfg.tapDir);
        if (b2.ready() && verify.solve() != null && verify.bestTaps() == 0) markSolved();
        else if (!b2.ready()) markSolved();          // leprechaun started walking
    }

    boolean intersectsPanel() {
        Rect p = svc.panelRect();
        return p != null && Rect.intersects(p, lastBoardRect);
    }

    void markSolved() {
        solvedCount++;
        attempts = 0;
        solvedWaiting = true;
        solvedAt = System.currentTimeMillis();
        svc.status("Solved! (" + solvedCount + " total)");
    }

    /** Taps one asymmetric road tile once and sees which way it turned. */
    boolean learnTapDirection(Board board) {
        int idx = -1;
        for (int i = 0; i < board.masks.length; i++) {
            int m = board.masks[i];
            if (m != 0 && m != 15 && m != 5 && m != 10) { idx = i; break; }
        }
        if (idx < 0) { cfg.tapDir = 1; cfg.save(svc); return true; }
        int r = idx / board.cols, c = idx % board.cols, before = board.masks[idx];
        svc.status("Learning tap direction…");
        tap(board.cx(c), board.cy(r), board.pitchX / 2f, board.pitchY / 2f, "tile", "learn_direction");
        AutoPlayerService.sleep(cfg.settleMs);
        Frame f2 = lastBoardRect != null && intersectsPanel() ? svc.screenshotWithoutPanel() : svc.screenshot();
        if (f2 == null) return false;
        Vision.read(f2, board, cfg);
        int after = board.masks[idx];
        if (after == Solver.rotCw(before)) cfg.tapDir = 1;
        else if (after == Solver.rotCcw(before)) cfg.tapDir = -1;
        else return false;   // try again next tick
        cfg.save(svc);
        svc.status("Tap rotates " + (cfg.tapDir > 0 ? "clockwise" : "counter-clockwise"));
        return false;        // re-read the board next tick
    }
}
