package com.reactivewallpaper;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.SurfaceHolder;

import java.io.File;

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
        /** Fallback check of the lock state, in case a broadcast is missed. */
        private static final long LOCK_POLL_MS = 1000;

        private final Handler handler = new Handler(Looper.getMainLooper());
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

        // Lock screen look: its own settings and renderer, so unlocking needs no reload.
        private KeyguardManager keyguard;
        private boolean locked;
        private long lastLockCheck;
        private WallpaperSettings lockSettings;
        private WallpaperRenderer lockRenderer;
        private String loadedLockKey;

        private final SharedPreferences.OnSharedPreferenceChangeListener presetsListener =
                (prefs, key) -> reloadLockLook();

        private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateLocked();
                // The keyguard state can settle a moment after the broadcast.
                handler.postDelayed(ReactiveEngine.this::updateLocked, 300);
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            Context context = ReactiveWallpaperService.this;
            choreographer = Choreographer.getInstance();
            settings = WallpaperSettings.load(context);
            analyzer = new AudioAnalyzer(settings.barCount);
            route = new OutputRoute(context, bluetooth -> current().applyTo(analyzer, bluetooth, true));
            audio = new AudioController(context, analyzer);
            renderer = new WallpaperRenderer(getResources().getDisplayMetrics().density);
            tilt = new TiltSensor(context);
            power = new PowerState(context);
            keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            // Home-screen page changes arrive through onOffsetsChanged.
            setOffsetNotificationsEnabled(true);
            WallpaperSettings.prefs(context).registerOnSharedPreferenceChangeListener(this);
            PresetStore.registerListener(context, presetsListener);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Only system broadcasts are wanted here; nothing else may send to this receiver.
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
            locked = isLockedNow();
            reloadLockLook();
            current().applyTo(analyzer, route.isBluetooth(), true);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            Context context = ReactiveWallpaperService.this;
            WallpaperSettings.prefs(context).unregisterOnSharedPreferenceChangeListener(this);
            PresetStore.unregisterListener(context, presetsListener);
            unregisterReceiver(screenReceiver);
            handler.removeCallbacksAndMessages(null);
            stopFrames();
            audio.stop();
            route.stop();
            tilt.stop();
            renderer.release();
            if (lockRenderer != null) {
                lockRenderer.release();
            }
        }

        /** The settings for what is on screen right now: the lock look while locked. */
        private WallpaperSettings current() {
            return locked && lockSettings != null ? lockSettings : settings;
        }

        private WallpaperRenderer currentRenderer() {
            return locked && lockSettings != null && lockRenderer != null ? lockRenderer : renderer;
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            boolean sizeChanged = w != width || h != height;
            width = w;
            height = h;
            if (sizeChanged) {
                loadedImageVersion = -1;
                loadedLockKey = null;
            }
            loadImageIfNeeded();
            reloadLockLook();
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
                locked = isLockedNow();
                route.start();
                current().applyTo(analyzer, route.isBluetooth(), true);
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
            loadImageIfNeeded();
            reloadLockLook();
            current().applyTo(analyzer, route.isBluetooth(), true);
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
            float position = xOffsetStep > 0f && xOffsetStep < 1f ? xOffset * 2f - 1f : 0f;
            renderer.setSwipe(position);
            if (lockRenderer != null) {
                lockRenderer.setSwipe(position);
            }
        }

        private boolean isLockedNow() {
            return !isPreview() && keyguard != null && keyguard.isKeyguardLocked();
        }

        private void updateLocked() {
            boolean now = isLockedNow();
            if (now == locked) {
                return;
            }
            locked = now;
            current().applyTo(analyzer, route.isBluetooth(), true);
            if (visible) {
                updateTilt();
                lastFrame = 0;
                asleep = false;
                scheduleFrame(0);
            }
        }

        private void updateTilt() {
            if (current().tiltParallax > 0f) {
                tilt.start();
            } else {
                tilt.stop();
            }
        }

        private void loadImageIfNeeded() {
            if (width == 0 || height == 0 || loadedImageVersion == settings.imageVersion) {
                return;
            }
            Context context = ReactiveWallpaperService.this;
            Bitmap bitmap = ImageStore.load(context, width, height);
            renderer.setImage(bitmap, ImageStore.loadMask(ImageStore.maskFile(context)));
            loadedImageVersion = settings.imageVersion;
            // A lock look without its own image shows this image too.
            loadedLockKey = null;
        }

        /** Loads (or drops) the lock screen look when the choice or that look changes. */
        private void reloadLockLook() {
            Context context = ReactiveWallpaperService.this;
            PresetStore.Preset preset = PresetStore.find(context, WallpaperSettings.lockLookId(context));
            if (preset == null) {
                lockSettings = null;
                if (lockRenderer != null) {
                    lockRenderer.release();
                    lockRenderer = null;
                }
                loadedLockKey = null;
                return;
            }
            lockSettings = PresetStore.fromJson(preset.look).keepDeviceSettingsFrom(settings);
            if (width == 0 || height == 0) {
                return;
            }
            File image = preset.hasImage ? PresetStore.imageFile(context, preset.id) : ImageStore.file(context);
            File mask = preset.hasImage ? PresetStore.maskFile(context, preset.id) : ImageStore.maskFile(context);
            String key = preset.id + "|" + image.lastModified() + "|" + mask.lastModified() + "|" + settings.imageVersion;
            if (key.equals(loadedLockKey) && lockRenderer != null) {
                return;
            }
            if (lockRenderer == null) {
                lockRenderer = new WallpaperRenderer(getResources().getDisplayMetrics().density);
            }
            lockRenderer.setImage(ImageStore.load(image, width, height), ImageStore.loadMask(mask));
            loadedLockKey = key;
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
            if (now - lastLockCheck > LOCK_POLL_MS) {
                lastLockCheck = now;
                updateLocked();
            }
            WallpaperSettings s = current();
            boolean saving = power.shouldSave(s.batterySaver);
            currentRenderer().setSaving(saving);

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
            } else if (s.sleepMinutes > 0 && now - lastSoundTime > s.sleepMinutes * 60_000L) {
                asleep = true;
                scheduleFrame(SLEEP_CHECK_MS);
                return;
            }

            long delay = saving ? SAVER_FRAME_MS : 0;
            if (idle) {
                boolean animated = s.breathe > 0f || s.sparkleAmount > 0f || s.sway > 0f
                        || s.colorCycle > 0f || s.tiltParallax > 0f;
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
            WallpaperRenderer r = currentRenderer();
            r.setTilt(tilt.x(), tilt.y());

            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = lockCanvas(holder);
                if (canvas != null) {
                    r.draw(canvas, width, height, analyzer, current(), dt);
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
