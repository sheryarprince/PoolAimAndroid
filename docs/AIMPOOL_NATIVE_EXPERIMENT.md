# AimPool native-engine experiment

This branch adds a minimal JNI registration shim based on the native declarations recovered from the supplied AimPool APK.

## What this test does

- Attempts to load `libapool.so` from the app's native library path.
- Resolves the expected Java shim class at `com.team.aimpool.ai.team.ntpool.AimPool`.
- Reports load/registration errors for diagnosis.

## What it does not do yet

The original native API accepts opaque `Object` scene values and string handles. The required scene/ball/frame object contracts have not been reconstructed. The probe therefore does **not** call `start`, `putAPoolScene`, or the other native methods with fabricated inputs. Doing so could crash the process and would not be a meaningful test of aiming accuracy.

## Add the authorized native binary

Place the ARM64 library extracted from the authorized AimPool APK at:

`app/src/main/jniLibs/arm64-v8a/libapool.so`

The supplied APK's ARM64 `libapool.so` was inspected to depend only on Android system libraries. The app build will package it automatically from this standard Android Gradle location. This branch does not include the binary itself.

## Test

Install the debug APK on an ARM64 Android device and inspect the native status displayed in the app. A successful load confirms ABI packaging and JNI initialization only; it does not yet prove that the aiming engine can process a live table frame.
