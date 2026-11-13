/* SPDX-License-Identifier: GPL-3.0-only */
package dev.cafa;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import dev.cafa.core.Control;
import dev.cafa.core.Envelope;
import dev.cafa.core.Project;
import dev.cafa.core.ProjectBackup;
import dev.cafa.core.Synth;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Minimal native instrument UI. Hardware and touch edit the same Project instance. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 20, 27), FG = Color.rgb(231, 238, 246), ACCENT = Color.rgb(73, 210, 169);
    private final Handler main = new Handler(Looper.getMainLooper());
    private Project project;
    private Synth synth;
    private Control control;
    private AudioOutput audio;
    private MidiConnection midi;
    private AudioManager audioManager;
    private MidiManager midiManager;
    private AtomicFile projectFile;
    private LinearLayout root;
    private TextView status, pageInfo, transportInfo, pitchInfo;
    private EditText name, length;
    private Spinner audioSpinner, midiSpinner, engineSpinner;
    private SeekBar bpm, pitch, volume, pan, gate;
    private final SeekBar[] parameters = new SeekBar[4];
    private final TextView[] paramLabels = new TextView[4];
    private final SeekBar[] envelopeParameters = new SeekBar[4];
    private final TextView[] envelopeLabels = new TextView[4];
    private boolean saveAllowed = true;
    private final Button[] tracks = new Button[6], steps = new Button[16], pages = new Button[6];
    private Button play, record, mute, audioButton, connect;
    private final List<AudioDeviceInfo> audioDevices = new ArrayList<>();
    private final List<MidiDeviceInfo> midiDevices = new ArrayList<>();
    private boolean updating, resumed, wasLinked;
    private int lastTrack = -1, selectedAudioId = -1;
    private String routeLabel = "Audio stopped";
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!resumed) return;
            midi.tick(audio.queuedFrames());
            if (midi.applied() && !wasLinked) { status.setText("FM1 synchronized with native ENV. Phone owns sound and timeline."); wasLinked = true; }
            refreshModel();
            main.postDelayed(this, 34);
        }
    };
    private final AudioDeviceCallback audioCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { refreshDevices(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            for (AudioDeviceInfo device : removed) if (device.getId() == selectedAudioId && audio.running()) stopEverything("Audio device disconnected. Playback stopped.");
            refreshDevices();
        }
    };
    private final MidiManager.DeviceCallback midiCallback = new MidiManager.DeviceCallback() {
        @Override public void onDeviceAdded(MidiDeviceInfo info) { refreshDevices(); }
        @Override public void onDeviceRemoved(MidiDeviceInfo info) {
            if (info.getId() == midi.deviceId()) stopEverything("FM1 disconnected. Local Skiff resumes after its lease expires.");
            refreshDevices();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        projectFile = new AtomicFile(new File(getFilesDir(), "project.cafa"));
        String loaded = "Choose an audio output, start audio, then connect your FM1.";
        byte[] originalProject = null;
        try (java.io.InputStream in = projectFile.openRead()) {
            originalProject = ProjectBackup.read(in);
            project = Project.load(new DataInputStream(new ByteArrayInputStream(originalProject)));
        }
        catch (java.io.FileNotFoundException e) {
            project = new Project();
            if (projectFile.getBaseFile().exists() || new File(getFilesDir(), "project.cafa.bak").exists()) {
                saveAllowed = false; loaded = "Saved project is not readable. Autosave disabled to preserve it.";
            }
        }
        catch (Exception e) { project = new Project(); saveAllowed = false; loaded = "Saved project could not be loaded. Autosave disabled to preserve it: " + e.getMessage(); }
        if (project.migratedFromVersion1) {
            try {
                ProjectBackup.preserve(getFilesDir(), originalProject);
                loaded = "Previous project preserved. A/R times migrated to native Skiff ADSR; release is now exponential. Patterns and names retained.";
            } catch (Exception e) { saveAllowed = false; loaded = "Could not preserve the original project. Autosave disabled: " + e.getMessage(); }
        }
        synth = new Synth(project);
        control = new Control(synth, new Control.Actions() {
            public void save() { saveProject(true); }
            public void notice(String text) { Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show(); }
        });
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        midiManager = (MidiManager) getSystemService(MIDI_SERVICE);
        audio = new AudioOutput(audioManager, main, new AudioOutput.Listener() {
            public void ready(String route) { routeLabel = route; status.setText("Verified output: " + route + ". Connect FM1 or play on the touchscreen."); }
            public void failed(String reason) { stopEverything(reason); }
        });
        control.setAudioQueue(audio::queuedFrames);
        midi = new MidiConnection(midiManager, main, synth, control, new MidiConnection.Listener() {
            public void status(String text) { status.setText(text); }
            public void failed(String reason) { stopEverything(reason); }
        });
        buildUi(); root.setFocusableInTouchMode(true); root.requestFocus(); status.setText(loaded); refreshDevices(); refreshModel();
        audioManager.registerAudioDeviceCallback(audioCallback, main);
        if (midiManager != null) midiManager.registerDeviceCallback(midiCallback, main);
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(20, 20, 20, 36); scroll.addView(root); setContentView(scroll);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int padding = (int) (16 * getResources().getDisplayMetrics().density);
            view.setPadding(padding + insets.getSystemWindowInsetLeft(), padding + insets.getSystemWindowInsetTop(),
                    padding + insets.getSystemWindowInsetRight(), padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = text("CAFA", 30); title.setTextColor(ACCENT);
        text("Phone engines · synchronized FM1 control", 14);
        status = text("", 14);
        text("1  AUDIO OUTPUT", 16); audioSpinner = spinner(root);
        audioButton = button("Start audio", root, () -> {
            if (audio.running()) { stopEverything("Audio stopped."); return; }
            int selected = audioSpinner.getSelectedItemPosition();
            if (selected < 0 || selected >= audioDevices.size()) { status.setText("No supported audio output is available."); return; }
            synth.stop(); selectedAudioId = audioDevices.get(selected).getId();
            audio.start(synth, audioDevices.get(selected));
        });
        text("2  FM1 USB MIDI", 16); midiSpinner = spinner(root);
        LinearLayout connectionRow = row(root);
        connect = button("Connect FM1", connectionRow, () -> {
            if (midi.isOpen()) { midi.close(); wasLinked = false; status.setText("FM1 disconnected; standalone Skiff resumes."); return; }
            if (!audio.verified()) { status.setText("Start and verify the chosen audio output first."); return; }
            int selected = midiSpinner.getSelectedItemPosition();
            if (selected < 0 || selected >= midiDevices.size()) { status.setText("No bidirectional USB MIDI device found. Use a data/OTG cable."); return; }
            wasLinked = false; midi.open(midiDevices.get(selected));
        });
        button("Refresh", connectionRow, this::refreshDevices);
        button("Stop / panic", connectionRow, () -> stopEverything("Stopped safely."));
        text("Phone speaker is an explicit preview option. For USB return select the FM1 audio output; routing and real-device latency still need validation.", 12);
        text("TRACKS", 16);
        LinearLayout trackRow = row(root);
        for (int i = 0; i < 6; i++) { final int t = i; tracks[i] = button(Integer.toString(i + 1), trackRow, () -> { synchronized (synth) { project.select(t); } }); }
        LinearLayout names = row(root);
        name = input(names, false); name.setHint("Track name");
        button("Rename", names, () -> { synchronized (synth) { String value = name.getText().toString().trim(); if (!value.isEmpty()) project.selected().name = value.substring(0, Math.min(128, value.length())); } saveProject(false); });
        engineSpinner = spinner(root); engineSpinner.setAdapter(adapter(java.util.Arrays.asList(Project.ENGINES)));
        engineSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int at, long id) { if (!updating) synchronized (synth) { project.selected().engine = at; } }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        LinearLayout pageRow = row(root);
        String[] labels = {"HOME", "SEQ", "SOUND", "ENV", "FX", "MIX"};
        for (int i = 0; i < 6; i++) { final int p = i; pages[i] = button(labels[i], pageRow, () -> { synchronized (synth) { project.page = p; } }); pages[i].setTextSize(10); }
        text("The first slice implements sound, envelope, sequence and mixer controls. FX, LFO, ARP and full Skiff project compatibility are not implemented.", 12);
        LinearLayout transport = row(root);
        play = button("Play", transport, () -> { if (!audio.verified()) { status.setText("Start audio first."); return; } synchronized (synth) { if (synth.playing()) synth.stop(); else synth.start(); } });
        record = button("Record", transport, () -> { synchronized (synth) { project.recording = !project.recording; } });
        mute = button("Mute", transport, () -> { synchronized (synth) { project.selected().mute = !project.selected().mute; } });
        button("Save", transport, () -> saveProject(true));
        transportInfo = text("", 14);
        bpm = slider("Tempo", 280, value -> project.bpm = value + 20, null);
        text("PATTERN", 16);
        LinearLayout lengthRow = row(root); length = input(lengthRow, true); length.setHint("1–1024 steps");
        button("Set length", lengthRow, () -> {
            try { int n = Integer.parseInt(length.getText().toString()); if (n < 1 || n > 1024) throw new NumberFormatException(); synchronized (synth) { project.length(n); } }
            catch (NumberFormatException e) { status.setText("Length must be between 1 and 1024 steps."); }
        });
        LinearLayout bank = row(root);
        button("Previous 16", bank, () -> { synchronized (synth) { project.turnPage(-1); project.page = Control.SEQ; } });
        button("Next 16", bank, () -> { synchronized (synth) { project.turnPage(1); project.page = Control.SEQ; } });
        pageInfo = text("", 14);
        GridLayout grid = new GridLayout(this); grid.setColumnCount(4); root.addView(grid);
        for (int i = 0; i < 16; i++) {
            final int at = i; steps[i] = new Button(this); steps[i].setTextSize(14);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = 0; lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); steps[i].setLayoutParams(lp);
            grid.addView(steps[i]); steps[i].setOnClickListener(v -> { synchronized (synth) { project.toggle(project.pageStart + at); project.page = Control.SEQ; } });
        }
        pitchInfo = text("", 14);
        pitch = slider("New step pitch", 127, value -> project.lastNote = value, null);
        text("PLAY KEYS", 16);
        LinearLayout keyboard = row(root);
        for (int i = 0; i < 8; i++) {
            final int offset = new int[]{0, 2, 4, 5, 7, 9, 11, 12}[i];
            Button key = button(new String[]{"C", "D", "E", "F", "G", "A", "B", "C"}[i], keyboard, () -> {});
            final int[] held = {-1, -1};
            key.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && audio.verified()) {
                    synchronized (synth) {
                        held[0] = project.selectedTrack; held[1] = Project.clamp(60 + offset + project.octave * 12, 0, 127);
                        project.lastNote = held[1]; synth.noteOn(held[0], held[1]); synth.record(held[0], held[1], audio.queuedFrames());
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (held[1] >= 0) synth.noteOff(held[0], held[1]); held[1] = -1; v.performClick();
                }
                return true;
            });
        }
        LinearLayout octaves = row(root);
        button("Octave −", octaves, () -> { synchronized (synth) { project.octave = Math.max(-3, project.octave - 1); } });
        button("Octave +", octaves, () -> { synchronized (synth) { project.octave = Math.min(3, project.octave + 1); } });
        text("TOUCHSCREEN SOUND EDITOR", 16);
        for (int i = 0; i < 4; i++) {
            final int parameter = i;
            paramLabels[i] = text("", 14);
            parameters[i] = slider(null, i < 2 ? 1000 : 127, value -> {
                if (parameter < 2) project.selected().params[parameter] = value / 1000.0;
                else project.selected().envelope[parameter == 2 ? 0 : 3] = value;
            }, null);
        }
        text("NATIVE SKIFF ADSR", 16);
        text("Same 0–127 values, time mapping and envelope stages as FM1 ENV. Decay/release reach about 99% of their change at the displayed time. ENV SYNC is off.", 12);
        for (int i = 0; i < 4; i++) {
            final int e = i;
            envelopeLabels[i] = text("", 14);
            envelopeParameters[i] = slider(null, 127, value -> { project.selected().envelope[e] = value; project.page = Control.ENV; }, null);
        }
        volume = slider("Track volume", 1000, value -> project.selected().volume = value / 1000.0, null);
        pan = slider("Stereo pan", 1000, value -> project.selected().pan = value / 500.0 - 1, null);
        gate = slider("Step gate", 950, value -> project.selected().gate = (value + 50) / 1000.0, null);
        button("About this build", root, () -> new AlertDialog.Builder(this).setTitle("CAFA native ENV prototype")
                .setMessage("Six tracks, 1–1024 steps each, three phone synth engines, 32 voices, native Skiff ADSR controls and envelopes, local save/reopen, protocol v2 synchronization and selected audio routing. Foreground only: leaving the app stops audio and releases FM1. Each step holds one note. No samples, effects, song arrangement or original Skiff project import. Hardware takeover, USB return and latency require an FM1/Android device test. GPL-3.0-only; source is in cafa/android of the accompanying Skiff source tree.")
                .setPositiveButton("Close", null).show());
    }
    private interface Change { void set(int value); }
    private SeekBar slider(String label, int maximum, Change change, LinearLayout parent) {
        if (label != null) text(label, 13);
        SeekBar bar = new SeekBar(this); bar.setMax(maximum); (parent == null ? root : parent).addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) { if (fromUser) synchronized (synth) { change.set(progress); } }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        }); return bar;
    }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(FG); t.setPadding(0, 12, 0, 8); root.addView(t); return t; }
    private LinearLayout row(LinearLayout parent) { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); parent.addView(r); return r; }
    private Button button(String title, LinearLayout parent, Runnable action) { Button b = new Button(this); b.setText(title); b.setTextSize(12); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(4, 2, 4, 2); parent.addView(b, new LinearLayout.LayoutParams(parent.getOrientation() == LinearLayout.HORIZONTAL ? 0 : LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, parent.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0)); b.setOnClickListener(v -> action.run()); return b; }
    private EditText input(LinearLayout parent, boolean number) { EditText e = new EditText(this); e.setTextColor(FG); e.setSingleLine(true); if (number) e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); parent.addView(e, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2)); return e; }
    private Spinner spinner(LinearLayout parent) { Spinner s = new Spinner(this); parent.addView(s); return s; }
    private ArrayAdapter<String> adapter(List<String> values) { ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values); a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); return a; }
    private void refreshDevices() {
        if (audioSpinner == null) return;
        int oldAudio = audioSpinner.getSelectedItemPosition();
        int wantedAudio = oldAudio >= 0 && oldAudio < audioDevices.size() ? audioDevices.get(oldAudio).getId() : selectedAudioId;
        int oldMidi = midiSpinner.getSelectedItemPosition();
        int wantedMidi = oldMidi >= 0 && oldMidi < midiDevices.size() ? midiDevices.get(oldMidi).getId() : -1;
        audioDevices.clear(); List<String> audioNames = new ArrayList<>(); int chosen = 0;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type != AudioDeviceInfo.TYPE_USB_DEVICE && type != AudioDeviceInfo.TYPE_USB_HEADSET && type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) continue;
            if (device.getId() == wantedAudio) chosen = audioDevices.size();
            audioDevices.add(device); audioNames.add((type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? "Phone speaker preview: " : "USB output: ") + device.getProductName() + " (#" + device.getId() + ")");
        }
        audioSpinner.setAdapter(adapter(audioNames)); if (!audioDevices.isEmpty()) audioSpinner.setSelection(chosen);
        midiDevices.clear(); List<String> midiNames = new ArrayList<>(); chosen = 0;
        if (midiManager != null) for (MidiDeviceInfo device : midiManager.getDevices()) {
            if (device.getType() != MidiDeviceInfo.TYPE_USB || device.getInputPortCount() == 0 || device.getOutputPortCount() == 0) continue;
            if (device.getId() == wantedMidi) chosen = midiDevices.size();
            midiDevices.add(device); midiNames.add(device.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME, "USB MIDI") + " (#" + device.getId() + ")");
        }
        midiSpinner.setAdapter(adapter(midiNames)); if (!midiDevices.isEmpty()) midiSpinner.setSelection(chosen);
    }
    private void refreshModel() {
        if (play == null) return;
        updating = true;
        synchronized (synth) {
            Project.Track t = project.selected();
            if (lastTrack != project.selectedTrack) { name.setText(t.name); length.setText(Integer.toString(t.length)); lastTrack = project.selectedTrack; }
            if (!name.hasFocus() && !name.getText().toString().equals(t.name)) name.setText(t.name);
            if (!length.hasFocus()) length.setText(Integer.toString(t.length));
            if (engineSpinner.getSelectedItemPosition() != t.engine) engineSpinner.setSelection(t.engine);
            for (int i = 0; i < 6; i++) { tracks[i].setText((i + 1) + (project.tracks[i].mute ? " ×" : "")); tracks[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(i == project.selectedTrack ? ACCENT : Color.LTGRAY)); pages[i].setTextColor(i == project.page ? Color.rgb(0, 110, 70) : Color.BLACK); }
            play.setText(synth.playing() ? "Stop" : "Play"); record.setText(project.recording ? "REC ●" : "Record"); mute.setText(t.mute ? "Unmute" : "Mute");
            int head = synth.audiblePlayhead(project.selectedTrack, audio.queuedFrames());
            transportInfo.setText(project.bpm + " BPM · " + t.length + " steps · octave " + project.octave + " · " + synth.activeVoices() + " voices\n" + routeLabel);
            pageInfo.setText(t.name + " · steps " + (project.pageStart + 1) + "–" + Math.min(project.pageStart + 16, t.length) + " / " + t.length + (head >= 0 ? " · playing " + (head + 1) : ""));
            for (int i = 0; i < 16; i++) {
                int at = project.pageStart + i; boolean enabled = at < t.length; steps[i].setEnabled(enabled);
                steps[i].setText((at + 1) + (enabled && t.notes[at] >= 0 ? "\n" + noteName(t.notes[at]) : "\n·"));
                steps[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(at == head ? Color.rgb(244, 190, 73) : enabled && t.notes[at] >= 0 ? ACCENT : Color.LTGRAY));
            }
            bpm.setProgress(project.bpm - 20); pitch.setProgress(project.lastNote); pitchInfo.setText("New steps: " + noteName(project.lastNote) + " (MIDI " + project.lastNote + ")");
            for (int i = 0; i < 4; i++) {
                if (i < 2) {
                    parameters[i].setProgress((int) (t.params[i] * 1000));
                    paramLabels[i].setText(Project.LABELS[t.engine][i] + "  " + Math.round(t.params[i] * 1000) / 10.0 + "%");
                } else {
                    int e = i == 2 ? 0 : 3;
                    parameters[i].setProgress(t.envelope[e]);
                    paramLabels[i].setText(Envelope.LABELS[e] + "  " + Envelope.format(e, t.envelope[e]));
                }
                envelopeParameters[i].setProgress(t.envelope[i]);
                envelopeLabels[i].setText(Envelope.LABELS[i] + "  " + Envelope.format(i, t.envelope[i]));
            }
            volume.setProgress((int) (t.volume * 1000)); pan.setProgress((int) ((t.pan + 1) * 500)); gate.setProgress((int) (t.gate * 1000) - 50);
        }
        audioButton.setText(audio.running() ? "Stop audio" : "Start audio"); connect.setText(midi.isOpen() ? "Disconnect FM1" : "Connect FM1");
        audioSpinner.setEnabled(!audio.running()); midiSpinner.setEnabled(!midi.isOpen());
        updating = false;
    }
    private static String noteName(int note) { return new String[]{"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"}[note % 12] + (note / 12 - 1); }
    private void saveProject(boolean notify) {
        if (!saveAllowed) { if (notify) status.setText("Saving is disabled to preserve the previous project file."); return; }
        FileOutputStream stream = null;
        try {
            stream = projectFile.startWrite(); DataOutputStream out = new DataOutputStream(stream);
            synchronized (synth) { project.save(out); }
            out.flush(); projectFile.finishWrite(stream);
            if (notify) status.setText("Project saved locally. It will reopen stopped.");
        } catch (Exception e) { if (stream != null) projectFile.failWrite(stream); status.setText("Project save failed: " + e.getMessage()); }
    }
    private void stopEverything(String reason) {
        if (midi != null) midi.close(); if (synth != null) synth.stop(); if (audio != null) audio.stop();
        wasLinked = false; routeLabel = "Audio stopped"; if (status != null) status.setText(reason);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; main.post(tick); }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(tick); stopEverything("Paused. Playback and FM1 session stopped."); saveProject(false); super.onPause(); }
    @Override protected void onDestroy() { audioManager.unregisterAudioDeviceCallback(audioCallback); if (midiManager != null) midiManager.unregisterDeviceCallback(midiCallback); stopEverything("Closed."); super.onDestroy(); }
}
