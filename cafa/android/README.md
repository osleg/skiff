<!-- SPDX-License-Identifier: GPL-3.0-only -->
# CAFA Android 0.2.0

A first vertical slice, not a full Skiff replacement. The phone owns the project,
sequencer clock and synthesis. Touch controls and the FM1 edit that same state.
The companion firmware is required for physical controls/display and USB return.

## Implemented

- Six tracks with stable indices, independent pattern lengths of 1–1024 steps,
  full phone-side track names, shortened printable names on FM1, and 16-step pages.
- Three new phone synths: seven-oscillator detuned saw, two-operator FM glass,
  and a variable-harmonic additive cloud. Up to 32 voices total with voice stealing.
- One note per sequencer step; polyphonic live keyboard. Sample-count sequencing
  runs in the audio render thread, not a UI timer. Rendered position history is used
  to compensate the display playhead for the AudioTrack queue.
- Sound parameters, full native Skiff attack/decay/sustain/release, volume, pan and
  gate on the touchscreen. MIDI knobs and touch sliders change the same values.
  The phone uses Skiff's actual generated time/coefficient tables, Q24 envelope
  stage rules and 32-sample Q15 interpolation. This is not a relabeled AR envelope.
- The matching firmware draws ENV with the actual native Skiff header, four
  columns, ADSR graph and footer. ENV uses native 0–127 values and native time/
  percent formatting. SYNC is off. Only ENV uses this native projection so far;
  the other supported pages still use the prototype layout.
- Local versioned project save/reopen, including long patterns and all six tracks.
  Save is atomic. Reopening is always stopped; recording is not restored.
- USB MIDI device selection and a CAFA v2 session with snapshot synchronization,
  applied-state and heartbeat acknowledgments, input serial checking, and fail-closed disconnects.
- Explicit audio device selection, including a deliberately selected phone-speaker
  preview. USB routing must be observed through `getRoutedDevice()`, not merely
  accepted by `setPreferredDevice()`. Route loss, focus loss, pause, detach and MIDI
  failures stop playback and close the CAFA session. No background playback.

## Not implemented / not yet proved

No ENV DEST/ENV2, tempo-synced envelopes, Skiff project import, samples, drum kits,
FX, LFO, arpeggiator, scenes, song
arrangement, parameter locks or full Skiff UI parity. The FX page says NO FX and
its physical knobs do nothing. This is an Android source/APK build plus host
verification, not a physical-device certification. This corrected build has not been launched on
an Android device or emulator, and USB audio return, FM1 takeover/recovery,
phone routing, touch layout and round-trip playing latency require hardware tests.

The prototype uses Java AudioTrack rather than AAudio/Oboe and a bounded,
synchronized model shared with the UI. MIDI input is dispatched to the main
thread. Scheduling is sample-based once audio is rendered, but live controls can
be delayed by the output buffer and UI/disk work. There is no measured latency
claim or production realtime guarantee. Android may resample even when the
AudioTrack format is 44.1 kHz. Test on the actual phone and FM1 before relying on it.

## Native ENV compatibility and saved projects

Use this APK only with the matching **native-ENV protocol-v2 firmware**. The
HELLO/ACK frame version negotiates those semantics; a v1 ACK cannot enable the
session, and there is no silent v1 fallback. This avoids interpreting four native
ADSR values as the old attack/release/gate/volume controls. Both sides echo the
negotiated version. The 92-byte STATE carries the same native ADSR values used by
the phone audio engine, plus transport, track, octave and playhead information.

Each new track defaults to native ADSR values 10 / 70 / 90 / 60. A/D/R map from
1 ms to 10 seconds; attack is linear, decay and release are exponential. The
native time display is rounded, so adjacent low values may display the same time.
Decay/release times represent about 99% of the change, not time until hard silence.
Sustain follows native S/128 audio amplitude while its UI displays S/127 percent.
The synth engines themselves remain CAFA phone engines, not Skiff engine emulations.

Project format v2 persists the four native values. Existing v1 projects are
migrated on load: pattern data, track names, engines, volume, pan and gate are
preserved, old A/R times map to the closest native displayed times, decay is set
to zero and sustain to 127. The release shape necessarily changes from the old
linear envelope to native exponential, so old sounds are not bit-identical.
Before any migrated autosave, the app preserves and byte-verifies the exact v1
file as a private `project-v1-before-adsr.cafa` backup. If that name contains a
partial or different original, a numbered backup is created without overwriting
it. A failed backup or unreadable project disables autosave. The older APK cannot
read v2 saves; keep the backup if rolling back. No files are sent over the network.

## Build

Android 8.0/API 26 or newer; compile/target API 35. No third-party application
libraries or network permissions. The source is GPL-3.0-only, under the root LICENSE.

Open this directory in Android Studio with a compatible JDK, Android SDK 35,
Build Tools 35.0.0 and Gradle 8.9/AGP 8.7.3, or run the dependency-free manual build:

```sh
ANDROID_JAR=/path/to/android-sdk/platforms/android-35/android.jar \
ANDROID_BUILD_TOOLS=/path/to/android-sdk/build-tools/35.0.0 \
./tools/build-apk.sh
```

The manual script uses a JDK (`java`, its compiler module and `keytool`) and
Python 3. It compiles Java, runs Google's D8 and aapt2, aligns the package, and
signs with a local debug key. Output: `build/manual/cafa-debug.apk`.
The default local key is `~/.android/cafa-debug.keystore`; override with
`CAFA_DEBUG_KEY`. Keep that key if rebuilding a package intended to update the
same installation. Never include the debug key in source/delivery archives. The
public Android debug password is not a release credential. This package is not
signed for store release. The Gradle path is supplied but was not run in this build.

Official SDK packages used for the verified manual build:

- `https://dl.google.com/android/repository/platform-35_r02.zip`
  SHA-1 from Google's package metadata: `0bb560a90a7a2cbd0dd8348224d518b638fe7949`
- `https://dl.google.com/android/repository/build-tools_r35_linux.zip`
  SHA-1 from Google's package metadata: `2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc`

Both downloaded packages matched the official metadata before use. SDK binaries
are build prerequisites, not bundled with CAFA source.

## Tests

```sh
./tools/test-core.sh
java -cp build/core-tests ProtocolVectors build/vectors
java -cp build/core-tests EnvelopeVectors build/vectors/envelope.tsv
# From the repository root, after building its probe:
build/cafa-tests/wire-probe \
  cafa/android/build/vectors/hello.syx cafa/android/build/vectors/state.syx
sh tests/run_cafa_link_tests.sh
```

The deterministic suite passes 61,816 assertions, including a 16,385-state
acknowledgment wrap sweep. It covers framing, fragmented MIDI/realtime bytes,
malformed input, revision wrap, save/reopen and corrupt/truncated files, long
patterns, first-input encoder motion, duplicate/gapped input, original-track note
release, engine output, panic/mute, bounded voices, block-size-independent sample
timing, queued playhead position and recording through buffered output, tempo changes, a
1024-step loop, session mismatch, stale/future/repeated heartbeat acknowledgments,
revision wrap and lease expiry. The firmware C parser
also accepts actual Java-emitted session/state vectors for the final 16-step page.
The r2 transport baseline passed a further 1,976 real Java/C mailbox checks cover pacing, applied-state acknowledgments, loss recovery and reconnect. See `../../docs/CAFA_DISCONNECT_FIX.md`. These host checks are separate from Android/device integration tests.

Native ENV checks cover entering ENV from the sequencer, retaining ENV while
changing tracks, all four physical knobs and native clamp ranges, SELECT staying
on ENV until ENV2 exists, octave flags, v2-only session acceptance, native project
save/reopen, v1 migration and complete/distinct/partial backup handling. The 384
Java coefficient/display table entries are checked against the actual Skiff table
generator on every core test run. A native C probe using Skiff's real `env_tick`
and `esync_coef` passes **71,100 Java-generated Q24 level/stage comparisons**,
including minimum/maximum times, early release and live parameter changes. The
Android suite also checks 4,096 signed sample-ramp interpolation values and verifies
that sustain changes actual rendered PCM. The firmware native cards/ADSR graph
pixel-parity test passes separately; this does not establish physical OLED timing
or whole-Skiff UI parity.

The Java/C session test also runs the actual Java link against the firmware's
production USB-MIDI parser, one-frame receive mailbox and CAFA surface. It covers
20.4-second sessions with firmware service intervals of 34, 102 and 170 ms,
lost initial HELLO or ACK, return-path loss, safe timeout and reconnection.
The original transport failed this test at 1530 ms: STATE immediately followed
by HEARTBEAT filled the mailbox with STATE and dropped the only ACK trigger.
Version 0.1.1 paces frames, retries HELLO, and the matching firmware acknowledges
each accepted STATE. See [the disconnect correction](../../docs/CAFA_DISCONNECT_FIX.md).

## First device check

1. Install the debug APK yourself on a compatible Android phone. Start with low
   speaker/headphone/FM1 volume. Firmware flashing is a separate user action.
2. Open CAFA. For an isolated preview choose **Phone speaker preview**, then
   **Start audio**. Wait for **Verified output**. Play the touch keyboard and edit
   a pattern. The app does not silently switch to a phone speaker on USB failure.
3. With the companion firmware running, connect phone host/OTG to FM1 using a
   data-capable cable. Choose the named FM1 USB audio output and start audio.
   Choose its bidirectional USB MIDI entry and tap **Connect FM1**. Do not use an
   unrelated USB MIDI endpoint. The **FM1 synchronized** message requires an
   acknowledgment of an applied STATE, not just USB enumeration.
4. Press ENV. Confirm the real Skiff ENV layout, ATTACK / DECAY / SUSTAIN / RELEASE
   columns and graph appear. Turn all four knobs and compare the same time/percent
   values on the phone. Hold a note, change sustain, release early, and compare the
   sound to the envelope shape. Change track while on ENV; it should stay on ENV
   and show that track's values. SELECT intentionally does nothing on ENV because
   ENV2 is not implemented. Verify the octave/tempo/transport header stays in sync.
5. Extend a track to 128 or 1024 steps. Turn SELECT on the SEQ page to later pages.
   Toggle physical white keys and verify the same steps change on the phone.
   Rename the track and select each sound from either interface. Check both views.
6. Save, close and reopen. Verify long patterns and parameters survive while
   transport remains stopped. Background/close should return FM1 to standalone
   stopped; reconnect requires a new session. Repeat while playing, holding a note,
   changing track, and turning a knob. Confirm no stale key or stuck note survives.
7. Unplug and reconnect. Test USB route loss and app focus loss. Confirm the phone
   stops and FM1 returns to local mode within its 1500 ms lease. Measure live-note
   latency and sustained audio stability before treating this as usable hardware.

## Physical subset

| FM1 control | CAFA behavior |
|---|---|
| ALGORITHM | Select track 1–6 |
| PRESETS | Select one of the three phone engines |
| SELECT on SEQ | Move through 16-step pages |
| SELECT on ENV | No-op; ENV2 is not implemented |
| SELECT on other pages | Select HOME / SEQ / SOUND / ENV / FX / MIX |
| HOME, SEQ, SEL/EDIT, ENV, GLO | Select corresponding page |
| PLAY, REC, OCT−/OCT+, SAVE | Transport, recording, octave, local phone save |
| White keys on SEQ | Toggle the corresponding visible step |
| Other keys / other pages | Play the corresponding F3–G5 note, plus octave |
| Knobs on HOME/SOUND | Two engine controls plus shared native attack/release |
| Knobs on SEQ | New-note pitch, length in 16-step increments, gate, BPM |
| Knobs on ENV | Native attack, decay, sustain, release (0–127) |
| Knobs on MIX | Volume, pan, mute, BPM |
| FX / LFO / ARP | Explicitly unimplemented in this slice |
| Physical MASTER | Firmware USB-return gain |

Full wire layout and firmware behavior are in `../../docs/CAFA_PROTOCOL.md` and
`../../docs/CAFA.md`.
