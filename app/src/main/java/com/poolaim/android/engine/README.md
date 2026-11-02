# REA-derived native engine scaffold

This package reproduces only ARM64 behaviours supported by the supplied
AimPool Hide third-pass report. Each implementation is independently testable.

## Implemented mechanics

- DirectionalRaySampler: 0x10d048, exact 0xff mask run, FCVTAS-style
  coordinate rounding, three consecutive misses, image-edge stop and
  distance to last matching pixel.
- RadialMaskValidator: 0x10c5e0, the 180-direction / 6..8 outer band,
  5..6 inner band, 9x9 and 16x16 density gates.
- AngleCandidateEnumerator: confirmed lateral offsets and mask-ray
  measurement from 0x10d848. The angle schedule is an input and must be
  transcribed from CFG evidence; opaque profile scoring is not implemented.
- PrimaryCandidateEnumerator: first-pass candidate geometry from 0x10cb68.
  Secondary perpendicular retry order is not implemented because its exact
  schedule is not yet specified.
- PointRefinementPlan: crop, primary grid and scale-derived radii from
  0x112e98. Score evaluation, N > 8 fine-pass bounds and fallback helper are
  deliberately not implemented.
- NativeCoordinateTransform: JNI adapter scale/offset/truncate operation.
- CapturedRgbaFrame: row-stride-aware raw RGBA frame snapshot. No threshold
  or binary mask is generated.

## Explicitly not implemented

- Upstream binary-mask creation and thresholding.
- Native profile scoring in 0x1108d0 / 0x10d19c.
- Candidate score in 0x112cac.
- Complete angle scan branch schedule.
- Secondary candidate retry schedule in 0x10cb68.
- N > 8 fine refinement pass and fallback scan exact bounds.
- Named scene/category mapping.
- Bank shots, cushion reflection, pocket selection or collision physics.

These omissions are intentional. Do not substitute generic CV thresholds,
Hough circles, heuristic scoring, or speculative physics and label them as
AimPool-compatible.

## Coordinate and rounding rules

- Ray and radial pixel sampling use nearest-integer rounding with ties away
  from zero, matching FCVTAS.
- Native-to-Java point conversion divides by scale, adds output offset, then
  truncates toward zero.
- The radial direction table is supplied by the caller. Its initialization
  is not assumed to be sin/cos until separately verified.
- All addresses cited in REA notes are raw-image RVAs in the recovered,
  headerless ARM64 payload; they are not ELF VMAs.

## Validation

Run gradle testDebugUnitTest. Tests use synthetic masks designed to exercise
individual recovered rules. They do not establish detector accuracy on real
game captures. Real-frame validation remains a separate milestone.
