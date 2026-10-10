package com.kiwyu.tapassist;

import java.util.Arrays;

/**
 * Screen recognition for TerraMine Gold Rush, tuned on 1080x2340 recordings from the game but
 * written in proportions of the screen / board so other resolutions work too.
 */
public class Vision {

    // ------------------------------------------------------------ board frame
    static boolean frameYellow(int p) {
        return Frame.r(p) > 230 && Frame.g(p) > 190 && Frame.b(p) < 90;
    }

    /** Finds the square board inside its yellow frame; falls back to the manual grid; null if absent. */
    public static Board findBoard(Frame f, Config cfg) {
        Board b = autoBoard(f, cfg);
        if (b != null || !cfg.manualGrid()) return b;
        float px = (cfg.brX - cfg.tlX) / (float) (cfg.cols - 1), py = (cfg.brY - cfg.tlY) / (float) (cfg.rows - 1);
        return new Board(Math.round(cfg.tlX - px / 2), Math.round(cfg.tlY - py / 2),
                Math.round(px * cfg.cols), Math.round(py * cfg.rows), cfg.rows, cfg.cols);
    }

    static Board autoBoard(Frame f, Config cfg) {
        for (float fy : new float[]{0.45f, 0.40f, 0.50f, 0.35f}) {
            int y = Math.round(f.h * fy);
            int left = -1, lt = 0;
            for (int x = 0; x < f.w * 0.2f; x++) {
                if (frameYellow(f.get(x, y))) {
                    int run = 0;
                    while (x + run < f.w && frameYellow(f.get(x + run, y))) run++;
                    if (run >= 3 && run <= f.w / 50) { left = x; lt = run; }
                    break;
                }
            }
            int right = -1;
            for (int x = f.w - 1; x > f.w * 0.8f; x--) {
                if (frameYellow(f.get(x, y))) {
                    int run = 0;
                    while (x - run >= 0 && frameYellow(f.get(x - run, y))) run++;
                    if (run >= 3 && run <= f.w / 50) right = x;
                    break;
                }
            }
            if (left < 0 || right < 0) continue;
            int outer = right - left + 1;
            if (outer < f.w * 0.6f) continue;
            // vertical extent of the left frame line
            int xc = left + lt / 2, best = 0, bestEnd = -1, run = 0;
            for (int yy = 0; yy < f.h; yy++) {
                if (frameYellow(f.get(xc, yy))) { run++; if (run > best) { best = run; bestEnd = yy; } }
                else run = 0;
            }
            if (best < outer * 0.8f) continue;
            int cyMid = bestEnd - best / 2;
            int inner = outer - 2 * lt;
            int bl = left + lt, bt = cyMid - inner / 2;
            int n = gridCount(f, bl, bt, inner, cfg.cols);
            return new Board(bl, bt, inner, inner, n, n);
        }
        return null;
    }

    /**
     * Number of tiles per side (4, 5, 6 or 7). Tiles are separated by
     * darker grass seams, so for each candidate n we compare brightness at the n-1 seam lines with
     * brightness at the tile middles; the right n has dark seams and bright middles.
     */
    static int gridCount(Frame f, int left, int top, int size, int fallback) {
        if (size < 40) return fallback;
        float[] colP = new float[size], rowP = new float[size];
        int step = Math.max(1, size / 250);
        for (int y = 0; y < size; y += step)          // column profile: every x, sampled rows
            for (int x = 0; x < size; x++) colP[x] += f.lum(left + x, top + y);
        for (int x = 0; x < size; x += step)          // row profile: every y, sampled columns
            for (int y = 0; y < size; y++) rowP[y] += f.lum(left + x, top + y);
        int best = fallback;
        float bestScore = 10;   // below this the seams aren't clear; keep the default
        for (int n = 4; n <= 7; n++) {   // the game uses 4x4, 5x5, 6x6 and 7x7 boards
            float sc = seamScore(colP, n) + seamScore(rowP, n);
            if (sc > bestScore) { bestScore = sc; best = n; }
        }
        return best;
    }

    static float seamScore(float[] p, int n) {
        int len = p.length, w = Math.max(2, len / 250);
        // normalise: profile values are sums over sampled lines; scale out by the mean
        float mean = 0;
        for (float v : p) mean += v;
        mean /= len;
        if (mean <= 0) return 0;
        float seams = 0, mids = 0;
        for (int k = 1; k < n; k++) {
            int x = Math.round(k * len / (float) n);
            float mn = Float.MAX_VALUE;
            for (int i = Math.max(0, x - 2 * w); i <= Math.min(len - 1, x + 2 * w); i++) mn = Math.min(mn, p[i]);
            seams += mn;
        }
        for (int k = 0; k < n; k++) {
            int x = Math.round((k + 0.5f) * len / (float) n);
            float sum = 0;
            int cnt = 0;
            for (int i = Math.max(0, x - 2 * w); i <= Math.min(len - 1, x + 2 * w); i++) { sum += p[i]; cnt++; }
            mids += sum / cnt;
        }
        // in "average luminance" units, like the prototype
        return (mids / n - seams / (n - 1)) / mean * 128f;
    }

    // ----------------------------------------------------------------- tiles
    /** Fills masks / leprechaun / pot. */
    public static void read(Frame f, Board b, Config cfg) {
        int n = b.rows * b.cols;
        b.masks = new int[n];
        b.scores = new int[n][4];
        b.srcR = b.srcC = b.dstR = b.dstC = -1;
        float bestGreen = 0, bestPot = 0;
        for (int r = 0; r < b.rows; r++)
            for (int c = 0; c < b.cols; c++) {
                float[] sp = special(f, b, r, c);   // dark, green, pale
                if (sp[2] > 0.06f && sp[0] > 0.08f) {
                    if (sp[0] + sp[2] > bestPot) { bestPot = sp[0] + sp[2]; b.dstR = r; b.dstC = c; }
                    continue;
                }
                if (sp[1] > 0.06f) {
                    if (sp[1] > bestGreen) { bestGreen = sp[1]; b.srcR = r; b.srcC = c; }
                    continue;
                }
                int m = 0;
                for (int side = 0; side < 4; side++) {
                    int s = stripe(f, b, r, c, side);
                    b.scores[r * b.cols + c][side] = s;
                    if (s >= cfg.edgeThreshold) m |= 1 << side;
                }
                b.masks[r * b.cols + c] = m;
            }
        if (b.srcR >= 0) b.masks[b.srcR * b.cols + b.srcC] = 0;
        if (b.dstR >= 0) b.masks[b.dstR * b.cols + b.dstC] = 0;
    }

    /** Fractions of dark, leprechaun-green and pale pixels in a cell. */
    static float[] special(Frame f, Board b, int r, int c) {
        int x = b.cx(c), y = b.cy(r), h = Math.round(b.pitch() * 0.42f);
        int step = Math.max(1, h / 40), n = 0, dark = 0, green = 0, pale = 0;
        for (int yy = y - h; yy < y + h; yy += step)
            for (int xx = x - h; xx < x + h; xx += step) {
                int p = f.get(xx, yy), R = Frame.r(p), G = Frame.g(p), B = Frame.b(p);
                n++;
                if (R + G + B < 150) dark++;
                if (G > R + 20 && G > B + 20 && R < 130) green++;
                if (R > 200 && G > 200 && B > 150) pale++;
            }
        return new float[]{dark / (float) n, green / (float) n, pale / (float) n};
    }

    /**
     * Is there a bright gold stripe running into the given side? Takes the band between 30% and 46%
     * of a tile from its centre, averages R+G across it, and compares the brightest pipe-wide
     * stripe with the band's median. Pipes are often drawn a little off-centre, so the stripe is
     * searched for across most of the tile width.
     */
    static int stripe(Frame f, Board b, int r, int c, int side) {
        float p = b.pitch();
        int x = b.cx(c), y = b.cy(r);
        int a = Math.round(p * 0.30f), bb = Math.round(p * 0.46f), span = Math.round(p * 0.32f);
        int sw = Math.max(2, Math.round(p * 0.04f));
        int len = 2 * span + 1;
        float[] prof = new float[len];
        boolean vertical = side == 0 || side == 2;
        int dir = (side == 0 || side == 3) ? -1 : 1;
        int d0 = Math.min(dir * a, dir * bb), d1 = Math.max(dir * a, dir * bb);
        for (int i = 0; i < len; i++) {
            int o = i - span;
            long sum = 0;
            int cnt = 0;
            for (int d = d0; d <= d1; d++) {
                int px = vertical ? f.get(x + o, y + d) : f.get(x + d, y + o);
                sum += Frame.r(px) + Frame.g(px);
                cnt++;
            }
            prof[i] = sum / (float) cnt;
        }
        float best = -1e9f;
        float win = 0;
        int ww = 2 * sw + 1;
        for (int i = 0; i < len; i++) {
            win += prof[i];
            if (i >= ww) win -= prof[i - ww];
            if (i >= ww - 1) best = Math.max(best, win / ww);
        }
        float[] sorted = prof.clone();
        Arrays.sort(sorted);
        return Math.round(best - sorted[len / 2]);
    }

    // ---------------------------------------------------------- replay button
    /**
     * Finds the orange "Watch Ad -> Play Level N" button or the yellow "Play Again" button below
     * {@code fromY}. Returns {x, y, halfWidth, halfHeight, type} (type 1 = Watch Ad, 0 = Play Again) or null. The grey-green Exit button never matches.
     */
    public static int[] findReplayButton(Frame f, int fromY) {
        int[] o = band(f, fromY, true);
        if (o != null) return o;
        return band(f, fromY, false);
    }

    /** True when the middle of {@code r} is mostly button yellow or orange (i.e. the button is really drawn). */
    public static boolean looksLikeButton(Frame f, android.graphics.Rect r) {
        return looksLikeButton(f, r.left, r.top, r.right, r.bottom);
    }

    public static boolean looksLikeButton(Frame f, int left, int top, int right, int bottom) {
        int w = right - left, h = bottom - top;
        int x0 = left + w / 5, x1 = right - w / 5;
        int y0 = top + h / 4, y1 = bottom - h / 4;
        if (x1 <= x0 || y1 <= y0) return false;
        int n = 0, hit = 0;
        int sx = Math.max(1, (x1 - x0) / 40), sy = Math.max(1, (y1 - y0) / 10);
        for (int y = y0; y < y1; y += sy)
            for (int x = x0; x < x1; x += sx) {
                int p = f.get(x, y);
                n++;
                if (yellow(p) || orange(p)) hit++;
            }
        return n > 0 && hit >= n * 0.30f;
    }

    static boolean orange(int p) {
        int R = Frame.r(p), G = Frame.g(p), B = Frame.b(p);
        return R > 225 && G > 120 && G < 190 && B < 90;
    }

    static boolean yellow(int p) {
        int R = Frame.r(p), G = Frame.g(p), B = Frame.b(p);
        return R > 235 && G > 190 && G < 235 && B < 60;
    }

    static int[] band(Frame f, int fromY, boolean wantOrange) {
        float minW = (wantOrange ? 0.25f : 0.18f) * f.w;
        int minRun = Math.max(8, Math.round(f.h * 0.012f));
        int run = 0, bestRun = 0, bestEnd = -1;
        int y1 = Math.round(f.h * 0.93f);
        for (int y = Math.max(0, fromY); y < y1; y++) {
            int cnt = 0;
            for (int x = 0; x < f.w; x += 2) {
                int p = f.px[y * f.w + x];
                if (wantOrange ? orange(p) : yellow(p)) cnt++;
            }
            if (cnt * 2 > minW) { run++; if (run > bestRun) { bestRun = run; bestEnd = y; } }
            else run = 0;
        }
        if (bestRun < minRun) return null;
        int yc = bestEnd - bestRun / 2;
        long sx = 0;
        int n = 0, minX = f.w, maxX = 0;
        for (int x = 0; x < f.w; x++) {
            int p = f.px[yc * f.w + x];
            if (wantOrange ? orange(p) : yellow(p)) { sx += x; n++; minX = Math.min(minX, x); maxX = Math.max(maxX, x); }
        }
        if (n == 0) return null;
        return new int[]{(int) (sx / n), yc, Math.max(1, (maxX - minX) / 2), Math.max(1, bestRun / 2), wantOrange ? 1 : 0};
    }

    // ------------------------------------------------------------- ad close X
    /**
     * Looks for an "X" glyph in both top corners: two uniform diagonal strokes that contrast with
     * the uniform background between the arms. Returns {score, x, y}.
     */
    public static int[] findCloseX(Frame f) {
        int a = Math.max(6, Math.round(f.w * 0.014f));
        int st = Math.max(2, f.w / 360);
        int[] best = {Integer.MIN_VALUE, 0, 0};
        int[][] ranges = {{Math.round(f.w * 0.78f), f.w - a - 2}, {a + 2, Math.round(f.w * 0.22f)}};
        int yTop = Math.round(f.h * 0.03f), yBot = Math.round(f.h * 0.12f);
        float[] d = new float[4 * a];
        float[] o = new float[8];
        for (int[] rg : ranges)
            for (int y = yTop; y < yBot; y += st)
                for (int x = rg[0]; x < rg[1]; x += st) {
                    int k = 0;
                    for (int t = -a; t <= a; t++) {
                        if (t == 0) continue;
                        d[k++] = f.lum(x + t, y + t);
                        d[k++] = f.lum(x - t, y + t);
                    }
                    o[0] = f.lum(x, y - a); o[1] = f.lum(x, y + a); o[2] = f.lum(x - a, y); o[3] = f.lum(x + a, y);
                    o[4] = f.lum(x, y - a / 2); o[5] = f.lum(x, y + a / 2);
                    o[6] = f.lum(x - a / 2, y); o[7] = f.lum(x + a / 2, y);
                    float s = Math.abs(mean(d, k) - mean(o, 8)) - std(d, k) - std(o, 8);
                    if (s > best[0]) { best[0] = Math.round(s); best[1] = x; best[2] = y; }
                }
        return best;
    }

    static float mean(float[] v, int n) { float s = 0; for (int i = 0; i < n; i++) s += v[i]; return s / n; }

    static float std(float[] v, int n) {
        float m = mean(v, n), s = 0;
        for (int i = 0; i < n; i++) s += (v[i] - m) * (v[i] - m);
        return (float) Math.sqrt(s / n);
    }
}
