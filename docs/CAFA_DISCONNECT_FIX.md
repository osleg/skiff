<!-- SPDX-License-Identifier: GPL-3.0-only -->
# CAFA disconnect correction, 2026-11-13

## Report and reproduced cause

On the user's first physical test, the Android app and companion firmware booted.
After selecting audio, starting it and connecting FM1, the FM1 display flashed,
then returned to standalone with `CAFA DISCONNECTED` a few seconds later. The
same symptom occurred with phone-speaker preview and USB output.

The original Java `Link.tick()` sent a STATE and then a HEARTBEAT in the same
timer callback. The production firmware USB-MIDI receiver has one completed
SysEx mailbox. If both frames arrived before the main loop serviced it, STATE
occupied that mailbox and HEARTBEAT was dropped. Only HELLO and HEARTBEAT
generated ACKs. Thus FM1 could apply the state and enter companion mode while
Android received no further acknowledgment and closed the session after its
1500 ms lease.

A new host integration test runs the actual Java Link against the actual C
USB-MIDI packet parser, mailbox, CAFA state processing and transmit queue. With
the original code it reproducibly failed at **1530 ms**, even when the firmware
serviced incoming frames every 34 ms. This reproduces a software cause, not a
measurement of the user's USB driver or instrument.

## Changes

- Firmware acknowledges every accepted STATE, including advancing revisions
  whose display values are unchanged. Receipt of a heartbeat is no longer the
  only way for Android to learn that its snapshots are applied.
- Android sends no more than one frame per timer tick. A due heartbeat gets its
  own tick rather than being sent immediately behind STATE.
- While negotiating, Android retries the idempotent HELLO every 250 ms. It no
  longer sends heartbeats to a session whose HELLO may have been lost.
- The FM1 skips a full-screen clear/redraw for an unchanged snapshot. A screen
  clear transfers 115,200 pixel bytes and takes at least about 77 ms at the
  configured 12 MHz SPI clock, before text, controls and interrupt overhead.
  Unnecessary redraws therefore worsened receive-mailbox contention and flashing.
- Android version is now **0.1.1**, versionCode **2**, signed with the same local
  debug certificate as the initial APK. The protocol version remains v1.

The 1500 ms timeout, fail-closed behavior, input-sequence checks, local project
preservation and held-control release guard remain intact. The firmware does
not silently enlarge its lease or claim synchronization without applying state.

## Verification

- Original-code regression: deterministically disconnected at 1530 ms.
- Corrected Java core: 57,540 assertions, including revision wrapping, framing,
  one-frame pacing, HELLO retry, stale ACK rejection and safe timeout.
- Corrected Java/C integration: 1,976 assertions. Three 20.4-second sessions
  stay synchronized with firmware servicing every 34, 102 and 170 ms. Lost
  first HELLO and lost first HELLO ACK recover. Return-path loss still stops
  safely, releases FM1 and permits a fresh session.
- Firmware protocol, surface, audio and cross-language tests pass with Address-
  and UndefinedBehaviorSanitizer, with leak checking disabled for this runtime.
- New surface assertions cover immediate applied-state ACKs and suppressing
  identical redraws while acknowledging the newer revision.
- APK rebuilt with the official API 35/Build Tools 35.0.0 toolchain. Its v2 and
  v3 APK signatures verify. Compiler deprecation warnings remain; no compile error.

Run from the repository root:

```sh
sh tests/run_cafa_link_tests.sh
ASAN_OPTIONS=detect_leaks=0 \
  CAFA_TEST_CFLAGS='-fsanitize=address,undefined -fno-sanitize-recover=all' \
  sh tests/run_cafa_tests.sh
```

No device was flashed or attached to these tests. Android OS MIDI dispatch,
physical screen timing, USB routing, sustained DAC output and real latency are
still hardware tests. A host regression pass is not a claim that the corrected
build has been proven on the user's instrument.

## Focused physical retest

Use the matching corrected firmware and APK at low volume. After `Start audio`
and `Connect FM1`, wait for `FM1 synchronized`, leave the connection idle for
at least one minute, then play and turn controls. Verify both views stay in
sync. Repeat once with explicit phone-speaker preview and once with the named
FM1 USB output. Stop audio, pause the app, unplug/reconnect, and reconnect while
holding then releasing a physical key. Confirm each interruption returns FM1
to stopped standalone mode without a stuck note or unexpectedly resumed song.

If it still disconnects, retain the exact Android status message, how long the
session lasted, whether `FM1 synchronized` appeared, and whether Android's USB
audio/MIDI device entries disappeared. Those distinguish an ACK timeout from
an audio-route, input-sequence or USB-detach failure.
