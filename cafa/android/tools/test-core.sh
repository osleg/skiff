#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tools/sync-envelope-tables.py --check
mkdir -p build/core-tests
java com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all -d build/core-tests app/src/main/java/dev/cafa/core/*.java core-tests/*.java
java -ea -cp build/core-tests CoreTests
