package com.reactivewallpaper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Live, audio-reactive preview of the wallpaper inside the editor. Dragging it
 * moves the picture, pinching zooms it and a double-tap resets both.
 */
public final class PreviewView extends View {
    /** Told about framing gestures, in fractions of the preview's size. */
    interface FramingListener {
        void onMove(float dxFraction, float dyFraction);

        void onZoom(float factor);

        void onReset();

        /** The finger lifted: a good moment to save. */
        void onGestureEnd();
    }

    private FramingListener framingListener;
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private boolean gesturing;
    private WallpaperRenderer renderer;
    private AudioAnalyzer analyzer;
    private WallpaperSettings settings;
    private long lastFrame;
    private boolean running;
    private TiltSensor tilt;

    public PreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float radius = 20f * getResources().getDisplayMetrics().density;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);

        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (framingListener != null) {
                    framingListener.onZoom(detector.getScaleFactor());
                    gesturing = true;
                }
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (framingListener != null && !scaler.isInProgress() && getWidth() > 0) {
                    framingListener.onMove(-distanceX / getWidth(), -distanceY / getHeight());
                    gesturing = true;
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (framingListener != null) {
                    framingListener.onReset();
                    framingListener.onGestureEnd();
                }
                return true;
            }
        });
    }

    void setFramingListener(FramingListener listener) {
        framingListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Keep the surrounding scroll view from taking the drag.
        getParent().requestDisallowInterceptTouchEvent(true);
        scaler.onTouchEvent(event);
        gestures.onTouchEvent(event);
        int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && gesturing) {
            gesturing = false;
            if (framingListener != null) {
                framingListener.onGestureEnd();
            }
        }
        return true;
    }

    void bind(WallpaperRenderer renderer, AudioAnalyzer analyzer, WallpaperSettings settings) {
        this.renderer = renderer;
        this.analyzer = analyzer;
        this.settings = settings;
        invalidate();
    }

    void setTiltSensor(TiltSensor tilt) {
        this.tilt = tilt;
    }

    void setSettings(WallpaperSettings settings) {
        this.settings = settings;
        invalidate();
    }

    void setRunning(boolean running) {
        this.running = running;
        lastFrame = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (renderer == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 1f / 60f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        analyzer.update(dt);
        if (tilt != null) {
            renderer.setTilt(tilt.x(), tilt.y());
        }
        renderer.draw(canvas, getWidth(), getHeight(), analyzer, settings, dt);
        if (running) {
            postInvalidateOnAnimation();
        }
    }
}
