package com.reactivewallpaper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

/** Live, audio-reactive preview of the wallpaper inside the editor. */
public final class PreviewView extends View {
    private WallpaperRenderer renderer;
    private AudioAnalyzer analyzer;
    private WallpaperSettings settings;
    private long lastFrame;
    private boolean running;

    public PreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float radius = 20f * getResources().getDisplayMetrics().density;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    void bind(WallpaperRenderer renderer, AudioAnalyzer analyzer, WallpaperSettings settings) {
        this.renderer = renderer;
        this.analyzer = analyzer;
        this.settings = settings;
        invalidate();
    }

    void setSettings(WallpaperSettings settings) {
        this.settings = settings;
        invalidate();
    }

    void setRunning(boolean running) {
        this.running = running;
        lastFrame = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (renderer == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 1f / 60f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        analyzer.update(dt);
        renderer.draw(canvas, getWidth(), getHeight(), analyzer, settings, dt);
        if (running) {
            postInvalidateOnAnimation();
        }
    }
}
