# Phase 3 — ball recognition and collision-aware aiming

- Preserves the original Ghost Ball Trainer IDs exactly: cue 0, solids 1–7, eight 8, stripes 9–15.
- Replaces green-only table detection with blue/green felt-region detection.
- Replaces the centre-pixel cue flag with full-disc sampling of white fraction, dark fraction, and dominant coloured pixels.
- Distinguishes cue, eight, solid, stripe, and unknown; ambiguous candidates keep a null ID instead of receiving a guessed number.
- Adds direct and one-cushion ghost-ball candidates. Each candidate checks the cue path and every leg of the object-ball path against the other detected balls.
- Moves image processing to a latest-frame worker so capture callbacks do not wait for the detector.
- Adds unit tests for the identity map, all seven base colours and stripe pairs, cue/eight identification, blue-table detection, direct aiming, and obstruction rejection.

## Initial screenshot set

The companion initial_screenshot_set.json lists the seven recovered 8 Ball Pool captures, image dimensions, table bounds, ball centres, radii, and IDs where the number is visibly readable. Entries with id: null are deliberately retained as unclassified references. The original JPGs are supplied separately in the screenshot-corpus ZIP; they are not copied into the APK.

## Limits that remain

This is a practical image-based first recognition engine, not an exact reproduction of the original protected AimPool native recognizer. The REA native mask preprocessing still lacks confirmed RGBA-to-mask channel normalization, original direction tables and profile templates. The Android solver performs geometric path-clearance checks for direct and one-cushion candidates; it does not yet simulate the trainer's complete friction, spin, cue power, cushion loss, throw, scratch and time-stepped collision physics. Device testing across live game states is still required. The detector is capped at 20 captured samples per second and uses latest-frame dropping; real FPS depends on handset speed and table visibility.

## Build verification

The build workflow runs testDebugUnitTest and assembleDebug, uploads PoolAimAndroid-phase3-debug, and prints the APK SHA-256. A successful workflow run is required before treating the APK as verified.
