# Reactive Wallpaper

An Android live wallpaper that turns any photo into a sound-reactive wallpaper.
Pick an image, choose a visualizer, and your home and lock screen move to whatever
music or video is playing on your phone.

## Features

- **Your own image** from the gallery, center-cropped to fill the screen and
  rotated correctly for camera photos.
- **Living image:** the glowing, colorful parts of your picture light up and
  bloom with the music in their own colors. The image's dominant color follows
  the bass and its second color follows the mids and highs.
- **Idle animation:** the glow breathes, sparkles in the image's colors drift
  across it, and the picture sways slowly, so it stays alive even in silence.
- **Full control of every effect.** Each has its own slider (0 turns it off):
  - Glow: bass glow, treble glow, beat flare, halo size, coverage (how much of
    the image glows), swap colors
  - Idle: breathing strength and speed, sway amount and speed
  - Sparkles: amount, size, speed, brightness, music reaction
  - Beat effects: pulse/zoom, flash, shake
  - Visualizer overlay (bars, ring, wave or none): height, opacity, bar count,
    ring position and size, colors
  - Audio: sensitivity, phone audio or microphone
- The preview stays on screen while you adjust. **Demo beat** lets you tune
  without playing music, and there's a one-tap reset to defaults.
- Changes apply instantly to the running wallpaper.
- Battery-friendly: audio capture and drawing stop whenever the wallpaper isn't
  visible.

## Install

1. Open the repository's **Actions** tab, pick the latest successful
   **Build APK** run, and download the `ReactiveWallpaper-apk` artifact (a zip
   containing `ReactiveWallpaper.apk`).
   - Or download it from the **Releases** page, which is easier from a phone.
     To publish a new release, run the **Build APK** workflow manually
     ("Run workflow") with a version such as `v1.1`, or push a `v*` tag.
2. Copy the APK to your phone and open it. Allow "install unknown apps" for your
   browser or file manager when Android asks.
3. Open **Reactive Wallpaper**, allow audio access, choose an image, adjust it,
   and tap **Set as wallpaper**.

To switch from your old sound-reactive wallpaper, tap **Set as wallpaper**. It
replaces whatever live wallpaper is active.

## Notes

- Android only. iPhones don't allow third-party live wallpapers.
- Android labels the permission "record audio". In the default **Phone audio**
  mode, the app only reads a frequency spectrum of the sound the phone is
  playing. Nothing is recorded or saved, and the app has no internet permission.
- Some phones with system-wide audio effects (Dolby Atmos, some Samsung sound
  settings) block the phone-audio visualizer. If the bars don't move while music
  plays, try turning those effects off, or switch to **Microphone**. The app
  also falls back to the microphone automatically if phone audio is
  unavailable.

## Building locally

Open the project in Android Studio, or run:

```sh
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The APK is signed with the committed `app/debug.keystore`, so every build can be
installed over the previous one. This is fine for sideloading, but use your own
key if you ever publish it to a store.
