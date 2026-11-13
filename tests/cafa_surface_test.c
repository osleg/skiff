/* SPDX-License-Identifier: GPL-3.0-only */
#include <assert.h>
#include "../firmware/src/cafa_protocol.h"
#define UI_TEST_NO_MAIN 1
#define FELUCCA_CAFA 1
#define FELUCCA_OTA 1
static cafa_link_t cafa;
static volatile uint8_t cafa_audio_on;
static int cafa_frame(const uint8_t *,uint32_t);
static int cafa_input(void),cafa_draw(void),cafa_leds(void);
static unsigned cafa_full_fills, cafa_pixel_writes;
static void cafa_count_fill(uint32_t x,uint32_t y,uint32_t w,uint32_t h) {
 cafa_pixel_writes+=w*h;
 if(!x && !y && w==240 && h==240)cafa_full_fills++;
}
static void cafa_count_blit(uint32_t x,uint32_t y,uint32_t w,uint32_t h) {
 (void)x;(void)y;cafa_pixel_writes+=w*h;
}
#define HOST_LCD_FILL_HOOK cafa_count_fill
#define HOST_LCD_BLIT_HOOK cafa_count_blit
#include "ui_test.c"
static uint32_t ota_now_ms(void) { return fm1_ms; }
static void ota_idle(void) { fm1_ms++; }
#include "../firmware/src/cafa.c"
static void message(uint8_t *f,int cmd,int sid,int rev) {
 memset(f,0,101); f[0]=0x7d; f[1]=0x43; f[2]=0x41; f[3]=1; f[4]=cmd;
 cafa_put14(f+5,sid); cafa_put14(f+7,rev);
 cafa_put14(f+12,1024); cafa_put14(f+14,1008); cafa_put14(f+16,1023); cafa_put14(f+18,120);
 f[10]=1; f[20]=0x15; f[21]=0x2a; f[22]=2;
 memcpy(f+29,"NIGHT SEQUENCE  ",16); memcpy(f+45,"Seven-saw       ",16);
 memcpy(f+61,"NOTE    LENGTH  GATE    BPM     ",32);
 cafa_put14(f+93,72);cafa_put14(f+95,1024);cafa_put14(f+97,70);cafa_put14(f+99,120);
}
static unsigned drain(uint8_t *f) {
 unsigned n=0;
 while(so_r!=so_w) { uint32_t w=sx_out_q[so_r++%SXQ]; unsigned cin=w&15,k=cin==4?3:cin-4,j;
   assert(k<=3); for(j=0;j<k;j++) f[n++]=(w>>(8+8*j))&255;
 }
 return n;
}
static void same_as_full(void) {
 static uint16_t incremental[240*240];
 memcpy(incremental,host_screen,sizeof incremental);
 cafa_dirty=2;cafa_draw();
 assert(!memcmp(incremental,host_screen,sizeof incremental));
 cafa_full_fills=cafa_pixel_writes=0;
}
static void flicker_regression(void) {
 uint8_t f[101],out[128]; unsigned i,revision=1;
 message(f,CAFA_HELLO,46,0);cafa_frame(f,9);drain(out);
 message(f,CAFA_STATE,46,revision++);cafa_frame(f,101);drain(out);
 cafa_full_fills=cafa_pixel_writes=0;cafa_draw();assert(cafa_full_fills==1);
 cafa_full_fills=cafa_pixel_writes=0;
 /* New revisions and physical-key LEDs alone must never touch the LCD. */
 cafa_put14(f+7,revision++);cafa_frame(f,101);drain(out);cafa_draw();
 assert(!cafa_pixel_writes);
 f[25]=1;cafa_put14(f+7,revision++);cafa_frame(f,101);drain(out);cafa_draw();
 assert(!cafa_pixel_writes);
 for(i=0;i<32;i++) {
  cafa_put14(f+16,1008+i%16);cafa_put14(f+7,revision++);
  cafa_frame(f,101);drain(out);cafa_draw();
  assert(!cafa_full_fills && cafa_pixel_writes<=1920);
  same_as_full();
 }
 /* Parameter/header/name/step/transport updates must erase only their regions. */
 for(i=0;i<7;i++) {
  if(i==0)cafa_put14(f+93,100);
  if(i==1)cafa_put14(f+18,99);
  if(i==2)memcpy(f+29,"SHORT           ",16);
  if(i==3)f[20]^=1;
  if(i==4)f[11]^=1;
  if(i==5){f[9]=5;f[10]=2;}
  if(i==6){cafa_put14(f+14,0);cafa_put14(f+12,16);cafa_put14(f+16,0);}
  cafa_put14(f+7,revision++);cafa_frame(f,101);drain(out);cafa_draw();
  assert(!cafa_full_fills && cafa_pixel_writes<15000);
  same_as_full();
 }
 message(f,CAFA_BYE,46,0);cafa_frame(f,9);
 message(f,CAFA_HELLO,47,0);cafa_frame(f,9);drain(out);
 message(f,CAFA_STATE,47,1);cafa_frame(f,101);drain(out);
 cafa_full_fills=cafa_pixel_writes=0;cafa_draw();assert(cafa_full_fills==1);
 message(f,CAFA_BYE,47,0);cafa_frame(f,9);
 puts("CAFA redraw: identical/LED-only states draw nothing; moving playhead and changed fields update regions only; incremental pixels equal full render; reconnect invalidates cache");
}
int main(void) {
 uint8_t f[101],out[128]; unsigned i,k; FILE *ppm;
 ui_power_on(); usb.config=1; fm1_ms=0;
 /* Incomplete HELLO must not stop standalone transport or voices. */
 trk_note_on(&trk[0],60,100); song.playing=1;
 message(f,CAFA_HELLO,41,0); cafa_frame(f,9); drain(out);
 fm1_ms=1500; cafa_input(); assert(!cafa.phase && song.playing && voices_busy());
 message(f,CAFA_HELLO,41,0); cafa_frame(f,9); drain(out);
 message(f,CAFA_BYE,41,0); cafa_frame(f,9); assert(song.playing && voices_busy());
 fm1_ms=0;
 trk[0].step[0].note[0]=60;
 step_t saved=trk[0].step[0]; int master=song.master_q12;
 memset(trk[0].v,0x33,sizeof trk[0].v); song.playing=1;
 message(f,CAFA_HELLO,42,0); assert(cafa_frame(f,9)); assert(cafa.phase==1); assert(drain(out)==12); assert(out[5]==CAFA_ACK);
 mn.on[0][1]=(1u<<28)|(1u<<30); mn.ch[0]=0;
 mo_w=mo_r+MQO; /* full MIDI queue: both offs must remain owed, not only the first */
 memset(&fx,0x33,sizeof fx);
 message(f,CAFA_STATE,42,0); assert(cafa_frame(f,101)); assert(cafa.phase==2 && cafa_audio_on && !song.playing);
 assert(drain(out)==12 && out[5]==CAFA_ACK && cafa_u14(out+8)==0);
 assert(mn.lost && mn.owe[0][1]==((1u<<28)|(1u<<30))); mo_r=mo_w;
 assert(!fx.dly_w && !fx.cho_w && !fx.cho_ph && !fx.dly_lp && !fx.rtype && !fx.sp_w);
 assert(!fx.sp_lp && !fx.sp_hp && !fx.sp_he && !fx.sp_size && !fx.sp_ph && !fx.cho_y);
 assert(!fx.dmode && !fx.dclr && !fx.dly_lpr && !fx.dpl && !fx.dpr && !fx.dmp && !fx.dsp);
 for(i=0;i<4;i++) assert(!fx.comb_i[i] && !fx.comb_lp[i] && !fx.ph_s[i]);
 for(i=0;i<2;i++) assert(!fx.ap_i[i]);
 for(i=0;i<sizeof trk[0].v;i++) assert(!((uint8_t *)trk[0].v)[i]);
 assert(!memcmp(&saved,&trk[0].step[0],sizeof saved)); assert(song.master_q12==master);
 fm1_in.buttons=1u<<panel.btn[B_PLAY]; fm1_in.notes=5;
 host_enc[panel.enc[0]]=3*panel.dir[0];
 assert(cafa_input()); assert(drain(out)==31); assert(out[5]==CAFA_INPUT && cafa_u14(out+8)==1);
 assert(cafa_u14(out+10)==(1u<<B_PLAY)); assert(out[12]==5); assert(cafa_u14(out+16)==8195);
 assert(cafa_input()); assert(drain(out)==0);
 fm1_in.buttons=fm1_in.notes=0; assert(cafa_input()); assert(drain(out)==31); assert(cafa_u14(out+8)==2);
 /* A complete short tap survives the physical sample already being released. */
 host_pressed=1u<<panel.btn[B_PLAY]; host_notes=4;
 assert(cafa_input()); assert(drain(out)==31); assert(cafa_u14(out+10)==(1u<<B_PLAY) && out[12]==4 && cafa_u14(out+8)==3);
 assert(cafa_input()); assert(drain(out)==31); assert(cafa_u14(out+10)==0 && out[12]==0 && cafa_u14(out+8)==4);
 /* A new edge while the last snapshot was held needs a release then a press. */
 fm1_in.buttons=1u<<panel.btn[B_PLAY]; fm1_in.notes=4; cafa_input(); assert(drain(out)==31);
 host_pressed=fm1_in.buttons; host_notes=4; host_enc[panel.enc[0]]=2*panel.dir[0];
 cafa_input(); assert(drain(out)==62);
 assert(cafa_u14(out+10)==0 && out[12]==0 && cafa_u14(out+8)==6 && cafa_u14(out+16)==8192);
 assert(cafa_u14(out+41)==(1u<<B_PLAY) && out[43]==4 && cafa_u14(out+39)==7 && cafa_u14(out+47)==8194);
 fm1_in.buttons=fm1_in.notes=0; cafa_input(); assert(drain(out)==31);
 assert(cafa_draw()); assert(cafa_leds());
 ppm=fopen("build/cafa-surface.ppm","wb"); assert(ppm); fprintf(ppm,"P6\n240 240\n255\n");
 for(i=0;i<240*240;i++) { unsigned raw=host_screen[i],p=((raw>>8)|(raw<<8))&65535u; uint8_t rgb[3]={(p>>11)*255/31,((p>>5)&63)*255/63,(p&31)*255/31}; fwrite(rgb,1,3,ppm); } fclose(ppm);
 fm1_ms=100; f[9]=2; assert(cafa_frame(f,101)); assert(cafa.view[0]==0 && cafa.last_ms==0);
 fm1_in.notes=1; fm1_ms=1500; assert(cafa_input()); assert(!cafa.phase && !cafa_audio_on && kb_boot_hold && cafa_release_guard);
 fm1_in.notes=0; assert(cafa_input()); assert(!kb_boot_hold && !cafa_release_guard);
 message(f,CAFA_HELLO,43,0); cafa_frame(f,9); drain(out); message(f,CAFA_STATE,43,0); cafa_frame(f,101); assert(cafa.phase==2); drain(out);
 assert(cafa_input()); assert(drain(out)==31 && cafa_u14(out+8)==1);
 message(f,CAFA_HEARTBEAT,43,0); cafa_frame(f,9); assert(drain(out)==12 && out[5]==CAFA_ACK && cafa_u14(out+8)==0 && out[10]==1);
 /* A newer identical snapshot advances/ACKs the lease without a full LCD clear. */
 cafa_draw(); assert(!cafa_dirty);
 message(f,CAFA_STATE,43,1); cafa_frame(f,101);
 assert(!cafa_dirty && drain(out)==12 && cafa_u14(out+8)==1);
 message(f,CAFA_STATE,43,2); f[9]=1; cafa_frame(f,101);
 assert(cafa_dirty && cafa.view[0]==1 && drain(out)==12 && cafa_u14(out+8)==2);
 usb.config=0; cafa_input(); assert(!cafa.phase); fm1_in.notes=fm1_in.buttons=0; cafa_input();
 usb.config=1; message(f,CAFA_HELLO,44,0); cafa_frame(f,9); drain(out); message(f,CAFA_STATE,44,0); cafa_frame(f,101);
 drain(out);
 so_w=so_r+SXQ; cafa_input(); assert(!cafa.phase && cafa_release_guard); so_r=so_w; cafa_input();
 message(f,CAFA_HELLO,45,0); cafa_frame(f,9); drain(out); message(f,CAFA_STATE,45,0); cafa_frame(f,101);
 drain(out);
 message(f,CAFA_BYE,45,0); cafa_frame(f,9); assert(!cafa.phase && !cafa_audio_on);
 flicker_regression();
 assert(!memcmp(&saved,&trk[0].step[0],sizeof saved)); assert(song.master_q12==master);
 puts("CAFA surface: passed voice mute, project preservation, input serialization, redraw, stale state, timeout, disconnect, release guard, reconnect, queue saturation and BYE");
 return 0;
}
