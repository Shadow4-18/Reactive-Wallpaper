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
        return instrumentation.startActivitySync(intent);
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
}
