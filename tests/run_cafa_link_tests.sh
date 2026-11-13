#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/cafa-tests
${CC:-cc} -O1 -w ${CAFA_TEST_CFLAGS:-} -Ibuild/gen -Ifirmware/src tests/cafa_link_peer.c -lm -o build/cafa-tests/link-peer
bash cafa/android/tools/test-core.sh
java -ea -cp cafa/android/build/core-tests FirmwareLinkTests build/cafa-tests/link-peer
