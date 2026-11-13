/* SPDX-License-Identifier: GPL-3.0-only
 * CAFA experimental companion protocol. No hardware dependencies. */
#ifndef CAFA_PROTOCOL_H
#define CAFA_PROTOCOL_H
#include <stdint.h>
#define CAFA_STATE_BYTES 92u
#define CAFA_LEASE_MS 1500u
enum { CAFA_HELLO=1, CAFA_ACK, CAFA_STATE, CAFA_HEARTBEAT, CAFA_BYE, CAFA_INPUT };
typedef struct {
    uint32_t last_ms;
    uint16_t session, revision;
    uint8_t version; /* negotiated wire version: 1 proxy, 2 native ENV, 3 Loom proxy, 4 Loom native */
    uint8_t phase; /* 0 standalone, 1 negotiated, 2 authoritative state received */
    uint8_t view[CAFA_STATE_BYTES];
} cafa_link_t;
static uint32_t cafa_u14(const uint8_t *p) { return p[0] | (uint32_t)p[1] << 7; }
static void cafa_put14(uint8_t *p, uint32_t v) { p[0]=v&127u; p[1]=(v>>7)&127u; }
static int cafa_newer(uint32_t a, uint32_t b) { uint32_t d=(a-b)&16383u; return d && d<8192u; }
static int cafa_expired(const cafa_link_t *c, uint32_t now) { return c->phase && now-c->last_ms>=CAFA_LEASE_MS; }
/* Frames exclude F0/F7. Returns 1 ACK, 2 initial state, 3 updated state, 4 exit, 5 heartbeat ACK, 0 ignored.
 * Invalid/stale traffic cannot renew a lease or partially change a display. */
static int cafa_receive(cafa_link_t *c, const uint8_t *f, uint32_t n, uint32_t now)
{
    uint32_t i, sid, rev, len, page, head;
    const uint8_t *p=f+9;
    if (n<9u || f[0]!=0x7d || f[1]!=0x43 || f[2]!=0x41 || (f[3]<1 || f[3]>4)) return 0;
    for (i=0;i<n;i++) if (f[i]&128u) return 0;
    sid=cafa_u14(f+5); rev=cafa_u14(f+7);
    if (!sid) return 0;
    if (f[4]==CAFA_HELLO && n==9u) {
        if (c->phase && (c->session!=sid || c->version!=f[3])) return 0;
        if (!c->phase) { c->session=(uint16_t)sid; c->version=f[3]; c->revision=0; c->phase=1; }
        c->last_ms=now;
        return 1;
    }
    if (!c->phase || c->session!=sid || c->version!=f[3]) return 0;
    if (f[4]==CAFA_BYE && n==9u) return 4;
    if (f[4]==CAFA_HEARTBEAT && n==9u) { c->last_ms=now; return 5; }
    if (f[4]!=CAFA_STATE || n!=9u+CAFA_STATE_BYTES ||
        (c->phase==2 && !cafa_newer(rev,c->revision))) return 0;
    len=cafa_u14(p+3); page=cafa_u14(p+5); head=cafa_u14(p+7);
    if (p[0]>=(c->version>=3 ? 8 : 6) || p[1]>(c->version==4 ? 8 : 5) || ((c->version==1 || c->version==3) ? p[2]>7 : p[2]>55) || !len || len>1024 || page>=len || (page&15u) ||
        (head!=16383u && head>=len) || cafa_u14(p+9)<(c->version>=3 ? 12u : 20u) || cafa_u14(p+9)>300 ||
        p[13]>3 || p[19]>63) return 0;
    if (c->version==2 && p[1]==3)
        for (i=0;i<4;i++) if (cafa_u14(p+84+2*i)>127) return 0;
    if (c->version==4 && p[1]==3)
        for (i=0;i<4;i++) if (cafa_u14(p+84+2*i)>100) return 0;
    for (i=20;i<84;i++) if (p[i] && (p[i]<32 || p[i]>126)) return 0;
    i=c->phase==1 ? 2u : 3u;
    { uint32_t k; for (k=0;k<CAFA_STATE_BYTES;k++) c->view[k]=p[k]; }
    c->revision=(uint16_t)rev; c->last_ms=now; c->phase=2;
    return (int)i;
}
#endif
