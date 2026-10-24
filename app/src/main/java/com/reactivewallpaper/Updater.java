package com.reactivewallpaper;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Finds newer Resona releases on GitHub and installs them through Android's
 * package installer (which always asks the user to confirm).
 */
final class Updater {
    private static final String LATEST_RELEASE =
            "https://api.github.com/repos/Shadow4-18/Reactive-Wallpaper/releases/latest";
    private static final String APK_NAME = "Resona.apk";
    private static final long MAX_APK_BYTES = 100L << 20;
    private static final long CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final String PREFS = "updates";

    static final class Release {
        final String version;
        final String notes;
        final String apkUrl;

        Release(String version, String notes, String apkUrl) {
            this.version = version;
            this.notes = notes;
            this.apkUrl = apkUrl;
        }
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    private Updater() {}

    static String installedVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    static boolean autoCheckEnabled(Context context) {
        return prefs(context).getBoolean("auto", true);
    }

    static void setAutoCheck(Context context, boolean on) {
        prefs(context).edit().putBoolean("auto", on).apply();
    }

    /** True if an automatic check is due (and records that one is happening now). */
    static boolean claimAutoCheck(Context context) {
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        if (!p.getBoolean("auto", true) || now - p.getLong("lastCheck", 0) < CHECK_INTERVAL_MS) {
            return false;
        }
        p.edit().putLong("lastCheck", now).apply();
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The latest release if it is newer than this app, else null. Call off the main thread. */
    static Release checkForUpdate(Context context) throws IOException {
        HttpURLConnection connection = open(LATEST_RELEASE);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub answered " + connection.getResponseCode());
            }
            String body;
            try (InputStream in = connection.getInputStream()) {
                body = readText(in, 1L << 20);
            }
            JSONObject release = new JSONObject(body);
            String tag = release.getString("tag_name");
            if (!isNewer(tag, installedVersion(context))) {
                return null;
            }
            String apkUrl = null;
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (APK_NAME.equals(asset.optString("name"))) {
                    apkUrl = asset.optString("browser_download_url");
                }
            }
            if (apkUrl == null || !apkUrl.startsWith("https://github.com/Shadow4-18/Reactive-Wallpaper/")) {
                return null;
            }
            return new Release(tag.replaceFirst("^[vV]", ""), notesOf(release.optString("body")), apkUrl);
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from GitHub", e);
        } finally {
            connection.disconnect();
        }
    }

    /** The release's "what's new" text: everything above the --- line. */
    static String notesOf(String body) {
        if (body == null) {
            return "";
        }
        int cut = body.indexOf("\n---");
        String notes = (cut >= 0 ? body.substring(0, cut) : body.startsWith("---") ? "" : body).trim();
        return notes;
    }

    /** Compares versions like "v2.10" and "2.9" number by number. */
    static boolean isNewer(String candidate, String installed) {
        int[] a = parts(candidate);
        int[] b = parts(installed);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] parts(String version) {
        String[] pieces = version.replaceFirst("^[vV]", "").split("[^0-9]+");
        int[] out = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                out[i] = pieces[i].isEmpty() ? 0 : Integer.parseInt(pieces[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Downloads the release's APK into app storage. Call off the main thread. */
    static File download(Context context, Release release, Progress progress) throws IOException {
        File target = new File(context.getCacheDir(), "update.apk");
        HttpURLConnection connection = open(release.apkUrl);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download failed (" + connection.getResponseCode() + ")");
            }
            long total = connection.getContentLengthLong();
            if (total > MAX_APK_BYTES) {
                throw new IOException("Update is unexpectedly large");
            }
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    done += n;
                    if (done > MAX_APK_BYTES) {
                        throw new IOException("Update is unexpectedly large");
                    }
                    out.write(buffer, 0, n);
                    progress.onProgress(done, total);
                }
            }
            return target;
        } catch (IOException e) {
            target.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Makes sure the download is a newer Resona signed with the same key as this
     * one. Returns null when it is fine, else a message for the user.
     */
    @SuppressWarnings("deprecation")
    static String verify(Context context, File apk) {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo update = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (update == null) {
            return "The download isn't a valid app.";
        }
        if (!context.getPackageName().equals(update.packageName)) {
            return "The download isn't Resona.";
        }
        try {
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
            if (versionCode(update) <= versionCode(installed)) {
                return "You already have this version.";
            }
            Signature[] ours = signatures(installed);
            Signature[] theirs = signatures(update);
            // Android's installer also refuses a different signature; this just explains it sooner.
            if (ours != null && theirs != null && !Arrays.equals(ours, theirs)) {
                return "The update isn't signed like this copy of Resona, so Android won't install it.";
            }
        } catch (PackageManager.NameNotFoundException e) {
            return "Couldn't read the installed version.";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            return info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }

    /**
     * Hands the APK to Android's installer. Android then asks the user to
     * confirm (see {@link UpdateInstallReceiver}).
     */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("resona.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                session.fsync(out);
            }
            Intent status = new Intent(context, UpdateInstallReceiver.class);
            // Mutable so the installer can add its result to the intent.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(context, id, status, flags);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(id);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "Resona-Android");
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static String readText(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > limit) {
                throw new IOException("Answer from GitHub too large");
            }
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
