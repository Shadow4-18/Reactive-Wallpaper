package com.reactivewallpaper;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.ScrollView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Opens every screen with a picture already chosen, and screenshots each. A
 * crash anywhere (including the wallpaper, which runs in the same process)
 * fails the run.
 */
@RunWith(AndroidJUnit4.class)
public class AppSmokeTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Before
    public void setUp() throws Exception {
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),
                Manifest.permission.RECORD_AUDIO);
        // Keep the run offline and repeatable.
        Updater.setAutoCheck(context, false);
        for (File f : new File[] {ImageStore.draft(context).image, ImageStore.live(context).image}) {
            try (FileOutputStream out = new FileOutputStream(f)) {
                TestScenes.neon(1200, 2660).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
        }
        WallpaperSettings settings = new WallpaperSettings();
        settings.imageVersion = 1;
        settings.style = WallpaperSettings.STYLE_CIRCLE;
        settings.circleY = 0.58f;
        settings.circleSize = 0.36f;
        settings.save(context);
        settings.saveDraft(context);
        // The first-run tour has its own test; elsewhere it would cover the screenshots.
        editorPrefs().edit().putBoolean("tourShown", true).putBoolean("simpleEditor", false).commit();
    }

    private android.content.SharedPreferences editorPrefs() {
        return context.getSharedPreferences("editor_ui", android.content.Context.MODE_PRIVATE);
    }

    @Test
    public void firstRunTourAndSimpleMode() throws Exception {
        editorPrefs().edit().putBoolean("tourShown", false).commit();
        Activity activity = start(MainActivity.class, null);
        shot("08_first_run_tour", 5000);
        instrumentation.getUiAutomation().performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        activity.finish();
        SystemClock.sleep(1000);
        editorPrefs().edit().putBoolean("simpleEditor", true).commit();
        activity = start(MainActivity.class, null);
        shot("09_simple_mode", 3000);
        activity.finish();
    }

    private void shot(String name, long waitMs) throws Exception {
        SystemClock.sleep(waitMs);
        TestScenes.saveShot(context, "app_" + name, instrumentation.getUiAutomation().takeScreenshot());
    }

    private Activity start(Class<?> activity, Intent extras) {
        Intent intent = new Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (extras != null) {
            intent.putExtras(extras);
        }
        // Not startActivitySync: it waits for the main thread to go fully idle, which a live,
        // animating preview may never do on a slow emulator. Wait until it's resumed instead.
        context.startActivity(intent);
        long deadline = SystemClock.uptimeMillis() + 45000;
        while (SystemClock.uptimeMillis() < deadline) {
            Activity[] found = new Activity[1];
            instrumentation.runOnMainSync(() -> {
                for (Activity a : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (a.getClass() == activity) {
                        found[0] = a;
                    }
                }
            });
            if (found[0] != null) {
                return found[0];
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError(activity.getSimpleName() + " didn't open");
    }

    @Test
    public void editor() throws Exception {
        Activity activity = start(MainActivity.class, null);
        shot("01_editor_top", 3000);
        instrumentation.runOnMainSync(() -> {
            View controls = activity.findViewById(R.id.controls);
            ((ScrollView) controls.getParent()).fullScroll(View.FOCUS_DOWN);
        });
        shot("02_editor_bottom", 1500);
        // The full-screen preview, from the button in the preview's corner.
        instrumentation.runOnMainSync(() -> {
            java.util.ArrayList<View> found = new java.util.ArrayList<>();
            activity.getWindow().getDecorView().findViewsWithText(found, "Full screen preview",
                    View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            if (found.isEmpty()) {
                throw new AssertionError("no full-screen button");
            }
            found.get(0).performClick();
        });
        shot("06_full_screen_preview", 2500);
        instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        shot("07_after_full_screen", 1500);
        activity.finish();
    }

    @Test
    public void glowPainter() throws Exception {
        Intent extras = new Intent().putExtra(GlowPaintActivity.EXTRA_COVERAGE, 0.6f);
        Activity activity = start(GlowPaintActivity.class, extras);
        shot("03_glow_painter", 4000);
        activity.finish();
    }

    @Test
    public void widgetSetup() throws Exception {
        Intent extras = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 4242);
        Activity activity = start(WidgetConfigActivity.class, extras);
        shot("04_widget_setup", 2500);
        activity.finish();
    }

    @Test
    public void liveWallpaperPreview() throws Exception {
        // Android's own preview screen, running Resona's wallpaper engine.
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(context, ReactiveWallpaperService.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        shot("05_system_wallpaper_preview", 6000);
        instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        SystemClock.sleep(1000);
    }

    private void shell(String command) throws Exception {
        android.os.ParcelFileDescriptor out = instrumentation.getUiAutomation().executeShellCommand(command);
        try (java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(out)) {
            byte[] buf = new byte[1024];
            while (in.read(buf) > 0) {
                // Wait for the command to finish.
            }
        }
    }

    /** The editor on a foldable's inner screen (side by side), then the QR share dialog. */
    @Test
    public void wideScreenEditorAndSharing() throws Exception {
        shell("wm size 2208x1840");
        try {
            SystemClock.sleep(2000);
            Activity activity = start(MainActivity.class, null);
            shot("10_wide_editor", 4000);
            instrumentation.runOnMainSync(() -> LookShare.show(activity, "Test look", new WallpaperSettings()));
            shot("11_share_qr", 2000);
            activity.finish();
        } finally {
            shell("wm size reset");
            SystemClock.sleep(2000);
        }
    }

    /** A look message shared to Resona from another app opens the "Add it?" prompt. */
    @Test
    public void receivesASharedLookMessage() throws Exception {
        WallpaperSettings look = new WallpaperSettings();
        look.style = WallpaperSettings.STYLE_ORB;
        String message = "My Resona wallpaper look. To add it, copy this message.\n"
                + LookCode.encode("Friend's look", look);
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, message)
                .setClassName(context, MainActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        shot("12_shared_look_prompt", 5000);
        instrumentation.getUiAutomation().performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        SystemClock.sleep(1000);
    }
}
