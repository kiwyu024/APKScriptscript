package com.kiwyu.terraminebot;

import android.content.Context;
import android.content.SharedPreferences;

/** All tunable settings, persisted in SharedPreferences. */
public class Config {
    public static final String PREFS = "terraminebot";

    public int rows = 6, cols = 6;
    // Optional manual grid (centres of the top-left and bottom-right tiles), used only when the
    // yellow board frame can't be found automatically. -1 = not set.
    public int tlX = -1, tlY = -1, brX = -1, brY = -1;

    /** 1 = a tap rotates clockwise (what the recordings show), -1 = counter-clockwise, 0 = learn with one tap. */
    public int tapDir = 1;

    public int edgeThreshold = 60;   // stripe brightness needed to call a tile side "open"
    public int xThreshold = 20;      // ad close-X detector score needed to tap it

    public int tapDelayMs = 300;     // between taps (each rotation animates)
    public int settleMs = 800;       // after the last tap before re-checking
    public int loopMs = 700;

    public String playTexts = "Play Again,Play Level";  // accessibility text of the replay buttons
    public int replayGapSec = 6;     // don't tap the replay button again within this time

    public int adTimeoutSec = 90;    // no X found by then -> tap corners, then Back
    public String adClosePoints = ""; // optional "x,y;x,y" spots to try
    public boolean backAsLastResort = true;

    public String gamePackage = "";
    public boolean debugDump = false;

    public boolean manualGrid() { return tlX >= 0 && brX > tlX && brY > tlY; }

    public static Config load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config k = new Config();
        k.rows = p.getInt("rows", k.rows);
        k.cols = p.getInt("cols", k.cols);
        k.tlX = p.getInt("tlX", -1); k.tlY = p.getInt("tlY", -1);
        k.brX = p.getInt("brX", -1); k.brY = p.getInt("brY", -1);
        k.tapDir = p.getInt("tapDir", k.tapDir);
        k.edgeThreshold = p.getInt("edgeThreshold", k.edgeThreshold);
        k.xThreshold = p.getInt("xThreshold", k.xThreshold);
        k.tapDelayMs = p.getInt("tapDelayMs", k.tapDelayMs);
        k.settleMs = p.getInt("settleMs", k.settleMs);
        k.loopMs = p.getInt("loopMs", k.loopMs);
        k.playTexts = p.getString("playTexts", k.playTexts);
        k.replayGapSec = p.getInt("replayGapSec", k.replayGapSec);
        k.adTimeoutSec = p.getInt("adTimeoutSec", k.adTimeoutSec);
        k.adClosePoints = p.getString("adClosePoints", "");
        k.backAsLastResort = p.getBoolean("backAsLastResort", k.backAsLastResort);
        k.gamePackage = p.getString("gamePackage", "");
        k.debugDump = p.getBoolean("debugDump", false);
        return k;
    }

    public void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("rows", rows).putInt("cols", cols)
                .putInt("tlX", tlX).putInt("tlY", tlY).putInt("brX", brX).putInt("brY", brY)
                .putInt("tapDir", tapDir)
                .putInt("edgeThreshold", edgeThreshold).putInt("xThreshold", xThreshold)
                .putInt("tapDelayMs", tapDelayMs).putInt("settleMs", settleMs).putInt("loopMs", loopMs)
                .putString("playTexts", playTexts).putInt("replayGapSec", replayGapSec)
                .putInt("adTimeoutSec", adTimeoutSec)
                .putString("adClosePoints", adClosePoints)
                .putBoolean("backAsLastResort", backAsLastResort)
                .putString("gamePackage", gamePackage)
                .putBoolean("debugDump", debugDump)
                .apply();
    }

    /** Parses "x,y;x,y" into int pairs. */
    public int[][] closePoints() {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (adClosePoints != null)
            for (String s : adClosePoints.split(";")) {
                String[] xy = s.split(",");
                if (xy.length != 2) continue;
                try { out.add(new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())}); }
                catch (NumberFormatException ignored) { }
            }
        return out.toArray(new int[0][]);
    }
}
