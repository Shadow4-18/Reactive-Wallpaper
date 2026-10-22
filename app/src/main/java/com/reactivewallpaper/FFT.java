package com.reactivewallpaper;

/** Minimal in-place radix-2 FFT. */
final class FFT {
    private FFT() {}

    /** Transforms re/im in place. Length must be a power of two. */
    static void transform(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            float wRe = (float) Math.cos(angle);
            float wIm = (float) Math.sin(angle);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                float curRe = 1f;
                float curIm = 0f;
                for (int k = 0; k < half; k++) {
                    int a = i + k;
                    int b = a + half;
                    float tRe = re[b] * curRe - im[b] * curIm;
                    float tIm = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - tRe;
                    im[b] = im[a] - tIm;
                    re[a] += tRe;
                    im[a] += tIm;
                    float nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
