package com.kiwyu.terraminebot;

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
    }

    void start() {
        running = true;
        thread = new Thread(this, "TerraMineLoop");
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
            AutoPlayerService.sleep(cfg.loopMs);
        }
        svc.status("Stopped (solved " + solvedCount + ")");
    }

    void step() {
        long now = System.currentTimeMillis();

        // A stray tap on an ad opened the Play Store / a browser: go back.
        if (isOutsideApp(svc.fgPackage) && (adStart > 0 || now < expectAdUntil + 60000)) {
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
            svc.status("Tapping replay (solved " + solvedCount + ")");
            svc.tap(replay[0], replay[1]);
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
        if (now - unknownSince > 6000) {
            int[] x = Vision.findCloseX(f);
            if (x[0] >= cfg.xThreshold * 2 && now - lastAdTap > 2000) {
                svc.status("Unknown screen with an X — tapping it");
                svc.tap(x[1], x[2]);
                lastAdTap = now;
                return;
            }
        }
        svc.status("Waiting for the Gold Rush puzzle…");
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
            if (!r.isEmpty()) return new int[]{r.centerX(), r.centerY()};
        }
        // Both result screens still show the board (in place, or moved up on level-up), so a colour
        // match is only trusted below a visible board — this keeps yellow/orange ads from matching.
        if (board == null) return null;
        return Vision.findReplayButton(f, Math.max(Math.round(f.h * 0.5f), board.top + board.size + 4));
    }

    // -------------------------------------------------------------------- ads
    void handleAd(Frame f, long now, boolean adFg) {
        if (adStart == 0) adStart = now;
        long secs = (now - adStart) / 1000;
        if (now - lastAdTap < 1500) return;

        int[] x = Vision.findCloseX(f);
        if (x[0] >= cfg.xThreshold) {
            svc.status("Ad: closing X at " + x[1] + "," + x[2] + " (" + secs + "s)");
            svc.tap(x[1], x[2]);
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
                svc.tap(p[0], p[1]);
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

        int[] target = masks.clone();
        for (int i = 0; i < masks.length; i++) target[i] = Solver.rotCw(masks[i], turns[i]);
        svc.status("Solving: " + solver.bestTaps() + " taps");
        for (int r = 0; r < board.rows && running; r++)
            for (int c = 0; c < board.cols && running; c++) {
                int k = solver.taps(turns[r * board.cols + c]);
                for (int t = 0; t < k && running; t++) {
                    svc.tap(board.cx(c), board.cy(r));
                    AutoPlayerService.sleep(cfg.tapDelayMs);
                }
            }
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
        svc.tap(board.cx(c), board.cy(r));
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
