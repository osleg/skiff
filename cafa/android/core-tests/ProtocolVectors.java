/* SPDX-License-Identifier: GPL-3.0-only */
import dev.cafa.core.*;
import java.nio.file.*;

/** Emits byte-for-byte Android frames for the firmware host parser integration test. */
public final class ProtocolVectors {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length == 0 ? "build/vectors" : args[0]); Files.createDirectories(dir);
        Project p = new Project(); p.selectedTrack = 5; p.tracks[5].length = 1024; p.page = Control.SEQ; p.pageStart = 1008;
        p.tracks[5].name = "CAFA long name Ω"; p.tracks[5].engine = 2; p.tracks[5].notes[1023] = 72;
        Synth synth = new Synth(p); Control control = new Control(synth, new Control.Actions() { public void save() {} public void notice(String s) {} });
        Files.write(dir.resolve("hello.syx"), Protocol.frame(Protocol.HELLO, 42, 0, new byte[0]));
        Files.write(dir.resolve("state.syx"), Protocol.frame(Protocol.STATE, 42, 1, control.snapshot(0)));
        Files.write(dir.resolve("heartbeat.syx"), Protocol.frame(Protocol.HEARTBEAT, 42, 1, new byte[0]));
        Files.write(dir.resolve("bye.syx"), Protocol.frame(Protocol.BYE, 42, 1, new byte[0]));
        p.page = Control.ENV; p.octave = 2;
        int[] adsr = {0, 70, 96, 127}; System.arraycopy(adsr, 0, p.selected().envelope, 0, 4);
        Files.write(dir.resolve("env-state.syx"), Protocol.frame(Protocol.STATE, 42, 2, control.snapshot(0)));
        System.out.println("Android vectors: session42, revision1, track5, length1024, pageStart1008, playheadNONE, bpm120, step15, ASCII name fallback");
    }
}
