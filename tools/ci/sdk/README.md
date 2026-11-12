# Minimal official JieLi SDK packaging inputs

These three **unmodified** files are copied from the official JieLi Technology
AC79 SDK, under Apache-2.0 (included as `LICENSE`). They are packaging inputs for
the FM-1 firmware, not host executables. No other SDK source or tools are bundled.

Source: https://gitee.com/Jieli-Tech/fw-AC79_AIoT_SDK
Tag: `AC79NN_SDK_V1.2.1_2023-12-13`
Peeled commit: `d179b4484759423312073f5fbb232501aa491047`
Original paths: `cpu/wl82/tools/uboot.boot`, `cpu/wl82/tools/cfg_tool.bin`, and
`cpu/wl82/tools/cfg/eq_cfg_hw.bin`, preserved here.

The official commit was fetched and all three bytestrings were verified against
the existing `tools/build.py` SHA256 pins on 2026-11-12. The included licence is
byte-identical to the SDK root licence and `LICENSES/Apache-2.0.txt`. No upstream
NOTICE applies to these three files. Copyright remains with JieLi Technology.

Why included: GitHub's hosted runner timed out connecting over TLS to Gitee. The
15,574-byte set avoids an unreliable 263 MB SDK Git download and allows CI to use
only the exact, already-pinned packaging inputs. The CI helper checks every hash
before packaging and copies this minimal tree to its isolated dependency folder.
Normal local builds may still use a full SDK checkout via `AC79_SDK`.

| File | SHA256 |
| --- | --- |
| `uboot.boot` | `4e3b4c220dc96641cb5a723f41e68ce41d5261ae9434bb33fbd7f2c59976ded4` |
| `cfg_tool.bin` | `276579954f076886a6a7694f65dc71c034a63a2c204b76749065c0ac7b010d1b` |
| `cfg/eq_cfg_hw.bin` | `41167491bffed4651750719c973d2758adeb9021a5670d02d6a53c85ed80ea7d` |
