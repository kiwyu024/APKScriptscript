package com.kiwyu.terraminebot;

import android.graphics.Bitmap;

/** A screenshot copied into an int[] for fast pixel reads. */
public class Frame {
    public final int w, h;
    public final int[] px;
    public final Bitmap bitmap;

    public Frame(Bitmap b) {
        bitmap = b;
        w = b.getWidth();
        h = b.getHeight();
        px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
    }

    /** For desktop tests: raw ARGB pixels without a Bitmap. */
    public Frame(int w, int h, int[] px) {
        this.bitmap = null; this.w = w; this.h = h; this.px = px;
    }

    public int get(int x, int y) {
        if (x < 0) x = 0; else if (x >= w) x = w - 1;
        if (y < 0) y = 0; else if (y >= h) y = h - 1;
        return px[y * w + x];
    }

    public static int r(int p) { return (p >> 16) & 255; }
    public static int g(int p) { return (p >> 8) & 255; }
    public static int b(int p) { return p & 255; }

    /** Average luminance-ish brightness (mean of R,G,B). */
    public int lum(int x, int y) {
        int p = get(x, y);
        return (r(p) + g(p) + b(p)) / 3;
    }
}
