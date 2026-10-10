package com.kiwyu.terraminebot;

/** Geometry of the puzzle board on screen plus what was read from it. */
public class Board {
    public final int left, top, size, rows, cols;
    public final float pitchX, pitchY;
    public int[] masks;           // openings per cell (N=1 E=2 S=4 W=8), 0 for grass / special cells
    public int[][] scores;        // [cell][side] stripe scores, for debugging
    public int srcR = -1, srcC = -1;  // leprechaun cell
    public int dstR = -1, dstC = -1;  // pot of gold cell

    public Board(int left, int top, int width, int height, int rows, int cols) {
        this.left = left; this.top = top; this.size = Math.min(width, height);
        this.rows = rows; this.cols = cols;
        pitchX = width / (float) cols;
        pitchY = height / (float) rows;
    }

    public int cx(int c) { return Math.round(left + pitchX * (c + 0.5f)); }
    public int cy(int r) { return Math.round(top + pitchY * (r + 0.5f)); }
    public float pitch() { return Math.min(pitchX, pitchY); }
    public boolean ready() { return masks != null && srcR >= 0 && dstR >= 0; }
}
