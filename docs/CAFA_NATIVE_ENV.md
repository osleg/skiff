<!-- SPDX-License-Identifier: GPL-3.0-only -->
# CAFA r3: first native Skiff page, not full UI parity

The user tested r2 and reported working physical controls and audio routing, but
a flashing display and the wrong simplified UI. The display correction and the
native UI work are separate. This milestone fixes the proxy redraw defect and
implements **the actual Skiff ENV page**. HOME, SEQ, SOUND, FX and MIX remain the
previous incomplete proxy workflows. Do not call r3 a complete native Skiff UI.

## Implemented, not merely restyled

`firmware/src/cafa_native_ui.c` feeds an isolated selected-track view into the
existing Skiff `draw_head`, `draw_columns`, `draw_graph` and `draw_foot` functions.
It uses `PAGES[0]`, the real ENV parameter descriptors and `graph_adsr`. It does
not copy the card/curve renderer or draw a lookalike panel. Fonts, six themes,
large-card preference, track colour, native units and knob highlights are reused.
The header has phone tempo, transport/record, octave and absolute bar/beat. The
footer identifies CAFA and the phone sound. The native header leaves more space
for a 1024-step timeline's bar/beat. It does not index native 64-step storage with
an absolute phone step.

The phone now implements actual ATTACK, DECAY, SUSTAIN and RELEASE, integers
0..127, using Skiff's generated coefficients, Q24 stages and 32-sample Q15
interpolation. All four ENV knobs and touchscreen controls edit those values.
ENV stays selected when changing track. SELECT does nothing on ENV until a
second ENV page is implemented; the footer does not claim a usable second page.
The renderer never displays an invented ADSR curve for the previous two-stage
attack/release synth.

### What is still unsupported

- Full native HOME overview, NOTES piano roll, PATTERN, MIXER, sound browser and
  the other Skiff pages and hold/shift/layer workflows.
- ENV2/ENV DEST and tempo-synced envelopes, LFO/ARP, effects, automation/parameter
  locks, song arrangements, Skiff project import, drum/sample engines.
- Existing phone sequencing remains one note per step with a track-wide gate.
  It is not the native Skiff note-selection, velocity, tie and length workflow.

A cosmetic replacement of the other pages is not an acceptable completion of
these requirements. Their next implementation needs real state and commands.

## Wire compatibility

Version 2 is an explicit capability. The new APK sends version-2 HELLO and will
not silently downgrade. The firmware accepts versions 1 and 2, binds the version
to the session, and echoes it in ACK and INPUT. Cross-version HELLO, STATE,
HEARTBEAT and BYE cannot mutate or renew an existing session. Old firmware ignores
version 2 and stays standalone; update both matching artifacts for native ENV.
Version-1 phones still work through the corrected proxy renderer.

STATE remains exactly 92 seven-bit bytes. For version 2 only:

- Flags byte 2 bits 0..2 retain PLAY/REC/MUTE. Bits 3..5 are octave+3 (0..6);
  bit 6 is reserved zero. Valid byte values are 0..55.
- Page 3 means native ENV. Four 14-bit values at offsets 84,86,88,90 are ATK,
  DEC, SUS and REL, each 0..127. Labels are ATTACK/DECAY/SUSTAIN/RELEASE.
- Names and the remaining layout fields keep their v1 meanings.

Lease, revision, ordered input, applied-state acknowledgements and fail-closed
behaviour remain in force. See CAFA_PROTOCOL.md for the original frame layout.

## Isolation and resource design

No standalone track, parameter, step, project, preset or storage data is written
by native drawing. A temporary 2,780-byte `track_t` is constructed on the main
stack. Only selected drawing context is borrowed, then restored: `tsel`, UI,
selected track, play/record, octave, BPM and free-take visibility. Audio remains
in the phone-only ISR path while this happens. The CPU meter, master gain and
other ISR-owned song fields are not saved/restored. Standalone automation and
project-slot marks are deliberately not projected onto phone state.

The native UI cache is 396 bytes on the target (400 on the 64-bit host); the
proxy rendered-state cache is 92 bytes. New UI globals use an ordinary BSS
subsection to keep the compiler from changing the grouping/offsets of unrelated
hot DSP arrays. No DSP algorithm or budget was changed.

The compiled native draw function reserves 3,208 bytes plus saved registers;
this is within the 11.75 KiB main stack before its callees, but hardware maximum
stack depth has not been measured. Existing UI calls have their own stack use.
This is a new qualification item, not a claim of a measured stack margin.

The exact combined target built with CAFA=1, UAC_OUT=1, CDC=0 and
ENGINE_BALANCE=1 uses:

| Region | Used | Free |
|---|---:|---:|
| App flash | 581,216 | 348 |
| Data+BSS | 91,184 | 3,024 |
| Pool | 351,128 | 9,320 |
| RAM code+ramx | 29,760 | 2,752 |

Pool free includes the required 8,192-byte reserve. Every original static target
budget passes unchanged: ISR1390, STRUM139, room218, mono delay25. These are
instruction estimates, not measured hardware utilisation. Flash is extremely
close to full; additional native pages need size work, not a budget increase.

## Verification

- Cards and graph are pixel-identical to the ordinary native Skiff page across
  36 combinations of theme, card size and envelope values.
- Native rendering preserves standalone tracks, UI, song, motion and preferences;
  idle draws transfer zero pixels; hot-knob updates settle to the same pixels as
  a full redraw; native/proxy transitions and reconnection invalidate caches.
- A step-1023 playhead is used only for the native ENV header, never as a native
  pattern-array index.
- 71,100 Java-generated ADSR ticks exactly match actual `esync_coef`/`env_tick`
  Q24 levels and stage transitions in C. The phone tests also compare 4,096
  signed interpolation samples and generated tables.
- Android host suite: 61,816 assertions. APK compile and signature verification
  pass. Project v1 migration preserves a byte-verified original backup, maps old
  attack/release times to the nearest native setting, and adds DECAY0/SUSTAIN127.
  The release curve changes from linear to exponential, so migrated sounds are
  not bit-identical. No uninstall is needed; uninstall can erase local projects.
- Final balance-enabled AddressSanitizer/UndefinedBehaviorSanitizer suite passes,
  including adversarial v1/v2 isolation, all 128 flag encodings, ADSR bounds,
  native and proxy rendering, the real audio ISR, and 1,976 actual Java/C USB
  mailbox checks. Sessions cover 20.4 seconds with firmware service every
  34/102/170 ms, initial HELLO/ACK loss, return-path failure and reconnection.
- Stock UI goldens pass both with CAFA disabled and compiled in but inactive:
  3,738 pictures unchanged, 266 screens across six palettes, no lint findings or
  alignment deviations. No golden or target budget was regenerated.
- Exact-package OTA and loader simulations pass. Balance calibration retains
  the 0.401 dB engine-median spread; both configurations keep all 176 frozen
  renders, strict arithmetic checks and both quiet-tail baselines. All six
  deliberate broken-quantizer variants are rejected as expected.

Run `sh tests/run_cafa_tests.sh` after the Android fixture generator. It includes
protocol, surface, native UI, envelope parity, actual audio ISR and Java/C USB
mailbox tests. Run the target budget checker on the exact combined disassembly.
Final sanitizer/session and standalone UI results are recorded in build logs.
No physical flash, install, listening or on-device stack measurement was done by
the developer. The user's successful r2 test is not hardware qualification of r3.

## Matching artifacts and optional owner test

- Firmware: `cafa-native-env-r3-NOT-HARDWARE-QUALIFIED.fwsc`, 610,429 bytes,
  SHA256 `6428de673e5d65effc50fbbfe206530aca9f16e33d1f31911984685fb94804af`.
- Android: `cafa-native-env-0.2.0.apk`, versionCode 3, 45,459 bytes,
  SHA256 `72a954bcb409fa8ceb782b0f2f34c189297dd1c011d0307452666bed7d5fa6a6`.
  It uses the original debug signing identity. Update the installed app rather
  than uninstalling it; uninstall can erase its project.
- The package identity is still `FM-1_700` and the displayed source version is
  still v2.5.1. Those strings do not distinguish r3 from older prototypes; use
  the exact filename and SHA256. The decrypted packaged app matches the target
  build byte for byte. The balance table occurs once; LOFI is still 14637/32768
  (about -7 dB), not new per-preset normalization.

If choosing to test, keep an Editor backup and a known-good firmware package
first. This is based on newer dev, not a CAFA-only patch on Skiff v2.2. Do not
assume saved projects can be downgraded. Use stable power and a direct data
cable, disconnect external amplification/headphones and turn master down.

From the bundled `source/` directory, inspect the device with
`python3 tools/fm1_install.py --info`. To deliberately install, use
`python3 tools/fm1_install.py ../artifacts/cafa-native-env-r3-NOT-HARDWARE-QUALIFIED.fwsc`
and keep its normal interactive confirmation. Do not use `--force` or `--yes`.
No factory reset or storage format is part of this test.

Test standalone first at low volume, then use the matching APK and wait for
“FM1 synchronized.” Press ENV: this is the native page, with four true ADSR
controls. Compare phone/device changes in both directions, change tracks while
staying on ENV, and check long-pattern playback. Leave it idle, then play for
several minutes to check for flashing, link loss, audio glitches and stack
failures. Test disconnect/reconnect and verify standalone projects are intact.
Other pages still use the proxy and are not expected to match Skiff workflows.
Physical CPU load, latency, stack depth and audible transitions remain unverified.

To reproduce with the pinned compiler and SDK:

```sh
FELUCCA_CAFA=1 FELUCCA_UAC_OUT=1 FELUCCA_CDC=0 FELUCCA_ENGINE_BALANCE=1 ./build.sh
python3 tests/target_budget.py build/felucca.dis tests/target_budget.txt
bash cafa/android/tools/test-core.sh
java -cp cafa/android/build/core-tests ProtocolVectors cafa/android/build/vectors
java -cp cafa/android/build/core-tests EnvelopeVectors cafa/android/build/vectors/envelope.tsv
ASAN_OPTIONS=detect_leaks=0 CAFA_TEST_CFLAGS='-DFELUCCA_ENGINE_BALANCE=1 -fsanitize=address,undefined -fno-sanitize-recover=all' sh tests/run_cafa_tests.sh
sh tests/run_engine_balance_tests.sh
```

## Next genuine pages

1. A versioned bounded state cache: six track summaries (names/mute/level/playhead
   and visible occupancy), a selected 16/64-step note window with pitches,
   velocity, gate/ties, and typed parameter descriptors. Apply fragments atomically
   by session/revision; stale or incomplete windows must not become editable.
2. Read-only model accessors for the existing HOME/NOTES/MIXER renderers. The
   current v1 bitmap cannot determine a piano roll: different pitches serialize
   to the same bitmap. Do not fabricate missing values or overwrite standalone
   tracks to trick the renderer into drawing them.
3. Reuse the native gesture workflow with a command sink: edit/record/undo/save
   commands go to the phone, are acknowledged by model revision, and are bounded
   and idempotent. Do not simply remove `cafa_input()`'s early return: ordinary
   `ui_input()` can write local projects/presets and invoke local synthesis.
4. Differential native renderer/gesture tests, interrupted-session/project
   isolation tests, exact target budgets, and physical testing for each added page.
