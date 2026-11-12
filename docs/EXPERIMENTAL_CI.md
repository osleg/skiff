# Experimental firmware CI

The `Experimental firmware` workflow builds **FM-1 development artifacts**, not a
release. It never flashes a device, creates a GitHub release, or deploys a site.
It runs on pushes to `experimental/**` and `main`, relevant pull requests, and
manual dispatch. A new workflow may not appear under manual dispatch until it
is on the default branch; the experimental branch's push is its initial trigger.
Forks may require their owner to enable Actions in GitHub first.

## Outputs and failures

Each run has independent jobs:

- **Host balance:** original flag-off and frozen pre-optimization flag-on golden
  renders, calibration, arithmetic UBSan, DAC/USB quiet tails, multicore
  equivalence and performance effects. USB harmonic measurements coherently
  average dither without relaxing the 3 dB limit; per-sample noise bounds and
  deliberate bad-quantizer mutations check that real defects still fail.
- **FM-1 target:** `FELUCCA_ENGINE_BALANCE=0` and `=1`, each built with `BOARD=fm1`
  and without `--release`. Both run the unchanged target cost checks. The full
  upstream aggregate test suite runs against the flag-off build. Target image,
  RAM, pool and loader checks are enforced by the normal build. The ELF section
  delta is recorded by `tools/size_diff.py`. Static target instruction estimates
  are not measured hardware cycles; Linux skips the macOS host instruction counter.
- **Browser simulator:** one job per flag value, Emscripten build plus the
  existing native/Wasm byte-for-byte audio and interaction checks. Download and
  extract its artifact, run `python3 -m http.server 8790` in the package folder,
  then open `http://localhost:8790/emu/`. This is not hardware/USB timing emulation.

The optimized enabled build passes the original target cost budgets with their
existing 10% tolerance. Neither the estimator nor budget files were changed.
Any future failing check still fails its job; artifacts upload for diagnosis.

Artifacts last 14 days. Firmware artifacts include separately named `baseline/`
and `experimental-balanced/` folders, `.fwsc`, `.bin`, `.elf`, disassembly, loader,
logs, build provenance, SHA256 checksums, licences and source attribution. Check
job logs/status files: **an available artifact does not mean every test passed**.
Simulator artifacts include the page, Wasm, worklet, fonts and sibling `panel.js`.

Default source builds still have engine balance **off**. Enabled firmware changes
existing mixes and is not release-ready. Read [ENGINE_BALANCE.md](ENGINE_BALANCE.md)
and do not install an experimental artifact without understanding the firmware
recovery process and the remaining listening/target-timing checks.

## Dependency and trust pins

- GitHub-owned checkout, setup-python and upload-artifact actions are pinned to
  full commit SHAs. Checkout credentials are not persisted; workflow permissions
  are read-only `contents`. There are no repository secrets or publish tokens.
- Runner image: `ubuntu-24.04`, Python `3.12.12`. The hosted image/system packages
  can change; this workflow does not claim a fully hermetic operating system.
- Font tools: Pillow 13.0.0 and fonttools 4.61.1 from official PyPI wheels, locked
  by version and SHA256 in `tools/ci/requirements.txt`; source distributions are
  refused. These match the versions used for the local calibration build.
- JieLi toolchain: version `20260730.1`, the versioned vendor CDN URL resolved from
  the repository's official `https://pkgman.jieliapp.com/s/linux-toolchain` route:
  `https://jl-update.oss-cn-shenzhen.aliyuncs.com/jieli-linux-toolchains-20260730.1.tar.xz`.
  SHA256 `a1545cddbc451a06bac43eb5cd96d831470c42a97c5c0b1f98879a20fc7647bd` is
  verified **before extraction or execution**. Two downloads matched. No vendor
  signature was found; this pins reviewed bytes, not independent provenance.
  If the vendor replaces them, the workflow fails closed.
- Official JieLi SDK: `https://gitee.com/Jieli-Tech/fw-AC79_AIoT_SDK.git`, commit
  `d179b4484759423312073f5fbb232501aa491047` (peeled tag
  `AC79NN_SDK_V1.2.1_2023-12-13`). Only the three required packaging inputs
  (15,574 bytes) are bundled unmodified under `tools/ci/sdk/`, with origin and
  licence, because Gitee TLS connections timed out from GitHub hosted runners.
  There is no unverified mirror or live SDK fetch. The three files are verified against
  the pre-existing SHA256 values in `tools/build.py`. The SDK's Apache-2.0 licence
  matches `LICENSES/Apache-2.0.txt` and is included in artifacts.
- Official Emscripten SDK: `https://github.com/emscripten-core/emsdk.git`, tag
  4.0.23 pinned to commit `c0bb220cb6e6f4e0fabb6f6db9efd53390ef5e56`, version
  `4.0.23`, release `aaa43392544d695232b70eda706d751f18980c2a`. Its installer
  downloads the versioned compiler and Node archives from the official sources
  in that pinned manifest. This relies on Emscripten's installer/CDN trust; those
  transitive archives are not separately SHA256-locked by this workflow.

Nothing from an unverified mirror is substituted if an official source fails.
Changes to these pins should be reviewed and rerun through the same tests.

## Local reproduction

On Linux x86-64 with Python 3.12, a C compiler, binutils, Git and curl:

```sh
python3 -m pip install --require-hashes --only-binary=:all: -r tools/ci/requirements.txt
tools/ci/get_dependencies.sh /tmp/skiff-deps
set -a
. /tmp/skiff-deps/paths.env
set +a
tools/ci/build_firmware.sh
sh tests/run_engine_balance_tests.sh
```

The firmware script records failures and still builds the other variant where
possible; its final exit status remains nonzero if any build, budget, or aggregate
check failed. The balance script fails on any calibration, frozen audio hash,
arithmetic, tail or mutation-sensitivity regression. Neither script rewrites
committed budgets/goldens.
