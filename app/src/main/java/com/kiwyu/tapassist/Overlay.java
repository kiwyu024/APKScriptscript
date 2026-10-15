package com.kiwyu.tapassist;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Floating, draggable start/stop button with a small menu, drawn as an accessibility overlay. */
public class Overlay {
    final AutoPlayerService svc;
    final WindowManager wm;
    LinearLayout root, menu;
    Button startBtn;
    TextView status;
    WindowManager.LayoutParams lp;

    Overlay(AutoPlayerService svc) {
        this.svc = svc;
        this.wm = svc.getSystemService(WindowManager.class);
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, svc.getResources().getDisplayMetrics()));
    }

    void show() {
        root = new LinearLayout(svc);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC202020);
        bg.setCornerRadius(dp(14));
        root.setBackground(bg);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));

        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = smallButton("▶", 0xFF2E7D32);
        startBtn.setOnClickListener(v -> toggle());
        Button gear = smallButton("⚙", 0xFF455A64);
        gear.setOnClickListener(v -> menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        Button speedBtn = smallButton(speedLabel(Config.load(svc).speed), 0xFF6D4C41);
        speedBtn.setTextSize(13);
        speedBtn.setOnClickListener(v -> {
            float[] steps = {1f, 1.25f, 1.5f, 1.75f, 2f, 3f};
            float cur = Config.load(svc).speed, next = steps[0];
            for (int i = 0; i < steps.length; i++)
                if (Math.abs(steps[i] - cur) < 0.01f) { next = steps[(i + 1) % steps.length]; break; }
            svc.setSpeed(next);
            speedBtn.setText(speedLabel(next));
            setStatus("Speed " + speedLabel(next));
        });
        row.addView(startBtn);
        row.addView(speedBtn);
        row.addView(gear);
        root.addView(row);

        status = new TextView(svc);
        status.setTextColor(Color.WHITE);
        status.setTextSize(11);
        status.setMaxWidth(dp(110));
        status.setTypeface(android.graphics.Typeface.MONOSPACE);
        status.setText("Stopped");
        root.addView(status);

        menu = new LinearLayout(svc);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setVisibility(View.GONE);
        Button modeItem = menuItem(modeLabel(), v -> { });
        modeItem.setOnClickListener(v -> {
            menu.setVisibility(View.GONE);
            if (svc.isRunning()) { setStatus("Stop the bot (■) before switching mode"); return; }
            Config c = Config.load(svc);
            c.mode = "collect".equals(c.mode) ? "puzzle" : "collect";
            c.save(svc);
            modeItem.setText(modeLabel());
            setStatus("Mode: " + ("collect".equals(c.mode) ? "Collect resources" : "Gold Rush puzzle"));
        });
        menu.addView(modeItem);
        menu.addView(menuItem("Read screen text", v -> readText()));
        menu.addView(menuItem("Check board now", v -> snapshot(false)));
        menu.addView(menuItem("Save debug snapshot", v -> snapshot(true)));
        menu.addView(menuItem("Add ad X position", v -> Calibrator.adClose(svc, this)));
        menu.addView(menuItem("Clear ad X positions", v -> {
            Config c = Config.load(svc); c.adClosePoints = ""; c.save(svc); setStatus("Ad X positions cleared");
        }));
        menu.addView(menuItem("Manual grid (fallback)", v -> Calibrator.grid(svc, this)));
        menu.addView(menuItem("Settings", v -> {
            Intent i = new Intent(svc, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            svc.startActivity(i);
        }));
        root.addView(menu);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // Anchored bottom-right (over the game's Settings tab) so it stays clear of the board, the ad X,
        // Play Again / Watch Ad and Exit; status text grows upwards. It appears in screenshots, so keep it there.
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        android.content.SharedPreferences p = svc.getSharedPreferences(Config.PREFS, 0);
        lp.x = p.getInt("panelX", dp(4));
        lp.y = p.getInt("panelY", dp(60));
        clamp();   // a position saved off-screen comes back into view

        // Drag by grabbing anywhere on the panel background or buttons.
        View.OnTouchListener drag = new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; moved = false;
                        return v == root;   // buttons keep handling their own clicks
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!moved && Math.hypot(dx, dy) < dp(8)) return false;
                        moved = true;
                        lp.x = ox - Math.round(dx); lp.y = oy - Math.round(dy);   // measured from bottom-right
                        clamp();
                        wm.updateViewLayout(root, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            p.edit().putInt("panelX", lp.x).putInt("panelY", lp.y).apply();
                            v.setPressed(false);
                            return true;   // swallow the click after a drag
                        }
                        return false;
                }
                return false;
            }
        };
        startBtn.setOnTouchListener(drag);
        gear.setOnTouchListener(drag);
        root.setOnTouchListener(drag);
        wm.addView(root, lp);
    }

    static String speedLabel(float s) {
        return (s == Math.round(s) ? String.valueOf(Math.round(s)) : String.valueOf(s)) + "x";
    }

    Button smallButton(String text, int color) {
        Button b = new Button(svc);
        b.setText(text);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(24));
        b.setBackground(d);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(48), dp(48));
        l.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(l);
        return b;
    }

    Button menuItem(String text, View.OnClickListener l) {
        Button b = new Button(svc);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(v -> { menu.setVisibility(View.GONE); l.onClick(v); });
        return b;
    }

    void toggle() {
        if (svc.isRunning()) {
            svc.stopLoop();
            startBtn.setText("▶");
            ((GradientDrawable) startBtn.getBackground()).setColor(0xFF2E7D32);
        } else {
            svc.startLoop();
            startBtn.setText("■");
            ((GradientDrawable) startBtn.getBackground()).setColor(0xFFC62828);
        }
    }

    /** Reads the current screen and shows what the bot sees (optionally saving images). */
    void snapshot(boolean save) {
        setStatus("Reading screen…");
        new Thread(() -> {
            AutoPlayerService.sleep(300);
            Frame f = svc.screenshotWithoutPanel();
            if (f == null) { svc.status("Screenshot failed"); return; }
            Config cfg = Config.load(svc);
            Board b = Vision.findBoard(f, cfg);
            StringBuilder sb = new StringBuilder();
            if (b == null) sb.append("No board found.\n");
            else {
                Vision.read(f, b, cfg);
                for (String line : Solver.describe(b)) sb.append(line).append('\n');
                if (b.ready()) {
                    Solver s = new Solver(b, cfg.tapDir);
                    sb.append(s.solve() == null ? "No road found\n" : "Road: " + s.bestTaps() + " taps\n");
                } else sb.append("Leprechaun/pot not found\n");
            }
            int[] rb = Vision.findReplayButton(f, b == null ? f.h / 2 : Math.max(f.h / 2, b.top + b.size + 4));
            if (rb != null) sb.append("Replay button at ").append(rb[0]).append(',').append(rb[1]).append('\n');
            int[] x = Vision.findCloseX(f);
            sb.append("X score ").append(x[0]).append(" at ").append(x[1]).append(',').append(x[2]);
            if (save) sb.append('\n').append(DebugDump.save(svc, f, b, "snapshot"));
            svc.status(sb.toString());
        }).start();
    }

    String modeLabel() {
        return "collect".equals(Config.load(svc).mode) ? "Mode: Collect resources ⇄" : "Mode: Gold Rush puzzle ⇄";
    }

    /** Shows what text the bot can read on screen (accessibility or OCR). */
    void readText() {
        setStatus("Reading screen text…");
        new Thread(() -> {
            AutoPlayerService.sleep(300);
            Frame f = svc.screenshot();
            ScreenText a = ScreenText.read(svc, f, false);
            ScreenText o = f == null ? new ScreenText() : ScreenText.read(svc, f, true);
            StringBuilder sb = new StringBuilder();
            sb.append("Accessibility: ").append("accessibility".equals(a.source) ? a.items.size() : 0).append(" texts\n");
            sb.append("OCR: ").append(o.items.size()).append(" texts\n");
            ScreenText best = a.items.size() >= o.items.size() ? a : o;
            for (int i = 0; i < Math.min(6, best.items.size()); i++) sb.append(best.items.get(i).text).append('\n');
            svc.status(sb.toString().trim());
        }).start();
    }

    void setStatus(String s) { if (status != null) status.setText(s); }

    /** Panel bounds on screen (main thread), or null when not shown. */
    android.graphics.Rect screenRect() {
        if (root == null || root.getVisibility() != View.VISIBLE || root.getWidth() == 0) return null;
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        return new android.graphics.Rect(loc[0], loc[1], loc[0] + root.getWidth(), loc[1] + root.getHeight());
    }

    /** Makes the panel invisible (but keeps its place) so it doesn't show up in a screenshot. */
    void setTransparent(boolean on) { if (root != null) root.setAlpha(on ? 0f : 1f); }

    /** While the bot taps, let touches fall through the panel so it can never swallow a tap. */
    void setPassThrough(boolean on) {
        if (root == null) return;
        if (on) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
    }

    /** Keeps the panel on screen (so it can always be grabbed again). */
    void clamp() {
        android.util.DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int w = root != null && root.getWidth() > 0 ? root.getWidth() : dp(110);
        int h = root != null && root.getHeight() > 0 ? root.getHeight() : dp(70);
        lp.x = Math.max(0, Math.min(lp.x, dm.widthPixels - w));
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - h));
    }

    /** Puts the panel back in its default spot (bottom-right, over the game's Settings tab). */
    void resetPosition() {
        if (root == null) return;
        lp.x = dp(4);
        lp.y = dp(60);
        svc.getSharedPreferences(Config.PREFS, 0).edit().putInt("panelX", lp.x).putInt("panelY", lp.y).apply();
        root.setVisibility(View.VISIBLE);
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
    }

    void setVisible(boolean v) { if (root != null) root.setVisibility(v ? View.VISIBLE : View.GONE); }

    void hide() {
        if (root != null) { try { wm.removeView(root); } catch (Exception ignored) { } root = null; }
    }
}
