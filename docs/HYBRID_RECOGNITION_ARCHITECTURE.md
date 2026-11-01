# Hybrid live-recognition architecture

## Decision

PoolAimAndroid will use a permission-based live frame stream as its primary sensor and may fuse a second source only when a game exposes a documented, authorized interface. The app will not patch the game, inspect another process's memory, inject code, defeat anti-cheat, or attempt to bypass Android's application sandbox.

The user approved Android's one-time MediaProjection consent. Frames are processed locally in memory and are not written to storage or uploaded.

## What “native” means in this project

The existing OpenCV Android package already executes its image-processing primitives in native code behind the Java/Kotlin API. The capture path is Android MediaProjection -> ImageReader -> in-memory Bitmap -> OpenCV. This is a live stream, not a workflow based on manually saved screenshots.

A normal third-party Android app cannot directly read another app's private GPU surface or process memory. A game-specific integration is therefore optional and must be supported by a documented API/SDK or another explicitly authorized interface. The AimPool Hide APK's protected native library is not a drop-in SDK; its internal contract and input source have not been established.

## Data flow

1. **Frame source:** MediaProjection virtual display and ImageReader, with user consent.
2. **Frame normalization:** account for row/pixel stride, orientation, display changes, crop and scale; retain frames only for the duration of analysis.
3. **Visual detector:** table polygon, six pockets, ball centres/radii, cue stick and aim direction. OpenCV supplies fast proposals; a pool-specific quantized model may refine proposals after it has been trained and evaluated.
4. **Temporal tracker:** associate detections across frames, estimate motion, reject jumps and maintain confidence/uncertainty.
5. **Optional authorized state adapter:** normalize documented game-provided state into the same table-coordinate representation. No memory scraping, injection or anti-cheat bypass.
6. **Fusion:** align coordinate systems and timestamps; prefer a source only when its validity/confidence is higher. If sources disagree, mark the state uncertain rather than drawing a confident shot.
7. **Geometry and overlay:** calculate direct and bank paths only from sufficiently reliable positions; render the guidance overlay.

## Interfaces to keep sources independent

A future implementation should expose a normalized observation model with:
- timestamp in monotonic nanoseconds;
- table polygon and six pocket centres in normalized table coordinates;
- ball centre, radius, class probabilities and confidence;
- cue-stick line/aim vector and confidence when visible;
- source identifier and frame dimensions.

Each source should implement a detector adapter that returns this model. Fusion and shot geometry must not depend on MediaProjection or on a particular game.

## Performance and privacy requirements

- Target 20–30 analysed frames/second; this is a measured target, not a guarantee.
- Use a single-flight analysis queue and drop stale frames rather than build latency.
- Record capture, preprocessing, inference, tracking and render timings separately.
- Reuse buffers where safe and release every Image/Mat/Bitmap promptly.
- Keep frame pixels on-device; do not save or upload them.
- Stop capture immediately when the projection is revoked or the user stops the service.
- Show uncertainty and suppress guidance when table/ball confidence is inadequate.

## Milestones

### H1 — harden live frame ingestion
Validate frame dimensions, rotation, row stride and pixel stride; handle display changes and projection revocation; verify lifecycle cleanup and stale-frame dropping.

### H2 — improve visual detections
Replace single-colour felt assumptions with table-boundary and multi-feature proposals. Add confidence calibration and explicit unknown classes. Benchmark on-device before claiming 20–30 FPS.

### H3 — train and integrate a pool detector
Only integrate a model after obtaining appropriately licensed, representative labelled data and reporting precision/recall by class and table theme. Keep the model optional and quantized.

### H4 — temporal fusion
Add robust ball association, motion estimates and source-confidence fusion. Unit-test coordinate transforms and collision geometry.

### H5 — optional authorized game adapter
Proceed only if a supported interface is identified and documented. If no such interface exists, visual recognition remains the supported input.

## AimPool Hide research status

The inspected APK contains protected runtime components and native libraries, and its DEX includes references to Android MediaProjection APIs. Those facts do not establish which source is used for its pool detections. Its native library is not treated as a reusable or understood implementation. Further analysis must identify observable behavior and public integration points without bypassing protection or extracting proprietary implementation code.
