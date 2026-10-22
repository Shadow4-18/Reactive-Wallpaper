package com.reactivewallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Keeps a private copy of the chosen image so the wallpaper never loses access to it. */
final class ImageStore {
    private static final String TAG = "ImageStore";
    private static final String FILE_NAME = "wallpaper_image";

    private ImageStore() {}

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    static boolean hasImage(Context context) {
        return file(context).length() > 0;
    }

    /** Copies the picked image into app storage. Call off the main thread. */
    static boolean importImage(Context context, Uri uri) {
        File target = file(context);
        File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) {
                return false;
            }
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Could not import image", e);
            temp.delete();
            return false;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(temp.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            temp.delete();
            return false;
        }
        return temp.renameTo(target);
    }

    /**
     * Decodes the stored image, upright, at roughly the requested size (never
     * smaller than needed to cover it). Returns null if no image is stored.
     */
    static Bitmap load(Context context, int reqWidth, int reqHeight) {
        File f = file(context);
        if (f.length() == 0) {
            return null;
        }
        String path = f.getPath();
        int rotation = 0;
        try {
            int orientation = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                rotation = 90;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                rotation = 180;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                rotation = 270;
            }
        } catch (IOException ignored) {
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int w = rotation % 180 == 0 ? opts.outWidth : opts.outHeight;
        int h = rotation % 180 == 0 ? opts.outHeight : opts.outWidth;
        if (w <= 0 || h <= 0) {
            return null;
        }
        // Allow some headroom so pulse/zoom still looks sharp.
        float cover = Math.max(reqWidth * 1.2f / w, reqHeight * 1.2f / h);
        int sample = 1;
        while (cover * sample * 2 <= 1f) {
            sample *= 2;
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            opts.inSampleSize = sample * 2;
            bitmap = BitmapFactory.decodeFile(path, opts);
        }
        if (bitmap == null || rotation == 0) {
            return bitmap;
        }
        Matrix m = new Matrix();
        m.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    /** Picks a vivid representative color from the image for "auto" coloring. */
    static int accentColor(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 32, 32, true);
        float[] hsv = new float[3];
        double x = 0;
        double y = 0;
        double weightSum = 0;
        for (int py = 0; py < 32; py++) {
            for (int px = 0; px < 32; px++) {
                Color.colorToHSV(small.getPixel(px, py), hsv);
                double weight = hsv[1] * hsv[1] * hsv[2];
                double rad = Math.toRadians(hsv[0]);
                x += Math.cos(rad) * weight;
                y += Math.sin(rad) * weight;
                weightSum += weight;
            }
        }
        if (small != bitmap) {
            small.recycle();
        }
        if (weightSum < 1.0) {
            // Mostly grey image: a clean white-ish cyan reads well on it.
            return Color.HSVToColor(new float[] {190f, 0.35f, 1f});
        }
        float hue = (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
        return Color.HSVToColor(new float[] {hue, 0.75f, 1f});
    }
}
