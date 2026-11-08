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
        // Renamed, deleted or starred looks show on the looks widget too.
        LooksWidgetProvider.updateAll(activity);
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

        // When each part starts.
        int[] mode = {WallpaperSettings.timeOfDayMode(activity)};
        int[] custom = WallpaperSettings.customStarts(activity);
        double[][] where = {WallpaperSettings.sunLocation(activity)};
        TextView[] partLabels = new TextView[4];
        TextView timesNote = new TextView(activity);
        timesNote.setTextColor(activity.getColor(R.color.text_secondary));
        LinearLayout customRow = new LinearLayout(activity);
        customRow.setOrientation(LinearLayout.VERTICAL);
        Runnable showTimes = () -> {
            int[] starts = startsFor(mode[0], custom, where[0]);
            for (int i = 0; i < 4; i++) {
                if (partLabels[i] != null) {
                    partLabels[i].setText(TimeOfDay.NAMES[i] + " (from " + TimeOfDay.format(starts[i]) + ")");
                }
            }
            customRow.setVisibility(mode[0] == TimeOfDay.TIMES_CUSTOM ? View.VISIBLE : View.GONE);
            if (mode[0] == TimeOfDay.TIMES_SUN) {
                timesNote.setText(where[0] == null ? "Finding your approximate location…"
                        : "Morning starts at sunrise, evening an hour before sunset, night an hour after "
                        + "(for your approximate location, updated daily).");
                timesNote.setVisibility(View.VISIBLE);
            } else {
                timesNote.setVisibility(View.GONE);
            }
        };
        ui.label(box, "Parts of the day start");
        RadioGroup timeModes = new RadioGroup(activity);
        String[] timeNames = {"6 am, noon, 6 pm and 10 pm", "My own times", "Follow sunrise and sunset"};
        for (int i = 0; i < timeNames.length; i++) {
            RadioButton b = new RadioButton(activity);
            b.setId(View.generateViewId());
            b.setText(timeNames[i]);
            b.setTextColor(activity.getColor(R.color.text_primary));
            b.setChecked(i == mode[0]);
            int value = i;
            b.setOnClickListener(v -> {
                mode[0] = value;
                if (value == TimeOfDay.TIMES_SUN && where[0] == null) {
                    locate(location -> {
                        where[0] = location;
                        if (location == null) {
                            timesNote.setText("Couldn't get your location, so the fixed times are used. "
                                    + "Allow location for Resona, or choose your own times.");
                        } else {
                            showTimes.run();
                        }
                    });
                }
                showTimes.run();
            });
            timeModes.addView(b);
        }
        box.addView(timeModes);
        for (int i = 0; i < 4; i++) {
            int index = i;
            Button time = new Button(activity, null, android.R.attr.borderlessButtonStyle);
            time.setTextColor(activity.getColor(R.color.accent));
            Runnable timeLabel = () -> time.setText(TimeOfDay.NAMES[index] + " starts at "
                    + TimeOfDay.format(custom[index]));
            timeLabel.run();
            time.setOnClickListener(v -> new android.app.TimePickerDialog(activity, (picker, h, m) -> {
                custom[index] = h * 60 + m;
                int[] fixed = TimeOfDay.ordered(custom);
                System.arraycopy(fixed, 0, custom, 0, 4);
                timeLabel.run();
                showTimes.run();
            }, custom[index] / 60, custom[index] % 60, android.text.format.DateFormat.is24HourFormat(activity)).show());
            customRow.addView(time);
        }
        box.addView(customRow);
        box.addView(timesNote);

        for (int part = 0; part < 4; part++) {
            int index = part;
            TextView partLabel = new TextView(activity);
            partLabel.setTextColor(activity.getColor(R.color.text_primary));
            partLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            partLabel.setPadding(0, dp(10), 0, 0);
            partLabels[part] = partLabel;
            box.addView(partLabel);
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
        showTimes.run();
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(box);
        new AlertDialog.Builder(activity)
                .setTitle("Looks by time of day")
                .setView(scroll)
                .setPositiveButton("Save", (d, which) -> {
                    WallpaperSettings.setTimeOfDayTimes(activity, mode[0], custom, where[0]);
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

    private static int[] startsFor(int mode, int[] custom, double[] where) {
        if (mode == TimeOfDay.TIMES_CUSTOM) {
            return TimeOfDay.ordered(custom);
        }
        if (mode == TimeOfDay.TIMES_SUN && where != null) {
            java.util.Calendar now = java.util.Calendar.getInstance();
            int offset = java.util.TimeZone.getDefault().getOffset(now.getTimeInMillis()) / 60000;
            return TimeOfDay.sunStarts(where[0], where[1], now.get(java.util.Calendar.DAY_OF_YEAR), offset);
        }
        return TimeOfDay.FIXED.clone();
    }

    static final int REQUEST_LOCATION = 10;
    private java.util.function.Consumer<double[]> pendingLocation;

    /**
     * The phone's approximate location (city-level is plenty for sunrise), asking
     * for permission first if needed; null if it can't be had.
     */
    private void locate(java.util.function.Consumer<double[]> then) {
        if (activity.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingLocation = then;
            activity.requestPermissions(new String[] {android.Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION);
            return;
        }
        android.location.LocationManager lm =
                (android.location.LocationManager) activity.getSystemService(android.content.Context.LOCATION_SERVICE);
        android.location.Location best = null;
        if (lm != null) {
            for (String provider : lm.getProviders(true)) {
                try {
                    android.location.Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) {
                        best = l;
                    }
                } catch (SecurityException ignored) {
                    // Not allowed for this provider; try the others.
                }
            }
        }
        if (best != null) {
            then.accept(new double[] {best.getLatitude(), best.getLongitude()});
            return;
        }
        if (lm != null && android.os.Build.VERSION.SDK_INT >= 30
                && lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.getCurrentLocation(android.location.LocationManager.NETWORK_PROVIDER, null,
                        activity.getMainExecutor(), l -> then.accept(l == null ? null
                                : new double[] {l.getLatitude(), l.getLongitude()}));
                return;
            } catch (SecurityException ignored) {
                // Fall through.
            }
        }
        then.accept(null);
    }

    /** Forwarded from the activity: the answer to the location permission request. */
    void onLocationPermission(boolean granted) {
        java.util.function.Consumer<double[]> then = pendingLocation;
        pendingLocation = null;
        if (then == null) {
            return;
        }
        if (granted) {
            locate(then);
        } else {
            then.accept(null);
        }
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
        boolean favorite = WallpaperSettings.favoriteIds(activity).contains(preset.id);
        name.setText(favorite ? "★ " + preset.name : preset.name);
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
        boolean favorite = WallpaperSettings.favoriteIds(activity).contains(preset.id);
        String star = favorite ? "☆ Remove from favorites" : "★ Add to favorites (looks widget)";
        if (preset.builtIn) {
            new AlertDialog.Builder(activity)
                    .setTitle(preset.name)
                    // (A message would hide the list.) Built-ins can be starred, not changed.
                    .setItems(new String[] {star}, (d, which) -> toggleFavorite(preset, favorite))
                    .show();
            return;
        }
        String[] actions = {star, "Update with current look", "Rename", "Share as QR code or link", "Export file",
                "Delete"};
        new AlertDialog.Builder(activity)
                .setTitle(preset.name)
                .setItems(actions, (d, chosen) -> {
                    if (chosen == 0) {
                        toggleFavorite(preset, favorite);
                        return;
                    }
                    int which = chosen - 1;
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

    private void toggleFavorite(PresetStore.Preset preset, boolean favorite) {
        WallpaperSettings.setFavorite(activity, preset.id, !favorite);
        refresh();
        int count = WallpaperSettings.favoriteIds(activity).size();
        Toast.makeText(activity, favorite ? "Removed from favorites"
                : count > LooksWidgetProvider.FAVORITE_SLOTS
                        ? "Added. The looks widget shows your first " + LooksWidgetProvider.FAVORITE_SLOTS + " favorites."
                        : "Added to favorites. Add the looks widget from Home screen widgets.",
                Toast.LENGTH_LONG).show();
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
