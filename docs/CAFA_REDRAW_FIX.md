<!-- SPDX-License-Identifier: GPL-3.0-only -->
# CAFA proxy-display redraw correction

This is a narrow correction to the existing companion display. It does **not**
implement the actual Skiff page renderer or restore full Skiff interactions.
The original proxy layout remains visibly different and is incomplete for that
requirement. Do not describe this change as native Skiff UI parity.

## Confirmed defect

After r2, the user reported that physical controls and audio routing work, but
the FM1 display still flashes and shows a simplified interface instead of Skiff.

r2 suppresses redraw only when every snapshot byte is unchanged. Otherwise it
clears the entire screen and draws every field again. This includes snapshots
whose only change is a physical-key LED, although those LED bytes are not drawn
on the LCD. The same full clear occurs when the playhead or one knob value moves.

An instrumented run of the production renderer reproduces the defect: changing
only the LED byte writes **91,608 pixels** and clears the whole screen once.
That is 183,216 pixel bytes, over 122 ms at the configured 12 MHz LCD SPI rate
even before command and interrupt overhead. This is a host-observed transfer
count, not a measurement of the user's screen or its reported one-second cadence.

## Correction

- Cache the last snapshot actually drawn, separate from the last received one.
- A fresh takeover forces a full draw. Ordinary snapshot updates never clear the
  full screen, even when track, page, viewport or pattern length changes.
- Ignore physical LED-only changes when deciding whether to update the display.
- Redraw only changed labels, values, names, header fields, transport or cells.
- Compose a cell's background and playhead underline in the existing canvas
  before transferring it, instead of erasing the cell and drawing its marker later.
- Continue acknowledging every accepted snapshot independently of redraws.

No protocol, APK, synthesis, transport lease, physical-control mapping or saved
standalone project change is needed. The rendered-state cache uses 92 bytes.

## Regression checks

`tests/cafa_surface_test.c` exercises the production renderer through the host
LCD boundary and checks:

- Identical snapshots and LED-only changes transfer zero pixels.
- A moving playhead updates only the old and new 24×20 cells, normally 960 pixels.
- Value, tempo, shortened name, step mask, transport, track, page, viewport and
  pattern-length changes never clear the full screen.
- Every incremental result is pixel-identical to a fresh complete render of that
  same snapshot, including removal of old markers and longer previous text.
- Reconnecting forces a full initial draw rather than reusing stale pixels.

The same test fails on r2 at the LED-only check. This correction can be useful
without being a solution to the separate native-UI requirement. Actual display
appearance and USB/interrupt timing still require a physical retest.
