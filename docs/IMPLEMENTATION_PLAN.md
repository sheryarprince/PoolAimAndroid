# PoolAimAndroid: Hybrid Recognition Implementation Plan

## Product direction

Build a local, permission-based Android practice assistant. The app analyses the visible pool table and draws aiming guidance from detected positions. The player remains responsible for all in-game touch and shot input.

The performance target is 20–30 processed frames per second on supported devices. This is a target, not a guaranteed rate; actual throughput must be measured on the user's phone and adapted to thermal and battery limits.

## Current repository state

- Kotlin Android app with MediaProjection screen capture and a floating overlay.
- OpenCV 4.10.0 Android AAR dependency.
- OpenCV preprocessing pipeline converts captured RGBA frames to grayscale, downsizes to a maximum 960-pixel long edge, blurs and computes Canny edges.
- Capture frames are throttled to at most approximately 30 FPS and processed on a single background executor.
- Placeholder aiming geometry is disabled until validated live detections exist. The current preprocessing stage does not identify balls, pockets or the table.

## Target architecture

1. **Capture:** MediaProjection + ImageReader; handle orientation and display-size changes; avoid blocking the UI thread.
2. **OpenCV preprocessing:** ROI selection, colour/edge features, perspective normalization and image-quality checks.
3. **AI detector:** A custom, quantized object-detection model for table rails, six pockets, cue ball and object balls. The model must be trained/evaluated on representative screenshots before integration. Generic off-the-shelf object detectors are not assumed to recognize pool balls reliably.
4. **Tracking and calibration:** Associate detections across frames; estimate table plane and ball radius; smooth noisy positions; reject low-confidence frames.
5. **Geometry engine:** Ghost-ball contact point, object-ball tangent direction, direct pocket paths, collision checks and one/two/three-cushion reflection paths.
6. **Overlay renderer:** Draw only from current high-confidence detections; transform table coordinates to screen coordinates; show confidence/status and provide hide/minimize controls.
7. **Performance controller:** Measure capture, preprocessing, inference and rendering time separately. Use adaptive input resolution and frame skipping to stay within thermal/battery limits.

## Milestones and acceptance criteria

### A. Capture and preprocessing (in progress)
- App builds successfully.
- Captured frames are closed reliably and processed off the main thread.
- Sustained frame rate and processing latency are logged.
- No guessed guideline is displayed when detection is unavailable.

### B. Table and pocket detection
- Detect table polygon and six pocket centres across multiple table themes, orientations and screen sizes.
- Calibrate screen-to-table coordinates.
- Reject menu screens and partial/occluded tables.

### C. Ball detection and tracking
- Train/evaluate a custom detector for cue ball and object balls.
- Return class, centre, radius and confidence.
- Track balls without jumping between frames; explicitly mark uncertain detections.

### D. Shot geometry
- Unit-test ghost-ball, tangent and cushion reflection calculations.
- Compare predictions with labelled practice screenshots.
- Draw direct and bank-shot guides only when geometry and detection confidence pass thresholds.

### E. Device optimization
- Benchmark 20–30 FPS on the actual Android device.
- Record average and 95th-percentile processing latency, memory and thermal behaviour.
- Degrade gracefully to lower FPS/resolution if the device cannot sustain the target.

## Safety and scope

No game APK patching, process-memory reading, injection, anti-cheat evasion, automatic shooting, matchmaking automation or claims of undetectability. Screen analysis stays on-device; no captured gameplay is uploaded by this app.

## Required test data

The AI detector needs representative, labelled gameplay screenshots covering cue/object balls, solids/stripes/8-ball, table skins, lighting, occlusions, pocket positions, resolutions and portrait/landscape layouts. No trained model is included yet.
