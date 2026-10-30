package com.rabbit.sleepguard;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Summary-only sync, separate from guard state parsing and screen-sharing. */
public final class UsageSync {
    public interface Callback { void done(String message); }
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy, pending;
    private static long lastAttempt = -300000L;

    public static boolean hasAccess(Context context) {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }
    public static void schedule(Context context, boolean force) { schedule(context, force, null); }
    public static synchronized void schedule(Context context, boolean force, Callback callback) {
        Context app = context.getApplicationContext();
        GuardPreferences prefs = new GuardPreferences(app);
        if (!prefs.configured()) { complete(callback, "先保存服务器连接，才能同步给伴侣。"); return; }
        if (busy) { if (force) pending = true; complete(callback, "正在同步，稍后可再刷新状态。"); return; }
        long elapsed = SystemClock.elapsedRealtime();
        if (!force && elapsed-lastAttempt < 300000L) return;
        lastAttempt = elapsed;
        busy = true;
        WORK.execute(() -> {
            String message;
            try {
                JSONObject report;
                try { report = collect(app, prefs); }
                catch (Exception error) { report = state("read_failed"); }
                // Respect a permission/switch change that happened during the scan.
                if (!prefs.usageSharing()) report = state("disabled");
                else if (!hasAccess(app)) report = state("permission_required");
                if (!upload(prefs, report)) throw new IllegalStateException("upload failed");
                String status = report.getString("state");
                String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                message = status.equals("ready") ? "已同步诊断 · " + time + "（尚未接通手机屏幕时长）"
                        : status.equals("disabled") ? "已停止同步，服务器上的旧统计已清除。"
                        : status.equals("permission_required") ? "还需要开启使用情况访问权限。"
                        : "系统读取失败，已告知伴侣统计暂不可用。";
                prefs.setUsageSyncMessage(message);
            } catch (Exception error) {
                message = "同步未成功，请检查连接后重试；伴侣只能查到上次快照。";
                prefs.setUsageSyncMessage(message);
            } finally {
                boolean again;
                synchronized (UsageSync.class) { busy = false; again = pending; pending = false; }
                if (again) schedule(app, true);
            }
            complete(callback, message);
        });
    }
    private static void complete(Callback cb, String message) {
        if (cb != null) MAIN.post(() -> cb.done(message));
    }
    private static JSONObject state(String state) throws Exception {
        return new JSONObject().put("state", state).put("captured_at", Instant.now().toString());
    }
    private static JSONObject collect(Context context, GuardPreferences prefs) throws Exception {
        if (!prefs.usageSharing()) return state("disabled");
        if (!hasAccess(context)) return state("permission_required");
        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long midnight = today.atStartOfDay(zone).toInstant().toEpochMilli();
        long[] starts = {midnight, today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()};
        long[] ends = {now, midnight, now};
        Map<String,String> names = new HashMap<>();
        String[] keys = {"today", "yesterday", "week"};
        JSONObject ranges = new JSONObject();
        for (int i=0;i<3;i++) {
            UsageSummary summary = SystemUsage.read(context, starts[i], ends[i], now);
            JSONArray apps = new JSONArray();
            for (Map.Entry<String,Long> item : summary.totals().entrySet()) {
                String pkg = item.getKey();
                String label = names.get(pkg);
                if (label == null) {
                    label = pkg;
                    try { label = context.getPackageManager().getApplicationLabel(
                            context.getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
                    catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
                    if (label.length() > 256) label = label.substring(0,256);
                    names.put(pkg,label);
                }
                apps.put(new JSONObject().put("package_name",pkg).put("name",label).put("duration_ms",item.getValue()));
            }
            ranges.put(keys[i], new JSONObject().put("start",Instant.ofEpochMilli(summary.start).toString())
                    .put("end",Instant.ofEpochMilli(summary.end).toString())
                    .put("requested_start",Instant.ofEpochMilli(starts[i]).toString())
                    .put("requested_end",Instant.ofEpochMilli(ends[i]).toString())
                    .put("first_record_at",summary.firstRecord == Long.MAX_VALUE ? JSONObject.NULL : Instant.ofEpochMilli(summary.firstRecord).toString())
                    .put("apps",apps));
        }
        return new JSONObject().put("state","ready").put("captured_at",Instant.ofEpochMilli(now).toString())
                .put("diagnostics", NativeUsageProbe.inspect(context)).put("source",SystemUsage.SOURCE).put("timezone",zone.getId()).put("ranges",ranges);
    }
    private static boolean upload(GuardPreferences prefs, JSONObject report) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(prefs.serverUrl()+"/api/device/usage").openConnection();
        try {
            c.setRequestMethod("POST"); c.setConnectTimeout(4000); c.setReadTimeout(5000);
            c.setRequestProperty("Authorization","Bearer "+prefs.deviceToken());
            c.setRequestProperty("Content-Type","application/json; charset=utf-8"); c.setDoOutput(true);
            byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            return c.getResponseCode() == 200;
        } finally { c.disconnect(); }
    }
}
