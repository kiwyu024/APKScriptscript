package com.kiwyu.tapassist;

import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Full-screen transparent overlay that records screen taps for calibration. */
public class Calibrator {
    interface Done { void onPoints(List<int[]> pts); }

    static void capture(AutoPlayerService svc, Overlay overlay, String[] prompts, Done done) {
        if (svc.isRunning()) overlay.toggle();   // stop the bot while calibrating
        WindowManager wm = svc.getSystemService(WindowManager.class);
        FrameLayout catcher = new FrameLayout(svc);
        catcher.setBackgroundColor(0x33000000);
        TextView tv = new TextView(svc);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setBackgroundColor(0xCC000000);
        tv.setPadding(30, 20, 30, 20);
        tv.setText(prompts[0] + "\n(tap this box to cancel)");
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        catcher.addView(tv, tlp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.gravity = Gravity.TOP | Gravity.START;

        List<int[]> pts = new ArrayList<>();
        overlay.setVisible(false);
        tv.setOnClickListener(v -> {
            try { wm.removeView(catcher); } catch (Exception ignored) { }
            overlay.setVisible(true);
            svc.status("Calibration cancelled");
        });
        catcher.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            pts.add(new int[]{Math.round(e.getRawX()), Math.round(e.getRawY())});
            if (pts.size() < prompts.length) {
                tv.setText(prompts[pts.size()] + "\n(tap this box to cancel)");
                // keep the hint away from where the user needs to tap next
                tlp.gravity = pts.size() % 2 == 1 ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;
                tv.setLayoutParams(tlp);
            } else {
                try { wm.removeView(catcher); } catch (Exception ignored) { }
                overlay.setVisible(true);
                done.onPoints(pts);
            }
            return true;
        });
        wm.addView(catcher, lp);
    }

    /** Fallback only: the board is normally found automatically from its yellow frame. */
    static void grid(AutoPlayerService svc, Overlay overlay) {
        capture(svc, overlay, new String[]{
                "Tap the CENTRE of the TOP-LEFT tile",
                "Tap the CENTRE of the BOTTOM-RIGHT tile"}, pts -> {
            Config cfg = Config.load(svc);
            cfg.tlX = pts.get(0)[0]; cfg.tlY = pts.get(0)[1];
            cfg.brX = pts.get(1)[0]; cfg.brY = pts.get(1)[1];
            if (!cfg.manualGrid()) { svc.status("Bad grid taps — tap top-left first, then bottom-right"); return; }
            cfg.save(svc);
            svc.status("Manual grid saved (used only if the board frame isn't found)");
        });
    }

    static void adClose(AutoPlayerService svc, Overlay overlay) {
        capture(svc, overlay, new String[]{"Tap where the ad's X is (it won't be pressed)"}, pts -> {
            Config cfg = Config.load(svc);
            String p = pts.get(0)[0] + "," + pts.get(0)[1];
            cfg.adClosePoints = cfg.adClosePoints.isEmpty() ? p : cfg.adClosePoints + ";" + p;
            cfg.save(svc);
            svc.status("Ad X saved: " + cfg.adClosePoints);
        });
    }
}
