package com.reactivewallpaper;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public class ReactiveWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new ReactiveEngine();
    }

    private final class ReactiveEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private static final long FRAME_MS = 16;
        private static final long IDLE_FRAME_MS = 100;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable drawRunnable = this::drawFrame;
        private WallpaperSettings settings;
        private AudioAnalyzer analyzer;
        private AudioController audio;
        private WallpaperRenderer renderer;
        private boolean visible;
        private int width;
        private int height;
        private long lastFrame;
        private long loadedImageVersion = -1;
        private boolean useHardwareCanvas = true;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            settings = WallpaperSettings.load(ReactiveWallpaperService.this);
            analyzer = new AudioAnalyzer(settings.barCount);
            analyzer.setSensitivity(settings.sensitivity);
            audio = new AudioController(ReactiveWallpaperService.this, analyzer);
            renderer = new WallpaperRenderer(getResources().getDisplayMetrics().density);
            WallpaperSettings.prefs(ReactiveWallpaperService.this)
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            WallpaperSettings.prefs(ReactiveWallpaperService.this)
                    .unregisterOnSharedPreferenceChangeListener(this);
            handler.removeCallbacks(drawRunnable);
            audio.stop();
            renderer.release();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            boolean sizeChanged = w != width || h != height;
            width = w;
            height = h;
            if (sizeChanged) {
                loadedImageVersion = -1;
            }
            loadImageIfNeeded();
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            visible = false;
            handler.removeCallbacks(drawRunnable);
            audio.stop();
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (isVisible) {
                audio.start(settings.source);
                lastFrame = 0;
                drawFrame();
            } else {
                handler.removeCallbacks(drawRunnable);
                audio.stop();
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            settings = WallpaperSettings.load(ReactiveWallpaperService.this);
            analyzer.setBandCount(settings.barCount);
            analyzer.setSensitivity(settings.sensitivity);
            loadImageIfNeeded();
            if (visible) {
                audio.start(settings.source);
                drawFrame();
            }
        }

        private void loadImageIfNeeded() {
            if (width == 0 || height == 0 || loadedImageVersion == settings.imageVersion) {
                return;
            }
            Bitmap bitmap = ImageStore.load(ReactiveWallpaperService.this, width, height);
            renderer.setImage(bitmap);
            loadedImageVersion = settings.imageVersion;
        }

        private void drawFrame() {
            handler.removeCallbacks(drawRunnable);
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 1f / 60f : Math.min(0.1f, (now - lastFrame) / 1000f);
            lastFrame = now;
            analyzer.update(dt);

            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = lockCanvas(holder);
                if (canvas != null) {
                    renderer.draw(canvas, width, height, analyzer, settings, dt);
                }
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (IllegalStateException ignored) {
                        // Surface went away mid-frame.
                    }
                }
            }

            if (visible) {
                handler.postDelayed(drawRunnable, analyzer.isIdle() ? IDLE_FRAME_MS : FRAME_MS);
            }
        }

        private Canvas lockCanvas(SurfaceHolder holder) {
            if (useHardwareCanvas) {
                try {
                    return holder.lockHardwareCanvas();
                } catch (RuntimeException e) {
                    useHardwareCanvas = false;
                }
            }
            try {
                return holder.lockCanvas();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
