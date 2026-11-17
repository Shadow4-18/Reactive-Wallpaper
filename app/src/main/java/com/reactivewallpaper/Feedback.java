package com.reactivewallpaper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * "Feedback & suggestions": a problem report or an idea as a GitHub issue
 * (a short form, with the app version and phone filled in), or, for people
 * without a GitHub account, a Google Form.
 */
final class Feedback {
    /** Where the issue forms live (.github/ISSUE_TEMPLATE in this repository). */
    static final String ISSUES = "https://github.com/Shadow4-18/Reactive-Wallpaper/issues/new";
    /**
     * The Google Form for feedback without a GitHub account ("" hides the
     * option until the form exists).
     */
    static final String FORM_URL =
            "https://docs.google.com/forms/d/e/1FAIpQLSfEMOJOKnLuU3wwjHRahaU7ZOZjduWKd_46Bm1N8B7kFT-w9w/viewform";

    private Feedback() {}

    static void show(Activity activity) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Report a problem");
        actions.add(() -> askDiagnostics(activity));
        labels.add("Suggest a feature or idea");
        actions.add(() -> open(activity, Uri.parse(ISSUES).buildUpon()
                .appendQueryParameter("template", "idea.yml")
                .appendQueryParameter("version", version(activity))
                .build()));
        if (!FORM_URL.isEmpty()) {
            labels.add("No GitHub account? Send it through a form");
            actions.add(() -> open(activity, Uri.parse(FORM_URL)));
        }
        new AlertDialog.Builder(activity)
                .setTitle("Feedback & suggestions")
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** A problem report can carry the diagnostics report, copied for pasting into the form. */
    private static void askDiagnostics(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("Include diagnostics?")
                .setMessage("Resona can copy a diagnostics report (phone model, settings, recent crashes and "
                        + "its own log; no pictures or notifications) for you to paste into the report's "
                        + "Diagnostics box. It helps a lot with crashes.")
                .setPositiveButton("Copy and continue", (d, w) -> {
                    Handler main = new Handler(Looper.getMainLooper());
                    new Thread(() -> {
                        String report = Diagnostics.report(activity);
                        main.post(() -> {
                            ClipboardManager clipboard =
                                    (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("Resona diagnostics", report));
                                Toast.makeText(activity, "Diagnostics copied: paste them into the Diagnostics box.",
                                        Toast.LENGTH_LONG).show();
                            }
                            openProblem(activity);
                        });
                    }).start();
                })
                .setNegativeButton("Skip", (d, w) -> openProblem(activity))
                .show();
    }

    private static void openProblem(Activity activity) {
        open(activity, Uri.parse(ISSUES).buildUpon()
                .appendQueryParameter("template", "bug.yml")
                .appendQueryParameter("version", version(activity))
                .appendQueryParameter("device", Build.MANUFACTURER + " " + Build.MODEL + ", Android "
                        + Build.VERSION.RELEASE)
                .build());
    }

    private static String version(Context context) {
        return Updater.installedVersion(context);
    }

    private static void open(Activity activity, Uri uri) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(activity, "No browser to open the feedback page.", Toast.LENGTH_LONG).show();
        }
    }
}
