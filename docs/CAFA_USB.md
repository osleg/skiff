# CAFA USB audio return prototype

This is a local, unflashed prototype. Android enumeration, simultaneous MIDI/audio,
real USB packet timing, analogue sound quality and device CPU load have **not** been
verified. Host tests are not a hardware qualification or a reason to flash it.

## Build and routing contract

- `FELUCCA_UAC_OUT` defaults to `FELUCCA_CAFA`, which defaults to zero in the USB
  driver. Playback also requires `FELUCCA_UAC`. Ordinary builds and loaders keep
  their previous recording/MIDI descriptors, without a playback endpoint.
- Playback advertises UAC1 PCM, **44,100 Hz, stereo, signed 16-bit little-endian**
  only. EP4 OUT (`0x04`) is adaptive isochronous, one packet per millisecond,
  maximum 196 bytes. EP4 IN (`0x84`) remains the separate recording endpoint,
  advertising the existing 44.1/48 kHz formats. MIDI and optional CDC remain.
- `uac_play(out, n, gain_q12)` **adds Q23 samples** to interleaved 32-bit output
  words; it does not clear or clip the destination. Gain 4096 maps PCM16 full
  scale to DAC24 full scale. CAFA clears the output then calls it with
  `song.master_q12 / 2`: the half factor preserves Skiff's normal -6 dBFS output
  ceiling (`OUT_SHIFT=7`) and makes the MASTER control effective. Zero gain still
  consumes the stream. This is not a general-purpose saturating mixer.
- The CAFA audio integration bypasses local synthesis and effects. It must not
  feed the USB return into `uac_tap`, avoiding accidental host feedback. This
  driver does not add a USB loopback route. The existing recording descriptor
  does not imply a new CAFA recording source.

## Buffer and clock handling

The 1024-frame ring uses 4096 bytes; the DMA RX buffer uses 200 bytes. The target
fill is 256 frames, about 5.8 ms of source audio. Render halves contain 128
frames. This is a buffer contribution, **not** end-to-end Android latency.

Linear interpolation uses a Q16 position and a fixed-point fill controller. The
nominal step is 65510 (44.1 kHz source versus the approximately 44,117.65 Hz DAC
clock). A smoothed fill error adjusts it by at most 400 Q16 units. This is clock
drift accommodation, not high-quality arbitrary sample-rate conversion; a host
must not send a 48 kHz playback stream.

Playback waits for the target fill before starting. When late or overfilled at
startup it uses the newest target-sized window. An underrun leaves the caller's
output unchanged, discards the remaining partial buffer and waits for a fresh
target fill. A full ring drops an incoming packet, never overwriting unread
frames. Malformed (non-frame-sized or oversized) and inactive packets are
ignored. Ring counters wrap using unsigned arithmetic.

Stream changes, configuration changes and bus resets invalidate buffered audio.
`uac_play_flush()` additionally discards buffered playback without changing the
host's alternate setting, for entering/leaving CAFA. Call it in the audio
consumer context, or from main with interrupts excluded so that it cannot run
concurrently with `uac_play`.
A producer/consumer generation handshake keeps TIMER5's reset from rewriting the
audio ISR's read index while nested in it. Packets following the reset use a new
write-position floor until the audio ISR acknowledges the generation. A render
already in progress can finish its current block; this does not promise a
sample-accurate instantaneous mute on disconnect. Hardware-level DMA/FIFO reset
and request processing still need physical-device testing.

## Verification

Run from the repository root:

```
tests/run_cafa_usb_tests.sh
CAFA_TEST_CFLAGS='-fsanitize=undefined -fno-sanitize-recover=all' tests/run_cafa_usb_tests.sh
```

The dedicated suite covers:

- 16 CAFA descriptor combinations: CDC included/excluded and shown/hidden,
  across all four USB layouts. It checks total lengths, interface numbering,
  AC collection and class-descriptor length, IAD ranges, endpoint uniqueness,
  stereo16 playback at only 44.1 kHz, and unchanged recording rates.
- Three 60-second synthetic streams per combination, using nominal DAC rate
  and ±200 ppm offsets. Observed maximum fill: 308/1024; zero under/overruns.
- Priming, underrun/recovery, mute consumption, stop/start without stale audio,
  repeated reset before rendering, unconfigured/inactive packet rejection,
  malformed packets, deliberate overflow, counter wrap, PCM extrema and
  signed interpolation, and the add-to-output gain convention.
- Mode-transition flush with the same host alternate setting persisting.
- 64 stock descriptor combinations, checked against the repository's existing
  byte-level compatibility tests with CAFA disabled.

The host tests invoke the production packet and resampler code. They do not
simulate the actual USB controller registers, Android USB stack or interrupt
preemption instruction by instruction. Target builds, memory budgets and the
wider firmware regressions are separate checks.

## Source and licence provenance

Playback descriptor macros, endpoint setup and the original ring/control design
were adapted from `hugelton/felucca-bsp`, commit
`d18879ba557531f921752780607bf1a882dc801a`, `chip/usb.c`:

https://github.com/hugelton/felucca-bsp/blob/d18879ba557531f921752780607bf1a882dc801a/chip/usb.c

That source's notice is MPL-2.0, Copyright (C) 2026 Leo Kuroshita
(@kurogedelic), Hügelton Instruments, and Copyright (C) 2026 Kerem Kilic (Ellic
Studio), USB host integration. Its separate MIDI clock/transport credit names
keremimo, under MPL-2.0 by agreement in BSP PR #3. This port imports the playback
subset, not the BSP's host-mode or new MIDI clock/transport implementation.
Skiff's existing GPL-3.0-only file notice remains; the playback origin and its
original licence/copyright notice are retained in `firmware/src/usb.c`.

Local changes replace floating-point playback interpolation/control with fixed
point, reduce the ring from 2048 to 1024 frames, retain a 256-frame fill target,
add reset-generation handling and malformed-packet rejection, and keep the new
playback interface off by default outside CAFA. No BSP host-mode or mass-storage
implementation is included.

The pinned USB source contains no Exhibit B or “Incompatible With Secondary
Licenses” notice. The pinned repository LICENSE is the standard MPL-2.0 text,
including the *template* Exhibit B; that template is not itself an attached
incompatibility designation. Its exact text is included at
`LICENSES/MPL-2.0-felucca-bsp.txt`. Section 3.3 describes conditional secondary
licensing for a combined larger work; this prototype preserves the origin and
notices and does not claim a separate author-granted relicensing permission.
Review distribution obligations before publishing a combined release.
