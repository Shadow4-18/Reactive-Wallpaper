package com.reactivewallpaper;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.SurfaceHolder;

import java.util.List;

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
        private static final long DOUBLE_TAP_MS = 400;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private Context context;
        private Choreographer choreographer;
        private boolean frameScheduled;
        private float density;

        /** The applied (live) settings; slots may show a rotation or lock look instead. */
        private WallpaperSettings live;
        private LookSlot home;
        private LookSlot lock;
        private AudioAnalyzer analyzer;
        private AudioController audio;
        private OutputRoute route;
        private TiltSensor tilt;
        private PowerState power;
        private long lastSoundTime;
        private boolean asleep;
        private boolean visible;
        private int width;
        private int height;
        private long lastFrame;
        private boolean useHardwareCanvas = true;

        private KeyguardManager keyguard;
        private boolean locked;
        private long lastLockCheck;

        // Look rotation.
        private List<String> rotationIds;
        private int rotationMode;
        private int rotationMinutes;
        private int rotationIndex;
        private long lastRotation;
        private long lastTapTime;

        // The playing song's cover, for album art mode and per-song rotation.
        private AlbumArtSource artSource;
        private Bitmap art;
        private String artKey;

        private final SharedPreferences.OnSharedPreferenceChangeListener presetsListener =
                (prefs, key) -> refresh(false);

        private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                updateLocked();
                // The keyguard state can settle a moment after the broadcast.
                handler.postDelayed(ReactiveEngine.this::updateLocked, 300);
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            context = ReactiveWallpaperService.this;
            choreographer = Choreographer.getInstance();
            density = getResources().getDisplayMetrics().density;
            live = WallpaperSettings.load(context);
            home = new LookSlot(density, live);
            analyzer = new AudioAnalyzer(live.barCount);
            route = new OutputRoute(context, bluetooth -> applyAudio());
            audio = new AudioController(context, analyzer);
            tilt = new TiltSensor(context);
            power = new PowerState(context);
            keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            artSource = new AlbumArtSource(context, this::onTrack);
            // Home-screen page changes arrive through onOffsetsChanged.
            setOffsetNotificationsEnabled(true);
            WallpaperSettings.prefs(context).registerOnSharedPreferenceChangeListener(this);
            PresetStore.registerListener(context, presetsListener);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                // Only system broadcasts are wanted here; nothing else may send to this receiver.
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
            loadRotation();
            lastRotation = SystemClock.uptimeMillis();
            locked = isLockedNow();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            WallpaperSettings.prefs(context).unregisterOnSharedPreferenceChangeListener(this);
            PresetStore.unregisterListener(context, presetsListener);
            unregisterReceiver(screenReceiver);
            handler.removeCallbacksAndMessages(null);
            stopFrames();
            audio.stop();
            route.stop();
            tilt.stop();
            artSource.stop();
            home.release();
            if (lock != null) {
                lock.release();
            }
            if (art != null) {
                art.recycle();
                art = null;
            }
        }

        /** The look on screen right now: the lock look while locked, if there is one. */
        private LookSlot currentSlot() {
            return locked && lock != null ? lock : home;
        }

        private WallpaperSettings current() {
            return currentSlot().settings;
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = w;
            height = h;
            refresh(false);
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            setVisible(false);
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            setVisible(isVisible);
        }

        private void setVisible(boolean isVisible) {
            visible = isVisible;
            home.setPlaying(isVisible);
            if (lock != null) {
                lock.setPlaying(isVisible);
            }
            if (isVisible) {
                locked = isLockedNow();
                route.start();
                applyAudio();
                audio.start(live.source);
                updateTilt();
                updateArtSource();
                lastFrame = 0;
                lastSoundTime = SystemClock.uptimeMillis();
                asleep = false;
                scheduleFrame(0);
            } else {
                stopFrames();
                audio.stop();
                route.stop();
                tilt.stop();
                artSource.stop();
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            live = WallpaperSettings.load(context);
            loadRotation();
            refresh(false);
            if (visible) {
                audio.start(live.source);
                updateTilt();
                updateArtSource();
                scheduleFrame(0);
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep,
                                     int xPixelOffset, int yPixelOffset) {
            // Launchers with a single page report 0.5 or 0; both mean "centered".
            float position = xOffsetStep > 0f && xOffsetStep < 1f ? xOffset * 2f - 1f : 0f;
            home.setSwipe(position);
            if (lock != null) {
                lock.setSwipe(position);
            }
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            // Launchers report taps on empty home-screen space; two in a row switch looks.
            if (WallpaperManager.COMMAND_TAP.equals(action) && rotationMode == WallpaperSettings.ROTATE_DOUBLE_TAP) {
                long now = SystemClock.uptimeMillis();
                if (now - lastTapTime < DOUBLE_TAP_MS) {
                    lastTapTime = 0;
                    rotate();
                } else {
                    lastTapTime = now;
                }
            }
            return null;
        }

        // ---- Which look each slot shows -------------------------------------

        /** A look to show: its settings and where its background comes from. */
        private final class Look {
            final WallpaperSettings settings;
            final ImageStore.Picture picture;

            Look(WallpaperSettings settings, ImageStore.Picture picture) {
                this.settings = settings;
                this.picture = picture;
            }
        }

        private Look lookFor(PresetStore.Preset preset) {
            WallpaperSettings own = PresetStore.fromJson(preset.look);
            WallpaperSettings s = PresetStore.fromJson(preset.look).keepDeviceSettingsFrom(live);
            if (preset.hasImage) {
                s.mediaType = own.mediaType;
                return new Look(s, PresetStore.picture(context, preset.id));
            }
            return new Look(s, ImageStore.live(context));
        }

        private Look homeLook() {
            if (rotationMode != WallpaperSettings.ROTATE_OFF && !rotationIds.isEmpty()) {
                int start = rotationIndex;
                // Skip looks that were deleted since rotation was set up.
                for (int i = 0; i < rotationIds.size(); i++) {
                    int index = (start + i) % rotationIds.size();
                    PresetStore.Preset preset = PresetStore.find(context, rotationIds.get(index));
                    if (preset != null) {
                        rotationIndex = index;
                        return lookFor(preset);
                    }
                }
            }
            return new Look(live, ImageStore.live(context));
        }

        private Look lockLook() {
            PresetStore.Preset preset = PresetStore.find(context, WallpaperSettings.lockLookId(context));
            return preset != null ? lookFor(preset) : null;
        }

        /** Brings both slots up to date, loading backgrounds only when they changed. */
        private void refresh(boolean fade) {
            Look homeLook = homeLook();
            Look lockLook = lockLook();
            home.settings = homeLook.settings;
            if (lockLook == null) {
                if (lock != null) {
                    lock.release();
                    lock = null;
                }
            } else {
                if (lock == null) {
                    lock = new LookSlot(density, lockLook.settings);
                    lock.setPlaying(visible);
                }
                lock.settings = lockLook.settings;
            }
            if (width > 0 && height > 0) {
                show(home, homeLook, fade);
                if (lock != null) {
                    show(lock, lockLook, fade);
                }
            }
            applyAudio();
            updateArtSource();
        }

        private void show(LookSlot slot, Look look, boolean fade) {
            WallpaperSettings s = look.settings;
            boolean useArt = s.albumArt && art != null;
            String key = (useArt ? "art|" + artKey + "|" + s.albumArtStyle
                    : look.picture.stamp() + "|" + s.imageVersion + "|" + s.mediaType) + "|" + width + "x" + height;
            if (key.equals(slot.backgroundKey())) {
                return;
            }
            LookSlot.Background background;
            if (useArt) {
                background = new LookSlot.Background(AlbumArt.compose(art, width, height, s.albumArtStyle), null, null);
            } else {
                background = new LookSlot.Background(
                        ImageStore.load(look.picture.image, width, height),
                        ImageStore.loadMask(look.picture.mask),
                        s.mediaType != WallpaperSettings.MEDIA_IMAGE
                                ? MediaBackground.open(context, look.picture.media, s.mediaType) : null);
            }
            slot.setBackground(key, background, fade && slot.hasBackground());
        }

        private void loadRotation() {
            rotationIds = WallpaperSettings.rotationIds(context);
            rotationMode = WallpaperSettings.rotationMode(context);
            rotationMinutes = Math.max(1, WallpaperSettings.rotationMinutes(context));
        }

        /** Moves the home screen on to the next look in the rotation. */
        private void rotate() {
            if (rotationMode == WallpaperSettings.ROTATE_OFF || rotationIds.size() < 2) {
                return;
            }
            rotationIndex = (rotationIndex + 1) % rotationIds.size();
            lastRotation = SystemClock.uptimeMillis();
            refresh(true);
            if (visible) {
                updateTilt();
                scheduleFrame(0);
            }
        }

        private void onTrack(String trackKey, Bitmap cover) {
            String previousKey = artKey;
            if (art != null) {
                art.recycle();
            }
            art = cover;
            artKey = trackKey;
            boolean newSong = trackKey != null && previousKey != null && !trackKey.equals(previousKey);
            if (newSong && rotationMode == WallpaperSettings.ROTATE_SONG) {
                rotate();
            } else {
                refresh(true);
            }
            if (visible) {
                scheduleFrame(0);
            }
        }

        private void updateArtSource() {
            boolean wanted = visible && (home.settings.albumArt || (lock != null && lock.settings.albumArt)
                    || rotationMode == WallpaperSettings.ROTATE_SONG);
            if (wanted) {
                artSource.start();
            } else {
                artSource.stop();
            }
        }

        // ---- Lock state, sensors, audio --------------------------------------

        private boolean isLockedNow() {
            return !isPreview() && keyguard != null && keyguard.isKeyguardLocked();
        }

        private void updateLocked() {
            boolean now = isLockedNow();
            if (now == locked) {
                return;
            }
            locked = now;
            applyAudio();
            if (visible) {
                updateTilt();
                lastFrame = 0;
                asleep = false;
                scheduleFrame(0);
            }
        }

        private void applyAudio() {
            current().applyTo(analyzer, route.isBluetooth(), true);
        }

        private void updateTilt() {
            if (visible && current().tiltParallax > 0f) {
                tilt.start();
            } else {
                tilt.stop();
            }
        }

        // ---- Frames -----------------------------------------------------------

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
            if (rotationMode == WallpaperSettings.ROTATE_MINUTES
                    && now - lastRotation > rotationMinutes * 60_000L) {
                rotate();
            }
            LookSlot slot = currentSlot();
            WallpaperSettings s = slot.settings;
            boolean saving = power.shouldSave(s.batterySaver);
            slot.setSaving(saving);

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
            if (idle && !slot.isFading()) {
                boolean moving = s.mediaType != WallpaperSettings.MEDIA_IMAGE && !(s.albumArt && art != null);
                boolean animated = moving || s.breathe > 0f || s.sparkleAmount > 0f || s.sway > 0f
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
            LookSlot slot = currentSlot();
            slot.setTilt(tilt.x(), tilt.y());

            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = lockCanvas(holder);
                if (canvas != null) {
                    slot.draw(canvas, width, height, analyzer, dt);
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
