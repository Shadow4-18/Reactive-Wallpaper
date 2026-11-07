package com.reactivewallpaper;

import android.Manifest;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps crash reports and builds a plain-text diagnostics report the user can
 * save and send. It never includes pictures or notification contents.
 */
final class Diagnostics {
    private static final int KEEP_CRASHES = 5;
    private static final int LOG_LINES = 400;

    private Diagnostics() {}

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "diagnostics");
        dir.mkdirs();
        return dir;
    }

    /** Saves a report of any crash in this process (editor or wallpaper), then lets it crash as usual. */
    static void install(Context context) {
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                saveCrash(app, thread, error);
            } catch (Throwable ignored) {
                // Never let reporting hide the original crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private static void saveCrash(Context context, Thread thread, Throwable error) throws IOException {
        File dir = dir(context);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        try (PrintWriter out = new PrintWriter(new FileWriter(new File(dir, "crash-" + stamp + ".txt")))) {
            out.println("Crash at " + stamp + " in thread " + thread.getName());
            out.println("Resona " + Updater.installedVersion(context));
            error.printStackTrace(out);
        }
        File[] crashes = crashFiles(context);
        for (int i = KEEP_CRASHES; i < crashes.length; i++) {
            crashes[i].delete();
        }
    }

    /** Newest first. */
    private static File[] crashFiles(Context context) {
        File[] files = dir(context).listFiles((d, name) -> name.startsWith("crash-"));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        return files;
    }

    /** The full report. Call off the main thread (it reads the app's log). */
    static String report(Context context) {
        StringBuilder r = new StringBuilder();
        line(r, "Resona diagnostics, " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
        line(r, "No pictures or notification contents are included.");

        section(r, "App and phone");
        line(r, "Resona " + Updater.installedVersion(context));
        line(r, "Phone: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        line(r, "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), build " + Build.DISPLAY);
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        line(r, "Screen: " + dm.widthPixels + "x" + dm.heightPixels + " px, density " + dm.density);

        section(r, "Access");
        line(r, "Microphone/audio: " + (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED ? "allowed" : "not allowed"));
        line(r, "Notification access (album art, per-song rotation): " + AlbumArtSource.hasAccess(context));
        line(r, "May install updates: " + context.getPackageManager().canRequestPackageInstalls());
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        line(r, "System battery saver on: " + (power != null && power.isPowerSaveMode()));

        section(r, "Wallpaper");
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context);
            ComponentName ours = new ComponentName(context, ReactiveWallpaperService.class);
            WallpaperInfo home = wm.getWallpaperInfo();
            line(r, "Home screen: " + (home == null ? "a still picture" : home.getComponent().equals(ours)
                    ? "Resona" : home.getComponent().flattenToShortString()));
            if (Build.VERSION.SDK_INT >= 34) {
                WallpaperInfo lock = wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
                line(r, "Lock screen: " + (lock == null ? "same as home or a still picture"
                        : lock.getComponent().equals(ours) ? "Resona" : lock.getComponent().flattenToShortString()));
            }
        } catch (RuntimeException e) {
            line(r, "Couldn't read wallpaper state: " + e);
        }
        line(r, "Lock screen look: " + WallpaperSettings.lockLookId(context));
        line(r, "Rotation: mode " + WallpaperSettings.rotationMode(context) + ", "
                + WallpaperSettings.rotationIds(context).size() + " looks, every "
                + WallpaperSettings.rotationMinutes(context) + " min");
        line(r, "Media button widgets: " + MediaWidgetProvider.widgetIds(context).length);

        section(r, "Pictures");
        picture(r, "Live", ImageStore.live(context));
        picture(r, "Editor", ImageStore.draft(context));
        line(r, "Saved looks: " + PresetStore.all(context).size() + " (including built-ins)");

        section(r, "Live settings");
        line(r, PresetStore.toJson(WallpaperSettings.load(context)).toString());
        section(r, "Editor settings");
        line(r, PresetStore.toJson(WallpaperSettings.loadDraft(context)).toString());

        section(r, "Recent crashes");
        File[] crashes = crashFiles(context);
        if (crashes.length == 0) {
            line(r, "None.");
        }
        for (File f : crashes) {
            line(r, readFile(f));
        }

        section(r, "Recent app log");
        line(r, recentLog());
        return r.toString();
    }

    private static void picture(StringBuilder r, String label, ImageStore.Picture p) {
        if (!p.hasImage()) {
            line(r, label + ": none");
            return;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(p.image.getPath(), bounds);
        line(r, label + ": " + bounds.outWidth + "x" + bounds.outHeight + " " + bounds.outMimeType
                + ", " + p.image.length() / 1024 + " KB, painted glow " + (p.mask.length() > 0)
                + ", subject " + (p.subject.length() > 0)
                + ", video/animation " + (p.media.length() > 0 ? p.media.length() / 1024 + " KB" : "none"));
    }

    /** This app's own recent log lines (apps may always read their own). */
    private static String recentLog() {
        try {
            java.lang.Process logcat = Runtime.getRuntime().exec(new String[] {
                    "logcat", "-d", "-t", String.valueOf(LOG_LINES), "--pid", String.valueOf(Process.myPid())});
            try (InputStream in = logcat.getInputStream()) {
                return readStream(in);
            }
        } catch (IOException e) {
            return "Couldn't read the log: " + e;
        }
    }

    private static String readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return readStream(in);
        } catch (IOException e) {
            return "Couldn't read " + f.getName();
        }
    }

    private static String readStream(InputStream in) throws IOException {
        StringWriter out = new StringWriter();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = reader.readLine()) != null) {
                out.append(l).append('\n');
            }
        }
        return out.toString();
    }

    private static void section(StringBuilder r, String title) {
        r.append("\n== ").append(title).append(" ==\n");
    }

    private static void line(StringBuilder r, String text) {
        r.append(text).append('\n');
    }
}
