package com.kiwyu.tapassist;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.List;

/**
 * Handles one ad at a time, behaving like a particular kind of viewer, for testing how a game reacts to
 * people skipping ads. It only touches ads that carry Google's test-ad label ("Test Ad" / "Test mode").
 * Anything else is left alone and ad automation is paused for the rest of the session.
 *
 * Viewer types (picked per ad by weight):
 *   watch     - waits for the close X, then closes it after a human reaction time (sometimes slow to
 *               notice, sometimes misses the X and taps again)
 *   impatient - pokes the corner where the X will be and/or presses Back before it appears, then
 *               closes fast once it shows
 *   skipper   - presses Back once or twice early, then leaves with Home and comes back a few seconds
 *               later. If a "close video?" dialog shows up it usually picks close.
 * Everything that happens is written to the tap log as events.
 */
public class AdHandler {
    final AutoPlayerService svc;
    final Config cfg;
    final Humanizer human;
    final TapLog log;
    final Toucher touch;

    // session
    boolean blockedThisSession = false;   // a non-test ad was seen
    int handled = 0;

    // current ad
    boolean active = false;
    String behavior = "";
    String pkg = "";
    long start, lastSeen, xSeenAt, reactAt, nextActionAt, nextEarlyAt, relaunchAt, awaySince;
    String lastAction = "";
    boolean testConfirmed, leftAlone;
    int earlyLeft, backsLeft, closeTaps, fallbackTries;
    int stage;            // skipper: 0 = before backs, 1 = backs, 2 = away (home), 3 = back in app
    String closedBy = "";

    static final String[] TEST_LABELS = {"test ad", "test mode"};
    static final String[] CLOSE_LABELS = {"close", "close ad", "close advertisement", "skip", "skip ad",
            "skip video", "×", "✕", "✖", "dismiss"};

    public AdHandler(AutoPlayerService svc, Config cfg, Humanizer human, TapLog log, Toucher touch) {
        this.svc = svc; this.cfg = cfg; this.human = human; this.log = log; this.touch = touch;
    }

    /** True while this handler still owns the screen (e.g. it went Home and will come back). */
    public boolean busy(long now) {
        return active && (stage == 2 || now - lastSeen < 2500);
    }

    void begin(long now) {
        active = true;
        start = lastSeen = now;
        xSeenAt = reactAt = nextActionAt = relaunchAt = 0;
        testConfirmed = leftAlone = false;
        closeTaps = fallbackTries = 0;
        stage = 0;
        closedBy = "";
        lastAction = "";
        pkg = svc.adPackage != null && !svc.adPackage.isEmpty() ? svc.adPackage : svc.fgPackage;
        behavior = pickBehavior();
        earlyLeft = behavior.equals("impatient") ? 1 + human.rnd.nextInt(3) : 0;
        nextEarlyAt = now + human.range(2000, 12000);
        backsLeft = behavior.equals("skipper") ? 1 + human.rnd.nextInt(2) : 0;
        nextActionAt = behavior.equals("skipper") ? now + human.range(1000, 6000) : 0;
        log.event("ad start; viewer=" + behavior + " app=" + pkg);
    }

    String pickBehavior() {
        int w = Math.max(0, cfg.adWatchPct), i = Math.max(0, cfg.adImpatientPct), s = Math.max(0, cfg.adSkipperPct);
        int roll = human.rnd.nextInt(Math.max(1, w + i + s));
        return roll < w ? "watch" : roll < w + i ? "impatient" : "skipper";
    }

    /** Call when the ad is gone. */
    public void finish(long now) {
        if (!active) return;
        active = false;
        if (!leftAlone) handled++;
        log.event("ad end after " + (now - start) + " ms; viewer=" + behavior
                + (xSeenAt > 0 ? "; X appeared after " + (xSeenAt - start) + " ms" : "; X never seen")
                + "; closed by " + (!closedBy.isEmpty() ? closedBy : leftAlone ? "nobody (left alone)"
                : lastAction.isEmpty() ? "the ad itself" : "probably " + lastAction));
    }

    /** One step while an ad is (probably) on screen. */
    public void tick(Frame f, long now, boolean adFg) {
        if (!active) {
            // only start once an ad is really up (the screen is often black for a moment before it loads)
            if (!adFg && svc.findNodes(TEST_LABELS, false).isEmpty()) {
                svc.status("Waiting for the ad…");
                return;
            }
            begin(now);
        }
        if (adFg) lastSeen = now;

        // ---- test-ad gate
        if (!testConfirmed && stage != 2) {
            if (!svc.findNodes(TEST_LABELS, false).isEmpty()) {
                testConfirmed = true;
                log.event("ad is labelled as a test ad");
            } else if (now - start > 8000) {   // give the creative time to load before judging
                if (!leftAlone) {
                    leftAlone = true;
                    if (!blockedThisSession) log.event("ad is NOT labelled as a test ad: leaving it alone, ad automation paused for this session");
                    blockedThisSession = true;
                }
                svc.status("Ad isn't a test ad — leaving it alone");
                return;
            } else {
                svc.status("Ad: checking for the test-ad label…");
                return;
            }
        }
        if (leftAlone) return;
        if (now < nextActionAt && stage != 2) return;
        long secs = (now - start) / 1000;

        // ---- skipper away from the app: come back after a few seconds
        if (stage == 2) {
            if (now >= relaunchAt) {
                Intent i = svc.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    svc.startActivity(i);
                    log.event("ad: came back to the app after " + (now - awaySince) + " ms away");
                } else log.event("ad: couldn't relaunch " + pkg);
                stage = 3;
                lastSeen = now;
                nextActionAt = now + human.range(800, 1600);
            } else svc.status("Ad: away from the app (" + (relaunchAt - now) / 1000 + "s)");
            return;
        }

        // ---- "close video? / resume" dialog
        if (handleDialog()) return;

        int[] x = Vision.findCloseX(f);
        boolean xVisible = x[0] >= cfg.xThreshold;
        if (xVisible && xSeenAt == 0) {
            xSeenAt = now;
            reactAt = now + reaction();
            log.event("ad: close X visible after " + (now - start) + " ms");
        }

        // ---- skip attempts before the X
        if (!xVisible) {
            if (behavior.equals("skipper") && stage <= 1) {
                if (backsLeft > 0) {
                    stage = 1;
                    backsLeft--;
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    lastAction = "Back (skipper)";
                    log.event("ad: pressed Back at " + (now - start) + " ms");
                    svc.status("Ad: pressing Back (skipper)");
                    nextActionAt = now + human.range(800, 2200);
                    return;
                }
                stage = 2;
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                lastAction = "leaving the app (skipper)";
                awaySince = now;
                nextActionAt = now;
                relaunchAt = now + human.range(2000, 10000);
                log.event("ad: pressed Home at " + (now - start) + " ms");
                svc.status("Ad: left the app (skipper)");
                return;
            }
            if (earlyLeft > 0 && now >= nextEarlyAt) {
                earlyLeft--;
                nextEarlyAt = now + human.range(1500, 6000);
                if (human.rnd.nextInt(100) < 30) {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    lastAction = "Back (impatient)";
                    log.event("ad: impatient Back at " + (now - start) + " ms");
                } else {
                    int[] p = cornerGuess(f);
                    touch.tap(p[0], p[1], f.w * 0.03f, f.w * 0.03f, "ad_early_tap", "before_x");
                    lastAction = "early corner tap (impatient)";
                    log.event("ad: impatient tap where the X should be at " + (now - start) + " ms");
                }
                svc.status("Ad: trying to skip early (impatient)");
                return;
            }
        }

        // ---- close it once the X is up and the viewer has reacted
        if (xVisible && now >= reactAt) {
            float r = Math.max(6, f.w * 0.012f);
            if (closeTaps == 0 && human.on() && human.rnd.nextInt(100) < 7) {
                // missed the X
                double ang = human.rnd.nextDouble() * 2 * Math.PI, d = r * (2.2 + human.rnd.nextDouble() * 1.5);
                touch.tap((int) Math.round(x[1] + Math.cos(ang) * d), (int) Math.round(x[2] + Math.sin(ang) * d),
                        2, 2, "ad_close_miss", "missed_x");
                log.event("ad: missed the X");
                nextActionAt = now + human.range(400, 1100);
                closeTaps++;
                return;
            }
            touch.tap(x[1], x[2], r, r, "ad_close", behavior);
            closeTaps++;
            closedBy = "X (" + behavior + ")";
            log.event("ad: tapped X " + (now - (xSeenAt == 0 ? now : xSeenAt)) + " ms after it appeared");
            svc.status("Ad: closed with the X (" + behavior + ", " + secs + "s)");
            nextActionAt = now + 1500;
            return;
        }

        // ---- fallbacks if nothing shows up
        if (secs > 40 && xSeenAt == 0) {
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
                svc.clickNode(best);
                closedBy = "close button";
                log.event("ad: clicked an accessibility close button");
                nextActionAt = now + 1500;
                return;
            }
        }
        if (secs > cfg.adTimeoutSec) {
            int[][] cal = cfg.closePoints();
            int[][] defaults = {cornerGuess(f), {Math.round(f.w * 0.058f), Math.round(f.h * 0.067f)}};
            int[][] all = Arrays.copyOf(cal, cal.length + defaults.length);
            System.arraycopy(defaults, 0, all, cal.length, defaults.length);
            if (fallbackTries < all.length) {
                int[] p = all[fallbackTries++];
                touch.tap(p[0], p[1], 4, 4, "ad_close", "corner_fallback");
                log.event("ad: no X found, tried " + p[0] + "," + p[1]);
            } else if (cfg.backAsLastResort) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                log.event("ad: timeout, pressed Back");
                closedBy = "Back (timeout)";
                fallbackTries = 0;
                start = now - (cfg.adTimeoutSec - 20) * 1000L;
            }
            nextActionAt = now + 1500;
            return;
        }
        svc.status("Ad (" + behavior + "): " + (xVisible ? "X is up" : "waiting for the X") + " — " + secs + "s");
    }

    /** Where AdMob put the X in the recordings (top right). */
    static int[] cornerGuess(Frame f) {
        return new int[]{Math.round(f.w * 0.942f), Math.round(f.h * 0.067f)};
    }

    /** Human reaction time from the X appearing to tapping it, by viewer type. */
    long reaction() {
        if (!human.on()) return 0;
        double t = human.tempo();
        switch (behavior) {
            case "impatient": return human.logNormal(450 * t, 0.35, 200, 2000);
            case "skipper": return human.logNormal(350 * t, 0.30, 150, 1500);
            default:
                long r = human.logNormal(1100 * t, 0.45, 300, 6000);
                if (human.rnd.nextInt(100) < 8) r += human.range(3000, 12000);   // didn't notice it straight away
                return r;
        }
    }

    /**
     * Rewarded ads often ask "close video? you'll lose your reward" with Close / Resume. Skippers usually
     * close, impatient viewers usually resume, watchers resume.
     */
    boolean handleDialog() {
        List<AccessibilityNodeInfo> resume = svc.findNodes(new String[]{"resume"}, false);
        if (resume.isEmpty()) return false;
        List<AccessibilityNodeInfo> close = svc.findNodes(new String[]{"close video", "close ad", "close"}, false);
        int closePct = behavior.equals("skipper") ? 80 : behavior.equals("impatient") ? 30 : 0;
        boolean pickClose = !close.isEmpty() && human.rnd.nextInt(100) < closePct;
        AutoPlayerService.sleep(human.on() ? human.range(500, 1800) : 300);
        svc.clickNode(pickClose ? close.get(0) : resume.get(0));
        log.event("ad: close-confirmation dialog, picked " + (pickClose ? "close" : "resume"));
        if (pickClose) closedBy = "confirmation dialog (" + behavior + ")";
        nextActionAt = System.currentTimeMillis() + 1200;
        return true;
    }
}
