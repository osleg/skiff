/* SPDX-License-Identifier: GPL-3.0-only */
/* Cross-language fixture reader: Java's real Protocol/Control serialization into the firmware parser. */
#include <assert.h>
#include <stdio.h>
#include "../firmware/src/cafa_protocol.h"
int main(int argc,char **argv) {
    cafa_link_t c={0}; unsigned i; uint8_t frame[140];
    assert(argc==3);
    for(i=1;i<3;i++) {
        FILE *f=fopen(argv[i],"rb");size_t n;int a;assert(f);
        n=fread(frame,1,sizeof frame,f);assert(feof(f));fclose(f);
        assert(n>=11 && frame[0]==0xf0 && frame[n-1]==0xf7);
        a=cafa_receive(&c,frame+1,(uint32_t)n-2,10*i);
        assert(a==(i==1?1:2));
    }
    assert(c.phase==2 && c.session==42 && c.view[0]==5 && c.view[1]==1);
    assert(cafa_u14(c.view+3)==1024 && cafa_u14(c.view+5)==1008);
    assert(cafa_u14(c.view+7)==16383 && cafa_u14(c.view+9)==120);
    assert((cafa_u14(c.view+11)|((unsigned)c.view[13]<<14))==32768);
    puts("CAFA Java -> firmware wire fixture passed: session42, track6, steps1009-1024, step1024 note, names and knob payload accepted");return 0;
}
