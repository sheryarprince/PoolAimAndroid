# REA-derived native engine port

This package contains independently testable ports of mechanics recovered
from the static ARM64 raw payload. It does not claim a complete AimPool Hide
frame-to-overlay pipeline while original input preparation and data remain
unknown.

## Implemented from binary evidence

- `DirectionalRaySampler`: 0x10d048 ray walk over an already prepared byte
  mask, ties-away-from-zero sampling, exact `0xff` hits, three consecutive
  misses, edge stop, and distance to last matching pixel.
- `RadialMaskValidator`: 0x10c5e0 radial and density gates. It accepts the
  required 180 direction values from the caller because their initialization
  was not recovered.
- `PrimaryCandidateEnumerator`: 0x10cb68 candidate order, truncation, bounds,
  radial validation and perpendicular retries. Its first accepted candidate
  is not the later winner-selection stage.
- `NativeAngleSchedule` and `AngleCandidateEnumerator`: observed 0x10d848
  angle/lateral schedule and ray-length prefilter. Profile winner selection
  remains unresolved.
- `PointRefinementPlan`: crop, primary grid and scale-derived radii observed
  in 0x112e98; score/fine/fallback integration remains incomplete.
- `NativeMaskGenerator`: component predicates and output-byte writes from
  0x11167c, 0x1117f0 and 0x111988, without RGB/BGR assumptions or implicit
  clearing of unmatched mask pixels.
- `NativeRadialMaskScorer`: instruction-derived 0x112cac radial score, with
  a required injected trig-pair routine and caller-prepared crop.
- `NativeTemplateScore`: method-1 normalized squared-difference formula seen
  at 0x1108d0. The surface exposes scores but does not choose a min-location
  tie or model unresolved OpenCV edge cases.
- `NativeProfileSelector`: 0x110c38's strict threshold order and literal
  categories 8, 9 and 7.
- `NativeCoordinateTransform`: observed JNI adapter scale/offset/truncation.
- `CapturedRgbaFrame`: row-stride-aware capture snapshot; not native triplet
  preparation.

## Explicitly unresolved

The original 180-entry direction tables, upstream RGBA-to-component pipeline,
mask allocation/clear lifecycle, three original template images, runtime
score threshold, indirect trig symbol, 0x10d848 profile score semantics,
scene names, JNI runtime table relocation, ARMv7 protected payload, and full
0x112e98 fallback are not guessed. Pocket, cushion, bank-shot, rebound, and
collision math are not claimed from the examined native paths.

## Validation limits

Tests use synthetic values to verify the ported arithmetic. They are not
original-game captures or native-vs-Kotlin parity evidence. See
`docs/REA_NATIVE_PORT_STATUS.md` and the Phase 2 deliverables for the
architecture and remaining gaps.

