package com.reactivewallpaper;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;

/**
 * Builds the editor's controls: collapsible sections, sliders with live values,
 * switches and choices. Moving a slider reports a live change (for the preview);
 * letting go, or flipping a switch or choice, reports a change worth saving.
 */
final class Controls {
    private static final int SEEK_STEPS = 1000;

    interface Listener {
        /** A value is changing (e.g. a slider is moving): refresh the preview. */
        void onLiveChange();

        /** A change is finished: save it. */
        void onCommit();
    }

    private final Context context;
    private final LinearLayout root;
    /** Which sections are open, by title; kept by the caller so it survives rebuilds. */
    private final Map<String, Boolean> expandedSections;
    private final Listener listener;

    Controls(Context context, LinearLayout root, Map<String, Boolean> expandedSections, Listener listener) {
        this.context = context;
        this.root = root;
        this.expandedSections = expandedSections;
        this.listener = listener;
    }

    /** A small grey explanation under a control. */
    void hint(LinearLayout parent, String text) {
        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(context.getColor(R.color.text_secondary));
        hint.setText(text);
        parent.addView(hint);
    }

    interface FloatGetter {
        float get();
    }

    interface FloatSetter {
        void set(float value);
    }

    interface BoolGetter {
        boolean get();
    }

    interface BoolSetter {
        void set(boolean value);
    }

    interface IntSetter {
        void set(int value);
    }

    interface Formatter {
        String format(float value);
    }

    static String percent(float v) {
        int p = Math.round(v * 100);
        return p == 0 ? "Off" : p + "%";
    }

    static String position(float v) {
        int p = Math.round(v * 100);
        return p == 0 ? "Centered" : (p > 0 ? "+" : "") + p + "%";
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static String speed(float v) {
        return String.format(Locale.US, "%.2f×", v);
    }

    int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /**
     * A collapsible section; returns the container its controls go in. Every
     * section starts collapsed, so the editor opens as a tidy list.
     */
    LinearLayout section(String title) {
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setLetterSpacing(0.06f);
        header.setAllCaps(true);
        header.setTextColor(context.getColor(R.color.accent));
        header.setMinHeight(dp(48));
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(header, lp);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(8));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable refresh = () -> header.setText((body.getVisibility() == View.VISIBLE ? "▾  " : "▸  ") + title);
        // Remember open/closed sections across rebuilds (e.g. after applying a look).
        boolean open = expandedSections.getOrDefault(title, false);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        refresh.run();
        header.setOnClickListener(v -> {
            boolean nowOpen = body.getVisibility() != View.VISIBLE;
            body.setVisibility(nowOpen ? View.VISIBLE : View.GONE);
            expandedSections.put(title, nowOpen);
            refresh.run();
        });
        return body;
    }

    /** A plain sub-container, used for controls that show or hide together. */
    LinearLayout group(LinearLayout parent) {
        LinearLayout g = new LinearLayout(context);
        g.setOrientation(LinearLayout.VERTICAL);
        parent.addView(g, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return g;
    }

    void label(LinearLayout parent, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTextColor(context.getColor(R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(label, lp);
    }

    void slider(LinearLayout parent, String title, float min, float max,
                        FloatGetter getter, FloatSetter setter, Formatter formatter) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTextColor(context.getColor(R.color.text_primary));
        TextView value = new TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTextColor(context.getColor(R.color.text_secondary));
        value.setText(formatter.format(getter.get()));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        parent.addView(row, rowLp);

        SeekBar bar = new SeekBar(context);
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
                    listener.onLiveChange();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                listener.onCommit();
            }
        });
        parent.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    void toggle(LinearLayout parent, String title, BoolGetter getter, BoolSetter setter) {
        Switch toggle = new Switch(context);
        toggle.setText(title);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        toggle.setTextColor(context.getColor(R.color.text_primary));
        toggle.setChecked(getter.get());
        toggle.setOnCheckedChangeListener((b, checked) -> {
            setter.set(checked);
            listener.onCommit();
        });
        parent.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    void choice(LinearLayout parent, String[] labels, int[] values, int current, IntSetter setter) {
        choice(parent, labels, values, current, setter, true);
    }

    /** One option per line, for longer labels. */
    void choiceColumn(LinearLayout parent, String[] labels, int[] values, int current, IntSetter setter) {
        choice(parent, labels, values, current, setter, false);
    }

    void choice(LinearLayout parent, String[] labels, int[] values, int current, IntSetter setter,
                        boolean horizontal) {
        RadioGroup group = new RadioGroup(context);
        group.setOrientation(horizontal ? RadioGroup.HORIZONTAL : RadioGroup.VERTICAL);
        int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            RadioButton button = new RadioButton(context);
            ids[i] = View.generateViewId();
            button.setId(ids[i]);
            button.setText(labels[i]);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            button.setTextColor(context.getColor(R.color.text_primary));
            button.setMinHeight(dp(48));
            group.addView(button, horizontal
                    ? new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    : new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (values[i] == current) {
                group.check(ids[i]);
            }
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checkedId) {
                    setter.set(values[i]);
                    listener.onCommit();
                }
            }
        });
        parent.addView(group, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
