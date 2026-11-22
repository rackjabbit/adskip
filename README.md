# Ad Skip

Version 1.1.0. Personal sideloaded Android app (`com.rackjabbit.adskip`) for Hans's phone.

While a video ad is on screen in YouTube or Prime Video, Ad Skip can show a banner or a dim cover, mute media volume, and press **Skip** once that button is clickable. It watches only:

- `com.google.android.youtube`
- `com.amazon.avod.thirdpartyclient`
- `com.amazon.amazonvideo.livingroom`

The app has no internet permission and does not collect data. Detection rules stay on the phone as JSON.

`minSdk` is 26. `targetSdk` and `compileSdk` are 37.

## Build

Install JDK 17 or 21 and the Android SDK (platform 37, build-tools 37). Point `ANDROID_HOME` at the SDK, or put `sdk.dir` in a gitignored `local.properties`.

```bash
./gradlew assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Tests and lint:

```bash
./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug
```

## Install and enable

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android blocks accessibility services for sideloaded apps until restricted settings are allowed (Android 13 and newer).

1. Open **App info** for Ad Skip. Tap the three-dot menu and choose **Allow restricted settings**. Confirm with your screen lock.
2. Open **Accessibility settings**, find **Ad Skip** (on some phones it is under Installed apps), and turn it on. Allow it to perform gestures when asked.
3. Leave **Run while videos play** switched on.

Some phones (Samsung, Xiaomi, and others) hide the service behind an extra switch.

## What it does during an ad

- **Banner** sits under the status bar. **Full cover** dims the frame. The overlay does not take touches, so it does not block Skip.
- **Mute media volume** mutes only the music stream (`ADJUST_MUTE`), checks that the mute stuck, and mutes again if YouTube turns the sound back up. Back-to-back ads stay muted for a short grace period. Your previous level comes back when the ad block ends, or if the service is destroyed. A volume change you make during the ad is kept.
- Skip is pressed only when the control is enabled, visible, and clickable (the node itself or a clickable ancestor). `ACTION_CLICK` is tried first. If that returns false, Ad Skip taps the control's bounds. Presses are at least 1.2 seconds apart. A label such as "Skip in 5" is left alone, and **Skip intro** is not a skip button.

## Screen logs

When YouTube or Prime Video changes its layout, the built-in view ids can miss the new buttons. Capture a tree from the phone:

1. In Ad Skip, turn on **Write screen logs**.
2. Open YouTube or Prime Video and let an ad play.
3. Come back to Ad Skip and tap **Share latest log**.

The text file is the accessibility node tree plus whether the current rules matched, and mute lines (applied, verified, re-applied, restored). Send that file back, then update the rules with **Edit JSON** or **Import JSON**. **Restore built-in rules** puts back `app/src/main/assets/default_ad_config.json`.

## Detection rules

Each app entry in the JSON has:

- `adSignals` — view ids and text that mean an ad is on screen. `strength` `weak` (exact `Ad`, `Sponsored`, or `Advertisement`) counts only when a short `m:ss` countdown sits next to that badge.
- `skipSignals` — view ids and text for the Skip control. View ids match the resource entry, so `skip_ad_button` matches `com.google.android.youtube:id/skip_ad_button`.
- `skipNotReady` — patterns that mean the control is still counting down.
- `countdownPatterns` — capturing patterns for the remaining time.
- `standaloneCountdown` — a bare `m:ss`, used only beside an ad badge, and ignored when the view id looks like the normal player clock.

## Tests

Unit tests in `:engine` cover recorded trees (ad present, Skip visible but not clickable, Skip clickable, no ad), mute across back-to-back ads, and restore when the service stops.

## License

MIT. Copyright (c) 2026 Hans Belka. See [LICENSE](LICENSE).
