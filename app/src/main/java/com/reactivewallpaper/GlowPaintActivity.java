package com.reactivewallpaper;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lets the user paint which parts of the picture glow. The result is a gray
 * mask saved next to the draft image: 255 = always glow, 0 = never, 128 = auto.
 */
public class GlowPaintActivity extends Activity {
    static final String EXTRA_COVERAGE = "coverage";
    private static final int MASK_LONG_SIDE = 512;
    private static final int MAX_UNDO = 15;

    private static final int MODE_GLOW = 0;
    private static final int MODE_NO_GLOW = 1;
    private static final int MODE_AUTO = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PaintView paintView;
    private TextView status;
    private float coverage;
    private float[] hues;
    private int mode = MODE_GLOW;
    private float brushDp = 36f;
    private boolean saving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        coverage = getIntent().getFloatExtra(EXTRA_COVERAGE, WallpaperSettings.DEFAULT_COVERAGE);

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
        title.setText("Paint the glow");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(getColor(R.color.text_primary));
        title.setGravity(Gravity.CENTER);
        Button save = flatButton("Save");
        save.setOnClickListener(v -> save());
        top.addView(cancel);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(save);
        root.addView(top);

        FrameLayout canvasArea = new FrameLayout(this);
        paintView = new PaintView(this);
        canvasArea.addView(paintView);
        status = new TextView(this);
        status.setText("Loading…");
        status.setTextColor(getColor(R.color.text_secondary));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        canvasArea.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(canvasArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(16), dp(8), dp(16), dp(8));
        tools.setBackgroundColor(getColor(R.color.surface));

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"Glow", "No glow", "Auto"};
        int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            ids[i] = View.generateViewId();
            b.setId(ids[i]);
            b.setText(labels[i]);
            b.setTextColor(getColor(R.color.text_primary));
            b.setMinHeight(dp(48));
            modes.addView(b, new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        modes.check(ids[MODE_GLOW]);
        modes.setOnCheckedChangeListener((g, checked) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checked) {
                    mode = i;
                }
            }
        });
        tools.addView(modes);

        LinearLayout brushRow = new LinearLayout(this);
        brushRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brushLabel = new TextView(this);
        brushLabel.setText("Brush");
        brushLabel.setTextColor(getColor(R.color.text_primary));
        SeekBar brush = new SeekBar(this);
        brush.setMax(100);
        brush.setProgress(Math.round((brushDp - 8f) / 112f * 100));
        brush.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                brushDp = 8f + 112f * progress / 100f;
                paintView.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        brushRow.addView(brushLabel);
        brushRow.addView(brush, new LinearLayout.LayoutParams(0, dp(40), 1f));
        tools.addView(brushRow);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Switch showGlow = new Switch(this);
        showGlow.setText("Show glow");
        showGlow.setTextColor(getColor(R.color.text_primary));
        showGlow.setChecked(true);
        showGlow.setOnCheckedChangeListener((b, checked) -> paintView.setShowGlow(checked));
        Button undo = flatButton("Undo");
        undo.setOnClickListener(v -> paintView.undo());
        Button clear = flatButton("Clear");
        clear.setOnClickListener(v -> paintView.clear());
        actions.addView(showGlow, new LinearLayout.LayoutParams(0, dp(48), 1f));
        actions.addView(undo);
        actions.addView(clear);
        tools.addView(actions);
        root.addView(tools);
        setContentView(root);

        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void load() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        executor.execute(() -> {
            Bitmap image = ImageStore.load(ImageStore.draftFile(this), w / 2, w);
            if (image == null) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "Choose an image first.", Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }
            float[] imageHues = ImageStore.accentHues(image);
            float longSide = Math.max(image.getWidth(), image.getHeight());
            int mw = Math.max(1, Math.round(image.getWidth() * MASK_LONG_SIDE / longSide));
            int mh = Math.max(1, Math.round(image.getHeight() * MASK_LONG_SIDE / longSide));
            Bitmap mask = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
            Bitmap saved = ImageStore.loadMask(ImageStore.draftMaskFile(this));
            Canvas c = new Canvas(mask);
            if (saved != null) {
                c.drawBitmap(saved, null, new android.graphics.Rect(0, 0, mw, mh),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
                saved.recycle();
            } else {
                c.drawColor(Color.rgb(LivingImage.MASK_AUTO, LivingImage.MASK_AUTO, LivingImage.MASK_AUTO));
            }
            Bitmap glow = LivingImage.glowPreview(image, mask, imageHues, coverage,
                    image.getWidth() / 2, image.getHeight() / 2);
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                hues = imageHues;
                paintView.setContent(image, mask, glow);
                status.setText("Paint over parts that should glow (or shouldn't). Pinch to zoom.");
            });
        });
    }

    /** Recomputes what glows after a change, off the main thread. */
    private void refreshGlow() {
        Bitmap image = paintView.image;
        Bitmap maskCopy = paintView.mask.copy(Bitmap.Config.ARGB_8888, false);
        executor.execute(() -> {
            Bitmap glow = LivingImage.glowPreview(image, maskCopy, hues, coverage,
                    image.getWidth() / 2, image.getHeight() / 2);
            maskCopy.recycle();
            mainHandler.post(() -> {
                if (!isDestroyed()) {
                    paintView.setGlow(glow);
                }
            });
        });
    }

    private void save() {
        if (paintView.mask == null || saving) {
            return;
        }
        saving = true;
        Bitmap maskCopy = paintView.mask.copy(Bitmap.Config.ARGB_8888, false);
        executor.execute(() -> {
            // An untouched mask is the same as no mask at all.
            boolean ok = ImageStore.saveMask(isAllAuto(maskCopy) ? null : maskCopy,
                    ImageStore.draftMaskFile(this));
            mainHandler.post(() -> {
                if (ok) {
                    setResult(RESULT_OK);
                    finish();
                } else {
                    saving = false;
                    Toast.makeText(this, "Couldn't save the glow painting.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static boolean isAllAuto(Bitmap mask) {
        int w = mask.getWidth();
        int[] row = new int[w];
        for (int y = 0; y < mask.getHeight(); y++) {
            mask.getPixels(row, 0, w, 0, y, w, 1);
            for (int p : row) {
                if (Math.abs((p & 0xFF) - LivingImage.MASK_AUTO) > 4) {
                    return false;
                }
            }
        }
        return true;
    }

    private Button flatButton(String text) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextColor(getColor(R.color.accent));
        return b;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /** Shows the picture, its glow and the painted areas, and turns touches into brush strokes. */
    private final class PaintView extends View {
        private Bitmap image;
        private Bitmap mask;
        private Bitmap overlay;
        private Bitmap glow;
        private Canvas maskCanvas;
        private Canvas overlayCanvas;
        private boolean showGlow = true;

        private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint overlayPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        // No anti-aliasing: mask values must stay exact (no half-transparent edges).
        private final Paint maskBrush = new Paint();
        private final Paint overlayBrush = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** Image pixels to view; user zoom and pan are folded in. */
        private final Matrix viewMatrix = new Matrix();
        private final Matrix inverse = new Matrix();
        private final Matrix scratch = new Matrix();
        private final float[] pt = new float[2];
        private final Deque<Bitmap[]> undoStack = new ArrayDeque<>();
        private final ScaleGestureDetector scaler;

        private boolean stroking;
        private boolean gesturing;
        private float lastMaskX;
        private float lastMaskY;
        private float cursorX = -1;
        private float cursorY = -1;
        private float lastFocusX;
        private float lastFocusY;

        PaintView(Context context) {
            super(context);
            glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            for (Paint p : new Paint[] {maskBrush, overlayBrush}) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                // Replace rather than blend, so overlapping strokes don't build up.
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            }
            cursorPaint.setStyle(Paint.Style.STROKE);
            cursorPaint.setColor(Color.WHITE);
            cursorPaint.setStrokeWidth(dp(1.5f));
            scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    viewMatrix.postScale(detector.getScaleFactor(), detector.getScaleFactor(),
                            detector.getFocusX(), detector.getFocusY());
                    invalidate();
                    return true;
                }
            });
        }

        void setContent(Bitmap image, Bitmap mask, Bitmap glow) {
            this.image = image;
            this.mask = mask;
            this.glow = glow;
            maskCanvas = new Canvas(mask);
            overlay = Bitmap.createBitmap(mask.getWidth(), mask.getHeight(), Bitmap.Config.ARGB_8888);
            overlayCanvas = new Canvas(overlay);
            rebuildOverlay();
            fitToView();
            invalidate();
        }

        void setGlow(Bitmap newGlow) {
            if (glow != null) {
                glow.recycle();
            }
            glow = newGlow;
            invalidate();
        }

        void setShowGlow(boolean show) {
            showGlow = show;
            invalidate();
        }

        void undo() {
            if (undoStack.isEmpty()) {
                return;
            }
            Bitmap[] state = undoStack.pop();
            maskCanvas.drawBitmap(state[0], 0, 0, null);
            state[0].recycle();
            rebuildOverlay();
            invalidate();
            refreshGlow();
        }

        void clear() {
            if (mask == null) {
                return;
            }
            pushUndo();
            mask.eraseColor(Color.rgb(LivingImage.MASK_AUTO, LivingImage.MASK_AUTO, LivingImage.MASK_AUTO));
            rebuildOverlay();
            invalidate();
            refreshGlow();
        }

        private void pushUndo() {
            undoStack.push(new Bitmap[] {mask.copy(Bitmap.Config.ARGB_8888, false)});
            while (undoStack.size() > MAX_UNDO) {
                undoStack.removeLast()[0].recycle();
            }
        }

        /** Colors painted areas: cyan for glow, red for no glow. */
        private void rebuildOverlay() {
            int w = mask.getWidth();
            int h = mask.getHeight();
            int[] px = new int[w * h];
            mask.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                px[i] = overlayColor(px[i] & 0xFF);
            }
            overlay.setPixels(px, 0, w, 0, 0, w, h);
        }

        private int overlayColor(int value) {
            if (value > LivingImage.MASK_AUTO + 8) {
                return Color.argb(90, 0, 229, 255);
            }
            if (value < LivingImage.MASK_AUTO - 8) {
                return Color.argb(120, 255, 40, 70);
            }
            return Color.TRANSPARENT;
        }

        private void fitToView() {
            if (image == null || getWidth() == 0) {
                return;
            }
            float scale = Math.min(getWidth() / (float) image.getWidth(), getHeight() / (float) image.getHeight());
            viewMatrix.setScale(scale, scale);
            viewMatrix.postTranslate((getWidth() - image.getWidth() * scale) / 2f,
                    (getHeight() - image.getHeight() * scale) / 2f);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            fitToView();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if (image == null) {
                return;
            }
            canvas.drawBitmap(image, viewMatrix, imagePaint);
            if (showGlow && glow != null) {
                scratch.set(viewMatrix);
                scratch.preScale(image.getWidth() / (float) glow.getWidth(),
                        image.getHeight() / (float) glow.getHeight());
                canvas.drawBitmap(glow, scratch, glowPaint);
            }
            scratch.set(viewMatrix);
            scratch.preScale(image.getWidth() / (float) mask.getWidth(), image.getHeight() / (float) mask.getHeight());
            canvas.drawBitmap(overlay, scratch, overlayPaint);
            if (cursorX >= 0) {
                canvas.drawCircle(cursorX, cursorY, dp(brushDp) / 2f, cursorPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (image == null) {
                return true;
            }
            scaler.onTouchEvent(e);
            int action = e.getActionMasked();
            if (e.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN) {
                // Two fingers zoom and pan. The first finger already painted a dot; take it back.
                if (stroking) {
                    stroking = false;
                    undo();
                }
                float fx = (e.getX(0) + e.getX(1)) / 2f;
                float fy = (e.getY(0) + e.getY(1)) / 2f;
                if (gesturing) {
                    viewMatrix.postTranslate(fx - lastFocusX, fy - lastFocusY);
                    invalidate();
                }
                lastFocusX = fx;
                lastFocusY = fy;
                gesturing = true;
                return true;
            }
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    gesturing = false;
                    pushUndo();
                    stroking = true;
                    toMask(e.getX(), e.getY());
                    lastMaskX = pt[0];
                    lastMaskY = pt[1];
                    paintTo(pt[0], pt[1]);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (stroking) {
                        toMask(e.getX(), e.getY());
                        paintTo(pt[0], pt[1]);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    endStroke();
                    gesturing = false;
                    cursorX = -1;
                    invalidate();
                    return true;
                default:
                    break;
            }
            cursorX = e.getX();
            cursorY = e.getY();
            invalidate();
            return true;
        }

        private void endStroke() {
            if (stroking) {
                stroking = false;
                refreshGlow();
            }
        }

        private void toMask(float x, float y) {
            viewMatrix.invert(inverse);
            pt[0] = x;
            pt[1] = y;
            inverse.mapPoints(pt);
            pt[0] *= mask.getWidth() / (float) image.getWidth();
            pt[1] *= mask.getHeight() / (float) image.getHeight();
        }

        private void paintTo(float mx, float my) {
            int value = mode == MODE_GLOW ? 255 : mode == MODE_NO_GLOW ? 0 : LivingImage.MASK_AUTO;
            // Brush size is in screen dp, so convert it to mask pixels at the current zoom.
            float[] values = new float[9];
            viewMatrix.getValues(values);
            float viewPerMask = values[Matrix.MSCALE_X] * image.getWidth() / (float) mask.getWidth();
            float width = Math.max(1f, dp(brushDp) / viewPerMask);
            maskBrush.setColor(Color.rgb(value, value, value));
            maskBrush.setStrokeWidth(width);
            overlayBrush.setColor(overlayColor(value));
            overlayBrush.setStrokeWidth(width);
            float x0 = lastMaskX;
            float y0 = lastMaskY;
            // A zero-length line still draws a round dot, so taps paint too.
            maskCanvas.drawLine(x0, y0, mx, my, maskBrush);
            overlayCanvas.drawLine(x0, y0, mx, my, overlayBrush);
            lastMaskX = mx;
            lastMaskY = my;
        }
    }
}
