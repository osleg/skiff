/* SPDX-License-Identifier: GPL-3.0-only */
/* Binary subprocess peer for the Java Link tests. Uses production USB-MIDI
 * assembly, the one-frame mailbox, CAFA mode changes and outgoing MIDI queue.
 * Only the main-loop service cadence and physical HAL are simulated. */
#include <assert.h>
#include "../firmware/src/cafa_protocol.h"
#define UI_TEST_NO_MAIN 1
#define FELUCCA_CAFA 1
#define FELUCCA_OTA 1
static cafa_link_t cafa;
static volatile uint8_t cafa_audio_on;
static int cafa_frame(const uint8_t *,uint32_t);
static int cafa_input(void),cafa_draw(void),cafa_leds(void);
#include "ui_test.c"
static uint32_t ota_now_ms(void) { return fm1_ms; }
static void ota_idle(void) { fm1_ms++; }
#include "../firmware/src/cafa.c"

static uint32_t read32(void)
{
    uint32_t value=0;
    unsigned k;
    for (k=0;k<4;k++) { int b=getchar(); if (b==EOF) exit(0); value=(value<<8)|(uint8_t)b; }
    return value;
}
static void write32(uint32_t value)
{
    int k;
    for (k=3;k>=0;k--) putchar((value>>(k*8))&255u);
}
int main(void)
{
    uint8_t frame[256],reply[SXQ*3];
    ui_power_on(); usb.config=1;
    for (;;) {
        unsigned service,frames,j,n=0;
        fm1_ms=read32(); service=read32(); frames=read32();
        assert(frames<100);
        for (j=0;j<frames;j++) {
            unsigned size=read32(),i=0;
            assert(size<=sizeof frame);
            assert(fread(frame,1,size,stdin)==size);
            while (i<size) {
                unsigned k=size-i>=3u?3u:size-i;
                unsigned cin=k==3u && i+3u<size?4u:k==3u?7u:4u+k;
                midi_in_event(cin|(uint32_t)frame[i]<<8|
                    (k>1?(uint32_t)frame[i+1]<<16:0)|(k>2?(uint32_t)frame[i+2]<<24:0));
                i+=k;
            }
        }
        if (service) {
            const uint8_t *p;
            uint32_t size;
            if (ota_frame_get(&p,&size)) { cafa_frame(p,size); ota_frame_done(); }
            cafa_input();
        }
        while (so_r!=so_w) {
            uint32_t event=sx_out_q[so_r++%SXQ];
            unsigned cin=event&15u,k=cin==4?3:cin-4,i;
            assert(k<=3 && n+k<=sizeof reply);
            for (i=0;i<k;i++) reply[n++]=(event>>(8+8*i))&255;
        }
        write32(cafa.phase); write32(cafa.revision); write32(sx_ready); write32(n);
        fwrite(reply,1,n,stdout); fflush(stdout);
    }
}
