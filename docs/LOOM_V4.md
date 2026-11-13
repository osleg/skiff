# Loom v4 native companion

Experimental FM-1 firmware, not flashed or hardware-qualified. The phone owns synthesis and sequencing. The firmware uses existing Skiff drawing primitives and native BLOCKS header/cards/footer while mapping the phone's authoritative snapshot. It restores every borrowed UI/transport field after drawing and never copies phone state into standalone project data.

## Compatibility and payload

The frame and 92-byte state layout are unchanged. Negotiation must agree on a single wire version; other-version frames cannot renew the lease.

- v1: six tracks, six proxy pages, BPM 20–300, flags 0–7.
- v2: same with native Skiff ENV and 0–127 ENV values; octave encoded in flags 0–55.
- v3: eight tracks, six proxy pages, BPM 12–300, flags 0–7.
- v4: eight tracks, nine native pages, BPM 12–300, flags 0–55. Bits 0/1/2 mean play/record/mute; bits 3–5 encode octave +3 (0–6).

Pages: HOME 0, SEQ 1, SOUND 2, ENV 3, FX 4, MIX 5, LFO 6, ARP 7, PATTERN 8. Values remain four u14 integers; labels remain four eight-character ASCII fields. ENV values are bounded to percentages 0–100. Other values retain the app-provided label and scale. OCT+3, INV+2, SWING+23, GATE x10 and RATE x2 are decoded for display. Percentage suffixes are shown as units. Boolean controls show ON/OFF; FX UNITY is dimmed and read-only.

## Honest display limits

The protocol carries one 16-step occupancy bitmap, not piano-roll pitches, per-note durations, audio samples or all eight track waveforms. Other pages show this bitmap with its viewport and playhead. No inferred note pitches are drawn. ENV PREVIEW models Loom's common `engines/Adsr.h` controls: cubic attack (2 seconds maximum), decay/release time constants (3 seconds maximum), exponential tails and normalized sustain. This small integer preview approximates the curve and assumes a sustain dwell/key release. It is not measured audio, a measured note gate or an exact SoundFont output-amplitude graph. The current phone app does not implement record-from-hardware.

## Build

```
. ../skiff-ci-deps/paths.env
export JIELI_TOOLCHAIN AC79_SDK
FELUCCA_FACTORY_SAMPLES=0 FELUCCA_CAFA=1 FELUCCA_UAC_OUT=1 FELUCCA_CDC=0 FELUCCA_ENGINE_BALANCE=1 ./build.sh
```

The new optional `FELUCCA_FACTORY_SAMPLES=0` omits compiled factory PIANO/FLUTE/SAX sets (including aliases) to make flash room. Assets remain in source. Standalone user sample storage/slots and upload support are retained, as are the built-in SLICE BREAK/FUNK loops. Factory sample-dependent sounds lose their sampled material and use the existing no-sample fallback. Defaults remain unchanged when this flag is absent.

## Verification

`tests/run_cafa_tests.sh` includes version bounds, malformed frames, lease/revision isolation, all nine v4 pages in all palettes and card sizes, T8, native state isolation, idle no-write and incremental/full redraw equivalence. Existing v2 native pixel parity remains covered. Set `LOOM_V4_FIXTURES` to the Loom app's `app/build/cafa-model-vectors` to verify and render its actual 72 model snapshots plus HELLO/BYE and 12/300 BPM fixtures. Use `ASAN_OPTIONS=detect_leaks=0 CAFA_TEST_CFLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer'` for the sandbox sanitizer run (LeakSanitizer cannot run under its tracer).

Original target budgets must pass without changing `tests/target_budget.txt`. Hardware USB playback, control latency, LCD refresh timing, audio quality and update recovery still require a backed-up real FM-1 test. This build does not flash anything.
