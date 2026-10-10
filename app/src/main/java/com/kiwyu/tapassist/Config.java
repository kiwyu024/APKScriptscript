package com.kiwyu.tapassist;

import android.content.Context;
import android.content.SharedPreferences;

/** All tunable settings, persisted in SharedPreferences. */
public class Config {
    public static final String PREFS = "tapassist";

    public int rows = 6, cols = 6;
    // Optional manual grid (centres of the top-left and bottom-right tiles), used only when the
    // yellow board frame can't be found automatically. -1 = not set.
    public int tlX = -1, tlY = -1, brX = -1, brY = -1;

    /** Taps always turn a tile clockwise in this game (seen in every recording); kept as a constant for the solver. */
    public final int tapDir = 1;

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

    // Humanize (for testing the game's anti-cheat).
    public boolean humanize = false;
    public int jitterPct = 55;       // max tap offset from tile centre, % of half a tile
    public int pressMinMs = 45, pressMaxMs = 130;
    public int driftPx = 3;          // finger slide while pressed
    public int thinkMinMs = 700, thinkMaxMs = 2600; // before the first tap of a puzzle
    public int reactMinMs = 600, reactMaxMs = 2200; // before tapping the replay button
    public int startLepPct = 65;     // rounds that start at the tile next to the leprechaun
    public int startPotPct = 34;     // ... next to the pot of gold; the rest open with a stray tap elsewhere
    public int perfectRoundPct = 1;  // humanized rounds with no bad taps at all
    public int badTapsMin = 1, badTapsMax = 15;  // bad taps per round (weighted towards fewer)
    public int tapsLeftMargin = 3;   // keep this many of the game's "N left" taps unused
    public int hesitationPct = 6;    // chance per tile move of a 0.7-3 s hesitation
    public int distractionPct = 2;   // chance per round of a 5-25 s pause mid-round
    public boolean takeBreaks = true;
    public int breakEveryMin = 8, breakEveryMax = 25;   // rounds between breaks
    public int breakMinSec = 45, breakMaxSec = 300;
    public boolean logTaps = true;

    /** Ads: off by default; turn on only in builds that serve AdMob test ads. */
    public boolean handleAds = false;

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
        k.humanize = p.getBoolean("humanize", k.humanize);
        k.jitterPct = p.getInt("jitterPct", k.jitterPct);
        k.pressMinMs = p.getInt("pressMinMs", k.pressMinMs); k.pressMaxMs = p.getInt("pressMaxMs", k.pressMaxMs);
        k.driftPx = p.getInt("driftPx", k.driftPx);
        k.thinkMinMs = p.getInt("thinkMinMs", k.thinkMinMs); k.thinkMaxMs = p.getInt("thinkMaxMs", k.thinkMaxMs);
        k.reactMinMs = p.getInt("reactMinMs", k.reactMinMs); k.reactMaxMs = p.getInt("reactMaxMs", k.reactMaxMs);
        k.startLepPct = p.getInt("startLepPct", k.startLepPct);
        k.startPotPct = p.getInt("startPotPct", k.startPotPct);
        k.perfectRoundPct = p.getInt("perfectRoundPct", k.perfectRoundPct);
        k.badTapsMin = p.getInt("badTapsMin", k.badTapsMin); k.badTapsMax = p.getInt("badTapsMax", k.badTapsMax);
        k.tapsLeftMargin = p.getInt("tapsLeftMargin", k.tapsLeftMargin);
        k.hesitationPct = p.getInt("hesitationPct", k.hesitationPct);
        k.distractionPct = p.getInt("distractionPct", k.distractionPct);
        k.takeBreaks = p.getBoolean("takeBreaks", k.takeBreaks);
        k.breakEveryMin = p.getInt("breakEveryMin", k.breakEveryMin); k.breakEveryMax = p.getInt("breakEveryMax", k.breakEveryMax);
        k.breakMinSec = p.getInt("breakMinSec", k.breakMinSec); k.breakMaxSec = p.getInt("breakMaxSec", k.breakMaxSec);
        k.logTaps = p.getBoolean("logTaps", k.logTaps);
        k.handleAds = p.getBoolean("handleAds", k.handleAds);
        k.gamePackage = p.getString("gamePackage", "");
        k.debugDump = p.getBoolean("debugDump", false);
        return k;
    }

    public void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("rows", rows).putInt("cols", cols)
                .putInt("tlX", tlX).putInt("tlY", tlY).putInt("brX", brX).putInt("brY", brY)
                .putInt("edgeThreshold", edgeThreshold).putInt("xThreshold", xThreshold)
                .putInt("tapDelayMs", tapDelayMs).putInt("settleMs", settleMs).putInt("loopMs", loopMs)
                .putString("playTexts", playTexts).putInt("replayGapSec", replayGapSec)
                .putInt("adTimeoutSec", adTimeoutSec)
                .putString("adClosePoints", adClosePoints)
                .putBoolean("backAsLastResort", backAsLastResort)
                .putBoolean("humanize", humanize).putInt("jitterPct", jitterPct)
                .putInt("pressMinMs", pressMinMs).putInt("pressMaxMs", pressMaxMs).putInt("driftPx", driftPx)
                .putInt("thinkMinMs", thinkMinMs).putInt("thinkMaxMs", thinkMaxMs)
                .putInt("reactMinMs", reactMinMs).putInt("reactMaxMs", reactMaxMs)
                .putInt("startLepPct", startLepPct).putInt("startPotPct", startPotPct)
                .putInt("perfectRoundPct", perfectRoundPct)
                .putInt("badTapsMin", badTapsMin).putInt("badTapsMax", badTapsMax)
                .putInt("tapsLeftMargin", tapsLeftMargin)
                .putInt("hesitationPct", hesitationPct).putInt("distractionPct", distractionPct)
                .putBoolean("takeBreaks", takeBreaks)
                .putInt("breakEveryMin", breakEveryMin).putInt("breakEveryMax", breakEveryMax)
                .putInt("breakMinSec", breakMinSec).putInt("breakMaxSec", breakMaxSec).putBoolean("logTaps", logTaps)
                .putBoolean("handleAds", handleAds)
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
