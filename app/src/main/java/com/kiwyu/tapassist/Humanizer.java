package com.kiwyu.tapassist;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Makes the bot's play look like a person's, for testing the game's anti-cheat (see docs/ANTI_CHEAT_NOTES.md).
 *
 * Randomness is layered:
 *  - session: thumb bias and spread, base speed, typical press length, rounds until a break
 *  - round:   mood (speed factor), fatigue, number of bad taps, revisits, an occasional distraction
 *  - tap:     position, press length, finger slide, gaps (Fitts-style travel time), hesitations
 *
 * With humanize off everything is exact and fixed (centre taps, fixed delays, row order, no mistakes).
 */
public class Humanizer {
    final Config cfg;
    final Random rnd = new SecureRandom();

    // session profile
    double speed;          // > 1 = slower player
    float biasX, biasY;    // habitual offset from the tile centre, fraction of half a tile
    float spread;          // how loose this session's taps are
    double pressMedian;    // ms
    int roundsSinceBreak, roundsUntilBreak;
    // round state
    double mood = 1;

    public Humanizer(Config cfg) {
        this.cfg = cfg;
        newSession();
    }

    /** Overrides cfg.humanize when set (the collector has its own switch). */
    public Boolean force = null;

    boolean on() { return force != null ? force : cfg.humanize; }

    void newSession() {
        speed = logNormal(1.0, 0.15);
        double ang = rnd.nextDouble() * 2 * Math.PI, mag = 0.04 + rnd.nextDouble() * 0.14;
        biasX = (float) (Math.cos(ang) * mag);
        biasY = (float) (Math.sin(ang) * mag);
        spread = 0.75f + rnd.nextFloat() * 0.5f;
        pressMedian = 65 + rnd.nextInt(50);
        roundsSinceBreak = 0;
        roundsUntilBreak = range(cfg.breakEveryMin, cfg.breakEveryMax);
    }

    /** Call when a new puzzle starts. */
    public void newRound() {
        mood = logNormal(1.0, 0.12);
        roundsSinceBreak++;
    }

    /** Overall slowness right now: session speed × round mood × fatigue. */
    double tempo() {
        double fatigue = Math.min(1.3, 1 + 0.004 * roundsSinceBreak);
        return speed * mood * fatigue;
    }

    // ------------------------------------------------------------------ taps
    /** A tap point inside a box of half-size (hw, hh) around (cx, cy). */
    public int[] point(int cx, int cy, float hw, float hh) {
        if (!on()) return new int[]{cx, cy};
        float lim = cfg.jitterPct / 100f;
        boolean sloppy = rnd.nextInt(100) < 4;
        float sd = (sloppy ? 0.45f : 0.22f) * spread * lim;
        float fx = biasX + (float) rnd.nextGaussian() * sd;
        float fy = biasY + (float) rnd.nextGaussian() * sd;
        float max = sloppy ? 0.8f : lim;
        fx = clamp(fx, -max, max);
        fy = clamp(fy, -max, max);
        return new int[]{Math.round(cx + fx * hw), Math.round(cy + fy * hh)};
    }

    /**
     * A tap point anywhere on a button/card of half-size (hw, hh): centred on this session's habitual
     * spot, loose, but kept inside the inner 75% so it never lands off the element.
     */
    public int[] pointWide(int cx, int cy, float hw, float hh) {
        if (!on()) return new int[]{cx, cy};
        float sd = 0.30f * spread;
        float fx = biasX * 2 + (float) rnd.nextGaussian() * sd;
        float fy = biasY * 2 + (float) rnd.nextGaussian() * sd;
        if (rnd.nextInt(100) < 15) fx = (rnd.nextFloat() * 2 - 1) * 0.7f;   // sometimes just anywhere along it
        fx = clamp(fx, -0.75f, 0.75f);
        fy = clamp(fy, -0.75f, 0.75f);
        return new int[]{Math.round(cx + fx * hw), Math.round(cy + fy * hh)};
    }

    /** Pause, ms: log-normal around {@code medianMs} (scaled by tempo and speed); 0 when humanize is off. */
    public long glance(double medianMs, long lo, long hi) {
        if (!on()) return 0;
        return scaled(logNormal(medianMs * tempo(), 0.4, lo, hi));
    }

    public boolean chance(int pct) { return on() && rnd.nextInt(100) < pct; }

    /** How long the finger stays down, ms. */
    public int pressMs() {
        if (!on()) return 40;
        long v = logNormal(pressMedian * Math.sqrt(tempo()), 0.28, cfg.pressMinMs, cfg.pressMaxMs);
        return (int) v;
    }

    /** Small slide while pressed, px (kept under Android's touch slop so it is still a tap). */
    public int[] drift() {
        if (!on() || cfg.driftPx <= 0) return new int[]{0, 0};
        double mag = Math.min(cfg.driftPx, Math.abs(rnd.nextGaussian()) * cfg.driftPx / 2.0);
        double ang = rnd.nextDouble() * 2 * Math.PI;
        return new int[]{(int) Math.round(Math.cos(ang) * mag), (int) Math.round(Math.sin(ang) * mag)};
    }

    /** Gap before tapping the same tile again (a rhythm; faster than moving). */
    public long gapSameTile() {
        long v = !on() ? cfg.tapDelayMs
                : logNormal(cfg.tapDelayMs * 0.85 * tempo(), 0.22, Math.max(160, cfg.tapDelayMs / 2), cfg.tapDelayMs * 4L);
        return fast(v, SAME_TILE_MIN_MS);
    }

    // ------------------------------------------------------------------ speed
    /*
     * Speed setting: every delay is divided by cfg.speed, but never below what the game needs. Measured
     * on the 120 fps recordings, one tile turn animates for about 120-175 ms, so:
     */
    static final long SAME_TILE_MIN_MS = 200;   // let a turn finish before tapping the same tile again
    static final long MOVE_MIN_MS = 90;         // different tiles animate independently
    static final long SETTLE_MIN_MS = 400;      // wait for the last turn before re-reading the board
    static final long LOOP_MIN_MS = 350;        // Android allows about 3 screenshots a second

    /** Delay scaled by the speed setting, but not below {@code floorMs}. */
    public long fast(long ms, long floorMs) {
        double sp = Math.max(0.25, cfg.speed);
        return Math.max(floorMs, Math.round(ms / sp));
    }

    /** Delay scaled by the speed setting (pauses with no game-imposed minimum). */
    public long scaled(long ms) {
        return Math.round(ms / Math.max(0.25, cfg.speed));
    }

    /** Accidental double tap: second touch right after the first. */
    public long bounceGap() { return range(55, 120); }

    /**
     * Gap before moving to another tile: a base gap plus Fitts-style travel time that grows with
     * distance, and now and then a hesitation.
     */
    public long gapMove(Board b, int fromCell, int toCell) {
        if (!on()) return fast(cfg.tapDelayMs, MOVE_MIN_MS);
        double dist = 0;
        if (fromCell >= 0) {
            int dr = fromCell / b.cols - toCell / b.cols, dc = fromCell % b.cols - toCell % b.cols;
            dist = Math.hypot(dr, dc);   // in tiles
        }
        double fitts = (60 + 120 * Math.log(1 + dist) / Math.log(2)) * tempo();
        long gap = logNormal(cfg.tapDelayMs * tempo(), 0.25, 150, cfg.tapDelayMs * 5L) + Math.round(fitts);
        if (rnd.nextInt(100) < cfg.hesitationPct) gap += range(700, 3000);
        return fast(gap, MOVE_MIN_MS);
    }

    /** Pause before going back to fix something (noticing the mistake). */
    public long noticePause() {
        if (!on()) return 0;
        return scaled(logNormal(450 * tempo(), 0.4, 150, 2500));
    }

    /** "Reading the board" pause before the first tap; longer for harder boards. */
    public long thinkPause(int neededTaps) {
        if (!on()) return 0;
        double difficulty = Math.min(1.0, neededTaps / 25.0);
        double median = cfg.thinkMinMs + (cfg.thinkMaxMs - cfg.thinkMinMs) * (0.25 + 0.75 * difficulty);
        return scaled(logNormal(median * tempo(), 0.30, cfg.thinkMinMs / 2, cfg.thinkMaxMs * 2L));
    }

    /** Reaction time before pressing the replay button. */
    public long reactionPause() {
        if (!on()) return 0;
        double median = (cfg.reactMinMs + cfg.reactMaxMs) / 2.0;
        return scaled(logNormal(median * tempo(), 0.35, cfg.reactMinMs / 2, cfg.reactMaxMs * 2L));
    }

    /** A rare long pause during a round (phone notification, looking away). 0 if none. */
    public long distraction() {
        if (!on() || rnd.nextInt(1000) >= cfg.distractionPct * 10) return 0;
        return scaled(range(5000, 25000));
    }

    /** If a break is due (checked between rounds), its length in ms; otherwise 0. */
    public long breakDue() {
        if (!on() || !cfg.takeBreaks || roundsSinceBreak < roundsUntilBreak) return 0;
        long ms = range(cfg.breakMinSec, cfg.breakMaxSec) * 1000L;
        roundsSinceBreak = 0;
        roundsUntilBreak = range(cfg.breakEveryMin, cfg.breakEveryMax);
        mood = logNormal(1.0, 0.12);
        return ms;
    }

    /** Screen polling interval, jittered so reaction to a new screen isn't a fixed beat. */
    public long loopDelay() {
        if (!on()) return fast(cfg.loopMs, LOOP_MIN_MS);
        return fast(Math.round(cfg.loopMs * (0.7 + rnd.nextDouble() * 0.6)), LOOP_MIN_MS);
    }

    /** Where a round starts: the leprechaun end, the gold end, or a stray tap somewhere else first. */
    enum Start { LEPRECHAUN, GOLD, STRAY }

    /** The start habit for a round: {@code startLepPct}% leprechaun end, {@code startPotPct}% gold end, rest stray. */
    Start pickStart(boolean allowStray) {
        int roll = rnd.nextInt(1000);
        int lep = cfg.startLepPct * 10, pot = cfg.startPotPct * 10;
        if (roll < lep) return Start.LEPRECHAUN;
        if (roll < lep + pot) return Start.GOLD;
        if (allowStray) return Start.STRAY;
        // no stray tap allowed (perfect round): keep the same lep:gold proportion
        return rnd.nextInt(Math.max(1, lep + pot)) < lep ? Start.LEPRECHAUN : Start.GOLD;
    }

    /**
     * Order to visit the tiles that need taps. The first is always the first tile needing a turn from the
     * chosen end (next to the leprechaun, or next to the gold); after that it works along the road from that end,
     * with neighbouring tiles occasionally swapped (never the first). Humanize off: from the leprechaun end, in order.
     */
    public List<Integer> order(List<Integer> roadOrder, List<Integer> needTaps, boolean fromGold, boolean vary) {
        List<Integer> out = new ArrayList<>();
        for (int i : roadOrder) if (needTaps.contains(i)) out.add(i);
        for (int i : needTaps) if (!out.contains(i)) out.add(i);
        if (fromGold) Collections.reverse(out);
        if (!on() && !vary) return out;
        for (int i = 1; i + 1 < out.size(); i++)
            if (rnd.nextInt(100) < 15) Collections.swap(out, i, i + 1);
        return out;
    }

    // ------------------------------------------------------------- tap plans
    /** One step of a plan: tap {@code cell} {@code taps} times. */
    public static class Action {
        public final int cell;
        public int taps;
        public final String kind;   // tile, overshoot, bounce, correction, wrong_tile, grass, revisit
        Action(int cell, int taps, String kind) { this.cell = cell; this.taps = taps; this.kind = kind; }
    }

    /** A plan plus the board it should leave behind. */
    public static class Plan {
        public final List<Action> actions;
        public final int[] finalMasks;
        public final int badTaps;          // wrong taps + over-rotation taps
        public final List<String> mistakes;
        public String summary = "";        // e.g. "6x6, clean 60% -> clean: 2 wrong, 1 over-rotation"
        Plan(List<Action> a, int[] m, int bad, List<String> mk) { actions = a; finalMasks = m; badTaps = bad; mistakes = mk; }
    }

    // ------------------------------------------------------- mistake model
    /**
     * Chance (%) that a round is "clean" (solved with only a few wrong taps), by board size, nudged down
     * for boards that need more turns than usual for their size and up for easier ones.
     */
    int cleanPct(Board b, int neededTaps) {
        int n = Math.max(4, Math.min(7, b.rows));
        int base = n == 4 ? cfg.cleanPct4 : n == 5 ? cfg.cleanPct5 : n == 6 ? cfg.cleanPct6 : cfg.cleanPct7;
        // typical turns needed per size (4x4 level 1 needed 13, the recorded 6x6 levels 10-30)
        double typical = n == 4 ? 12 : n == 5 ? 16 : n == 6 ? 21 : 27;
        double harder = Math.max(-1, Math.min(1, (neededTaps - typical) / typical));
        // how far a harder/easier board can move the chance: little near 100% (early levels stay ~clean),
        // up to 10 points in the middle
        double room = Math.min(10, (100 - base) / 2.0 + 2);
        int pct = (int) Math.round(base - harder * room);
        return Math.max(1, Math.min(99, pct));
    }

    /** For the settings screen / tests. */
    public int cleanPctPublic(Board b, int neededTaps) { return cleanPct(b, neededTaps); }

    /**
     * Wrong taps for a round, as a weighted pick so the counts cluster around a typical value and the
     * maximum stays possible but rare:
     *   clean rounds - 4x4: 0-2, mostly 1;  bigger boards: 1-3, mostly 2-3
     *   messy rounds - from 3 (4x4) or 4 up to 5 / 8 / 12 / 15 (4x4 / 5x5 / 6x6 / 7x7); most likely the
     *                  lowest count on 4x4-6x6 (falling off towards the max), 5-8 on 7x7
     * The clean/messy split (cleanPct) decides how often each happens, so 4x4 is mostly 1, 5x5/6x6
     * mostly 2-3, and 7x7 mostly 5-8.
     */
    int pickWrongTaps(Board b, boolean clean, int cap) {
        double[] w = wrongTapWeights(b.rows, clean);
        double sum = 0;
        for (double v : w) sum += v;
        double x = rnd.nextDouble() * sum;
        int pick = 0;
        for (int k = 0; k < w.length; k++) { if ((x -= w[k]) <= 0) { pick = k; break; } pick = k; }
        return Math.max(0, Math.min(pick, cap));
    }

    /** Relative weight of each wrong-tap count (index = count) for a clean or messy round on an n x n board. */
    public static double[] wrongTapWeights(int boardSize, boolean clean) {
        int n = Math.max(4, Math.min(7, boardSize));
        if (clean) {
            if (n == 4) return new double[]{0.30, 0.45, 0.25};                    // 0-2, mostly 1
            if (n == 7) return new double[]{0, 0.30, 0.40, 0.30};                  // 1-3
            return new double[]{0, 0.20, 0.42, 0.38};                              // 1-3, mostly 2-3
        }
        int max = n == 4 ? 5 : n == 5 ? 8 : n == 6 ? 12 : 15;
        int lo = n == 4 ? 3 : 4;                               // 4x4 messy starts at 3 so there's no gap after 2
        int modeLo = n == 7 ? 5 : lo, modeHi = n == 7 ? 8 : lo;
        double atMax = 0.06;                                   // the max is ~6% as likely as the typical count
        double decay = Math.pow(atMax, 1.0 / Math.max(1, max - modeHi));
        double[] w = new double[max + 1];
        for (int k = lo; k <= max; k++) {
            if (k < modeLo) w[k] = Math.pow(0.6, modeLo - k);
            else if (k <= modeHi) w[k] = 1;
            else w[k] = Math.pow(decay, k - modeHi);
        }
        return w;
    }

    /**
     * Over-rotations: each correct tap goes one step too far with this round's rate. The rate itself is
     * random per round (0 to 2x {@code overRotatePct}, so it averages out at {@code overRotatePct}%) unless
     * {@code fixedRate} is set.
     */
    int pickOverRotations(int correctTaps, int slippableTaps, int cap, boolean fixedRate) {
        double rate = fixedRate ? cfg.overRotatePct / 100.0 : rnd.nextDouble() * 2 * cfg.overRotatePct / 100.0;
        // the last tile turned completes the road, so it can't slip: spread its share over the others
        if (slippableTaps <= 0) return 0;
        rate = Math.min(1, rate * correctTaps / (double) slippableTaps);
        int k = 0;
        for (int i = 0; i < slippableTaps; i++) if (rnd.nextDouble() < rate) k++;
        return Math.max(0, Math.min(k, cap / 2));
    }

    /**
     * The odd "straight line" maps: leprechaun and gold in the same row or column with only straight
     * pieces between them.
     */
    public static boolean isStraightLine(Board b) {
        if (!b.ready()) return false;
        int between = 0;
        if (b.srcR == b.dstR) {
            for (int c = Math.min(b.srcC, b.dstC) + 1; c < Math.max(b.srcC, b.dstC); c++, between++)
                if (!straight(b.masks[b.srcR * b.cols + c])) return false;
        } else if (b.srcC == b.dstC) {
            for (int r = Math.min(b.srcR, b.dstR) + 1; r < Math.max(b.srcR, b.dstR); r++, between++)
                if (!straight(b.masks[r * b.cols + b.srcC])) return false;
        } else return false;
        return between > 0;
    }

    static boolean straight(int m) { return m == 5 || m == 10; }

    /** Taps for a full turn back to the same orientation: 2 for a straight, 4 for other shapes, 1 if symmetric. */
    static int period(int m) {
        if (Solver.rotCw(m) == m) return 1;
        if (Solver.rotCw(m, 2) == m) return 2;
        return 4;
    }

    /**
     * Builds the taps for one round. With mistakes on, the first attempt at a puzzle is either "clean"
     * (a few wrong taps) or "messy" (more), with the clean chance falling as boards get bigger and harder
     * (see {@link #cleanPct}), plus over-rotations on about {@code overRotatePct}% of the correct taps. Every plan is simulated tap by tap and only kept if the road connects
     * on the very last tap: the game ends the moment it connects, so later taps would never happen.
     *
     * @param tapCap most bad taps allowed (from the game's "N left" counter), or Integer.MAX_VALUE
     */
    public Plan plan(Board b, int[] turns, List<Integer> road, int tapDir, boolean firstAttempt, int tapCap) {
        List<Integer> need = new ArrayList<>();
        int correctTaps = 0;
        for (int i = 0; i < turns.length; i++) {
            int t = taps(turns[i], tapDir);
            if (t > 0) { need.add(i); correctTaps += t; }
        }
        List<String> none = new ArrayList<>();
        if (need.isEmpty()) return new Plan(new ArrayList<>(), b.masks.clone(), 0, none);
        Set<Integer> roadSet = new HashSet<>(road);

        // start habit (humanize only): leprechaun end / gold end / rare stray tap
        Start start = on() ? pickStart(cfg.mistakes && firstAttempt) : Start.LEPRECHAUN;
        boolean fromGold = start == Start.GOLD;
        int stray = start == Start.STRAY ? pickStray(b, roadSet, road, need) : -1;
        if (start == Start.STRAY && stray < 0) start = Start.LEPRECHAUN;

        if (!cfg.mistakes || !firstAttempt) {
            List<Action> base = base(road, need, turns, tapDir, fromGold);
            Plan plain = new Plan(base, simulate(b, base, tapDir, false), 0, none);
            plain.summary = cfg.mistakes ? "retry, no mistakes" : "mistakes off";
            return plain;
        }

        int cap = Math.max(0, tapCap);
        boolean straightMap = isStraightLine(b);
        int cleanPct = straightMap ? 100 : cleanPct(b, correctTaps);
        boolean clean = rnd.nextInt(100) < cleanPct;
        int wantWrong = straightMap ? 0 : pickWrongTaps(b, clean, cap);   // never a wrong tile on straight maps
        // taps that can slip: all but the last tile's (that one connects the road), and only on shapes that change
        int lastTaps = 0;
        {
            List<Action> probe = base(road, need, turns, tapDir, fromGold);
            Action lastA = probe.get(probe.size() - 1);
            lastTaps = lastA.taps;
            for (Action a : probe) if (period(b.masks[a.cell]) == 1) lastTaps += a.taps;
        }
        int wantOver;
        if (straightMap)   // straight-line maps: 95% of rounds spot on, the rest over-rotate one tile and fix it
            wantOver = correctTaps - lastTaps > 0 && cap >= 2 && rnd.nextInt(1000) < cfg.overRotatePct * 10 ? 1 : 0;
        else
            wantOver = pickOverRotations(correctTaps, correctTaps - lastTaps, cap - wantWrong, false);
        if (straightMap) { start = start == Start.STRAY ? Start.LEPRECHAUN : start; }
        String head = straightMap ? "straight-line map"
                : b.rows + "x" + b.cols + ", clean chance " + cleanPct + "% -> " + (clean ? "clean" : "messy");

        List<Action> fallback = null;
        for (int attempt = 0; attempt < 150; attempt++) {
            // fresh order each attempt: some orders connect a side road early whatever we do
            List<Action> p = base(road, need, turns, tapDir, fromGold, attempt > 0);
            if (fallback == null) fallback = copy(p);
            List<String> done = new ArrayList<>();
            // later attempts settle for fewer mistakes
            int wrongTarget = attempt < 50 ? wantWrong : attempt < 100 ? (wantWrong + 1) / 2 : Math.min(wantWrong, 1);
            int overTarget = attempt < 100 ? wantOver : 0;
            int wrong = 0, over = 0, overTaps = 0, guard = 0;
            if (start == Start.STRAY && wrongTarget > 0) wrong = 1;   // the stray opening tap is one of them
            while (wrong < wrongTarget && guard++ < 40) {
                int added = addMistake(b, p, roadSet, turns, tapDir, wrongTarget - wrong, done, false);
                if (added <= 0) break;
                wrong += added;
            }
            for (int k = 0; k < overTarget; k++) {
                int extra = addOverRotation(b, p, done);
                if (extra > 0) { over++; overTaps += extra; }
            }
            if (start == Start.STRAY && wrongTarget > 0)
                p.add(0, new Action(stray, 1, b.masks[stray] == 0 ? "grass" : "wrong_tile"));
            int[] fin = simulate(b, p, tapDir, true);
            if (fin != null) {
                Plan plan = new Plan(p, fin, wrong + overTaps, done);
                plan.summary = head + ": " + wrong + " wrong tap" + (wrong == 1 ? "" : "s")
                        + (over > 0 ? ", " + over + " over-rotation" + (over == 1 ? "" : "s") : "");
                return plan;
            }
        }
        Plan plain = new Plan(fallback, simulate(b, fallback, tapDir, false), 0, none);
        plain.summary = head + ": couldn't fit mistakes on this board";
        return plain;
    }

    /**
     * Turns a correct tile one step too far, then on round to where it should be: +4 taps for most
     * shapes, +2 for a straight (it looks the same every two turns). Returns the extra taps, 0 if none fitted.
     */
    int addOverRotation(Board b, List<Action> p, List<String> done) {
        int last = p.size() - 1;
        List<Integer> tiles = new ArrayList<>();
        for (int i = 0; i < last; i++)
            if (p.get(i).kind.equals("tile") && period(b.masks[p.get(i).cell]) > 1) tiles.add(i);
        if (tiles.isEmpty()) return 0;
        int i = tiles.get(rnd.nextInt(tiles.size()));
        Action a = p.get(i);
        int per = period(b.masks[a.cell]);
        p.add(i + 1, new Action(a.cell, 1, "overshoot"));
        p.add(i + 2, new Action(a.cell, per - 1, "correction"));
        done.add("over-rotated " + name(b, a.cell));
        return per;
    }

    /**
     * Taps for each tile that needs it, in habit order. A lone tile needing several turns is split
     * (first turn, then the rest) so bad taps can fit after the first tap.
     */
    List<Action> base(List<Integer> road, List<Integer> need, int[] turns, int tapDir, boolean fromGold) {
        return base(road, need, turns, tapDir, fromGold, false);
    }

    /** @param vary shuffle neighbouring tiles a little even with humanize off (used when retrying a plan) */
    List<Action> base(List<Integer> road, List<Integer> need, int[] turns, int tapDir, boolean fromGold, boolean vary) {
        List<Action> base = new ArrayList<>();
        for (int idx : order(road, need, fromGold, vary)) base.add(new Action(idx, taps(turns[idx], tapDir), "tile"));
        if ((on() || cfg.mistakes) && base.size() == 1 && base.get(0).taps >= 2) {
            Action a = base.get(0);
            base.add(new Action(a.cell, a.taps - 1, "tile"));
            a.taps = 1;
        }
        return base;
    }

    /** A block for the rare stray first tap: off the route (road tile or grass), not a start tile. */
    int pickStray(Board b, Set<Integer> roadSet, List<Integer> road, List<Integer> need) {
        List<Integer> cands = new ArrayList<>();
        for (int i = 0; i < b.masks.length; i++) {
            if (i == b.srcR * b.cols + b.srcC || i == b.dstR * b.cols + b.dstC) continue;
            if (roadSet.contains(i)) continue;
            cands.add(i);
        }
        return cands.isEmpty() ? -1 : cands.get(rnd.nextInt(cands.size()));
    }

    static List<Action> copy(List<Action> p) {
        List<Action> out = new ArrayList<>();
        for (Action a : p) out.add(new Action(a.cell, a.taps, a.kind));
        return out;
    }

    /**
     * Adds one mistake costing at most {@code remaining} bad taps; returns its cost (0 if none fitted).
     * The final action always stays final.
     */
    int addMistake(Board b, List<Action> p, Set<Integer> roadSet, int[] turns, int tapDir, int remaining,
                   List<String> done, boolean allowOver) {
        int last = p.size() - 1;
        List<Integer> offRoad = new ArrayList<>(), grass = new ArrayList<>(), settled = new ArrayList<>();
        for (int i = 0; i < b.masks.length; i++) {
            if (i == b.srcR * b.cols + b.srcC || i == b.dstR * b.cols + b.dstC) continue;
            if (b.masks[i] == 0) grass.add(i);
            else if (!roadSet.contains(i)) offRoad.add(i);
            else if (taps(turns[i], tapDir) == 0) settled.add(i);
        }
        // weights per kind, only for kinds that fit
        int wOver = allowOver && remaining >= 4 && last >= 1 ? 30 : 0;
        int wBounce = allowOver && remaining >= 4 && last >= 1 ? 10 : 0;
        int wBump = remaining >= 4 && !settled.isEmpty() ? 8 : 0;
        int wWrong = !offRoad.isEmpty() ? 37 : 0;
        int wGrass = !grass.isEmpty() ? 15 : 0;
        int total = wOver + wBounce + wBump + wWrong + wGrass;
        if (total == 0) return 0;
        int roll = rnd.nextInt(total);

        if (roll < wOver + wBounce) {
            // over-rotate a tile (1-3 steps, or an accidental double tap), then keep going round to
            // where it should be: always +4 taps in total
            boolean bounce = roll >= wOver;
            int i = rnd.nextInt(last);
            Action a = p.get(i);
            if (!a.kind.equals("tile")) return 0;
            int over = bounce ? 1 : 1 + rnd.nextInt(3);
            p.add(i + 1, new Action(a.cell, over, bounce ? "bounce" : "overshoot"));
            int fixAt = i + 2 + (rnd.nextInt(100) < 70 ? 0 : rnd.nextInt(Math.max(1, last - i)));
            p.add(Math.min(fixAt, p.size() - 1), new Action(a.cell, 4 - over, "correction"));
            done.add((bounce ? "double tap " : "over-rotated ") + name(b, a.cell));
            return 4;
        }
        roll -= wOver + wBounce;
        if (roll < wBump) {
            // bump a road tile that was already right, then turn it the rest of the way round (+4)
            int cell = nearPick(b, settled, p);
            int n = 1 + rnd.nextInt(3);
            int j = insertNear(b, p, cell, last);
            p.add(j, new Action(cell, n, "wrong_tile"));
            p.add(Math.min(j + 1 + rnd.nextInt(2), p.size() - 1), new Action(cell, 4 - n, "correction"));
            done.add("bumped " + name(b, cell));
            return 4;
        }
        roll -= wBump;
        if (roll < wWrong) {
            // tap a road tile that isn't part of the route, near where we're working (+1..3)
            int cell = nearPick(b, offRoad, p);
            int n = Math.min(remaining, 1 + (rnd.nextInt(100) < 35 ? 1 : 0) + (rnd.nextInt(100) < 10 ? 1 : 0));
            p.add(insertNear(b, p, cell, last), new Action(cell, n, "wrong_tile"));
            done.add("wrong tile " + name(b, cell) + (n > 1 ? " x" + n : ""));
            return n;
        }
        // tap a grass square next to the road (+1)
        int cell = nearPick(b, grass, p);
        p.add(insertNear(b, p, cell, last), new Action(cell, 1, "grass"));
        done.add("missed onto grass " + name(b, cell));
        return 1;
    }

    /** Splits a multi-tap tile so part of it is finished later (no extra taps). */
    void addRevisit(Board b, List<Action> p, List<String> done) {
        int last = p.size() - 1;
        List<Integer> multi = new ArrayList<>();
        for (int i = 0; i < last; i++) if (p.get(i).taps >= 2 && p.get(i).kind.equals("tile")) multi.add(i);
        if (multi.isEmpty()) return;
        int i = multi.get(rnd.nextInt(multi.size()));
        Action a = p.get(i);
        int first = 1 + rnd.nextInt(a.taps - 1);
        p.add(i + 1 + rnd.nextInt(last - i), new Action(a.cell, a.taps - first, "revisit"));
        a.taps = first;
        done.add("came back to " + name(b, a.cell));
    }

    /** Picks a candidate cell, favouring ones close to the tiles in the plan (mistakes happen where you look). */
    int nearPick(Board b, List<Integer> cands, List<Action> p) {
        double[] w = new double[cands.size()];
        double sum = 0;
        for (int k = 0; k < cands.size(); k++) {
            double d = minDist(b, cands.get(k), p);
            w[k] = 1.0 / (0.5 + d * d);
            sum += w[k];
        }
        double x = rnd.nextDouble() * sum;
        for (int k = 0; k < w.length; k++) if ((x -= w[k]) <= 0) return cands.get(k);
        return cands.get(cands.size() - 1);
    }

    /** Insertion index right after the planned tile nearest to {@code cell} (70%), else anywhere after the first tap and before the end. */
    int insertNear(Board b, List<Action> p, int cell, int last) {
        if (rnd.nextInt(100) < 70) {
            int best = -1;
            double bd = 1e9;
            for (int i = 0; i < last; i++) {
                double d = dist(b, p.get(i).cell, cell);
                if (d < bd) { bd = d; best = i; }
            }
            if (best >= 0) return best + 1;
        }
        return 1 + rnd.nextInt(Math.max(1, last));   // never before the first tap
    }

    static double minDist(Board b, int cell, List<Action> p) {
        double m = 1e9;
        for (Action a : p) m = Math.min(m, dist(b, a.cell, cell));
        return m;
    }

    static double dist(Board b, int a, int c) {
        return Math.hypot(a / b.cols - c / b.cols, a % b.cols - c % b.cols);
    }

    static String name(Board b, int cell) { return cell / b.cols + ":" + cell % b.cols; }

    /**
     * Applies the plan to a copy of the board. With {@code strict}, returns null if the road connects
     * before the final tap or isn't connected after it. Grass taps change nothing.
     */
    static int[] simulate(Board b, List<Action> plan, int tapDir, boolean strict) {
        int[] m = b.masks.clone();
        int total = 0;
        for (Action a : plan) total += a.taps;
        int done = 0;
        for (Action a : plan)
            for (int t = 0; t < a.taps; t++) {
                if (m[a.cell] != 0) m[a.cell] = tapDir >= 0 ? Solver.rotCw(m[a.cell]) : Solver.rotCcw(m[a.cell]);
                done++;
                if (strict && done < total && connected(b, m)) return null;
            }
        if (strict && !connected(b, m)) return null;
        return m;
    }

    public static boolean connected(Board b, int[] masks) {
        Board c = new Board(b.left, b.top, b.size, b.size, b.rows, b.cols);
        c.masks = masks;
        c.srcR = b.srcR; c.srcC = b.srcC; c.dstR = b.dstR; c.dstC = b.dstC;
        Solver s = new Solver(c, 1);
        return s.solve() != null && s.bestTaps() == 0;
    }

    static int taps(int cwTurns, int tapDir) { return tapDir >= 0 ? cwTurns : (4 - cwTurns) % 4; }

    // ---------------------------------------------------------------- helpers
    int range(int lo, int hi) {
        if (hi <= lo) return lo;
        return lo + rnd.nextInt(hi - lo + 1);
    }

    double logNormal(double median, double sigma) { return median * Math.exp(rnd.nextGaussian() * sigma); }

    /**
     * Log-normal sample kept inside [lo, hi] with soft edges: values past a bound land a random amount
     * inside it instead of piling up on the bound (a pile-up at one exact value is itself a tell).
     */
    long logNormal(double median, double sigma, long lo, long hi) {
        long v = Math.round(logNormal(median, sigma));
        long room = Math.max(1, (hi - lo) / 5);
        if (v < lo) v = lo + (long) (rnd.nextDouble() * Math.min(room, Math.max(1, lo / 3)));
        if (v > hi) v = hi - (long) (rnd.nextDouble() * room);
        return Math.max(lo, Math.min(hi, v));
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
