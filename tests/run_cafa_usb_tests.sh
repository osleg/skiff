#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
CC=${CC:-cc}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
# Optimizer removes unused hardware functions in this unity-style host test.
for layout in 0 1 2 3; do
    for cdc in 0 1; do
        for on in 0 1; do
            "$CC" -std=c99 -O2 -DT_CDC="$cdc" -DT_ON="$on" -DT_LAYOUT="$layout" \
                ${CAFA_TEST_CFLAGS:-} tests/cafa_usb_test.c -o "$OUT/cafa"
            "$OUT/cafa"
        done
    done
done
# Stock descriptor bytes and existing recording path remain unchanged with CAFA off.
for uac in 0 1; do
    for cdc in 0 1; do
        for on in 0 1; do
            for layout in 0 1 2 3; do
                for rate48 in 0 1; do
                    "$CC" -std=c99 -O2 -DT_CDC="$cdc" -DT_ON="$on" -DT_UAC="$uac" \
                        -DT_LAYOUT="$layout" -DT_48K="$rate48" tests/usb_desc_test.c -o "$OUT/stock"
                    "$OUT/stock" > "$OUT/stock.log" || { cat "$OUT/stock.log"; exit 1; }
                done
            done
        done
    done
done
printf '%s\n' 'Stock USB descriptor matrix passed (64 variants)'
