package com.kiwyu.terraminebot;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds tile rotations that make a continuous road from the leprechaun's cell to the pot of gold's
 * cell, using as few taps as possible (the game limits taps). Tiles off the road are left alone.
 * Masks: N=1, E=2, S=4, W=8.
 */
public class Solver {
    static final int[] DR = {-1, 0, 1, 0};
    static final int[] DC = {0, 1, 0, -1};

    public static int rotCw(int m) { return ((m << 1) | (m >> 3)) & 15; }
    public static int rotCcw(int m) { return ((m >> 1) | (m << 3)) & 15; }
    public static int rotCw(int m, int k) { for (int i = 0; i < k; i++) m = rotCw(m); return m; }
    static boolean has(int m, int side) { return (m & (1 << side)) != 0; }

    final Board b;
    final int tapDir;
    final boolean[] visited;
    final int[] turns, bestTurns;
    int cost, bestCost = Integer.MAX_VALUE;
    long budget = 3_000_000;

    public Solver(Board b, int tapDir) {
        this.b = b;
        this.tapDir = tapDir >= 0 ? 1 : -1;
        int n = b.rows * b.cols;
        visited = new boolean[n];
        turns = new int[n];
        bestTurns = new int[n];
    }

    /** Taps needed for k clockwise quarter turns. */
    int taps(int k) { return tapDir > 0 ? k : (4 - k) % 4; }

    /** @return clockwise quarter turns per cell, or null if no road exists. */
    public int[] solve() {
        if (!b.ready()) return null;
        for (int s = 0; s < 4; s++)
            step(b.srcR + DR[s], b.srcC + DC[s], (s + 2) % 4);
        return bestCost == Integer.MAX_VALUE ? null : bestTurns.clone();
    }

    public int bestTaps() { return bestCost; }

    /** The road enters cell (r,c) through side {@code in}. */
    void step(int r, int c, int in) {
        if (r < 0 || c < 0 || r >= b.rows || c >= b.cols || --budget < 0) return;
        if (r == b.dstR && c == b.dstC) {
            if (cost < bestCost) { bestCost = cost; System.arraycopy(turns, 0, bestTurns, 0, turns.length); }
            return;
        }
        int idx = r * b.cols + c;
        int base = b.masks[idx];
        if (visited[idx] || base == 0) return;
        visited[idx] = true;
        int seen = 0;
        for (int k = 0; k < 4; k++) {
            int m = rotCw(base, k);
            if ((seen & (1 << m)) != 0 || !has(m, in)) continue;
            seen |= 1 << m;
            int t = taps(k);
            if (cost + t >= bestCost) continue;
            turns[idx] = k;
            cost += t;
            for (int out = 0; out < 4; out++)
                if (out != in && has(m, out)) step(r + DR[out], c + DC[out], (out + 2) % 4);
            cost -= t;
            turns[idx] = 0;
        }
        visited[idx] = false;
    }

    public static String maskName(int m) {
        StringBuilder sb = new StringBuilder();
        if (has(m, 0)) sb.append('N');
        if (has(m, 1)) sb.append('E');
        if (has(m, 2)) sb.append('S');
        if (has(m, 3)) sb.append('W');
        return sb.length() == 0 ? "." : sb.toString();
    }

    public static List<String> describe(Board b) {
        List<String> lines = new ArrayList<>();
        for (int r = 0; r < b.rows; r++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < b.cols; c++) {
                String s = r == b.srcR && c == b.srcC ? "LEP" : r == b.dstR && c == b.dstC ? "POT"
                        : maskName(b.masks[r * b.cols + c]);
                sb.append(String.format("%-5s", s));
            }
            lines.add(sb.toString().trim());
        }
        return lines;
    }
}
