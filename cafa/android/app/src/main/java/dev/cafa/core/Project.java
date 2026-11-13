/* SPDX-License-Identifier: GPL-3.0-only */
package dev.cafa.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/** Phone-authoritative project. No Android dependencies; persistence is versioned. */
public final class Project {
    public static final int TRACKS = 6, MAX_STEPS = 1024;
    public static final String[] ENGINES = {"Seven-saw", "FM glass", "Harmonic cloud"};
    public static final String[][] LABELS = {
        {"DETUNE", "CUTOFF", "ATTACK", "RELEASE"},
        {"FM INDEX", "RATIO", "ATTACK", "RELEASE"},
        {"HARMONIC", "MORPH", "ATTACK", "RELEASE"}
    };
    public static final class Track {
        public final int id;
        public String name;
        public int engine, length = 128;
        public final int[] notes = new int[MAX_STEPS];
        // First two engine controls; the last slots remain only for v1 migration.
        public final double[] params = {0.25, 0.65, 0.01, 0.25};
        public final int[] envelope = {10, 70, 90, 60};
        public double volume = 0.55, pan, gate = 0.7;
        public boolean mute;
        Track(int id) {
            this.id = id;
            name = "Track " + (id + 1);
            engine = id % ENGINES.length;
            Arrays.fill(notes, -1);
        }
        public String soundName() { return ENGINES[engine]; }
    }
    public final Track[] tracks = new Track[TRACKS];
    public int bpm = 120, selectedTrack, page, pageStart, octave, lastNote = 60;
    public boolean recording;
    public boolean migratedFromVersion1;
    public Project() { for (int i = 0; i < TRACKS; i++) tracks[i] = new Track(i); }
    public Track selected() { return tracks[selectedTrack]; }
    public void select(int track) {
        selectedTrack = Math.floorMod(track, TRACKS);
        pageStart = Math.min(pageStart, ((selected().length - 1) / 16) * 16);
    }
    public void length(int value) {
        selected().length = clamp(value, 1, MAX_STEPS);
        pageStart = Math.min(pageStart, ((selected().length - 1) / 16) * 16);
    }
    public void turnPage(int delta) {
        int pages = (selected().length + 15) / 16;
        pageStart = Math.floorMod(pageStart / 16 + delta, pages) * 16;
    }
    public void toggle(int step) {
        if (step >= 0 && step < selected().length) {
            selected().notes[step] = selected().notes[step] >= 0 ? -1 : lastNote;
        }
    }
    public static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    public static double unit(double v) { return Math.max(0, Math.min(1, v)); }
    public void save(DataOutput out) throws IOException {
        out.writeInt(0x43414641); out.writeInt(2);
        out.writeInt(bpm); out.writeInt(selectedTrack); out.writeInt(page);
        out.writeInt(pageStart); out.writeInt(octave); out.writeInt(lastNote);
        for (Track t : tracks) {
            out.writeUTF(t.name); out.writeInt(t.engine); out.writeInt(t.length);
            for (int n : t.notes) out.writeShort(n);
            for (int i = 0; i < 2; i++) out.writeDouble(t.params[i]);
            for (int e : t.envelope) out.writeByte(e);
            out.writeDouble(t.volume); out.writeDouble(t.pan); out.writeDouble(t.gate);
            out.writeBoolean(t.mute);
        }
    }
    public static Project load(DataInput in) throws IOException {
        if (in.readInt() != 0x43414641) throw new IOException("Not a CAFA project");
        int version = in.readInt();
        if (version != 1 && version != 2) throw new IOException("Unknown CAFA project version");
        Project p = new Project(); p.migratedFromVersion1 = version == 1;
        p.bpm = range(in.readInt(), 20, 300); p.selectedTrack = range(in.readInt(), 0, 5);
        p.page = range(in.readInt(), 0, 5); p.pageStart = range(in.readInt(), 0, 1008);
        p.octave = range(in.readInt(), -3, 3); p.lastNote = range(in.readInt(), 0, 127);
        for (Track t : p.tracks) {
            t.name = in.readUTF();
            if (t.name.length() > 128) throw new IOException("Track name too long");
            t.engine = range(in.readInt(), 0, ENGINES.length - 1);
            t.length = range(in.readInt(), 1, MAX_STEPS);
            for (int i = 0; i < MAX_STEPS; i++) t.notes[i] = range(in.readShort(), -1, 127);
            for (int i = 0; i < (version == 1 ? 4 : 2); i++) t.params[i] = number(in.readDouble(), 0, 1);
            if (version == 1) {
                t.envelope[0] = Envelope.nearestTime(2 + t.params[2] * t.params[2] * 2000);
                t.envelope[1] = 0; t.envelope[2] = 127;
                t.envelope[3] = Envelope.nearestTime(25 + t.params[3] * t.params[3] * 4000);
            } else for (int i = 0; i < 4; i++) t.envelope[i] = range(in.readUnsignedByte(), 0, 127);
            t.volume = number(in.readDouble(), 0, 1); t.pan = number(in.readDouble(), -1, 1);
            t.gate = number(in.readDouble(), 0.05, 1); t.mute = in.readBoolean();
        }
        if ((p.pageStart & 15) != 0 || p.pageStart >= p.selected().length) throw new IOException("Invalid page");
        return p;
    }
    private static int range(int v, int lo, int hi) throws IOException {
        if (v < lo || v > hi) throw new IOException("Project value outside supported range");
        return v;
    }
    private static double number(double v, double lo, double hi) throws IOException {
        if (!Double.isFinite(v) || v < lo || v > hi) throw new IOException("Invalid project parameter");
        return v;
    }
}
