/* SPDX-License-Identifier: GPL-3.0-only */
/* Compare Java-generated ADSR ticks against the actual standalone Skiff code. */
#define main hostsim_main
#include "hostsim.c"
#undef main
#include <assert.h>
int main(int argc,char **argv) {
 FILE *f;track_t t={0};voice_t v={0};int id,last=-1,tick,a,d,s,r,gate,want,stage,n;unsigned count=0;
 assert(argc==2);f=fopen(argv[1],"r");assert(f);
 while((n=fscanf(f,"%d%d%d%d%d%d%d%d%d",&id,&tick,&a,&d,&s,&r,&gate,&want,&stage))==9){
  if(id!=last){memset(&v,0,sizeof v);v.stage=v.active=1;last=id;}
  t.p[P_ATK]=a;t.p[P_DEC]=d;t.p[P_SUS]=s;t.p[P_REL]=r;t.p[P_ESYNC]=0;esync_coef(&t);
  if(!gate && (v.stage==1 || v.stage==2))v.stage=3;
  env_tick(&t,&v);
  if(v.env!=want || v.stage!=stage){fprintf(stderr,"case%d tick%d: native %d/%u Java %d/%d\n",id,tick,v.env,v.stage,want,stage);return 1;}
  count++;
 }
 assert(n==EOF && count);fclose(f);printf("CAFA native ADSR: %u Java ticks exactly match Skiff Q24 envelope and stage\n",count);return 0;
}
