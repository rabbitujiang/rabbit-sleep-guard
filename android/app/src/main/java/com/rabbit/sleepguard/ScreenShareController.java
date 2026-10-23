package com.rabbit.sleepguard;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.view.Display;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opt-in, request-driven screenshots, independent of the sleep guard's network queue. */
public final class ScreenShareController {
    private final AccessibilityService service;
    private final GuardPreferences preferences;
    private final SharedPreferences settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private volatile boolean closed = false;
    private boolean busy = false;
    private volatile long generation = 0;
    private final Runnable tick = this::poll;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = this::preferenceChanged;

    private void preferenceChanged(SharedPreferences prefs, String key) {
        if ("screen_share_enabled".equals(key)) main.post(() -> {
            generation++;
            GuardNotification.screenSharing(service, preferences.screenSharing());
            main.removeCallbacks(tick);
            main.post(tick);
        });
    }

    public ScreenShareController(AccessibilityService service, GuardPreferences preferences) {
        this.service = service;
        this.preferences = preferences;
        settings = service.getSharedPreferences("rabbit_sleep_guard", Context.MODE_PRIVATE);
        settings.registerOnSharedPreferenceChangeListener(listener);
        GuardNotification.screenSharing(service, preferences.screenSharing());
        main.post(tick);
    }

    private boolean available() {
        PowerManager power = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE);
        return !closed && Build.VERSION.SDK_INT >= 30 && preferences.screenSharing()
                && power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }

    private void schedule() {
        if (!closed) {
            main.removeCallbacks(tick);
            main.postDelayed(tick, 3000);
        }
    }

    public void wake() {
        main.removeCallbacks(tick);
        main.post(tick);
    }

    private void poll() {
        if (closed) return;
        if (busy || !preferences.configured()) { schedule(); return; }
        // When disabled or locked, notify once on state change; do not repeatedly poll the network.
        boolean enabled = preferences.screenSharing() && Build.VERSION.SDK_INT >= 30;
        boolean ready = available();
        if (!ready && lastUnavailableReported && lastEnabled == enabled) { schedule(); return; }
        lastEnabled = enabled;
        busy = true;
        final long current = generation;
        network.execute(() -> {
            JSONObject result = exchange("/api/device/screen/poll", object("enabled", enabled, "available", ready));
            main.post(() -> {
                busy = false;
                if (closed) return;
                lastUnavailableReported = !ready && result.optBoolean("ok");
                JSONObject request = result.optJSONObject("request");
                if (request != null && current == generation && available()) capture(request, current);
                else schedule();
            });
        });
    }
    private boolean lastUnavailableReported = false;
    private boolean lastEnabled = false;

    private void capture(JSONObject request, long current) {
        if (Build.VERSION.SDK_INT < 30) { schedule(); return; }
        busy = true;
        String id = request.optString("id");
        if (System.currentTimeMillis() >= request.optLong("expires_at")) { busy = false; schedule(); return; }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap hardware = null;
                    Bitmap bitmap = null;
                    try {
                        if (!available() || current != generation) return;
                        hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware == null) throw new IllegalStateException("empty_bitmap");
                        bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        final Bitmap source = bitmap;
                        bitmap = null;
                        String viewer = request.optString("viewer");
                        String name = "codex".equals(viewer) ? "季恒远" : "claude".equals(viewer) ? "Claude" : "agy".equals(viewer) ? "阿行" : "伴侣";
                        Toast.makeText(service, name + "正在看这一张屏幕", Toast.LENGTH_LONG).show();
                        network.execute(() -> {
                            Bitmap scaled = null;
                            try {
                                int longest = Math.max(source.getWidth(), source.getHeight());
                                float ratio = Math.min(1f, 1600f / longest);
                                scaled = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth()*ratio)), Math.max(1, Math.round(source.getHeight()*ratio)), true);
                                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                                scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes);
                                if (available() && current == generation && System.currentTimeMillis() < request.optLong("expires_at")) {
                                    exchange("/api/device/screen/result", object("id", id, "data", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)));
                                }
                            } finally {
                                if (scaled != null && scaled != source) scaled.recycle();
                                source.recycle();
                                main.post(() -> { busy = false; schedule(); });
                            }
                        });
                    } catch (Exception error) {
                        failure(id, "screenshot_encode_failed");
                    } finally {
                        if (hardware != null) hardware.recycle();
                        if (bitmap != null) bitmap.recycle();
                        buffer.close();
                        if (current != generation || !available()) { busy = false; schedule(); }
                    }
                }
                @Override public void onFailure(int code) { failure(id, "android_screenshot_error_" + code); }
            });
        } catch (Exception error) { failure(id, "screenshot_permission_unavailable"); }
    }

    private void failure(String id, String error) {
        if (closed) return;
        network.execute(() -> {
            exchange("/api/device/screen/result", object("id", id, "error", error));
            main.post(() -> { busy = false; schedule(); });
        });
    }

    private JSONObject exchange(String path, JSONObject body) {
        HttpURLConnection connection = null;
        try {
            if (!preferences.configured()) return new JSONObject();
            connection = (HttpURLConnection) new URL(preferences.serverUrl() + path).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(2500); connection.setReadTimeout(5000);
            connection.setRequestProperty("Authorization", "Bearer " + preferences.deviceToken());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            if (connection.getResponseCode() != 200) return new JSONObject();
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] part = new byte[4096]; int count;
                while ((count = in.read(part)) != -1) { out.write(part, 0, count); if (out.size() > 65536) return new JSONObject(); }
                return new JSONObject(out.toString("UTF-8"));
            }
        } catch (Exception ignored) { return new JSONObject(); }
        finally { if (connection != null) connection.disconnect(); }
    }

    private static JSONObject object(Object... values) {
        JSONObject json = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) json.put((String) values[i], values[i+1]); }
        catch (Exception ignored) { }
        return json;
    }

    public void close() {
        closed = true; generation++;
        main.removeCallbacks(tick);
        settings.unregisterOnSharedPreferenceChangeListener(listener);
        GuardNotification.screenSharing(service, false);
        network.shutdown();
    }
}
