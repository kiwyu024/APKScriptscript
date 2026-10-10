package com.kiwyu.tapassist;

import android.accessibilityservice.AccessibilityService;

/**
 * Ads-only bot (the standalone "Ad Tester" app): waits for ads in any app and handles them as a
 * random kind of viewer (see {@link AdHandler}). Only test ads are touched.
 */
public class AdLoop implements Runnable, BotLoop {
    final AutoPlayerService svc;
    final Config cfg;
    final Humanizer human;
    final TapLog log = new TapLog();
    final AdHandler ads;
    volatile boolean running;
    Thread thread;

    public AdLoop(AutoPlayerService svc, Config cfg) {
        this.svc = svc;
        this.cfg = cfg;
        this.human = new Humanizer(cfg);
        this.ads = new AdHandler(svc, cfg, human, log, new Toucher(svc, cfg, human, log));
    }

    @Override public void start() {
        running = true;
        thread = new Thread(this, "AdLoop");
        thread.start();
    }

    @Override public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override public boolean isRunning() { return running; }

    @Override
    public void run() {
        svc.status("Waiting for an ad…");
        while (running) {
            try { step(); }
            catch (Exception e) { svc.status("Error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
            AutoPlayerService.sleep(human.loopDelay());
        }
        ads.finish(System.currentTimeMillis());
        String saved = cfg.logTaps ? log.save(svc) : null;
        svc.status("Stopped (" + ads.handled + " ads)" + (saved == null ? "" : "\nLog: " + saved));
    }

    void step() {
        long now = System.currentTimeMillis();
        String fg = svc.fgPackage;
        // a tap on the ad opened the Play Store / a browser: go back
        if (ads.active && ads.stage != 2 && (fg.equals("com.android.vending") || fg.contains("browser")
                || fg.equals("com.android.chrome"))) {
            log.event("ad opened " + fg + "; going back");
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            AutoPlayerService.sleep(1500);
            return;
        }
        boolean adFg = svc.adInForeground();
        if (adFg || ads.busy(now)) {
            Frame f = svc.screenshot();
            if (f == null) return;
            ads.tick(f, now, adFg);
            return;
        }
        if (ads.active) ads.finish(now);
        svc.status("Waiting for an ad… (" + ads.handled + " handled"
                + (ads.blockedThisSession ? ", real ads left alone" : "") + ")");
    }
}
