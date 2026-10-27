package com.reactivewallpaper;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.widget.RemoteViews;

/**
 * Home-screen media buttons that can be faint or fully invisible. They press
 * the same media keys a headset would, so they control whatever is playing.
 */
public class MediaWidgetProvider extends AppWidgetProvider {
    static final String ACTION_MEDIA_KEY = "com.reactivewallpaper.MEDIA_KEY";
    private static final String EXTRA_KEY_CODE = "keyCode";
    private static final String PREFS = "widgets";

    static final int LAYOUT_ROW = 0;
    static final int LAYOUT_COLUMN = 1;
    static final int LAYOUT_SINGLE = 2;

    /** One widget's look. */
    static final class Options {
        int layout = LAYOUT_ROW;
        /** 0 = invisible (still tappable) .. 1 = fully visible. */
        float visibility = 0.15f;
        boolean backdrop = false;
        boolean ripple = false;
        boolean vibrate = true;

        static Options load(Context context, int id) {
            SharedPreferences p = prefs(context);
            Options o = new Options();
            o.layout = p.getInt(id + ".layout", o.layout);
            o.visibility = p.getFloat(id + ".visibility", o.visibility);
            o.backdrop = p.getBoolean(id + ".backdrop", o.backdrop);
            o.ripple = p.getBoolean(id + ".ripple", o.ripple);
            o.vibrate = p.getBoolean(id + ".vibrate", o.vibrate);
            return o;
        }

        void save(Context context, int id) {
            prefs(context).edit()
                    .putInt(id + ".layout", layout)
                    .putFloat(id + ".visibility", visibility)
                    .putBoolean(id + ".backdrop", backdrop)
                    .putBoolean(id + ".ripple", ripple)
                    .putBoolean(id + ".vibrate", vibrate)
                    .apply();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** While on, invisible buttons are outlined so their positions can be learned. */
    static boolean revealing(Context context) {
        return prefs(context).getBoolean("reveal", false);
    }

    static void setRevealing(Context context, boolean reveal) {
        prefs(context).edit().putBoolean("reveal", reveal).apply();
        updateAll(context);
    }

    static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, MediaWidgetProvider.class));
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : widgetIds(context)) {
            manager.updateAppWidget(id, views(context, id));
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            manager.updateAppWidget(id, views(context, id));
        }
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (int id : ids) {
            for (String key : new String[] {"layout", "visibility", "backdrop", "ripple", "vibrate"}) {
                editor.remove(id + "." + key);
            }
        }
        editor.apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_MEDIA_KEY.equals(intent.getAction())) {
            int code = intent.getIntExtra(EXTRA_KEY_CODE, 0);
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            pressMediaKey(context, code);
            if (Options.load(context, id).vibrate) {
                tick(context);
            }
            return;
        }
        super.onReceive(context, intent);
    }

    /** Builds a widget's buttons with its chosen look. */
    static RemoteViews views(Context context, int id) {
        Options o = Options.load(context, id);
        boolean reveal = revealing(context);
        int layout = o.layout == LAYOUT_COLUMN ? R.layout.widget_column
                : o.layout == LAYOUT_SINGLE ? R.layout.widget_single : R.layout.widget_row;
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        views.setInt(R.id.widget_root, "setBackgroundResource", o.backdrop ? R.drawable.widget_backdrop : 0);

        int[][] buttons = o.layout == LAYOUT_SINGLE
                ? new int[][] {{R.id.widget_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE}}
                : new int[][] {
                        {R.id.widget_previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS},
                        {R.id.widget_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE},
                        {R.id.widget_next, KeyEvent.KEYCODE_MEDIA_NEXT},
                };
        // Invisible buttons stay tappable: only the icon is see-through.
        int alpha = Math.round(255 * (reveal ? Math.max(0.6f, o.visibility) : o.visibility));
        int background = reveal ? R.drawable.widget_outline : o.ripple ? R.drawable.widget_ripple : 0;
        for (int[] button : buttons) {
            views.setInt(button[0], "setImageAlpha", alpha);
            views.setInt(button[0], "setBackgroundResource", background);
            views.setOnClickPendingIntent(button[0], keyIntent(context, id, button[1]));
        }
        return views;
    }

    private static PendingIntent keyIntent(Context context, int id, int keyCode) {
        Intent intent = new Intent(context, MediaWidgetProvider.class)
                .setAction(ACTION_MEDIA_KEY)
                .putExtra(EXTRA_KEY_CODE, keyCode)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        // Distinct per widget and button, so each keeps its own extras.
        int request = id * 8 + (keyCode % 8);
        return PendingIntent.getBroadcast(context, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Presses and releases a media key, exactly like a headset button. */
    static void pressMediaKey(Context context, int code) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null || code == 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    /** A tiny haptic click: the only sign an invisible button was pressed. */
    private static void tick(Context context) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
