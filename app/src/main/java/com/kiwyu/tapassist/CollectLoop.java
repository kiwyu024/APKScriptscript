package com.kiwyu.tapassist;

import android.accessibilityservice.AccessibilityService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resource collector: goes through Profile → Properties, opens each mine, and collects its daily activity
 * (Coal Pile, Conveyor Belt, Sluice Box, Slot Machine, …) when the counter shows attempts left (1/3), skipping
 * ones that show 0/3. Scrolls the list, taps "Load 20 More" when it runs out, and finds its way back to the
 * property list if it ends up somewhere else. It never taps Watch Ad, Edit, Refer, Logout or ENTER MINE.
 *
 * Screens are recognised from their text (accessibility labels, or on-device OCR if the game exposes none),
 * matching what the screen recording shows.
 */
public class CollectLoop implements Runnable, BotLoop {
    final AutoPlayerService svc;
    final Config cfg;
    final Humanizer human;
    final TapLog log = new TapLog();
    volatile boolean running;
    Thread thread;

    final Set<String> visited = new HashSet<>();   // properties opened this pass (by coordinates)
    String current = "";                            // property being worked on
    int collected = 0, alreadyDone = 0, opened = 0;
    int unknownTicks = 0, mineScrolls = 0, actionTaps = 0, listStill = 0, loadingTicks = 0;
    String lastListKeys = "";

    static final Pattern BADGE = Pattern.compile("^(\\d+)\\s*/\\s*(\\d+)$");
    static final Pattern COORDS = Pattern.compile("^-?\\d{1,3}\\.\\d+\\s*,\\s*-?\\d{1,3}\\.\\d+$");
    static final Pattern NUMBER = Pattern.compile("^\\d[\\d,]*$");
    /** Never tapped, whatever happens. */
    static final String[] BLOCKED = {"watch", "logout", "log out", "delete", "edit", "refer", "enter mine",
            "buy", "purchase", "upgrade", "sell", "transfer", "withdraw", "no attempts"};

    public CollectLoop(AutoPlayerService svc, Config cfg) {
        this.svc = svc;
        this.cfg = cfg;
        this.human = new Humanizer(cfg);
    }

    @Override public Config config() { return cfg; }
    @Override public boolean isRunning() { return running; }

    @Override public void start() {
        running = true;
        thread = new Thread(this, "CollectLoop");
        thread.start();
    }

    @Override public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        svc.status("Collector running — open Profile → Properties");
        log.event("collector started");
        while (running) {
            try { step(); }
            catch (Exception e) { svc.status("Error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
            AutoPlayerService.sleep(human.loopDelay());
        }
        log.event("collector stopped: opened " + opened + ", collected " + collected + ", already done " + alreadyDone);
        String saved = cfg.logTaps ? log.save(svc) : null;
        svc.status("Stopped (collected " + collected + ", already done " + alreadyDone + ")"
                + (saved == null ? "" : "\nLog: " + saved));
    }

    // ------------------------------------------------------------------ helpers
    static boolean blocked(String lower) {
        for (String b : BLOCKED) if (lower.contains(b)) return true;
        return lower.matches(".*\\bad\\b.*");
    }

    /** Human-ish pause before acting. */
    void pause() {
        AutoPlayerService.sleep(human.fast(human.reactionPause() + 350, 250));
    }

    void tapAt(int x, int y, float hw, float hh, String kind, String detail) {
        int[] p = human.point(x, y, hw, hh);
        int press = human.pressMs();
        int[] d = human.drift();
        svc.tap(p[0], p[1], press, d[0], d[1]);
        if (cfg.logTaps) log.add(p[0], p[1], press, d, kind, detail);
    }

    /** Taps a text item (never a blocked one). */
    boolean tapItem(ScreenText.Item it, String kind) {
        if (it == null || blocked(it.lower)) return false;
        pause();
        tapAt(it.cx(), it.cy(), Math.max(6, it.width() / 3f), Math.max(4, it.height() / 3f), kind, it.text);
        return true;
    }

    void back(ScreenText st, Frame f, String why) {
        ScreenText.Item b = null;
        for (ScreenText.Item it : st.items)
            if (it.lower.replace("←", "").trim().equals("back") && it.cy() < f.h * 0.15f) { b = it; break; }
        if (b != null) tapItem(b, "nav_back");
        else { pause(); svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); }
        if (cfg.logTaps) log.event("back: " + why);
    }

    void swipeUp(Frame f, float amount) {
        int x = Math.round(f.w * (0.30f + (float) Math.random() * 0.15f));
        int y1 = Math.round(f.h * 0.72f), y2 = Math.round(f.h * (0.72f - amount));
        AutoPlayerService.sleep(human.fast(250 + (long) (Math.random() * 250), 120));
        svc.swipe(x, y1, x + (int) (Math.random() * 24 - 12), y2, 300 + (int) (Math.random() * 200));
        AutoPlayerService.sleep(human.fast(700, 450));
    }

    /** "1/3"-style counters on screen, optionally only above/below a height. */
    static List<int[]> badges(ScreenText st, int minY, int maxY) {
        List<int[]> out = new ArrayList<>();
        for (ScreenText.Item it : st.items) {
            java.util.regex.Matcher m = BADGE.matcher(it.text.replace(" ", ""));
            if (m.matches() && it.cy() >= minY && it.cy() <= maxY)
                out.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), it.cx(), it.cy()});
        }
        return out;
    }

    // ------------------------------------------------------------------ main step
    enum Screen { REWARDS, LOADING, ACTIVITY, MINE, LIST, PROFILE, DIALOG, UNKNOWN }

    static Screen classify(ScreenText st) {
        if (st.has("collect rewards", "rewards earned")) return Screen.REWARDS;
        if (st.has("loading property")) return Screen.LOADING;
        if (st.has("daily activity")) return Screen.ACTIVITY;
        if (st.has("production level", "mine entrance", "visitor greeting", "unnamed mine", "edit name")) return Screen.MINE;
        boolean coords = false;
        for (ScreenText.Item it : st.items) if (COORDS.matcher(it.text).matches()) { coords = true; break; }
        if (st.has("properties") && (coords || st.has("load 20 more", "load more", "sort:"))) return Screen.LIST;
        if (st.has("got it")) return Screen.DIALOG;
        if (st.has("portfolio", "properties")) return Screen.PROFILE;
        return Screen.UNKNOWN;
    }

    void step() {
        Frame f = svc.screenshot();
        if (f == null) { svc.status("Screenshot failed (retrying)"); return; }
        ScreenText st = ScreenText.read(svc, f, false);
        Screen s = classify(st);
        if (s == Screen.UNKNOWN && "accessibility".equals(st.source)) {   // maybe the labels are incomplete: try OCR
            ScreenText o = ScreenText.read(svc, f, true);
            if (classify(o) != Screen.UNKNOWN) { st = o; s = classify(o); }
        }
        if (s != Screen.UNKNOWN) unknownTicks = 0;
        if (s != Screen.LOADING) loadingTicks = 0;

        switch (s) {
            case REWARDS: onRewards(st, f); break;
            case LOADING:
                svc.status("Loading property…");
                if (++loadingTicks > 25) { back(st, f, "stuck loading"); loadingTicks = 0; }
                break;
            case ACTIVITY: onActivity(st, f); break;
            case MINE: onMine(st, f); break;
            case LIST: onList(st, f); break;
            case DIALOG: tapItem(st.find("got it"), "nav"); break;
            case PROFILE:
                svc.status("Profile: opening Properties");
                tapItem(st.find("properties"), "nav");
                break;
            default: onUnknown(st, f);
        }
    }

    // ------------------------------------------------------------------ screens
    void onRewards(ScreenText st, Frame f) {
        // read what was earned: a number with the resource name under it
        String reward = "";
        for (ScreenText.Item it : st.items)
            if (NUMBER.matcher(it.text).matches() && it.cy() > f.h * 0.2f && it.cy() < f.h * 0.8f) {
                ScreenText.Item name = null;
                for (ScreenText.Item n : st.items)
                    if (n.cy() > it.cy() && n.cy() - it.cy() < f.h * 0.06f && !NUMBER.matcher(n.text).matches()) { name = n; break; }
                reward = it.text + (name == null ? "" : " " + name.text);
                break;
            }
        ScreenText.Item c = st.find("collect rewards");
        boolean tapped;
        if (c != null) tapped = tapItem(c, "collect");
        else {
            int[] band = collectButton(st, f);
            if (band == null) { svc.status("Rewards: can't find COLLECT REWARDS yet"); return; }
            pause();
            tapAt(band[0], band[1], f.w * 0.15f, band[2] * 0.25f, "collect", "blue_button");
            tapped = true;
        }
        svc.status("Collecting rewards " + reward);
        if (tapped) {
            collected++;
            if (cfg.logTaps) log.event("collected " + (reward.isEmpty() ? "?" : reward) + " at " + current);
            AutoPlayerService.sleep(human.fast(1200, 600));
        }
    }

    void onActivity(ScreenText st, Frame f) {
        List<int[]> b = badges(st, 0, Math.round(f.h * 0.16f));
        int left = b.isEmpty() ? -1 : b.get(0)[0];
        // busy animation ("Breaking coal...", "Processing rocks...", "Washing for gold...", "Spinning...")
        for (ScreenText.Item it : st.items)
            if ((it.text.endsWith("...") || it.text.endsWith("…")) && !it.lower.contains("loading") && it.cy() > f.h * 0.5f) {
                svc.status("Activity: " + it.text);
                return;
            }
        if (left == 0) {
            svc.status("Activity done (0/" + (b.isEmpty() ? "?" : b.get(0)[1]) + ") — going back");
            actionTaps = 0;
            back(st, f, "activity has no attempts left");
            AutoPlayerService.sleep(human.fast(900, 500));
            return;
        }
        if (++actionTaps > 4) {
            svc.status("Activity didn't start after 4 tries — going back");
            if (cfg.logTaps) log.event("activity didn't start at " + current);
            actionTaps = 0;
            back(st, f, "activity didn't start");
            return;
        }
        ScreenText.Item button = activityButton(st, f.w, f.h);
        java.util.List<int[]> green = button == null
                ? Vision.buttonBands(f, Math.round(f.h * 0.45f), Math.round(f.h * 0.92f), false) : java.util.Collections.emptyList();
        if (button != null) {
            svc.status("Activity: tapping " + button.text);
            tapItem(button, "activity");
        } else if (!green.isEmpty()) {
            int[] g = green.get(0);
            svc.status("Activity: tapping the green button");
            pause();
            tapAt(g[0], g[1], f.w * 0.12f, g[2] * 0.25f, "activity", "green_button");
        } else {
            // coal pile / conveyor: tap the picture itself, above the "Tap to …" line
            ScreenText.Item hint = st.find("tap to");
            int top = Math.round(f.h * 0.13f);
            int bottom = hint != null ? hint.top : Math.round(f.h * 0.62f);
            int y = (top + bottom) / 2;
            svc.status("Activity: tapping the picture");
            pause();
            tapAt(f.w / 2, y, f.w * 0.18f, (bottom - top) * 0.18f, "activity", "picture");
        }
        AutoPlayerService.sleep(human.fast(1500, 800));
    }

    /**
     * COLLECT REWARDS by colour when its text can't be read: the topmost blue button below the reward, never
     * one whose text reads "Watch".
     */
    static int[] collectButton(ScreenText st, Frame f) {
        int from = Math.round(f.h * 0.45f);
        ScreenText.Item earned = st.find("rewards earned");
        if (earned != null) from = Math.max(from, earned.cy() + Math.round(f.h * 0.15f));
        for (int[] band : Vision.buttonBands(f, from, Math.round(f.h * 0.92f), true)) {
            boolean isAd = false;
            for (ScreenText.Item it : st.items)
                if (it.lower.contains("watch") && Math.abs(it.cy() - band[1]) < band[2]) isAd = true;
            if (!isAd) return band;
        }
        return null;
    }

    /** An action button (PAN FOR GOLD, PULL LEVER, …) in the lower part of an activity screen; never Watch Ad. */
    static ScreenText.Item activityButton(ScreenText st, int w, int h) {
        for (ScreenText.Item it : st.items) {
            if (it.cy() < h * 0.45f || it.cy() > h * 0.92f || blocked(it.lower)) continue;
            String letters = it.text.replaceAll("[^A-Za-z ]", "").trim();
            if (letters.length() >= 4 && letters.length() <= 24 && letters.equals(letters.toUpperCase())
                    && !it.lower.contains("reward") && !it.lower.contains("attempt")) return it;
        }
        return null;
    }

    /** Property cards on the list: {title or null, coordinates line}, top to bottom. */
    static List<ScreenText.Item[]> cards(ScreenText st, int w, int h) {
        List<ScreenText.Item[]> cards = new ArrayList<>();
        for (ScreenText.Item c : st.items) {
            if (!COORDS.matcher(c.text).matches()) continue;
            ScreenText.Item title = null;
            for (ScreenText.Item t : st.items)
                if (t != c && t.cy() < c.cy() && c.cy() - t.cy() < h * 0.05f && Math.abs(t.left - c.left) < w * 0.1f
                        && (title == null || t.cy() > title.cy())) title = t;
            cards.add(new ScreenText.Item[]{title, c});
        }
        cards.sort((a, b2) -> Integer.compare(a[1].cy(), b2[1].cy()));
        return cards;
    }

    void onMine(ScreenText st, Frame f) {
        actionTaps = 0;
        List<int[]> b = badges(st, Math.round(f.h * 0.10f), Math.round(f.h * 0.90f));
        if (b.isEmpty()) {
            if (mineScrolls++ < 5) {
                svc.status("Mine: scrolling to its activity");
                swipeUp(f, 0.30f);
            } else {
                svc.status("Mine: no activity found — going back");
                if (cfg.logTaps) log.event("no activity counter found at " + current);
                mineScrolls = 0;
                back(st, f, "no activity counter");
            }
            return;
        }
        int[] badge = b.get(0);
        if (badge[0] == 0) {
            mineScrolls = 0;
            alreadyDone++;
            svc.status("Already collected (0/" + badge[1] + ") — next mine");
            if (cfg.logTaps) log.event("already collected (0/" + badge[1] + ") at " + current);
            back(st, f, "mine already collected");
            AutoPlayerService.sleep(human.fast(900, 500));
            return;
        }
        // tap the activity picture between the counter and its "Tap to …" line
        ScreenText.Item hint = null;
        for (ScreenText.Item it : st.items)
            if (it.lower.startsWith("tap to") || it.lower.contains(" tap to")) {
                if (it.cy() > badge[3] && (hint == null || it.cy() < hint.cy())) hint = it;
            }
        int y = hint != null ? (badge[3] + hint.cy()) / 2 - Math.round(f.h * 0.03f) : badge[3] + Math.round(f.h * 0.12f);
        if (y > f.h * 0.82f) {           // picture too low: bring it up first
            swipeUp(f, 0.25f);
            return;
        }
        mineScrolls = 0;
        svc.status("Mine has " + badge[0] + "/" + badge[1] + " — opening the activity");
        pause();
        tapAt(f.w / 2, y, f.w * 0.15f, f.h * 0.03f, "open_activity", badge[0] + "/" + badge[1]);
        AutoPlayerService.sleep(human.fast(1200, 600));
    }

    void onList(ScreenText st, Frame f) {
        mineScrolls = 0;
        actionTaps = 0;
        // cards: a coordinates line with the mine's name just above it
        List<ScreenText.Item[]> cards = cards(st, f.w, f.h);
        StringBuilder keys = new StringBuilder();
        for (ScreenText.Item[] c : cards) keys.append(c[1].text).append(';');
        for (ScreenText.Item[] card : cards) {
            String key = card[1].text.replace(" ", "");
            if (visited.contains(key)) continue;
            int y = card[0] != null ? (card[0].cy() + card[1].cy()) / 2 : card[1].cy();
            if (y < f.h * 0.18f || y > f.h * 0.86f) continue;   // only cards fully on screen
            visited.add(key);
            current = (card[0] != null ? card[0].text + " " : "") + card[1].text;
            opened++;
            listStill = 0;
            svc.status("Opening " + current + " (" + opened + " opened, " + collected + " collected)");
            if (cfg.logTaps) log.event("open " + current);
            pause();
            tapAt(card[1].cx(), y, Math.max(20, card[1].width() / 3f), f.h * 0.012f, "open_property", current);
            AutoPlayerService.sleep(human.fast(1500, 700));
            return;
        }
        // nothing new on screen: load more, or scroll on
        ScreenText.Item more = st.find("load 20 more", "load more");
        if (more != null && more.cy() < f.h * 0.88f) {
            svc.status("Loading more properties");
            tapItem(more, "load_more");
            AutoPlayerService.sleep(human.fast(1800, 900));
            return;
        }
        String k = keys.toString();
        if (k.equals(lastListKeys)) listStill++;
        else listStill = 0;
        lastListKeys = k;
        if (listStill >= 3) {
            finishPass();
            return;
        }
        svc.status("Scrolling the list (" + visited.size() + " checked, " + collected + " collected)");
        swipeUp(f, 0.38f);
    }

    void finishPass() {
        String msg = "Pass done: opened " + opened + ", collected " + collected + ", already done " + alreadyDone;
        if (cfg.logTaps) log.event(msg);
        long waitMs = cfg.collectRepeatMin * 60_000L;
        long end = System.currentTimeMillis() + waitMs;
        while (running && System.currentTimeMillis() < end) {
            svc.status(msg + "\nNext pass in " + ((end - System.currentTimeMillis()) / 60_000 + 1) + " min");
            AutoPlayerService.sleep(5000);
        }
        visited.clear();
        listStill = 0;
        lastListKeys = "";
        svc.status("Starting a new pass — scroll to the top of the list");
        if (cfg.logTaps) log.event("new pass");
        // back to the top of the list
        Frame f = svc.screenshot();
        if (f != null)
            for (int i = 0; i < 12 && running; i++)
                svc.swipe(f.w / 2, Math.round(f.h * 0.35f), f.w / 2, Math.round(f.h * 0.80f), 250);
    }

    void onUnknown(ScreenText st, Frame f) {
        unknownTicks++;
        if (unknownTicks < 4) { svc.status("Waiting… (unrecognised screen)"); return; }
        if (cfg.logTaps) log.event("lost (" + st.dump().substring(0, Math.min(200, st.dump().length())) + ")");
        // the bottom bar's Profile tab leads back to Properties
        ScreenText.Item profile = null;
        for (ScreenText.Item it : st.items)
            if (it.lower.equals("profile") && it.cy() > f.h * 0.8f) { profile = it; break; }
        if (profile != null) {
            svc.status("Lost — going to Profile");
            tapItem(profile, "nav");
        } else {
            svc.status("Lost — pressing Back");
            back(st, f, "unrecognised screen");
        }
        unknownTicks = 0;
    }
}
