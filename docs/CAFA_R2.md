# CAFA r2: balance, connection and target-cost corrections

Date: 2026-11-13. Source base: osleg/skiff dev `2d15baea6e7e94062b31dc3c59c4db23a88d7bc0`. Branch: `cafa/prototype-20261112`. No push or flash was performed by the developer. The user reported that the first prototype booted but standalone sounds were unbalanced and the phone link disconnected. **The corrected r2 package has not yet been hardware-tested.**

## Corrections

1. **Standalone engine balance is enabled.** The first delivery included the source but omitted `FELUCCA_ENGINE_BALANCE=1`, so its compiled gain function returned unity. r2 explicitly enables the existing optimized 15-engine table and Q22 precision path. It is not a runtime menu switch. The general wide-multiply, peak and mixer optimizations were present in the original delivery and remain unchanged. Phone-generated CAFA sounds bypass the standalone engine mixer and are not calibrated by this table.
2. **The disconnect race is reproduced and fixed in host integration.** Back-to-back STATE/HEARTBEAT frames could overflow the firmware's one-completed-message mailbox. r2 acknowledges applied STATE, sends at most one phone frame per tick, retries HELLO, and avoids redrawing unchanged snapshots. See [cause and evidence](CAFA_DISCONNECT_FIX.md). Timeout and fail-closed behavior are not weakened.
3. **The exact combined target now passes original static budgets.** The first CAFA image exceeded room-reverb, strum and mono-delay estimates; a balance-only rebuild also exceeded the audio-ISR estimate. Its earlier aggregate had checked the standalone target. Equivalent individual FX-state clears restore compiler optimization of hot globals. Phone ownership is checked once per DMA half, and its USB return renders once for the full 128 frames. Standalone 32-frame control-block rendering and synth/mixer algorithms are unchanged. No budgets, tolerances or golden outputs were edited.

## Exact final artifacts

- Firmware: `cafa-balanced-r2-NOT-HARDWARE-QUALIFIED.fwsc`, 610,429 bytes.
  SHA256 `6833aa94354f5028d181f80cf972396b18d6338b1ed055f5adb0466dc1884e60`.
- Android: `cafa-debug.apk`, version 0.1.1, versionCode 2.
  SHA256 `7e3706ee81cc8f5a73a208155a90196a146843fa0154ac626a2ea12a1ba4e653`.
  Signed with the same local debug certificate as the first APK, so it is an update rather than a different signing identity. No private key is included.
- Firmware package identity remains `FM-1_700`, the normal FM-1 development identity. The source's displayed version remains v2.5.1. Neither identity alone distinguishes r2 from the old prototype; use the exact filename/checksum.

The package was parsed and CRC-checked, its app decrypted and compared byte-for-byte with the target build, and its embedded gain table and compiled table lookup verified. The original package's app has no table and returns unity; this is binary evidence, not just a build-option assertion.

## Target resources and checks

| Region | Used | Limit | Free |
|---|---:|---:|---:|
| App flash | 579,732 | 581,564 | 1,832 |
| Data + BSS | 90,688 | 94,208 | 3,520 |
| Audio/storage pool | 351,128 | 360,448 | 9,320 |
| RAM code + ramx | 29,760 | 32,512 | 2,752 |

Pool headroom includes the mandatory 8,192-byte reserve, leaving 1,128 bytes beyond it. Margin remains tight. Target compile, memory/flash/MMIO checks, exact-package loader and OTA simulations pass.

All original static target budgets pass at their unchanged 10% tolerance. Audio ISR cost is 1,390 against base budget 1,266, under the allowed 1,392.6. Room reverb is 218/218, strum 139/139 and mono delay 25/25. These are weighted instruction estimates, not measured hardware cycles or a CPU utilization guarantee.

## Host verification

- Balanced CAFA protocol/surface/actual-ISR tests pass under ASan and strict UBSan; leak detection is disabled for this traced runtime. The ISR boundary test covers both DMA halves, one USB render per half, output clearing, physical master gain, note-off retries, no local synthesis/sequencer/click/capture, auxiliary interrupts and silent standalone restoration. USB resampling is separately tested, not simulated by that boundary stub.
- 57,540 Android core assertions and 1,976 actual Java-to-C USB mailbox checks pass. The original race fails at 1,530 ms; corrected sessions last 20.4 seconds under 34/102/170 ms firmware-service schedules. Lost HELLO/ACK and return-path loss/reconnect are covered.
- Existing standalone audio tests pass with CAFA compiled in, inactive, and balance enabled.
- Balance calibration: 450 raw, 450 premaster, 450 output and 15 routing pairs pass; engine median spread 0.401 dB. Both configurations retain their 176 frozen goldens. Strict arithmetic UBSan, both quiet-tail baselines and all six deliberate bad-quantizer mutations pass.
- Multicore: 0/38 scenes differ across splits; voice-budget and performance routing tests pass. Linux instruction-counter CPU measurements are unavailable.
- USB lifecycle/drift suite: 16 sanitized CAFA variants and 64 stock descriptor variants pass. 60-second nominal and ±200 ppm streams show no under/overruns, maximum fill 308/1024.
- Independent review found no blocking issue in the FX reset, main/ISR ordering or one-half rendering changes.

The full stock aggregate passed for the first source delivery with its documented skips; r2 used the focused suites above plus its exact target budgets. This is not a claim that the whole aggregate, an Android emulator or physical hardware qualification was rerun for r2. Audible transition clicks, Android routing, USB power, sustained live load and latency remain physical tests.

## Reproduce

Use the pinned compiler/SDK and root BUILDING.md prerequisites:

```sh
FELUCCA_CAFA=1 FELUCCA_UAC_OUT=1 FELUCCA_CDC=0 FELUCCA_ENGINE_BALANCE=1 ./build.sh
python3 tests/target_budget.py build/felucca.dis tests/target_budget.txt
ASAN_OPTIONS=detect_leaks=0 CAFA_TEST_CFLAGS='-DFELUCCA_ENGINE_BALANCE=1 -fsanitize=address,undefined -fno-sanitize-recover=all' sh tests/run_cafa_tests.sh
sh tests/run_engine_balance_tests.sh
```

## Optional hardware retest, performed by the owner

1. Keep a complete Skiff Editor backup and a known-good prior firmware package locally. This prototype is based on the requested newer dev source, not a CAFA-only patch on v2.2. Do not assume newer saves remain backward-compatible. Know the normal updater and boot-mode recovery routes before flashing.
2. Update Android with the matching 0.1.1 APK. Back up anything important first; do not uninstall casually, because uninstall can erase the phone project.
3. Close other MIDI applications. Use stable power and a direct data cable. With external amplification/headphones disconnected and master at minimum, inspect `python3 tools/fm1_install.py --info` from the bundled source directory.
4. If deliberately proceeding, run `python3 tools/fm1_install.py ../artifacts/cafa-balanced-r2-NOT-HARDWARE-QUALIFIED.fwsc`. Keep its interactive confirmation; do not use `--force` or `--yes`. A standalone firmware attachment may have a different local path; use that exact file, not the old ZIP's firmware.
5. First verify standalone controls and balance at low volume. Then start CAFA audio, connect FM1, wait for “FM1 synchronized,” and leave it idle for at least a minute before playing. Test phone-speaker preview and named FM1 USB output separately. Follow the interruption/reconnect checklist in CAFA_DISCONNECT_FIX.md.

No instruction here makes flashing risk-free. If a normal boot/update path fails, recovery may require specialized tools. Do not run a factory reset or format storage as part of this test.
