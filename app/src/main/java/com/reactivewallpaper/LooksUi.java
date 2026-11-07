package com.reactivewallpaper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;

/**
 * The editor's Looks section: the row of saved looks, saving, updating and
 * deleting them, the lock screen look, look rotation, and .resona export/import.
 */
final class LooksUi {
    static final int REQUEST_EXPORT = 4;
    static final int REQUEST_IMPORT = 5;

    interface Host {
        /** The editor's current (draft) settings. */
        WallpaperSettings settings();

        /** A look was loaded into the editor; {@code newPicture} if it brought its own image. */
        void onLookLoaded(WallpaperSettings next, boolean newPicture);
    }

    private final Activity activity;
    private final ExecutorService executor;
    private final Handler main;
    private final Host host;
    private Controls ui;
    private LinearLayout looksRow;
    private Button lockLookButton;
    private Button rotationButton;
    private Button timeOfDayButton;
    /** Looks waiting for the user to pick where to save the export. */
    private java.util.List<PresetStore.Preset> pendingExport;

    LooksUi(Activity activity, ExecutorService executor, Handler main, Host host) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.host = host;
    }

    /** Fills the Looks section. */
    void build(Controls controls, LinearLayout looks) {
        ui = controls;
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        looksRow = new LinearLayout(activity);
        looksRow.setOrientation(LinearLayout.HORIZONTAL);
        looksRow.setPadding(0, dp(6), 0, dp(4));
        scroll.addView(looksRow);
        looks.addView(scroll);
        ui.hint(looks, "Tap to load a look into the editor. Long-press your own looks to update, rename, export or delete them.");
        Button saveLook = new Button(activity);
        saveLook.setText("Save current look");
        saveLook.setOnClickListener(v -> saveLookDialog());
        looks.addView(saveLook, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lockLookButton = new Button(activity);
        lockLookButton.setOnClickListener(v -> chooseLockLook());
        looks.addView(lockLookButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rotationButton = new Button(activity);
        rotationButton.setOnClickListener(v -> rotationDialog());
        looks.addView(rotationButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        timeOfDayButton = new Button(activity);
        timeOfDayButton.setOnClickListener(v -> timeOfDayDialog());
        looks.addView(timeOfDayButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout shareRow = new LinearLayout(activity);
        Button importLooks = new Button(activity);
        importLooks.setText("Import looks");
        importLooks.setOnClickListener(v -> startImport());
        Button exportLooks = new Button(activity);
        exportLooks.setText("Export all");
        exportLooks.setOnClickListener(v -> {
            java.util.List<PresetStore.Preset> mine = new java.util.ArrayList<>();
            for (PresetStore.Preset p : PresetStore.all(activity)) {
                if (!p.builtIn) {
                    mine.add(p);
                }
            }
            if (mine.isEmpty()) {
                Toast.makeText(activity, "Save a look first.", Toast.LENGTH_SHORT).show();
            } else {
                startExport(mine, "Resona looks");
            }
        });
        shareRow.addView(importLooks, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        shareRow.addView(exportLooks, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        looks.addView(shareRow);
        Button shareCurrent = new Button(activity);
        shareCurrent.setText("Share this look (QR code or link)");
        shareCurrent.setOnClickListener(v -> LookShare.show(activity, "My Resona look", host.settings()));
        looks.addView(shareCurrent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout receiveRow = new LinearLayout(activity);
        Button scan = new Button(activity);
        scan.setText("Scan a look");
        scan.setOnClickListener(v -> LookShare.scan(activity, look -> LookShare.offer(activity, look, this::refresh)));
        Button paste = new Button(activity);
        paste.setText("Paste a look");
        paste.setOnClickListener(v -> LookShare.paste(activity, look -> LookShare.offer(activity, look, this::refresh)));
        receiveRow.addView(scan, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        receiveRow.addView(paste, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        looks.addView(receiveRow);
        refresh();
    }

    /** Handles the file pickers this section opened; returns false for anyone else's. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        boolean picked = resultCode == Activity.RESULT_OK && data != null && data.getData() != null;
        if (requestCode == REQUEST_EXPORT) {
            if (picked) {
                finishExport(data.getData());
            } else {
                pendingExport = null;
            }
            return true;
        }
        if (requestCode == REQUEST_IMPORT) {
            if (picked) {
                finishImport(data.getData());
            }
            return true;
        }
        return false;
    }

    /** Redraws the looks row and the lock screen and rotation buttons. */
    void refresh() {
        looksRow.removeAllViews();
        for (PresetStore.Preset preset : PresetStore.all(activity)) {
            looksRow.addView(lookItem(preset));
        }
        PresetStore.Preset lock = PresetStore.find(activity, WallpaperSettings.lockLookId(activity));
        lockLookButton.setText("Lock screen: " + (lock == null ? "same as home" : lock.name));
        rotationButton.setText("Rotate looks: " + rotationSummary());
        int set = 0;
        for (String id : WallpaperSettings.timeOfDayIds(activity)) {
            if (!id.isEmpty() && PresetStore.find(activity, id) != null) {
                set++;
            }
        }
        timeOfDayButton.setText("Time of day: " + (set == 0 ? "off" : set + (set == 1 ? " look" : " looks")));
    }

    /**
     * Picks a saved look for each part of the day. Parts left empty keep the
     * look of the part before them; with none set, the editor's look shows.
     */
    private void timeOfDayDialog() {
        java.util.List<PresetStore.Preset> all = PresetStore.all(activity);
        if (all.isEmpty()) {
            Toast.makeText(activity, "Save a look first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] ids = WallpaperSettings.timeOfDayIds(activity);
        String[] names = new String[all.size() + 1];
        names[0] = "Keep the earlier one";
        for (int i = 0; i < all.size(); i++) {
            names[i + 1] = all.get(i).name;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        ui.hint(box, "The home screen switches looks through the day. Look rotation, when on, takes "
                + "priority.");
        for (int part = 0; part < 4; part++) {
            int index = part;
            ui.label(box, TimeOfDay.NAMES[part]);
            Button pick = new Button(activity);
            Runnable label = () -> {
                PresetStore.Preset p = PresetStore.find(activity, ids[index]);
                pick.setText(p == null ? names[0] : p.name);
            };
            label.run();
            pick.setOnClickListener(v -> {
                int checked = 0;
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).id.equals(ids[index])) {
                        checked = i + 1;
                    }
                }
                new AlertDialog.Builder(activity)
                        .setTitle(TimeOfDay.NAMES[index])
                        .setSingleChoiceItems(names, checked, (d, which) -> {
                            ids[index] = which == 0 ? "" : all.get(which - 1).id;
                            label.run();
                            d.dismiss();
                        })
                        .show();
            });
            box.addView(pick, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(box);
        new AlertDialog.Builder(activity)
                .setTitle("Looks by time of day")
                .setView(scroll)
                .setPositiveButton("Save", (d, which) -> {
                    WallpaperSettings.setTimeOfDay(activity, ids);
                    refresh();
                })
                .setNeutralButton("Turn off", (d, which) -> {
                    WallpaperSettings.setTimeOfDay(activity, new String[] {"", "", "", ""});
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String rotationSummary() {
        int mode = WallpaperSettings.rotationMode(activity);
        int count = 0;
        for (String id : WallpaperSettings.rotationIds(activity)) {
            if (PresetStore.find(activity, id) != null) {
                count++;
            }
        }
        if (mode == WallpaperSettings.ROTATE_OFF || count < 2) {
            return "off";
        }
        String when = mode == WallpaperSettings.ROTATE_MINUTES
                ? "every " + WallpaperSettings.rotationMinutes(activity) + " min"
                : mode == WallpaperSettings.ROTATE_SONG ? "every song" : "on double-tap";
        return count + " looks, " + when;
    }

    /** Picks which saved looks the home screen cycles through, and when it moves on. */
    private void rotationDialog() {
        java.util.List<PresetStore.Preset> all = PresetStore.all(activity);
        java.util.List<String> chosen = new java.util.ArrayList<>(WallpaperSettings.rotationIds(activity));
        int[] mode = {WallpaperSettings.rotationMode(activity)};
        int[] minutes = {WallpaperSettings.rotationMinutes(activity)};

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        ui.label(box, "Switch looks");
        TextView minutesLabel = new TextView(activity);
        SeekBar minutesBar = new SeekBar(activity);
        RadioGroup modes = new RadioGroup(activity);
        String[] modeNames = {"Off", "Every few minutes", "Every new song", "Double-tap the home screen"};
        int[] modeValues = {WallpaperSettings.ROTATE_OFF, WallpaperSettings.ROTATE_MINUTES,
                WallpaperSettings.ROTATE_SONG, WallpaperSettings.ROTATE_DOUBLE_TAP};
        for (int i = 0; i < modeNames.length; i++) {
            RadioButton b = new RadioButton(activity);
            b.setId(View.generateViewId());
            b.setText(modeNames[i]);
            b.setTextColor(activity.getColor(R.color.text_primary));
            int value = modeValues[i];
            b.setChecked(value == mode[0]);
            b.setOnClickListener(v -> {
                mode[0] = value;
                minutesBar.setEnabled(value == WallpaperSettings.ROTATE_MINUTES);
            });
            modes.addView(b);
        }
        box.addView(modes);
        minutesLabel.setTextColor(activity.getColor(R.color.text_secondary));
        minutesLabel.setText("Every " + minutes[0] + " min");
        box.addView(minutesLabel);
        minutesBar.setMax(119);
        minutesBar.setProgress(minutes[0] - 1);
        minutesBar.setEnabled(mode[0] == WallpaperSettings.ROTATE_MINUTES);
        minutesBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                minutes[0] = progress + 1;
                minutesLabel.setText("Every " + minutes[0] + " min");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(minutesBar);
        ui.label(box, "Looks to rotate through");
        for (PresetStore.Preset preset : all) {
            CheckBox check = new CheckBox(activity);
            check.setText(preset.name);
            check.setTextColor(activity.getColor(R.color.text_primary));
            check.setChecked(chosen.contains(preset.id));
            check.setOnCheckedChangeListener((b, on) -> {
                chosen.remove(preset.id);
                if (on) {
                    chosen.add(preset.id);
                }
            });
            box.addView(check);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(box);

        new AlertDialog.Builder(activity)
                .setTitle("Rotate looks")
                .setView(scroll)
                .setPositiveButton("Save", (d, which) -> {
                    // Keep the list in the order the looks appear.
                    java.util.List<String> ordered = new java.util.ArrayList<>();
                    for (PresetStore.Preset p : all) {
                        if (chosen.contains(p.id)) {
                            ordered.add(p.id);
                        }
                    }
                    WallpaperSettings.setRotation(activity, ordered, mode[0], minutes[0]);
                    refresh();
                    if (mode[0] != WallpaperSettings.ROTATE_OFF && ordered.size() < 2) {
                        Toast.makeText(activity, "Pick at least two looks to rotate.", Toast.LENGTH_LONG).show();
                    } else if (mode[0] == WallpaperSettings.ROTATE_SONG && !AlbumArtSource.hasAccess(activity)) {
                        AlbumArtSource.askForAccess(activity);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseLockLook() {
        java.util.List<PresetStore.Preset> all = PresetStore.all(activity);
        String[] names = new String[all.size() + 1];
        names[0] = "Same as home screen";
        for (int i = 0; i < all.size(); i++) {
            names[i + 1] = all.get(i).name;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Lock screen look")
                .setItems(names, (d, which) -> {
                    // Applies straight away: it picks between saved looks, it doesn't edit one.
                    WallpaperSettings.setLockLookId(activity, which == 0 ? "" : all.get(which - 1).id);
                    refresh();
                    Toast.makeText(activity, which == 0 ? "Lock screen matches the home screen"
                            : "Lock screen will show “" + names[which] + "”", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void startExport(java.util.List<PresetStore.Preset> presets, String fileName) {
        pendingExport = presets;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, fileName + ".resona");
        try {
            activity.startActivityForResult(intent, REQUEST_EXPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No app available to save files.", Toast.LENGTH_LONG).show();
        }
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, REQUEST_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No app available to open files.", Toast.LENGTH_LONG).show();
        }
    }

    private void finishExport(Uri uri) {
        java.util.List<PresetStore.Preset> presets = pendingExport;
        pendingExport = null;
        if (presets == null) {
            return;
        }
        executor.execute(() -> {
            String message;
            try (java.io.OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new java.io.IOException("Could not open the file");
                }
                PresetStore.export(activity, presets, out);
                message = presets.size() == 1 ? "Exported “" + presets.get(0).name + "”"
                        : "Exported " + presets.size() + " looks";
            } catch (java.io.IOException | SecurityException e) {
                message = "Export failed: " + e.getMessage();
            }
            String text = message;
            main.post(() -> Toast.makeText(activity, text, Toast.LENGTH_LONG).show());
        });
    }

    private void finishImport(Uri uri) {
        executor.execute(() -> {
            String message;
            try (java.io.InputStream in = activity.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new java.io.IOException("Could not open the file");
                }
                int count = PresetStore.importFrom(activity, in);
                message = count == 0 ? "No looks found in that file."
                        : count == 1 ? "Imported 1 look" : "Imported " + count + " looks";
            } catch (java.io.IOException | SecurityException e) {
                message = "Couldn't import: " + e.getMessage();
            }
            String text = message;
            main.post(() -> {
                if (!activity.isDestroyed()) {
                    refresh();
                    Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private View lookItem(PresetStore.Preset preset) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0, 0, dp(12), 0);

        ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setImageResource(R.drawable.wallpaper_thumbnail);
        final float radius = dp(14);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        thumb.setClipToOutline(true);
        item.addView(thumb, new LinearLayout.LayoutParams(dp(72), dp(96)));
        if (preset.hasImage) {
            int w = dp(72);
            int h = dp(96);
            executor.execute(() -> {
                Bitmap bitmap = ImageStore.load(PresetStore.image(activity, preset), w, h);
                main.post(() -> {
                    if (bitmap != null && !activity.isDestroyed()) {
                        thumb.setImageBitmap(bitmap);
                    }
                });
            });
        }

        TextView name = new TextView(activity);
        name.setText(preset.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setTextColor(activity.getColor(R.color.text_primary));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setPadding(0, dp(4), 0, 0);
        item.addView(name, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        item.setOnClickListener(v -> applyLook(preset));
        item.setOnLongClickListener(v -> {
            lookMenu(preset);
            return true;
        });
        return item;
    }

    private void applyLook(PresetStore.Preset preset) {
        WallpaperSettings current = host.settings();
        executor.execute(() -> {
            WallpaperSettings next = PresetStore.apply(activity, preset, current);
            main.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                host.onLookLoaded(next, preset.hasImage);
                Toast.makeText(activity, "Loaded “" + preset.name + "”", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void saveLookDialog() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setHint("Name");
        name.setText("My look " + (PresetStore.all(activity).size() - 3));
        name.selectAll();
        box.addView(name);
        CheckBox includeImage = new CheckBox(activity);
        includeImage.setText("Include the current image");
        boolean hasImage = ImageStore.hasDraftImage(activity);
        includeImage.setChecked(hasImage);
        includeImage.setEnabled(hasImage);
        box.addView(includeImage);

        new AlertDialog.Builder(activity)
                .setTitle("Save current look")
                .setView(box)
                .setPositiveButton("Save", (d, which) -> {
                    String title = name.getText().toString().trim();
                    String finalTitle = title.isEmpty() ? "My look" : title;
                    WallpaperSettings current = host.settings();
                    boolean withImage = includeImage.isChecked();
                    executor.execute(() -> {
                        PresetStore.save(activity, finalTitle, current, withImage);
                        main.post(() -> {
                            if (!activity.isDestroyed()) {
                                refresh();
                                Toast.makeText(activity, "Saved “" + finalTitle + "”", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void lookMenu(PresetStore.Preset preset) {
        if (preset.builtIn) {
            Toast.makeText(activity, "Built-in looks can't be changed. Apply it, adjust, then save your own.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] actions = {"Update with current look", "Rename", "Share as QR code or link", "Export file", "Delete"};
        new AlertDialog.Builder(activity)
                .setTitle(preset.name)
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        WallpaperSettings current = host.settings();
                        executor.execute(() -> {
                            PresetStore.update(activity, preset, current);
                            main.post(() -> {
                                if (!activity.isDestroyed()) {
                                    refresh();
                                    Toast.makeText(activity, "Updated “" + preset.name + "”", Toast.LENGTH_SHORT).show();
                                }
                            });
                        });
                    } else if (which == 1) {
                        renameLookDialog(preset);
                    } else if (which == 2) {
                        LookShare.show(activity, preset.name, PresetStore.fromJson(preset.look));
                    } else if (which == 3) {
                        startExport(java.util.Collections.singletonList(preset), preset.name);
                    } else {
                        new AlertDialog.Builder(activity)
                                .setTitle("Delete “" + preset.name + "”?")
                                .setPositiveButton("Delete", (d2, w2) -> {
                                    PresetStore.delete(activity, preset);
                                    refresh();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .show();
    }

    private void renameLookDialog(PresetStore.Preset preset) {
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setText(preset.name);
        name.selectAll();
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(activity)
                .setTitle("Rename look")
                .setView(box)
                .setPositiveButton("Rename", (d, which) -> {
                    String title = name.getText().toString().trim();
                    if (!title.isEmpty()) {
                        PresetStore.rename(activity, preset, title);
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }
}
