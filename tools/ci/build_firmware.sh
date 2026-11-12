#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Build both FM-1 variants, preserve failures and diagnostics, never flash/release.
set -euo pipefail
cd "$(dirname "$0")/../.."
ulimit -n 8192
OUT="$PWD/build/ci/firmware"
mkdir -p "$OUT"
FAIL=0
{
    printf 'Source commit: '; git rev-parse HEAD
    printf 'Toolchain: '; "$JIELI_TOOLCHAIN/pi32v2/bin/clang" --version
    printf 'SDK commit: '
    if [[ -f "$AC79_SDK/SOURCE_COMMIT" ]]; then
        cat "$AC79_SDK/SOURCE_COMMIT"
    else
        git -C "$AC79_SDK" rev-parse HEAD
    fi
    python3 --version
    python3 -m pip freeze
    cc --version | head -1
    printf '\nExperimental artifacts only. Not validated for hardware installation.\n'
    printf 'Target cost checks are static instruction estimates, not hardware cycle measurements.\n'
} > "$OUT/provenance.txt"
for balance in 0 1; do
    NAME=baseline
    [[ "$balance" == 1 ]] && NAME=experimental-balanced
    DIR="$OUT/$NAME"
    mkdir -p "$DIR"
    printf 'FELUCCA_ENGINE_BALANCE=%s; BOARD=fm1; no --release\n' "$balance" > "$DIR/config.txt"
    # A failed build must not archive a previous variant's binary.
    rm -f build/felucca.{bin,elf,dis,fwsc} build/loader/ota.bin
    if env BOARD=fm1 FELUCCA_ENGINE_BALANCE="$balance" ./build.sh 2>&1 | tee "$DIR/build.log"; then
        cp build/felucca.{bin,elf,dis,fwsc} "$DIR/"
        cp build/loader/ota.bin "$DIR/"
        readelf -SW build/felucca.elf > "$DIR/sections.txt"
        if python3 tests/target_budget.py build/felucca.dis tests/target_budget.txt \
            2>&1 | tee "$DIR/target-budget.log"; then
            echo PASS > "$DIR/target-budget.status"
        else
            echo FAIL > "$DIR/target-budget.status"
            FAIL=1
        fi
        # Existing aggregate suite expects original audio golden files. Keep it on flag-off.
        if [[ "$balance" == 0 ]]; then
            if sh tests/run_tests.sh > "$DIR/aggregate-tests.log" 2>&1; then
                echo PASS > "$DIR/aggregate-tests.status"
            else
                echo FAIL > "$DIR/aggregate-tests.status"
                FAIL=1
                tail -80 "$DIR/aggregate-tests.log"
            fi
        fi
    else
        echo FAIL > "$DIR/build.status"
        FAIL=1
    fi
done
if [[ -f "$OUT/baseline/felucca.elf" && -f "$OUT/experimental-balanced/felucca.elf" ]]; then
    python3 tools/size_diff.py "$OUT/baseline/felucca.elf" "$OUT/experimental-balanced/felucca.elf" \
        | tee "$OUT/size-diff.txt"
fi
printf 'Corresponding source: https://github.com/%s/tree/%s\n' \
    "${GITHUB_REPOSITORY:-osleg/skiff}" "$(git rev-parse HEAD)" > "$OUT/SOURCE.txt"
cp LICENSE LICENSING.md docs/ENGINE_BALANCE.md docs/EXPERIMENTAL_CI.md "$OUT/"
cp -R LICENSES "$OUT/"
cp assets/samples-cc0/ATTRIBUTION.txt "$OUT/ATTRIBUTION-SAMPLES.txt"
# Include existing sample licences/attributions without assuming their subdirectory.
find assets -type f \( -iname '*attribution*' -o -iname '*license*' -o -name 'CREDITS.txt' \) \
    -print > "$OUT/attribution-sources.txt"
while IFS= read -r file; do
    mkdir -p "$OUT/source-attributions/$(dirname "$file")"
    cp "$file" "$OUT/source-attributions/$file"
done < "$OUT/attribution-sources.txt"
(cd "$OUT" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
        echo '## Experimental FM-1 artifacts'
        echo 'Both flag values are built explicitly. These are not releases and have not been flashed.'
        echo 'The engine-balance host job retains known USB quiet-tail failures.'
        echo 'Target CPU figures are static estimates; hardware listening and timing remain required.'
        find "$OUT" -name '*.status' -print -exec cat {} \;
        if [[ -f "$OUT/size-diff.txt" ]]; then
            echo '```text'; head -12 "$OUT/size-diff.txt"; echo '```'
        fi
    } >> "$GITHUB_STEP_SUMMARY"
fi
exit "$FAIL"
