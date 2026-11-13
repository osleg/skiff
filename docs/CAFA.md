# CAFA prototype

Experimental, opt-in FM-1 companion mode. Current milestone: [r3 actual native ENV and redraw correction](CAFA_NATIVE_ENV.md); [r2 verification](CAFA_R2.md) is historical. Based on osleg/skiff dev commit 2d15baea6e7e94062b31dc3c59c4db23a88d7bc0. Work branch cafa/prototype-20261112. The user reported working controls and audio with r2. The developer has not flashed hardware, and r3 is not hardware-qualified.

## Ownership

Android owns its six tracks, expanded sounds and parameters, per-track patterns up to 1,024 steps, transport and audio clock. Skiff owns only a bounded view of that state and its physical controls. Existing Skiff projects, patterns and sound parameters are not replaced. A USB connection alone does not activate CAFA: HELLO plus a valid complete STATE is required. Unfinished negotiation leaves standalone playing normally.

On activation, Skiff stops transport, releases local and outgoing MIDI notes, clears effect tails, then bypasses local DSP, sequencer, click and USB capture. Its DAC takes only CAFA USB playback. The physical MASTER still sets this returned signal level, with the existing -6 dBFS full-scale ceiling. There is no returned-audio loop into USB capture. USB recording remains available for normal standalone use.

The phone publishes names, a 16-step viewport into its long pattern, playhead, four knob labels/values, and button/key LEDs. Physical button/key held states and seven raw relative encoders go to Android. Hardware calibration is respected. Short taps/repeated presses between LCD updates retain their press edges. CAFA must implement the semantics; this is not a MIDI CC map limited to stock engine parameters.

## First-slice scope

With the matching protocol-v2 phone, ENV uses the actual Skiff page renderer and true native ADSR parameters. HOME, SEQ, SOUND, FX and MIX remain incomplete proxy views, not native Skiff workflows. Version-1 phones retain the proxy ENV view. Neither mode implements all Skiff screens, modifiers, arps, chains, scenes, parameter locks or recording modes. Android documentation lists implemented mappings. Long names are retained by Android; the wire mirror is 16 printable ASCII characters and knob labels 8. The native ENV footer fits 15 sound-name characters. Long patterns are not written into the 64-step standalone structure.

Heartbeat acknowledgements confirm the last applied state revision. A 1.5-second lease, USB loss, BYE, encoder overflow or full outgoing SysEx queue ends active mode, silences voices and returns standalone stopped. Held controls are ignored until released. Existing outgoing MIDI note-offs continue retrying under queue congestion. The normal both-octaves bootloader shortcut is disabled while CAFA owns the controls, including the release guard.

## Build and verification

Use the pinned compiler/SDK in tools/ci/get_dependencies.sh and normal BUILDING.md prerequisites.

    FELUCCA_CAFA=1 FELUCCA_CDC=0 FELUCCA_ENGINE_BALANCE=1 ./build.sh

The engine-balance flag is explicit: omitting it compiles unity gain and disables standalone engine trims. The original delivered image accidentally omitted it; r2 corrects this. Phone-generated CAFA synths are independent of those standalone trims.

The development serial console is omitted to leave room for USB return and the surface client. All standalone engines, storage and normal controls remain compiled in. CAFA defaults to 0. FELUCCA_UAC_OUT defaults to CAFA's flag; it can be explicitly disabled for MIDI/surface-only bring-up. SMK-37 is rejected by this prototype build rather than silently pretending its extra controls work.

    sh tests/run_cafa_tests.sh
    ASAN_OPTIONS=detect_leaks=0 CAFA_TEST_CFLAGS='-fsanitize=address,undefined -fno-sanitize-recover=all' sh tests/run_cafa_tests.sh
    sh tests/run_cafa_usb_tests.sh
    sh tests/run_tests.sh

The leak checker is disabled only because it cannot run under the cloud container's tracer; address and undefined-behavior checks still run. Tests exercise the real firmware surface and audio block, strict parsing and 100,000 malformed frames, stale/wrapped revisions, long views, handshake and liveness, reconnect, held controls, short taps, transmit saturation, MIDI note cleanup and preservation of the standalone project. The USB suite exercises descriptor variants, reset/flush, clipping/scaling, nominal and +/-200 ppm producer clocks. See CAFA_USB.md for detailed USB tests and source licensing.

## Hardware gate before use

A fitting binary and host tests do not establish Android enumeration, phone USB host power/charging, audio routing, codec gain, latency, jitter, ISR deadlines, no clicks, or reliable reconnect on a physical FM-1. Verify at low speaker/headphone volume with a backed-up instrument. Measure end-to-end touch/key latency and playhead alignment with the actual AudioTrack buffer/USB pipeline. Test disconnect while playing, app pause/crash, denied USB/MIDI access, route change, hot replug and queue backpressure. Do not distribute this as a production firmware release.

## Measured prototype budget (2026-11-13)

Final reviewed r2 FM-1 app, CAFA and engine balance on / CDC off, official pinned JieLi 20260730.1 compiler, normal -Os plus repository size_fns pass:

| Region | Used | Limit | Free |
|---|---:|---:|---:|
| App flash | 579,732 | 581,564 | 1,832 |
| Ordinary data/BSS RAM | 90,688 | 94,208 | 3,520 |
| Audio/storage pool | 351,128 | 360,448 | 9,320 |
| RAM code plus ramx | 29,760 | 32,512 | 2,752 |

These are measured together, not sums of separate estimates. The pool still meets the existing requirement to leave 8,192 bytes unused; only 1,128 bytes remain beyond that safety allowance. The complete packaged image is 610,429 bytes because the package includes the boot/loader/layout, not just the app slot. Fitting does not qualify real-time performance or hardware safety. A hardware pass and more margin would be appropriate before production.
