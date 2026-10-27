package com.reactivewallpaper;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Shown when the media widget is added (or reconfigured): its look, with a live preview. */
public class WidgetConfigActivity extends Activity {
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private MediaWidgetProvider.Options options;
    private LinearLayout previewButtons;
    private TextView visibilityValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // If the user backs out, the launcher drops the widget.
        setResult(RESULT_CANCELED);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        options = MediaWidgetProvider.Options.load(this, widgetId);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.background));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        // Preview: the buttons over the actual wallpaper picture.
        FrameLayout preview = new FrameLayout(this);
        ImageView wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setBackgroundColor(0xFF000000);
        preview.addView(wallpaper);
        previewButtons = new LinearLayout(this);
        previewButtons.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams buttonsLp = new FrameLayout.LayoutParams(dp(260), dp(80), Gravity.CENTER);
        preview.addView(previewButtons, buttonsLp);
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        new Thread(() -> {
            Bitmap image = ImageStore.load(this, dp(400), dp(220));
            if (image != null) {
                runOnUiThread(() -> wallpaper.setImageBitmap(image));
            }
        }).start();

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(controls);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        title(controls, "Media buttons");
        text(controls, "Previous, play/pause and next for whatever is playing. Turn visibility down to 0% "
                + "for invisible buttons that still work. Learn where they are first.");

        title(controls, "Buttons");
        RadioGroup layouts = new RadioGroup(this);
        String[] names = {"Row", "Column", "Play/pause only"};
        int[] values = {MediaWidgetProvider.LAYOUT_ROW, MediaWidgetProvider.LAYOUT_COLUMN,
                MediaWidgetProvider.LAYOUT_SINGLE};
        for (int i = 0; i < names.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(names[i]);
            b.setTextColor(getColor(R.color.text_primary));
            int value = values[i];
            b.setChecked(options.layout == value);
            b.setOnClickListener(v -> {
                options.layout = value;
                refreshPreview();
            });
            layouts.addView(b);
        }
        controls.addView(layouts);

        LinearLayout row = new LinearLayout(this);
        TextView label = text(row, "Visibility");
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        visibilityValue = text(row, "");
        controls.addView(row);
        SeekBar visibility = new SeekBar(this);
        visibility.setMax(100);
        visibility.setProgress(Math.round(options.visibility * 100));
        visibility.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                options.visibility = progress / 100f;
                refreshPreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        controls.addView(visibility, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        toggle(controls, "Dark background behind the buttons", options.backdrop, on -> {
            options.backdrop = on;
            refreshPreview();
        });
        toggle(controls, "Vibrate on tap (feel it without seeing it)", options.vibrate, on -> options.vibrate = on);
        toggle(controls, "Ripple on tap", options.ripple, on -> options.ripple = on);
        toggle(controls, "Show where the buttons are (outlines on every widget)",
                MediaWidgetProvider.revealing(this), on -> MediaWidgetProvider.setRevealing(this, on));
        text(controls, "Tip: leave the outlines on until you know where the buttons are, then turn them off "
                + "here or in Resona. On Android 12+, long-press the widget to change these settings.");

        Button done = new Button(this);
        done.setText("Done");
        done.setOnClickListener(v -> finishSetup());
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        doneLp.setMargins(dp(16), dp(8), dp(16), dp(12));
        root.addView(done, doneLp);
        setContentView(root);
        refreshPreview();
    }

    private void finishSetup() {
        options.save(this, widgetId);
        AppWidgetManager.getInstance(this).updateAppWidget(widgetId, MediaWidgetProvider.views(this, widgetId));
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        finish();
    }

    private void refreshPreview() {
        visibilityValue.setText(options.visibility < 0.005f ? "Invisible" : Math.round(options.visibility * 100) + "%");
        previewButtons.removeAllViews();
        previewButtons.setOrientation(options.layout == MediaWidgetProvider.LAYOUT_COLUMN
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        previewButtons.setBackgroundResource(options.backdrop ? R.drawable.widget_backdrop : 0);
        int[] icons = options.layout == MediaWidgetProvider.LAYOUT_SINGLE
                ? new int[] {R.drawable.ic_widget_play_pause}
                : new int[] {R.drawable.ic_widget_previous, R.drawable.ic_widget_play_pause, R.drawable.ic_widget_next};
        boolean reveal = MediaWidgetProvider.revealing(this);
        for (int icon : icons) {
            ImageView b = new ImageView(this);
            b.setImageResource(icon);
            b.setImageAlpha(Math.round(255 * (reveal ? Math.max(0.6f, options.visibility) : options.visibility)));
            b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            b.setPadding(dp(8), dp(8), dp(8), dp(8));
            if (reveal) {
                b.setBackgroundResource(R.drawable.widget_outline);
            }
            LinearLayout.LayoutParams lp = options.layout == MediaWidgetProvider.LAYOUT_COLUMN
                    ? new LinearLayout.LayoutParams(dp(56), 0, 1f)
                    : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            previewButtons.addView(b, lp);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewButtons.getLayoutParams();
        if (options.layout == MediaWidgetProvider.LAYOUT_COLUMN) {
            lp.width = dp(72);
            lp.height = dp(200);
        } else {
            lp.width = options.layout == MediaWidgetProvider.LAYOUT_SINGLE ? dp(96) : dp(260);
            lp.height = dp(80);
        }
        previewButtons.setLayoutParams(lp);
    }

    private interface OnToggle {
        void set(boolean on);
    }

    private void toggle(LinearLayout parent, String label, boolean checked, OnToggle onToggle) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(getColor(R.color.text_primary));
        s.setChecked(checked);
        s.setOnCheckedChangeListener((b, on) -> {
            onToggle.set(on);
            refreshPreview();
        });
        parent.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    }

    private void title(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.accent));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setAllCaps(true);
        t.setPadding(0, dp(12), 0, dp(4));
        parent.addView(t);
    }

    private TextView text(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.text_secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        parent.addView(t);
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
