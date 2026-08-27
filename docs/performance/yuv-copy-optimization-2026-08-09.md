# YUV Copy Performance Optimization — 2026-08-09

> Status: HISTORICAL
>
> This document records evidence from 2026-08-09. Its test counts, machine
> conditions, and acceptance state must not be treated as the current Android
> baseline. For current test/build/device status, see
> [CURRENT_STATUS.md](../CURRENT_STATUS.md) and
> [VERIFIED_FEATURES.md](../VERIFIED_FEATURES.md).

## Scope

This change reduces CPU time in the 1280x720 camera preprocessing step that
copies CameraX YUV_420_888 planes into the packed buffer consumed by the H.264
encoder. The benchmark does not measure CameraX capture, MediaCodec encoding,
WebSocket transmission, UI rendering, memory use, power use, or end-to-end
latency on a Temi robot. A separate real-device smoke test verified App
startup, CameraX ownership, and H.264 encoder initialization without movement
commands.

## Bottleneck

The previous H264Encoder.copyPlane implementation copied each source row into a
scratch array and then copied every visible sample with a Java loop. A
1280x720 YUV 4:2:0 frame contains 1,382,400 visible samples, so the loop
visited about 41.5 million samples per second at the configured 30 FPS.

Yuv420PlaneCopier now uses ByteBuffer.get(byte[], offset, length) to copy a
packed source row directly into the encoder buffer when both pixel strides are
one. Interleaved chroma planes retain the strided path. The encoder continues
to reuse the frame and scratch buffers; the change adds no per-frame array
allocation.

## Method

The repository benchmark preserves the previous implementation as the legacy
case and invokes the production Yuv420PlaneCopier as the optimized case. Before
timing, the benchmark requires byte-for-byte equality for the complete output
frame.

Environment:

- Checkout: standalone Android project root.
- App module: app/.
- Runtime: Alibaba Dragonwell Extended Edition 21.0.11.0.11, 64-bit Server VM.
- OS architecture: amd64.
- Frame size: 1280x720 YUV 4:2:0.
- Sampling per process: 50 warm-up iterations, 15 samples, 20 frames per
  sample.
- Repetitions: three independent Java processes.

Command, run from the Android project root:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\benchmark_yuv_copy.ps1 -JavaHomePath '<JDK_21_PATH>'

## Results

The table uses the median of the three process-level results for each metric.

| Source layout | Metric | Previous | Current | Reduction |
|---|---:|---:|---:|---:|
| Interleaved chroma to planar | Median | 0.607 ms/frame | 0.206 ms/frame | 66.1% |
| Interleaved chroma to planar | p95 | 0.658 ms/frame | 0.217 ms/frame | 67.0% |
| Planar to planar | Median | 0.592 ms/frame | 0.046 ms/frame | 92.2% |
| Planar to planar | p95 | 0.738 ms/frame | 0.050 ms/frame | 93.2% |

The three processes printed output_equal=true for both layouts. Their
interleaved-layout median reductions ranged from 66.0% to 77.2%; planar-layout
median reductions ranged from 92.1% to 92.9%. At 30 FPS, the representative
interleaved-layout median equals about 18.2 ms of one CPU core per second
before the change and 6.2 ms after the change. That derived estimate applies
only to the measured copy routine.

## Verification

- Yuv420PlaneCopierTest: PASS. Four tests cover packed rows with padding,
  interleaved source bytes, target pixel stride, and invalid scratch capacity.
- Benchmark output equality: PASS for interleaved and planar source layouts.
- Full debug JVM suite: PASS, 254 tests, 0 failures, 0 errors, 0 skipped.
- Debug Java compile, APK assembly, and lint tasks: PASS in the combined Gradle
  run. The generated lint report retained two baseline errors and 16 warnings;
  Yuv420PlaneCopier introduced no lint finding.
- A debug APK was built during the original benchmark work. Its local hash and
  device endpoint are intentionally omitted from this publication document.
- A separate historical Temi smoke test covered App startup, CameraX ownership,
  and H.264 encoder initialization without movement commands, MQTT commands, or
  media actions. It is not a result of the current publication cleanup and does
  not replace fresh device acceptance.

## Limits and rollback

Desktop JVM timing does not prove the same percentage on Android Runtime or on
Temi hardware. Real-device CPU, frame cadence, thermal behavior, and
end-to-end latency improvement remain unverified by this cleanup.

The original patch boundary was limited to H264Encoder, Yuv420PlaneCopier, their
unit test, and the benchmark files. Reverting those files restores the
previous copy path without changing camera resolution, frame rate, encoder
settings, packet format, MQTT behavior, WebSocket endpoints, or robot controls.
