package com.reactivewallpaper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_IMAGE = 1;
    private static final int REQUEST_AUDIO = 2;
    private static final int SEEK_STEPS = 1000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WallpaperSettings settings;
    private WallpaperRenderer renderer;
    private AudioAnalyzer analyzer;
    private AudioController audio;
    private DemoAudioInput demo;
    private OutputRoute route;
    private TextView routeLabel;
    private boolean demoOn;
    private PreviewView preview;
    private TextView permissionNote;
    private LinearLayout controls;
    private View circleOptions;
    private View hueOption;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = WallpaperSettings.load(this);
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
        sizePreviewToScreen();
        loadPreviewImage();

        permissionNote = findViewById(R.id.permission_note);
        permissionNote.setOnClickListener(v -> requestAudioPermission());
        findViewById(R.id.choose_image).setOnClickListener(v -> chooseImage());
        findViewById(R.id.set_wallpaper).setOnClickListener(v -> applyWallpaper());

        controls = findViewById(R.id.controls);
        buildControls();

        if (!AudioController.hasPermission(this)) {
            requestAudioPermission();
        }
    }

    private void buildControls() {
        controls.removeAllViews();
        WallpaperSettings s = settings;

        LinearLayout glow = section("Glow (the image's own colors)", true);
        slider(glow, "Bass glow", 0f, 3f, () -> s.bassGlow, v -> s.bassGlow = v, MainActivity::percent);
        slider(glow, "Treble glow", 0f, 3f, () -> s.highGlow, v -> s.highGlow = v, MainActivity::percent);
        slider(glow, "Beat flare", 0f, 3f, () -> s.beatFlare, v -> s.beatFlare = v, MainActivity::percent);
        slider(glow, "Halo size", 0f, 3f, () -> s.bloom, v -> s.bloom = v, MainActivity::percent);
        slider(glow, "Coverage (how much of the image glows)", 0f, 1f,
                () -> s.glowCoverage, v -> s.glowCoverage = v, MainActivity::percent);
        toggle(glow, "Swap which color follows the bass",
                () -> s.swapGlowColors, v -> s.swapGlowColors = v);

        LinearLayout idle = section("Idle animation (moves without sound)", true);
        slider(idle, "Breathing glow", 0f, 2f, () -> s.breathe, v -> s.breathe = v, MainActivity::percent);
        slider(idle, "Breathing speed", 0.25f, 3f, () -> s.breatheSpeed, v -> s.breatheSpeed = v, MainActivity::speed);
        slider(idle, "Sway", 0f, 2f, () -> s.sway, v -> s.sway = v, MainActivity::percent);
        slider(idle, "Sway speed", 0.25f, 3f, () -> s.swaySpeed, v -> s.swaySpeed = v, MainActivity::speed);

        LinearLayout sparkles = section("Sparkles", false);
        slider(sparkles, "Amount", 0f, 1f, () -> s.sparkleAmount, v -> s.sparkleAmount = v,
                v -> Math.round(v * WallpaperSettings.MAX_SPARKLES) == 0
                        ? "Off" : String.valueOf(Math.round(v * WallpaperSettings.MAX_SPARKLES)));
        slider(sparkles, "Size", 0.25f, 3f, () -> s.sparkleSize, v -> s.sparkleSize = v, MainActivity::percent);
        slider(sparkles, "Speed", 0.25f, 3f, () -> s.sparkleSpeed, v -> s.sparkleSpeed = v, MainActivity::speed);
        slider(sparkles, "Brightness", 0f, 2f, () -> s.sparkleBrightness, v -> s.sparkleBrightness = v,
                MainActivity::percent);
        slider(sparkles, "Music reaction", 0f, 2f, () -> s.sparkleReact, v -> s.sparkleReact = v,
                MainActivity::percent);

        LinearLayout beat = section("Beat effects", false);
        slider(beat, "Pulse (zoom with the bass)", 0f, 2f, () -> s.pulse, v -> s.pulse = v, MainActivity::percent);
        slider(beat, "Flash", 0f, 2f, () -> s.flash, v -> s.flash = v, MainActivity::percent);
        slider(beat, "Shake", 0f, 2f, () -> s.shake, v -> s.shake = v, MainActivity::percent);

        LinearLayout viz = section("Visualizer overlay", false);
        choice(viz, new String[] {"None", "Bars", "Circle", "Wave"},
                new int[] {WallpaperSettings.STYLE_NONE, WallpaperSettings.STYLE_BARS,
                        WallpaperSettings.STYLE_CIRCLE, WallpaperSettings.STYLE_WAVE},
                s.style, v -> {
                    s.style = v;
                    updateVisibility();
                });
        slider(viz, "Height", 0f, 2f, () -> s.vizHeight, v -> s.vizHeight = v, MainActivity::percent);
        slider(viz, "Opacity", 0f, 1f, () -> s.vizOpacity, v -> s.vizOpacity = v, MainActivity::percent);
        slider(viz, "Bar count", WallpaperSettings.MIN_BARS, WallpaperSettings.MAX_BARS,
                () -> s.barCount, v -> {
                    s.barCount = Math.round(v);
                    applyAudioSettings();
                }, v -> String.valueOf(Math.round(v)));
        LinearLayout circle = group(viz);
        slider(circle, "Ring position (up / down)", 0.2f, 0.8f, () -> s.circleY, v -> s.circleY = v,
                MainActivity::percent);
        slider(circle, "Ring size", 0.15f, 0.45f, () -> s.circleSize, v -> s.circleSize = v, MainActivity::percent);
        circleOptions = circle;
        label(viz, "Color");
        choice(viz, new String[] {"From image", "Rainbow", "Custom"},
                new int[] {WallpaperSettings.COLOR_AUTO, WallpaperSettings.COLOR_RAINBOW,
                        WallpaperSettings.COLOR_CUSTOM},
                s.colorMode, v -> {
                    s.colorMode = v;
                    updateVisibility();
                });
        LinearLayout hue = group(viz);
        slider(hue, "Hue", 0f, 360f, () -> s.hue, v -> s.hue = v, v -> Math.round(v) + "°");
        hueOption = hue;

        LinearLayout sound = section("Audio", false);
        slider(sound, "Sensitivity", 0.25f, 3f, () -> s.sensitivity, v -> {
            s.sensitivity = v;
            applyAudioSettings();
        }, MainActivity::percent);
        slider(sound, "Smoothing (snappy ↔ smooth)", 0f, 1f, () -> s.smoothing, v -> {
            s.smoothing = v;
            applyAudioSettings();
        }, v -> Math.round(v * 100) + "%");
        slider(sound, "Beat sensitivity", 0f, 1f, () -> s.beatSensitivity, v -> {
            s.beatSensitivity = v;
            applyAudioSettings();
        }, v -> Math.round(v * 100) + "%");
        label(sound, "Sync: if the wallpaper reacts before you hear the beat, add delay. "
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
        slider(sound, "Delay – phone speaker / wired", 0f, 600f, () -> s.syncSpeakerMs, v -> {
            s.syncSpeakerMs = Math.round(v);
            applyAudioSettings();
        }, v -> Math.round(v) + " ms");
        slider(sound, "Delay – Bluetooth", 0f, 600f, () -> s.syncBluetoothMs, v -> {
            s.syncBluetoothMs = Math.round(v);
            applyAudioSettings();
        }, v -> Math.round(v) + " ms");
        label(sound, "React to");
        choice(sound, new String[] {"Phone audio", "Microphone"},
                new int[] {WallpaperSettings.SOURCE_SYSTEM, WallpaperSettings.SOURCE_MIC},
                s.source, v -> {
                    s.source = v;
                    applyAudioSettings();
                    restartAudio();
                });
        toggle(sound, "Demo beat in preview (tune without music)", () -> demoOn, v -> {
            demoOn = v;
            applyAudioSettings();
            restartAudio();
        });

        Button reset = new Button(this);
        reset.setText("Reset all effects to defaults");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        controls.addView(reset, lp);
        reset.setOnClickListener(v -> {
            settings = settings.resetEffects();
            settings.save(this);
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
        applyAudioSettings();
        updatePermissionNote();
        restartAudio();
        preview.setRunning(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        preview.setRunning(false);
        audio.stop();
        demo.stop();
        route.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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

    // ---- Sync calibration --------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
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
        boolean bluetooth = route.isBluetooth();
        String output = bluetooth ? "Bluetooth" : "phone speaker / wired";

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        TextView text = new TextView(this);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(getColor(R.color.text_primary));
        text.setText("Measures the delay for " + output + ".\n\n"
                + "Your music will pause and you'll hear " + BeatCalibration.TOTAL_CLICKS
                + " clicks. Tap the big button exactly in time with each click you hear. "
                + "The first " + BeatCalibration.WARMUP_CLICKS + " are practice.\n\n"
                + "Turn the volume up so the clicks are easy to hear.");
        box.addView(text);
        TextView status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTextColor(getColor(R.color.accent));
        status.setPadding(0, dp(12), 0, dp(12));
        box.addView(status);
        Button pad = new Button(this);
        pad.setText("TAP");
        pad.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        pad.setEnabled(false);
        box.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Calibrate sync")
                .setView(box)
                .setPositiveButton("Start", null)
                .setNegativeButton("Cancel", null)
                .create();
        BeatCalibration[] session = new BeatCalibration[1];
        int[] measured = {-1};

        dialog.setOnShowListener(d -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                if (measured[0] >= 0) {
                    if (bluetooth) {
                        settings.syncBluetoothMs = measured[0];
                    } else {
                        settings.syncSpeakerMs = measured[0];
                    }
                    settings.save(this);
                    applyAudioSettings();
                    buildControls();
                    dialog.dismiss();
                    return;
                }
                // Free the shared audio capture while calibrating.
                audio.stop();
                demo.stop();
                session[0] = new BeatCalibration(this, new BeatCalibration.Callback() {
                    @Override
                    public void onProgress(int clicksPlayed, int taps) {
                        status.setText("Click " + clicksPlayed + " of " + BeatCalibration.TOTAL_CLICKS
                                + "   ·   taps: " + taps);
                    }

                    @Override
                    public void onFinished(BeatCalibration.Result result) {
                        session[0] = null;
                        restartAudio();
                        pad.setEnabled(false);
                        if (result.ok) {
                            measured[0] = result.delayMs;
                            status.setText("Measured delay: " + result.delayMs + " ms. " + result.message);
                            positive.setText("Use " + result.delayMs + " ms");
                        } else {
                            status.setText(result.message);
                            positive.setText("Try again");
                        }
                        positive.setEnabled(true);
                    }
                });
                if (!session[0].start()) {
                    session[0] = null;
                    restartAudio();
                    status.setText("Phone audio can't be captured on this device. Try microphone mode instead.");
                    return;
                }
                positive.setEnabled(false);
                pad.setEnabled(true);
            });
            // Time taps from the touch itself (not the click callback) for accuracy.
            pad.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && session[0] != null) {
                    session[0].tap(event.getEventTime());
                    v.setPressed(true);
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                }
                return true;
            });
        });
        dialog.setOnDismissListener(d -> {
            if (session[0] != null) {
                session[0].cancel();
                session[0] = null;
                restartAudio();
            }
        });
        dialog.show();
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

    private void sizePreviewToScreen() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = Math.min(dm.widthPixels, dm.heightPixels);
        int screenH = Math.max(dm.widthPixels, dm.heightPixels);
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        lp.height = (int) (dm.heightPixels * 0.38f);
        lp.width = (int) (lp.height * screenW / (float) screenH);
        preview.setLayoutParams(lp);
    }

    private void loadPreviewImage() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels / 2;
        int h = dm.heightPixels / 2;
        executor.execute(() -> {
            Bitmap bitmap = ImageStore.load(this, w, h);
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    return;
                }
                renderer.setImage(bitmap);
                preview.invalidate();
            });
        });
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
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
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        executor.execute(() -> {
            boolean ok = ImageStore.importImage(this, uri);
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (!ok) {
                    Toast.makeText(this, R.string.image_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                settings.imageVersion = System.currentTimeMillis();
                settings.save(this);
                loadPreviewImage();
            });
        });
    }

    private void applyWallpaper() {
        settings.save(this);
        ComponentName component = new ComponentName(this, ReactiveWallpaperService.class);
        WallpaperInfo current = WallpaperManager.getInstance(this).getWallpaperInfo();
        if (current != null && component.equals(current.getComponent())) {
            Toast.makeText(this, R.string.already_active, Toast.LENGTH_LONG).show();
            return;
        }
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
        circleOptions.setVisibility(settings.style == WallpaperSettings.STYLE_CIRCLE ? View.VISIBLE : View.GONE);
        hueOption.setVisibility(settings.colorMode == WallpaperSettings.COLOR_CUSTOM ? View.VISIBLE : View.GONE);
    }

    private void changed() {
        settings.save(this);
        preview.setSettings(settings);
    }

    // ---- Control builders -------------------------------------------------

    private interface FloatGetter {
        float get();
    }

    private interface FloatSetter {
        void set(float value);
    }

    private interface BoolGetter {
        boolean get();
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private interface IntSetter {
        void set(int value);
    }

    private interface Formatter {
        String format(float value);
    }

    private static String percent(float v) {
        int p = Math.round(v * 100);
        return p == 0 ? "Off" : p + "%";
    }

    private static String speed(float v) {
        return String.format(Locale.US, "%.2f×", v);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    /** A collapsible section; returns the container its controls go in. */
    private LinearLayout section(String title, boolean expanded) {
        TextView header = new TextView(this);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setLetterSpacing(0.06f);
        header.setAllCaps(true);
        header.setTextColor(getColor(R.color.accent));
        header.setMinHeight(dp(48));
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        controls.addView(header, lp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(8));
        controls.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable refresh = () -> header.setText((body.getVisibility() == View.VISIBLE ? "▾  " : "▸  ") + title);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        refresh.run();
        header.setOnClickListener(v -> {
            body.setVisibility(body.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            refresh.run();
        });
        return body;
    }

    /** A plain sub-container, used for controls that show or hide together. */
    private LinearLayout group(LinearLayout parent) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        parent.addView(g, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return g;
    }

    private void label(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(label, lp);
    }

    private void slider(LinearLayout parent, String title, float min, float max,
                        FloatGetter getter, FloatSetter setter, Formatter formatter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTextColor(getColor(R.color.text_primary));
        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTextColor(getColor(R.color.text_secondary));
        value.setText(formatter.format(getter.get()));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        parent.addView(row, rowLp);

        SeekBar bar = new SeekBar(this);
        bar.setMax(SEEK_STEPS);
        float fraction = (getter.get() - min) / (max - min);
        bar.setProgress(Math.round(Math.max(0f, Math.min(1f, fraction)) * SEEK_STEPS));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float v = min + (max - min) * progress / SEEK_STEPS;
                    setter.set(v);
                    value.setText(formatter.format(v));
                    preview.setSettings(settings);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                changed();
            }
        });
        parent.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    private void toggle(LinearLayout parent, String title, BoolGetter getter, BoolSetter setter) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        toggle.setTextColor(getColor(R.color.text_primary));
        toggle.setChecked(getter.get());
        toggle.setOnCheckedChangeListener((b, checked) -> {
            setter.set(checked);
            changed();
        });
        parent.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private void choice(LinearLayout parent, String[] labels, int[] values, int current, IntSetter setter) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            RadioButton button = new RadioButton(this);
            ids[i] = View.generateViewId();
            button.setId(ids[i]);
            button.setText(labels[i]);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            button.setTextColor(getColor(R.color.text_primary));
            button.setMinHeight(dp(48));
            group.addView(button, new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (values[i] == current) {
                group.check(ids[i]);
            }
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checkedId) {
                    setter.set(values[i]);
                    changed();
                }
            }
        });
        parent.addView(group, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
