# Experimental engine output balance

**Opt-in prototype. Not release-ready.** Base revision: `illuminus5/skiff`
main `fe387231f7b7cb98823f19d8d4f99fee4fcd3792`.

## Build and compatibility

Default `FELUCCA_ENGINE_BALANCE=0` keeps existing rendering bit-for-bit unchanged.
No settings, preset fields, project formats or editor IDs change. Enable with
`FELUCCA_ENGINE_BALANCE=1 ./build.sh`. There is no runtime switch. Enabled builds
change existing projects and user samples too; a default build restores their
original mix. Browser emulator:
`EMU_CFLAGS=-DFELUCCA_ENGINE_BALANCE=1 web/emu/build.sh`.

Host-only checks: `sh tests/run_engine_balance_tests.sh`. They write diagnostics
under `build/host/engine_balance/`, never replace committed golden files, do not
need the JieLi compiler and do not access hardware. **The script currently exits
nonzero on three unchanged, single-run USB quiet-tail assertions.** It reports
those failures rather than hiding or relaxing them. The experiment is therefore
not added to the normal aggregate test runner.

## Provisional calibration

150 factory presets x three notes: MIDI 48/60/72, or kick/snare/hat 36/38/42 for
DRUM/DRUM-X. One note at velocity 100 held for one second; factory sound/envelope,
ARP off, LEVEL 112 (0 dB), no DRIVE/INSERT or sends, SPREAD off, master full,
speaker processing off. Each render starts in a fresh process. Metric: loudest
non-overlapping approximately 100 ms RMS window, aggregated as the median over
that engine's presets/notes. **This is not LUFS or equal perceived loudness.**

PHYS is the quietest median, about -20 dBFS. Other engines are attenuated toward
it in half-dB increments, never boosted. Gains are
`round(32768 * 10**(trim_dB / 20))`, with 32768 exact unity. DIGITAL is retired,
not in default builds, unmeasured and left at unity.

The final calibration point is the dry mixer before the master, not the raw
voice renderer. This includes normal modulation processing. Pre-master sums are
32-bit and can exceed 0 dBFS without integer clipping. Relative reference: 32768.

| Engine | Presets | Trim dB | Before master, old | Before master, balanced | Output old | Output balanced |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ANALOG | 25 | -12.0 | -7.97 | -19.97 | -10.16 | -20.01 |
| PHASE | 9 | -14.5 | -5.55 | -20.05 | -10.51 | -20.11 |
| LOFI | 8 | -7.0 | -13.04 | -20.04 | -13.32 | -20.10 |
| SAMPLE | 10 | -13.0 | -6.79 | -19.79 | -10.98 | -19.85 |
| VOICE | 8 | -13.5 | -6.39 | -19.89 | -10.64 | -19.96 |
| TRIO | 9 | -6.5 | -13.36 | -19.86 | -14.56 | -19.95 |
| WHEEL | 8 | -11.0 | -9.19 | -20.19 | -12.23 | -20.25 |
| GRAIN | 8 | -7.5 | -12.56 | -20.06 | -14.41 | -20.16 |
| PHYS | 11 | 0.0 | -19.89 | -19.89 | -20.00 | -20.00 |
| DRUM | 13 | -10.5 | -9.52 | -20.02 | -14.58 | -20.16 |
| NOISE | 8 | -3.5 | -16.52 | -20.02 | -16.85 | -20.12 |
| FM6 | 14 | -6.0 | -14.10 | -20.10 | -14.30 | -20.15 |
| SLICE | 9 | -5.0 | -14.98 | -19.98 | -15.27 | -20.05 |
| DRUM-X | 10 | -4.0 | -15.89 | -19.89 | -17.38 | -20.08 |

These values align engine **medians**, not every patch. Percussion, sparse noise,
short samples, pads and basses should not all have identical energy. Preset
variation remains; arbitrary user samples and imported FM patches are not
calibrated. Solo levels are lower. Do not undo the headroom with blind boosts.
A production pass should use musical roles, phrase-based loudness and listening.

## Implementation and precision

The final patch does not scale voice amplitude endpoints. An early prototype did
and failed the existing DAC tail-distortion checks. Folding the gain into Q12
track LEVEL still cut quiet samples too early and failed those checks as well.

Instead, the original raw engine and track DRIVE/INSERT/LEVEL/PAN paths stay
unchanged. The dry contribution is attenuated into Q22 using split 32-bit
products. Integer and fractional parts accumulate in separate per-core sums;
whole fractional units are carried before THROW/DUB sees the mix. The remaining
seven low bits join the existing master path before DC blocking, speaker EQ,
limiting and soft clipping. The sounding engine ID is used through switch fades.
Ordinary sends are attenuated separately; SAMPLE extra sends use the corresponding
level coefficient, without applying its dry trim twice. Sends keep their existing
lower precision. Performance effects keep their existing integer/low-bit handling.

No AGC, compressor, operator adjustment, saved-state change or gain boost. The
added dry accumulators cost 512 bytes with both cores built, 256 in a single-core
build, only when enabled. New arithmetic is checked exhaustively for every
integer sample in [-2^20, 2^20] and every gain against a 64-bit reference, plus
all 128 LEVEL coefficients. Unity and opposite-sign cancellation are checked.
UBSan on this arithmetic excludes no categories. The target build fits flash/RAM,
but four static target cost checks fail; see the target verification below.

## Verified and unresolved

- 450 raw render pairs are identical, not merely similarly loud.
- 450 dry pre-master pairs match intended gain within 0.1 dB; 450 post-master
  pairs remain in range. Engine median spread is about 0.40 dB at both stages.
- Envelope fingerprints remain unchanged. Fifteen stereo/panned-drum/user-sample
  send cases pass; unity paths are exact.
- Disabled build matches all 176 original golden renders. Enabled build passes
  health and voice/routing checks with a separate scratch golden. That generated
  golden is not independent proof of sound quality.
- Multicore: 38 scenes match across every split. Performance-effect tests pass.
- All six **DAC** quiet-tail checks pass. Three combined upstream tail assertions
  still fail because of **USB** estimates at the deepest tail with master at 25%.
  The enabled output is much quieter: the fundamental approaches -100 dBFS and
  its expected third harmonic falls below a single 4096-sample dither-noise
  estimate. `tests/engine_balance_tail.py` coherently averages 256 independent
  dither quantizations of the same signal; all six cases then satisfy the
  original 3 dB harmonic-growth limit. This supports noise-floor contamination
  rather than added coherent distortion. It does not make the original failures
  passes or establish hardware sound quality. The diagnostic pads its temporary
  host-test arrays by one block for the final partial block; production code and
  the upstream test file are unchanged.
- FM-1 target builds were completed with the pinned official JieLi toolchain and
  SDK in [EXPERIMENTAL_CI.md](EXPERIMENTAL_CI.md). No device was flashed and no
  release was published. Flag-off image: 579,544 bytes. Flag-on: 580,192 bytes
  (+648), leaving just 1,372 bytes in the 581,564-byte app slot. Enabled
  `.data + .bss` is 89,272 / 94,208 bytes (+512); the pool stays
  351,128 / 360,448 bytes (9,320 bytes spare); RAM code + `.ramx` becomes
  30,080 / 32,512 bytes (+440). The loader is 7,273 / 7,936 bytes.
- Flag-off passes the unchanged target cost budget. **Flag-on fails four static
  cost checks**: `fm1_alnk0_irq` 3,600 vs 1,266 (+184%), `perf_master` 68 vs 58
  (+17%), `mix_spread` 198 vs 143 (+38%), and `lpan_mix` 136 vs 91 (+49%).
  These are weighted static loop instruction estimates, not measured CPU load or
  cycle percentages. No budget was updated to hide them. Hardware timing and a
  review of compiled loop structure remain necessary. Linux lacks the macOS
  host instruction-counter check.
- Emscripten 4.0.23 builds the enabled browser simulator. Its 88,200 stereo audio
  frames match the native simulator bit-for-bit; existing boot, keys, MIDI,
  drawing, LED, playback and save-persistence checks pass. This does not emulate
  hardware timing, USB audio, or flash/update safety.

Before release: hardware/USB listening, noise-aware tail criteria reviewed
upstream, role-appropriate loudness calibration, quiet lane/sample levels,
imported patches, parameter extremes, busy six-track mixes, engine changes with
insert tails, and resolving the static target-cost failures with hardware timing checks. Fixed trims are experimental until
those checks pass; existing mixer effects can depend on absolute level.

Methodology reference for a later pass:
[the SLOOP level-presets tool retained in STATTIC](https://github.com/Timqos/stattic/blob/acfafc77f1bcd16455a9b3d0aeb5edbbf650d795/tools/level_presets.py)
uses role-appropriate phrases, offline gated BS.1770 loudness and per-preset
trims. Its table is not portable across engine sets or master paths. No code or
trim values from it were copied here.
