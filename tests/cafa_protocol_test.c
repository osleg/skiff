/* SPDX-License-Identifier: GPL-3.0-only */
#include <assert.h>
#include <string.h>
#include <stdio.h>
#include "../firmware/src/cafa_protocol.h"
static void frame(uint8_t *f,int cmd,int sid,int rev) {
 memset(f,0,101); f[0]=0x7d; f[1]=0x43; f[2]=0x41; f[3]=1; f[4]=cmd;
 cafa_put14(f+5,sid); cafa_put14(f+7,rev);
 cafa_put14(f+12,1024); cafa_put14(f+14,1008); cafa_put14(f+16,1023); cafa_put14(f+18,120);
}
static void versions(void) {
 cafa_link_t c,before,trial; uint8_t f[101]; unsigned version,phase,command,flags,k;
 for(version=1;version<=4;version++) {
  memset(&c,0,sizeof c);frame(f,CAFA_HELLO,91,0);f[3]=(uint8_t)version;
  assert(cafa_receive(&c,f,9,100)==1 && c.version==version && c.phase==1);
  before=c;
  /* No frame from another protocol version may replace or renew this session,
   * including another HELLO with the same session ID. Cover both phases. */
  for(phase=1;phase<=2;phase++) {
   if(phase==2) {
    frame(f,CAFA_STATE,91,1);f[3]=(uint8_t)version;
    assert(cafa_receive(&c,f,101,110)==2);before=c;
   }
   for(command=CAFA_HELLO;command<=CAFA_INPUT;command++) {
    frame(f,(int)command,91,2);f[3]=(uint8_t)(version%4+1);
    assert(!cafa_receive(&c,f,command==CAFA_STATE?101:9,120));
    assert(!memcmp(&c,&before,sizeof c));
   }
   frame(f,CAFA_HELLO,92,0);f[3]=(uint8_t)version;
   assert(!cafa_receive(&c,f,9,120));assert(!memcmp(&c,&before,sizeof c));
  }
  /* Every flags byte: v1 uses only transport/mute; v2 adds octave -3..+3. */
  for(flags=0;flags<128;flags++) {
   trial=c;frame(f,CAFA_STATE,91,2);f[3]=(uint8_t)version;f[11]=(uint8_t)flags;
   if(flags<=((version==1 || version==3)?7u:55u)) {
    assert(cafa_receive(&trial,f,101,121)==3 && trial.view[2]==flags && trial.last_ms==121);
   } else {assert(!cafa_receive(&trial,f,101,121));assert(!memcmp(&trial,&c,sizeof c));}
  }
  frame(f,CAFA_HELLO,91,0);f[3]=(uint8_t)version;
  assert(cafa_receive(&c,f,9,122)==1 && c.phase==2 && c.revision==1);
  frame(f,CAFA_HEARTBEAT,91,1);f[3]=(uint8_t)version;
  assert(cafa_receive(&c,f,9,123)==5 && c.last_ms==123);
  frame(f,CAFA_BYE,91,1);f[3]=(uint8_t)version;before=c;
  assert(cafa_receive(&c,f,9,124)==4);assert(!memcmp(&c,&before,sizeof c));
  /* cafa_frame owns the actual leave. Its reset permits a new-version session. */
  c.phase=0;frame(f,CAFA_HELLO,92,0);f[3]=(uint8_t)(version%4+1);
  assert(cafa_receive(&c,f,9,125)==1 && c.version==version%4+1 && c.session==92 && c.revision==0);
 }
 memset(&c,0,sizeof c);frame(f,CAFA_HELLO,93,0);f[3]=2;assert(cafa_receive(&c,f,9,200)==1);
 frame(f,CAFA_STATE,93,1);f[3]=2;f[10]=3;f[11]=55;
 for(k=0;k<4;k++)cafa_put14(f+93+2*k,k&1?127:0);
 assert(cafa_receive(&c,f,101,201)==2);before=c;
 for(k=0;k<4;k++) {
  unsigned bad;
  for(bad=128;bad<=16383;bad=16383) {
   frame(f,CAFA_STATE,93,2);f[3]=2;f[10]=3;cafa_put14(f+93+2*k,bad);
   assert(!cafa_receive(&c,f,101,202));assert(!memcmp(&c,&before,sizeof c));
   if(bad==16383)break;
  }
 }
 /* A malformed v2 ENV cannot partly replace otherwise valid values. */
 frame(f,CAFA_STATE,93,2);f[3]=2;f[10]=3;
 for(k=0;k<101;k++){assert(!cafa_receive(&c,f,k,203));assert(!memcmp(&c,&before,sizeof c));}
 for(k=0;k<101;k++){f[k]|=128;assert(!cafa_receive(&c,f,101,203));assert(!memcmp(&c,&before,sizeof c));f[k]&=127;}
 for(k=0;k<128;k++)if(k!=1 && k!=2) {
  trial=c;frame(f,CAFA_HELLO,93,0);f[3]=(uint8_t)k;
  assert(!cafa_receive(&trial,f,9,204));assert(!memcmp(&trial,&c,sizeof c));
 }
 puts("CAFA v1/v2: negotiated-version isolation, all flags, native ADSR bounds, malformed rejection, lease protection and cross-version reconnect passed");
}
int main(void) {
 cafa_link_t c={0}, before; uint8_t f[101]; unsigned i,j,r=1234567;
 frame(f,CAFA_HELLO,7,0); assert(cafa_receive(&c,f,9,0)==1);
 before=c; frame(f,CAFA_STATE,8,0); assert(!cafa_receive(&c,f,101,1)); assert(!memcmp(&c,&before,sizeof c));
 frame(f,CAFA_STATE,7,16383); assert(cafa_receive(&c,f,101,2)==2); assert(cafa_u14(c.view+3)==1024);
 before=c;
 for(i=0;i<101;i++) { assert(!cafa_receive(&c,f,i,3)); assert(!memcmp(&c,&before,sizeof c)); }
 frame(f,CAFA_STATE,7,0); assert(cafa_receive(&c,f,101,4)==3);
 before=c; assert(!cafa_receive(&c,f,101,5)); assert(!memcmp(&c,&before,sizeof c));
 frame(f,CAFA_STATE,7,8192); assert(!cafa_receive(&c,f,101,6)); assert(!memcmp(&c,&before,sizeof c));
 frame(f,CAFA_STATE,7,1);
 for(i=0;i<101;i++) { f[i]|=128; assert(!cafa_receive(&c,f,101,7)); assert(!memcmp(&c,&before,sizeof c)); f[i]&=127; }
 /* Well-formed envelopes with invalid state fields cannot mutate or renew. */
 { const unsigned offsets[]={0,1,2,13,19,20}; const uint8_t values[]={6,6,8,4,64,31};
   for(i=0;i<6;i++) { frame(f,CAFA_STATE,7,1); f[9+offsets[i]]=values[i]; assert(!cafa_receive(&c,f,101,8)); assert(!memcmp(&c,&before,sizeof c)); }
 }
 { const unsigned offsets[]={3,3,5,5,7,9,9}; const unsigned values[]={0,1025,1009,1024,1024,19,301};
   for(i=0;i<7;i++) { frame(f,CAFA_STATE,7,1); cafa_put14(f+9+offsets[i],values[i]); assert(!cafa_receive(&c,f,101,8)); assert(!memcmp(&c,&before,sizeof c)); }
 }
 frame(f,CAFA_STATE,0,1); assert(!cafa_receive(&c,f,101,8)); assert(!memcmp(&c,&before,sizeof c));
 for(i=0;i<100000;i++) {
   for(j=0;j<101;j++) { r=r*1664525u+1013904223u; f[j]=r>>24; }
   before=c; assert(!cafa_receive(&c,f,i%102,8)); assert(!memcmp(&c,&before,sizeof c));
 }
 frame(f,CAFA_HEARTBEAT,7,0); assert(cafa_receive(&c,f,9,0xffffff00u)==5);
 assert(!cafa_expired(&c,0xffffff00u+1499u)); assert(cafa_expired(&c,0xffffff00u+1500u));
 frame(f,CAFA_BYE,7,0); assert(cafa_receive(&c,f,9,20)==4);
 versions();
 for(i=3;i<=4;i++) {
  memset(&c,0,sizeof c);frame(f,CAFA_HELLO,97,0);f[3]=i;assert(cafa_receive(&c,f,9,0)==1);
  frame(f,CAFA_STATE,97,1);f[3]=i;f[9]=7;f[10]=i==4?8:5;cafa_put14(f+18,12);
  assert(cafa_receive(&c,f,101,1)==2);before=c;
  f[9]=8;cafa_put14(f+7,2);assert(!cafa_receive(&c,f,101,2));assert(!memcmp(&c,&before,sizeof c));
  f[9]=7;cafa_put14(f+18,11);assert(!cafa_receive(&c,f,101,2));assert(!memcmp(&c,&before,sizeof c));
  cafa_put14(f+18,300);f[10]=i==4?9:6;assert(!cafa_receive(&c,f,101,2));
  if(i==4){f[10]=3;cafa_put14(f+93,101);assert(!cafa_receive(&c,f,101,2));cafa_put14(f+93,100);assert(cafa_receive(&c,f,101,2)==3);}
 }
 puts("Loom v3/v4: eight tracks, 12 BPM, page/version/flags bounds, percentage ENV and rejected-frame isolation passed");
 puts("CAFA protocol: passed malformed, session, timeline, revision wrap, lease wrap and 100000 rejection fuzz cases");
 return 0;
}
