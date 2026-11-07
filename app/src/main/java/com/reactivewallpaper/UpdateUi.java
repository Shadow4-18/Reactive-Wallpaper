package com.reactivewallpaper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** The editor's update banner, and downloading and installing an update. */
final class UpdateUi {
    private final Activity activity;
    private final ExecutorService executor;
    private final Handler main;
    /** Called when a check finds an update, so the banner can be shown. */
    private final Runnable onFound;
    private Updater.Release available;

    UpdateUi(Activity activity, ExecutorService executor, Handler main, Runnable onFound) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.onFound = onFound;
        // An update found before shows at once, even offline, until it's installed.
        available = Updater.cachedUpdate(activity);
    }

    /** Asks GitHub; a newer release shows a banner. Says so either way if the user asked. */
    void check(boolean userAsked) {
        if (userAsked) {
            Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show();
        }
        executor.execute(() -> {
            Updater.Release release = null;
            String error = null;
            try {
                release = Updater.checkForUpdate(activity);
            } catch (java.io.IOException e) {
                error = e.getMessage();
            }
            Updater.Release found = release;
            String problem = error;
            main.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                if (found != null) {
                    available = found;
                    onFound.run();
                } else if (userAsked) {
                    Toast.makeText(activity, problem != null ? "Couldn't check for updates: " + problem
                            : "You have the latest version.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** Adds the "update available" banner, if there is an update. */
    void addBanner(LinearLayout parent) {
        if (available == null) {
            return;
        }
        LinearLayout banner = new LinearLayout(activity);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setBackgroundColor(activity.getColor(R.color.surface));
        banner.setPadding(dp(16), dp(8), dp(8), dp(8));
        TextView text = new TextView(activity);
        text.setText("Resona " + available.version + " is available");
        text.setTextColor(activity.getColor(R.color.text_primary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        Button update = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        update.setText("Update");
        update.setTextColor(activity.getColor(R.color.accent));
        update.setOnClickListener(v -> confirm(available));
        banner.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        banner.addView(update);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(banner, lp);
    }

    private void confirm(Updater.Release release) {
        String notes = release.notes.isEmpty() ? "Bug fixes and improvements." : release.notes;
        new AlertDialog.Builder(activity)
                .setTitle("Update to Resona " + release.version)
                .setMessage(notes + "\n\nYour wallpaper, looks and settings stay as they are. "
                        + "Android will ask you to confirm the install.")
                .setPositiveButton("Update now", (d, w) -> start(release))
                .setNegativeButton("Later", null)
                .show();
    }

    private void start(Updater.Release release) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow Resona to install updates")
                    .setMessage("Android needs your OK once for Resona to install its own updates. "
                            + "Turn on \"Allow from this source\" on the next screen, come back, and tap Update again.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(activity, "Couldn't open the settings screen.", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        android.widget.ProgressBar bar = new android.widget.ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        TextView status = new TextView(activity);
        status.setTextColor(activity.getColor(R.color.text_secondary));
        status.setText("Downloading…");
        box.addView(bar);
        box.addView(status);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Updating Resona")
                .setView(box)
                .setCancelable(false)
                .create();
        dialog.show();

        executor.execute(() -> {
            String error = null;
            try {
                java.io.File apk = Updater.download(activity, release, (done, total) -> main.post(() -> {
                    if (total > 0) {
                        bar.setProgress((int) (done * 1000 / total));
                        status.setText(String.format(Locale.US, "Downloading… %.1f of %.1f MB",
                                done / 1048576f, total / 1048576f));
                    }
                }));
                main.post(() -> status.setText("Checking the download…"));
                error = Updater.verify(activity, apk);
                if (error == null) {
                    Updater.install(activity, apk);
                }
            } catch (java.io.IOException e) {
                error = "Update failed: " + e.getMessage();
            }
            String problem = error;
            main.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                dialog.dismiss();
                if (problem != null) {
                    Toast.makeText(activity, problem, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }
}
