/* SPDX-License-Identifier: GPL-3.0-only */
import dev.cafa.core.*;
import java.io.*;
import java.util.*;

/** Dependency-free deterministic tests; NOT an Android/device integration test. */
public final class CoreTests {
    private static int checks;
    private static void check(boolean value, String what) { checks++; if (!value) throw new AssertionError(what); }
    private static byte[] input(int buttons, long keys, int... deltas) {
        byte[] p = new byte[20]; Protocol.put14(p, 0, buttons); Protocol.put28(p, 2, keys);
        for (int i = 0; i < 7; i++) Protocol.put14(p, 6 + i * 2, 8192 + (i < deltas.length ? deltas[i] : 0));
        return p;
    }
    private static Protocol.Frame decode(byte[] data) {
        List<Protocol.Frame> frames = new ArrayList<>(); new Protocol.Decoder(frames::add).feed(data, 0, data.length);
        check(frames.size() == 1, "one decoded frame"); return frames.get(0);
    }
    private static Control control(Synth synth) { return new Control(synth, new Control.Actions() { public void save() {} public void notice(String text) {} }); }
    private static void protocol() {
        byte[] payload = new byte[92]; Arrays.fill(payload, (byte) 127);
        byte[] bytes = Protocol.frame(Protocol.STATE, 16383, 16383, payload);
        Protocol.Frame f = decode(bytes);
        check(f.session == 16383 && f.serial == 16383 && Arrays.equals(f.payload, payload), "u14 framing round trip");
        List<Protocol.Frame> frames = new ArrayList<>(); Protocol.Decoder decoder = new Protocol.Decoder(frames::add);
        for (byte b : bytes) { decoder.feed(new byte[]{b}, 0, 1); decoder.feed(new byte[]{(byte) 0xf8}, 0, 1); }
        check(frames.size() == 1, "fragmented SysEx with realtime");
        decoder.feed(new byte[]{(byte) 0xf0, 0x7d, (byte) 0x90, 0, (byte) 0xf7}, 0, 5);
        check(frames.size() == 1, "status in SysEx invalidates frame");
        byte[] bad = new byte[400]; bad[0] = (byte) 0xf0; bad[399] = (byte) 0xf7; decoder.feed(bad, 0, bad.length);
        decoder.feed(bytes, 0, bytes.length); check(frames.size() == 2, "decoder recovers after overflow");
        check(Protocol.newer(0, 16383) && !Protocol.newer(16383, 0) && !Protocol.newer(8192, 0) && !Protocol.newer(0, 0), "revision wrapping");
        try { Protocol.frame(3, 0, 0, new byte[0]); throw new AssertionError(); } catch (IllegalArgumentException expected) { checks++; }
        try { Protocol.frame(3, 1, 0, new byte[]{(byte) 128}); throw new AssertionError(); } catch (IllegalArgumentException expected) { checks++; }
    }
    private static void project() throws Exception {
        Project p = new Project(); p.tracks[0].name = "Clouds 🌤 / long name"; p.length(1024); p.pageStart = 1008; p.toggle(1023);
        p.tracks[2].notes[890] = 73; p.tracks[2].length = 999; p.tracks[4].params[1] = 0.773;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); p.save(new DataOutputStream(bytes));
        Project copy = Project.load(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        check(copy.selected().length == 1024 && copy.selected().notes[1023] == 60 && copy.pageStart == 1008, "1024-step save/reopen");
        check(copy.tracks[2].notes[890] == 73 && copy.tracks[2].length == 999 && copy.tracks[4].params[1] == 0.773, "all track state persists");
        check(copy.tracks[0].name.equals(p.tracks[0].name) && !copy.recording, "full names persist; recording resets");
        copy.length(17); check(copy.pageStart == 16, "length clamps viewport"); copy.turnPage(1); check(copy.pageStart == 0, "last page wraps");
        copy.select(-1); check(copy.selectedTrack == 5, "track selection wraps");
        try { Project.load(new DataInputStream(new ByteArrayInputStream(new byte[10]))); throw new AssertionError(); } catch (IOException expected) { checks++; }
        byte[] corrupt = bytes.toByteArray(); corrupt[15] = 99; // selected track
        try { Project.load(new DataInputStream(new ByteArrayInputStream(corrupt))); throw new AssertionError(); } catch (IOException expected) { checks++; }
        for (int size : new int[]{1, 8, 35, bytes.size() - 1}) {
            try { Project.load(new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(bytes.toByteArray(), size)))); throw new AssertionError(); } catch (IOException expected) { checks++; }
        }
    }
    private static void controls() {
        Project p = new Project(); Synth s = new Synth(p); Control c = control(s);
        check(c.input(1, input(0, 0)), "initial input accepted");
        check(c.input(2, input(1 << 9, 0)), "SEQ button accepted"); check(p.page == Control.SEQ, "SEQ page");
        c.input(3, input(0, 1L << 6)); check(p.selected().notes[3] == 60, "F-based keyboard white B maps to step 4");
        c.input(4, input(0, 0, 4)); check(p.pageStart == 64, "SELECT pages beyond Skiff 64 steps");
        c.input(5, input(0, 1)); check(p.selected().notes[64] == 60, "hardware edits extended step");
        c.input(5, input(0, 1)); check(p.selected().notes[64] == 60, "duplicate input not applied twice");
        check(!c.input(7, input(0, 0)), "missing input rejected");
        p.page = Control.HOME; c.input(6, input(0, 0)); c.input(7, input(0, 1));
        check(s.activeVoices() == 1 && p.lastNote == 53, "F3 key makes phone voice");
        c.input(8, input(0, 1, 0, 1)); check(p.selectedTrack == 1, "algorithm chooses track");
        c.input(9, input(0, 0)); s.render(new short[44100 * 2], 44100); check(s.activeVoices() == 0, "release applies to original track after track switch");
        p.page = Control.SEQ; c.knob(1, 56); check(p.selected().length == 1024, "pattern can extend to 1024");
        p.selected().name = "Unicode: Ω\nlong long long";
        byte[] snap = c.snapshot(0); check(snap.length == 92 && Protocol.u14(snap, 3) == 1024, "snapshot exact layout");
        for (byte b : snap) check((b & 128) == 0, "snapshot seven-bit-safe");
        check(Protocol.u14(snap, 7) == Protocol.NONE, "stopped playhead is none");
        p.select(0); p.pageStart = 0; p.page = Control.SEQ; p.selected().notes[15] = 72;
        snap = c.snapshot(0); check((snap[13] & 2) != 0 && (Protocol.u28(snap, 16) & (1L << 26)) != 0, "16th step mask/LED high bits");
        c.reset(); int beforeTrack = p.selectedTrack; check(c.input(1, input(1 << 10, 1, 0, 1)), "held takeover baseline");
        check(p.selectedTrack == (beforeTrack + 1) % 6, "first input encoder deltas are not discarded"); check(!s.playing() && s.activeVoices() == 0, "takeover doesn't replay held controls");
    }
    private static void audio() {
        for (int engine = 0; engine < Project.ENGINES.length; engine++) {
            Project p = new Project(); p.tracks[0].engine = engine; Synth s = new Synth(p);
            s.noteOn(0, 60); short[] out = new short[8192]; s.render(out, 4096);
            long energy = 0; int peak = 0; for (short v : out) { energy += (long) v * v; peak = Math.max(peak, Math.abs((int) v)); }
            check(energy > 0 && peak < 32767, "engine " + engine + " sounds without PCM overflow");
            s.panic(); Arrays.fill(out, (short) 111); s.render(out, 4096);
            check(Arrays.equals(out, new short[out.length]), "panic silences engine " + engine);
        }
        Project p = new Project(); p.selected().length = 17; p.selected().notes[0] = 60; p.selected().notes[16] = 72;
        Synth a = new Synth(p), b = new Synth(p); a.start(); b.start();
        short[] whole = new short[30000 * 2]; a.render(whole, 30000);
        short[] fragmented = new short[whole.length]; int at = 0;
        while (at < 30000) { int frames = Math.min(137, 30000 - at); short[] chunk = new short[frames * 2]; b.render(chunk, frames); System.arraycopy(chunk, 0, fragmented, at * 2, chunk.length); at += frames; }
        check(Arrays.equals(whole, fragmented), "sample timing independent of block size");
        check(a.playhead(0) == 5 && b.playhead(0) == 5, "120 BPM sixteenth-note timing");
        check(a.audiblePlayhead(0, 6000) == 4, "audible head accounts for queued frames");
        p.bpm = 240; check(a.audiblePlayhead(0, 6000) == 4, "tempo changes do not rewrite past audible positions");
        Project recorded = new Project(); Synth rec = new Synth(recorded); recorded.recording = true; rec.start();
        rec.render(new short[12000], 6000);
        check(rec.playhead(0) == 1 && rec.audiblePlayhead(0, 2000) == 0, "rendered and audible recording positions differ");
        rec.record(0, 74, 2000);
        check(recorded.selected().notes[0] == 74 && recorded.selected().notes[1] == -1, "recording follows audible step, not render head");
        rec.record(0, 75, 6000); check(recorded.selected().notes[0] == 75, "pre-roll recording uses upcoming first step");
        a.stop(); check(!a.playing() && a.activeVoices() == 0 && a.playhead(0) == -1, "stop resets voices and position");
        for (int i = 0; i < 80; i++) a.noteOn(i % 6, 40 + i % 60);
        check(a.activeVoices() == Synth.VOICES, "polyphony bounded with deterministic voice stealing");
        p.tracks[0].mute = true; a.render(new short[2], 1); check(a.activeVoices() < Synth.VOICES, "muting kills track voices");
        Project longProject = new Project(); longProject.bpm = 300; longProject.selected().length = 1024;
        Synth longSynth = new Synth(longProject); longSynth.start();
        short[] block = new short[4410]; for (int i = 0; i < 1025; i++) longSynth.render(block, 2205);
        check(longSynth.playhead(0) == 0, "1024-step loop wraps correctly");
    }
    private static void link() {
        Project p = new Project(); Synth synth = new Synth(p); List<Protocol.Frame> sent = new ArrayList<>(); List<String> errors = new ArrayList<>();
        Link link = new Link(synth, control(synth), new Link.Transport() {
            public void send(byte[] data) { sent.add(decode(data)); }
            public void failed(String reason) { errors.add(reason); }
        });
        link.begin(12, 0); check(sent.get(0).command == Protocol.HELLO, "HELLO first");
        link.receive(decode(Protocol.frame(Protocol.ACK, 13, 0, new byte[]{1})), 1);
        link.tick(40, 0); check(sent.size() == 1, "foreign session ACK ignored");
        link.receive(decode(Protocol.frame(Protocol.ACK, 12, 0, new byte[]{1})), 50);
        link.tick(51, 0); check(sent.get(1).command == Protocol.STATE && sent.get(1).serial == 1, "ACK sends first STATE");
        link.receive(decode(Protocol.frame(Protocol.ACK, 12, 1, new byte[]{1})), 250); check(link.applied(), "heartbeat confirms applied state");
        link.receive(decode(Protocol.frame(Protocol.INPUT, 12, 1, input(0, 0))), 251);
        synth.start();
        link.receive(decode(Protocol.frame(Protocol.INPUT, 12, 3, input(0, 0))), 252);
        check(!link.isOpen() && !synth.playing() && errors.size() == 1, "serial gap panics and closes");
        check(sent.get(sent.size() - 1).command == Protocol.BYE, "gap sends BYE");
        link.begin(14, 300); link.receive(decode(Protocol.frame(Protocol.ACK, 14, 0, new byte[]{1})), 301);
        link.tick(1801, 0); check(!link.isOpen() && errors.size() == 2, "lease expiry fails closed");
        link.begin(15, 2000); link.tick(3500, 0); check(!link.isOpen(), "missing handshake times out");
        link.begin(16, 4000);
        link.receive(decode(Protocol.frame(Protocol.ACK, 16, 9, new byte[]{1})), 4200);
        link.tick(4300, 0); check(!link.applied(), "nonzero handshake acknowledgment rejected");
        link.receive(decode(Protocol.frame(Protocol.ACK, 16, 0, new byte[]{1})), 4301); link.tick(4302, 0);
        link.receive(decode(Protocol.frame(Protocol.ACK, 16, 0, new byte[]{1})), 5600);
        link.receive(decode(Protocol.frame(Protocol.ACK, 16, 12, new byte[]{1})), 5700);
        link.tick(5801, 0); check(!link.isOpen(), "stale handshake or future ACK does not renew lease");
        link.begin(17, 6000); link.receive(decode(Protocol.frame(Protocol.ACK, 17, 0, new byte[]{1})), 6001); link.tick(6002, 0);
        link.receive(decode(Protocol.frame(Protocol.ACK, 17, 1, new byte[]{1})), 6003);
        link.receive(decode(Protocol.frame(Protocol.ACK, 17, 1, new byte[]{1})), 7400); link.tick(7503, 0);
        check(!link.isOpen(), "replayed applied-state ACK cannot renew lease");
        link.begin(18, 8000); link.receive(decode(Protocol.frame(Protocol.ACK, 18, 0, new byte[]{1})), 8001);
        int states = 0;
        for (int i = 1; states <= 16384; i++) {
            int before = sent.size();
            long now = 8001 + i * 40L; link.tick(now, 0);
            check(sent.size() == before + 1, "at most one outgoing frame per tick");
            Protocol.Frame outgoing = sent.get(sent.size() - 1);
            if (outgoing.command == Protocol.STATE) states++;
            link.receive(decode(Protocol.frame(Protocol.ACK, 18, states & 16383, new byte[]{1})), now + 1);
        }
        check(link.isOpen() && link.applied(), "applied-state ACK progress survives revision wrap");
        link.close(); sent.clear(); link.begin(19, 800000); link.tick(800250, 0);
        check(sent.size() == 2 && sent.get(1).command == Protocol.HELLO && sent.get(1).serial == 0,
            "lost HELLO is retried instead of sending a heartbeat to an unknown session");
        link.receive(decode(Protocol.frame(Protocol.ACK, 19, 0, new byte[]{1})), 800251);
        link.tick(800252, 0); check(sent.get(2).command == Protocol.STATE, "retry handshake proceeds to STATE");
    }
    private static void nativeEnvelope() throws Exception {
        check(Envelope.format(0, 0).equals("1.0 ms") && Envelope.format(3, 127).equals("10.0 s"), "native time endpoints");
        check(Envelope.format(2, 0).equals("0%") && Envelope.format(2, 127).equals("100%"), "native sustain formatting");
        for (int value = 0; value < 128; value++) {
            check(Envelope.milliseconds(Envelope.nearestTime(Envelope.milliseconds(value))) == Envelope.milliseconds(value), "native time round trip");
        }
        Envelope env = new Envelope(); int[] adsr = {0, 0, 64, 0}; env.start();
        env.tick(adsr, true); check(env.stage() == 1 && env.levelQ24() > 0, "linear attack begins");
        env.tick(adsr, true); check(env.stage() == 2 && env.levelQ24() == (1 << 24), "attack reaches exact peak");
        for (int i = 0; i < 20; i++) env.tick(adsr, true);
        check(env.levelQ24() == (64 << 17), "decay approaches native sustain Q24");
        adsr[2] = 0;
        for (int i = 0; i < 20; i++) env.tick(adsr, true);
        check(env.stage() == 2 && env.levelQ24() == 0, "zero sustain holds silently");
        adsr[2] = 127;
        for (int i = 0; i < 20; i++) env.tick(adsr, true);
        check(env.levelQ24() >= (127 << 17) - 1, "sustain changes during hold follow native envelope");
        env.tick(adsr, false); check(env.stage() == 3 && env.levelQ24() < (127 << 17), "release begins from current level");
        for (int i = 0; i < 20; i++) env.tick(adsr, false);
        check(env.stage() == 0 && env.levelQ24() == 0, "native release terminates");
        env.start(); adsr = new int[]{127, 127, 127, 127};
        for (int i = 0; i < 16; i++) env.tick(adsr, true);
        int early = env.levelQ24(); env.tick(adsr, false);
        check(env.stage() == 3 && env.levelQ24() <= early, "early release never jumps to sustain or peak");
        for (int i = 0; i < 60000; i++) env.tick(adsr, false);
        check(env.stage() == 0, "long release eventually ends");
        // Native amp_at divides signed ramp deltas toward zero, including descending ramps.
        Envelope ramp = new Envelope(), endpoint = new Envelope(); ramp.start(); endpoint.start();
        int previous = 0, next = 0; adsr = new int[]{0, 30, 64, 30};
        for (int sample = 0; sample < 4096; sample++) {
            boolean gate = sample < 2048; int offset = sample & 31;
            if (offset == 0) { previous = next; next = endpoint.tick(adsr, gate); }
            int expected = previous + (next - previous) * offset / 32;
            check(ramp.sample(adsr, gate) == expected / 32768.0, "native sample interpolation including descending ramps");
        }
        Project silent = new Project(), held = new Project();
        Arrays.fill(silent.selected().envelope, 0); Arrays.fill(held.selected().envelope, 0); held.selected().envelope[2] = 127;
        Synth quietSynth = new Synth(silent), heldSynth = new Synth(held); quietSynth.noteOn(0, 60); heldSynth.noteOn(0, 60);
        short[] quietAudio = new short[20000], heldAudio = new short[20000]; quietSynth.render(quietAudio, 10000); heldSynth.render(heldAudio, 10000);
        long quietEnergy = 0, heldEnergy = 0;
        for (int i = 16000; i < 20000; i++) { quietEnergy += (long) quietAudio[i] * quietAudio[i]; heldEnergy += (long) heldAudio[i] * heldAudio[i]; }
        check(quietEnergy == 0 && heldEnergy > 0, "native sustain controls real rendered phone sound");
        Project p = new Project(); Synth synth = new Synth(p); Control c = control(synth);
        c.input(1, input(0, 0)); p.page = Control.SEQ; c.input(2, input(1 << 2, 0));
        check(p.page == Control.ENV, "physical ENV enters native envelope from sequencer");
        c.input(3, input(0, 0, 1)); check(p.page == Control.ENV, "ENV SELECT cannot advertise unsupported pages");
        c.input(4, input(0, 0, 0, 1)); check(p.page == Control.ENV && p.selectedTrack == 1, "track selection retains ENV");
        int[] initial = p.selected().envelope.clone();
        c.input(5, input(0, 0, 0, 0, 0, 1, 2, 3, 4));
        for (int i = 0; i < 4; i++) check(p.selected().envelope[i] == initial[i] + i + 1, "ENV knob order and native detents");
        for (int i = 0; i < 4; i++) { c.knob(i, 1000); check(p.selected().envelope[i] == 127, "ENV clamps upper boundary"); c.knob(i, -1000); check(p.selected().envelope[i] == 0, "ENV clamps lower boundary"); }
        p.selected().envelope[1] = 70; p.octave = -3; byte[] state = c.snapshot(0);
        check(Protocol.u14(state, 86) == 70 && state[1] == Control.ENV && ((state[2] >>> 3) & 7) == 0, "snapshot native ADSR/octave lower bound");
        p.octave = 3; state = c.snapshot(0); check(((state[2] >>> 3) & 7) == 6 && (state[2] & 64) == 0, "snapshot octave upper bound");
        for (int i = 0; i < 4; i++) check(new String(state, 52 + i * 8, 8, "US-ASCII").trim().equals(Envelope.LABELS[i]), "snapshot native ENV labels");
        p.page = Control.SOUND; c.knob(2, 1); c.knob(3, 2);
        check(p.selected().envelope[0] == 1 && p.selected().envelope[3] == 2, "sound macros share authoritative ADSR");
        ByteArrayOutputStream current = new ByteArrayOutputStream(); p.save(new DataOutputStream(current));
        Project loaded = Project.load(new DataInputStream(new ByteArrayInputStream(current.toByteArray())));
        check(Arrays.equals(loaded.selected().envelope, p.selected().envelope) && !loaded.migratedFromVersion1, "native ADSR persists as project v2");
        Project old = new Project(); old.selected().name = "Saved v1"; old.length(1024); old.selected().notes[1023] = 81;
        old.selected().params[2] = 0.37; old.selected().params[3] = 0.68;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x43414641); out.writeInt(1); out.writeInt(old.bpm); out.writeInt(0); out.writeInt(Control.ENV); out.writeInt(1008); out.writeInt(0); out.writeInt(60);
        for (Project.Track t : old.tracks) {
            out.writeUTF(t.name); out.writeInt(t.engine); out.writeInt(t.length);
            for (int n : t.notes) out.writeShort(n);
            for (double value : t.params) out.writeDouble(value);
            out.writeDouble(t.volume); out.writeDouble(t.pan); out.writeDouble(t.gate); out.writeBoolean(t.mute);
        }
        java.io.File backups = java.nio.file.Files.createTempDirectory("cafa-migration-test").toFile();
        byte[] original = bytes.toByteArray();
        java.io.File preserved = ProjectBackup.preserve(backups, original);
        check(Arrays.equals(original, java.nio.file.Files.readAllBytes(preserved.toPath())), "legacy backup byte-verified before migration");
        check(ProjectBackup.preserve(backups, original).equals(preserved), "matching backup safely reused");
        java.nio.file.Files.write(preserved.toPath(), new byte[]{1, 2, 3});
        java.io.File repaired = ProjectBackup.preserve(backups, original);
        check(!repaired.equals(preserved) && Arrays.equals(original, java.nio.file.Files.readAllBytes(repaired.toPath())), "partial backup does not permit unprotected migration");
        byte[] different = original.clone(); different[different.length - 1] ^= 1;
        java.io.File distinct = ProjectBackup.preserve(backups, different);
        check(!distinct.equals(repaired) && Arrays.equals(original, java.nio.file.Files.readAllBytes(repaired.toPath())), "different restored legacy project preserves distinct originals");
        for (java.io.File file : backups.listFiles()) java.nio.file.Files.delete(file.toPath());
        java.nio.file.Files.delete(backups.toPath());
        Project migrated = Project.load(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        check(migrated.migratedFromVersion1 && migrated.selected().notes[1023] == 81 && migrated.selected().name.equals("Saved v1"), "v1 migration preserves patterns and names");
        check(migrated.selected().envelope[0] == Envelope.nearestTime(2 + 0.37 * 0.37 * 2000) && migrated.selected().envelope[3] == Envelope.nearestTime(25 + 0.68 * 0.68 * 4000), "v1 migration preserves closest displayed A/R times");
        check(migrated.selected().envelope[1] == 0 && migrated.selected().envelope[2] == 127, "v1 AR migration adds near-full sustain and minimal decay");
        migrated.selected().envelope[0] = 128; bytes.reset(); migrated.save(new DataOutputStream(bytes));
        try { Project.load(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))); throw new AssertionError(); } catch (IOException expected) { checks++; }
        List<Protocol.Frame> sent = new ArrayList<>();
        Link link = new Link(synth, c, new Link.Transport() { public void send(byte[] frame) { sent.add(decode(frame)); } public void failed(String reason) {} });
        link.begin(55, 0); check(sent.get(0).version == 2, "HELLO negotiates native-ENV v2");
        link.receive(decode(Protocol.frame(1, Protocol.ACK, 55, 0, new byte[]{1})), 1); link.tick(40, 0);
        check(sent.size() == 1 && !link.applied(), "legacy ACK cannot enable mismatched ENV semantics");
        link.receive(decode(Protocol.frame(Protocol.ACK, 55, 0, new byte[]{1})), 41); link.tick(42, 0);
        check(sent.size() == 2 && sent.get(1).command == Protocol.STATE && sent.get(1).version == 2, "matching v2 handshake sends native state");
        int before = p.selectedTrack;
        link.receive(decode(Protocol.frame(1, Protocol.INPUT, 55, 1, input(0, 0, 0, 1))), 43);
        check(p.selectedTrack == before, "legacy input ignored during v2 session");
    }
    public static void main(String[] args) throws Exception {
        protocol(); project(); controls(); audio(); link(); nativeEnvelope();
        System.out.println("PASS: " + checks + " assertions (protocol, persistence, controls, synthesis, sequencing, leases)");
    }
}
