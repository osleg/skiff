/* SPDX-License-Identifier: GPL-3.0-only */
package dev.cafa;

import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Handler;
import android.os.SystemClock;
import dev.cafa.core.Control;
import dev.cafa.core.Link;
import dev.cafa.core.Protocol;
import dev.cafa.core.Synth;
import java.io.IOException;
import java.security.SecureRandom;

/** One explicitly selected USB MIDI endpoint. No Bluetooth or network discovery. */
public final class MidiConnection {
    public interface Listener { void status(String text); void failed(String reason); }
    private final MidiManager manager;
    private final Handler main;
    private final Listener listener;
    private final Synth synth;
    private final Control control;
    private final SecureRandom random = new SecureRandom();
    private MidiDevice device;
    private MidiInputPort input;
    private MidiOutputPort output;
    private Link link;
    private int generation, deviceId = -1;
    public MidiConnection(MidiManager manager, Handler main, Synth synth, Control control, Listener listener) {
        this.manager = manager; this.main = main; this.synth = synth; this.control = control; this.listener = listener;
    }
    public boolean isOpen() { return link != null && link.isOpen(); }
    public boolean applied() { return link != null && link.applied(); }
    public int deviceId() { return deviceId; }
    public void open(MidiDeviceInfo info) {
        close();
        if (manager == null || info == null || info.getType() != MidiDeviceInfo.TYPE_USB) {
            listener.failed("Select a USB MIDI device."); return;
        }
        int token = ++generation; deviceId = info.getId();
        listener.status("Opening selected USB MIDI device…");
        manager.openDevice(info, opened -> {
            if (generation != token) { if (opened != null) try { opened.close(); } catch (IOException ignored) { } return; }
            if (opened == null) { listener.failed("Android could not open that MIDI device."); return; }
            device = opened;
            int inPort = -1, outPort = -1;
            for (MidiDeviceInfo.PortInfo port : info.getPorts()) {
                if (port.getType() == MidiDeviceInfo.PortInfo.TYPE_INPUT && inPort < 0) inPort = port.getPortNumber();
                if (port.getType() == MidiDeviceInfo.PortInfo.TYPE_OUTPUT && outPort < 0) outPort = port.getPortNumber();
            }
            input = inPort < 0 ? null : opened.openInputPort(inPort);
            output = outPort < 0 ? null : opened.openOutputPort(outPort);
            if (input == null || output == null) { listener.failed("The selected MIDI device needs an available input and output port."); return; }
            link = new Link(synth, control, new Link.Transport() {
                public void send(byte[] bytes) throws IOException { if (input == null) throw new IOException("Port closed"); input.send(bytes, 0, bytes.length); }
                public void failed(String reason) { main.post(() -> { if (generation == token) listener.failed(reason); }); }
            });
            Protocol.Decoder decoder = new Protocol.Decoder(frame -> {
                main.post(() -> { if (generation == token && link != null) link.receive(frame, SystemClock.elapsedRealtime()); });
            });
            output.connect(new MidiReceiver() {
                @Override public void onSend(byte[] bytes, int offset, int count, long timestamp) {
                    synchronized (decoder) { decoder.feed(bytes, offset, count); }
                }
                @Override public void onFlush() { synchronized (decoder) { decoder.reset(); } }
            });
            link.begin(1 + random.nextInt(16383), SystemClock.elapsedRealtime());
            listener.status("Waiting for matching CAFA native-ENV firmware (protocol v2)…");
        }, main);
    }
    public void tick(long queuedFrames) {
        if (link != null) link.tick(SystemClock.elapsedRealtime(), queuedFrames);
    }
    public void close() {
        generation++;
        if (link != null) link.close(); link = null;
        try { if (output != null) output.close(); } catch (IOException ignored) { }
        try { if (input != null) input.close(); } catch (IOException ignored) { }
        try { if (device != null) device.close(); } catch (IOException ignored) { }
        output = null; input = null; device = null; deviceId = -1;
    }
}
