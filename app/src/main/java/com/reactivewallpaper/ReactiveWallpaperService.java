package com.reactivewallpaper;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.SurfaceHolder;

public class ReactiveWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new ReactiveEngine();
    }

    private final class ReactiveEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener, Choreographer.FrameCallback {
        private static final long IDLE_FRAME_MS = 100;
        private static final long AMBIENT_FRAME_MS = 33;
        private static final long SAVER_FRAME_MS = 33;
        private static final long SAVER_AMBIENT_FRAME_MS = 66;
        /** While asleep, only listen for sound to come back. */
        private static final long SLEEP_CHECK_MS = 250;

        private Choreographer choreographer;
        private boolean frameScheduled;
        private WallpaperSettings settings;
        private AudioAnalyzer analyzer;
        private AudioController audio;
        private OutputRoute route;
        private TiltSensor tilt;
        private PowerState power;
        private long lastSoundTime;
        private boolean asleep;
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
            choreographer = Choreographer.getInstance();
            settings = WallpaperSettings.load(ReactiveWallpaperService.this);
            analyzer = new AudioAnalyzer(settings.barCount);
            route = new OutputRoute(ReactiveWallpaperService.this,
                    bluetooth -> settings.applyTo(analyzer, bluetooth, true));
            settings.applyTo(analyzer, route.isBluetooth(), true);
            audio = new AudioController(ReactiveWallpaperService.this, analyzer);
            renderer = new WallpaperRenderer(getResources().getDisplayMetrics().density);
            tilt = new TiltSensor(ReactiveWallpaperService.this);
            power = new PowerState(ReactiveWallpaperService.this);
            // Home-screen page changes arrive through onOffsetsChanged.
            setOffsetNotificationsEnabled(true);
            WallpaperSettings.prefs(ReactiveWallpaperService.this)
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            WallpaperSettings.prefs(ReactiveWallpaperService.this)
                    .unregisterOnSharedPreferenceChangeListener(this);
            stopFrames();
            audio.stop();
            route.stop();
            tilt.stop();
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
            stopFrames();
            audio.stop();
            route.stop();
            tilt.stop();
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (isVisible) {
                route.start();
                settings.applyTo(analyzer, route.isBluetooth(), true);
                audio.start(settings.source);
                updateTilt();
                lastFrame = 0;
                lastSoundTime = SystemClock.uptimeMillis();
                asleep = false;
                scheduleFrame(0);
            } else {
                stopFrames();
                audio.stop();
                route.stop();
                tilt.stop();
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            settings = WallpaperSettings.load(ReactiveWallpaperService.this);
            settings.applyTo(analyzer, route.isBluetooth(), true);
            loadImageIfNeeded();
            if (visible) {
                audio.start(settings.source);
                updateTilt();
                scheduleFrame(0);
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep,
                                     int xPixelOffset, int yPixelOffset) {
            // Launchers with a single page report 0.5 or 0; both mean "centered".
            renderer.setSwipe(xOffsetStep > 0f && xOffsetStep < 1f ? xOffset * 2f - 1f : 0f);
        }

        private void updateTilt() {
            if (settings.tiltParallax > 0f) {
                tilt.start();
            } else {
                tilt.stop();
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

        // Frames are driven by the display's vsync so motion stays even and on time.
        private void scheduleFrame(long delayMs) {
            if (frameScheduled) {
                return;
            }
            frameScheduled = true;
            choreographer.postFrameCallbackDelayed(this, delayMs);
        }

        private void stopFrames() {
            choreographer.removeFrameCallback(this);
            frameScheduled = false;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            if (!visible) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            boolean saving = power.shouldSave(settings.batterySaver);
            renderer.setSaving(saving);

            if (asleep) {
                // Keep the last frame on screen and just check whether sound is back.
                analyzer.update(SLEEP_CHECK_MS / 1000f);
                if (analyzer.isIdle()) {
                    scheduleFrame(SLEEP_CHECK_MS);
                    return;
                }
                asleep = false;
                lastFrame = 0;
            }

            drawFrame();
            boolean idle = analyzer.isIdle();
            if (!idle) {
                lastSoundTime = now;
            } else if (settings.sleepMinutes > 0 && now - lastSoundTime > settings.sleepMinutes * 60_000L) {
                asleep = true;
                scheduleFrame(SLEEP_CHECK_MS);
                return;
            }

            long delay = saving ? SAVER_FRAME_MS : 0;
            if (idle) {
                boolean animated = settings.breathe > 0f || settings.sparkleAmount > 0f || settings.sway > 0f
                        || settings.colorCycle > 0f || settings.tiltParallax > 0f;
                if (animated) {
                    delay = saving ? SAVER_AMBIENT_FRAME_MS : AMBIENT_FRAME_MS;
                } else {
                    delay = IDLE_FRAME_MS;
                }
            }
            scheduleFrame(delay);
        }

        private void drawFrame() {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 1f / 60f : Math.min(0.1f, (now - lastFrame) / 1000f);
            lastFrame = now;
            analyzer.update(dt);
            renderer.setTilt(tilt.x(), tilt.y());

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
