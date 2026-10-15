package com.kiwyu.tapassist;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    Config cfg;
    LinearLayout col;
    TextView serviceState;
    final Map<String, EditText> fields = new LinkedHashMap<>();
    Spinner dir;
    CheckBox back, debug, humanize, logTaps, handleAds, takeBreaks, mistakes, collectHumanize, collectBreaks;

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
        ScrollView sv = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad * 2, pad, pad * 2);
        sv.addView(col);
        setContentView(sv);

        title("Tap Assist");
        serviceState = new TextView(this);
        col.addView(serviceState);
        button("Accessibility settings", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("App info (allow restricted settings)", () -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        TextView help = new TextView(this);
        help.setText(HELP);
        help.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(help);

        title("Resource collector");
        note("Switch the floating panel's mode (⚙ → Mode) to 'Collect resources', open Profile → Properties and " +
                "press ▶. It opens each mine, collects its daily activity when it shows attempts left (e.g. 1/3), " +
                "skips ones at 0/3, scrolls and taps 'Load 20 More', and finds its way back if it gets lost. " +
                "It never taps Watch Ad, Edit, Refer, Logout or ENTER MINE.");
        num("collectRepeatMin", "After a full pass, start again after (minutes)", cfg.collectRepeatMin);
        collectHumanize = check("Humanize the collector", cfg.collectHumanize);
        note("Taps land anywhere on the button or card (each session has its own habitual spot), presses and " +
                "pauses vary, it looks at each reward before collecting, swipes are uneven curved flicks, it sometimes " +
                "opens the 2nd card first and sometimes uses the phone's Back instead of ← Back.");
        num("collectSysBackPct", "Use the phone's Back instead of ← Back (%)", cfg.collectSysBackPct);
        num("collectSkipPct", "Open the 2nd card on screen first (%)", cfg.collectSkipPct);
        collectBreaks = check("Take breaks while collecting", cfg.collectBreaks);
        num("collectBreakEveryMin", "Break after at least (properties)", cfg.collectBreakEveryMin);
        num("collectBreakEveryMax", "… and at most (properties)", cfg.collectBreakEveryMax);
        num("collectBreakMinSec", "Break length min (seconds)", cfg.collectBreakMinSec);
        num("collectBreakMaxSec", "Break length max (seconds)", cfg.collectBreakMaxSec);

        title("Speed");
        dec("speed", "Speed (1 = normal, 1.5, 2, 3 …)", cfg.speed);
        note("Speeds up every pause and tap gap. It never goes below what the game needs: 200 ms between taps on " +
                "the same tile (a turn animates for ~120-175 ms), 400 ms before re-reading the board, ~3 screen " +
                "checks a second. Mistake rates don't change. Breaks between rounds keep their length. " +
                "Also switchable on the floating panel (the 1x button).");

        title("Puzzle");
        dir = spinner("Tap rotates", new String[]{"clockwise", "counter-clockwise", "learn (uses 1 tap)"},
                cfg.tapDir > 0 ? "clockwise" : cfg.tapDir < 0 ? "counter-clockwise" : "learn (uses 1 tap)");
        num("edgeThreshold", "Road detection threshold (default 60)", cfg.edgeThreshold);
        num("tapDelayMs", "Delay between taps, ms", cfg.tapDelayMs);
        num("settleMs", "Wait after tapping, ms", cfg.settleMs);
        num("loopMs", "Loop interval, ms", cfg.loopMs);

        title("Mistakes");
        mistakes = check("Make mistakes", cfg.mistakes);
        note("Works with or without Humanize. Each puzzle is either 'clean' (4x4: 0-2 wrong taps, bigger boards: 1-3) " +
                "or 'messy' (4 up to 5 / 8 / 12 / 15 wrong taps on 4x4 / 5x5 / 6x6 / 7x7). The clean chance below is " +
                "nudged down for boards that need more turns than usual. Over-rotations come on top: the right tile " +
                "turned one step too far, then all the way round (+4 taps).");
        num("cleanPct4", "Clean puzzles on 4x4, %", cfg.cleanPct4);
        num("cleanPct5", "Clean puzzles on 5x5, %", cfg.cleanPct5);
        num("cleanPct6", "Clean puzzles on 6x6, %", cfg.cleanPct6);
        num("cleanPct7", "Clean puzzles on 7x7, %", cfg.cleanPct7);
        num("overRotatePct", "Over-rotations, % of correct taps", cfg.overRotatePct);
        num("tapsLeftMargin", "Keep this many of the game's 'N left' taps spare", cfg.tapsLeftMargin);

        title("Humanize (anti-cheat testing)");
        humanize = check("Humanize taps", cfg.humanize);
        note("Random tap spot inside each tile, press length, finger drift, pauses, tile order. " +
                "Off = exact centre taps with fixed delays.");
        num("jitterPct", "Max tap offset from tile centre (% of half a tile)", cfg.jitterPct);
        num("pressMinMs", "Press length min, ms", cfg.pressMinMs);
        num("pressMaxMs", "Press length max, ms", cfg.pressMaxMs);
        num("driftPx", "Finger drift while pressed, px (keep ≤ 8)", cfg.driftPx);
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
        takeBreaks = check("Take breaks between rounds", cfg.takeBreaks);
        num("breakEveryMin", "Break every N rounds, min", cfg.breakEveryMin);
        num("breakEveryMax", "Break every N rounds, max", cfg.breakEveryMax);
        num("breakMinSec", "Break length, min seconds", cfg.breakMinSec);
        num("breakMaxSec", "Break length, max seconds", cfg.breakMaxSec);
        logTaps = check("Log every tap to Download/TapAssist (CSV, saved on stop)", cfg.logTaps);

        title("Replay");
        text("playTexts", "Replay button texts (comma separated)", cfg.playTexts);
        num("replayGapSec", "Min seconds between replay taps", cfg.replayGapSec);

        title("Ads");
        handleAds = check("Watch & close ads automatically (only with AdMob test ads)", cfg.handleAds);
        note("Off: the bot stops at 'Watch Ad' screens and leaves ads alone.");
        num("xThreshold", "Close-X detection threshold (default 20)", cfg.xThreshold);
        num("adTimeoutSec", "If no X after this many seconds, tap corners / Back", cfg.adTimeoutSec);
        text("adClosePoints", "Extra X spots to try (x,y;x,y)", cfg.adClosePoints);
        back = check("Press Back if the ad can't be closed", cfg.backAsLastResort);
        debug = check("Save debug images while running", cfg.debugDump);

        button("Save", this::save);
        button("Reset floating panel position", () -> {
            getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit().remove("panelX").remove("panelY").apply();
            AutoPlayerService s = AutoPlayerService.instance;
            if (s != null && s.overlay != null) s.overlay.resetPosition();
            Toast.makeText(this, "Panel moved back to the bottom-right", Toast.LENGTH_SHORT).show();
        });
        button("Clear manual grid", () -> {
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
            int d = dir.getSelectedItemPosition();
            cfg.tapDir = d == 0 ? 1 : d == 1 ? -1 : 0;
            cfg.edgeThreshold = i("edgeThreshold");
            cfg.speed = Math.max(0.25f, Math.min(10f, Float.parseFloat(fields.get("speed").getText().toString().trim())));
            cfg.collectRepeatMin = i("collectRepeatMin");
            cfg.collectHumanize = collectHumanize.isChecked();
            cfg.collectBreaks = collectBreaks.isChecked();
            cfg.collectSysBackPct = Math.max(0, Math.min(100, i("collectSysBackPct")));
            cfg.collectSkipPct = Math.max(0, Math.min(100, i("collectSkipPct")));
            cfg.collectBreakEveryMin = Math.max(1, i("collectBreakEveryMin"));
            cfg.collectBreakEveryMax = Math.max(cfg.collectBreakEveryMin, i("collectBreakEveryMax"));
            cfg.collectBreakMinSec = Math.max(0, i("collectBreakMinSec"));
            cfg.collectBreakMaxSec = Math.max(cfg.collectBreakMinSec, i("collectBreakMaxSec"));
            cfg.tapDelayMs = i("tapDelayMs"); cfg.settleMs = i("settleMs"); cfg.loopMs = i("loopMs");
            cfg.playTexts = fields.get("playTexts").getText().toString();
            cfg.replayGapSec = i("replayGapSec");
            cfg.xThreshold = i("xThreshold");
            cfg.adTimeoutSec = i("adTimeoutSec");
            cfg.adClosePoints = fields.get("adClosePoints").getText().toString().trim();
            cfg.backAsLastResort = back.isChecked();
            cfg.humanize = humanize.isChecked();
            cfg.jitterPct = i("jitterPct");
            cfg.pressMinMs = i("pressMinMs"); cfg.pressMaxMs = i("pressMaxMs");
            cfg.driftPx = i("driftPx");
            cfg.hesitationPct = i("hesitationPct"); cfg.distractionPct = i("distractionPct");
            cfg.thinkMinMs = i("thinkMinMs"); cfg.thinkMaxMs = i("thinkMaxMs");
            cfg.reactMinMs = i("reactMinMs"); cfg.reactMaxMs = i("reactMaxMs");
            cfg.startLepPct = i("startLepPct"); cfg.startPotPct = i("startPotPct");
            cfg.mistakes = mistakes.isChecked();
            cfg.cleanPct4 = i("cleanPct4"); cfg.cleanPct5 = i("cleanPct5");
            cfg.cleanPct6 = i("cleanPct6"); cfg.cleanPct7 = i("cleanPct7");
            cfg.overRotatePct = i("overRotatePct");
            cfg.tapsLeftMargin = i("tapsLeftMargin");
            cfg.takeBreaks = takeBreaks.isChecked();
            cfg.breakEveryMin = i("breakEveryMin"); cfg.breakEveryMax = i("breakEveryMax");
            cfg.breakMinSec = i("breakMinSec"); cfg.breakMaxSec = i("breakMaxSec");
            cfg.logTaps = logTaps.isChecked();
            cfg.handleAds = handleAds.isChecked();
            cfg.debugDump = debug.isChecked();
            cfg.save(this);
            Toast.makeText(this, "Saved — restart the bot (■ then ▶) to apply", Toast.LENGTH_LONG).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid number: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    int i(String k) { return Integer.parseInt(fields.get(k).getText().toString().trim()); }

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

    Spinner spinner(String label, String[] items, String sel) {
        TextView l = new TextView(this);
        l.setText(label);
        col.addView(l);
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        for (int i = 0; i < items.length; i++) if (items[i].equals(sel)) s.setSelection(i);
        col.addView(s);
        return s;
    }

    CheckBox check(String t, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(t);
        c.setChecked(v);
        col.addView(c);
        return c;
    }
}
