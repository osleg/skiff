/* SPDX-License-Identifier: GPL-3.0-only */
/* Host-only tests: descriptor and production packet/resampling paths, no MMIO. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define RING_PUBLISH() __asm__ volatile("" ::: "memory")
#define HALF_FRAMES 128
#define FELUCCA_CAFA 1
#define FELUCCA_UAC 1
#define FELUCCA_CDC T_CDC
#define FELUCCA_USB_LAYOUT T_LAYOUT
#define FELUCCA_CDC_DEFAULT T_ON
static void fm1_delay_ms(uint32_t ms) { (void)ms; }
#pragma GCC diagnostic ignored "-Wint-to-pointer-cast"
#include "../firmware/src/usb.c"

static uint32_t le16(const uint8_t *p) { return p[0] | (uint32_t)p[1] << 8; }
static uint32_t rate(const uint8_t *p) { return le16(p) | (uint32_t)p[2] << 16; }
static void descriptors(void)
{
    const uint8_t *c; uint16_t n;
    unsigned off, ni = 0, endpoints = 0, epseen[256] = {0};
    unsigned playback = 0, recording = 0, ac_start = 0, ac_total = 0, iad = 0;
    int iface = -1, expected_eps = 0, got_eps = 0;
    assert(get_desc(0x0200, &c, &n));
    assert(le16(c + 2) == n && c[4] == 4 + 2 * (T_CDC && T_ON));
    for (off = 0; off < n; off += c[off]) {
        const uint8_t *d = c + off;
        assert(d[0] >= 2 && off + d[0] <= n);
        if (d[1] == 0x0b) {
            assert(T_CDC && T_ON && d[0] == 8 && d[2] == ni);
            if (d[4] == 1) assert(d[2] == AUD_IF && d[3] == 4 && d[5] == 1);
            else assert(d[4] == 2 && d[3] == 2 && d[5] == 2);
            iad++;
        } else if (d[1] == 4) {
            assert(got_eps == expected_eps);
            got_eps = 0; expected_eps = d[4]; iface = d[2];
            if (!d[3]) assert(d[2] == ni++);
            if (ac_start) { assert(off - ac_start == ac_total); ac_start = 0; }
        } else if (d[1] == 5) {
            assert(!epseen[d[2]]++); endpoints++; got_eps++;
            if (d[2] == 0x04) {
                assert(iface == UAC_ASO_IF && d[3] == 0x09 && le16(d+4) == UO_MAXP && d[6] == 1);
            } else if (d[2] == 0x84) {
                assert(iface == UAC_AS_IF && d[3] == 0x05 && le16(d+4) == 196 && d[6] == 1);
            }
        } else if (d[1] == 0x24) {
            if (iface == AUD_IF && d[2] == 1) {
                assert(d[7] == 3 && d[0] == 11);
                assert(d[8] == UAC_AS_IF && d[9] == UAC_ASO_IF && d[10] == AUD_IF + IF_MIDI);
                ac_start = off; ac_total = le16(d+5);
            }
            if (d[2] == 2 && iface == UAC_ASO_IF) {
                assert(d[0] == 11 && d[3] == 1 && d[4] == 2 && d[5] == 2 && d[6] == 16);
                assert(d[7] == 1 && rate(d+8) == 44100); playback++;
            }
            if (d[2] == 2 && iface == UAC_AS_IF) {
                assert(d[0] == 14 && d[4] == 2 && d[5] == 2 && d[6] == 16);
                assert(d[7] == 2 && rate(d+8) == 44100 && rate(d+11) == 48000); recording++;
            }
        }
    }
    assert(off == n && ni == c[4] && got_eps == expected_eps && iad == 2 * (T_CDC && T_ON));
    assert(playback == 1 && recording == 1 && endpoints == 4 + 3 * (T_CDC && T_ON));
    assert(epseen[0x04] && epseen[0x84] && epseen[0x01] && epseen[0x81]);
}
static void fresh(void)
{
    memset(&uo, 0, sizeof uo); memset(uo_ring, 0, sizeof uo_ring);
    uo_w = uo_r = 0; usb.config = 1; uo_stream(1);
}
static void feed(uint32_t n, int16_t l, int16_t r)
{
    uint32_t p[UO_MAXF];
    for (unsigned i=0; i<UO_MAXF; i++) p[i] = (uint16_t)l | (uint32_t)(uint16_t)r << 16;
    while (n) { uint32_t k = n > UO_MAXF ? UO_MAXF : n; uo_packet(p, k*4); n -= k; }
}
static void silence(const int32_t *out)
{
    for (unsigned i=0; i<2*HALF_FRAMES; i++) assert(!out[i]);
}
static void lifecycle(void)
{
    int32_t out[2*HALF_FRAMES] = {0}; uint32_t bad[50] = {0};
    fresh(); feed(UO_TGT-1, 32767, -32768);
    uac_play(out, HALF_FRAMES, 4096); silence(out); assert(!uo.go);
    feed(1, 32767, -32768); uac_play(out, HALF_FRAMES, 2048);
    for (unsigned i=0; i<HALF_FRAMES; i++) { assert(out[2*i] == 32767*128); assert(out[2*i+1] == -32768*128); }
    for (unsigned i=0; i<3 && uo.go; i++) {
        memset(out, 0, sizeof out);
        uac_play(out, HALF_FRAMES, 4096);
    }
    silence(out); assert(uo.underruns == 1 && !uo.go && uo_r == uo_w);
    uac_play(out, HALF_FRAMES, 4096); assert(uo.underruns == 1); /* waiting is not another underrun */
    feed(UO_TGT, 1234, -2345); uac_play(out, HALF_FRAMES, 0); silence(out); assert(uo.go); /* mute still drains */
    feed(UO_TGT, 30000, 30000); uo_stream(0); uac_play(out, HALF_FRAMES, 4096); silence(out);
    uint32_t w = uo_w; feed(44, 7, 7); assert(uo_w == w); /* inactive packets dropped */
    uo_stream(1); uac_play(out, HALF_FRAMES, 4096); silence(out); assert(!uo.go); /* no stale restart */
    feed(UO_TGT, 222, -333); uac_play(out, HALF_FRAMES, 4096);
    for (unsigned i=0; i<HALF_FRAMES; i++) { assert(out[2*i] == 222*256); assert(out[2*i+1] == -333*256); }
    /* Reset and re-enable before the consumer runs: producer must use new reset floor. */
    uo_stream(0); uo_stream(1); feed(UO_TGT, 111, 111);
    memset(out, 0, sizeof out); uac_play(out, HALF_FRAMES, 4096); assert(out[0] == 111*256);
    /* Mode switch flush keeps alt 1, but old audio cannot be replayed. */
    feed(UO_TGT, 20000, 20000); uac_play_flush();
    assert(uo.alt == 1 && uo_r == uo_w && !uo.go && !uo.pos && !uo.err_q16);
    memset(out, 0, sizeof out); uac_play(out, HALF_FRAMES, 4096); silence(out);
    feed(UO_TGT, 77, -88); uac_play(out, HALF_FRAMES, 4096);
    assert(out[0] == 77*256 && out[1] == -88*256);
    usb.config = 0; memset(out, 0, sizeof out); uac_play(out, HALF_FRAMES, 4096); silence(out);
    w = uo_w; feed(44, 7, 7); assert(uo_w == w);
    fresh(); uo_packet(bad, 3); uo_packet(bad, sizeof bad); assert(uo_w == 0);
    feed(UO_N, 11, 12); assert(uo_w == UO_N); feed(44, 11, 12); assert(uo.overruns == 1 && uo_w == UO_N);
    uac_play(out, HALF_FRAMES, 4096); assert(uo_w - uo_r <= UO_TGT); /* starts with newest target frames */
    fresh(); uo_w = uo_r = UINT32_MAX - 100; uo_stream(1); feed(UO_TGT, 5, -5);
    memset(out, 0, sizeof out); uac_play(out, HALF_FRAMES, 4096); assert(out[0] == 5*256 && uo_w - uo_r < UO_TGT);
    /* Interpolation halfway between opposite full-scale endpoints, add-to-output contract. */
    fresh(); feed(UO_TGT, 32767, -32768); uac_play(out, 0, 4096);
    uo.go = 1; uo.pos = 32768; uo_ring[1] = 0x7fff8000u;
    out[0] = 123; out[1] = 456; uac_play(out, 1, 4096);
    assert(out[0] == 123-128 && out[1] == 456-128);
}
static void drift(double ppm)
{
    int32_t out[2*HALF_FRAMES]; unsigned acc=0, hi=0, renders=0;
    double render_ms = 0, period = 1000.0 * HALF_FRAMES / (44117.64705882353 * (1 + ppm/1000000));
    fresh();
    for (unsigned ms=0; ms<60000; ms++) {
        acc += 441; feed(acc/10, 1000, -1000); acc %= 10;
        if (uo_w-uo_r > hi) hi = uo_w-uo_r;
        while (render_ms <= ms) {
            memset(out, 0, sizeof out); uac_play(out, HALF_FRAMES, 2048);
            assert(uo_w-uo_r <= UO_N);
            render_ms += period; renders++;
        }
    }
    assert(uo.go && !uo.underruns && !uo.overruns && renders > 20000 && hi < UO_N);
    printf("60 s drift %+.0f ppm: no under/overruns, max fill %u/%u\n", ppm, hi, UO_N);
}
int main(void)
{
    descriptors(); lifecycle(); drift(0); drift(-200); drift(200);
    puts("CAFA USB playback tests passed"); return 0;
}
