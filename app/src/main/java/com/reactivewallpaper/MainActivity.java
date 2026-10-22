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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_IMAGE = 1;
    private static final int REQUEST_AUDIO = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WallpaperSettings settings;
    private WallpaperRenderer renderer;
    private AudioAnalyzer analyzer;
    private AudioController audio;
    private PreviewView preview;
    private TextView permissionNote;
    private View hueRow;
    private View circleRow;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = WallpaperSettings.load(this);
        analyzer = new AudioAnalyzer(settings.barCount);
        analyzer.setSensitivity(settings.sensitivity);
        audio = new AudioController(this, analyzer);
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

        bindRadio(R.id.style_group, new int[] {R.id.style_bars, R.id.style_circle, R.id.style_wave, R.id.style_none},
                new int[] {WallpaperSettings.STYLE_BARS, WallpaperSettings.STYLE_CIRCLE,
                        WallpaperSettings.STYLE_WAVE, WallpaperSettings.STYLE_NONE},
                settings.style, value -> {
                    settings.style = value;
                    updateCircleVisibility();
                });
        bindRadio(R.id.color_group, new int[] {R.id.color_auto, R.id.color_rainbow, R.id.color_custom},
                new int[] {WallpaperSettings.COLOR_AUTO, WallpaperSettings.COLOR_RAINBOW,
                        WallpaperSettings.COLOR_CUSTOM},
                settings.colorMode, value -> {
                    settings.colorMode = value;
                    updateHueVisibility();
                });
        bindRadio(R.id.source_group, new int[] {R.id.source_system, R.id.source_mic},
                new int[] {WallpaperSettings.SOURCE_SYSTEM, WallpaperSettings.SOURCE_MIC},
                settings.source, value -> {
                    settings.source = value;
                    if (resumed) {
                        audio.start(value);
                    }
                });

        bindSwitch(R.id.effect_pulse, settings.pulse, checked -> settings.pulse = checked);
        bindSwitch(R.id.effect_flash, settings.flash, checked -> settings.flash = checked);
        bindSwitch(R.id.effect_shake, settings.shake, checked -> settings.shake = checked);
        bindSwitch(R.id.living_glow, settings.glow, checked -> settings.glow = checked);
        bindSwitch(R.id.living_ambient, settings.ambient, checked -> settings.ambient = checked);
        bindSeek(R.id.glow_strength, (settings.glowStrength - 0.25f) / 1.75f,
                p -> settings.glowStrength = 0.25f + p * 1.75f);

        // SeekBars run 0..100 and map onto each setting's range.
        bindSeek(R.id.sensitivity, (settings.sensitivity - 0.25f) / 2.75f, p -> {
            settings.sensitivity = 0.25f + p * 2.75f;
            analyzer.setSensitivity(settings.sensitivity);
        });
        bindSeek(R.id.intensity, (settings.intensity - 0.25f) / 1.75f,
                p -> settings.intensity = 0.25f + p * 1.75f);
        bindSeek(R.id.bar_count,
                (settings.barCount - WallpaperSettings.MIN_BARS)
                        / (float) (WallpaperSettings.MAX_BARS - WallpaperSettings.MIN_BARS),
                p -> {
                    settings.barCount = Math.round(WallpaperSettings.MIN_BARS
                            + p * (WallpaperSettings.MAX_BARS - WallpaperSettings.MIN_BARS));
                    analyzer.setBandCount(settings.barCount);
                });
        bindSeek(R.id.hue, settings.hue / 360f, p -> settings.hue = p * 360f);
        bindSeek(R.id.circle_y, (settings.circleY - 0.2f) / 0.6f, p -> settings.circleY = 0.2f + p * 0.6f);
        bindSeek(R.id.circle_size, (settings.circleSize - 0.15f) / 0.3f,
                p -> settings.circleSize = 0.15f + p * 0.3f);

        hueRow = findViewById(R.id.hue_row);
        updateHueVisibility();
        circleRow = findViewById(R.id.circle_row);
        updateCircleVisibility();

        if (!AudioController.hasPermission(this)) {
            requestAudioPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updatePermissionNote();
        audio.start(settings.source);
        preview.setRunning(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        preview.setRunning(false);
        audio.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        renderer.release();
    }

    private void sizePreviewToScreen() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = Math.min(dm.widthPixels, dm.heightPixels);
        int screenH = Math.max(dm.widthPixels, dm.heightPixels);
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        lp.height = (int) (dm.heightPixels * 0.5f);
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
            if (resumed) {
                audio.stop();
                audio.start(settings.source);
            }
        }
    }

    private void updatePermissionNote() {
        permissionNote.setVisibility(AudioController.hasPermission(this) ? View.GONE : View.VISIBLE);
    }

    private void updateHueVisibility() {
        hueRow.setVisibility(settings.colorMode == WallpaperSettings.COLOR_CUSTOM ? View.VISIBLE : View.GONE);
    }

    private void updateCircleVisibility() {
        circleRow.setVisibility(settings.style == WallpaperSettings.STYLE_CIRCLE ? View.VISIBLE : View.GONE);
    }

    private void changed() {
        settings.save(this);
        preview.setSettings(settings);
    }

    private interface IntSetter {
        void set(int value);
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private interface FloatSetter {
        void set(float value);
    }

    private void bindRadio(int groupId, int[] buttonIds, int[] values, int current, IntSetter setter) {
        RadioGroup group = findViewById(groupId);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                group.check(buttonIds[i]);
            }
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            for (int i = 0; i < buttonIds.length; i++) {
                if (buttonIds[i] == checkedId) {
                    setter.set(values[i]);
                    changed();
                }
            }
        });
    }

    private void bindSwitch(int id, boolean current, BoolSetter setter) {
        CompoundButton button = findViewById(id);
        button.setChecked(current);
        button.setOnCheckedChangeListener((b, checked) -> {
            setter.set(checked);
            changed();
        });
    }

    private void bindSeek(int id, float fraction, FloatSetter setter) {
        SeekBar bar = findViewById(id);
        bar.setMax(100);
        bar.setProgress(Math.round(Math.max(0f, Math.min(1f, fraction)) * 100));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    setter.set(progress / 100f);
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
    }
}
