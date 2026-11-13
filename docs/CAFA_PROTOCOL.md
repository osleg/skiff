# CAFA wire protocol v1 and v2

Version 2 retains this frame and 92-byte STATE layout, with two explicit changes:
flags byte 2 bits 3..5 encode octave+3 (0..6), bit 6 stays zero; on page 3, the
four values at 84/86/88/90 are actual native ATK/DEC/SUS/REL integers 0..127.
Firmware binds the accepted version to the session and echoes it in ACK/INPUT.
Cross-version frames cannot change or renew a session. Android 0.2.0 requires
version 2 and does not silently downgrade. Version 1 stays supported by new
firmware, using the proxy view. See [native ENV scope](CAFA_NATIVE_ENV.md).

The original version-1 layout below remains unchanged; for version 2 replace
the header version byte `01` with `02` and apply the two changes above.

All frames: `F0 7D 43 41 01 cmd sessionLo sessionHi serialLo serialHi payload... F7`. All interior bytes are 7-bit. Integers are little-endian groups of 7 bits. Session is a nonzero u14 chosen by Android. Noncommercial manufacturer ID 7D is for prototype use. No pairing/authentication is implied.

| Command | Sender | Payload |
|---|---|---|
| 1 HELLO | Android | empty; negotiate session, not audio ownership |
| 2 ACK | Skiff | `[1]`; HELLO serial 0, accepted STATE and heartbeat serial last applied STATE revision |
| 3 STATE | Android | 92 bytes below; first valid snapshot activates mode |
| 4 HEARTBEAT | Android | empty, every 250 ms; ACK confirms last applied STATE |
| 5 BYE | Android | empty; return standalone stopped if active |
| 6 INPUT | Skiff | 20 bytes below, serial starts 1 per session |

Lease is 1500 ms, unsigned millisecond wrap-safe. Invalid/stale traffic cannot renew it. HELLO for the same session is idempotent. Another session is refused until BYE or lease expiry. STATE revisions must advance modulo 16384, with forward delta <8192. Each snapshot is validated completely before publication. INPUT serials likewise wrap. A lost INPUT serial must panic/reconnect, not silently ignore a relative knob move. Entire outgoing SysEx frames are queued nonblocking or the session closes. Neither source should assume the other remains active merely because USB remains enumerated.

Firmware acknowledges every accepted STATE directly, including a newer revision with unchanged display content. Android emits at most one frame per UI timer tick (nominally 34 ms), with a due heartbeat replacing that tick's snapshot. Until HELLO is acknowledged, it retries HELLO every 250 ms rather than sending HEARTBEAT. These rules are necessary because the USB receive path has one completed-frame mailbox: a second frame arriving before the main loop consumes the first is dropped. STATE revisions may skip; INPUT serials must not. ACKing accepted STATE also permits the original 0.1 Android sender to stay live if its immediately following heartbeat is dropped. Android only renews its acknowledgment lease when the applied STATE revision advances; replaying one ACK is not evidence of continued progress.

Unchanged snapshots do not force a redraw, but still advance the revision and receive an ACK. Neither the lease duration nor the wire format/version changed in this transport correction.

STATE payload offsets:

| Offset | Value |
|---|---|
| 0 | selected track 0..5 |
| 1 | page: 0 HOME, 1 SEQ, 2 SOUND, 3 ENV, 4 FX, 5 MIX |
| 2 | flags: bit0 playing, bit1 record, bit2 mute |
| 3..4 | track pattern length 1..1024 |
| 5..6 | visible first step, multiple16 less than length |
| 7..8 | playhead less than length, or 16383 none |
| 9..10 | integer BPM20..300 |
| 11..13 | 16 visible step bits |
| 14..15 | logical button LEDs (order below) |
| 16..19 | 27 physical note key LEDs |
| 20..35 | track name, printable ASCII or zero padding |
| 36..51 | sound name, printable ASCII or zero padding |
| 52..83 | four knob labels, ASCII8 each |
| 84..91 | four knob values, u14 each |

INPUT payload offsets0..1 are logical button held mask, 2..5 physical note held mask, 6..19 seven signed encoder deltas encoded as u14(value+8192). Logical button order: FX, SEL, ENV, LFO, EDIT, GLO, HOME, SAVE, ARP, SEQ, PLAY, REC, OCT-, OCT+. Encoder order: SELECT, ALGORITHM, PRESETS, KNOB1, KNOB2, KNOB3, KNOB4. Physical note bit0 is F3/MIDI53, bit26 G5/MIDI79. Input represents raw intentions; Android decides page/context, note/step mapping and sound semantics. The full held snapshot repairs release state after each valid message, but not lost encoder deltas.

USB playback is separate UAC1 stereo16 at44.1kHz, not sent through SysEx. See CAFA_USB.md.
