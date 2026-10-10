package com.kiwyu.terraminebot;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Randomises how the bot taps, so the developer can check whether their anti-cheat still
 * notices it: where inside a tile it taps, how long each touch lasts, small finger drift,
 * the pauses between taps, a "reading the board" pause, tile order and the odd mistake.
 * With humanize off everything is exact and fixed (centre taps, fixed delays, row order).
 */
public class Humanizer {
    final Config cfg;
    final Random rnd = new SecureRandom();

    public Humanizer(Config cfg) { this.cfg = cfg; }

    boolean on() { return cfg.humanize; }

    /** A tap point inside a box of half-size (hw, hh) around (cx, cy): normal spread, clamped to stay inside. */
    public int[] point(int cx, int cy, float hw, float hh) {
        if (!on()) return new int[]{cx, cy};
        float maxX = hw * cfg.jitterPct / 100f, maxY = hh * cfg.jitterPct / 100f;
        float dx = clamp((float) rnd.nextGaussian() * maxX / 2f, -maxX, maxX);
        float dy = clamp((float) rnd.nextGaussian() * maxY / 2f, -maxY, maxY);
        return new int[]{Math.round(cx + dx), Math.round(cy + dy)};
    }

    /** How long the finger stays down, ms. */
    public int pressMs() {
        if (!on()) return 40;
        return range(cfg.pressMinMs, cfg.pressMaxMs);
    }

    /** Small slide while pressed (px), well under Android's touch slop so it still counts as a tap. */
    public int[] drift() {
        if (!on() || cfg.driftPx <= 0) return new int[]{0, 0};
        return new int[]{rnd.nextInt(2 * cfg.driftPx + 1) - cfg.driftPx, rnd.nextInt(2 * cfg.driftPx + 1) - cfg.driftPx};
    }

    /** Gap before tapping the same tile again. */
    public long gapSameTile() {
        if (!on()) return cfg.tapDelayMs;
        return logNormal(cfg.tapDelayMs, 0.30, Math.max(150, cfg.tapDelayMs / 2), cfg.tapDelayMs * 3L);
    }

    /** Gap before moving to the next tile (finger travel + looking). */
    public long gapNextTile() {
        if (!on()) return cfg.tapDelayMs;
        return gapSameTile() + range(cfg.moveMinMs, cfg.moveMaxMs);
    }

    /** "Reading the board" pause before the first tap of a new puzzle. */
    public long thinkPause() {
        if (!on()) return 0;
        return range(cfg.thinkMinMs, cfg.thinkMaxMs);
    }

    /** Reaction time before pressing the replay button. */
    public long reactionPause() {
        if (!on()) return 0;
        return range(cfg.reactMinMs, cfg.reactMaxMs);
    }

    /** True when this tile should get a deliberate wrong turn first. */
    public boolean mistake() {
        return on() && cfg.mistakePct > 0 && rnd.nextInt(100) < cfg.mistakePct;
    }

    /**
     * Order to visit the tiles that need taps. Humanized: usually along the road from the
     * leprechaun, sometimes from the pot end, sometimes shuffled, with a few neighbours swapped.
     */
    public List<Integer> order(List<Integer> roadOrder, List<Integer> needTaps) {
        List<Integer> out = new ArrayList<>();
        for (int i : roadOrder) if (needTaps.contains(i)) out.add(i);
        for (int i : needTaps) if (!out.contains(i)) out.add(i);
        if (!on()) { Collections.sort(out); return out; }
        int roll = rnd.nextInt(100);
        if (roll < 20) Collections.reverse(out);
        else if (roll < 35) Collections.shuffle(out, rnd);
        for (int i = 0; i + 1 < out.size(); i++)
            if (rnd.nextInt(100) < 15) Collections.swap(out, i, i + 1);
        return out;
    }

    int range(int lo, int hi) {
        if (hi <= lo) return lo;
        return lo + rnd.nextInt(hi - lo + 1);
    }

    long logNormal(double median, double sigma, long lo, long hi) {
        long v = Math.round(median * Math.exp(rnd.nextGaussian() * sigma));
        return Math.max(lo, Math.min(hi, v));
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
