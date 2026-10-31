package com.petricho.threedays;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private StudyApp app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingLeave;
    private String date = LocalDate.now().toString(), month = YearMonth.now().toString(), pendingExport;
    private static final int EXPORT = 40, IMPORT = 41, NOTIFICATIONS = 42;
    private boolean importing, webReady;

    @SuppressLint("SetJavaScriptEnabled") // Required for packaged, CSP-restricted assets; all remote navigation is blocked.
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); app = (StudyApp) getApplication();
        getWindow().setStatusBarColor(0xfff7fbff); getWindow().setNavigationBarColor(0xfff7fbff);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        web = new WebView(this); web.setBackgroundColor(0xfff7fbff);
        web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(0); web.getSettings().setSupportMultipleWindows(false);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "StudyBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"planner.local".equals(uri.getHost())) return blocked();
                String path = uri.getPath();
                String file = path == null || "/".equals(path) ? "index.html" : path.substring(1);
                if (!file.matches("[a-zA-Z0-9.-]+")) return blocked();
                try {
                    String mime = file.endsWith(".js") ? "application/javascript" : file.endsWith(".css") ? "text/css" : "text/html";
                    return new WebResourceResponse(mime, "UTF-8", getAssets().open(file));
                } catch (Exception e) { return blocked(); }
            }
            @Override public void onPageFinished(WebView view, String url) { webReady = true; openRequestedPage(); snapshot(); }
        });
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        if (saved != null) pendingExport = saved.getString("export");
        // Keep the original origin and localStorage key to retain existing planner data on updates.
        web.loadUrl("https://planner.local/");
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); if (webReady) openRequestedPage();
    }
    private void openRequestedPage() {
        if (getIntent().getBooleanExtra("open_study", false)) {
            js("window.StudyUI && window.StudyUI.show('study')"); getIntent().removeExtra("open_study");
        }
    }
    private WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", new java.io.ByteArrayInputStream(new byte[0])); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("export", pendingExport); super.onSaveInstanceState(out); }
    @Override protected void onResume() {
        super.onResume(); app.activityResumed = true;
        if (pendingLeave != null) handler.removeCallbacks(pendingLeave);
        if (unlocked()) app.engine.returnToApp(app.now());
        app.save(); refreshService();
        js("window.refreshToday && window.refreshToday();window.StudyUI && window.StudyUI.request()");
    }
    @Override protected void onPause() {
        app.activityResumed = false; app.engine.checkpoint(app.now()); app.save(); super.onPause();
    }
    @Override protected void onStop() { super.onStop(); if (!isChangingConfigurations()) scheduleLeave(); }
    @Override protected void onUserLeaveHint() { super.onUserLeaveHint(); if (unlocked()) autoPause(app.now()); }
    private boolean unlocked() { return getSystemService(PowerManager.class).isInteractive() && !getSystemService(KeyguardManager.class).isKeyguardLocked(); }
    private void autoPause(long now) {
        if (TimerService.current != null) TimerService.current.autoPause(now);
        else { app.engine.leaveScreen(getSystemService(PowerManager.class).isInteractive(), getSystemService(KeyguardManager.class).isKeyguardLocked(), Math.max(app.engine.activeStart, now)); app.save(); }
    }
    private void scheduleLeave() {
        if (pendingLeave != null) handler.removeCallbacks(pendingLeave);
        long leaveAt = app.now();
        pendingLeave = () -> { if (!app.activityResumed && unlocked()) autoPause(leaveAt); };
        handler.postDelayed(pendingLeave, 500);
    }
    @Override public void onBackPressed() {
        if (web == null) return;
        web.evaluateJavascript("window.closeEditor ? window.closeEditor() : false", result -> {
            if ("false".equals(result)) { autoPause(app.now()); moveTaskToBack(true); }
        });
    }
    @Override protected void onDestroy() {
        if (web != null) { web.removeJavascriptInterface("StudyBridge"); web.destroy(); web = null; }
        super.onDestroy();
    }
    private void js(String script) { if (web != null && !isDestroyed()) web.evaluateJavascript(script, null); }
    private void message(String text) { js("window.StudyUI && window.StudyUI.message(" + JSONObject.quote(text) + ")"); }
    private void refreshService() { if (TimerService.current != null) TimerService.current.refresh(); }
    private long midnight(LocalDate day) { return day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    private List<StudyEngine.Interval> displayIntervals(long now) {
        List<StudyEngine.Interval> items = new ArrayList<>(app.engine.records);
        if (app.engine.state == StudyEngine.State.RUNNING && now > app.engine.activeStart) {
            if (!items.isEmpty() && items.get(items.size() - 1).end == app.engine.activeStart) {
                StudyEngine.Interval last = items.remove(items.size() - 1);
                items.add(new StudyEngine.Interval(last.id, last.start, now));
            } else items.add(new StudyEngine.Interval(-1, app.engine.activeStart, now));
        }
        return items;
    }
    private JSONArray dayRecords(LocalDate day, long now) throws Exception {
        JSONArray rows = new JSONArray(); long from = midnight(day), until = midnight(day.plusDays(1));
        List<StudyEngine.Interval> items = displayIntervals(now);
        items.sort(Comparator.comparingLong((StudyEngine.Interval i) -> i.start).reversed());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        for (StudyEngine.Interval r : items) {
            long start = Math.max(from, r.start), end = Math.min(until, r.end);
            if (end <= start) continue;
            boolean live = app.engine.state == StudyEngine.State.RUNNING && r.end == now && now < until;
            String label = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(fmt) + " – " +
                    (live ? "공부 중" : end == until ? "24:00" : Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).format(fmt));
            JSONObject row = new JSONObject(); row.put("id", Long.toString(r.id)); row.put("start", r.start); row.put("end", r.end);
            row.put("elapsed", end - start); row.put("label", label); row.put("live", live); rows.put(row);
        }
        return rows;
    }
    private void snapshot() {
        try {
            long now = app.now(); LocalDate today = LocalDate.now(), selected = LocalDate.parse(date); YearMonth ym = YearMonth.parse(month);
            JSONObject data = new JSONObject(); data.put("today", today.toString()); data.put("date", date); data.put("month", month);
            data.put("state", app.engine.state.name()); data.put("todayTotal", app.engine.totalForDay(today, ZoneId.systemDefault(), now));
            data.put("selectedTotal", app.engine.totalForDay(selected, ZoneId.systemDefault(), now));
            data.put("monthTotal", app.engine.totalBetween(midnight(ym.atDay(1)), midnight(ym.plusMonths(1).atDay(1)), now));
            JSONArray days = new JSONArray(); for (int d = 1; d <= ym.lengthOfMonth(); d++) days.put(app.engine.totalForDay(ym.atDay(d), ZoneId.systemDefault(), now));
            data.put("days", days); data.put("todayRecords", dayRecords(today, now)); data.put("selectedRecords", dayRecords(selected, now));
            data.put("saveFailed", app.saveFailed); data.put("loadFailed", app.loadFailed); data.put("recovered", app.recovered);
            js("window.StudyUI && window.StudyUI.receive(" + data + ")"); app.recovered = false;
        } catch (Exception e) { message("기록 표시 실패: " + e.getMessage()); }
    }
    private void command(String action) {
        if (importing) { message("기록을 가져오는 중입니다. 잠시 기다려 주세요."); return; }
        if (app.loadFailed) { message("기존 기록을 보호하기 위해 측정을 중지했습니다."); return; }
        if ("start".equals(action)) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this).setTitle("공부시간 알림 허용")
                        .setMessage("잠금화면에서 시간을 확인하려면 알림을 허용해 주세요. 알림 없이도 앱에서 측정할 수 있습니다.")
                        .setPositiveButton("권한 요청", (d, w) -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS))
                        .setNegativeButton("알림 없이 시작", (d, w) -> startTimer()).show();
            } else startTimer();
        } else {
            if ("pause".equals(action)) app.engine.pause(false, app.now());
            else if ("stop".equals(action)) app.engine.stop(app.now());
            app.save(); refreshService(); snapshot();
        }
    }
    private void startTimer() {
        if (!app.activityResumed || !unlocked()) return;
        try { startForegroundService(new Intent(this, TimerService.class).setAction(TimerService.START)); handler.postDelayed(this::snapshot, 100); }
        catch (RuntimeException e) { message("측정을 시작하지 못했습니다. 앱의 배터리 설정을 확인해 주세요."); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] result) {
        super.onRequestPermissionsResult(code, permissions, result); if (code == NOTIFICATIONS) startTimer();
    }
    private boolean editable() {
        if (app.loadFailed) { message("저장 파일을 읽지 못해 편집을 중지했습니다."); return false; }
        if (app.engine.state == StudyEngine.State.RUNNING) { message("측정을 일시정지한 후 수정·가져오기를 해 주세요."); return false; }
        return true;
    }
    public final class Bridge {
        @JavascriptInterface public void snapshot(String selected, String selectedMonth) {
            handler.post(() -> { try { date = LocalDate.parse(selected).toString(); month = YearMonth.parse(selectedMonth).toString(); MainActivity.this.snapshot(); } catch (Exception e) { message("날짜를 확인해 주세요."); } });
        }
        @JavascriptInterface public void action(String action) { handler.post(() -> command(action)); }
        @JavascriptInterface public void editRecord(String id, long start, long end) {
            handler.post(() -> {
                if (!editable()) return;
                try {
                    if (end > System.currentTimeMillis()) throw new IllegalArgumentException("미래 시각은 기록할 수 없습니다.");
                    if (id.isEmpty()) app.engine.add(start, end); else app.engine.replace(Long.parseLong(id), start, end);
                    app.save(); refreshService();
                    if (app.saveFailed) throw new IllegalStateException("기록 저장에 실패했습니다. 저장 공간을 확인해 주세요.");
                    js("window.StudyUI.recordSaved()"); MainActivity.this.snapshot();
                } catch (Exception e) { js("window.StudyUI.recordError(" + JSONObject.quote(e.getMessage()) + ")"); }
            });
        }
        @JavascriptInterface public void deleteRecord(String id) {
            handler.post(() -> { if (!editable()) return; try { app.engine.remove(Long.parseLong(id)); app.save(); refreshService(); if (app.saveFailed) throw new IllegalStateException("저장 공간을 확인해 주세요."); js("window.StudyUI.recordSaved()"); MainActivity.this.snapshot(); } catch (Exception e) { message(e.getMessage()); } });
        }
        @JavascriptInterface public void openSettings(String kind) {
            handler.post(() -> {
                try {
                    Intent i = "notification".equals(kind) ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()) : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (RuntimeException e) { message("휴대폰 설정에서 앱 알림·배터리 항목을 확인해 주세요."); }
            });
        }
        @JavascriptInterface public void exportData(String planner) { handler.post(() -> exportBackup(planner)); }
        @JavascriptInterface public void chooseImport() {
            handler.post(() -> { if (!editable()) return; Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, IMPORT); });
        }
        @JavascriptInterface public void importData(String text) { handler.post(() -> importBackup(text)); }
    }
    private JSONObject studyJson() throws Exception {
        JSONObject data = new JSONObject(); data.put("version", 1); data.put("state", app.engine.state.name());
        JSONArray rows = new JSONArray(); for (StudyEngine.Interval r : app.engine.records) { JSONObject v = new JSONObject(); v.put("id", r.id); v.put("start", r.start); v.put("end", r.end); rows.put(v); }
        data.put("records", rows); return data;
    }
    private void exportBackup(String planner) {
        if (app.loadFailed) { message("공부 기록을 읽지 못해 백업을 중지했습니다. 기존 데이터를 지우지 마세요."); return; }
        try {
            if ("null".equals(planner)) throw new IllegalArgumentException("플래너 시작일을 먼저 정해 주세요.");
            app.engine.checkpoint(app.now()); app.save();
            JSONObject data = new JSONObject(); data.put("type", "three-day-study-backup"); data.put("version", 1); data.put("planner", new JSONObject(planner)); data.put("study", studyJson());
            pendingExport = data.toString();
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, "three-day-study-" + LocalDate.now() + ".json");
            startActivityForResult(i, EXPORT);
        } catch (Exception e) { message("백업 준비 실패: " + e.getMessage()); }
    }
    private List<StudyEngine.Interval> validateRecords(JSONObject study) throws Exception {
        if (study.optInt("version", 1) != 1) throw new IllegalArgumentException("지원하지 않는 공부 기록 버전입니다.");
        JSONArray rows = study.getJSONArray("records"); if (rows.length() > 100000) throw new IllegalArgumentException("기록이 너무 많습니다.");
        List<StudyEngine.Interval> items = new ArrayList<>(); long now = System.currentTimeMillis();
        for (int i = 0; i < rows.length(); i++) { JSONObject r = rows.getJSONObject(i); long s = r.getLong("start"), e = r.getLong("end"); if (s < 0 || e <= s || e > now) throw new IllegalArgumentException("올바르지 않은 공부 구간입니다."); items.add(new StudyEngine.Interval(i + 1, s, e)); }
        return items;
    }
    private void importBackup(String text) {
        if (!editable() || importing) return;
        try {
            JSONObject root = new JSONObject(text); boolean full = "three-day-study-backup".equals(root.optString("type"));
            if (full && root.optInt("version") != 1) throw new IllegalArgumentException("지원하지 않는 백업 버전입니다.");
            List<StudyEngine.Interval> incoming = validateRecords(full ? root.getJSONObject("study") : root);
            if (full) {
                importing = true;
                String script = "(function(){try{const next=" + root.getJSONObject("planner") + ";window.PlannerUI.validate(next);return JSON.stringify(window.PlannerUI.raw());}catch(e){return null;}})()";
                web.evaluateJavascript(script, result -> {
                    try {
                        if (result == null || "null".equals(result)) throw new IllegalArgumentException("플래너 백업 형식이 올바르지 않습니다.");
                        // Save study first, then persist planner. Roll back study if localStorage rejects it.
                        List<StudyEngine.Interval> previous = new ArrayList<>(app.engine.records); StudyEngine.State previousState = app.engine.state;
                        app.engine.restore(incoming); app.engine.state = StudyEngine.State.MANUAL_PAUSED; app.save();
                        if (app.saveFailed) { app.engine.restore(previous); app.engine.state = previousState; throw new IllegalStateException("공부 기록 저장 실패"); }
                        web.evaluateJavascript("window.PlannerUI.restore(" + root.getJSONObject("planner") + ")", ok -> {
                            importing = false;
                            if (!"true".equals(ok)) { app.engine.restore(previous); app.engine.state = previousState; app.save(); message("플래너 저장 실패로 가져오기를 취소했습니다."); }
                            else { refreshService(); js("window.StudyUI.imported()"); snapshot(); }
                        });
                    } catch (Exception e) { importing = false; message("가져오기 실패: " + e.getMessage()); }
                });
            } else {
                List<StudyEngine.Interval> previous = new ArrayList<>(app.engine.records);
                for (StudyEngine.Interval r : incoming) app.engine.add(r.start, r.end);
                app.save();
                if (app.saveFailed) { app.engine.restore(previous); throw new IllegalStateException("저장 공간을 확인해 주세요."); }
                refreshService(); js("window.StudyUI.imported()"); snapshot();
            }
        } catch (Exception e) { message("가져오기 실패: " + e.getMessage()); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) { if (request == EXPORT) pendingExport = null; return; }
        try {
            if (request == EXPORT && pendingExport != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (out == null) throw new IllegalStateException("파일을 열 수 없습니다."); out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                }
                pendingExport = null; message("백업 파일을 저장했어요.");
            } else if (request == IMPORT) {
                try (InputStream in = getContentResolver().openInputStream(data.getData()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (in == null) throw new IllegalStateException("파일을 열 수 없습니다.");
                    byte[] bytes = new byte[8192]; int count;
                    while ((count = in.read(bytes)) != -1) { if (out.size() + count > 4 * 1024 * 1024) throw new IllegalArgumentException("4MB 이하의 백업 파일만 지원합니다."); out.write(bytes, 0, count); }
                    JSONObject imported = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                    js("window.StudyUI.prepareImport(" + imported + ")");
                }
            }
        } catch (Exception e) { message("파일 처리 실패: " + e.getMessage()); }
    }
}
