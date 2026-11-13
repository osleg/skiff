#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/cafa-tests
CC=${CC:-cc}
$CC -std=c99 -Wall -Wextra ${CAFA_TEST_CFLAGS:-} tests/cafa_protocol_test.c -o build/cafa-tests/protocol
build/cafa-tests/protocol
$CC -O1 -w ${CAFA_TEST_CFLAGS:-} -Ibuild/gen -Ifirmware/src tests/cafa_surface_test.c -lm -o build/cafa-tests/surface

$CC -O1 -w ${CAFA_TEST_CFLAGS:-} -Ibuild/gen -Ifirmware/src tests/cafa_native_ui_test.c -lm -o build/cafa-tests/native-ui
ASAN_OPTIONS=detect_leaks=0 build/cafa-tests/native-ui
if [ -f cafa/android/build/vectors/envelope.tsv ]; then
    $CC -O1 -w ${CAFA_TEST_CFLAGS:-} -Ibuild/gen -Ifirmware/src tests/cafa_envelope_probe.c -lm -o build/cafa-tests/envelope-probe
    ASAN_OPTIONS=detect_leaks=0 build/cafa-tests/envelope-probe cafa/android/build/vectors/envelope.tsv
fi
build/cafa-tests/surface

${CC:-cc} -O1 -w ${CAFA_TEST_CFLAGS:-} -Ibuild/gen -Ifirmware/src tests/cafa_audio_test.c -lm -o build/cafa-tests/audio
ASAN_OPTIONS=detect_leaks=0 build/cafa-tests/audio

# When the Android host tests have emitted fixtures, verify the actual Java serializer against C.
if [ -f cafa/android/build/vectors/hello.syx ] && [ -f cafa/android/build/vectors/state.syx ]; then
    $CC -std=c99 -Wall -Wextra -Wno-unused-function ${CAFA_TEST_CFLAGS:-} tests/cafa_wire_probe.c -o build/cafa-tests/wire-probe
    build/cafa-tests/wire-probe cafa/android/build/vectors/hello.syx cafa/android/build/vectors/state.syx
fi

# Exercise Java session timing against the real firmware USB-MIDI mailbox.
if command -v java >/dev/null 2>&1; then
    sh tests/run_cafa_link_tests.sh
else
    echo "SKIP: Java/C session integration requires a JDK"
fi

if [ -n "${LOOM_V4_FIXTURES:-}" ]; then
    $CC -O1 -w ${CAFA_TEST_CFLAGS:-} -Ibuild/gen -Ifirmware/src -Itests tests/loom_native_lint.c -lm -o build/cafa-tests/loom-lint
    ASAN_OPTIONS=detect_leaks=0 build/cafa-tests/loom-lint "$LOOM_V4_FIXTURES"
fi
