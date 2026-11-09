package com.reactivewallpaper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Places the character's skeleton on the picture: a first guess from the
 * subject's shape and ordinary figure proportions, then drag any dot to
 * where that joint really is. Arms and hair can be switched off for
 * pictures where they aren't visible.
 */
public class RigActivity extends Activity {
    static final String EXTRA_RIG = "rig";

    private final Handler main = new Handler(Looper.getMainLooper());
    private RigView view;
    private TextView status;
    private Bitmap image;
    private float[] mask;
    private int maskW;
    private int maskH;
    private CharacterRig rig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.background));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), 0);
        Button cancel = flatButton("Cancel");
        cancel.setOnClickListener(v -> finish());
        TextView title = new TextView(this);
        title.setText("Character skeleton");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(getColor(R.color.text_primary));
        title.setGravity(Gravity.CENTER);
        Button save = flatButton("Save");
        save.setOnClickListener(v -> {
            if (rig == null) {
                finish();
                return;
            }
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RIG, rig.encode()));
            finish();
        });
        top.addView(cancel);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(save);
        root.addView(top);

        FrameLayout area = new FrameLayout(this);
        view = new RigView();
        area.addView(view);
        status = new TextView(this);
        status.setText("Loading…");
        status.setTextColor(getColor(R.color.text_secondary));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        area.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(area, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(16), dp(8), dp(16), dp(8));
        tools.setBackgroundColor(getColor(R.color.surface));
        LinearLayout switches = new LinearLayout(this);
        Switch arms = new Switch(this);
        arms.setText("Arms");
        arms.setTextColor(getColor(R.color.text_primary));
        arms.setChecked(true);
        arms.setOnCheckedChangeListener((b, on) -> {
            if (rig != null) {
                rig.arms = on;
                view.invalidate();
            }
        });
        Switch hair = new Switch(this);
        hair.setText("Hair");
        hair.setTextColor(getColor(R.color.text_primary));
        hair.setChecked(true);
        hair.setOnCheckedChangeListener((b, on) -> {
            if (rig != null) {
                rig.hair = on;
                view.invalidate();
            }
        });
        switches.addView(arms, new LinearLayout.LayoutParams(0, dp(48), 1f));
        switches.addView(hair, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tools.addView(switches);
        Button auto = flatButton("Place it automatically again");
        auto.setOnClickListener(v -> autoPlace(arms, hair));
        tools.addView(auto);
        TextView hint = new TextView(this);
        hint.setText("Drag each dot onto that part of the character. Turn off arms or hair if they "
                + "aren't in the picture. The character then sways, nods on the beat and its hair swings.");
        hint.setTextColor(getColor(R.color.text_secondary));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tools.addView(hint);
        root.addView(tools);
        setContentView(root);

        CharacterRig given = CharacterRig.decode(getIntent().getStringExtra(EXTRA_RIG));
        new Thread(() -> {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            Bitmap picture = ImageStore.load(ImageStore.draftFile(this), dm.widthPixels, dm.heightPixels);
            Bitmap subject = ImageStore.loadMask(ImageStore.draftSubjectFile(this));
            float[] m = null;
            int mw = 96;
            int mh = 0;
            if (picture != null && subject != null) {
                mh = Math.max(8, Math.round(mw * picture.getHeight() / (float) picture.getWidth()));
                java.util.List<float[]> grids = SubjectLayer.readMasks(subject, mw, mh);
                m = new float[mw * mh];
                for (float[] g : grids) {
                    for (int i = 0; i < m.length; i++) {
                        m[i] = Math.max(m[i], g[i]);
                    }
                }
            }
            if (subject != null) {
                subject.recycle();
            }
            float[] finalMask = m;
            int finalH = mh;
            main.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (picture == null || finalMask == null) {
                    Toast.makeText(this, "Mark the subject first (Subject in front).", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                image = picture;
                mask = finalMask;
                maskW = mw;
                maskH = finalH;
                if (given != null) {
                    rig = given;
                    arms.setChecked(rig.arms);
                    hair.setChecked(rig.hair);
                    status.setText("");
                    view.invalidate();
                } else {
                    autoPlace(arms, hair);
                }
            });
        }).start();
    }

    /** From the subject's shape, by ordinary figure proportions. */
    private void autoPlace(Switch arms, Switch hair) {
        if (image == null) {
            return;
        }
        CharacterRig guess = CharacterRig.guess(mask, maskW, maskH);
        if (guess == null) {
            Toast.makeText(this, "The subject mask is empty. Mark the subject first.", Toast.LENGTH_LONG).show();
            return;
        }
        rig = guess;
        rig.arms = arms.isChecked();
        rig.hair = hair.isChecked();
        view.invalidate();
        status.setText("Placed from the subject's shape. Drag any dot that's off.");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (image != null) {
            image.recycle();
        }
    }

    private Button flatButton(String text) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextColor(getColor(R.color.accent));
        b.setAllCaps(false);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** The picture with the skeleton over it; drag the nearest dot. */
    private final class RigView extends View {
        private final Paint bone = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix fit = new Matrix();
        private final RectF bounds = new RectF();
        private int dragging = -1;

        RigView() {
            super(RigActivity.this);
            bone.setStyle(Paint.Style.STROKE);
            bone.setStrokeWidth(dp(3));
            bone.setStrokeCap(Paint.Cap.ROUND);
            label.setColor(Color.WHITE);
            label.setTextSize(dp(13));
            label.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        }

        private float px(int j) {
            return bounds.left + rig.x[j] * bounds.width();
        }

        private float py(int j) {
            return bounds.top + rig.y[j] * bounds.height();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (image == null) {
                return;
            }
            float scale = Math.min(getWidth() / (float) image.getWidth(), getHeight() / (float) image.getHeight());
            float w = image.getWidth() * scale;
            float h = image.getHeight() * scale;
            bounds.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
            fit.setRectToRect(new RectF(0, 0, image.getWidth(), image.getHeight()), bounds, Matrix.ScaleToFit.FILL);
            canvas.drawBitmap(image, fit, null);
            if (rig == null) {
                return;
            }
            for (int j = 1; j < CharacterRig.COUNT; j++) {
                if (!rig.used(j)) {
                    continue;
                }
                int p = CharacterRig.PARENT[j];
                bone.setColor(CharacterRig.isHair(j) ? 0xCCFF4FD8 : CharacterRig.isArm(j) ? 0xCCFFC94F : 0xCC00E5FF);
                canvas.drawLine(px(p), py(p), px(j), py(j), bone);
            }
            for (int j = 0; j < CharacterRig.COUNT; j++) {
                if (!rig.used(j)) {
                    continue;
                }
                boolean active = j == dragging;
                dot.setColor(Color.BLACK);
                canvas.drawCircle(px(j), py(j), dp(active ? 12 : 9), dot);
                dot.setColor(active ? Color.WHITE : CharacterRig.isHair(j) ? 0xFFFF4FD8
                        : CharacterRig.isArm(j) ? 0xFFFFC94F : 0xFF00E5FF);
                canvas.drawCircle(px(j), py(j), dp(active ? 10 : 7), dot);
            }
            if (dragging >= 0) {
                canvas.drawText(CharacterRig.NAMES[dragging], px(dragging) + dp(14), py(dragging) - dp(14), label);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (rig == null) {
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = -1;
                    float best = dp(36);
                    for (int j = 0; j < CharacterRig.COUNT; j++) {
                        if (!rig.used(j)) {
                            continue;
                        }
                        float d = (float) Math.hypot(e.getX() - px(j), e.getY() - py(j));
                        if (d < best) {
                            best = d;
                            dragging = j;
                        }
                    }
                    invalidate();
                    return dragging >= 0;
                case MotionEvent.ACTION_MOVE:
                    if (dragging >= 0) {
                        rig.x[dragging] = Math.max(0f, Math.min(1f, (e.getX() - bounds.left) / bounds.width()));
                        rig.y[dragging] = Math.max(0f, Math.min(1f, (e.getY() - bounds.top) / bounds.height()));
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }
}
