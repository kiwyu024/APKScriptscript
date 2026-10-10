package com.kiwyu.tapassist;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records every tap the bot makes (time, position, press length, what it was for) so it can be
 * compared with what the game's anti-cheat logged. Saved as CSV in Download/TapAssist when the bot stops.
 */
public class TapLog {
    final StringBuilder sb = new StringBuilder("epoch_ms,x,y,press_ms,drift_x,drift_y,kind,detail\n");
    int count = 0;

    public synchronized void add(int x, int y, int pressMs, int[] drift, String kind, String detail) {
        sb.append(System.currentTimeMillis()).append(',').append(x).append(',').append(y).append(',')
                .append(pressMs).append(',').append(drift[0]).append(',').append(drift[1]).append(',')
                .append(kind).append(',').append(detail == null ? "" : detail.replace(',', ';')).append('\n');
        count++;
    }

    public synchronized String save(Context ctx) {
        if (count == 0) return null;
        String name = "taps_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TapAssist");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return "tap log save failed";
            try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            return "Download/TapAssist/" + name + " (" + count + " taps)";
        } catch (Exception e) {
            return "tap log save failed: " + e.getMessage();
        }
    }
}
