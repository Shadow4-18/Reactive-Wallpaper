package com.reactivewallpaper;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.Log;

/**
 * GPU effects over the whole finished frame (Android 13+): ripples spreading
 * from beats or taps, a color-fringe split, glitch bursts, heat haze, film
 * grain and a vignette. The frame is recorded into a RenderNode and drawn
 * through one AGSL shader.
 */
@TargetApi(33)
final class ShaderEffects {
    private static final String TAG = "ShaderEffects";
    private static final float RIPPLE_SECONDS = 1.6f;

    private static final String AGSL = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float time;\n"
            + "uniform float density;\n"
            + "uniform float2 rippleCenter;\n"
            + "uniform float rippleAge;\n"
            + "uniform float rippleAmp;\n"
            + "uniform float split;\n"
            + "uniform float glitch;\n"
            + "uniform float haze;\n"
            + "uniform float grain;\n"
            + "uniform float vignette;\n"
            + "\n"
            + "float hash(float2 p) {\n"
            + "    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 p) {\n"
            + "    float2 q = p;\n"
            // A ring of waves racing outward from the center, fading as it goes.
            + "    if (rippleAmp > 0.0) {\n"
            + "        float2 d = p - rippleCenter;\n"
            + "        float dist = length(d);\n"
            + "        float front = rippleAge * 330.0 * density;\n"
            + "        float band = exp(-abs(dist - front) / (45.0 * density));\n"
            + "        float wave = sin((dist - front) / (7.0 * density)) * band * exp(-rippleAge * 1.8);\n"
            + "        if (dist > 0.5) {\n"
            + "            q += d / dist * wave * rippleAmp * 9.0 * density;\n"
            + "        }\n"
            + "    }\n"
            + "    if (haze > 0.0) {\n"
            + "        q += haze * density * 1.6 * float2(sin(p.y / (14.0 * density) + time * 2.1),\n"
            + "                cos(p.x / (18.0 * density) + time * 1.7));\n"
            + "    }\n"
            // Glitch: random horizontal slices jump sideways for a moment.
            + "    if (glitch > 0.0) {\n"
            + "        float row = floor(p.y / (10.0 * density));\n"
            + "        float tick = floor(time * 24.0);\n"
            + "        if (hash(float2(row, tick)) < glitch * 0.3) {\n"
            + "            q.x += (hash(float2(row + 7.0, tick)) - 0.5) * 60.0 * density * glitch;\n"
            + "        }\n"
            + "    }\n"
            + "    half4 c;\n"
            + "    if (split > 0.0) {\n"
            + "        float2 o = float2(split, 0.0);\n"
            + "        half4 g = content.eval(q);\n"
            + "        c = half4(content.eval(q + o).r, g.g, content.eval(q - o).b, g.a);\n"
            + "    } else {\n"
            + "        c = content.eval(q);\n"
            + "    }\n"
            + "    if (grain > 0.0) {\n"
            + "        float n = hash(p + fract(time) * 100.0) - 0.5;\n"
            + "        c.rgb = clamp(c.rgb + half3(n * grain * 0.1), half3(0.0), half3(1.0));\n"
            + "    }\n"
            + "    if (vignette > 0.0) {\n"
            + "        float2 uv = p / size - 0.5;\n"
            + "        c.rgb *= half(clamp(1.0 - vignette * dot(uv, uv) * 1.6, 0.0, 1.0));\n"
            + "    }\n"
            + "    return c;\n"
            + "}\n";

    private final float density;
    private final RenderNode node = new RenderNode("resona-effects");
    private RuntimeShader shader;
    private boolean failed;
    private float time;
    private float rippleAge = RIPPLE_SECONDS;
    private float rippleX;
    private float rippleY;
    private float rippleStrength;
    private float lastBeat;

    ShaderEffects(float density) {
        this.density = density;
    }

    /** True if this phone can run them and any is turned on. */
    static boolean wanted(WallpaperSettings s, Canvas canvas, boolean tapRipple) {
        return Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated()
                && (s.fxRipple > 0f || s.fxSplit > 0f || s.fxGlitch > 0f || s.fxHaze > 0f
                || s.fxGrain > 0f || s.fxVignette > 0f || tapRipple);
    }

    boolean failed() {
        return failed;
    }

    /** Null if the effects shader compiles on this phone, else the compiler's message. */
    static String compileError() {
        try {
            new RuntimeShader(AGSL);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /** A tap starts a ripple at x, y. */
    void ripple(float x, float y) {
        rippleX = x;
        rippleY = y;
        rippleAge = 0f;
        rippleStrength = 1f;
    }

    /** Starts recording the frame; draw it into the returned canvas, then call {@link #end}. */
    RecordingCanvas begin(int w, int h) {
        node.setPosition(0, 0, w, h);
        return node.beginRecording(w, h);
    }

    /** Draws the recorded frame onto {@code target} through the effects. */
    void end(Canvas target, int w, int h, WallpaperSettings s, AudioAnalyzer audio, float dt, float centerY) {
        node.endRecording();
        time += dt;
        float beat = audio.beat();
        boolean hit = beat > 0.7f && lastBeat <= 0.7f;
        lastBeat = beat;
        // Strong beats send a ripple from the middle of the scene, unless a tap ripple is still running.
        if (hit && s.fxRipple > 0f && (rippleAge > 0.6f || rippleStrength < 1f)) {
            rippleX = w / 2f;
            rippleY = centerY;
            rippleAge = 0f;
            rippleStrength = s.fxRipple * (0.5f + 0.5f * audio.bass());
        }
        rippleAge += dt;
        float level = audio.level();
        if (!failed && shader == null) {
            try {
                shader = new RuntimeShader(AGSL);
            } catch (RuntimeException e) {
                Log.w(TAG, "Effects shader unavailable", e);
                failed = true;
            }
        }
        if (failed) {
            target.drawRenderNode(node);
            return;
        }
        shader.setFloatUniform("size", w, h);
        shader.setFloatUniform("time", time % 1000f);
        shader.setFloatUniform("density", density);
        shader.setFloatUniform("rippleCenter", rippleX, rippleY);
        shader.setFloatUniform("rippleAge", rippleAge);
        shader.setFloatUniform("rippleAmp", rippleAge < RIPPLE_SECONDS ? rippleStrength : 0f);
        shader.setFloatUniform("split", s.fxSplit * density * (6f * beat + 1.5f * level));
        shader.setFloatUniform("glitch", beat > 0.25f ? s.fxGlitch * beat : 0f);
        shader.setFloatUniform("haze", s.fxHaze * (0.4f + 0.6f * level));
        shader.setFloatUniform("grain", s.fxGrain);
        shader.setFloatUniform("vignette", s.fxVignette * (0.7f + 0.3f * audio.bass()));
        node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
        target.drawRenderNode(node);
    }

    /** True while a ripple is still spreading (so frames keep coming). */
    boolean isActive() {
        return rippleAge < RIPPLE_SECONDS;
    }

    void release() {
        node.discardDisplayList();
    }
}
