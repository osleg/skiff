/* SPDX-License-Identifier: GPL-3.0-only
 * Skiff/CAFA prototype: phone-authoritative surface, no writes to the standalone project. */
static uint32_t cafa_buttons, cafa_keys, cafa_tx_serial;
static uint8_t cafa_dirty, cafa_first, cafa_release_guard;
static uint8_t cafa_native_shown __attribute__((section(".bss.cafa_ui")));
static uint8_t cafa_drawn[CAFA_STATE_BYTES] __attribute__((section(".bss.cafa_ui"))); /* last pixels shown, not merely last state received */
/* Entire frames queued atomically. Never block input/the lease watchdog on a MIDI host. */
static int cafa_send(uint32_t cmd, uint32_t serial, const uint8_t *p, uint32_t n)
{
    uint8_t f[31];
    uint32_t i=0, size=n+11u;
    if (!usb.config || size>sizeof f || SXQ-(so_w-so_r)<(size+2u)/3u) return 0;
    f[0]=0xf0; f[1]=0x7d; f[2]=0x43; f[3]=0x41; f[4]=cafa.version; f[5]=(uint8_t)cmd;
    cafa_put14(f+6,cafa.session); cafa_put14(f+8,serial);
    if (n) memcpy(f+10,p,n);
    f[size-1]=0xf7;
    sx_busy=1;
    while (i<size) {
        uint32_t k=size-i>=3u ? 3u : size-i;
        uint32_t cin=k==3u && i+3u<size ? 4u : k==3u ? 7u : 4u+k;
        sx_out_q[so_w%SXQ]=cin | (uint32_t)f[i]<<8 | (k>1 ? (uint32_t)f[i+1]<<16 : 0) |
                          (k>2 ? (uint32_t)f[i+2]<<24 : 0);
        RING_PUBLISH(); so_w++; i+=k;
    }
    sx_busy=0;
    return 1;
}
static void cafa_silence_standalone(void)
{
    uint32_t k;
    /* The audio ISR owns voices. Hold it while changing mode, and empty pending input. */
    fm1_irq_off();
    seq_stop(); transport_req=panic_req=0;
    /* Mark EVERY outgoing note owed before a bounded flush can run out of queue room. */
    memcpy(mn.owe,mn.on,sizeof mn.on); mn.lost=1;
    mo_flush((1u<<NTRK)-1u);
    for (k=0;k<NTRK;k++) {
        memset(trk[k].v,0,sizeof trk[k].v);
        trk[k].nheld=0; trk[k].arp_phys=0; arp_forget(&trk[k]); midi_forget_track(k);
    }
    memset(kb_ped,0,sizeof kb_ped); kb_ped_trk=0;
    memset(kb_note,KB_SILENT,sizeof kb_note); kb_prev=fm1_in.notes;
    mi_r=mi_w; midi_in_overflow=0;
    kb_boot_hold=1;
#if FELUCCA_UAC_OUT
    uac_play_flush();
#endif
    fm1_irq_on();
    /* Audio is bypassed during this reset, so large effect buffers can clear with IRQs on. */
    memset(dly_buf,0,sizeof dly_buf); memset(cho_buf,0,sizeof cho_buf); rev_clear();
    /* Scalar stores let the target compiler keep hot mixer globals split. */
    fx.dly_w=fx.cho_w=fx.cho_ph=0; fx.dly_lp=0;
    for (k=0;k<4;k++) { fx.comb_i[k]=0; fx.comb_lp[k]=0; fx.ph_s[k]=0; }
    for (k=0;k<2;k++) fx.ap_i[k]=0;
    fx.rtype=0; fx.sp_w=0; fx.sp_lp=fx.sp_hp=fx.sp_he=fx.sp_size=0; fx.sp_ph=0;
    fx.cho_y=0; fx.dmode=fx.dclr=0; fx.dly_lpr=fx.dpl=fx.dpr=fx.dmp=fx.dsp=0; memset(&pf,0,sizeof pf); memset(ins,0,sizeof ins);
    memset(&clk,0,sizeof clk); click_req=0;
    perf_held=perf_latched=perf_act=0;
}
static void cafa_leave(void)
{
    /* Negotiation alone never owns or interrupts the standalone instrument. */
    if (cafa.phase!=2 && !cafa_audio_on) { cafa.phase=0; return; }
    cafa_audio_on=1;
    cafa_silence_standalone();
    cafa.phase=0; cafa_release_guard=1; cafa_audio_on=0;
    ui.force=1; ui.home_t0=ui.save_t0=0;
    ui_message("CAFA DISCONNECTED");
}
static int cafa_frame(const uint8_t *p, uint32_t n)
{
    int action, changed;
    uint8_t ack=1;
    if (n<3 || p[0]!=0x7d || p[1]!=0x43 || p[2]!=0x41) return 0;
    if (cafa_expired(&cafa,fm1_ms)) cafa_leave();
    /* Held/step LED bytes 14..19 are not LCD content. A key press must not blank
     * the display just because its physical LED changes. */
    changed=n==9u+CAFA_STATE_BYTES && (memcmp(cafa.view,p+9,14) ||
            memcmp(cafa.view+20,p+29,CAFA_STATE_BYTES-20u));
    action=cafa_receive(&cafa,p,n,fm1_ms);
    if (action==1 || action==5) {
        if (!cafa_send(CAFA_ACK,action==1 ? 0u : cafa.revision,&ack,1)) cafa_leave();
    } else if (action==2) {
        cafa_audio_on=1;
        cafa_silence_standalone();
        cafa_audio_on=1; cafa_first=1; cafa_tx_serial=0; cafa_buttons=cafa_keys=0;
        cafa_dirty=2; cafa_native_shown=0; scr_wake_now(); /* takeover invalidates the whole display cache */
    } else if (action==3 && changed && !cafa_dirty) cafa_dirty=1;
    else if (action==4) cafa_leave();
    /* The USB receive mailbox holds one frame. A heartbeat arriving directly
     * after STATE can be dropped before the main loop consumes STATE. Confirm
     * every applied snapshot itself, so valid state traffic cannot starve ACKs. */
    if ((action==2 || action==3) && !cafa_send(CAFA_ACK,cafa.revision,&ack,1)) cafa_leave();
    return 1;
}
/* Full held snapshots plus relative deltas, emitted atomically. */
static int cafa_emit(uint8_t *p,uint32_t b,uint32_t keys)
{
    uint32_t k;
    cafa_put14(p,b);
    for (k=0;k<4;k++) p[2+k]=(keys>>(7*k))&127u;
    if (!cafa_send(CAFA_INPUT,(cafa_tx_serial+1u)&16383u,p,20)) { cafa_leave(); return 0; }
    cafa_tx_serial=(cafa_tx_serial+1u)&16383u; cafa_first=0; cafa_buttons=b; cafa_keys=keys;
    return 1;
}
/* Return true while the standalone input must not consume physical intents. */
static int cafa_input(void)
{
    uint32_t k,b=0,keys=fm1_in.notes,changed=0,pressed,np,bp=0,rb,rk;
    uint8_t p[20], release[20]={0};
    if (cafa.phase && (!usb.config || cafa_expired(&cafa,fm1_ms))) cafa_leave();
    if (cafa.phase!=2 && !cafa_release_guard) return 0;
    pressed=fm1_input_edges(0); np=fm1_input_note_edges();
    for (k=0;k<NB;k++) {
        b|=((fm1_in.buttons>>panel.btn[k])&1u)<<k;
        bp|=((pressed>>panel.btn[k])&1u)<<k;
    }
    for (k=0;k<NE;k++) {
        int32_t d=panel_enc(k);
        if (d>8191 || d< -8192) { cafa_leave(); return 1; }
        changed|=d!=0;
        cafa_put14(p+6+2*k,(uint32_t)(d+8192));
        cafa_put14(release+6+2*k,8192);
    }
    if (cafa_release_guard) {
        kb_prev=keys;
        if (!keys && !b) { cafa_release_guard=0; kb_boot_hold=0; }
        return 1;
    }
    /* Press latches preserve taps completed during LCD transfers. A re-press of a
     * previously held control emits its intervening release before the new press. */
    rb=cafa_buttons&bp; rk=cafa_keys&np;
    if ((rb || rk) && !cafa_emit(release,cafa_buttons&~rb,cafa_keys&~rk)) return 1;
    b|=bp; keys|=np;
    if (!changed && b==cafa_buttons && keys==cafa_keys && !cafa_first) return 1;
    cafa_emit(p,b,keys);
    return 1;
}
static void cafa_text(uint32_t x,uint32_t y,uint32_t w,const uint8_t *p,uint32_t n)
{
    char s[17]; memcpy(s,p,n); s[n]=0;
    draw_text_line(x,y,w,&AF_S,s,T_TEXT,T_BG,0);
}
static __attribute__((noinline)) int cafa_draw(void)
{
    const uint8_t *p=cafa.view, *old=cafa_drawn;
    uint32_t k, first=cafa_u14(p+5), head=cafa_u14(p+7), mask=cafa_u14(p+11)|((uint32_t)p[13]<<14);
    uint32_t old_first=cafa_u14(old+5), old_head=cafa_u14(old+7), old_mask=cafa_u14(old+11)|((uint32_t)old[13]<<14);
    int full=cafa_dirty==2;
    char b[24];
    static const char *const pages[]={"HOME","SEQ","SOUND","ENV","FX","MIX"};
    if (cafa.phase!=2) return 0;
    if (cafa.version==4 || (cafa.version==2 && p[1]==3)) {
        cafa_native_draw(p,old,full || !cafa_native_shown || p[0]!=old[0] || p[1]!=old[1],cafa_dirty!=0);
        cafa_native_shown=1; cafa_dirty=0;
        memcpy(cafa_drawn,p,CAFA_STATE_BYTES);
        return 1;
    }
    if (cafa_native_shown) { full=1; cafa_dirty=2; cafa_native_shown=0; }
    if (!cafa_dirty) return 1;
    cafa_dirty=0;
    /* One full clear on takeover only. Dynamic fields are composed in the
     * existing canvas before their small region is transferred to the LCD. */
    if(full)lcd_fill(0,0,240,240,T_BG);
    if(full || p[0]!=old[0]) {
        b[0]='T'; b[1]='1'+p[0]; b[2]=0;
        draw_text_box(8,7,32,&AF_M,b,T_THEME,0);
    }
    if(full || p[1]!=old[1])draw_text_box(47,7,100,&AF_M,pages[p[1]],T_TEXT,0);
    if(full || memcmp(p+9,old+9,2)) {
        fmt_int(b,(int32_t)cafa_u14(p+9)); draw_text_box(175,7,60,&AF_M,b,T_THEME,2);
    }
    if(full || memcmp(p+20,old+20,16))cafa_text(8,39,224,p+20,16);
    if(full || memcmp(p+36,old+36,16))cafa_text(8,61,224,p+36,16);
    for (k=0;k<16;k++) {
        uint32_t x=8+(k%8)*28, y=92+(k/8)*25;
        uint16_t col=(mask>>k)&1u ? T_THEME : T_SURF;
        if(full || ((mask^old_mask)>>k&1u) || (head==first+k)!=(old_head==old_first+k)) {
            cv_begin(24,20,col);
            if(head==first+k)cv_rect(0,17,24,3,T_TEXT);
            cv_blit(x,y);
        }
    }
    if(full || memcmp(p+3,old+3,4)) {
        fmt_int(b,(int32_t)first+1); str_cpy(b+str_len(b)," / ",sizeof b-str_len(b));
        fmt_int(b+str_len(b),(int32_t)cafa_u14(p+3));
        draw_text_box(8,144,145,&AF_S,b,T_DIM,0);
    }
    if(full || ((p[2]^old[2])&1u))
        draw_text_box(155,144,80,&AF_S,(p[2]&1u)?"PLAY":"STOP",T_THEME,2);
    for (k=0;k<4;k++) {
        if(full || memcmp(p+52+8*k,old+52+8*k,8))cafa_text(4+k*59,173,56,p+52+8*k,8);
        if(full || memcmp(p+84+2*k,old+84+2*k,2)) {
            fmt_int(b,(int32_t)cafa_u14(p+84+2*k));
            draw_text_box(4+k*59,193,56,&AF_M,b,T_TEXT,0);
        }
    }
    if(full)draw_text_box(8,222,224,&AF_S,"CAFA  /  PHONE ENGINE",T_DIM,0);
    memcpy(cafa_drawn,p,CAFA_STATE_BYTES);
    return 1;
}
static int cafa_leds(void)
{
    uint32_t k, c, buttons=cafa_u14(cafa.view+14), keys=0;
    uint8_t nl[FM1_NCOL]={0};
    if (cafa.phase!=2) return 0;
    led_pos_init();
    for (k=0;k<4;k++) keys|=(uint32_t)cafa.view[16+k]<<(7*k);
    for (k=0;k<NB;k++) led_put(nl,panel.btn[k],(buttons>>k)&1u);
    for (k=0;k<27;k++) led_put(nl,LED_KEY0+k,(keys>>k)&1u);
    if (cafa.view[2]&1u) nl[LED_PLAY_GREEN>>3]|=1u<<(LED_PLAY_GREEN&7u);
    for (c=0;c<FM1_NCOL;c++) { fm1_led[c]=nl[c]; fm1_led_dim[c]=fm1_led_breath[c]=fm1_led_mid[c]=0; }
    return 1;
}
