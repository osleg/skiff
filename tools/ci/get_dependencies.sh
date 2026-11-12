#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Download the pinned official toolchain and verify bundled SDK inputs. Never install system-wide.
set -euo pipefail
DEST=${1:?usage: get_dependencies.sh DEST}
mkdir -p "$DEST"
DEST=$(cd "$DEST" && pwd)
TOOLCHAIN_VERSION=20260730.1
TOOLCHAIN_SHA256=a1545cddbc451a06bac43eb5cd96d831470c42a97c5c0b1f98879a20fc7647bd
SDK_COMMIT=d179b4484759423312073f5fbb232501aa491047
ARCHIVE="$DEST/jieli-linux-toolchains-$TOOLCHAIN_VERSION.tar.xz"
curl --fail --location --retry 3 --connect-timeout 30 --max-time 300 \
    "https://jl-update.oss-cn-shenzhen.aliyuncs.com/jieli-linux-toolchains-$TOOLCHAIN_VERSION.tar.xz" \
    --output "$ARCHIVE"
printf '%s  %s\n' "$TOOLCHAIN_SHA256" "$ARCHIVE" | sha256sum --check --strict
# The hash is verified before any extraction or execution.
python3 - "$ARCHIVE" "$DEST" <<'PY'
import sys
import tarfile
with tarfile.open(sys.argv[1], "r:xz") as archive:
    archive.extractall(sys.argv[2], filter="data")
PY
test -x "$DEST/jieli-linux-toolchains-$TOOLCHAIN_VERSION/pi32v2/bin/clang"
SDK="$DEST/ac79-sdk"
SDK_SOURCE="$(cd "$(dirname "$0")/sdk" && pwd)"
test "$(cat "$SDK_SOURCE/SOURCE_COMMIT")" = "$SDK_COMMIT"
mkdir -p "$SDK"
cp -R "$SDK_SOURCE/." "$SDK/"
# Verify all three SDK bytes before packaging. These are the upstream build's pins.
(cd "$SDK/cpu/wl82/tools" && sha256sum --check --strict <<'SUMS'
4e3b4c220dc96641cb5a723f41e68ce41d5261ae9434bb33fbd7f2c59976ded4  uboot.boot
276579954f076886a6a7694f65dc71c034a63a2c204b76749065c0ac7b010d1b  cfg_tool.bin
41167491bffed4651750719c973d2758adeb9021a5670d02d6a53c85ed80ea7d  cfg/eq_cfg_hw.bin
SUMS
)
printf 'JIELI_TOOLCHAIN=%s\nAC79_SDK=%s\n' \
    "$DEST/jieli-linux-toolchains-$TOOLCHAIN_VERSION" "$SDK" | tee "$DEST/paths.env"
if [[ -n "${GITHUB_ENV:-}" ]]; then
    cat "$DEST/paths.env" >> "$GITHUB_ENV"
fi
