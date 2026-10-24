# Resona

<img src="docs/icon.png" width="96" align="right" alt="Resona icon">

Resona is an Android live wallpaper that brings any picture to life with your
music.
Pick an image, choose a visualizer, and your home and lock screen move to whatever
music or video is playing on your phone.

## Features

- **Your own image, GIF or video** from the gallery, center-cropped to fill
  the screen and turned upright. Animated GIF/WebP needs Android 9+, video
  (muted, looping) Android 10+. Moving backgrounds glow wherever each frame is
  bright.
- **Album art mode:** show the playing song's cover (full screen, or as a card
  over a blurred copy) with all the effects, falling back to your image when
  nothing plays.
- **Rotate looks:** cycle the home screen through saved looks every few
  minutes, every new song, or on a double-tap, with a crossfade.
- **Living image:** the glowing, colorful parts of your picture light up and
  bloom with the music in their own colors. The image's dominant color follows
  the bass and its second color follows the mids and highs.
- **Idle animation:** the glow breathes, sparkles in the image's colors drift
  across it, and the picture sways slowly, so it stays alive even in silence.
- **Paint where it glows:** brush over parts of the picture that should glow
  (even dim ones, which glow in their own color) or shouldn't, with pinch to
  zoom, undo and a live glow view. The painting belongs to the image and is
  saved with looks.
- **Lock screen look:** show a different saved look while the phone is locked.
- **Export and import looks** as `.resona` files (settings plus image and
  painted glow) to back them up or share them.
- **Looks (presets):** save the current settings, optionally with the image,
  as a named look and switch between looks with one tap. Long-press a look to
  update, rename or delete it. Built-in starters: Neon glow, Halo ring, Club
  and Calm. Sync delays and the audio source belong to the phone, so looks
  don't change them.
- **Full control of every effect.** Each has its own slider (0 turns it off):
  - Glow: bass glow, treble glow, beat flare, halo size, coverage (how much of
    the image glows), swap colors
  - Idle: breathing strength and speed, sway amount and speed
  - Particles: sparkles, rising embers, falling stars, bubbles or light
    streaks, with amount, size, speed, brightness, music reaction
  - Recolor: hue shift, continuous color cycle, glow only or whole image
  - Parallax: the picture shifts with phone tilt and home-screen swipes, with
    particles in front for a sense of depth
  - Beat effects: pulse/zoom, flash, shake
  - Visualizer overlay (bars, ring, wave or none): height, opacity, bar count,
    ring position and size, colors
  - Audio: sensitivity, smoothing, beat sensitivity, phone audio or microphone
  - Sync: tap-along calibration, plus separate delays for the phone
    speaker/wired and for Bluetooth, picked automatically from where the
    sound is playing
- The preview stays on screen while you adjust. **Demo beat** lets you tune
  without playing music, and there's a one-tap reset to defaults.
- Edits are a draft: picking an image, moving sliders or loading a look only
  changes the preview. **Apply changes** puts them on your wallpaper, and
  **Discard** goes back to what the wallpaper shows. Sync delays and the audio
  source apply right away, since they describe your phone and headphones.
- Battery-friendly: audio capture and drawing stop whenever the wallpaper isn't
  visible. Battery saver (off, auto below 20% or in system battery saver, or
  always) halves the frame rate and particles, and the wallpaper can sleep
  after a chosen time without sound.

## Install

1. Open the repository's **Actions** tab, pick the latest successful
   **Build APK** run, and download the `Resona-apk` artifact (a zip
   containing `Resona.apk`).
   - Or download it from the **Releases** page, which is easier from a phone.
     To publish a new release, run the **Build APK** workflow manually
     ("Run workflow") with a version such as `v1.1`, or push a `v*` tag.
2. Copy the APK to your phone and open it. Allow "install unknown apps" for your
   browser or file manager when Android asks.
3. Open **Resona**, allow audio access, choose an image, adjust it,
   and tap **Set as wallpaper**. Later edits stay in the editor until you tap
   **Apply changes**.

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

## Sync

The app sees the audio when Android sends it to the speaker, which is earlier
than you hear it, especially over Bluetooth (often 150–300 ms).

The easiest fix is **Calibrate sync** under Audio. Your music pauses, 20 clicks
play through your current output, and you tap along to what you hear. The app
compares your taps with when it detected each click and saves the measured
delay for that output.

You can also set the **Delay** sliders by hand. The app switches between the
speaker and Bluetooth delays automatically.

## Notification access

Album art mode and "every new song" rotation need to know what's playing.
Android only shares that with apps that have notification access, so Resona
asks for it. Resona only reads the current song's title and cover; it never
reads your notifications.

## Building locally

Open the project in Android Studio, or run:

```sh
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The APK is signed with the committed `app/debug.keystore`, so every build can be
installed over the previous one. This is fine for sideloading, but use your own
key if you ever publish it to a store.
