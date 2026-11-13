/* SPDX-License-Identifier: GPL-3.0-only */
package dev.cafa.core;

/** Translates full FM1 button/key state and encoder deltas into the authoritative phone model. */
public final class Control {
    public static final int HOME = 0, SEQ = 1, SOUND = 2, ENV = 3, FX = 4, MIX = 5;
    public static final int[] WHITE_KEYS = {0, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 21, 23, 24, 26};
    private final Synth synth;
    private final Project p;
    private int buttons, lastSerial;
    private long keys;
    private boolean first = true;
    private final int[] heldTracks = new int[27], heldNotes = new int[27];
    public interface Actions { void save(); void notice(String text); }
    private final Actions actions;
    public interface AudioQueue { long frames(); }
    private AudioQueue audioQueue = () -> 0;
    public void setAudioQueue(AudioQueue queue) { audioQueue = queue; }
    public Control(Synth synth, Actions actions) {
        this.synth = synth; p = synth.project; this.actions = actions;
        reset();
    }
    public void reset() {
        synchronized (synth) {
            first = true; lastSerial = 0; buttons = 0; keys = 0;
            java.util.Arrays.fill(heldNotes, -1);
        }
    }
    public boolean input(int serial, byte[] payload) {
        if (payload.length != 20) return false;
        synchronized (synth) {
            if (!first && serial == lastSerial) return true;
            if (serial != ((lastSerial + 1) & 16383)) return false;
            int nextButtons = Protocol.u14(payload, 0);
            long nextKeys = Protocol.u28(payload, 2);
            if ((nextKeys & ~0x7ffffffL) != 0) return false;
            int pressed = nextButtons & ~buttons;
            long pressedKeys = nextKeys & ~keys, releasedKeys = keys & ~nextKeys;
            buttons = nextButtons; keys = nextKeys; lastSerial = serial;
            // Ignore already-held buttons/keys on takeover; don't replay accidental presses.
            if (first) { first = false; pressed = 0; pressedKeys = 0; }
            for (int k = 0; k < 27; k++) if ((releasedKeys & (1L << k)) != 0 && heldNotes[k] >= 0) {
                synth.noteOff(heldTracks[k], heldNotes[k]); heldNotes[k] = -1;
            }
            if ((pressed & (1 << 0)) != 0) p.page = FX;
            if ((pressed & (1 << 1)) != 0) p.page = SOUND;
            if ((pressed & (1 << 2)) != 0) p.page = ENV;
            if ((pressed & (1 << 3)) != 0) actions.notice("LFO page is not implemented in this first slice");
            if ((pressed & (1 << 4)) != 0) p.page = SOUND;
            if ((pressed & (1 << 5)) != 0) p.page = MIX;
            if ((pressed & (1 << 6)) != 0) p.page = HOME;
            if ((pressed & (1 << 7)) != 0) actions.save();
            if ((pressed & (1 << 8)) != 0) actions.notice("ARP is not implemented in this first slice");
            if ((pressed & (1 << 9)) != 0) p.page = SEQ;
            if ((pressed & (1 << 10)) != 0) { if (synth.playing()) synth.stop(); else synth.start(); }
            if ((pressed & (1 << 11)) != 0) p.recording = !p.recording;
            if ((pressed & (1 << 12)) != 0) p.octave = Project.clamp(p.octave - 1, -3, 3);
            if ((pressed & (1 << 13)) != 0) p.octave = Project.clamp(p.octave + 1, -3, 3);
            for (int e = 0; e < 7; e++) {
                int delta = Protocol.u14(payload, 6 + e * 2) - 8192;
                if (e == 0) { if (p.page == SEQ) p.turnPage(delta); else if (p.page != ENV) p.page = Math.floorMod(p.page + delta, 6); }
                else if (e == 1) p.select(p.selectedTrack + delta);
                else if (e == 2) p.selected().engine = Math.floorMod(p.selected().engine + delta, Project.ENGINES.length);
                else knob(e - 3, delta);
            }
            for (int k = 0; k < 27; k++) if ((pressedKeys & (1L << k)) != 0) {
                int white = whiteIndex(k);
                if (p.page == SEQ && white >= 0) p.toggle(p.pageStart + white);
                else {
                    int note = Project.clamp(53 + k + p.octave * 12, 0, 127);
                    p.lastNote = note; heldTracks[k] = p.selectedTrack; heldNotes[k] = note;
                    synth.noteOn(p.selectedTrack, note); synth.record(p.selectedTrack, note, audioQueue.frames());
                }
            }
        }
        return true;
    }
    public static int whiteIndex(int key) {
        for (int i = 0; i < WHITE_KEYS.length; i++) if (WHITE_KEYS[i] == key) return i;
        return -1;
    }
    public void knob(int knob, int delta) {
        synchronized (synth) {
            Project.Track t = p.selected();
            if (p.page == FX) return;
            if (p.page == SEQ) {
                if (knob == 0) p.lastNote = Project.clamp(p.lastNote + delta, 0, 127);
                else if (knob == 1) p.length(t.length + delta * 16);
                else if (knob == 2) t.gate = Math.max(0.05, Project.unit(t.gate + delta / 100.0));
                else p.bpm = Project.clamp(p.bpm + delta, 20, 300);
            } else if (p.page == MIX) {
                if (knob == 0) t.volume = Project.unit(t.volume + delta / 100.0);
                else if (knob == 1) t.pan = Math.max(-1, Math.min(1, t.pan + delta / 50.0));
                else if (knob == 2 && delta != 0) t.mute = delta > 0;
                else if (knob == 3) p.bpm = Project.clamp(p.bpm + delta, 20, 300);
            } else if (p.page == ENV) {
                t.envelope[knob] = Project.clamp(t.envelope[knob] + delta, 0, 127);
            } else if (knob < 2) t.params[knob] = Project.unit(t.params[knob] + delta / 100.0);
            else {
                int e = knob == 2 ? 0 : 3;
                t.envelope[e] = Project.clamp(t.envelope[e] + delta, 0, 127);
            }
        }
    }
    public byte[] snapshot(long queuedFrames) {
        synchronized (synth) {
            Project.Track t = p.selected();
            byte[] b = new byte[92];
            b[0] = (byte) p.selectedTrack; b[1] = (byte) p.page;
            b[2] = (byte) ((synth.playing() ? 1 : 0) | (p.recording ? 2 : 0) | (t.mute ? 4 : 0) | ((p.octave + 3) << 3));
            Protocol.put14(b, 3, t.length); Protocol.put14(b, 5, p.pageStart);
            int playhead = synth.audiblePlayhead(p.selectedTrack, queuedFrames);
            Protocol.put14(b, 7, playhead < 0 ? Protocol.NONE : playhead); Protocol.put14(b, 9, p.bpm);
            int mask = 0; long ledKeys = keys;
            for (int i = 0; i < 16 && p.pageStart + i < t.length; i++) {
                if (t.notes[p.pageStart + i] >= 0) mask |= 1 << i;
                if (p.page == SEQ && (t.notes[p.pageStart + i] >= 0 || playhead == p.pageStart + i)) ledKeys |= 1L << WHITE_KEYS[i];
            }
            b[11] = (byte) (mask & 127); b[12] = (byte) ((mask >>> 7) & 127); b[13] = (byte) ((mask >>> 14) & 3);
            int pageLed = p.page == SEQ ? 9 : p.page == SOUND ? 4 : p.page == ENV ? 2 : p.page == FX ? 0 : p.page == MIX ? 5 : 6;
            int ledButtons = (1 << pageLed) | (synth.playing() ? 1 << 10 : 0) | (p.recording ? 1 << 11 : 0);
            Protocol.put14(b, 14, ledButtons); Protocol.put28(b, 16, ledKeys);
            Protocol.text(b, 20, 16, t.name); Protocol.text(b, 36, 16, t.soundName());
            String[] labels; int[] values = new int[4];
            if (p.page == SEQ) {
                labels = new String[]{"NOTE", "LENGTH", "GATE", "BPM"};
                values[0] = p.lastNote; values[1] = t.length; values[2] = (int) (t.gate * 100); values[3] = p.bpm;
            } else if (p.page == FX) {
                labels = new String[]{"NO FX", "", "", ""};
            } else if (p.page == MIX) {
                labels = new String[]{"VOLUME", "PAN", "MUTE", "BPM"};
                values[0] = (int) (t.volume * 100); values[1] = (int) ((t.pan + 1) * 50); values[2] = t.mute ? 1 : 0; values[3] = p.bpm;
            } else if (p.page == ENV) {
                labels = Envelope.LABELS;
                System.arraycopy(t.envelope, 0, values, 0, 4);
            } else {
                labels = Project.LABELS[t.engine];
                for (int i = 0; i < 2; i++) values[i] = (int) (t.params[i] * 100);
                values[2] = t.envelope[0]; values[3] = t.envelope[3];
            }
            for (int i = 0; i < 4; i++) { Protocol.text(b, 52 + i * 8, 8, labels[i]); Protocol.put14(b, 84 + i * 2, values[i]); }
            return b;
        }
    }
}
