/* SPDX-License-Identifier: GPL-3.0-only */
/* Reuse Skiff's actual UI clipping, overlap and ink-alignment checks. */
#include <assert.h>
#include "../firmware/src/cafa_protocol.h"
#define FELUCCA_CAFA 1
#define FELUCCA_OTA 1
static cafa_link_t cafa;
static volatile uint8_t cafa_audio_on;
static int cafa_frame(const uint8_t *,uint32_t);
static int cafa_input(void),cafa_draw(void),cafa_leds(void);
#define UI_RENDER_NO_MAIN 1
#include "ui_render.c"
static uint32_t ota_now_ms(void) { return fm1_ms; }
static void ota_idle(void) { fm1_ms++; }
#include "../firmware/src/cafa.c"
int main(int argc,char **argv) {
 unsigned palette,large,t,p,count=0;uint8_t f[140];char path[1024],scene[80];FILE *fp;size_t n;
 assert(argc==2);rep=fopen("build/loom-native-lint.txt","w");assert(rep);
 for(palette=0;palette<NPALETTES;palette++)for(large=0;large<2;large++)for(t=1;t<=8;t++)for(p=0;p<9;p++) {
  snprintf(path,sizeof path,"%s/t%u-p%u.syx",argv[1],t,p);fp=fopen(path,"rb");assert(fp);n=fread(f,1,sizeof f,fp);fclose(fp);assert(n==103);
  snprintf(scene,sizeof scene,"%s/L%u/T%u/P%u",UI_PALETTES[palette].name,large,t,p);cur_name=scene;
  state();pal(palette);if(large)ui_prefs|=PREF_LARGE;
  nscr=npend=nrules=0;memcpy(cafa.view,f+10,92);cafa.version=4;cafa.phase=2;cafa_audio_on=1;cafa_dirty=2;cafa_native_shown=0;
  cafa_draw();lint();count++;
 }
 fprintf(rep,"%u scenes: %u lint findings, %u ink failures, %u alignment failures\n",count,nfind,nink,al_fail);fclose(rep);
 printf("Loom native lint: %u scenes, %u layout, %u ink, %u alignment findings\n",count,nfind,nink,al_fail);
 return nfind || nink || al_fail ? 1:0;
}
