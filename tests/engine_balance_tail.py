# SPDX-License-Identifier: GPL-3.0-only
"""Independent dither diagnostic; does not replace or relax tests/tail_test.c.

Coherently average 256 independent USB quantizations of the same DAC signal.
Random dither falls; any deterministic harmonic remains. The temporary copy
pads the upstream test's arrays by CTL for its final partial block.
"""
import os
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
source = (root / "tests/tail_test.c").read_text()
assert source.count("int main(void)") == 1
assert source.count("[SECS * 44100u]") == 2
source = source.replace("int main(void)", "int upstream_tail_main(void)")
source = source.replace("[SECS * 44100u]", "[SECS * 44100u + CTL]")
source += '\nint main(void) {\n    static const int32_t masters[2]={4088,1024};\n    int fails=0;\n    for (uint32_t eq=0;eq<3;eq++) for (uint32_t mi=0;mi<2;mi++) {\n        uint32_t n=render(masters[mi],eq);\n        /* Coherent averaging: sum signed samples, not FFT magnitudes. */\n        for (uint32_t i=0;i<n;i++) t_usb[i]=0;\n        for (uint32_t pass=0;pass<256;pass++) {\n            uac_rng=0x2545F491u+pass*0x9e3779b9u;\n            for (uint32_t i=0;i<n;i++) {\n                int32_t x=t_dac[i];\n                t_usb[i]+=uac_s16(uac_dith(x>>7,(uint32_t)(x&127)));\n            }\n        }\n        double h, ref=ratio_at(t_usb,n,32768.0*256,10,3,&h), growth=-99;\n        for (uint32_t down=30;down<=60;down+=10) {\n            double r=ratio_at(t_usb,n,32768.0*256,down,3,&h);\n            if (r-ref>growth) growth=r-ref;\n        }\n        printf("USB 256-seed coherent average eq=%u master=%d: H3 growth %.2f dB (%s)\\n",eq,masters[mi],growth,growth<=3?"pass":"FAIL");\n        fails+=growth>3;\n    }\n    return fails!=0;\n}\n'
p = out / "tail_usb_average.c"
p.write_text(source)
binary = out / "tail_usb_average"
subprocess.run([os.environ.get("CC", "cc"), "-O2", "-w", "-Itests", "-Ibuild/gen", "-Ifirmware/src",
                "-DFELUCCA_ENGINE_BALANCE=1", str(p), "-lm", "-o", str(binary)], cwd=root, check=True)
subprocess.run([str(binary)], cwd=root, check=True)
