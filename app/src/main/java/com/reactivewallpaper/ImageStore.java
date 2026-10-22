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

    /**
     * Picks the image's two most prominent vivid hues (e.g. pink and cyan in
     * neon art) so "auto" coloring can blend between them. When the image has
     * a single dominant hue, both entries are close to it.
     */
    static float[] accentHues(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 48, 48, true);
        float[] hsv = new float[3];
        float[] histogram = new float[36];
        float total = 0f;
        for (int py = 0; py < 48; py++) {
            for (int px = 0; px < 48; px++) {
                Color.colorToHSV(small.getPixel(px, py), hsv);
                float weight = hsv[1] * hsv[1] * hsv[2] * hsv[2];
                histogram[(int) (hsv[0] / 10f) % 36] += weight;
                total += weight;
            }
        }
        if (small != bitmap) {
            small.recycle();
        }
        if (total < 2f) {
            // Mostly grey image: a clean cyan reads well on it.
            return new float[] {180f, 200f};
        }
        // Smooth the histogram over neighbouring bins so peaks aren't split.
        float[] smooth = new float[36];
        for (int i = 0; i < 36; i++) {
            smooth[i] = histogram[(i + 35) % 36] * 0.5f + histogram[i] + histogram[(i + 1) % 36] * 0.5f;
        }
        int first = 0;
        for (int i = 1; i < 36; i++) {
            if (smooth[i] > smooth[first]) {
                first = i;
            }
        }
        int second = -1;
        for (int i = 0; i < 36; i++) {
            int distance = Math.min(Math.abs(i - first), 36 - Math.abs(i - first));
            if (distance >= 6 && (second < 0 || smooth[i] > smooth[second])) {
                second = i;
            }
        }
        float hueA = first * 10f + 5f;
        if (second < 0 || smooth[second] < smooth[first] * 0.25f) {
            return new float[] {hueA, hueA + 40f};
        }
        float hueB = second * 10f + 5f;
        // Order low-to-high so the blend runs the same way each time.
        return hueA < hueB ? new float[] {hueA, hueB} : new float[] {hueB, hueA};
    }
}
