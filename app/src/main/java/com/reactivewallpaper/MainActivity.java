package com.reactivewallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_IMAGE = 1;
    private static final int REQUEST_AUDIO = 2;
    private static final int REQUEST_PAINT = 3;
    private static final int REQUEST_DIAGNOSTICS = 6;
    private static final int REQUEST_SUBJECT = 7;
    private static final int REQUEST_SAVE_CLIP = 8;
    private static final int REQUEST_GALLERY = 9;
    private static final long CLIP_MS = 6000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WallpaperSettings settings;
    private WallpaperRenderer renderer;
    private AudioAnalyzer analyzer;
    private AudioController audio;
    private DemoAudioInput demo;
    private OutputRoute route;
    private TiltSensor tilt;
    private TextView routeLabel;
    private TextView tempoLabel;
    /** Refreshes the tempo readout while the editor is open. */
    private final Runnable tempoTicker = new Runnable() {
        @Override
        public void run() {
            updateTempoLabel();
            mainHandler.postDelayed(this, 500);
        }
    };
    private boolean demoOn;
    private PreviewView preview;
    /** Holds the preview and its full-screen button; sized to the screen's shape. */
    private FrameLayout previewFrame;
    private PreviewView.FramingListener framing;
    private android.app.Dialog fullScreen;
    private android.app.Dialog tourCard;
    /** A clip being recorded, or null. */
    private ClipRecorder clip;
    private android.app.AlertDialog clipDialog;
    /** A finished clip waiting to be saved where the user picks (Android 9 and older). */
    private java.io.File pendingClip;

    // Undo history of the editor's settings (as saved JSON), newest last.
    private static final int MAX_HISTORY = 40;
    private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> redoStack = new java.util.ArrayDeque<>();
    private String lastSnapshot;
    private TextView undoButton;
    private TextView redoButton;
    private TextView permissionNote;
    private Button setWallpaper;
    private View applyStatus;
    private LinearLayout controls;
    private Controls ui;
    private View circleOptions;
    private View hueOption;
    private AlbumArtSource artSource;
    /** A newer release found on GitHub, shown as a banner at the top of the controls. */
    private UpdateUi updates;
    private LooksUi looksUi;
    private Bitmap art;
    private final Map<String, Boolean> expandedSections = new HashMap<>();
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // The editor works on a draft; the wallpaper only changes when it's applied.
        settings = WallpaperSettings.loadDraft(this);
        analyzer = new AudioAnalyzer(settings.barCount);
        route = new OutputRoute(this, bluetooth -> applyAudioSettings());
        applyAudioSettings();
        audio = new AudioController(this, analyzer);
        demo = new DemoAudioInput(analyzer);
        renderer = new WallpaperRenderer(getResources().getDisplayMetrics().density);

        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        preview = findViewById(R.id.preview);
        preview.bind(renderer, analyzer, settings);
        tilt = new TiltSensor(this);
        preview.setTiltSensor(tilt);
        framing = new PreviewView.FramingListener() {
            @Override
            public void onMove(float dx, float dy) {
                settings.imagePanX = Controls.clamp(settings.imagePanX + dx, -0.5f, 0.5f);
                settings.imagePanY = Controls.clamp(settings.imagePanY + dy, -0.5f, 0.5f);
                preview.setSettings(settings);
            }

            @Override
            public void onZoom(float factor) {
                settings.imageZoom = Controls.clamp(settings.imageZoom * factor, 0.5f, 3f);
                preview.setSettings(settings);
            }

            @Override
            public void onReset() {
                settings.resetPosition();
                preview.setSettings(settings);
            }

            @Override
            public void onGestureEnd() {
                saveDraft();
                // Move the zoom and position sliders to match.
                buildControls();
            }
        };
        preview.setFramingListener(framing);
        addFullScreenButton();
        addHistoryRow();
        lastSnapshot = snapshot(settings);
        artSource = new AlbumArtSource(this, (key, cover) -> {
            if (art != null) {
                art.recycle();
            }
            art = cover;
            if (settings.albumArt) {
                loadPreviewImage();
            }
        });
        sizePreviewToScreen();
        loadPreviewImage();

        permissionNote = findViewById(R.id.permission_note);
        permissionNote.setOnClickListener(v -> requestAudioPermission());
        findViewById(R.id.choose_image).setOnClickListener(v -> chooseImage());
        setWallpaper = findViewById(R.id.set_wallpaper);
        setWallpaper.setOnClickListener(v -> applyWallpaper());
        applyStatus = findViewById(R.id.apply_status);
        findViewById(R.id.discard).setOnClickListener(v -> discardChanges());

        controls = findViewById(R.id.controls);
        updates = new UpdateUi(this, executor, mainHandler, this::buildControls);
        looksUi = new LooksUi(this, executor, mainHandler, new LooksUi.Host() {
            @Override
            public WallpaperSettings settings() {
                return settings;
            }

            @Override
            public void onLookLoaded(WallpaperSettings next, boolean newPicture) {
                settings = next;
                applyAudioSettings();
                preview.setSettings(settings);
                buildControls();
                if (newPicture) {
                    loadPreviewImage();
                }
                updateApplyState();
            }
        });
        buildControls();

        handleLookLink(getIntent());

        if (!editorPrefs().getBoolean(KEY_TOUR, false)) {
            editorPrefs().edit().putBoolean(KEY_TOUR, true).apply();
            mainHandler.postDelayed(() -> showTour(0), 600);
        } else if (!AudioController.hasPermission(this)) {
            requestAudioPermission();
        }
    }

    private static final String KEY_SIMPLE = "simpleEditor";
    private static final String KEY_TOUR = "tourShown";

    /** The editor's own preferences (kept apart from the wallpaper's, which it watches). */
    private android.content.SharedPreferences editorPrefs() {
        return getSharedPreferences("editor_ui", MODE_PRIVATE);
    }

    private boolean isSimpleMode() {
        return editorPrefs().getBoolean(KEY_SIMPLE, false);
    }

    private void buildControls() {
        controls.removeAllViews();
        ui = new Controls(this, controls, expandedSections, new Controls.Listener() {
            @Override
            public void onLiveChange() {
                preview.setSettings(settings);
            }

            @Override
            public void onCommit() {
                changed();
            }
        });
        WallpaperSettings s = settings;
        updates.addBanner(controls);
        ui.simple = isSimpleMode();
        ui.choice(controls, new String[] {"Simple", "Everything"}, new int[] {1, 0}, ui.simple ? 1 : 0, v -> {
            editorPrefs().edit().putBoolean(KEY_SIMPLE, v == 1).apply();
            mainHandler.post(this::buildControls);
        });
        if (ui.simple) {
            ui.hint(controls, "Showing the main settings. Choose Everything for camera moves, shader effects, "
                    + "touch, unlock and more.");
        }

        LinearLayout looks = ui.section("Looks");
        looksUi.build(ui, looks);

        Button gallery = new Button(this);
        gallery.setText("Browse the look gallery");
        gallery.setOnClickListener(v -> startActivityForResult(new Intent(this, GalleryActivity.class)
                .putExtra(GalleryActivity.EXTRA_CURRENT, LookCode.encode("My look", settings)), REQUEST_GALLERY));
        looks.addView(gallery, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button record = new Button(this);
        record.setText("Record a clip (6 s)");
        record.setOnClickListener(v -> recordClip());
        looks.addView(record, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ui.hint(looks, "Records the wallpaper reacting to what's playing, as a video to share (no sound).");

        LinearLayout fit = ui.section("Image fit");
        ui.choiceColumn(fit, new String[] {"Fill (crop to fill the screen)", "Fit (show the whole image)",
                        "Stretch", "Fit width", "Fit height"},
                new int[] {WallpaperSettings.FIT_FILL, WallpaperSettings.FIT_FIT, WallpaperSettings.FIT_STRETCH,
                        WallpaperSettings.FIT_WIDTH, WallpaperSettings.FIT_HEIGHT},
                s.fitMode, v -> s.fitMode = v);
        ui.slider(fit, "Zoom", 0.5f, 3f, () -> s.imageZoom, v -> s.imageZoom = v, v -> Math.round(v * 100) + "%");
        ui.slider(fit, "Horizontal position", -0.5f, 0.5f, () -> s.imagePanX, v -> s.imagePanX = v,
                Controls::position);
        ui.slider(fit, "Vertical position", -0.5f, 0.5f, () -> s.imagePanY, v -> s.imagePanY = v,
                Controls::position);
        ui.label(fit, "Edges (where the image doesn't reach)");
        ui.choice(fit, new String[] {"Blurred image", "Black"},
                new int[] {WallpaperSettings.EDGES_BLUR, WallpaperSettings.EDGES_BLACK},
                s.edgeStyle, v -> s.edgeStyle = v);
        ui.toggle(fit, "Zoom in slightly so motion never shows the edges",
                () -> s.hideEdges, v -> s.hideEdges = v);
        ui.hint(fit, "Tip: drag the preview to move the image, pinch to zoom, double-tap to reset.");

        LinearLayout glow = ui.section("Glow (the image's own colors)");
        ui.slider(glow, "Bass glow", 0f, 3f, () -> s.bassGlow, v -> s.bassGlow = v, Controls::percent);
        ui.slider(glow, "Treble glow", 0f, 3f, () -> s.highGlow, v -> s.highGlow = v, Controls::percent);
        ui.slider(glow, "Beat flare", 0f, 3f, () -> s.beatFlare, v -> s.beatFlare = v, Controls::percent);
        ui.slider(glow, "Halo size", 0f, 3f, () -> s.bloom, v -> s.bloom = v, Controls::percent);
        ui.slider(glow, "Coverage (how much of the image glows)", 0f, 1f,
                () -> s.glowCoverage, v -> s.glowCoverage = v, Controls::percent);
        Button paint = new Button(this);
        paint.setText("Paint where it glows");
        paint.setOnClickListener(v -> {
            if (!ImageStore.hasDraftImage(this)) {
                Toast.makeText(this, "Choose an image first.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (settings.mediaType != WallpaperSettings.MEDIA_IMAGE) {
                Toast.makeText(this, "Painting works on still images. Videos and GIFs glow wherever "
                        + "each frame is bright.", Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(this, GlowPaintActivity.class);
            intent.putExtra(GlowPaintActivity.EXTRA_COVERAGE, settings.glowCoverage);
            startActivityForResult(intent, REQUEST_PAINT);
        });
        glow.addView(paint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ui.toggle(glow, "Swap which color follows the bass",
                () -> s.swapGlowColors, v -> s.swapGlowColors = v);

        LinearLayout subject = ui.section("Subject in front");
        ui.hint(subject, "Mark the picture's subject (a person, a character) and it's drawn in front of the "
                + "glow, particles and visualizer, with its own depth.");
        Button markSubject = new Button(this);
        markSubject.setText("Mark the subject");
        markSubject.setOnClickListener(v -> {
            if (!ImageStore.hasDraftImage(this)) {
                Toast.makeText(this, "Choose an image first.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (settings.mediaType != WallpaperSettings.MEDIA_IMAGE) {
                Toast.makeText(this, "The subject can be marked on still images only.", Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(this, GlowPaintActivity.class);
            intent.putExtra(GlowPaintActivity.EXTRA_SUBJECT, true);
            startActivityForResult(intent, REQUEST_SUBJECT);
        });
        subject.addView(markSubject, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ui.toggle(subject, "Draw the subject in front", () -> s.subjectFront, v -> s.subjectFront = v);
        ui.slider(subject, "Depth (moves apart from the background)", 0f, 1f,
                () -> s.subjectDepth, v -> s.subjectDepth = v, Controls::percent);
        ui.slider(subject, "Light behind the subject", 0f, 1f,
                () -> s.subjectAura, v -> s.subjectAura = v, Controls::percent);

        LinearLayout albumArt = ui.section("Album art");
        ui.toggle(albumArt, "Show the playing song's cover instead of the image", () -> s.albumArt, v -> {
            s.albumArt = v;
            if (v && !AlbumArtSource.hasAccess(this)) {
                AlbumArtSource.askForAccess(this);
            }
            updateArtSource();
            loadPreviewImage();
        });
        ui.choice(albumArt, new String[] {"Card on blur", "Fill the screen"},
                new int[] {WallpaperSettings.ART_CARD, WallpaperSettings.ART_FILL},
                s.albumArtStyle, v -> {
                    s.albumArtStyle = v;
                    loadPreviewImage();
                });
        ui.hint(albumArt, "Uses your image whenever nothing is playing. Needs notification access, which "
                + "Android requires to see what's playing; Resona never reads your notifications.");

        LinearLayout recolor = ui.section("Recolor", true);
        ui.slider(recolor, "Hue shift", 0f, 360f, () -> s.hueShift, v -> s.hueShift = v,
                v -> Math.round(v) == 0 || Math.round(v) == 360 ? "Off" : "+" + Math.round(v) + "°");
        ui.slider(recolor, "Color cycle (keeps shifting)", 0f, 1f, () -> s.colorCycle, v -> s.colorCycle = v,
                Controls::percent);
        ui.toggle(recolor, "Recolor the whole image, not just the glow",
                () -> s.recolorImage, v -> s.recolorImage = v);

        LinearLayout idle = ui.section("Idle animation (moves without sound)", true);
        ui.slider(idle, "Breathing glow", 0f, 2f, () -> s.breathe, v -> s.breathe = v, Controls::percent);
        ui.slider(idle, "Breathing speed", 0.25f, 3f, () -> s.breatheSpeed, v -> s.breatheSpeed = v, Controls::speed);
        ui.slider(idle, "Sway", 0f, 2f, () -> s.sway, v -> s.sway = v, Controls::percent);
        ui.slider(idle, "Sway speed", 0.25f, 3f, () -> s.swaySpeed, v -> s.swaySpeed = v, Controls::speed);

        LinearLayout sparkles = ui.section("Particles");
        ui.choiceColumn(sparkles, new String[] {"Sparkles (on the glowing spots)", "Embers (rising)",
                        "Falling stars", "Bubbles", "Light streaks"},
                new int[] {WallpaperSettings.PARTICLE_SPARKLES, WallpaperSettings.PARTICLE_EMBERS,
                        WallpaperSettings.PARTICLE_STARS, WallpaperSettings.PARTICLE_BUBBLES,
                        WallpaperSettings.PARTICLE_STREAKS},
                s.particleStyle, v -> s.particleStyle = v);
        ui.slider(sparkles, "Amount", 0f, 1f, () -> s.sparkleAmount, v -> s.sparkleAmount = v,
                v -> Math.round(v * WallpaperSettings.MAX_SPARKLES) == 0
                        ? "Off" : String.valueOf(Math.round(v * WallpaperSettings.MAX_SPARKLES)));
        ui.slider(sparkles, "Size", 0.25f, 3f, () -> s.sparkleSize, v -> s.sparkleSize = v, Controls::percent);
        ui.slider(sparkles, "Speed", 0.25f, 3f, () -> s.sparkleSpeed, v -> s.sparkleSpeed = v, Controls::speed);
        ui.slider(sparkles, "Brightness", 0f, 2f, () -> s.sparkleBrightness, v -> s.sparkleBrightness = v,
                Controls::percent);
        ui.slider(sparkles, "Music reaction", 0f, 2f, () -> s.sparkleReact, v -> s.sparkleReact = v,
                Controls::percent);

        LinearLayout depth = ui.section("Parallax (3D depth)", true);
        ui.slider(depth, "Tilt the phone", 0f, 2f, () -> s.tiltParallax, v -> {
            s.tiltParallax = v;
            if (v > 0f && resumed) {
                tilt.start();
            }
        }, Controls::percent);
        ui.slider(depth, "Home-screen swipe", 0f, 2f, () -> s.swipeParallax, v -> s.swipeParallax = v,
                Controls::percent);

        LinearLayout beat = ui.section("Beat effects", true);
        ui.slider(beat, "Pulse (zoom with the bass)", 0f, 2f, () -> s.pulse, v -> s.pulse = v, Controls::percent);
        ui.slider(beat, "Flash", 0f, 2f, () -> s.flash, v -> s.flash = v, Controls::percent);
        ui.slider(beat, "Shake", 0f, 2f, () -> s.shake, v -> s.shake = v, Controls::percent);
        ui.slider(beat, "Camera punch (zoom kick)", 0f, 2f, () -> s.camPunch, v -> s.camPunch = v, Controls::percent);
        ui.slider(beat, "Camera tilt (alternating)", 0f, 2f, () -> s.camTilt, v -> s.camTilt = v, Controls::percent);

        LinearLayout effects = ui.section("Shader effects", true);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ui.slider(effects, "Bass ripples", 0f, 2f, () -> s.fxRipple, v -> s.fxRipple = v, Controls::percent);
            ui.slider(effects, "Color split on beats", 0f, 2f, () -> s.fxSplit, v -> s.fxSplit = v, Controls::percent);
            ui.slider(effects, "Glitch on beats", 0f, 2f, () -> s.fxGlitch, v -> s.fxGlitch = v, Controls::percent);
            ui.slider(effects, "Heat haze", 0f, 2f, () -> s.fxHaze, v -> s.fxHaze = v, Controls::percent);
            ui.slider(effects, "Film grain", 0f, 1f, () -> s.fxGrain, v -> s.fxGrain = v, Controls::percent);
            ui.slider(effects, "Vignette", 0f, 1f, () -> s.fxVignette, v -> s.fxVignette = v, Controls::percent);
            ui.hint(effects, "These bend and color the whole picture on the GPU. With the Ripple tap effect, "
                    + "taps make the picture ripple like water too.");
        } else {
            ui.hint(effects, "Shader effects need Android 13 or newer.");
        }

        LinearLayout touch = ui.section("Touch", true);
        ui.choiceColumn(touch, new String[] {"Off", "Ripple of light", "Burst of sparks (drag for a trail)",
                        "Glow flare"},
                new int[] {WallpaperSettings.TAP_OFF, WallpaperSettings.TAP_RIPPLE, WallpaperSettings.TAP_BURST,
                        WallpaperSettings.TAP_FLARE},
                s.tapEffect, v -> s.tapEffect = v);
        ui.hint(touch, "Touch empty space on your home screen. Tap the preview here to try it.");

        LinearLayout moments = ui.section("Unlock, charging & notifications", true);
        ui.label(moments, "When you unlock the phone");
        ui.choiceColumn(moments, new String[] {"Nothing", "Fade up from dark", "Zoom in and bloom", "Wave of light"},
                new int[] {WallpaperSettings.INTRO_OFF, WallpaperSettings.INTRO_FADE,
                        WallpaperSettings.INTRO_ZOOM, WallpaperSettings.INTRO_WAVE},
                s.unlockIntro, v -> {
                    s.unlockIntro = v;
                    renderer.playIntro(s);
                });
        Button previewIntro = new Button(this);
        previewIntro.setText("Preview the unlock");
        previewIntro.setOnClickListener(v -> renderer.playIntro(s));
        moments.addView(previewIntro, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ui.toggle(moments, "Wave of light and battery level when charging starts",
                () -> s.chargeEffect, v -> s.chargeEffect = v);
        Button previewCharge = new Button(this);
        previewCharge.setText("Preview charging");
        previewCharge.setOnClickListener(v -> {
            android.os.BatteryManager battery = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            int percent = battery != null
                    ? battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
            renderer.playCharge(percent >= 0 && percent <= 100 ? percent : -1);
        });
        moments.addView(previewCharge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ui.toggle(moments, "Glow around the edges when a notification arrives", () -> s.notifyPulse, v -> {
            s.notifyPulse = v;
            if (v && !AlbumArtSource.hasAccess(this)) {
                AlbumArtSource.askForAccess(this);
            }
        });
        ui.toggle(moments, "In the app's own color", () -> s.notifyUseAppColor, v -> s.notifyUseAppColor = v);
        ui.hint(moments, "Uses notification access. Resona only notices that something arrived and its "
                + "app's color; it never reads what notifications say.");
        Button previewPulse = new Button(this);
        previewPulse.setText("Preview notification glow");
        previewPulse.setOnClickListener(v -> renderer.playPulse(0));
        moments.addView(previewPulse, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout viz = ui.section("Visualizer overlay");
        ui.choiceColumn(viz, new String[] {"None", "Bars", "Mirror bars", "LED meter (peaks hold and fall)",
                "Circle", "Orb (ripples with the music)", "Wave", "Neon lines"},
                new int[] {WallpaperSettings.STYLE_NONE, WallpaperSettings.STYLE_BARS,
                        WallpaperSettings.STYLE_MIRROR, WallpaperSettings.STYLE_LED,
                        WallpaperSettings.STYLE_CIRCLE, WallpaperSettings.STYLE_ORB,
                        WallpaperSettings.STYLE_WAVE, WallpaperSettings.STYLE_LINES},
                s.style, v -> {
                    s.style = v;
                    updateVisibility();
                });
        ui.toggle(viz, "In front of the subject", () -> s.vizFront, v -> s.vizFront = v);
        ui.hint(viz, "When a subject is marked (Subject in front), the visualizer goes behind it unless this "
                + "is on.");
        ui.slider(viz, "Height", 0f, 2f, () -> s.vizHeight, v -> s.vizHeight = v, Controls::percent);
        ui.slider(viz, "Opacity", 0f, 1f, () -> s.vizOpacity, v -> s.vizOpacity = v, Controls::percent);
        ui.slider(viz, "Bar count", WallpaperSettings.MIN_BARS, WallpaperSettings.MAX_BARS,
                () -> s.barCount, v -> {
                    s.barCount = Math.round(v);
                    applyAudioSettings();
                }, v -> String.valueOf(Math.round(v)));
        Button findRing = new Button(this);
        findRing.setText("Find the ring in my picture");
        findRing.setOnClickListener(v -> alignRingToPicture());
        viz.addView(findRing, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout circle = ui.group(viz);
        ui.slider(circle, "Ring position (up / down)", 0.1f, 0.9f, () -> s.circleY, v -> s.circleY = v,
                Controls::percent);
        ui.slider(circle, "Ring position (left / right)", 0.1f, 0.9f, () -> s.circleX, v -> s.circleX = v,
                Controls::percent);
        ui.slider(circle, "Ring size", 0.08f, 0.5f, () -> s.circleSize, v -> s.circleSize = v, Controls::percent);
        circleOptions = circle;
        ui.label(viz, "Color");
        ui.choice(viz, new String[] {"From image", "Rainbow", "Custom"},
                new int[] {WallpaperSettings.COLOR_AUTO, WallpaperSettings.COLOR_RAINBOW,
                        WallpaperSettings.COLOR_CUSTOM},
                s.colorMode, v -> {
                    s.colorMode = v;
                    updateVisibility();
                });
        LinearLayout hue = ui.group(viz);
        ui.slider(hue, "Hue", 0f, 360f, () -> s.hue, v -> s.hue = v, v -> Math.round(v) + "°");
        hueOption = hue;

        LinearLayout sound = ui.section("Audio");
        ui.slider(sound, "Sensitivity", 0.25f, 3f, () -> s.sensitivity, v -> {
            s.sensitivity = v;
            applyAudioSettings();
        }, Controls::percent);
        ui.slider(sound, "Smoothing (snappy ↔ smooth)", 0f, 1f, () -> s.smoothing, v -> {
            s.smoothing = v;
            applyAudioSettings();
        }, v -> Math.round(v * 100) + "%");
        ui.slider(sound, "Beat sensitivity", 0f, 1f, () -> s.beatSensitivity, v -> {
            s.beatSensitivity = v;
            applyAudioSettings();
        }, v -> Math.round(v * 100) + "%");
        ui.toggle(sound, "Lock to the song's tempo", () -> s.tempoSync, v -> {
            s.tempoSync = v;
            applyAudioSettings();
        });
        ui.hint(sound, "Learns the beat from the music, then lands beat effects exactly on it, "
                + "including soft beats that would be missed.");
        tempoLabel = new TextView(this);
        tempoLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tempoLabel.setTextColor(getColor(R.color.accent));
        sound.addView(tempoLabel);
        updateTempoLabel();
        ui.label(sound, "Sync: if the wallpaper reacts before you hear the beat, add delay. "
                + "Bluetooth usually needs 150–300 ms. Calibrate to measure it.");
        Button calibrate = new Button(this);
        calibrate.setText("Calibrate sync (tap along)");
        calibrate.setOnClickListener(v -> openCalibration());
        sound.addView(calibrate, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        routeLabel = new TextView(this);
        routeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        routeLabel.setTextColor(getColor(R.color.accent));
        sound.addView(routeLabel);
        updateRouteLabel();
        ui.slider(sound, "Delay – phone speaker / wired", 0f, 600f, () -> s.syncSpeakerMs, v -> {
            s.syncSpeakerMs = Math.round(v);
            applyAudioSettings();
        }, v -> Math.round(v) + " ms");
        ui.slider(sound, "Delay – Bluetooth", 0f, 600f, () -> s.syncBluetoothMs, v -> {
            s.syncBluetoothMs = Math.round(v);
            applyAudioSettings();
        }, v -> Math.round(v) + " ms");
        ui.label(sound, "React to");
        ui.choice(sound, new String[] {"Phone audio", "Microphone"},
                new int[] {WallpaperSettings.SOURCE_SYSTEM, WallpaperSettings.SOURCE_MIC},
                s.source, v -> {
                    s.source = v;
                    applyAudioSettings();
                    restartAudio();
                });
        ui.toggle(sound, "Demo beat in preview (tune without music)", () -> demoOn, v -> {
            demoOn = v;
            applyAudioSettings();
            restartAudio();
        });

        LinearLayout widget = ui.section("Home screen media buttons", true);
        ui.label(widget, "A widget with previous, play/pause and next that can be faint or completely "
                + "invisible, so your screen shows only the wallpaper.");
        Button addWidget = new Button(this);
        addWidget.setText("Add media buttons to home screen");
        addWidget.setOnClickListener(v -> addMediaWidget());
        widget.addView(addWidget, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ui.toggle(widget, "Show where the buttons are", () -> MediaWidgetProvider.revealing(this),
                v -> MediaWidgetProvider.setRevealing(this, v));
        ui.hint(widget, "Outlines the buttons on every Resona widget until you turn this off. "
                + "Long-press a widget (Android 12+) to change how visible it is.");

        LinearLayout battery = ui.section("Battery", true);
        ui.label(battery, "Battery saver: lower frame rate and half the particles");
        ui.choice(battery, new String[] {"Off", "Auto", "Always"},
                new int[] {WallpaperSettings.SAVER_OFF, WallpaperSettings.SAVER_AUTO, WallpaperSettings.SAVER_ALWAYS},
                s.batterySaver, v -> s.batterySaver = v);
        ui.hint(battery, "Auto: when the phone is in battery saver or below 20% and not charging.");
        ui.label(battery, "Frame rate");
        ui.choice(battery, new String[] {"30", "60", "Screen's max"}, new int[] {30, 60, 0}, s.maxFps,
                v -> s.maxFps = v);
        ui.hint(battery, "Lower frame rates save battery; the screen's max is smoothest on 90 and 120 Hz phones.");
        ui.slider(battery, "Sleep after silence", 0f, 30f, () -> s.sleepMinutes, v -> s.sleepMinutes = Math.round(v),
                v -> Math.round(v) == 0 ? "Never" : Math.round(v) + " min");

        LinearLayout about = ui.section("About & updates");
        ui.label(about, "Resona " + Updater.installedVersion(this));
        Button tour = new Button(this);
        tour.setText("Show the quick tour");
        tour.setOnClickListener(v -> showTour(0));
        about.addView(tour, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button check = new Button(this);
        check.setText("Check for updates");
        check.setOnClickListener(v -> updates.check(true));
        about.addView(check, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ui.toggle(about, "Check automatically when opening the app",
                () -> Updater.autoCheckEnabled(this), v -> Updater.setAutoCheck(this, v));
        Button diagnostics = new Button(this);
        diagnostics.setText("Export diagnostics");
        diagnostics.setOnClickListener(v -> exportDiagnostics());
        about.addView(diagnostics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ui.hint(about, "Saves a text report (phone, settings, recent crashes and app log) to send "
                + "when something goes wrong. No pictures or notification contents are included.");

        Button reset = new Button(this);
        reset.setText("Reset all effects to defaults");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        controls.addView(reset, lp);
        reset.setOnClickListener(v -> {
            settings = settings.resetEffects();
            saveDraft();
            applyAudioSettings();
            preview.setSettings(settings);
            buildControls();
        });

        updateVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        route.start();
        mainHandler.removeCallbacks(tempoTicker);
        mainHandler.post(tempoTicker);
        applyAudioSettings();
        updatePermissionNote();
        // The wallpaper may have been set or changed while we were away.
        updateApplyState();
        // Coming back to the app (not just launching it) is when a new release is noticed.
        if (Updater.claimAutoCheck(this)) {
            updates.check(false);
        }
        tilt.start();
        renderer.setMediaPlaying(true);
        updateArtSource();
        looksUi.refresh();
        restartAudio();
        preview.setRunning(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        mainHandler.removeCallbacks(tempoTicker);
        if (clip != null) {
            // Leaving the editor ends the clip early; what was recorded is kept.
            endClip();
        }
        if (fullScreen != null) {
            fullScreen.dismiss();
        }
        preview.setRunning(false);
        audio.stop();
        demo.stop();
        route.stop();
        tilt.stop();
        artSource.stop();
        renderer.setMediaPlaying(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Dialogs go with the screen, so their windows aren't leaked.
        if (tourCard != null && tourCard.isShowing()) {
            tourCard.dismiss();
        }
        if (fullScreen != null) {
            fullScreen.dismiss();
        }
        executor.shutdown();
        renderer.release();
    }

    private void applyAudioSettings() {
        // The demo beat is generated in sync with the preview, so it is never delayed.
        settings.applyTo(analyzer, route.isBluetooth(), !demoOn);
        updateRouteLabel();
    }

    private void updateRouteLabel() {
        if (routeLabel != null) {
            routeLabel.setText(route.isBluetooth()
                    ? "Playing through Bluetooth now: the Bluetooth delay is in use."
                    : "Playing through the phone speaker or wired headphones now.");
        }
    }

    // ---- Looks (presets) ---------------------------------------------------

    private void updateArtSource() {
        if (resumed && settings.albumArt) {
            artSource.start();
        } else {
            artSource.stop();
        }
    }

    private void addMediaWidget() {
        android.appwidget.AppWidgetManager manager = android.appwidget.AppWidgetManager.getInstance(this);
        ComponentName provider = new ComponentName(this, MediaWidgetProvider.class);
        if (manager.isRequestPinAppWidgetSupported()) {
            // The launcher asks the user to place it.
            manager.requestPinAppWidget(provider, null, null);
        } else {
            Toast.makeText(this, "Long-press your home screen, choose Widgets, and find Resona.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void exportDiagnostics() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new java.util.Date());
        intent.putExtra(Intent.EXTRA_TITLE, "resona-diagnostics-" + stamp + ".txt");
        try {
            startActivityForResult(intent, REQUEST_DIAGNOSTICS);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app available to save files.", Toast.LENGTH_LONG).show();
        }
    }

    private void finishDiagnostics(Uri uri) {
        executor.execute(() -> {
            String message;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new java.io.IOException("Could not open the file");
                }
                out.write(Diagnostics.report(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                message = "Diagnostics saved";
            } catch (java.io.IOException | SecurityException e) {
                message = "Couldn't save diagnostics: " + e.getMessage();
            }
            String text = message;
            mainHandler.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
        });
    }

    // ---- Sync calibration --------------------------------------------------

    private void openCalibration() {
        if (settings.source != WallpaperSettings.SOURCE_SYSTEM) {
            Toast.makeText(this, "Not needed in microphone mode: the mic hears the sound when you do.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!AudioController.hasPermission(this)) {
            requestAudioPermission();
            return;
        }
        CalibrationDialog.show(this, route.isBluetooth(), new CalibrationDialog.Host() {
            @Override
            public void pauseAudio() {
                // Free the shared audio capture while calibrating.
                audio.stop();
                demo.stop();
            }

            @Override
            public void resumeAudio() {
                restartAudio();
            }

            @Override
            public void onMeasured(boolean bluetooth, int delayMs) {
                if (bluetooth) {
                    settings.syncBluetoothMs = delayMs;
                } else {
                    settings.syncSpeakerMs = delayMs;
                }
                saveDraft();
                applyAudioSettings();
                buildControls();
            }
        });
    }

    private void restartAudio() {
        if (!resumed) {
            return;
        }
        if (demoOn) {
            audio.stop();
            demo.start();
        } else {
            demo.stop();
            audio.stop();
            audio.start(settings.source);
        }
    }

    /**
     * Gives the preview the wallpaper's shape. On phones it sits above the
     * settings; on wide screens (tablets, unfolded foldables, landscape) it fills
     * most of the left column, in the screen's current shape.
     */
    private void sizePreviewToScreen() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        ViewGroup.LayoutParams lp = previewFrame.getLayoutParams();
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        if (wide) {
            float aspect = dm.widthPixels / (float) Math.max(1, dm.heightPixels);
            lp.height = (int) (dm.heightPixels * 0.72f);
            lp.width = (int) (lp.height * aspect);
            int maxWidth = (int) (dm.widthPixels * 0.42f * 0.9f);
            if (lp.width > maxWidth) {
                lp.width = maxWidth;
                lp.height = (int) (maxWidth / aspect);
            }
        } else {
            int screenW = Math.min(dm.widthPixels, dm.heightPixels);
            int screenH = Math.max(dm.widthPixels, dm.heightPixels);
            lp.height = (int) (dm.heightPixels * 0.38f);
            lp.width = (int) (lp.height * screenW / (float) screenH);
        }
        previewFrame.setLayoutParams(lp);
    }

    /** Puts the preview in a frame with a small full-screen button in its corner. */
    private void addFullScreenButton() {
        ViewGroup parent = (ViewGroup) preview.getParent();
        int index = parent.indexOfChild(preview);
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        parent.removeView(preview);
        previewFrame = new FrameLayout(this);
        // Let the preview's shadow show outside the frame.
        previewFrame.setClipChildren(false);
        previewFrame.setClipToPadding(false);
        previewFrame.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View expand = roundButton(IconDrawable.FULL_SCREEN, "Full screen preview");
        expand.setOnClickListener(v -> openFullScreenPreview());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END);
        blp.setMargins(0, dp(6), dp(6), 0);
        // Above the preview's elevation, so it isn't drawn underneath it.
        expand.setElevation(preview.getElevation() + dp(2));
        previewFrame.addView(expand, blp);
        parent.addView(previewFrame, index, lp);
    }

    /** A dark round button with a drawn icon (not a font symbol, which varies by phone). */
    private View roundButton(int icon, String description) {
        android.widget.ImageView b = new android.widget.ImageView(this);
        b.setImageDrawable(new IconDrawable(icon, 0xFFFFFFFF, dp(2)));
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        b.setContentDescription(description);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        b.setBackground(bg);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /**
     * Shows the preview over the whole screen, as the wallpaper will look, with
     * the system bars hidden. Taps, drags and pinches work as in the small one.
     */
    private void openFullScreenPreview() {
        if (fullScreen != null) {
            return;
        }
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        PreviewView full = new PreviewView(this, null);
        // Square corners: it fills the screen like the wallpaper does.
        full.setClipToOutline(false);
        full.bind(renderer, analyzer, settings);
        full.setTiltSensor(tilt);
        full.setFramingListener(framing);
        box.addView(full, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View close = roundButton(IconDrawable.CLOSE, "Close full screen preview");
        close.setOnClickListener(v -> dialog.dismiss());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(28), dp(16), 0);
        box.addView(close, clp);
        TextView tip = new TextView(this);
        tip.setText("Tap to try touch effects. Drag or pinch to frame the picture.");
        tip.setTextColor(0xFFFFFFFF);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dp(16), dp(10), dp(16), dp(10));
        android.graphics.drawable.GradientDrawable tipBg = new android.graphics.drawable.GradientDrawable();
        tipBg.setCornerRadius(dp(18));
        tipBg.setColor(0x99000000);
        tip.setBackground(tipBg);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tlp.bottomMargin = dp(48);
        box.addView(tip, tlp);
        tip.animate().alpha(0f).setStartDelay(3000).setDuration(600).start();
        dialog.setContentView(box);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.getAttributes().layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        dialog.setOnDismissListener(d -> {
            full.setRunning(false);
            fullScreen = null;
            preview.setSettings(settings);
            preview.setRunning(resumed);
        });
        // Only one view drives the shared renderer at a time.
        preview.setRunning(false);
        fullScreen = dialog;
        dialog.show();
        if (window != null) {
            hideSystemBars(window);
        }
        full.setRunning(true);
    }

    @SuppressWarnings("deprecation")
    private static void hideSystemBars(android.view.Window window) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void loadPreviewImage() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels / 2;
        int h = dm.heightPixels / 2;
        if (settings.albumArt && art != null) {
            // Show the playing song's cover, as the wallpaper will.
            renderer.setImage(AlbumArt.compose(art, w, h, settings.albumArtStyle), null);
            renderer.setFramed(false);
            renderer.setMedia(null);
            preview.invalidate();
            return;
        }
        int mediaType = settings.mediaType;
        executor.execute(() -> {
            Bitmap bitmap = ImageStore.load(ImageStore.draftFile(this), w, h);
            Bitmap mask = ImageStore.loadMask(ImageStore.draftMaskFile(this));
            Bitmap subjectMask = mediaType == WallpaperSettings.MEDIA_IMAGE
                    ? ImageStore.loadMask(ImageStore.draftSubjectFile(this)) : null;
            SubjectLayer subject = subjectMask != null ? SubjectLayer.build(bitmap, subjectMask) : null;
            if (subjectMask != null) {
                subjectMask.recycle();
            }
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    if (subject != null) {
                        subject.release();
                    }
                    return;
                }
                renderer.setImage(bitmap, mask);
                renderer.setSubject(subject);
                renderer.setFramed(true);
                // Videos and GIFs open on the main thread (their players call back here).
                renderer.setMedia(mediaType != WallpaperSettings.MEDIA_IMAGE
                        ? MediaBackground.open(this, ImageStore.draft(this).media, mediaType) : null);
                renderer.setMediaPlaying(resumed);
                preview.invalidate();
            });
        });
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.choose_image)), REQUEST_IMAGE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_gallery, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_GALLERY) {
            LookCode.Look look = resultCode == RESULT_OK && data != null
                    ? LookCode.decode(data.getStringExtra(GalleryActivity.EXTRA_CODE)) : null;
            if (look != null) {
                // Like loading a saved look: the picture, its framing and this phone's settings stay.
                WallpaperSettings next = look.settings.keepDeviceSettingsFrom(settings);
                next.saveDraft(this);
                settings = next;
                applyAudioSettings();
                preview.setSettings(settings);
                buildControls();
                updateArtSource();
                updateApplyState();
                Toast.makeText(this, "Loaded “" + look.name + "”. Set as wallpaper to apply it.",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQUEST_SAVE_CLIP) {
            java.io.File file = pendingClip;
            pendingClip = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && file != null) {
                Uri target = data.getData();
                executor.execute(() -> {
                    boolean ok = copyTo(file, target);
                    file.delete();
                    mainHandler.post(() -> Toast.makeText(this, ok ? "Clip saved" : "Couldn't save the clip",
                            Toast.LENGTH_SHORT).show());
                });
            }
            return;
        }
        if (requestCode == REQUEST_DIAGNOSTICS) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                finishDiagnostics(data.getData());
            }
            return;
        }
        if (looksUi.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        if ((requestCode == REQUEST_PAINT || requestCode == REQUEST_SUBJECT) && resultCode == RESULT_OK) {
            // The painted mask counts as an image change: preview and wallpaper reload both.
            settings.imageVersion = System.currentTimeMillis();
            saveDraft();
            loadPreviewImage();
            return;
        }
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        executor.execute(() -> {
            String type = getContentResolver().getType(uri);
            int mediaType = ImageStore.importBackground(this, uri);
            if (mediaType >= 0) {
                // Painted masks belong to the old picture.
                ImageStore.draftMaskFile(this).delete();
                ImageStore.draftSubjectFile(this).delete();
            }
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (mediaType < 0) {
                    boolean video = type != null && type.startsWith("video/");
                    Toast.makeText(this, video && android.os.Build.VERSION.SDK_INT < 29
                            ? "Video backgrounds need Android 10 or newer."
                            : video ? "Couldn't open that video." : getString(R.string.image_failed),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                settings.imageVersion = System.currentTimeMillis();
                settings.mediaType = mediaType;
                // A new picture starts centered; the fill mode the user prefers stays.
                settings.resetPosition();
                saveDraft();
                loadPreviewImage();
            });
        });
    }

    /**
     * Looks for a ring or circle in the picture and puts the ring visualizer on
     * it, where it will be on the phone's screen with the current framing.
     */
    private void alignRingToPicture() {
        if (!ImageStore.hasDraftImage(this) || (settings.albumArt && art != null)) {
            Toast.makeText(this, "Choose a picture first.", Toast.LENGTH_SHORT).show();
            return;
        }
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        Toast.makeText(this, "Looking for a ring…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            Bitmap image = ImageStore.load(ImageStore.draftFile(this), 480, 480);
            RingFinder.Ring found = null;
            int bw = 0;
            int bh = 0;
            if (image != null) {
                float scale = 240f / Math.max(image.getWidth(), image.getHeight());
                bw = Math.max(1, Math.round(image.getWidth() * scale));
                bh = Math.max(1, Math.round(image.getHeight() * scale));
                Bitmap small = Bitmap.createScaledBitmap(image, bw, bh, true);
                int[] px = new int[bw * bh];
                small.getPixels(px, 0, bw, 0, 0, bw, bh);
                found = RingFinder.find(px, bw, bh);
                if (small != image) {
                    small.recycle();
                }
                image.recycle();
            }
            RingFinder.Ring ring = found;
            int imageW = bw;
            int imageH = bh;
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (ring == null) {
                    Toast.makeText(this, "Couldn't find a clear ring or circle in this picture. "
                            + "Use the ring sliders to place it.", Toast.LENGTH_LONG).show();
                    return;
                }
                float[] fit = WallpaperRenderer.fit(imageW, imageH, screenW, screenH, settings, true);
                float x = (screenW - imageW * fit[0]) / 2f + fit[2] + ring.x * fit[0];
                float y = (screenH - imageH * fit[1]) / 2f + fit[3] + ring.y * fit[1];
                float radius = ring.radius * (fit[0] + fit[1]) / 2f;
                settings.circleX = clamp(x / screenW, 0.1f, 0.9f);
                settings.circleY = clamp(y / screenH, 0.1f, 0.9f);
                settings.circleSize = clamp(radius / Math.min(screenW, screenH), 0.08f, 0.5f);
                if (!WallpaperSettings.isRing(settings.style)) {
                    settings.style = WallpaperSettings.STYLE_CIRCLE;
                }
                saveDraft();
                buildControls();
                Toast.makeText(this, "Ring placed on the picture's ring.", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void updateTempoLabel() {
        if (tempoLabel == null) {
            return;
        }
        float bpm = analyzer.bpm();
        tempoLabel.setText(!settings.tempoSync ? "Tempo: off"
                : bpm > 0f ? "Tempo: " + Math.round(bpm) + " BPM (locked)"
                : "Tempo: listening… play some music");
    }

    private boolean isOurs(WallpaperInfo info) {
        return info != null && new ComponentName(this, ReactiveWallpaperService.class).equals(info.getComponent());
    }

    private boolean isOnHomeScreen() {
        return isOurs(WallpaperManager.getInstance(this).getWallpaperInfo());
    }

    private boolean isOnLockScreen() {
        WallpaperManager wm = WallpaperManager.getInstance(this);
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                return isOurs(wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK));
            } catch (SecurityException e) {
                // Some phone makers restrict this; fall through to the older check.
            }
        }
        // Before Android 14 the lock screen shows the home wallpaper unless it has its own.
        return isOnHomeScreen() && wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0;
    }

    /** True if Resona is the wallpaper on the home screen, the lock screen, or both. */
    private boolean isOurWallpaperActive() {
        return isOnHomeScreen() || isOnLockScreen();
    }

    /** Copies the draft (settings and image) to the live wallpaper, then runs {@code then}. */
    private void publish(Runnable then) {
        WallpaperSettings draft = settings.copy();
        executor.execute(() -> {
            WallpaperSettings live = WallpaperSettings.load(this);
            if (draft.imageVersion != live.imageVersion) {
                ImageStore.copy(ImageStore.draft(this), ImageStore.live(this));
            }
            // Saved after the image is in place, since saving is what tells the wallpaper to reload.
            draft.save(this);
            mainHandler.post(() -> {
                if (!isDestroyed()) {
                    then.run();
                }
            });
        });
    }

    private void applyWallpaper() {
        boolean pending = !settings.sameAs(WallpaperSettings.load(this));
        if (isOurWallpaperActive() && pending) {
            publish(() -> {
                updateApplyState();
                Toast.makeText(this, R.string.applied_toast, Toast.LENGTH_SHORT).show();
            });
            return;
        }
        // Nothing to apply: open Android's wallpaper screen, where the user picks home,
        // lock or both. It previews the live settings, so publish before opening it.
        publish(this::openWallpaperPicker);
    }

    private void openWallpaperPicker() {
        ComponentName component = new ComponentName(this, ReactiveWallpaperService.class);
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
                Toast.makeText(this, R.string.pick_from_list, Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, R.string.no_live_wallpaper, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestAudioPermission() {
        requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO) {
            updatePermissionNote();
            restartAudio();
        }
    }

    private void updatePermissionNote() {
        permissionNote.setVisibility(AudioController.hasPermission(this) ? View.GONE : View.VISIBLE);
    }

    private void updateVisibility() {
        circleOptions.setVisibility(WallpaperSettings.isRing(settings.style) ? View.VISIBLE : View.GONE);
        hueOption.setVisibility(settings.colorMode == WallpaperSettings.COLOR_CUSTOM ? View.VISIBLE : View.GONE);
    }

    private void changed() {
        saveDraft();
        preview.setSettings(settings);
    }

    /**
     * Saves the editor's draft. Audio source, sync delays and battery options
     * describe this phone rather than the look, so those reach the wallpaper at once.
     */
    private void saveDraft() {
        settings.saveDraft(this);
        WallpaperSettings live = WallpaperSettings.load(this);
        if (live.copyDeviceSettingsFrom(settings)) {
            live.save(this);
        }
        updateApplyState();
    }

    private static String snapshot(WallpaperSettings s) {
        return PresetStore.toJson(s).toString();
    }

    /** Remembers the settings as they were before this change, for undo. */
    private void noteHistory() {
        if (lastSnapshot == null) {
            return;
        }
        String now = snapshot(settings);
        if (now.equals(lastSnapshot)) {
            return;
        }
        undoStack.addLast(lastSnapshot);
        while (undoStack.size() > MAX_HISTORY) {
            undoStack.removeFirst();
        }
        redoStack.clear();
        lastSnapshot = now;
        updateHistoryButtons();
    }

    /** Steps back (or forward) through the history; the picture itself stays. */
    private void stepHistory(boolean undo) {
        java.util.ArrayDeque<String> from = undo ? undoStack : redoStack;
        java.util.ArrayDeque<String> to = undo ? redoStack : undoStack;
        if (from.isEmpty()) {
            return;
        }
        to.addLast(lastSnapshot);
        String json = from.removeLast();
        try {
            WallpaperSettings next = PresetStore.fromJson(new org.json.JSONObject(json));
            // The picture and its type stay as they are now; only the settings step.
            next.imageVersion = settings.imageVersion;
            next.mediaType = settings.mediaType;
            settings = next;
        } catch (org.json.JSONException e) {
            return;
        }
        lastSnapshot = snapshot(settings);
        settings.saveDraft(this);
        applyAudioSettings();
        preview.setSettings(settings);
        buildControls();
        updateArtSource();
        loadPreviewImage();
        updateApplyState();
        updateHistoryButtons();
    }

    private void updateHistoryButtons() {
        if (undoButton != null) {
            undoButton.setEnabled(!undoStack.isEmpty());
            undoButton.setAlpha(undoStack.isEmpty() ? 0.35f : 1f);
            redoButton.setEnabled(!redoStack.isEmpty());
            redoButton.setAlpha(redoStack.isEmpty() ? 0.35f : 1f);
        }
    }

    /** Undo, redo and hold-to-compare, in a slim row under the preview. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void addHistoryRow() {
        ViewGroup parent = (ViewGroup) previewFrame.getParent();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        undoButton = rowButton("\u21B6 Undo");
        undoButton.setOnClickListener(v -> stepHistory(true));
        redoButton = rowButton("Redo \u21B7");
        redoButton.setOnClickListener(v -> stepHistory(false));
        TextView compare = rowButton("Hold to compare");
        compare.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                // What's on the wallpaper now, drawn over the editor's picture.
                preview.setSettings(WallpaperSettings.load(this));
                compare.setText("Showing applied");
                v.setPressed(true);
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                preview.setSettings(settings);
                compare.setText("Hold to compare");
                v.setPressed(false);
            }
            return true;
        });
        row.addView(undoButton);
        row.addView(compare);
        row.addView(redoButton);
        parent.addView(row, parent.indexOfChild(previewFrame) + 1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updateHistoryButtons();
    }

    private TextView rowButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(getColor(R.color.accent));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(40));
        b.setPadding(dp(14), 0, dp(14), 0);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        b.setBackgroundResource(ripple.resourceId);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /**
     * A short first-run tour, a few cards long; also from About. Skipping
     * anywhere ends it.
     */
    private void showTour(int step) {
        String[][] cards = {
                {"Welcome to Resona", "Resona turns your picture into a wallpaper that moves with your music. "
                        + "Here's the quick tour."},
                {"1. Pick a picture", "Tap Choose image. Photos, art, GIFs and videos all work."},
                {"2. Make it yours", "Open the sections below the preview. Simple shows the main settings; "
                        + "Everything shows all of them. Undo, redo and hold-to-compare sit under the preview."},
                {"3. See it big", "Tap the corner button on the preview to see it full screen, and tap the "
                        + "preview to try touch effects."},
                {"4. Set it", "Tap Set wallpaper, then choose the home screen, lock screen or both. Save "
                        + "looks you like under Looks to switch back any time."},
        };
        if (isDestroyed()) {
            return;
        }
        if (step >= cards.length) {
            afterTour();
            return;
        }
        tourCard = new android.app.AlertDialog.Builder(this)
                .setTitle(cards[step][0])
                .setMessage(cards[step][1])
                .setPositiveButton(step == cards.length - 1 ? "Done" : "Next", (d, w) -> showTour(step + 1))
                .setNegativeButton(step == 0 ? "Skip" : null, (d, w) -> afterTour())
                .setOnCancelListener(d -> afterTour())
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleLookLink(intent);
    }

    /** A tapped resona://look/... link: offer to add the shared look. */
    private void handleLookLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            return;
        }
        LookCode.Look look = LookCode.decode(intent.getData().toString());
        // Handled once, even if the screen is recreated.
        intent.setAction(null);
        if (look == null) {
            Toast.makeText(this, "That link isn't a Resona look, or it got cut off.", Toast.LENGTH_LONG).show();
            return;
        }
        mainHandler.post(() -> LookShare.offer(this, look, () -> looksUi.refresh()));
    }

    /**
     * Records a few seconds of the wallpaper as it reacts to whatever is
     * playing, into a video (no sound) the user can keep and share.
     */
    private void recordClip() {
        if (clip != null) {
            return;
        }
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int[] size = ClipRecorder.size(720, dm.widthPixels, dm.heightPixels);
        java.io.File out = new java.io.File(getCacheDir(), "clip.mp4");
        try {
            clip = new ClipRecorder(out, size[0], size[1], 30);
        } catch (java.io.IOException | RuntimeException e) {
            Log.w("MainActivity", "Can't record", e);
            Toast.makeText(this, "This phone can't record clips.", Toast.LENGTH_LONG).show();
            return;
        }
        // The clip drives the shared renderer; the small preview rests meanwhile.
        preview.setRunning(false);
        clipDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Recording")
                .setMessage("Play some music! " + CLIP_MS / 1000 + " s left")
                .setCancelable(false)
                .setNegativeButton("Stop", (d, w) -> endClip())
                .show();
        long start = SystemClock.uptimeMillis();
        long[] last = {start};
        ClipRecorder recorder = clip;
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (clip != recorder) {
                    return;
                }
                long now = SystemClock.uptimeMillis();
                if (now - start >= CLIP_MS) {
                    endClip();
                    return;
                }
                // 30 frames a second, whatever the screen's refresh rate.
                if (now - last[0] >= 31) {
                    float dt = Math.min(0.1f, (now - last[0]) / 1000f);
                    last[0] = now;
                    analyzer.update(dt);
                    try {
                        recorder.frame((canvas, w, h) -> renderer.draw(canvas, w, h, analyzer, settings, dt));
                    } catch (RuntimeException e) {
                        Log.w("MainActivity", "Recording failed", e);
                        endClip();
                        return;
                    }
                    long left = (CLIP_MS - (now - start) + 999) / 1000;
                    clipDialog.setMessage("Play some music! " + left + " s left");
                }
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    private void endClip() {
        ClipRecorder recorder = clip;
        if (recorder == null) {
            return;
        }
        clip = null;
        if (clipDialog != null && clipDialog.isShowing()) {
            clipDialog.dismiss();
        }
        preview.setRunning(resumed);
        if (recorder.frames() < 15) {
            recorder.cancel();
            Toast.makeText(this, "The clip was too short to keep.", Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            java.io.File file = recorder.finish();
            Uri saved = file != null && android.os.Build.VERSION.SDK_INT >= 29 ? saveToMovies(file) : null;
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (file == null) {
                    Toast.makeText(this, "Couldn't record the clip.", Toast.LENGTH_LONG).show();
                } else if (saved != null) {
                    file.delete();
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Clip saved")
                            .setMessage("It's in your videos (Movies/Resona).")
                            .setPositiveButton("Share", (d, w) -> {
                                Intent send = new Intent(Intent.ACTION_SEND).setType("video/mp4")
                                        .putExtra(Intent.EXTRA_STREAM, saved)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(send, "Share clip"));
                            })
                            .setNegativeButton("Done", null)
                            .show();
                } else {
                    // Older Android: let the user pick where it goes.
                    pendingClip = file;
                    Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("video/mp4")
                            .putExtra(Intent.EXTRA_TITLE, "Resona clip.mp4");
                    startActivityForResult(pick, REQUEST_SAVE_CLIP);
                }
            });
        });
    }

    /** Copies the clip into the phone's videos (Movies/Resona); Android 10+. */
    @android.annotation.TargetApi(29)
    private Uri saveToMovies(java.io.File file) {
        android.content.ContentResolver resolver = getContentResolver();
        android.content.ContentValues values = new android.content.ContentValues();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "Resona_" + stamp + ".mp4");
        values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_MOVIES + "/Resona");
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(android.provider.MediaStore.Video.Media.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) {
            return null;
        }
        if (!copyTo(file, uri)) {
            resolver.delete(uri, null, null);
            return null;
        }
        values.clear();
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    private boolean copyTo(java.io.File file, Uri target) {
        try (java.io.InputStream in = new java.io.FileInputStream(file);
             java.io.OutputStream out = getContentResolver().openOutputStream(target)) {
            if (out == null) {
                return false;
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (java.io.IOException e) {
            Log.w("MainActivity", "Couldn't save the clip", e);
            return false;
        }
    }

    /** Once the tour is over, ask for audio access if it's still needed. */
    private void afterTour() {
        if (!isDestroyed() && !AudioController.hasPermission(this)) {
            requestAudioPermission();
        }
    }

    private void updateApplyState() {
        noteHistory();
        boolean active = isOurWallpaperActive();
        boolean pending = !settings.sameAs(WallpaperSettings.load(this));
        if (active && pending) {
            setWallpaper.setText(R.string.apply_changes);
        } else if (isOnHomeScreen() && isOnLockScreen()) {
            // Still opens the wallpaper screen when tapped, e.g. to change which screens.
            setWallpaper.setText(R.string.applied);
        } else {
            // Not set, or only on one screen: the wallpaper screen lets the user add the other.
            setWallpaper.setText(R.string.set_wallpaper);
        }
        applyStatus.setVisibility(active && pending ? View.VISIBLE : View.GONE);
    }

    /** Throws away the draft and goes back to what the wallpaper is showing. */
    private void discardChanges() {
        executor.execute(() -> {
            WallpaperSettings live = WallpaperSettings.load(this);
            ImageStore.copy(ImageStore.live(this), ImageStore.draft(this));
            live.saveDraft(this);
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                settings = live;
                applyAudioSettings();
                preview.setSettings(settings);
                buildControls();
                loadPreviewImage();
                updateApplyState();
            });
        });
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
