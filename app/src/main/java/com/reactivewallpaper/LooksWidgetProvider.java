package com.reactivewallpaper;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.widget.RemoteViews;

import java.io.File;
import java.util.List;

/**
 * A home-screen row of favorite looks: tap one and the wallpaper shows it
 * right away, no editor needed. The first button returns to the wallpaper as
 * applied in the editor.
 */
public class LooksWidgetProvider extends AppWidgetProvider {
    static final String ACTION_SHOW = "com.reactivewallpaper.SHOW_LOOK";
    private static final String EXTRA_LOOK = "lookId";
    /** Buttons in the layout: "yours" plus four favorites. */
    static final int FAVORITE_SLOTS = 4;

    private static final int[] CELLS = {R.id.look_cell_0, R.id.look_cell_1, R.id.look_cell_2,
            R.id.look_cell_3, R.id.look_cell_4};
    private static final int[] THUMBS = {R.id.look_thumb_0, R.id.look_thumb_1, R.id.look_thumb_2,
            R.id.look_thumb_3, R.id.look_thumb_4};
    private static final int[] NAMES = {R.id.look_name_0, R.id.look_name_1, R.id.look_name_2,
            R.id.look_name_3, R.id.look_name_4};

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, LooksWidgetProvider.class));
        if (ids.length == 0) {
            return;
        }
        // Thumbnails are read from disk; keep that off the caller's thread.
        Context app = context.getApplicationContext();
        new Thread(() -> {
            RemoteViews views = views(app);
            for (int id : ids) {
                manager.updateAppWidget(id, views);
            }
        }, "looks-widget").start();
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAll(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_SHOW.equals(intent.getAction())) {
            String id = intent.getStringExtra(EXTRA_LOOK);
            WallpaperSettings.setShownLookId(context, id);
            updateAll(context);
            return;
        }
        super.onReceive(context, intent);
    }

    /** The favorites that still exist, at most {@link #FAVORITE_SLOTS}. */
    static List<PresetStore.Preset> favorites(Context context) {
        List<PresetStore.Preset> out = new java.util.ArrayList<>();
        for (String id : WallpaperSettings.favoriteIds(context)) {
            PresetStore.Preset p = PresetStore.find(context, id);
            if (p != null && out.size() < FAVORITE_SLOTS) {
                out.add(p);
            }
        }
        return out;
    }

    static RemoteViews views(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_looks);
        List<PresetStore.Preset> favorites = favorites(context);
        String shown = WallpaperSettings.shownLookId(context);
        boolean shownExists = PresetStore.find(context, shown) != null;
        boolean empty = favorites.isEmpty();
        views.setViewVisibility(R.id.looks_empty, empty ? View.VISIBLE : View.GONE);
        for (int i = 0; i < CELLS.length; i++) {
            PresetStore.Preset preset = i == 0 ? null : (i - 1 < favorites.size() ? favorites.get(i - 1) : null);
            boolean used = !empty && (i == 0 || preset != null);
            views.setViewVisibility(CELLS[i], used ? View.VISIBLE : View.GONE);
            if (!used) {
                continue;
            }
            String id = preset == null ? "" : preset.id;
            boolean on = preset == null ? !shownExists : preset.id.equals(shown);
            views.setInt(CELLS[i], "setBackgroundResource",
                    on ? R.drawable.widget_look_cell_on : R.drawable.widget_look_cell);
            views.setTextViewText(NAMES[i], preset == null ? "Yours" : preset.name);
            File image = preset == null ? ImageStore.live(context).image
                    : (preset.hasImage ? PresetStore.imageFile(context, preset.id) : null);
            Bitmap thumb = thumbnail(context, image);
            if (thumb != null) {
                views.setImageViewBitmap(THUMBS[i], thumb);
            } else {
                views.setImageViewResource(THUMBS[i], R.drawable.wallpaper_thumbnail);
            }
            Intent tap = new Intent(context, LooksWidgetProvider.class).setAction(ACTION_SHOW)
                    .putExtra(EXTRA_LOOK, id);
            views.setOnClickPendingIntent(CELLS[i], PendingIntent.getBroadcast(context, i, tap,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return views;
    }

    /** A small rounded copy of a look's picture (widgets can't round corners themselves). */
    private static Bitmap thumbnail(Context context, File image) {
        if (image == null || !image.isFile() || image.length() == 0) {
            return null;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.round(56 * density);
        int h = Math.round(56 * density);
        Bitmap source = ImageStore.load(image, w, h);
        if (source == null) {
            return null;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        // Center-crop the picture into the square.
        float scale = Math.max(w / (float) source.getWidth(), h / (float) source.getHeight());
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - source.getWidth() * scale) / 2f, (h - source.getHeight() * scale) / 2f);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        new Canvas(out).drawRoundRect(new RectF(0, 0, w, h), 10 * density, 10 * density, paint);
        source.recycle();
        return out;
    }
}
