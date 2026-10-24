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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Keeps a private copy of the chosen image so the wallpaper never loses access to it. */
final class ImageStore {
    private static final String TAG = "ImageStore";
    private static final String FILE_NAME = "wallpaper_image";
    private static final String DRAFT_FILE_NAME = "editor_image";
    private static final String MASK_FILE_NAME = "wallpaper_mask.png";
    private static final String DRAFT_MASK_FILE_NAME = "editor_mask.png";
    private static final String MEDIA_FILE_NAME = "wallpaper_media";
    private static final String DRAFT_MEDIA_FILE_NAME = "editor_media";

    /**
     * Everything that makes up a background: the still image (for a video or
     * GIF, its first frame), the painted glow mask, and the video or animated
     * file if there is one. Missing files mean "none". They always move together.
     */
    static final class Picture {
        final File image;
        final File mask;
        final File media;

        Picture(File image, File mask, File media) {
            this.image = image;
            this.mask = mask;
            this.media = media;
        }

        boolean hasImage() {
            return image.length() > 0;
        }

        /** Changes whenever any of the files does, for spotting a reload. */
        String stamp() {
            return image.getPath() + ":" + image.lastModified() + ":" + mask.lastModified() + ":" + media.lastModified();
        }
    }

    /** The background the wallpaper shows. */
    static Picture live(Context context) {
        return new Picture(file(context), maskFile(context), new File(context.getFilesDir(), MEDIA_FILE_NAME));
    }

    /** The background being edited. */
    static Picture draft(Context context) {
        return new Picture(draftFile(context), draftMaskFile(context),
                new File(context.getFilesDir(), DRAFT_MEDIA_FILE_NAME));
    }

    /** Copies all parts of a background; a missing part removes the target's. Call off the main thread. */
    static void copy(Picture from, Picture to) {
        copyOrDelete(from.image, to.image);
        copyOrDelete(from.mask, to.mask);
        copyOrDelete(from.media, to.media);
    }

    private static void copyOrDelete(File from, File to) {
        if (from.length() > 0) {
            copy(from, to);
        } else {
            to.delete();
        }
    }

    private ImageStore() {}

    /** The image the wallpaper shows. */
    static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** The image being edited, which reaches the wallpaper only when applied. */
    static File draftFile(Context context) {
        return new File(context.getFilesDir(), DRAFT_FILE_NAME);
    }

    /** The painted glow mask for {@link #file}; absent means "all automatic". */
    static File maskFile(Context context) {
        return new File(context.getFilesDir(), MASK_FILE_NAME);
    }

    static File draftMaskFile(Context context) {
        return new File(context.getFilesDir(), DRAFT_MASK_FILE_NAME);
    }

    static Bitmap loadMask(File f) {
        if (f.length() == 0) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getPath(), opts);
    }

    /** Saves a mask as PNG, or deletes the file when {@code mask} is null. */
    static boolean saveMask(Bitmap mask, File f) {
        if (mask == null) {
            f.delete();
            return true;
        }
        File temp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            mask.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.w(TAG, "Could not save mask", e);
            temp.delete();
            return false;
        }
        return temp.renameTo(f);
    }

    /** Copies one image file to another, via a temp file so a reader never sees half a file. */
    static boolean copy(File from, File to) {
        File temp = new File(to.getPath() + ".tmp");
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not copy image", e);
            temp.delete();
            return false;
        }
        return temp.renameTo(to);
    }

    static boolean hasImage(Context context) {
        return file(context).length() > 0;
    }

    static boolean hasDraftImage(Context context) {
        return draftFile(context).length() > 0;
    }

    /**
     * Copies a picked image, animated GIF/WebP or video into the editor's draft
     * and returns its {@code WallpaperSettings.MEDIA_*} type, or -1 if it can't
     * be used. For moving backgrounds a still first frame is saved as the image.
     * Call off the main thread.
     */
    static int importBackground(Context context, Uri uri) {
        Picture draft = draft(context);
        String type = context.getContentResolver().getType(uri);
        boolean video = type != null && type.startsWith("video/");
        boolean maybeAnimated = type != null && (type.equals("image/gif") || type.equals("image/webp"));
        if (!video && !maybeAnimated) {
            if (!importImage(context, uri)) {
                return -1;
            }
            draft.media.delete();
            return WallpaperSettings.MEDIA_IMAGE;
        }
        if (video && android.os.Build.VERSION.SDK_INT < 29) {
            return -1;
        }
        if (!copyUri(context, uri, draft.media)) {
            return -1;
        }
        if (maybeAnimated) {
            boolean animated = android.os.Build.VERSION.SDK_INT >= 28 && AnimatedBackground.isAnimated(draft.media);
            // The file's first frame doubles as the still image.
            boolean ok = copy(draft.media, draft.image);
            if (!animated) {
                draft.media.delete();
            }
            return ok ? (animated ? WallpaperSettings.MEDIA_ANIMATED : WallpaperSettings.MEDIA_IMAGE) : -1;
        }
        Bitmap poster = videoPoster(draft.media);
        if (poster == null) {
            draft.media.delete();
            return -1;
        }
        boolean saved = saveJpeg(poster, draft.image);
        poster.recycle();
        return saved ? WallpaperSettings.MEDIA_VIDEO : -1;
    }

    /** The first frame of a video, upright. */
    private static Bitmap videoPoster(File video) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getPath());
            Bitmap frame = retriever.getFrameAtTime(0);
            if (frame == null) {
                return null;
            }
            int rotation = 0;
            int w = 0;
            int h = 0;
            try {
                rotation = Integer.parseInt(retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                w = Integer.parseInt(retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                h = Integer.parseInt(retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            } catch (NumberFormatException | NullPointerException ignored) {
            }
            // Frames usually come back upright, but some phones return them as stored.
            // A 90/270 degree video still in its stored shape is sideways: turn it.
            boolean sideways = rotation % 180 != 0 && w != h
                    && (frame.getWidth() > frame.getHeight()) == (w > h);
            if (sideways) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap turned = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), frame.getHeight(), m, true);
                if (turned != frame) {
                    frame.recycle();
                }
                return turned;
            }
            return frame;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read video", e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static boolean saveJpeg(Bitmap bitmap, File f) {
        File temp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
        } catch (IOException e) {
            temp.delete();
            return false;
        }
        return temp.renameTo(f);
    }

    private static boolean copyUri(Context context, Uri uri, File target) {
        File temp = new File(target.getPath() + ".tmp");
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
            Log.w(TAG, "Could not import file", e);
            temp.delete();
            return false;
        }
        return temp.renameTo(target);
    }

    /** Copies the picked image into the editor's draft. Call off the main thread. */
    static boolean importImage(Context context, Uri uri) {
        File target = draftFile(context);
        File temp = new File(context.getFilesDir(), DRAFT_FILE_NAME + ".tmp");
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
        return load(file(context), reqWidth, reqHeight);
    }

    static Bitmap load(File f, int reqWidth, int reqHeight) {
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
     * Picks the image's two most prominent vivid hues, dominant first (e.g. pink and cyan in
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
        // Dominant hue first: it colors the bass end and drives the bass glow.
        return new float[] {hueA, hueB};
    }
}
