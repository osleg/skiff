#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Use Skiff's actual table generator so Android/native ENV coefficients cannot drift."""
from pathlib import Path
import re
import runpy
import sys
import tempfile

android = Path(__file__).resolve().parents[1]
repo = android.parents[1]
generator = runpy.run_path(str(repo / 'tools/gen_tables.py'))
with tempfile.TemporaryDirectory(prefix='cafa-envelope-') as temp:
    header = Path(temp) / 'tables.h'
    generator['main'](str(header))
    contents = header.read_text()
lines = [
    '/* SPDX-License-Identifier: GPL-3.0-only',
    ' * Envelope tables generated from Skiff tools/gen_tables.py.',
    ' * Original generator copyright (C) 2026 Leo Kuroshita, Hugelton Instruments. */',
    'package dev.cafa.core;',
    '',
    '/** Generated, do not edit. Run tools/sync-envelope-tables.py from cafa/android. */',
    'final class EnvelopeTables {',
    '    private EnvelopeTables() {}'
]
for name in ('TIME_MS_X10', 'ENV_LIN', 'ENV_EXP'):
    match = re.search(r'\b' + name + r'\[128\]\s*=\s*\{([^}]+)\}', contents)
    if match is None:
        raise SystemExit('Missing native table: ' + name)
    values = [int(v) for v in re.findall(r'\d+', match.group(1))]
    assert len(values) == 128
    lines.append('    static final int[] ' + name + ' = {')
    for offset in range(0, 128, 8):
        lines.append('        ' + ', '.join(str(v) for v in values[offset:offset + 8]) + ',')
    lines.append('    };')
lines.append('}')
output = '\n'.join(lines) + '\n'
target = android / 'app/src/main/java/dev/cafa/core/EnvelopeTables.java'
if '--check' in sys.argv:
    if target.read_text() != output:
        raise SystemExit('Android ENV tables differ from the current Skiff generator')
    print('PASS: all 384 native envelope/display table entries match')
else:
    target.write_text(output)
    print('Wrote', target)
