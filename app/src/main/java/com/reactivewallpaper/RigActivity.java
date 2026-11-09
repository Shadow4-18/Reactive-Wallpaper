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
    private Switch blinkSwitch;
    private android.widget.SeekBar eyeSize;

    private void showEyeControls() {
        blinkSwitch.setChecked(rig.blink);
        eyeSize.setProgress(Math.round((rig.eyeSize - 0.008f) / 0.072f * 100f));
    }

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
        Switch blink = new Switch(this);
        blink.setText("Blink");
        blink.setTextColor(getColor(R.color.text_primary));
        blink.setChecked(true);
        blink.setOnCheckedChangeListener((b, on) -> {
            if (rig != null) {
                rig.blink = on;
                view.invalidate();
            }
        });
        blinkSwitch = blink;
        switches.addView(arms, new LinearLayout.LayoutParams(0, dp(48), 1f));
        switches.addView(hair, new LinearLayout.LayoutParams(0, dp(48), 1f));
        switches.addView(blink, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tools.addView(switches);
        TextView eyeLabel = new TextView(this);
        eyeLabel.setText("Eye size (the green circles should just cover the eyes)");
        eyeLabel.setTextColor(getColor(R.color.text_secondary));
        eyeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tools.addView(eyeLabel);
        eyeSize = new android.widget.SeekBar(this);
        eyeSize.setMax(100);
        eyeSize.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && rig != null) {
                    rig.eyeSize = 0.008f + 0.072f * progress / 100f;
                    view.invalidate();
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
        tools.addView(eyeSize);
        Button auto = flatButton("Place it automatically again");
        auto.setOnClickListener(v -> autoPlace(arms, hair));
        tools.addView(auto);
        TextView hint = new TextView(this);
        hint.setText("Drag each dot onto that part of the character, and the green dots onto the middle "
                + "of each eye. Pinch to zoom in; two fingers move around, double-tap shows it all. Turn off arms or hair if they aren't in the picture. The character then sways, "
                + "bobs with the music, blinks, and its hair swings.");
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
                    if (getIntent().getStringExtra(EXTRA_RIG).startsWith("r1;")) {
                        // Saved before eyes existed: put them where a face's usually are.
                        rig.guessEyes(image.getWidth(), image.getHeight());
                        rig.blink = true;
                    }
                    arms.setChecked(rig.arms);
                    hair.setChecked(rig.hair);
                    showEyeControls();
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
        rig.guessEyes(image.getWidth(), image.getHeight());
        rig.blink = blinkSwitch.isChecked();
        showEyeControls();
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
        private final Paint eye = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix fit = new Matrix();
        private final RectF bounds = new RectF();
        private int dragging = -1;
        // Pinch to zoom, two fingers to move around, double-tap to see it all again.
        private float zoom = 1f;
        private float panX;
        private float panY;
        private float lastFocusX;
        private float lastFocusY;
        private boolean multiTouch;
        private long lastTapTime;
        private final android.view.ScaleGestureDetector scaler = new android.view.ScaleGestureDetector(
                RigActivity.this, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector d) {
                        float before = zoom;
                        zoom = Math.max(1f, Math.min(8f, zoom * d.getScaleFactor()));
                        // Keep the point under the fingers where it is.
                        float k = zoom / before;
                        float cx = getWidth() / 2f + panX;
                        float cy = getHeight() / 2f + panY;
                        panX += (d.getFocusX() - cx) * (1 - k);
                        panY += (d.getFocusY() - cy) * (1 - k);
                        clampPan();
                        invalidate();
                        return true;
                    }
                });

        private void clampPan() {
            if (image == null) {
                return;
            }
            float scale = Math.min(getWidth() / (float) image.getWidth(), getHeight() / (float) image.getHeight());
            float w = image.getWidth() * scale * zoom;
            float h = image.getHeight() * scale * zoom;
            float maxX = Math.max(0f, (w - getWidth()) / 2f);
            float maxY = Math.max(0f, (h - getHeight()) / 2f);
            panX = Math.max(-maxX, Math.min(maxX, panX));
            panY = Math.max(-maxY, Math.min(maxY, panY));
        }

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
            return bounds.left + (j >= CharacterRig.COUNT ? rig.eyeX[j - CharacterRig.COUNT] : rig.x[j]) * bounds.width();
        }

        private float py(int j) {
            return bounds.top + (j >= CharacterRig.COUNT ? rig.eyeY[j - CharacterRig.COUNT] : rig.y[j])
                    * bounds.height();
        }

        private boolean shown(int j) {
            return j >= CharacterRig.COUNT ? rig.blink : rig.used(j);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (image == null) {
                return;
            }
            float scale = Math.min(getWidth() / (float) image.getWidth(), getHeight() / (float) image.getHeight());
            float w = image.getWidth() * scale * zoom;
            float h = image.getHeight() * scale * zoom;
            float cx = getWidth() / 2f + panX;
            float cy = getHeight() / 2f + panY;
            bounds.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
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
            if (rig.blink) {
                for (int e = CharacterRig.COUNT; e < CharacterRig.COUNT + 2; e++) {
                    boolean active = e == dragging;
                    eye.setStyle(Paint.Style.STROKE);
                    eye.setStrokeWidth(dp(2));
                    eye.setColor(0xFF3DFF8F);
                    float radius = rig.eyeSize * bounds.width() / 2f;
                    canvas.drawOval(px(e) - radius, py(e) - radius * 0.7f, px(e) + radius, py(e) + radius * 0.7f, eye);
                    eye.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(px(e), py(e), dp(active ? 7 : 5), eye);
                }
            }
            if (dragging >= 0) {
                String name = dragging >= CharacterRig.COUNT
                        ? (dragging == CharacterRig.COUNT ? "Left eye" : "Right eye") : CharacterRig.NAMES[dragging];
                canvas.drawText(name, px(dragging) + dp(14), py(dragging) - dp(14), label);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (rig == null) {
                return false;
            }
            scaler.onTouchEvent(e);
            if (e.getPointerCount() > 1) {
                // Two fingers: zoom (above) and move around; no dot is dragged.
                float fx = 0f;
                float fy = 0f;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    fx += e.getX(i);
                    fy += e.getY(i);
                }
                fx /= e.getPointerCount();
                fy /= e.getPointerCount();
                if (!multiTouch || e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                        || e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                    multiTouch = true;
                } else {
                    panX += fx - lastFocusX;
                    panY += fy - lastFocusY;
                    clampPan();
                }
                lastFocusX = fx;
                lastFocusY = fy;
                dragging = -1;
                invalidate();
                return true;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    multiTouch = false;
                    dragging = -1;
                    float best = dp(36);
                    for (int j = 0; j < CharacterRig.COUNT + 2; j++) {
                        if (!shown(j)) {
                            continue;
                        }
                        float d = (float) Math.hypot(e.getX() - px(j), e.getY() - py(j));
                        if (d < best) {
                            best = d;
                            dragging = j;
                        }
                    }
                    if (dragging < 0) {
                        // Double-tap on empty space: back to the whole picture.
                        if (e.getEventTime() - lastTapTime < 300) {
                            zoom = 1f;
                            panX = 0f;
                            panY = 0f;
                            lastTapTime = 0;
                        } else {
                            lastTapTime = e.getEventTime();
                        }
                    }
                    invalidate();
                    // Keep the gesture either way, so a second finger can zoom.
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging >= 0 && !multiTouch) {
                        float nx = Math.max(0f, Math.min(1f, (e.getX() - bounds.left) / bounds.width()));
                        float ny = Math.max(0f, Math.min(1f, (e.getY() - bounds.top) / bounds.height()));
                        if (dragging >= CharacterRig.COUNT) {
                            rig.eyeX[dragging - CharacterRig.COUNT] = nx;
                            rig.eyeY[dragging - CharacterRig.COUNT] = ny;
                        } else {
                            rig.x[dragging] = nx;
                            rig.y[dragging] = ny;
                        }
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
