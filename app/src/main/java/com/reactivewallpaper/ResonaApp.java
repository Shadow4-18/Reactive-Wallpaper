package com.reactivewallpaper;

import android.app.Application;

/** Starts crash reporting before anything else in the process (editor, wallpaper, widget). */
public class ResonaApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Diagnostics.install(this);
    }
}
