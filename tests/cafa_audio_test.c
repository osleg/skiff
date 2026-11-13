/* SPDX-License-Identifier: GPL-3.0-only */
#include <stdint.h>
#include <assert.h>
#define FELUCCA_CAFA 1
#define HOST_UAC_RETURN 1
#define AUDIO_TEST_NO_MAIN
static volatile uint8_t cafa_audio_on;
static uint32_t return_calls, return_n, return_gain;
/* USB resampler is tested separately. This boundary stub verifies actual ISR
 * block size, gain, ownership and DMA destination without simulating USB HW. */
static void uac_play(int32_t *out,uint32_t n,uint32_t gain) {
    uint32_t i; return_calls++; return_n=n; return_gain=gain;
    for(i=0;i<2u*n;i++) { assert(out[i]==0); out[i]=(int32_t)i-128; }
}
#include "audio_test.c"
int main(void) {
    voice_t before; unsigned i,step; uint32_t pos;
    fresh(); trk_note_on(&trk[0],60,100);song.playing=1;song.master_q12=4096;
    before=trk[0].v[0];step=trk[0].seq_idx;pos=trk[0].seq_pos;
    host_tap_n=0;click_req=2;mi_w=4;mi_r=0;cafa_audio_on=1;
    for(i=0;i<2u*HALF_WORDS;i++)abuf[i]=12345;
    usb.config=1; mn.on[0][1]=mn.owe[0][1]=(1u<<28)|(1u<<30); mn.lost=1; mo_w=mo_r+MQO;
    fm1_alnk0_irq(); assert(mn.lost && mn.on[0][1]);
    assert(return_calls==1 && return_n==HALF_FRAMES && return_gain==2048);
    mo_r=mo_w; song.master_q12=2048; fm1_alnk0_irq();
    assert(!mn.lost && !mn.on[0][1] && !mn.owe[0][1]);
    assert(return_calls==2 && return_n==HALF_FRAMES && return_gain==1024);
    for(i=0;i<HALF_WORDS;i++)assert(abuf[i]==(int32_t)i-128);
    for(i=HALF_WORDS;i<2u*HALF_WORDS;i++)assert(abuf[i]==12345);
    assert(!memcmp(&before,&trk[0].v[0],sizeof before));
    assert(step==trk[0].seq_idx && pos==trk[0].seq_pos);
    assert(host_tap_n==0 && click_req==2 && mi_r==mi_w && host_acks==2);
    /* The other DMA half is selected without touching the first. */
    host_dma_advanced=1;host_half_reads=1;fm1_alnk0_irq();
    assert(return_calls==3 && return_n==HALF_FRAMES && host_acks==3);
    for(i=0;i<HALF_WORDS;i++) {
        assert(abuf[i]==(int32_t)i-128);
        assert(abuf[HALF_WORDS+i]==(int32_t)i-128);
    }
    host_pending=0;fm1_alnk0_irq();assert(return_calls==3 && host_acks==3);
    host_dma_advanced=host_half_reads=0;
    /* Resume a silent standalone half: DSP golden/engine tests cover actual
     * voices. Stock ANALOG signed shifts are outside this focused UB check. */
    memset(trk[0].v,0,sizeof trk[0].v);
    cafa_audio_on=0;mi_r=mi_w=0;song.playing=0;click_req=0;host_pending=FM1_AUDIO_HALF;
    fm1_alnk0_irq();assert(host_tap_n==CTL && return_calls==3 && host_acks==4);
    puts("CAFA audio ISR: one USB render per DMA half, master gain, cleared output, ownership, note-offs and standalone restoration passed");
    return 0;
}
