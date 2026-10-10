package com.kiwyu.tapassist;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Saves the raw screenshot and an annotated copy (detected roads, scores) to Pictures/TapAssist. */
public class DebugDump {
    public static String save(Context ctx, Frame f, Board b, String note) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        try {
            write(ctx, f.bitmap, "shot_" + stamp + "_raw.png");
            if (b != null && b.masks != null) {
                Bitmap ann = f.bitmap.copy(Bitmap.Config.ARGB_8888, true);
                annotate(new Canvas(ann), b, note);
                write(ctx, ann, "shot_" + stamp + "_annotated.png");
                ann.recycle();
            }
            return "Pictures/" + ctx.getString(R.string.files_folder) + "/shot_" + stamp;
        } catch (Exception e) {
            return "save failed: " + e.getMessage();
        }
    }

    static void annotate(Canvas cv, Board b, String note) {
        float s = b.pitch();
        Paint grid = new Paint(); grid.setStyle(Paint.Style.STROKE); grid.setStrokeWidth(3); grid.setColor(Color.CYAN);
        Paint pipe = new Paint(); pipe.setStrokeWidth(Math.max(4, s / 12)); pipe.setColor(Color.MAGENTA);
        Paint txt = new Paint(); txt.setColor(Color.WHITE); txt.setTextSize(Math.max(20, s / 7));
        txt.setShadowLayer(4, 0, 0, Color.BLACK);
        Paint big = new Paint(txt); big.setTextSize(Math.max(30, s / 3)); big.setColor(Color.RED);
        for (int r = 0; r < b.rows; r++)
            for (int c = 0; c < b.cols; c++) {
                int x = b.cx(c), y = b.cy(r), i = r * b.cols + c;
                cv.drawRect(x - b.pitchX / 2, y - b.pitchY / 2, x + b.pitchX / 2, y + b.pitchY / 2, grid);
                if (r == b.srcR && c == b.srcC) { cv.drawText("L", x - s / 8, y + s / 8, big); continue; }
                if (r == b.dstR && c == b.dstC) { cv.drawText("P", x - s / 8, y + s / 8, big); continue; }
                int m = b.masks[i];
                for (int side = 0; side < 4; side++) {
                    float ex = x + Solver.DC[side] * s * 0.45f, ey = y + Solver.DR[side] * s * 0.45f;
                    if ((m & (1 << side)) != 0) cv.drawLine(x, y, ex, ey, pipe);
                    float tx = x + Solver.DC[side] * s * 0.30f - s / 10, ty = y + Solver.DR[side] * s * 0.30f + s / 20;
                    cv.drawText(String.valueOf(b.scores[i][side]), tx, ty, txt);
                }
            }
        if (note != null) cv.drawText(note, 20, Math.max(60, b.top - s / 2), big);
    }

    static void write(Context ctx, Bitmap bmp, String name) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ctx.getString(R.string.files_folder));
        Uri uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("MediaStore insert failed");
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
    }
}
