package com.kiwyu.tapassist;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.android.gms.tasks.Tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The text on screen with positions: from the game's accessibility tree when it exposes text (a web view
 * usually does), otherwise read from the screenshot with ML Kit's on-device text recognition.
 */
public class ScreenText {
    public static class Item {
        public final String text, lower;
        public final int left, top, right, bottom;
        public final AccessibilityNodeInfo node;   // null for OCR results
        public Item(String text, int left, int top, int right, int bottom, AccessibilityNodeInfo node) {
            this.text = text.trim();
            this.lower = this.text.toLowerCase(Locale.US);
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.node = node;
        }
        public int cx() { return (left + right) / 2; }
        public int cy() { return (top + bottom) / 2; }
        public int width() { return right - left; }
        public int height() { return bottom - top; }
        @Override public String toString() { return "'" + text + "' @" + cx() + "," + cy(); }
    }

    public final List<Item> items = new ArrayList<>();
    public String source = "";

    private static TextRecognizer ocr;

    /** Reads accessibility text; if there's hardly any, falls back to OCR on the screenshot. */
    public static ScreenText read(AutoPlayerService svc, Frame f, boolean forceOcr) {
        ScreenText st = new ScreenText();
        if (!forceOcr) {
            st.fromNodes(svc, f);
            st.source = "accessibility";
        }
        if (st.items.size() < 4 && f != null && f.bitmap != null) {
            ScreenText o = new ScreenText();
            o.fromOcr(f);
            o.source = "ocr";
            if (o.items.size() > st.items.size()) return o;
        }
        return st;
    }

    void fromNodes(AutoPlayerService svc, Frame f) {
        List<AccessibilityWindowInfo> wins;
        try { wins = svc.getWindows(); } catch (Exception e) { return; }
        for (AccessibilityWindowInfo w : wins) {
            // only app windows: skips the status bar clock, the nav bar's Back/Home/Recents,
            // Game Booster and our own panel
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence pkg = root.getPackageName();
            if (pkg != null && pkg.toString().equals(svc.getPackageName())) continue;
            walk(root, f, 0);
        }
    }

    void walk(AccessibilityNodeInfo n, Frame f, int depth) {
        if (n == null || depth > 80) return;
        if (n.isVisibleToUser()) {
            String t = n.getText() == null ? "" : n.getText().toString();
            if (t.trim().isEmpty() && n.getContentDescription() != null) t = n.getContentDescription().toString();
            if (!t.trim().isEmpty()) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (!r.isEmpty() && (f == null || (r.bottom > 0 && r.top < f.h)))
                    items.add(new Item(t, r.left, r.top, r.right, r.bottom, n));
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), f, depth + 1);
    }

    void fromOcr(Frame f) {
        try {
            if (ocr == null) ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            Text result = Tasks.await(ocr.process(InputImage.fromBitmap(f.bitmap, 0)), 5, TimeUnit.SECONDS);
            for (Text.TextBlock b : result.getTextBlocks())
                for (Text.Line line : b.getLines()) {
                    Rect r = line.getBoundingBox();
                    if (r != null) items.add(new Item(line.getText(), r.left, r.top, r.right, r.bottom, null));
                }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ queries
    /** First item containing any of the words (case-insensitive), or null. */
    public Item find(String... words) {
        for (Item it : items) for (String w : words) if (it.lower.contains(w)) return it;
        return null;
    }

    public List<Item> findAll(String word) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) if (it.lower.contains(word)) out.add(it);
        return out;
    }

    public boolean has(String... words) { return find(words) != null; }

    public String dump() {
        StringBuilder sb = new StringBuilder(source).append(": ");
        for (Item it : items) sb.append(it).append(" | ");
        return sb.toString();
    }
}
