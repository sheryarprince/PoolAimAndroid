# AimPool Hide REA port status

Target baseline: feature/rea-clean-baseline.

Source: user's AimPool Hide third-pass native geometry report and instruction-
derived pseudocode. ARM64 raw-image RVAs only.

## Evidence-to-code mapping

| REA function | Status in this branch | Code |
|---|---|---|
| 0x10d048 | Confirmed ray walk implemented | DirectionalRaySampler.kt |
| 0x10c5e0 | Radial/density gates implemented; direction array values must be supplied | RadialMaskValidator.kt |
| 0x10d848 | Candidate origins, lateral offsets, ray prefilter implemented; exact angle schedule and profile selection omitted | AngleCandidateEnumerator.kt |
| 0x10cb68 | Primary pass candidate geometry and validator calls enumerated; secondary retry omitted | PrimaryCandidateEnumerator.kt |
| 0x112e98 | Crop/grid/scale plan implemented; scoring, fine pass and fallback details omitted | PointRefinementPlan.kt |
| JNI adapter | Coordinate transform implemented | NativeCoordinateTransform.kt |
| raw frame capture | RGBA row/pixel-stride snapshot implemented | CapturedRgbaFrame.kt |

## Deliberately absent behaviours

No guessed binary-mask thresholding, profile score, point score, category
mapping, bank-shot solver, cushion reflection, pocket solver or collision
physics is present.

The two 180-entry direction arrays are not synthesized. They must be
recovered from native initialization data and then passed into DirectionTable.

The angle enumerator requires the exact angle sequence from the native CFG.
The primary candidate enumerator exposes only its documented first pass.
The point refinement plan exposes only its documented primary grid.

## Test scope

testDebugUnitTest covers rounding, row stride, ray stopping, radial gates,
candidate geometry, point-grid geometry and JNI coordinate conversion.
Synthetic unit tests are not substitutes for captured-frame comparisons.

## Current readiness

The code is a traceable mechanics scaffold, not yet a full compatible port.
A final guideline cannot be selected until the missing scoring and candidate
retry logic are recovered. No aim line is drawn by the Android overlay while
that work remains incomplete.
