package com.kiwyu.tapassist;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    Config cfg;
    LinearLayout col;
    TextView serviceState;
    final Map<String, EditText> fields = new LinkedHashMap<>();
    CheckBox back, debug, humanize, logTaps, handleAds, takeBreaks;

    static final String ADS_HELP =
            "For testing your own app with Google test ads (AdMob test ad units or a registered test device).\n\n" +
            "Setup (one time):\n" +
            "1. Tap 'App info', then ⋮ (top right) → 'Allow restricted settings'.\n" +
            "2. Tap 'Accessibility settings' → Installed apps → Ad Tester → On.\n" +
            "3. Press ▶ on the floating panel, open your app and trigger ads.\n\n" +
            "Each ad gets a random viewer type (watch / impatient / skipper, mix below). Only ads showing the " +
            "'Test Ad' / 'Test mode' label are touched; anything else is left alone and automation pauses for the " +
            "session. Every ad is logged (start, when the X appeared, each action, how it ended) to " +
            "Download/AdTester as CSV when you press stop.";

    static final String HELP =
            "Setup (one time):\n" +
            "1. Tap 'App info', then ⋮ (top right) → 'Allow restricted settings' (Android 13+ requires this for sideloaded apps).\n" +
            "2. Tap 'Accessibility settings' → Installed apps → Tap Assist → On.\n" +
            "3. A floating ▶ ⚙ panel appears at the bottom-right (over the game's Settings tab). Leave it there: " +
            "it must not cover the board, Play Again / Watch Ad, Exit or the ad's X.\n" +
            "4. Open the game's puzzle, then press ▶.\n\n" +
            "The board, leprechaun, pot and buttons are found automatically. ⚙ → 'Check board now' shows what the bot reads. " +
            "If something goes wrong, turn on 'Save debug images' (or ⚙ → Save debug snapshot); images go to Pictures/TapAssist.";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = Config.load(this);
        boolean adsOnly = getResources().getBoolean(R.bool.ads_only);
        ScrollView sv = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad * 2, pad, pad * 2);
        sv.addView(col);
        setContentView(sv);

        title(getString(R.string.app_name));
        serviceState = new TextView(this);
        col.addView(serviceState);
        button("Accessibility settings", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("App info (allow restricted settings)", () -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        TextView help = new TextView(this);
        help.setText(adsOnly ? ADS_HELP : HELP);
        help.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(help);

        if (!adsOnly) {
        title("Puzzle");
        num("edgeThreshold", "Road detection threshold (default 60)", cfg.edgeThreshold);
        num("tapDelayMs", "Delay between taps, ms", cfg.tapDelayMs);
        num("settleMs", "Wait after tapping, ms", cfg.settleMs);
        num("loopMs", "Loop interval, ms", cfg.loopMs);

        }

        title("Humanize (anti-cheat testing)");
        humanize = check("Humanize taps", cfg.humanize);
        note("Random tap spot inside each tile, press length, finger drift, pauses, tile order. " +
                "Off = exact centre taps with fixed delays.");
        num("jitterPct", "Max tap offset from tile centre (% of half a tile)", cfg.jitterPct);
        num("pressMinMs", "Press length min, ms", cfg.pressMinMs);
        num("pressMaxMs", "Press length max, ms", cfg.pressMaxMs);
        num("driftPx", "Finger drift while pressed, px (keep ≤ 8)", cfg.driftPx);
        if (!adsOnly) {
        num("hesitationPct", "Hesitation chance per tile move, % (0.7-3 s)", cfg.hesitationPct);
        num("distractionPct", "Distraction chance per round, % (5-25 s pause)", cfg.distractionPct);
        num("thinkMinMs", "Pause before solving, min ms", cfg.thinkMinMs);
        num("thinkMaxMs", "Pause before solving, max ms", cfg.thinkMaxMs);
        num("reactMinMs", "Pause before tapping replay, min ms", cfg.reactMinMs);
        num("reactMaxMs", "Pause before tapping replay, max ms", cfg.reactMaxMs);
        num("startLepPct", "Start at the tile by the leprechaun, % of rounds", cfg.startLepPct);
        num("startPotPct", "Start at the tile by the gold, % of rounds", cfg.startPotPct);
        note("The rest of the rounds open with a stray tap on another block (counts as a bad tap). " +
                "After the first tap the bot works along the road from that end.");
        num("perfectRoundPct", "Rounds with no bad taps, %", cfg.perfectRoundPct);
        num("badTapsMin", "Bad taps per round, min", cfg.badTapsMin);
        num("badTapsMax", "Bad taps per round, max", cfg.badTapsMax);
        num("tapsLeftMargin", "Keep this many of the game's 'N left' taps spare", cfg.tapsLeftMargin);
        note("Bad taps: over-rotating or double-tapping a tile and going round again (+4), tapping a road tile " +
                "that isn't on the route (+1-3), bumping a correct tile and fixing it (+4), tapping grass (+1). " +
                "Plus 0-2 tiles left half-turned and finished later (free). Counts lean towards the low end.");
        takeBreaks = check("Take breaks between rounds", cfg.takeBreaks);
        num("breakEveryMin", "Break every N rounds, min", cfg.breakEveryMin);
        num("breakEveryMax", "Break every N rounds, max", cfg.breakEveryMax);
        num("breakMinSec", "Break length, min seconds", cfg.breakMinSec);
        num("breakMaxSec", "Break length, max seconds", cfg.breakMaxSec);
        }
        logTaps = check("Log every tap and event to Download/" + getString(R.string.files_folder)
                + " (CSV, saved on stop)", cfg.logTaps);

        if (!adsOnly) {
        title("Replay");
        text("playTexts", "Replay button texts (comma separated)", cfg.playTexts);
        num("replayGapSec", "Min seconds between replay taps", cfg.replayGapSec);
        }

        title("Ads (test ads only)");
        if (!adsOnly) {
            handleAds = check("Watch & close ads automatically (only with AdMob test ads)", cfg.handleAds);
            note("Off: the bot stops at 'Watch Ad' screens and leaves ads alone. On: it only acts on ads " +
                    "labelled 'Test Ad' / 'Test mode'; a real ad pauses ad automation for the session.");
        }
        num("adWatchPct", "Viewer mix: watch (waits for the X), %", cfg.adWatchPct);
        num("adImpatientPct", "Viewer mix: impatient (tries to close early), %", cfg.adImpatientPct);
        num("adSkipperPct", "Viewer mix: skipper (Back, then leaves and returns), %", cfg.adSkipperPct);
        num("xThreshold", "Close-X detection threshold (default 20)", cfg.xThreshold);
        num("adTimeoutSec", "If no X after this many seconds, tap corners / Back", cfg.adTimeoutSec);
        text("adClosePoints", "Extra X spots to try (x,y;x,y)", cfg.adClosePoints);
        back = check("Press Back if the ad can't be closed", cfg.backAsLastResort);
        if (!adsOnly) debug = check("Save debug images while running", cfg.debugDump);

        button("Save", this::save);
        if (!adsOnly) button("Clear manual grid", () -> {
            cfg.tlX = cfg.tlY = cfg.brX = cfg.brY = -1;
            cfg.save(this);
            Toast.makeText(this, "Manual grid cleared", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        serviceState.setText(AutoPlayerService.instance != null
                ? "Service: ON — use the floating panel"
                : "Service: OFF — enable it in Accessibility settings");
        Config c = Config.load(this);
        if (c.manualGrid())
            serviceState.append("\nManual grid set: (" + c.tlX + "," + c.tlY + ") → (" + c.brX + "," + c.brY + ")");
    }

    void save() {
        try {
            cfg.edgeThreshold = i("edgeThreshold", cfg.edgeThreshold);
            cfg.tapDelayMs = i("tapDelayMs", cfg.tapDelayMs); cfg.settleMs = i("settleMs", cfg.settleMs); cfg.loopMs = i("loopMs", cfg.loopMs);
            cfg.playTexts = t("playTexts", cfg.playTexts);
            cfg.replayGapSec = i("replayGapSec", cfg.replayGapSec);
            cfg.xThreshold = i("xThreshold", cfg.xThreshold);
            cfg.adTimeoutSec = i("adTimeoutSec", cfg.adTimeoutSec);
            cfg.adClosePoints = t("adClosePoints", cfg.adClosePoints).trim();
            cfg.backAsLastResort = ck(back, cfg.backAsLastResort);
            cfg.humanize = ck(humanize, cfg.humanize);
            cfg.jitterPct = i("jitterPct", cfg.jitterPct);
            cfg.pressMinMs = i("pressMinMs", cfg.pressMinMs); cfg.pressMaxMs = i("pressMaxMs", cfg.pressMaxMs);
            cfg.driftPx = i("driftPx", cfg.driftPx);
            cfg.hesitationPct = i("hesitationPct", cfg.hesitationPct); cfg.distractionPct = i("distractionPct", cfg.distractionPct);
            cfg.thinkMinMs = i("thinkMinMs", cfg.thinkMinMs); cfg.thinkMaxMs = i("thinkMaxMs", cfg.thinkMaxMs);
            cfg.reactMinMs = i("reactMinMs", cfg.reactMinMs); cfg.reactMaxMs = i("reactMaxMs", cfg.reactMaxMs);
            cfg.startLepPct = i("startLepPct", cfg.startLepPct); cfg.startPotPct = i("startPotPct", cfg.startPotPct);
            cfg.perfectRoundPct = i("perfectRoundPct", cfg.perfectRoundPct);
            cfg.badTapsMin = i("badTapsMin", cfg.badTapsMin); cfg.badTapsMax = i("badTapsMax", cfg.badTapsMax);
            cfg.tapsLeftMargin = i("tapsLeftMargin", cfg.tapsLeftMargin);
            cfg.takeBreaks = ck(takeBreaks, cfg.takeBreaks);
            cfg.breakEveryMin = i("breakEveryMin", cfg.breakEveryMin); cfg.breakEveryMax = i("breakEveryMax", cfg.breakEveryMax);
            cfg.breakMinSec = i("breakMinSec", cfg.breakMinSec); cfg.breakMaxSec = i("breakMaxSec", cfg.breakMaxSec);
            cfg.logTaps = ck(logTaps, cfg.logTaps);
            cfg.handleAds = ck(handleAds, cfg.handleAds);
            cfg.debugDump = ck(debug, cfg.debugDump);
            cfg.adWatchPct = i("adWatchPct", cfg.adWatchPct);
            cfg.adImpatientPct = i("adImpatientPct", cfg.adImpatientPct);
            cfg.adSkipperPct = i("adSkipperPct", cfg.adSkipperPct);
            cfg.save(this);
            Toast.makeText(this, "Saved — restart the bot (■ then ▶) to apply", Toast.LENGTH_LONG).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid number: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Field value, or the current setting when this build doesn't show that field. */
    int i(String k, int def) {
        EditText e = fields.get(k);
        return e == null ? def : Integer.parseInt(e.getText().toString().trim());
    }

    String t(String k, String def) {
        EditText e = fields.get(k);
        return e == null ? def : e.getText().toString();
    }

    static boolean ck(CheckBox c, boolean def) { return c == null ? def : c.isChecked(); }

    void title(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(20);
        v.setPadding(0, 24, 0, 8);
        col.addView(v);
    }

    void note(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(12);
        col.addView(v);
    }

    void button(String t, Runnable r) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        col.addView(b);
    }

    EditText field(String key, String label, String value, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        col.addView(l);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setText(value);
        col.addView(e);
        fields.put(key, e);
        return e;
    }

    void num(String k, String l, int v) {
        field(k, l, String.valueOf(v), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
    }

    void dec(String k, String l, float v) {
        field(k, l, String.valueOf(v), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    }

    void text(String k, String l, String v) { field(k, l, v, InputType.TYPE_CLASS_TEXT); }

    CheckBox check(String t, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(t);
        c.setChecked(v);
        col.addView(c);
        return c;
    }
}
