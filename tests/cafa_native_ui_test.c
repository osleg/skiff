/* SPDX-License-Identifier: GPL-3.0-only */
/* A genuine native-page contract, not a screenshot of a lookalike renderer. */
#include <assert.h>
#include "../firmware/src/cafa_protocol.h"
#define UI_TEST_NO_MAIN 1
#define FELUCCA_CAFA 1
#define FELUCCA_OTA 1
static cafa_link_t cafa;
static volatile uint8_t cafa_audio_on;
static int cafa_frame(const uint8_t *,uint32_t);
static int cafa_input(void),cafa_draw(void),cafa_leds(void);
static unsigned native_full_fills,native_pixels;
static void native_fill(uint32_t x,uint32_t y,uint32_t w,uint32_t h) {
 native_pixels+=w*h; if(!x && !y && w==240 && h==240)native_full_fills++;
}
static void native_blit(uint32_t x,uint32_t y,uint32_t w,uint32_t h) { (void)x;(void)y;native_pixels+=w*h; }
#define HOST_LCD_FILL_HOOK native_fill
#define HOST_LCD_BLIT_HOOK native_blit
#include "ui_test.c"
static uint32_t ota_now_ms(void) { return fm1_ms; }
static void ota_idle(void) { fm1_ms++; }
#include "../firmware/src/cafa.c"
static uint16_t image[240*240];
static void state(uint8_t *f,unsigned rev,unsigned page) {
 memset(f,0,101); f[0]=0x7d;f[1]=0x43;f[2]=0x41;f[3]=2;f[4]=CAFA_STATE;
 cafa_put14(f+5,123);cafa_put14(f+7,rev);
 f[9]=4;f[10]=page;f[11]=24; /* zero octave */
 cafa_put14(f+12,1024);cafa_put14(f+14,1008);cafa_put14(f+16,1023);cafa_put14(f+18,120);
 memcpy(f+29,"PHONE TRACK FIVE",16);memcpy(f+45,"Harmonic cloud  ",16);
 memcpy(f+61,"ATTACK  DECAY   SUSTAIN RELEASE ",32);
 cafa_put14(f+93,10);cafa_put14(f+95,48);cafa_put14(f+97,75);cafa_put14(f+99,60);
}
static void dump(const char *name) {
 FILE *f=fopen(name,"wb");unsigned i;assert(f);fprintf(f,"P6\n240 240\n255\n");
 for(i=0;i<240*240;i++){unsigned r=host_screen[i],p=((r>>8)|(r<<8))&65535u;uint8_t c[3]={(p>>11)*255/31,((p>>5)&63)*255/63,(p&31)*255/31};fwrite(c,1,3,f);}fclose(f);
}
static void palette_parity(void) {
 unsigned pal,large,variant,i,j,count=0;uint8_t f[101];
 static const unsigned env[3][4]={{0,0,0,0},{10,48,75,60},{127,127,127,127}};
 for(pal=0;pal<NPALETTES;pal++)for(large=0;large<2;large++)for(variant=0;variant<3;variant++) {
  ui_power_on();palette_set(pal);if(large)ui_prefs|=PREF_LARGE;
  state(f,1,3);for(i=0;i<4;i++)cafa_put14(f+93+2*i,env[variant][i]);
  memcpy(cafa.view,f+9,CAFA_STATE_BYTES);cafa.version=2;cafa.phase=2;cafa_audio_on=1;cafa_dirty=2;cafa_native_shown=0;
  cafa_draw();memcpy(image,host_screen,sizeof image);
  ui_power_on();palette_set(pal);if(large)ui_prefs|=PREF_LARGE;
  sel_set(4);memset(TSEL,0,sizeof *TSEL);TSEL->p[P_SLEN]=16;
  TSEL->p[P_ATK]=env[variant][0];TSEL->p[P_DEC]=env[variant][1];TSEL->p[P_SUS]=env[variant][2];TSEL->p[P_REL]=env[variant][3];
  memset(&ui,0,sizeof ui);ui.page=0;ui.force=1;fr.on=0;memset(&motion,0,sizeof motion);ui_draw_page(0);
  for(j=Y_LABEL;j<foot_y();j++)for(i=0;i<240;i++)assert(image[j*240+i]==host_screen[j*240+i]);
  count++;
 }
 printf("Native ENV pixel parity: %u palette/size/envelope scenes match actual Skiff renderer\n",count);
}
static void loom_v4(void) {
 uint8_t f[101],saved_tracks[sizeof trk],saved_ui[sizeof ui];song_t saved_song;
 unsigned pal,large,page,rev=1,i;char label[9],value[12];const char *unit;
 static const char *labels[]={
 "VOLUME% PAN+100 MUTE    BPM     ","NOTE    VEL%    GATE x10PROB%   ",
 "CUTOFF  RESO    TONE    DRIVE   ","ATTACK  DECAY   SUSTAIN RELEASE ",
 "BUS     SEND%   UNITY   ENABLED ","VOLUME% PAN+100 MUTE    TRACK   ",
 "RATE%   DEPTH%  SHAPE   SYNC    ","MODE    OCT+3   LATCH   RATE x2 ",
 "LENGTH  BPM     SWING+23HUMAN%  "};
 for(pal=0;pal<NPALETTES;pal++)for(large=0;large<2;large++) {
  ui_power_on();palette_set(pal);if(large)ui_prefs|=PREF_LARGE;
  cafa.phase=0;state(f,0,0);f[3]=4;f[4]=CAFA_HELLO;cafa_frame(f,9);so_r=so_w;
  for(page=0;page<9;page++) {
   state(f,rev++,page);f[3]=4;f[9]=7;f[11]=55;memcpy(f+61,labels[page],32);
   cafa_put14(f+18,12);f[20]=127;f[21]=127;f[22]=3;
   memcpy(saved_tracks,trk,sizeof trk);memcpy(saved_ui,&ui,sizeof ui);saved_song=song;
   cafa_frame(f,101);so_r=so_w;cafa_draw();assert(cafa_native_shown);
   /* Entry alone silences standalone; draw never changes its saved state. */
   memcpy(saved_tracks,trk,sizeof trk);memcpy(saved_ui,&ui,sizeof ui);saved_song=song;
   cafa_dirty=2;cafa_draw();
   assert(!memcmp(saved_tracks,trk,sizeof trk));assert(!memcmp(saved_ui,&ui,sizeof ui));assert(!memcmp(&saved_song,&song,sizeof song));
   native_pixels=0;cafa_draw();assert(!native_pixels);
   cafa_put14(f+7,rev++);cafa_put14(f+93,100);cafa_frame(f,101);so_r=so_w;cafa_draw();
   for(i=0;i<42;i++)cafa_draw();memcpy(image,host_screen,sizeof image);cafa_dirty=2;cafa_draw();assert(!memcmp(image,host_screen,sizeof image));
   if(pal==0 && !large){char path[64];sprintf(path,"build/loom-v4-page-%u.ppm",page);dump(path);}
  }
 }
 state(f,rev++,7);f[3]=4;memcpy(f+61,"OCT+3   INV+2   GATE x10RATE x2 ",32);
 cafa_put14(f+93,0);cafa_loom_value(f+9,0,label,value,&unit);assert(!strcmp(label,"OCT") && !strcmp(value,"-3"));
 cafa_put14(f+95,4);cafa_loom_value(f+9,1,label,value,&unit);assert(!strcmp(value,"2"));
 cafa_put14(f+97,640);cafa_loom_value(f+9,2,label,value,&unit);assert(!strcmp(value,"64.0"));
 cafa_put14(f+99,1);cafa_loom_value(f+9,3,label,value,&unit);assert(!strcmp(value,"0.5"));
 puts("Loom v4: all nine native pages, all palettes/tall cards, T8, state isolation, idle no-write, incremental/full parity and offset units passed");
}
static void loom_fixtures(void) {
 const char *dir=getenv("LOOM_V4_FIXTURES");char path[1024];uint8_t f[140];unsigned t,p;FILE *fp;size_t n;
 if(!dir)return;
 ui_power_on();cafa.phase=0;usb.config=1;
 snprintf(path,sizeof path,"%s/hello.syx",dir);fp=fopen(path,"rb");assert(fp);n=fread(f,1,sizeof f,fp);fclose(fp);
 cafa_frame(f+1,n-2);so_r=so_w;assert(cafa.phase==1 && cafa.version==4);
 for(t=1;t<=8;t++)for(p=0;p<9;p++) {
  snprintf(path,sizeof path,"%s/t%u-p%u.syx",dir,t,p);fp=fopen(path,"rb");assert(fp);n=fread(f,1,sizeof f,fp);fclose(fp);
  assert(n==103 && f[0]==240 && f[n-1]==247);cafa_frame(f+1,n-2);so_r=so_w;
  assert(cafa.phase==2 && cafa.view[0]==t-1 && cafa.view[1]==p && cafa.revision==cafa_u14(f+8));cafa_draw();
  if(t==8){snprintf(path,sizeof path,"build/loom-v4-model-%u.ppm",p);dump(path);}
 }
 for(t=0;t<2;t++) {
  snprintf(path,sizeof path,"%s/tempo%u.syx",dir,t?300:12);fp=fopen(path,"rb");assert(fp);n=fread(f,1,sizeof f,fp);fclose(fp);
  cafa_frame(f+1,n-2);so_r=so_w;assert(cafa_u14(cafa.view+9)==(t?300:12));cafa_draw();
 }
 snprintf(path,sizeof path,"%s/bye.syx",dir);fp=fopen(path,"rb");assert(fp);n=fread(f,1,sizeof f,fp);fclose(fp);
 cafa_frame(f+1,n-2);assert(!cafa.phase);
 puts("Real Loom v4 app fixtures: 72 model snapshots, 12/300 BPM, HELLO/BYE accepted and natively drawn");
}
int main(void) {
 uint8_t f[101],saved_tracks[sizeof trk],saved_ui[sizeof ui],saved_motion[sizeof motion],saved_fav[sizeof favorites];
 song_t saved_song;unsigned i,j;
 ui_power_on();usb.config=1;
 state(f,0,3);f[4]=CAFA_HELLO;cafa_frame(f,9);assert(cafa.phase==1 && cafa.version==2);
 assert(((sx_out_q[so_r+1]>>16)&255u)==2); /* ACK echoes the negotiated wire version */
 so_r=so_w;f[4]=CAFA_STATE;cafa_frame(f,101);assert(cafa.phase==2);so_r=so_w;
 for(i=0;i<NTRK;i++){trk[i].p[P_ATK]=i+60;trk[i].step[7].note[0]=i+48;}
 ui.page=7;ui.home=1;ui.menu=1;ui.confirm=1;ui.layer=2;ui.take_t=20;ui.hot_t=12;
 fr.on=1;motion.on=63;motion.count=1;motion.event[0].id=P_ATK;motion.event[0].place=4u<<6;
 memcpy(saved_tracks,trk,sizeof trk);memcpy(saved_ui,&ui,sizeof ui);memcpy(saved_motion,&motion,sizeof motion);
 memcpy(saved_fav,&favorites,sizeof favorites);saved_song=song;
 native_pixels=native_full_fills=0;cafa_draw();
 assert(!native_full_fills && native_pixels && cafa_native_shown);
 assert(!memcmp(saved_tracks,trk,sizeof trk) && !memcmp(saved_ui,&ui,sizeof ui));
 assert(!memcmp(saved_motion,&motion,sizeof motion) && !memcmp(saved_fav,&favorites,sizeof favorites));
 assert(!memcmp(&saved_song,&song,sizeof song) && fr.on && !cafa_native_view && tsel==&trk[song.sel]);
 memcpy(image,host_screen,sizeof image);dump("build/cafa-native-env.ppm");
 /* With no state change or animation, native rendering performs no LCD writes. */
 native_pixels=0;cafa_draw();assert(!native_pixels);
 /* Native ENV's actual ordinary renderer, supplied the equivalent model. Its
  * cards and graph must match byte-for-byte, not just resemble one another. */
 ui_power_on();sel_set(4);memset(TSEL,0,sizeof *TSEL);TSEL->p[P_SLEN]=16;
 TSEL->p[P_ATK]=10;TSEL->p[P_DEC]=48;TSEL->p[P_SUS]=75;TSEL->p[P_REL]=60;
 memset(&ui,0,sizeof ui);ui.page=0;ui.force=1;fr.on=0;memset(&motion,0,sizeof motion);
 ui_draw_page(0);
 for(j=Y_LABEL;j<foot_y();j++)for(i=0;i<240;i++)assert(image[j*240+i]==host_screen[j*240+i]);
 /* Return to linked mode, update a real ENV value and verify isolation and
  * incremental equivalence when the native hot-knob timer has settled. */
 state(f,1,3);cafa_frame(f,101);so_r=so_w;cafa_dirty=2;cafa_draw();
 memcpy(saved_tracks,trk,sizeof trk);saved_song=song;memcpy(saved_ui,&ui,sizeof ui);
 state(f,2,3);cafa_put14(f+95,127);cafa_frame(f,101);so_r=so_w;
 native_full_fills=0;cafa_draw();assert(!native_full_fills && cafa_native_cache.hot_col==1 && cafa_native_cache.hot_t);
 for(i=0;i<42;i++)cafa_draw();assert(!cafa_native_cache.hot_t);
 memcpy(image,host_screen,sizeof image);cafa_dirty=2;cafa_draw();assert(!memcmp(image,host_screen,sizeof image));
 assert(!memcmp(saved_tracks,trk,sizeof trk) && !memcmp(&saved_song,&song,sizeof song) && !memcmp(saved_ui,&ui,sizeof ui));
 /* A playing 1024-step timeline is header-only on ENV; never index a native
  * 64-step pattern with its absolute phone step. */
 state(f,3,3);f[11]=55;cafa_frame(f,101);so_r=so_w;cafa_draw();dump("build/cafa-native-env-playing.ppm");
 assert(!memcmp(saved_tracks,trk,sizeof trk));
 /* Switching back to a legacy proxy page must replace all native pixels. */
 state(f,4,0);cafa_frame(f,101);so_r=so_w;cafa_draw();assert(!cafa_native_shown);
 memcpy(image,host_screen,sizeof image);cafa_dirty=2;cafa_draw();assert(!memcmp(image,host_screen,sizeof image));
 state(f,5,3);cafa_frame(f,101);so_r=so_w;native_full_fills=0;cafa_draw();assert(cafa_native_shown && !native_full_fills);
 /* Session exit still restores untouched standalone project data and route. */
 state(f,5,3);f[4]=CAFA_BYE;cafa_frame(f,9);assert(!cafa.phase && !cafa_audio_on);
 assert(!memcmp(saved_tracks,trk,sizeof trk));
 printf("Native CAFA ENV: real Skiff cards/graph pixel parity, project/UI isolation, hot-knob updates, 1024-step header, page return and v2 exit passed; track projection=%zu bytes, UI cache=%zu bytes\n",sizeof(track_t),sizeof cafa_native_cache);
 palette_parity();
 loom_v4();
 loom_fixtures();
 return 0;
}
