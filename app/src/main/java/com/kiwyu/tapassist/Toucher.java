package com.kiwyu.tapassist;

/** Taps through the humanizer and records them in the tap log. */
public class Toucher {
    final AutoPlayerService svc;
    final Config cfg;
    final Humanizer human;
    final TapLog log;

    public Toucher(AutoPlayerService svc, Config cfg, Humanizer human, TapLog log) {
        this.svc = svc; this.cfg = cfg; this.human = human; this.log = log;
    }

    /** One tap aimed at (cx, cy); box = half-size of the target, used for the humanized spread. */
    public void tap(int cx, int cy, float hw, float hh, String kind, String detail) {
        int[] p = human.point(cx, cy, hw, hh);
        int press = human.pressMs();
        int[] d = human.drift();
        svc.tap(p[0], p[1], press, d[0], d[1]);
        if (cfg.logTaps) log.add(p[0], p[1], press, d, kind, detail);
    }
}
