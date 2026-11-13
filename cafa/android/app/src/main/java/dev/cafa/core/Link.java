/* SPDX-License-Identifier: GPL-3.0-only */
package dev.cafa.core;

/** Lease/session manager. Serial gaps never apply a partial stream of encoder changes. */
public final class Link {
    public interface Transport {
        void send(byte[] data) throws Exception;
        void failed(String reason);
    }
    private final Transport transport;
    private final Control control;
    private final Synth synth;
    private int session, revision;
    private long sentStates, acknowledgedState;
    private long began, lastAck, lastHeartbeat, lastSnapshot;
    private boolean waiting, open, applied;
    public Link(Synth synth, Control control, Transport transport) {
        this.synth = synth; this.control = control; this.transport = transport;
    }
    public synchronized void begin(int session, long now) {
        if (open) close();
        this.session = session; revision = 0; sentStates = 0; acknowledgedState = 0; began = now; lastAck = now;
        lastHeartbeat = now; lastSnapshot = now - 40; waiting = true; open = true; applied = false;
        control.reset();
        send(Protocol.HELLO, 0, new byte[0]);
    }
    public synchronized boolean isOpen() { return open; }
    public synchronized boolean applied() { return applied; }
    public synchronized void receive(Protocol.Frame frame, long now) {
        if (!open || frame.session != session || frame.version != Protocol.VERSION) return;
        if (frame.command == Protocol.ACK && frame.payload.length == 1 && frame.payload[0] == 1) {
            if (waiting) {
                if (frame.serial != 0) return;
                lastAck = now; waiting = false;
                return;
            }
            // Resolve a modulo revision into the recent stream of states we actually sent.
            int behind = (revision - frame.serial) & 16383;
            if (behind >= 8192) return;
            long candidate = sentStates - behind;
            if (candidate <= acknowledgedState || candidate <= 0) return;
            acknowledgedState = candidate; lastAck = now; applied = true;
        } else if (frame.command == Protocol.INPUT && !waiting) {
            if (!control.input(frame.serial, frame.payload)) fail("MIDI input sequence gap. Stopped safely; reconnect to resynchronize.");
        }
    }
    public synchronized void tick(long now, long queuedFrames) {
        if (!open) return;
        if (now - lastAck >= 1500 || (waiting && now - began >= 1500)) {
            fail(waiting ? "FM1 did not confirm native ENV protocol v2. Install matching CAFA firmware and check the MIDI device."
                : "FM1 stopped acknowledging applied state. Audio stopped; reconnect when ready."); return;
        }
        // Firmware has one incoming SysEx mailbox. Never put a heartbeat directly
        // behind a state in the same tick: it can be discarded before servicing.
        // HELLO is idempotent, so retry it while waiting instead of assuming that
        // opening Android's output port means its first receive is already ready.
        if (now - lastHeartbeat >= 250) {
            send(waiting ? Protocol.HELLO : Protocol.HEARTBEAT, waiting ? 0 : revision, new byte[0]);
            lastHeartbeat = now;
        } else if (!waiting && now - lastSnapshot >= 34) {
            sentStates++; revision = (int) (sentStates & 16383);
            send(Protocol.STATE, revision, control.snapshot(queuedFrames)); lastSnapshot = now;
        }
    }
    public synchronized void close() {
        synth.stop(); control.reset();
        if (!open) return;
        open = false; applied = false;
        try { transport.send(Protocol.frame(Protocol.BYE, session, revision, new byte[0])); }
        catch (Exception ignored) { /* Firmware lease also expires without heartbeats. */ }
    }
    private void send(int command, int serial, byte[] payload) {
        if (!open) return;
        try { transport.send(Protocol.frame(command, session, serial, payload)); }
        catch (Exception e) { fail("MIDI send failed: " + e.getClass().getSimpleName()); }
    }
    private void fail(String reason) { close(); transport.failed(reason); }
}
