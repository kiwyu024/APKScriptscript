package com.kiwyu.tapassist;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AutoPlayerService extends AccessibilityService {
    static volatile AutoPlayerService instance;

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService shotExec = Executors.newSingleThreadExecutor();
    Overlay overlay;
    GameLoop loop;

    // Foreground tracking (written from main thread, read from worker).
    volatile String fgPackage = "";
    volatile String fgClass = "";
    volatile long adSeenAt = 0;

    /** Activity / package fragments used by common ad SDKs. */
    static final String[] AD_MARKERS = {
            "adactivity", "com.google.android.gms.ads", "unity3d.ads", "unity3d.services.ads", "applovin",
            "ironsource", "vungle", "liftoff", "mbridge", "mintegral", "chartboost", "fyber", "inmobi",
            "facebook.ads", "audiencenetwork", "pangle", "bytedance.sdk", "adcolony", "digitalturbine",
            "moloco", "bigo.ads", "yandex.mobile.ads", "interstitial", "rewardedvideo", "fullscreenad"
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        overlay = new Overlay(this);
        overlay.show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(getPackageName())) return;
        String c = cls == null ? "" : cls.toString();
        // Ignore transient system UI (notification shade, volume, keyboard).
        if (p.equals("com.android.systemui") || p.contains("inputmethod") || p.contains("honeyboard")) return;
        fgPackage = p;
        if (c.contains(".") || c.isEmpty()) fgClass = c;   // skip widget-only class names like "FrameLayout"
        if (isAdName(p) || isAdName(c)) adSeenAt = System.currentTimeMillis();
    }

    static boolean isAdName(String s) {
        String l = s.toLowerCase(Locale.US);
        for (String m : AD_MARKERS) if (l.contains(m)) return true;
        return false;
    }

    /** True when the foreground window currently looks like an ad. */
    boolean adInForeground() {
        return isAdName(fgClass) || isAdName(fgPackage);
    }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        stopLoop();
        if (overlay != null) overlay.hide();
        instance = null;
        shotExec.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ control
    void startLoop() {
        if (loop != null && loop.running) return;
        Config cfg = Config.load(this);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null
                && !root.getPackageName().toString().equals(getPackageName())) {
            cfg.gamePackage = root.getPackageName().toString();
            cfg.save(this);
        }
        loop = new GameLoop(this, cfg);
        loop.start();
    }

    void stopLoop() {
        if (loop != null) loop.stop();
        loop = null;
    }

    boolean isRunning() { return loop != null && loop.running; }

    void status(String s) {
        main.post(() -> { if (overlay != null) overlay.setStatus(s); });
    }

    // ------------------------------------------------------------- screenshots
    /** Panel bounds on screen, read on the main thread (null if hidden). */
    Rect panelRect() {
        AtomicReference<Rect> out = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> { if (overlay != null) out.set(overlay.screenRect()); latch.countDown(); });
        try { latch.await(1, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        return out.get();
    }

    /** Screenshot with the floating panel made invisible for the capture (it draws over whatever is under it). */
    Frame screenshotWithoutPanel() {
        CountDownLatch hidden = new CountDownLatch(1);
        main.post(() -> { if (overlay != null) overlay.setTransparent(true); hidden.countDown(); });
        try { hidden.await(1, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        sleep(120);   // let a frame without the panel reach the screen
        try { return screenshot(); }
        finally { main.post(() -> { if (overlay != null) overlay.setTransparent(false); }); }
    }

    /** Blocking screenshot (call from a worker thread). Retries when the system rate limit kicks in. */
    Frame screenshot() {
        for (int attempt = 0; attempt < 4; attempt++) {
            AtomicReference<Bitmap> out = new AtomicReference<>();
            AtomicReference<Integer> err = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExec, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    HardwareBuffer hb = r.getHardwareBuffer();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                    if (hw != null) {
                        out.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                        hw.recycle();
                    }
                    hb.close();
                    latch.countDown();
                }
                @Override public void onFailure(int code) { err.set(code); latch.countDown(); }
            });
            try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ie) { return null; }
            if (out.get() != null) return new Frame(out.get());
            Integer code = err.get();
            if (code != null && code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) sleep(400);
            else sleep(200);
        }
        return null;
    }

    // -------------------------------------------------------------------- taps
    volatile boolean passThroughOn = false;
    final Runnable passThroughOff = () -> {
        passThroughOn = false;
        if (overlay != null) overlay.setPassThrough(false);
    };

    /** Moves the panel back to its default spot if it sits over the given area. True if it was moved. */
    boolean movePanelOffIfOver(Rect area) {
        Rect pr = panelRect();
        if (pr == null || !Rect.intersects(pr, area)) return false;
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> { if (overlay != null) overlay.resetPosition(); done.countDown(); });
        try { done.await(1, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        sleep(200);
        return true;
    }

    /** Blocking tap at screen coordinates (call from a worker thread). */
    boolean tap(int x, int y) { return tap(x, y, 40, 0, 0); }

    /** Blocking tap held for {@code pressMs}, sliding (dx, dy) pixels while down. */
    boolean tap(int x, int y, int pressMs, int dx, int dy) {
        Path path = new Path();
        path.moveTo(Math.max(0, x), Math.max(0, y));
        if (dx != 0 || dy != 0) path.lineTo(Math.max(0, x + dx), Math.max(0, y + dy));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, pressMs))).build();
        // A tap under the floating panel would be caught by the panel. Make it let touches through and
        // wait for that to take effect before tapping (the window change is applied asynchronously);
        // it stays that way until taps stop for a moment.
        Rect pr = panelRect();
        if (pr != null) {
            pr.inset(-24, -24);
            if (pr.contains(x, y) && !passThroughOn) {
                CountDownLatch applied = new CountDownLatch(1);
                main.post(() -> { if (overlay != null) overlay.setPassThrough(true); applied.countDown(); });
                try { applied.await(1, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                passThroughOn = true;
                sleep(150);
            }
        }
        AtomicBoolean ok = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            boolean sent = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { ok.set(true); latch.countDown(); }
                @Override public void onCancelled(GestureDescription d) { latch.countDown(); }
            }, null);
            if (!sent) latch.countDown();
        });
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        if (passThroughOn) {
            main.removeCallbacks(passThroughOff);
            main.postDelayed(passThroughOff, 1500);
        }
        return ok.get();
    }

    // -------------------------------------------------------------- node search
    /** Finds visible nodes (any window) whose text or content description matches one of the labels. */
    List<AccessibilityNodeInfo> findNodes(String[] labels, boolean exact) {
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        List<AccessibilityWindowInfo> wins;
        try { wins = getWindows(); } catch (Exception e) { return found; }
        for (AccessibilityWindowInfo w : wins) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            if (root.getPackageName() != null && root.getPackageName().toString().equals(getPackageName())) continue;
            walk(root, labels, exact, found, 0);
        }
        return found;
    }

    void walk(AccessibilityNodeInfo n, String[] labels, boolean exact, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 60) return;
        if (n.isVisibleToUser()) {
            String t = n.getText() == null ? "" : n.getText().toString().trim();
            String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
            for (String l : labels) {
                if (matches(t, l, exact) || matches(d, l, exact)) { out.add(n); break; }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), labels, exact, out, depth + 1);
    }

    static boolean matches(String s, String label, boolean exact) {
        if (s.isEmpty()) return false;
        return exact ? s.equalsIgnoreCase(label) : s.toLowerCase(Locale.US).contains(label.toLowerCase(Locale.US));
    }

    /** Clicks a node via accessibility action, falling back to tapping its centre. */
    boolean clickNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            cur = cur.getParent();
        }
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (b.isEmpty()) return false;
        return tap(b.centerX(), b.centerY());
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
