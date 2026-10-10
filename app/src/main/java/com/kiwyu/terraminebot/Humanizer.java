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

    /** Extra pause before going back to fix something (noticing the mistake). */
    public long noticePause() {
        if (!on()) return 0;
        return range(250, 900);
    }

    // ------------------------------------------------------------- tap plans
    /** One step of a plan: tap {@code cell} {@code taps} times. */
    public static class Action {
        public final int cell;
        public int taps;
        public final String kind;   // tile, overshoot, correction, wrong_tile, revisit
        Action(int cell, int taps, String kind) { this.cell = cell; this.taps = taps; this.kind = kind; }
    }

    /** A plan plus the board it should leave behind. */
    public static class Plan {
        public final List<Action> actions;
        public final int[] finalMasks;
        public final int extraTaps;
        public final List<String> mistakes;
        Plan(List<Action> a, int[] m, int extra, List<String> mk) { actions = a; finalMasks = m; extraTaps = extra; mistakes = mk; }
    }

    /**
     * Builds the taps for one round. Humanized first attempts get at least one mistake (except
     * {@code perfectRoundPct}% of rounds): over-rotating a tile and going all the way round, tapping a tile
     * that isn't on the road, or leaving a tile half-turned and coming back to it later. Every plan is
     * simulated tap by tap and only kept if the road connects on the very last tap — the game ends the
     * moment it connects, so a mistake scheduled after that would never happen.
     */
    public Plan plan(Board b, int[] turns, List<Integer> road, int tapDir, boolean firstAttempt) {
        List<Integer> need = new ArrayList<>();
        for (int i = 0; i < turns.length; i++) if (taps(turns[i], tapDir) > 0) need.add(i);
        List<String> none = new ArrayList<>();
        if (need.isEmpty()) return new Plan(new ArrayList<>(), b.masks.clone(), 0, none);
        if (!on()) {
            List<Action> base = base(road, need, turns, tapDir);
            return new Plan(base, simulate(b, base, tapDir, false), 0, none);
        }
        boolean perfect = !firstAttempt || rnd.nextInt(1000) < cfg.perfectRoundPct * 10;
        int wanted = 1;
        while (wanted < 3 && rnd.nextInt(100) < cfg.extraMistakePct) wanted++;
        java.util.Set<Integer> roadSet = new java.util.HashSet<>(road);
        List<Action> fallback = null;
        for (int attempt = 0; attempt < 120; attempt++) {
            // a fresh tile order each attempt: some orders connect a side road early whatever we do
            List<Action> p = base(road, need, turns, tapDir);
            if (fallback == null) fallback = copy(p);
            List<String> done = new ArrayList<>();
            int extra = 0;
            if (!perfect) {
                // later attempts settle for fewer mistakes, but never none
                int want = attempt < 60 ? wanted : 1;
                for (int m = 0; m < want; m++) {
                    int added = addMistake(b, p, roadSet, turns, tapDir, cfg.maxExtraTaps - extra, done);
                    if (added >= 0) extra += added;
                }
                if (done.isEmpty()) continue;
            }
            int[] fin = simulate(b, p, tapDir, true);
            if (fin != null) return new Plan(p, fin, extra, done);
        }
        return new Plan(fallback, simulate(b, fallback, tapDir, false), 0, none);
    }

    /** Taps each tile that needs it, in a humanized order, with a road tile last. */
    List<Action> base(List<Integer> road, List<Integer> need, int[] turns, int tapDir) {
        List<Action> base = new ArrayList<>();
        for (int idx : order(road, need)) base.add(new Action(idx, taps(turns[idx], tapDir), "tile"));
        return base;
    }

    static List<Action> copy(List<Action> p) {
        List<Action> out = new ArrayList<>();
        for (Action a : p) out.add(new Action(a.cell, a.taps, a.kind));
        return out;
    }

    /** Adds one mistake; returns the extra taps it costs, or -1 if none fitted. */
    int addMistake(Board b, List<Action> p, java.util.Set<Integer> roadSet, int[] turns, int tapDir,
                   int budget, List<String> done) {
        int last = p.size() - 1;   // the final action stays final
        int roll = rnd.nextInt(100);
        // 1) over-rotate a tile, then go all the way round to fix it (+4 taps)
        if (roll < 40 && budget >= 4 && last >= 1) {
            int i = rnd.nextInt(last);
            Action a = p.get(i);
            if (a.kind.equals("tile")) {
                a.taps += 1;
                p.add(i + 1 + rnd.nextInt(last - i), new Action(a.cell, 3, "correction"));
                done.add("overshoot " + a.cell / b.cols + ":" + a.cell % b.cols);
                return 4;
            }
        }
        // 2) tap a tile that isn't on the road (+1..2), or a road tile that was already right (+4)
        if (roll < 80 || budget < 4) {
            List<Integer> decoys = new ArrayList<>();
            for (int i = 0; i < b.masks.length; i++) {
                if (b.masks[i] == 0 || roadSet.contains(i)) continue;
                if (i == b.srcR * b.cols + b.srcC || i == b.dstR * b.cols + b.dstC) continue;
                decoys.add(i);
            }
            if (!decoys.isEmpty() && budget >= 1) {
                int cell = decoys.get(rnd.nextInt(decoys.size()));
                int n = budget >= 2 && rnd.nextInt(100) < 30 ? 2 : 1;
                p.add(rnd.nextInt(last + 1), new Action(cell, n, "wrong_tile"));
                done.add("wrong tile " + cell / b.cols + ":" + cell % b.cols);
                return n;
            }
            List<Integer> settled = new ArrayList<>();
            for (int cell : roadSet) if (taps(turns[cell], tapDir) == 0) settled.add(cell);
            if (!settled.isEmpty() && budget >= 4) {
                int cell = settled.get(rnd.nextInt(settled.size()));
                int j = rnd.nextInt(last + 1);
                p.add(j, new Action(cell, 1, "wrong_tile"));
                p.add(j + 1 + rnd.nextInt(p.size() - 1 - j), new Action(cell, 3, "correction"));
                done.add("bumped correct tile " + cell / b.cols + ":" + cell % b.cols);
                return 4;
            }
        }
        // 3) leave a tile half-turned and come back to it later (no extra taps)
        List<Integer> multi = new ArrayList<>();
        for (int i = 0; i < last; i++) if (p.get(i).taps >= 2 && p.get(i).kind.equals("tile")) multi.add(i);
        if (!multi.isEmpty()) {
            int i = multi.get(rnd.nextInt(multi.size()));
            Action a = p.get(i);
            int first = 1 + rnd.nextInt(a.taps - 1);
            p.add(i + 1 + rnd.nextInt(last - i), new Action(a.cell, a.taps - first, "revisit"));
            a.taps = first;
            done.add("came back to " + a.cell / b.cols + ":" + a.cell % b.cols);
            return 0;
        }
        return -1;
    }

    /**
     * Applies the plan to a copy of the board. With {@code strict}, returns null if the road connects
     * before the final tap or isn't connected after it.
     */
    static int[] simulate(Board b, List<Action> plan, int tapDir, boolean strict) {
        int[] m = b.masks.clone();
        int total = 0;
        for (Action a : plan) total += a.taps;
        int done = 0;
        for (Action a : plan)
            for (int t = 0; t < a.taps; t++) {
                m[a.cell] = tapDir >= 0 ? Solver.rotCw(m[a.cell]) : Solver.rotCcw(m[a.cell]);
                done++;
                if (strict && done < total && connected(b, m)) return null;
            }
        if (strict && !connected(b, m)) return null;
        return m;
    }

    static boolean connected(Board b, int[] masks) {
        Board c = new Board(b.left, b.top, b.size, b.size, b.rows, b.cols);
        c.masks = masks;
        c.srcR = b.srcR; c.srcC = b.srcC; c.dstR = b.dstR; c.dstC = b.dstC;
        Solver s = new Solver(c, 1);
        return s.solve() != null && s.bestTaps() == 0;
    }

    static int taps(int cwTurns, int tapDir) { return tapDir >= 0 ? cwTurns : (4 - cwTurns) % 4; }

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
