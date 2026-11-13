# Original CAFA prototype verification, 2026-11-13

**Historical first-build report. Superseded by [r2 verification](CAFA_R2.md).** The first image omitted engine balance and contained the subsequently reproduced disconnect race. Its aggregate target-budget check used the standalone image, not the combined CAFA image. r2 enables balance, fixes transport, and passes budgets on its exact combined image.

This records the initial prototype checks. The later hardware-reported disconnect
and corrective regression are documented in [CAFA_DISCONNECT_FIX.md](CAFA_DISCONNECT_FIX.md).

This is a local prototype, not a production or hardware-qualified release. Nothing was pushed to GitHub or flashed to an instrument.

## Reproducible base

- Repository: https://github.com/osleg/skiff
- Requested default `dev` base: `2d15baea6e7e94062b31dc3c59c4db23a88d7bc0`.
- Local work branch: `cafa/prototype-20261112`.
- The delivery includes a complete source snapshot and a binary-capable Git patch against that exact base. The patch was checked and applied to a separate pristine base, then every resulting file was compared with the snapshot. `dev` was not modified.

## Passed

- Final Android debug APK compiled with official API 35 / Build Tools 35.0.0 and its signature verified. Minimum Android API 26. The manual build was used; the supplied Gradle build was not run.
- Android core: **35,298 assertions**, including a 16,385-state acknowledgment revision wrap sweep, controls, three synths, sample-based sequencing, long patterns, persistence, buffered playback/recording position, and disconnect leases.
- Actual Java-generated HELLO/STATE bytes accepted by the production firmware C parser: session 42, track 6, 1,024-step pattern, final viewport 1009–1024, note at step 1024, names and knob values.
- Firmware CAFA protocol, surface and audio tests pass under AddressSanitizer and UndefinedBehaviorSanitizer with leak detection disabled. Coverage includes 100,000 malformed frames, state/revision/lease wrapping, taps and repeated key presses, encoder deltas, queue overflow, outgoing MIDI note cleanup, local-project preservation, audio takeover, and silent standalone restoration. This is not a sanitizer certification of all pre-existing synthesis code.
- USB return tests under UndefinedBehaviorSanitizer: 16 CAFA descriptor variants, each with synthetic 60-second nominal and ±200 ppm streams; zero underruns/overruns, maximum fill 308/1024. Also reset, flush, format rejection, PCM scaling, gain, overflow and counter wrap. All 64 stock descriptor variants pass.
- Existing `sh tests/run_tests.sh` aggregate completed with **ALL HOST TESTS PASSED**. 3,738 UI goldens unchanged; 266 screens × 6 palettes; 2,173,109 alignment measurements with no deviation of 1 px or more. 176 engine golden renders unchanged in each of the default, threaded split, and CPU2-disabled runs, with no health, voice/routing or crash failures.
- Final combined FM-1 target compiled and packaged, with CAFA and USB return enabled and CDC disabled. A normal standalone target also compiled and packaged successfully. No standalone engine was removed.

## Combined target resource use

| Region | Used | Limit | Free |
|---|---:|---:|---:|
| App flash | 578,972 B | 581,564 B | 2,592 B |
| Data + BSS | 90,688 B | 94,208 B | 3,520 B |
| Audio/storage pool | 351,128 B | 360,448 B | 9,320 B |
| RAM code + ramx | 29,420 B | 32,512 B | 3,092 B |

The pool's free space includes the repository's required 8,192-byte safety reserve, leaving 1,128 bytes beyond it. The complete FM-1 package is 610,429 bytes, including loader/layout. These are combined measurements, not additive estimates from separate experiments.

## Explicitly unverified or skipped

- No emulator or physical Android launch, FM-1 flashing, USB enumeration/routing, physical control test, sustained audio load, DAC output, interrupt deadline, clock drift measurement, latency measurement or phone host-power test.
- The stock aggregate's optional sanitizer probe hit the environment's LeakSanitizer/ptrace limitation and skipped that section. Dedicated CAFA ASan/UBSan checks separately passed with `ASAN_OPTIONS=detect_leaks=0`; leak checking itself was not performed.
- Optional DaisySP reference, Emscripten browser emulator and official FM-1/SMK vendor-image fixtures were unavailable. Host instruction-counter timing was unavailable. Static target instruction/disassembly checks are not measured CPU utilization.
- The first target packaging attempt omitted `AC79_SDK`; target compilation and size checks passed, but packaging failed. Packaging the same app with the pinned SDK and `--product FM-1_700` then succeeded. The original failure log and successful packaging log are both retained.
- Android UI/lifecycle behavior is source-reviewed, not device-tested. Java AudioTrack and a synchronized model are a prototype architecture, not a verified low-latency realtime engine. Rendered-position history accounts for the AudioTrack queue but not an empirically calibrated downstream USB/DAC delay.
- The firmware preview is produced by the real host renderer, not a photograph of a physical display or an Android screenshot.

## Commands

Follow root `BUILDING.md` and use the compiler and SDK pinned in `tools/ci/get_dependencies.sh`.

```sh
FELUCCA_CAFA=1 FELUCCA_CDC=0 ./build.sh
(cd cafa/android && ./tools/test-core.sh && java -cp build/core-tests ProtocolVectors build/vectors)
ASAN_OPTIONS=detect_leaks=0 CAFA_TEST_CFLAGS='-fsanitize=address,undefined -fno-sanitize-recover=all' sh tests/run_cafa_tests.sh
CAFA_TEST_CFLAGS='-fsanitize=undefined -fno-sanitize-recover=all' sh tests/run_cafa_usb_tests.sh
FELUCCA_CAFA=0 ./build.sh
ASAN_OPTIONS=detect_leaks=0 sh tests/run_tests.sh
```

For Android APK prerequisites and exact manual build invocation see `cafa/android/README.md`. The first-slice limitations, control map and hardware checklist are there and in `docs/CAFA.md` and `docs/CAFA_USB.md`. The APK is locally debug-signed; the private debug key is deliberately not included. A rebuild with a different key cannot update the same installed app without a separate installation/uninstall.
