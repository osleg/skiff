/* SPDX-License-Identifier: GPL-3.0-only */
import dev.cafa.core.*;
import java.io.*;
import java.nio.file.*;

/** Byte-independent trace for comparison against Skiff's actual env_tick. */
public final class EnvelopeVectors {
    public static void main(String[] args) throws Exception {
        Path path = Paths.get(args.length == 0 ? "build/vectors/envelope.tsv" : args[0]);
        Files.createDirectories(path.getParent());
        int[][] cases = {{10,70,90,60}, {0,0,0,0}, {127,127,127,127}, {127,70,64,60}, {20,40,100,30}};
        int[] gates = {700, 30, 30000, 5, 600};
        int[] lengths = {4000, 100, 60000, 4000, 3000};
        int rows = 0;
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path))) {
            for (int c = 0; c < cases.length; c++) {
                Envelope envelope = new Envelope(); envelope.start(); int[] values = cases[c].clone();
                for (int tick = 0; tick < lengths[c]; tick++) {
                    if (c == 4 && tick == 300) values[2] = 10;
                    if (c == 4 && tick == 700) values[3] = 80;
                    boolean gate = tick < gates[c]; envelope.tick(values, gate);
                    out.printf(java.util.Locale.ROOT, "%d %d %d %d %d %d %d %d %d%n", c, tick, values[0], values[1], values[2], values[3], gate ? 1 : 0, envelope.levelQ24(), envelope.stage());
                    rows++;
                }
            }
        }
        System.out.println("Envelope vectors: " + rows + " native-stage/control-tick comparisons");
    }
}
