# PoolAimAndroid: Hybrid Recognition Implementation Plan

## Product direction

Build a local, permission-based Android practice assistant. The app analyses the visible pool table and draws aiming guidance from detected positions. The player remains responsible for all in-game touch and shot input.

The performance target is 20–30 processed frames per second on supported devices. This is a target, not a guaranteed rate; actual throughput must be measured on the user's phone and adapted to thermal and battery limits.

## Current repository state

- Kotlin Android app with MediaProjection screen capture and a floating overlay.
- OpenCV 4.10.0 Android AAR dependency.
- OpenCV HSV/felt segmentation proposes the playable table rectangle; HoughCircles proposes ball centres and radii.
- Colour-distance filtering rejects felt-like proposals; local inner-disk/outer-ring HSV features heuristically classify cue, 8-ball, stripe and solid candidates, with a temporal cue-position prior.
- Nearest-neighbour exponential smoothing stabilizes ball proposals between frames.
- Six pocket centres can now be calibrated by touch and persisted locally; guide geometry uses those calibrated positions instead of assuming pocket centres are exactly at table-rectangle corners.
- The overlay ranks direct ghost-ball and one-cushion bank-shot proposals by path length and cut angle, and rejects paths obstructed by another detected ball.
- Capture is throttled to at most approximately 30 FPS and a single-flight background executor skips frames when analysis is busy.
- This is a classical-CV prototype. OpenCV's Android implementation executes its image primitives in native code behind the Kotlin API. No pool-specific trained AI/ONNX model is bundled; AI recognition remains blocked on an appropriately licensed, labelled dataset and model evaluation.

## Target architecture

1. **Capture:** MediaProjection + ImageReader; handle orientation and display-size changes; avoid blocking the UI thread.
2. **OpenCV preprocessing:** ROI selection, colour/edge features, perspective normalization and image-quality checks.
3. **AI detector:** A custom, quantized object-detection model for table rails, six pockets, cue ball and object balls. The model must be trained/evaluated on representative, explicitly provided or consented labelled frame data before integration. The live app processes frames in memory; users are not required to take or upload screenshots. Generic off-the-shelf object detectors are not assumed to recognize pool balls reliably.
4. **Tracking and calibration:** Associate detections across frames; estimate table plane and ball radius; smooth noisy positions; reject low-confidence frames.
5. **Geometry engine:** Ghost-ball contact point, object-ball tangent direction, direct pocket paths, collision checks and one/two/three-cushion reflection paths.
6. **Overlay renderer:** Draw only from current high-confidence detections; transform table coordinates to screen coordinates; show confidence/status and provide hide/minimize controls.
7. **Performance controller:** Measure capture, preprocessing, inference and rendering time separately. Use adaptive input resolution and frame skipping to stay within thermal/battery limits.

## Milestones and acceptance criteria

### A. Capture and preprocessing (implemented; device benchmark pending)
- App builds successfully.
- Captured frames are closed reliably and processed off the main thread.
- Sustained frame rate and processing latency are logged.
- No guessed guideline is displayed when detection is unavailable.

### B. Table and pocket detection (table rectangle prototype and manual six-pocket calibration implemented; automatic pocket detection/validation pending)
- Detect table polygon across multiple table themes and orientations.
- Manual calibration records all six pocket centres; automatic pocket recognition remains a future enhancement.
- Calibrate screen-to-table coordinates.
- Reject menu screens and partial/occluded tables.

### C. Ball detection and tracking (OpenCV circle proposals, temporal smoothing and heuristic appearance classes implemented; trained AI validation pending)
- Train/evaluate a custom detector for cue ball and object balls.
- Return class, centre, radius and confidence.
- Track balls without jumping between frames; explicitly mark uncertain detections.

### D. Shot geometry (direct-shot ghost-ball, obstruction checks and one-cushion bank-shot prototype implemented; rail/collision validation and unit tests pending)
- Unit-test ghost-ball, tangent and cushion reflection calculations.
- Compare predictions with an explicitly consented, labelled evaluation set.
- Draw direct and bank-shot guides only when geometry and detection confidence pass thresholds.

### E. Device optimization
- Benchmark 20–30 FPS on the actual Android device.
- Record average and 95th-percentile processing latency, memory and thermal behaviour.
- Degrade gracefully to lower FPS/resolution if the device cannot sustain the target.

## Safety and scope

No game APK patching, process-memory reading, injection, anti-cheat evasion, automatic shooting, matchmaking automation or claims of undetectability. Screen analysis stays on-device; no captured gameplay is uploaded by this app.

## Required test data

The AI detector needs representative, labelled gameplay frames covering cue/object balls, solids/stripes/8-ball, table skins, lighting, occlusions, pocket positions, resolutions and portrait/landscape layouts. These may be contributed as a separately consented dataset; live runtime frames remain in memory. No trained model is included yet.

See [HYBRID_RECOGNITION_ARCHITECTURE.md](HYBRID_RECOGNITION_ARCHITECTURE.md) for the agreed live-stream, source-adapter and fusion design.
