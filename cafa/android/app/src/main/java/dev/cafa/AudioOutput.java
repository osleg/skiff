/* SPDX-License-Identifier: GPL-3.0-only */
package dev.cafa;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import dev.cafa.core.Synth;

/** Foreground-only output. A preference is never reported as a verified USB audio route. */
public final class AudioOutput {
    public interface Listener { void ready(String route); void failed(String reason); }
    private final AudioManager manager;
    private final Handler main;
    private final Listener listener;
    private AudioTrack track;
    private Thread thread;
    private AudioFocusRequest focus;
    private volatile boolean running, verified;
    private Synth source;
    private long renderStart;
    private long lastHead, headEpoch;
    private int generation;
    public AudioOutput(AudioManager manager, Handler main, Listener listener) {
        this.manager = manager; this.main = main; this.listener = listener;
    }
    public boolean running() { return running; }
    public boolean verified() { return running && verified; }
    public long queuedFrames() {
        AudioTrack current = track;
        if (!running || current == null) return 0;
        long head = current.getPlaybackHeadPosition() & 0xffffffffL;
        if (head < lastHead) headEpoch += 1L << 32;
        lastHead = head;
        return Math.max(0, source.frame() - renderStart - (headEpoch + head));
    }
    public void start(Synth synth, AudioDeviceInfo output) {
        stop();
        if (output == null) { listener.failed("Select a specific audio output first."); return; }
        int token = ++generation;
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (generation == token && running && change != AudioManager.AUDIOFOCUS_GAIN) listener.failed("Audio focus lost. Playback and FM1 session stopped.");
                }, main).build();
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            listener.failed("Android did not grant audio focus."); return;
        }
        try {
            int minimum = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum < 0) throw new IllegalStateException("44.1 kHz stereo output is unavailable");
            AudioTrack local = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(Synth.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum, 2048))
                    .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
            track = local;
            if (local.getState() != AudioTrack.STATE_INITIALIZED || !local.setPreferredDevice(output)) {
                throw new IllegalStateException("Android rejected the selected audio route");
            }
            source = synth; renderStart = synth.frame(); lastHead = 0; headEpoch = 0; running = true; verified = false;
            local.play();
            thread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                short[] buffer = new short[512];
                long started = SystemClock.elapsedRealtime();
                try {
                    while (running && generation == token) {
                        synth.render(buffer, 256);
                        int at = 0;
                        while (at < buffer.length && running && generation == token) {
                            int n = local.write(buffer, at, buffer.length - at, AudioTrack.WRITE_BLOCKING);
                            if (n <= 0) throw new IllegalStateException("Audio write failed (" + n + ")");
                            at += n;
                        }
                        AudioDeviceInfo actual = local.getRoutedDevice();
                        if (actual != null && actual.getId() == output.getId()) {
                            if (!verified) {
                                verified = true;
                                main.post(() -> { if (generation == token && running) listener.ready(output.getProductName().toString()); });
                            }
                        } else if (verified || SystemClock.elapsedRealtime() - started > 1000) {
                            throw new IllegalStateException("Selected audio route is not active; refusing a silent fallback");
                        }
                    }
                } catch (Exception e) {
                    main.post(() -> { if (generation == token && running) listener.failed(e.getMessage()); });
                }
            }, "CAFA-audio");
            thread.start();
        } catch (Exception e) { stop(); listener.failed("Cannot start audio: " + e.getMessage()); }
    }
    public void stop() {
        running = false; verified = false; generation++;
        AudioTrack local = track; track = null;
        if (local != null) {
            try { local.pause(); local.flush(); } catch (IllegalStateException ignored) { }
        }
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        thread = null;
        if (local != null) local.release();
        if (focus != null) { manager.abandonAudioFocusRequest(focus); focus = null; }
    }
}
