/* SPDX-License-Identifier: GPL-3.0-only
 * First native CAFA page: the real Skiff ENV descriptor, cards and ADSR graph.
 * This is a read-only projection, NOT a second project or permission to let the
 * standalone input handler edit phone state. The phone still owns ADSR and DSP.
 * A bounded temporary track is on the main stack, not in audio RAM.
 * No standalone track/parameter/step/storage data is written. Audio remains in
 * cafa_audio_block, which does not consume tsel or the UI's borrowed fields. */
/* Keep new caches outside the compiler's merged-global groups: otherwise their
 * insertion moves unrelated hot arrays behind large offsets and regresses ISR
 * and STRUM code generation. This section is still ordinary zeroed BSS. */
static __typeof__(ui) cafa_native_cache __attribute__((section(".bss.cafa_ui")));
/* Loom wire values are already display units. Never pass them through Skiff's
 * parameter descriptors: percentages, offsets and gate/rate scales differ. */
static void cafa_loom_value(const uint8_t *p,uint32_t k,char *label,char *value,const char **unit)
{
    int32_t v=(int32_t)cafa_u14(p+84+2*k);
    uint32_t n=8;
    memcpy(label,p+52+8*k,8); label[8]=0;
    while(n && label[n-1]==' ')label[--n]=0;
    *unit="";
    if (str_eq(label,"OCT+3")) { str_cpy(label,"OCT",9);v-=3; }
    else if (str_eq(label,"INV+2")) { str_cpy(label,"INV",9);v-=2; }
    else if (str_eq(label,"SWING+23")) { str_cpy(label,"SWING",9);v-=23;*unit="%"; }
    else if (str_eq(label,"GATE x10")) {
        str_cpy(label,"GATE",9);fmt_int(value,v/10);n=str_len(value);
        value[n]='.';value[n+1]=(char)('0'+v%10);value[n+2]=0;*unit="st";return;
    } else if (str_eq(label,"RATE x2")) {
        str_cpy(label,"RATE",9);fmt_int(value,v/2);n=str_len(value);
        value[n]='.';value[n+1]=(char)('0'+(v%2)*5);value[n+2]=0;*unit="x";return;
    } else if (n && label[n-1]=='%') { label[--n]=0;while(n && label[n-1]==' ')label[--n]=0;*unit="%"; }
    else if (p[1]==2 || p[1]==3) *unit="%";
    if (str_eq(label,"MUTE") || str_eq(label,"LATCH") || str_eq(label,"SYNC") || str_eq(label,"ENABLED") || str_eq(label,"ENABLE")) {
        str_cpy(value,v ? "ON" : "OFF",12);return;
    }
    fmt_int(value,v);
}
static void cafa_loom_columns(const uint8_t *p)
{
    uint32_t k;
    memset(card_mot_mask,0,sizeof card_mot_mask);
    for(k=0;k<4;k++) {
        char label[9],value[12];const char *unit;
        cafa_loom_value(p,k,label,value,&unit);
        draw_column(k,label,value,unit,str_eq(label,"UNITY") ? VR_DIM : VR_LIVE,-1,ICON_NONE);
    }
}
/* A Loom ADSR preview: cubic time controls (2/3/3 seconds), exponential
 * decay/release, not Skiff's envelope-time mapping. Sustain dwell is illustrative,
 * because a note-off timestamp is absent from the snapshot. Integer-only graph. */
static void cafa_loom_graph(const uint8_t *p)
{
    uint32_t k,mask=cafa_u14(p+11)|((uint32_t)p[13]<<14),first=cafa_u14(p+5),head=cafa_u14(p+7);
    int32_t h=(int32_t)graph_h(),bottom=h-8;
    cv_begin(240,graph_h(),T_BG);
    if(p[1]==3) {
        uint32_t a=cafa_u14(p+84),d=cafa_u14(p+86),sus=cafa_u14(p+88),r=cafa_u14(p+90);
        /* Use 9 decay and 12 release time constants to reach near silence. */
        uint32_t at=2*a*a*a/1000+1,dt=27*d*d*d/1000+1,rt=36*r*r*r/1000+1;
        uint32_t total=at+dt+rt+1000,ax=12+216*at/total,dx=ax+216*dt/total,sx=dx+216*1000/total;
        int32_t top=6,sy=bottom-(bottom-top)*(int32_t)sus/100,px=(int32_t)ax,py=top;
        cv_line_t(12,bottom,ax,top,T_THEME,2);
        for(k=ax+1;k<=dx;k++) {
            uint32_t t=(k-ax)*1024/(dx-ax),q=1024+t*9/8,j;
            uint32_t e=32768;for(j=0;j<8;j++)e=e*1024/q;
            int32_t y=sy-(sy-top)*(int32_t)e/32768;
            cv_line_t(px,py,k,y,T_THEME,2);px=k;py=y;
        }
        cv_line_t(px,py,sx,sy,T_THEME,2);px=sx;py=sy;
        cv_text_r(232,2,&AF_S,"ENV PREVIEW",T_MID,T_BG);
        cv_line(sx,sy,sx,bottom,T_LINE);
        for(k=sx+1;k<=228;k++) {
            uint32_t t=(k-sx)*1024/(228-sx),q=1024+t*12/8,j;
            uint32_t e=32768;for(j=0;j<8;j++)e=e*1024/q;
            int32_t y=bottom-(bottom-sy)*(int32_t)e/32768;
            cv_line_t(px,py,k,y,T_THEME,2);px=k;py=y;
        }
    } else {
        /* The protocol contains occupancy, not note pitches. Two rows of eight
         * steps are truthful on every page; no invented piano-roll notes. */
        int32_t cell=(h-24)/2;if(cell<4)cell=4;
        for(k=0;k<16;k++) {
            int32_t x=8+(k%8)*28,y=3+(k/8)*(cell+3);
            cv_rrect(x,y,24,cell,3,(mask>>k)&1u ? T_THEME:T_SURF,T_BG);
            if(head==first+k)cv_rect(x,y+cell-2,24,2,T_TEXT);
        }
        { char b[24];fmt_int(b,(int32_t)first+1);str_cpy(b+str_len(b)," / ",sizeof b-str_len(b));
          fmt_int(b+str_len(b),(int32_t)cafa_u14(p+3));
          cv_text(8,h-15,&AF_S,b,T_MID);
          cv_text_r(232,h-15,&AF_S,(p[2]&4)?"MUTED":"STEPS",T_MID,T_BG); }
    }
    cv_blit(0,graph_y());
}
static __attribute__((noinline)) void cafa_native_draw(const uint8_t *p,const uint8_t *old,int full,int changed)
{
    if (!full && !changed && !cafa_native_cache.hot_t) return;
    static const uint8_t id[4]={P_ATK,P_DEC,P_SUS,P_REL};
    track_t view;
    track_t *saved_tsel=tsel;
    __typeof__(ui) saved_ui=ui;
    uint8_t selected=song.sel, playing=song.playing, rec=song.rec, free_on=fr.on;
    int8_t octave=song.octave;
    int16_t bpm=song.g[G_BPM];
    uint32_t k, head=cafa_u14(p+7);
    memset(&view,0,sizeof view);
    if (full) memset(&cafa_native_cache,0,sizeof cafa_native_cache);
    ui=cafa_native_cache;
    ui.force=(uint8_t)full; ui.home=0; ui.page=0; /* PAGES[0]: actual ENV, not a copied descriptor */
    ui.frame++;
    for (k=0;k<4;k++) {
        view.p[id[k]]=(int16_t)cafa_u14(p+84+2*k);
        if (!full && memcmp(p+84+2*k,old+84+2*k,2)) { ui.hot_col=(uint8_t)k; ui.hot_t=40; }
    }
    view.p[P_SLEN]=16; /* never index the phone's 1024-step timeline in a 64-step native track */
    view.seq_idx=(uint16_t)(head==16383u ? 0 : head); /* ENV header only; no step renderer is entered */
    tsel=&view; song.sel=p[0]; song.playing=(p[2]&1u) && head!=16383u;
    song.rec=(uint8_t)(((p[2]>>1)&1u)<<p[0]); song.octave=(int8_t)((p[2]>>3)-3);
    song.g[G_BPM]=(int16_t)cafa_u14(p+9); fr.on=0;
    cafa_native_view=p; cafa_native_v4=cafa.version==4;
    if (ui.hot_t) ui.hot_t--;
    if (full) draw_frame();
    draw_head();
    if (cafa_native_v4) { cafa_loom_columns(p); cafa_loom_graph(p); }
    else { draw_columns(); draw_graph(); }
    draw_foot();
    ui.force=0; cafa_native_cache=ui;
    cafa_native_view=0; cafa_native_v4=0; ui=saved_ui; tsel=saved_tsel;
    song.sel=selected; song.playing=playing; song.rec=rec; song.octave=octave;
    song.g[G_BPM]=bpm; fr.on=free_on;
}
