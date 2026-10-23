package com.rabbit.sleepguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class GuardPreferences {
    private static final String FILE = "rabbit_sleep_guard";
    private static final String SERVER_URL = "server_url";
    private static final String DEVICE_TOKEN = "device_token";
    private static final String BLOCKED_PACKAGES = "blocked_packages";
    private static final String LOCK_SCREEN = "lock_screen";
    private static final String ACTIVE = "active";
    private static final String ATTEMPTS = "attempts";
    private static final String UNLOCK_REQUEST_COUNT = "unlock_request_count";
    private static final String UNLOCKS_REVOKED = "unlocks_revoked";
    private static final String ENDS_AT = "ends_at";
    private static final String LAST_SYNC = "last_sync";
    private static final String LAST_DEVICE_REPORT = "last_device_report";
    private static final String GUARD_PAGE_VISIBLE = "guard_page_visible";
    private static final String GUARDED_APP_NAME = "guarded_app_name";

    private final SharedPreferences preferences;

    public GuardPreferences(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String serverUrl() {
        return preferences.getString(SERVER_URL, "").replaceAll("/+$", "");
    }

    public String deviceToken() {
        return preferences.getString(DEVICE_TOKEN, "");
    }

    public void saveConnection(String serverUrl, String deviceToken) {
        preferences.edit()
                .putString(SERVER_URL, serverUrl.trim().replaceAll("/+$", ""))
                .putString(DEVICE_TOKEN, deviceToken.trim())
                .apply();
    }

    public Set<String> blockedPackages() {
        return Collections.unmodifiableSet(new HashSet<>(
                preferences.getStringSet(BLOCKED_PACKAGES, Collections.emptySet())
        ));
    }

    public void saveBlockedPackages(Set<String> packages) {
        preferences.edit().putStringSet(BLOCKED_PACKAGES, new HashSet<>(packages)).apply();
    }

    public boolean lockScreen() {
        return preferences.getBoolean(LOCK_SCREEN, true);
    }

    public void setLockScreen(boolean enabled) {
        preferences.edit().putBoolean(LOCK_SCREEN, enabled).apply();
    }

    public boolean cachedActive() {
        if (!preferences.getBoolean(ACTIVE, false)) return false;
        String end = preferences.getString(ENDS_AT, "");
        if (end == null || end.isEmpty()) return true;
        try {
            return java.time.Instant.parse(end).toEpochMilli() > System.currentTimeMillis();
        } catch (Exception ignored) {
            return true;
        }
    }

    public int attempts() {
        return preferences.getInt(ATTEMPTS, 0);
    }

    public int unlockRequestCount() {
        return preferences.getInt(UNLOCK_REQUEST_COUNT, 0);
    }

    public boolean unlocksRevoked() {
        return preferences.getBoolean(UNLOCKS_REVOKED, false);
    }

    public String endsAt() {
        return preferences.getString(ENDS_AT, "");
    }

    public long lastSync() {
        return preferences.getLong(LAST_SYNC, 0L);
    }

    public long lastDeviceReport() {
        return preferences.getLong(LAST_DEVICE_REPORT, 0L);
    }

    public void markDeviceReported() {
        preferences.edit().putLong(LAST_DEVICE_REPORT, System.currentTimeMillis()).apply();
    }

    public boolean guardPageVisible() {
        return preferences.getBoolean(GUARD_PAGE_VISIBLE, false);
    }

    public String guardedAppName() {
        return preferences.getString(GUARDED_APP_NAME, "受限应用");
    }

    public void showGuardPage(String appName) {
        preferences.edit()
                .putBoolean(GUARD_PAGE_VISIBLE, true)
                .putString(GUARDED_APP_NAME, appName == null || appName.isEmpty() ? "受限应用" : appName)
                .apply();
    }

    public void dismissGuardPage() {
        preferences.edit()
                .putBoolean(GUARD_PAGE_VISIBLE, false)
                .remove(GUARDED_APP_NAME)
                .apply();
    }

    public void updateState(boolean active, int attempts, int unlockRequestCount,
                            boolean unlocksRevoked, String endsAt) {
        preferences.edit()
                .putBoolean(ACTIVE, active)
                .putInt(ATTEMPTS, attempts)
                .putInt(UNLOCK_REQUEST_COUNT, unlockRequestCount)
                .putBoolean(UNLOCKS_REVOKED, unlocksRevoked)
                .putString(ENDS_AT, endsAt == null ? "" : endsAt)
                .putLong(LAST_SYNC, System.currentTimeMillis())
                .apply();
    }

    public boolean screenSharing() {
        return preferences.getBoolean("screen_share_enabled", false);
    }

    public void setScreenSharing(boolean enabled) {
        preferences.edit().putBoolean("screen_share_enabled", enabled).apply();
    }

    public boolean configured() {
        return serverUrl().startsWith("https://") && deviceToken().length() >= 24;
    }
}
