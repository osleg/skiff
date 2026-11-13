/* SPDX-License-Identifier: GPL-3.0-only
 * Native ENV semantics ported from Skiff voice.c and params.c.
 * Original envelope copyright (C) 2026 Leo Kuroshita, Hugelton Instruments. */
package dev.cafa.core;

/** Native Skiff envelope: Q24 state, one tick per 32 samples at 44.1 kHz. */
public final class Envelope {
    public static final int CONTROL_SAMPLES = 32;
    public static final String[] LABELS = {"ATTACK", "DECAY", "SUSTAIN", "RELEASE"};
    private int value, stage, offset, previous, next;
    public void start() { value = 0; stage = 1; offset = 0; previous = 0; next = 0; }
    public void clear() { value = 0; stage = 0; offset = 0; previous = 0; next = 0; }
    public boolean running() { return stage != 0 || offset != 0 || next != 0; }
    public int levelQ24() { return value; }
    public int stage() { return stage; }

    /** Same signed wide multiply/shift and stage transitions as native env_tick. */
    public int tick(int[] parameters, boolean gate) {
        if (!gate && stage != 0 && stage < 3) stage = 3;
        switch (stage) {
            case 1:
                value += EnvelopeTables.ENV_LIN[parameters[0]];
                if (value >= (1 << 24)) { value = 1 << 24; stage = 2; }
                break;
            case 2:
                int sustain = parameters[2] << 17;
                value += (int) (((long) (sustain - value) * EnvelopeTables.ENV_EXP[parameters[1]]) >> 16);
                break;
            case 3:
                value -= (int) (((long) value * EnvelopeTables.ENV_EXP[parameters[3]]) >> 16);
                if (value < (1 << 12)) { value = 0; stage = 0; }
                break;
            default:
                value = 0;
        }
        return value >> 9;
    }
    /** Interpolate native Q15 control endpoints, keeping timing independent of render chunks. */
    public double sample(int[] parameters, boolean gate) {
        if (offset == 0) { previous = next; next = tick(parameters, gate); }
        int amplitude = previous + (next - previous) * offset / CONTROL_SAMPLES;
        offset = (offset + 1) & 31;
        return amplitude / 32768.0;
    }
    public static double milliseconds(int value) {
        return EnvelopeTables.TIME_MS_X10[Project.clamp(value, 0, 127)] / 10.0;
    }
    public static int nearestTime(double milliseconds) {
        int best = 0; double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 128; i++) {
            double candidate = Math.abs(Math.log(milliseconds / milliseconds(i)));
            if (candidate < distance) { best = i; distance = candidate; }
        }
        return best;
    }
    /** Exactly the native F_TIME/F_PCT numeric formatting, including its rounding. */
    public static String format(int parameter, int value) {
        value = Project.clamp(value, 0, 127);
        if (parameter == 2) return ((value * 100 + 63) / 127) + "%";
        int ms10 = EnvelopeTables.TIME_MS_X10[value];
        if (ms10 < 100) return (ms10 / 10) + "." + (ms10 % 10) + " ms";
        if (ms10 < 10000) return ((ms10 + 5) / 10) + " ms";
        if (ms10 >= 100000) return (ms10 / 10000) + "." + ((ms10 / 1000) % 10) + " s";
        int hundredths = ms10 / 100;
        return (hundredths / 100) + "." + (hundredths % 100 < 10 ? "0" : "") + (hundredths % 100) + " s";
    }
}
