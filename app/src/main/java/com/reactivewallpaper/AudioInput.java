package com.reactivewallpaper;

/** A source of audio spectrum data that feeds an {@link AudioAnalyzer}. */
interface AudioInput {
    /** Starts capturing. Returns false if the source is unavailable on this device. */
    boolean start();

    void stop();
}
