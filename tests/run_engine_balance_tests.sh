#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Host-only calibration experiment. No downloads, target compiler or device.
set -eu
cd "$(dirname "$0")/.."
OUT=build/host/engine_balance
mkdir -p "$OUT" build/gen
CC=${CC:-cc}
python3 tools/gen_tables.py build/gen/felucca_tables.h
python3 tools/gen_fm6_patches.py build/gen/felucca_fm6.h
python3 tools/gen_samples.py build/gen/felucca_samples.h
for balance in 0 1; do
    "$CC" -O2 -w -Ibuild/gen -Ifirmware/src -DFELUCCA_ENGINE_BALANCE=$balance \
        tests/engine_balance_test.c -lm -o "$OUT/measure_$balance"
    "$OUT/measure_$balance" unit
    for mode in raw premaster output routes; do
        "$OUT/measure_$balance" "$mode" > "$OUT/${mode}_$balance.csv"
    done
done
python3 tests/engine_balance_check.py "$OUT" > "$OUT/summary.txt"
cat "$OUT/summary.txt"
# New arithmetic in isolation, every x in [-2^20,2^20] and all gains.
# Unlike the full legacy DSP, this check excludes no UB categories.
"$CC" -O1 -w -Ibuild/gen -Ifirmware/src -DFELUCCA_ENGINE_BALANCE=1 \
    -fsanitize=undefined -fno-sanitize-recover=all \
    tests/engine_balance_test.c -lm -o "$OUT/unit_ubsan"
"$OUT/unit_ubsan" unit
# The existing golden file is NEVER rewritten. Off must match upstream.
"$CC" -O2 -w -Ibuild/gen -Ifirmware/src tests/regress.c -lm -o "$OUT/regress_off"
"$OUT/regress_off" tests/golden.txt tests/cpu_baseline.txt > "$OUT/regress_off.log"
tail -1 "$OUT/regress_off.log"
# On must match the frozen pre-optimization experiment as well. Neither
# committed golden is regenerated here; intentional audio changes need review.
"$CC" -O2 -w -Ibuild/gen -Ifirmware/src -DFELUCCA_ENGINE_BALANCE=1 \
    tests/regress.c -lm -o "$OUT/regress_on"
"$OUT/regress_on" tests/engine_balance_golden.txt tests/cpu_baseline.txt > "$OUT/regress_on.log"
tail -1 "$OUT/regress_on.log"
# Both flags: same DAC/USB 3 dB harmonic bound, noise-aware USB estimation,
# individual quantization bounds, and deliberately broken quantizers rejected.
python3 tests/engine_balance_tail.py "$OUT/tail" > "$OUT/tail_validation.log"
cat "$OUT/tail_validation.log"
echo "balance: host checks passed; hardware listening and timing still required"
