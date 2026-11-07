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
 *
 * <p>With {@link #EXTRA_SUBJECT} it marks the picture's subject instead
 * (255 = subject, 0 = not), which is drawn in front of the effects, and
 * offers automatic detection.
 */
public class GlowPaintActivity extends Activity {
    static final String EXTRA_COVERAGE = "coverage";
    static final String EXTRA_SUBJECT = "subject";
    private static final int MASK_LONG_SIDE = 512;
    private static final int MAX_UNDO = 15;

    /** Overlay colors for subject layers 1-3: purple, orange, green. */
    private static final int[] LAYER_COLORS = {0xFFAA5AFF, 0xFFFF9628, 0xFF3CDC78};
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
    private boolean subjectMode;
    private boolean detecting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        coverage = getIntent().getFloatExtra(EXTRA_COVERAGE, WallpaperSettings.DEFAULT_COVERAGE);
        subjectMode = getIntent().getBooleanExtra(EXTRA_SUBJECT, false);

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
        title.setText(subjectMode ? "Mark the subject" : "Paint the glow");
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
        String[] labels = subjectMode ? new String[] {"Subject", "Erase"} : new String[] {"Glow", "No glow", "Auto"};
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
        if (subjectMode) {
            // Up to three subjects, each its own layer with its own depth.
            RadioGroup layerChoice = new RadioGroup(this);
            layerChoice.setOrientation(RadioGroup.HORIZONTAL);
            int[] layerIds = new int[SubjectLayer.MAX_PARTS];
            for (int i = 0; i < layerIds.length; i++) {
                RadioButton b = new RadioButton(this);
                layerIds[i] = View.generateViewId();
                b.setId(layerIds[i]);
                b.setText("Subject " + (i + 1));
                b.setTextColor(LAYER_COLORS[i]);
                b.setMinHeight(dp(44));
                layerChoice.addView(b, new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            layerChoice.check(layerIds[0]);
            layerChoice.setOnCheckedChangeListener((g, checked) -> {
                for (int i = 0; i < layerIds.length; i++) {
                    if (layerIds[i] == checked) {
                        paintView.setLayer(i);
                    }
                }
            });
            tools.addView(layerChoice);
        }

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
        if (subjectMode) {
            Button detect = flatButton("Auto-detect");
            detect.setOnClickListener(v -> detect());
            actions.addView(detect);
            actions.addView(new View(this), new LinearLayout.LayoutParams(0, dp(48), 1f));
        } else {
            Switch showGlow = new Switch(this);
            showGlow.setText("Show glow");
            showGlow.setTextColor(getColor(R.color.text_primary));
            showGlow.setChecked(true);
            showGlow.setOnCheckedChangeListener((b, checked) -> paintView.setShowGlow(checked));
            actions.addView(showGlow, new LinearLayout.LayoutParams(0, dp(48), 1f));
        }
        Button undo = flatButton("Undo");
        undo.setOnClickListener(v -> paintView.undo());
        Button clear = flatButton("Clear");
        clear.setOnClickListener(v -> paintView.clear());
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
            Bitmap saved = ImageStore.loadMask(maskFile());
            Canvas c = new Canvas(mask);
            if (saved != null) {
                c.drawBitmap(saved, null, new android.graphics.Rect(0, 0, mw, mh),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
                saved.recycle();
            } else {
                c.drawColor(Color.rgb(blank(), blank(), blank()));
            }
            Bitmap glow = subjectMode ? null : LivingImage.glowPreview(image, mask, imageHues, coverage,
                    image.getWidth() / 2, image.getHeight() / 2);
            // A subject mask may hold several subjects, one per color channel.
            Bitmap[] layers = subjectMode ? splitLayers(mask) : null;
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                hues = imageHues;
                paintView.setContent(image, layers != null ? layers[0] : mask, glow);
                if (layers != null) {
                    paintView.setLayers(layers);
                }
                status.setText(subjectMode
                        ? "Paint over the subject, or tap Auto-detect. It's drawn in front of the effects; "
                                + "use Subject 2 and 3 for more people or objects at other depths. Pinch to zoom."
                        : "Paint over parts that should glow (or shouldn't). Pinch to zoom.");
            });
        });
    }

    /** Where this painting is kept. */
    private java.io.File maskFile() {
        return subjectMode ? ImageStore.draftSubjectFile(this) : ImageStore.draftMaskFile(this);
    }

    /** The unpainted value: "auto" for glow, "not subject" for the subject. */
    private int blank() {
        return subjectMode ? 0 : LivingImage.MASK_AUTO;
    }

    private void detect() {
        if (paintView.image == null || detecting) {
            return;
        }
        detecting = true;
        status.setText("Looking for the subject…");
        SubjectDetector.detect(this, paintView.image, (found, error) -> {
            detecting = false;
            if (isDestroyed()) {
                if (found != null) {
                    found.recycle();
                }
                return;
            }
            if (found == null) {
                status.setText(error);
                return;
            }
            try {
                paintView.replaceMask(found);
            } catch (RuntimeException e) {
                // Never lose the editor over a detection result; report it instead.
                android.util.Log.e("GlowPaintActivity", "Couldn't use the detected subject", e);
                Diagnostics.recordProblem(this, e);
                status.setText("Couldn't use what was found. Paint the subject by hand instead.");
                found.recycle();
                return;
            }
            found.recycle();
            status.setText("Found it. Several people or objects each get their own layer (Subject 1-3), "
                    + "which move with their own depth. Touch up with Subject and Erase if needed.");
        });
    }

    /** Recomputes what glows after a change, off the main thread. */
    private void refreshGlow() {
        if (subjectMode) {
            return;
        }
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
        Bitmap maskCopy = subjectMode && paintView.layers != null
                ? composeLayers(paintView.layers) : paintView.mask.copy(Bitmap.Config.ARGB_8888, false);
        executor.execute(() -> {
            // An untouched mask is the same as no mask at all.
            boolean blankMask = maskCopy == null || isBlank(maskCopy, blank());
            boolean ok = ImageStore.saveMask(blankMask ? null : maskCopy, maskFile());
            mainHandler.post(() -> {
                if (ok) {
                    setResult(RESULT_OK);
                    finish();
                } else {
                    saving = false;
                    Toast.makeText(this, subjectMode ? "Couldn't save the subject." : "Couldn't save the glow painting.",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** A subject mask's subjects as gray layers (the first holds a single-subject mask). */
    private static Bitmap[] splitLayers(Bitmap mask) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        java.util.List<float[]> grids = SubjectLayer.readMasks(mask, w, h);
        Bitmap[] layers = new Bitmap[SubjectLayer.MAX_PARTS];
        for (int i = 0; i < layers.length; i++) {
            int[] px = new int[w * h];
            float[] g = i < grids.size() ? grids.get(i) : null;
            for (int k = 0; k < px.length; k++) {
                int v = g == null ? 0 : Math.round(g[k] * 255f);
                px[k] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            layers[i] = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true);
        }
        return layers;
    }

    /**
     * The layers as one mask: gray when only the first is used (as single-subject
     * masks always were), else a subject per color channel; null when all are empty.
     */
    private static Bitmap composeLayers(Bitmap[] layers) {
        int w = layers[0].getWidth();
        int h = layers[0].getHeight();
        int[][] px = new int[layers.length][w * h];
        boolean[] used = new boolean[layers.length];
        for (int i = 0; i < layers.length; i++) {
            layers[i].getPixels(px[i], 0, w, 0, 0, w, h);
            for (int p : px[i]) {
                if ((p & 0xFF) > 4) {
                    used[i] = true;
                    break;
                }
            }
        }
        if (!used[0] && !used[1] && !used[2]) {
            return null;
        }
        if (!used[1] && !used[2]) {
            return layers[0].copy(Bitmap.Config.ARGB_8888, false);
        }
        int[] out = new int[w * h];
        for (int k = 0; k < out.length; k++) {
            out[k] = 0xFF000000 | ((px[0][k] & 0xFF) << 16) | ((px[1][k] & 0xFF) << 8) | (px[2][k] & 0xFF);
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }

    private static boolean isBlank(Bitmap mask, int blank) {
        int w = mask.getWidth();
        int[] row = new int[w];
        for (int y = 0; y < mask.getHeight(); y++) {
            mask.getPixels(row, 0, w, 0, y, w, 1);
            for (int p : row) {
                if (Math.abs((p & 0xFF) - blank) > 4) {
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
        /** Subject mode: one gray mask per subject; {@link #mask} is the active one. */
        private Bitmap[] layers;
        private int activeLayer;
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

        /** Subject mode: the subjects' layers; the brush paints the chosen one. */
        void setLayers(Bitmap[] all) {
            layers = all;
            setLayer(0);
        }

        void setLayer(int index) {
            if (layers == null) {
                return;
            }
            activeLayer = index;
            mask = layers[index];
            maskCanvas = new Canvas(mask);
            // Undo steps belong to a layer; switching starts afresh.
            while (!undoStack.isEmpty()) {
                undoStack.pop()[0].recycle();
            }
            rebuildOverlay();
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
            mask.eraseColor(Color.rgb(blank(), blank(), blank()));
            if (layers != null) {
                for (Bitmap layer : layers) {
                    layer.eraseColor(Color.BLACK);
                }
            }
            rebuildOverlay();
            invalidate();
            refreshGlow();
        }

        /** Replaces the whole painting with {@code next} (stretched to fit); undoable. */
        void replaceMask(Bitmap next) {
            if (mask == null) {
                return;
            }
            if (layers != null) {
                // Detected subjects, each into its own layer.
                Bitmap scaled = Bitmap.createScaledBitmap(next, mask.getWidth(), mask.getHeight(), true);
                Bitmap[] split = splitLayers(scaled);
                for (int i = 0; i < layers.length; i++) {
                    new Canvas(layers[i]).drawBitmap(split[i], 0, 0, null);
                    split[i].recycle();
                }
                setLayer(0);
                return;
            }
            pushUndo();
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            maskCanvas.drawBitmap(next, null, new android.graphics.Rect(0, 0, mask.getWidth(), mask.getHeight()), p);
            rebuildOverlay();
            invalidate();
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
            if (layers != null) {
                // Every subject in its own color; where they overlap, the strongest shows.
                int[][] all = new int[layers.length][w * h];
                for (int i = 0; i < layers.length; i++) {
                    layers[i].getPixels(all[i], 0, w, 0, 0, w, h);
                }
                for (int k = 0; k < px.length; k++) {
                    int best = 0;
                    int value = 0;
                    for (int i = 0; i < layers.length; i++) {
                        int v = all[i][k] & 0xFF;
                        if (v > value) {
                            value = v;
                            best = i;
                        }
                    }
                    px[k] = layerColor(best, value);
                }
                overlay.setPixels(px, 0, w, 0, 0, w, h);
                return;
            }
            mask.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                px[i] = overlayColor(px[i] & 0xFF);
            }
            overlay.setPixels(px, 0, w, 0, 0, w, h);
        }

        private int layerColor(int layer, int value) {
            return (LAYER_COLORS[layer] & 0x00FFFFFF) | ((value * 130 / 255) << 24);
        }

        private int overlayColor(int value) {
            if (layers != null) {
                return layerColor(activeLayer, value);
            }
            if (subjectMode) {
                // Soft detected edges show as fainter purple.
                return Color.argb(value * 130 / 255, 170, 90, 255);
            }
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
            // In subject mode the two brushes are Subject (255) and Erase (0), the same values.
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
