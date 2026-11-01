# AimPool native initialization investigation

## Findings from the supplied AimPool 1.0.7 APK

The ARM64 library extracted from the APK is 5,535,984 bytes and has SHA-256:

`af2227b3fd92b1db5331016d43438a5fdec4a03c10f44dc6ae9b1e7aaa3a3834`

The original package declares these Java-side classes:

- `com.team.aimpool.ai.team.ntpool.AimPool`
- `com.team.aimpool.ai.team.ntpool.APoolScene`
- `com.team.aimpool.ai.team.ntpool.typool.APoolBallsInfo`
- `com.team.aimpool.ai.team.ntpool.typool.APoolBlackFrame`
- `com.team.aimpool.ai.service.PoolService`

### Recovered native API declarations

```java
public native Object findAimPoollyInfo(String sceneHandle);
public native void freeExpModel(String modelHandle);
public native void freeExpScene(String sceneHandle);
public native Object getLengthLine(String sceneHandle);
public native int putAPoolScene(Object scene);
public native void removeBlackFrame(String sceneHandle);
public native int start(Object modelOrEngine);
```

These signatures are confirmed from the DEX method table. The names alone do not establish the semantic meaning of the opaque string handles or the `Object` arguments.

### Recovered scene data contract

`APoolScene` exposes:

- Bitmap and ByteBuffer image inputs
- width and height
- image mode and pixel format
- capture mode and rotation
- width gap
- identity and name strings
- need-resize flag

This establishes that the engine's scene abstraction can carry either a Bitmap or ByteBuffer, but the enum values, required field combinations, buffer layout/stride and lifecycle are not yet known.

### Recovered output accessors

`APoolBallsInfo` exposes:

- white-ball and white-circle points
- ROI rectangle
- line angle
- two short-line angles and lengths
- scene category
- white-circle-close-to-edge flag

`APoolBlackFrame` exposes image width/height and a coordinate rectangle.

### Why initialization is not yet safely reproducible

The APK contains the protected-code runtime class `com.nmmedit.protect.NativeUtil` with native `classesInit0(int)`. The application classes have protected/obfuscated method bodies and encoded string constants. The native library's `JNI_OnLoad` region is also obfuscated: ordinary AArch64 disassembly produces invalid instruction words rather than a readable RegisterNatives table. Its dynamic symbol table does not export the individual Java native methods.

The application startup calls `AimPool.start(Object)` using an object created by an obfuscated helper class. The helper's type and construction path are part of the protected code. Consequently, the exact model handle creation, scene-handle creation, initialization order, parameter values and release lifecycle cannot be established from the readable DEX metadata alone.

## Integration decision

Do not call the native methods with guessed strings, fabricated Object instances, or arbitrary bitmap settings. That would risk process crashes and could silently produce meaningless output.

The current experimental branch's simple JNI shim is only a compatibility probe. It is not the original protected wrapper and it does not reproduce the original initialization sequence. Keep OpenCV as the working path until the protected wrapper/model setup can be recovered or the vendor provides a documented native API.

## Remaining evidence needed for a real native integration

At least one of:

1. An unprotected/original source or SDK package containing the AimPool wrapper and model initialization code.
2. A vendor-provided integration sample and the required model/configuration assets.
3. A runtime trace from the original APK on a test device showing the exact Java calls, arguments, native return values and lifecycle, captured in a controlled environment.

Until one of these is available, a claim that the native aiming engine has been integrated would be inaccurate.
