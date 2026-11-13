/* SPDX-License-Identifier: GPL-3.0-only */
package dev.cafa.core;

/** Bounded 32-voice phone synth, sequenced by rendered samples rather than the UI clock. */
public final class Synth {
    public static final int RATE = 44100, VOICES = 32;
    private static final double TAU = Math.PI * 2;
    public final Project project;
    private final Voice[] voices = new Voice[VOICES];
    private boolean playing;
    private long frame, transportFrame, step;
    private double untilStep;
    private final int[] playheads = new int[Project.TRACKS];
    private final long[] eventFrames = new long[64];
    private final int[][] eventHeads = new int[64][Project.TRACKS];
    private int eventCount;
    private static final class Voice {
        boolean active, releasing;
        int track, note, engine;
        long age, releaseAt;
        double phase, modPhase, envelope, filter;
        final double[] sawPhase = new double[7];
        final Envelope adsr = new Envelope();
    }
    public Synth(Project project) {
        this.project = project;
        for (int i = 0; i < VOICES; i++) voices[i] = new Voice();
        java.util.Arrays.fill(playheads, -1);
    }
    public synchronized boolean playing() { return playing; }
    public synchronized long frame() { return frame; }
    public synchronized int playhead(int track) { return playing ? playheads[track] : -1; }
    public synchronized int audiblePlayhead(int track, long queuedFrames) {
        if (!playing) return -1;
        long audible = frame - Math.max(0, queuedFrames) - 1;
        for (int n = 0; n < Math.min(eventCount, 64); n++) {
            int at = (eventCount - 1 - n) & 63;
            if (eventFrames[at] <= audible) return eventHeads[at][track] % project.tracks[track].length;
        }
        return -1;
    }
    public synchronized void start() {
        panic(); playing = true; transportFrame = 0; step = 0; untilStep = 0; eventCount = 0;
    }
    public synchronized void stop() { playing = false; panic(); java.util.Arrays.fill(playheads, -1); }
    public synchronized void panic() { for (Voice v : voices) v.active = false; }
    public synchronized int activeVoices() { int n = 0; for (Voice v : voices) if (v.active) n++; return n; }
    public synchronized void noteOn(int track, int note) { noteOn(track, note, Long.MAX_VALUE); }
    private void noteOn(int track, int note, long releaseAt) {
        if (note < 0 || note > 127 || project.tracks[track].mute) return;
        Voice v = voices[0];
        for (Voice candidate : voices) {
            if (!candidate.active) { v = candidate; break; }
            if (candidate.age < v.age) v = candidate;
        }
        v.active = true; v.releasing = false; v.track = track; v.note = note;
        v.engine = project.tracks[track].engine; v.age = frame; v.releaseAt = releaseAt;
        v.phase = 0; v.modPhase = 0; v.envelope = 0; v.filter = 0; v.adsr.start();
        for (int i = 0; i < 7; i++) v.sawPhase[i] = i / 7.0;
    }
    public synchronized void noteOff(int track, int note) {
        for (Voice v : voices) if (v.active && v.track == track && v.note == note && v.releaseAt == Long.MAX_VALUE) v.releasing = true;
    }
    public synchronized void record(int track, int note, long queuedFrames) {
        // Before the first rendered step reaches the output, record into the upcoming first step.
        if (playing && project.recording) {
            int at = Math.max(0, audiblePlayhead(track, queuedFrames));
            project.tracks[track].notes[at] = note;
        }
    }
    public synchronized void render(short[] out, int frames) {
        if (frames < 0 || frames > out.length / 2) throw new IllegalArgumentException("Output too short");
        for (int i = 0; i < frames; i++) {
            if (playing) {
                if (untilStep <= 0) {
                    double stepFrames = RATE * 60.0 / (project.bpm * 4.0);
                    for (int t = 0; t < Project.TRACKS; t++) {
                        Project.Track track = project.tracks[t];
                        int at = (int) (step % track.length);
                        playheads[t] = at;
                        if (track.notes[at] >= 0) noteOn(t, track.notes[at], frame + Math.max(1, (long) (stepFrames * track.gate)));
                    }
                    int event = eventCount & 63;
                    eventFrames[event] = frame;
                    System.arraycopy(playheads, 0, eventHeads[event], 0, Project.TRACKS);
                    eventCount++; step++; untilStep += stepFrames;
                }
                untilStep--; transportFrame++;
            }
            double left = 0, right = 0;
            for (Voice v : voices) {
                if (!v.active) continue;
                Project.Track t = project.tracks[v.track];
                if (t.mute) { v.active = false; continue; }
                if (frame >= v.releaseAt) v.releasing = true;
                v.envelope = v.adsr.sample(t.envelope, !v.releasing);
                if (!v.adsr.running() && v.envelope == 0) { v.active = false; continue; }
                double frequency = 440 * Math.pow(2, (v.note - 69) / 12.0);
                double increment = frequency / RATE;
                double sample;
                if (v.engine == 0) {
                    sample = 0;
                    for (int s = 0; s < 7; s++) {
                        double inc = Math.min(0.45, increment * Math.pow(2, (s - 3) * t.params[0] * 0.018));
                        double ph = v.sawPhase[s];
                        sample += 2 * ph - 1 - polyBlep(ph, inc);
                        v.sawPhase[s] = (ph + inc) % 1;
                    }
                    sample /= 7;
                    double cutoff = 70 + t.params[1] * t.params[1] * 15000;
                    double alpha = 1 - Math.exp(-TAU * cutoff / RATE);
                    v.filter += alpha * (sample - v.filter); sample = v.filter;
                } else if (v.engine == 1) {
                    double ratio = 1 + Math.floor(t.params[1] * 7);
                    v.modPhase = (v.modPhase + increment * ratio) % 1;
                    double index = t.params[0] * 8 * v.envelope;
                    sample = Math.sin(TAU * v.phase + Math.sin(TAU * v.modPhase) * index) * 0.7;
                } else {
                    sample = 0;
                    double total = 0;
                    int harmonics = 1 + (int) (t.params[0] * 15);
                    for (int h = 1; h <= harmonics && h * frequency < RATE * 0.45; h++) {
                        double weight = (h % 2 == 0 ? t.params[1] : 1.0) / h;
                        sample += Math.sin(TAU * v.phase * h) * weight; total += weight;
                    }
                    sample = total > 0 ? sample / total : 0;
                }
                v.phase = (v.phase + increment) % 1;
                sample *= v.envelope * t.volume * 0.23;
                left += sample * Math.sqrt((1 - t.pan) * 0.5);
                right += sample * Math.sqrt((1 + t.pan) * 0.5);
            }
            out[i * 2] = (short) Math.round(Math.tanh(left) * 30000);
            out[i * 2 + 1] = (short) Math.round(Math.tanh(right) * 30000);
            frame++;
        }
    }
    private static double polyBlep(double phase, double delta) {
        if (phase < delta) { double t = phase / delta; return t + t - t * t - 1; }
        if (phase > 1 - delta) { double t = (phase - 1) / delta; return t * t + t + t + 1; }
        return 0;
    }
}
