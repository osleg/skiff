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
# On: intentional sound changes get a separate scratch baseline. Health and
# voice/routing assertions still run; the committed upstream baseline is intact.
"$CC" -O2 -w -Ibuild/gen -Ifirmware/src -DFELUCCA_ENGINE_BALANCE=1 \
    tests/regress.c -lm -o "$OUT/regress_on"
GOLDEN_UPDATE=1 "$OUT/regress_on" "$OUT/experimental_golden.txt" tests/cpu_baseline.txt > "$OUT/regress_on.log"
tail -1 "$OUT/regress_on.log"
python3 tests/engine_balance_tail.py "$OUT" > "$OUT/tail_usb_average.log"
cat "$OUT/tail_usb_average.log"
# Keep the upstream tail checks unchanged. A failing USB check must not be
# hidden by the statistical calibration results above.
for balance in 0 1; do
    "$CC" -O2 -w -Ibuild/gen -Ifirmware/src -DFELUCCA_ENGINE_BALANCE=$balance \
        tests/tail_test.c -lm -o "$OUT/tail_$balance"
    "$OUT/tail_$balance" > "$OUT/tail_$balance.log" || { cat "$OUT/tail_$balance.log"; exit 1; }
done
echo "balance: host checks passed; target CPU/flash/RAM and hardware listening still required"
