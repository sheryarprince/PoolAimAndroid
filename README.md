# PoolAimAndroid

Native Android floating practice-guidance overlay for pool games.

## Current build

- Android 16 / API 36 target; minimum Android 8.0 (API 26).
- Kotlin + MediaProjection screen capture.
- OpenCV 4.10 Android library.
- Local table-colour segmentation and Hough-circle proposals.
- Cue-ball candidate ranking, ball markers, ghost-ball contact geometry and direct-shot obstruction checks.
- Floating AIM toggle, minimize/reopen control and overlay style controls.
- Screen frames remain on-device.

## Current recognition limits

This is an early computer-vision prototype, not a finished Easy Victory/Cheto replacement. OpenCV currently proposes the table region and circular ball candidates. It can confuse striped balls with the cue ball, miss balls under occlusion, or fail on different table skins. The overlay suppresses the guide when it cannot find a plausible cue ball and at least one object ball.

There is **no trained pool-specific AI model bundled yet**. A reliable hybrid AI + OpenCV release requires a labelled dataset from multiple table skins, screen sizes, orientations and lighting conditions, followed by model training and on-device evaluation. The current 20–30 FPS rate is a processing target, not a verified device benchmark.

## Build

Open this repository in Android Studio with JDK 17 and Android SDK 36, then run:

```bash
gradle assembleDebug
```

The GitHub Actions workflow also builds and uploads `app-debug.apk` after pushes to `main`.

## Device test checklist

1. Install the debug APK on the target phone.
2. Grant draw-over-other-apps permission.
3. Start screen capture and accept Android's MediaProjection prompt.
4. Open a pool table in landscape orientation.
5. Toggle AIM on and inspect the detected ball count and guide stability.
6. Test different table skins and partially occluded balls.
7. Record device model, Android version, FPS and false/missed detections.

## Scope

The app is a local practice aid. It does not patch the game, read game memory, inject into the game, automate shots or attempt to evade anti-cheat systems.
