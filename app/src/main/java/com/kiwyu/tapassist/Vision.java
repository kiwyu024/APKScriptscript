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

    // ------------------------------------------------------- mine screen (recovery)
    static boolean mineBrown(int p) {
        return Math.abs(Frame.r(p) - 139) < 22 && Math.abs(Frame.g(p) - 69) < 22 && Frame.b(p) < 50;
    }

    /** Fraction of a row (sampled) that is the Gold Mine header brown. */
    static float brownRow(Frame f, int y) {
        int n = 0, hit = 0;
        for (int x = 0; x < f.w; x += 4) { n++; if (mineBrown(f.get(x, y))) hit++; }
        return hit / (float) n;
    }

    /** The Gold Mine screen (the one with ENTER MINE): its brown header bar across the top. */
    public static boolean isMineScreen(Frame f) {
        return brownRow(f, Math.round(f.h * 0.045f)) > 0.80f && brownRow(f, Math.round(f.h * 0.088f)) > 0.80f;
    }

    /** The orange-brown ENTER MINE button: {x, y} or null. */
    public static int[] findEnterMine(Frame f) {
        int first = -1, last = -1, rows = 0;
        long sumMid = 0;
        int prevY = -1000;
        int[] best = null;
        int bestRows = 0;
        for (int y = Math.round(f.h * 0.15f); y < Math.round(f.h * 0.93f); y += 2) {
            // longest run of button brown in this row
            int run = 0, bestRun = 0, bestEnd = -1;
            for (int x = 0; x < f.w; x++) {
                if (mineBrown(f.px[y * f.w + x])) { run++; if (run > bestRun) { bestRun = run; bestEnd = x; } }
                else run = 0;
            }
            boolean ok = false;
            int x0 = bestEnd - bestRun + 1;
            if (bestRun >= f.w * 0.10f && bestRun <= f.w * 0.48f) {
                // a button: dark panel on both sides (not a full-width header bar)
                int l = f.get(Math.max(0, x0 - 12), y), r = f.get(Math.min(f.w - 1, bestEnd + 12), y);
                ok = Frame.r(l) < 100 && Frame.r(r) < 100;
            }
            if (!ok) continue;
            if (y - prevY > 40) {   // new cluster
                if (rows > bestRows && last - first >= f.h * 0.03f) { bestRows = rows; best = new int[]{(int) (sumMid / rows), (first + last) / 2}; }
                first = y; rows = 0; sumMid = 0;
            }
            last = y; prevY = y; rows++;
            sumMid += x0 + (bestEnd - x0) / 2;
        }
        if (rows > bestRows && last - first >= f.h * 0.03f) best = new int[]{(int) (sumMid / rows), (first + last) / 2};
        return best;
    }

    /** The grey "Name Your Mine!" style dialog: position of its teal GOT IT! button, or null. */
    public static int[] findGotIt(Frame f) {
        int cx = f.w / 2, run = 0, bestRun = 0, bestEnd = -1;
        for (int y = 0; y < f.h; y++) {
            int p = f.get(cx, y);
            boolean grey = Math.abs(Frame.r(p) - 66) < 12 && Math.abs(Frame.g(p) - 66) < 12 && Math.abs(Frame.b(p) - 66) < 12;
            if (grey) { run++; if (run > bestRun) { bestRun = run; bestEnd = y; } }
            else run = 0;
        }
        if (bestRun < f.h * 0.08f) return null;
        int top = bestEnd - bestRun + 1, bottom = bestEnd;
        // dialog must be wide
        int mid = (top + bottom) / 2, wide = 0;
        for (int x = 0; x < f.w; x += 2) {
            int p = f.get(x, mid);
            if (Math.abs(Frame.r(p) - 66) < 12 && Math.abs(Frame.g(p) - 66) < 12 && Math.abs(Frame.b(p) - 66) < 12) wide += 2;
        }
        if (wide < f.w * 0.75f) return null;
        // teal button text in the lower right of the dialog
        long sx = 0, sy = 0;
        int n = 0;
        for (int y = Math.max(top, bottom - Math.round(f.h * 0.07f)); y <= bottom; y++)
            for (int x = f.w / 2; x < f.w; x++) {
                int p = f.px[y * f.w + x];
                if (Frame.g(p) > 150 && Frame.b(p) > 150 && Frame.r(p) < 170 && Frame.g(p) - Frame.r(p) > 30) { sx += x; sy += y; n++; }
            }
        if (n < 30) return null;
        return new int[]{(int) (sx / n), (int) (sy / n)};
    }

    // ------------------------------------------------------- ad "next / skip" disc
    /**
     * A round white button with a dark symbol inside (e.g. the "->" arrow some ads show before their X)
     * in either top corner. Returns {score, x, y}; score 0 if none.
     */
    public static int[] findCornerDisc(Frame f) {
        int[] best = {0, 0, 0};
        int yTop = Math.round(f.h * 0.025f), yBot = Math.round(f.h * 0.12f);
        int step = Math.max(2, f.w / 270);
        for (float rf : new float[]{0.020f, 0.026f, 0.032f}) {
            int R = Math.round(f.w * rf);
            int[][] ranges = {{R + 2, Math.round(f.w * 0.22f)}, {Math.round(f.w * 0.78f), f.w - R - 2}};
            for (int[] rg : ranges)
                for (int y = yTop + R; y < yBot - R; y += step)
                    for (int x = rg[0]; x < rg[1]; x += step) {
                        // ring at 0.85R must be white all round
                        int ringMin = 255;
                        for (int k = 0; k < 16 && ringMin > 200; k++) {
                            double a = k * Math.PI / 8;
                            int p = f.get(x + (int) Math.round(Math.cos(a) * R * 0.85), y + (int) Math.round(Math.sin(a) * R * 0.85));
                            int lo = Math.min(Frame.r(p), Math.min(Frame.g(p), Frame.b(p)));
                            ringMin = Math.min(ringMin, lo);
                        }
                        if (ringMin <= 200) continue;
                        // just outside the disc should be noticeably darker somewhere (it's a button, not a white area)
                        int outsideDark = 0;
                        for (int k = 0; k < 16; k++) {
                            double a = k * Math.PI / 8;
                            int l = f.lum(x + (int) Math.round(Math.cos(a) * R * 1.35), y + (int) Math.round(Math.sin(a) * R * 1.35));
                            if (l < 170) outsideDark++;
                        }
                        if (outsideDark < 10) continue;
                        // a dark symbol inside
                        int n = 0, dark = 0, r2 = Math.round(R * 0.55f);
                        for (int yy = y - r2; yy <= y + r2; yy += 2)
                            for (int xx = x - r2; xx <= x + r2; xx += 2) {
                                if ((xx - x) * (xx - x) + (yy - y) * (yy - y) > r2 * r2) continue;
                                n++;
                                if (f.lum(xx, yy) < 90) dark++;
                            }
                        if (n == 0) continue;
                        float frac = dark / (float) n;
                        if (frac < 0.06f || frac > 0.55f) continue;
                        int score = Math.round(100 * frac) + outsideDark;
                        if (score > best[0]) { best[0] = score; best[1] = x; best[2] = y; }
                    }
        }
        return best;
    }

    static boolean pillGrey(int p) {
        int r = Frame.r(p), g = Frame.g(p), b = Frame.b(p);
        return r >= 190 && r <= 232 && g >= 190 && g <= 232 && b >= r && b - r <= 22 && Math.abs(r - g) <= 8;
    }

    /**
     * The light grey "Continue ▷|" pill some ads show (in the corners where the X usually is): a grey rounded bar with dark text and
     * a white circle (the skip icon) at its right end. Returns {score, x, y} of the white circle; score 0 if none.
     */
    public static int[] findContinuePill(Frame f) {
        int[] none = {0, 0, 0};
        int y0 = Math.round(f.h * 0.01f), y1 = Math.round(f.h * 0.985f);   // anywhere: it shows where the X usually is
        int minRun = Math.round(f.w * 0.06f), gap = Math.round(f.h * 0.035f);
        int bandStart = -1, bandEnd = -1, bandL = f.w, bandR = 0, rows = 0;
        java.util.List<int[]> bands = new java.util.ArrayList<>();
        for (int y = y0; y < y1; y += 2) {
            int run = 0, bestRun = 0, bestEnd = -1;
            for (int x = 0; x < f.w; x++) {
                if (pillGrey(f.px[y * f.w + x])) { run++; if (run > bestRun) { bestRun = run; bestEnd = x; } }
                else run = 0;
            }
            if (bestRun >= minRun && bestRun < f.w * 0.97f) {
                int l = bestEnd - bestRun + 1;
                if (bandStart < 0 || y - bandEnd > gap) {   // the text rows split the grey: bridge them
                    if (bandStart >= 0) bands.add(new int[]{bandStart, bandEnd, bandL, bandR, rows});
                    bandStart = y; bandL = l; bandR = bestEnd; rows = 0;
                }
                bandEnd = y; rows++;
                bandL = Math.min(bandL, l); bandR = Math.max(bandR, bestEnd);
            }
        }
        if (bandStart >= 0) bands.add(new int[]{bandStart, bandEnd, bandL, bandR, rows});
        for (int[] b : bands) {
            int top = b[0], bot = b[1], hgt = bot - top;
            if (hgt < f.h * 0.018f || hgt > f.h * 0.10f) continue;
            int cy = (top + bot) / 2;
            // the white circle: a run of near-white pixels on the middle row, just right of the grey
            int wl = -1, wr = -1;
            for (int x = Math.max(0, b[3] - hgt * 2); x < Math.min(f.w, b[3] + hgt * 2); x++) {
                int p = f.get(x, cy);
                boolean white = Frame.r(p) > 238 && Frame.g(p) > 238 && Frame.b(p) > 238;
                // the icon inside interrupts the white: allow gaps up to half the pill's height
                if (white) { if (wl < 0) wl = x; wr = x; }
                else if (wl >= 0 && x - wr > hgt / 2) break;
            }
            if (wl < 0 || wr - wl < hgt * 0.45f || wr - wl > hgt * 1.3f) continue;
            // a dark symbol inside the circle and dark text on the grey to its left
            int cx = (wl + wr) / 2, dk = 0, txt = 0;
            for (int dy = -hgt / 4; dy <= hgt / 4; dy += 2)
                for (int dx = -hgt / 4; dx <= hgt / 4; dx += 2) {
                    int p = f.get(cx + dx, cy + dy);
                    if (Frame.r(p) < 90 && Frame.g(p) < 90 && Frame.b(p) < 90) dk++;
                }
            for (int x = b[2]; x < wl; x += 2) {
                int p = f.get(x, cy);
                if (Frame.r(p) < 90 && Frame.g(p) < 90 && Frame.b(p) < 90) txt++;
            }
            if (dk >= 2 && txt >= 3) return new int[]{1, cx, cy};
        }
        return none;
    }

    /** How far right of x0 the last dark pixel on row y is (within x0..x1), 0 if none. */
    static int rowExtent(Frame f, int x0, int x1, int y) {
        int last = -1;
        for (int x = x0; x <= x1; x++) if (neutralDark(f.get(x, y))) last = x;
        return last < 0 ? 0 : last - x0 + 1;
    }

    static boolean neutralDark(int p) {
        int r = Frame.r(p), g = Frame.g(p), b = Frame.b(p);
        return r < 110 && g < 110 && b < 110 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 30;
    }

    /**
     * A small dark "▷|" skip icon (triangle + bar) sitting alone on a light background in a top corner,
     * where the X usually is - for ads whose skip circle is almost the same colour as the page.
     * Returns {score, x, y}; score 0 if none.
     */
    public static int[] findCornerSkipIcon(Frame f) {
        int[] none = {0, 0, 0};
        int y0 = Math.round(f.h * 0.045f), y1 = Math.round(f.h * 0.10f);   // below the status bar, the X band
        int minS = Math.round(f.w * 0.012f), maxS = Math.round(f.w * 0.05f);
        int[][] ranges = {{Math.round(f.w * 0.75f), f.w - 2}, {1, Math.round(f.w * 0.25f)}};
        boolean[] seen = new boolean[f.w * f.h];
        for (int[] xr : ranges)
            for (int y = y0; y < y1; y += 2)
                for (int x = xr[0]; x < xr[1]; x += 2) {
                    int i = y * f.w + x;
                    if (seen[i] || !neutralDark(f.px[i])) continue;
                    // flood the dark blob (bounded)
                    int minX = x, maxX = x, minY = y, maxY = y, count = 0;
                    java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
                    q.add(i); seen[i] = true;
                    while (!q.isEmpty() && count < 6000) {
                        int c = q.poll(), cx = c % f.w, cy = c / f.w;
                        count++;
                        minX = Math.min(minX, cx); maxX = Math.max(maxX, cx); minY = Math.min(minY, cy); maxY = Math.max(maxY, cy);
                        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                        for (int[] d : nb) {
                            int nx = cx + d[0], ny = cy + d[1];
                            if (nx < 0 || ny < 0 || nx >= f.w || ny >= f.h) continue;
                            int ni = ny * f.w + nx;
                            if (!seen[ni] && neutralDark(f.px[ni])) { seen[ni] = true; q.add(ni); }
                        }
                    }
                    int bw = maxX - minX + 1, bh = maxY - minY + 1;
                    if (bw < minS || bh < minS || bw > maxS || bh > maxS) continue;
                    float fill = count / (float) (bw * bh);
                    if (fill < 0.15f || fill > 0.75f) continue;
                    // the "▷|" shape: this blob is the triangle (straight left edge, widest in the middle,
                    // narrow at top and bottom), with a thin dark bar just to its right
                    if (bw < bh * 0.5f) continue;
                    int leftRows = 0;
                    for (int by = minY; by <= maxY; by++) {
                        boolean d = false;
                        for (int bx = minX; bx <= minX + Math.max(2, bw / 4) && !d; bx++) d = neutralDark(f.get(bx, by));
                        if (d) leftRows++;
                    }
                    if (leftRows < bh * 0.8f) continue;
                    if (rowExtent(f, minX, maxX, (minY + maxY) / 2) < bw * 0.75f) continue;
                    if (rowExtent(f, minX, maxX, minY + Math.max(1, bh / 8)) > bw * 0.5f) continue;
                    if (rowExtent(f, minX, maxX, maxY - Math.max(1, bh / 8)) > bw * 0.5f) continue;
                    boolean bar = false;
                    for (int bx = maxX + 1; bx <= maxX + bw && bx < f.w && !bar; bx++) {
                        int dark = 0;
                        for (int by = minY; by <= maxY; by++) if (neutralDark(f.get(bx, by))) dark++;
                        if (dark >= bh * 0.7f) {
                            int wdt = 0;
                            while (bx + wdt < f.w && wdt <= bw && neutralDark(f.get(bx + wdt, (minY + maxY) / 2))) wdt++;
                            bar = wdt <= Math.max(3, bw * 0.45f);
                        }
                    }
                    if (!bar) continue;
                    // alone on a light background: a ring around it is almost all light
                    int cxm = (minX + maxX) / 2, cym = (minY + maxY) / 2, r = Math.max(bw, bh);
                    int light = 0, n = 0;
                    for (int a = 0; a < 32; a++) {
                        double t = a * Math.PI / 16;
                        int px = (int) Math.round(cxm + Math.cos(t) * r * 1.3), py = (int) Math.round(cym + Math.sin(t) * r * 1.3);
                        if (px < 0 || py < 0 || px >= f.w || py >= f.h) continue;
                        n++;
                        if (f.lum(px, py) > 215) light++;
                    }
                    if (n >= 20 && light >= n * 0.9f) return new int[]{1, cxm, cym};
                }
        return none;
    }
}
