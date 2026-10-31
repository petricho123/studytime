package com.petricho.threedays;

import android.app.Application;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class StudyApp extends Application {
    public final StudyEngine engine = new StudyEngine();
    public boolean recovered, loadFailed, saveFailed;
    public boolean activityResumed;
    private AtomicFile storage;
    private long wallAnchor, elapsedAnchor;

    @Override public void onCreate() {
        super.onCreate();
        wallAnchor = System.currentTimeMillis(); elapsedAnchor = SystemClock.elapsedRealtime();
        storage = new AtomicFile(new File(getFilesDir(), "study-records.json"));
        if (!storage.getBaseFile().exists()) return;
        try {
            JSONObject root = new JSONObject(new String(storage.readFully(), StandardCharsets.UTF_8));
            JSONArray list = root.getJSONArray("records");
            ArrayList<StudyEngine.Interval> records = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                long s = r.getLong("start"), e = r.getLong("end");
                if (s < 0 || e <= s) throw new IllegalArgumentException("Invalid interval");
                records.add(new StudyEngine.Interval(r.getLong("id"), s, e));
            }
            engine.restore(records);
            String previous = root.optString("state", "STOPPED");
            recovered = !previous.equals("STOPPED");
            engine.state = recovered ? StudyEngine.State.MANUAL_PAUSED : StudyEngine.State.STOPPED;
        } catch (Exception e) { loadFailed = true; }
    }
    /** Monotonic elapsed time avoids adding hours if the system clock is changed mid-session. */
    public long now() { return wallAnchor + SystemClock.elapsedRealtime() - elapsedAnchor; }
    public void reanchorClock() {
        engine.checkpoint(now());
        wallAnchor = System.currentTimeMillis(); elapsedAnchor = SystemClock.elapsedRealtime();
        if (engine.state == StudyEngine.State.RUNNING) engine.activeStart = now();
        save();
    }
    public void save() {
        if (loadFailed) return; // Do not overwrite unreadable data with an empty history.
        FileOutputStream out = null;
        try {
            JSONObject root = new JSONObject(); root.put("version", 1);
            root.put("state", engine.state.name());
            JSONArray list = new JSONArray();
            for (StudyEngine.Interval r : engine.records) {
                JSONObject row = new JSONObject(); row.put("id", r.id); row.put("start", r.start); row.put("end", r.end); list.put(row);
            }
            root.put("records", list);
            out = storage.startWrite(); out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            storage.finishWrite(out); saveFailed = false;
        } catch (Exception e) { if (out != null) storage.failWrite(out); saveFailed = true; }
    }
}
