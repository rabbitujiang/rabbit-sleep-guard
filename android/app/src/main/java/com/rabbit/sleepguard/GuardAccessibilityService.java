package com.rabbit.sleepguard;

import android.accessibilityservice.AccessibilityService;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Set;

public final class GuardAccessibilityService extends AccessibilityService {
    private static final long HEARTBEAT_INTERVAL_MS = 30 * 60_000L;
    private static final long STATUS_CHANGE_DEBOUNCE_MS = 1_000L;
    private static final long EVENT_DEBOUNCE_MS = 2_500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private GuardPreferences preferences;
    private GuardApiClient api;
    private ScreenShareController screenShare;
    private WindowManager windowManager;
    private View overlay;
    private String lastPackage = "";
    private long lastHandledAt = 0L;
    private boolean screenLockRequestedByUser = false;
    private boolean statusReceiverRegistered = false;
    private boolean networkCallbackRegistered = false;
    private boolean reportInFlight = false;
    private boolean reportPending = false;
    private DeviceStatusReader.Snapshot lastReportedStatus;
    private ConnectivityManager connectivityManager;

    private final Runnable lockAfterReturningHome = this::lockScreenIfRequested;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                main.post(GuardAccessibilityService.this::restoreGuardPage);
            }
            if (screenShare != null) screenShare.wake();
            scheduleStatusChangeReport();
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            scheduleStatusChangeReport();
        }

        @Override
        public void onLost(Network network) {
            scheduleStatusChangeReport();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            scheduleStatusChangeReport();
        }
    };

    private final Runnable statusChangeReport = () -> reportAndRefreshGuard(false);

    private final Runnable usageHeartbeat = new Runnable() {
        @Override public void run() {
            UsageSync.schedule(GuardAccessibilityService.this, false);
            main.postDelayed(this, 5 * 60_000L);
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (api != null && preferences.configured()) {
                reportAndRefreshGuard(true);
            }
            main.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private void scheduleStatusChangeReport() {
        main.removeCallbacks(statusChangeReport);
        main.postDelayed(statusChangeReport, STATUS_CHANGE_DEBOUNCE_MS);
    }

    private void reportAndRefreshGuard(boolean force) {
        if (api == null || preferences == null || !preferences.configured()) return;
        UsageSync.schedule(this, false);
        DeviceStatusReader.Snapshot snapshot = DeviceStatusReader.read(this);
        if (!force && snapshot.hasSameStatus(lastReportedStatus)) return;
        if (reportInFlight) {
            reportPending = true;
            return;
        }
        reportInFlight = true;
        api.reportDeviceStatus(snapshot, result -> main.post(() -> {
            reportInFlight = false;
            if (result.requestOk) lastReportedStatus = snapshot;
            if (result.requestOk && result.active) {
                GuardNotification.showActive(GuardAccessibilityService.this, result.attempts);
                restoreGuardPage();
            } else if (result.requestOk) {
                GuardNotification.hideActive(GuardAccessibilityService.this);
                dismissGuardPage();
            }
            if (reportPending) {
                reportPending = false;
                reportAndRefreshGuard(false);
            }
        }));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        preferences = new GuardPreferences(this);
        api = new GuardApiClient(preferences);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        GuardNotification.createChannels(this);
        if (screenShare != null) screenShare.close();
        screenShare = new ScreenShareController(this, preferences);
        registerStatusReceiver();
        registerNetworkCallback();
        restoreGuardPage();
        main.removeCallbacks(heartbeat);
        main.post(heartbeat);
        main.removeCallbacks(usageHeartbeat);
        main.post(usageHeartbeat);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null || preferences == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        String packageName = event.getPackageName().toString();
        if (packageName.equals(getPackageName()) || packageName.startsWith("com.android.systemui")) return;
        Set<String> blocked = preferences.blockedPackages();
        if (!blocked.contains(packageName)) return;
        if (overlay != null) return;

        long now = System.currentTimeMillis();
        if (packageName.equals(lastPackage) && now - lastHandledAt < EVENT_DEBOUNCE_MS) return;
        lastPackage = packageName;
        lastHandledAt = now;

        String appName = appLabel(packageName);
        if (preferences.cachedActive()) showPendingOverlay(appName);
        api.blocked(appName, packageName, result -> main.post(() -> {
            if (result.requestOk && result.active && !result.ignored) {
                showGuardOverlay(appName, result.attempts, result.stage, result.unlocksRevoked);
                GuardNotification.showActive(this, result.attempts);
                GuardNotification.caught(this, appName, result.attempts);
            } else if (result.requestOk) {
                dismissGuardPage();
            } else if (!result.requestOk && preferences.cachedActive()) {
                int attempts = Math.max(1, preferences.attempts() + 1);
                showGuardOverlay(appName, attempts, stageFor(attempts), preferences.unlocksRevoked());
                GuardNotification.showActive(this, attempts);
            }
        }));
    }

    private String appLabel(String packageName) {
        try {
            PackageManager manager = getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
            return manager.getApplicationLabel(info).toString();
        } catch (Exception ignored) {
            return packageName;
        }
    }

    private void goBackToSleep() {
        main.removeCallbacks(lockAfterReturningHome);
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, GuardDeviceAdminReceiver.class);
        boolean canLock = preferences.lockScreen() && policy != null && policy.isAdminActive(admin);
        if (canLock) {
            // Keep the accessibility overlay attached while the display is off. It will still be
            // the first interactive page when the user presses the power button again.
            screenLockRequestedByUser = true;
            performGlobalAction(GLOBAL_ACTION_HOME);
            main.postDelayed(lockAfterReturningHome, 450L);
        } else {
            screenLockRequestedByUser = false;
            dismissGuardPage();
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private void requestTemporaryUnlock(String appName, Button button) {
        button.setEnabled(false);
        button.setText("正在留下申请记录…");
        api.requestTemporaryUnlock(appName, result -> main.post(() -> {
            if (!result.requestOk) {
                button.setEnabled(true);
                button.setText("申请暂未同步，点此重试");
                return;
            }
            if (!returnHomeAndDismissGuard()) {
                showGuardOverlay(appName, result.attempts, result.stage, result.unlocksRevoked);
            }
        }));
    }

    private boolean returnHomeAndDismissGuard() {
        cancelPendingScreenLock();
        boolean homeRequested = performGlobalAction(GLOBAL_ACTION_HOME);
        if (!homeRequested) return false;
        dismissGuardPage();
        // The same app may be reopened from Recents immediately. Its next window event must not
        // be mistaken for the event that originally displayed this guard page.
        lastPackage = "";
        lastHandledAt = 0L;
        return true;
    }

    private void showPendingOverlay(String appName) {
        cancelPendingScreenLock();
        removeOverlay();
        LinearLayout card = baseCard();
        TextView title = title("正在确认睡眠守卫…");
        card.addView(title);
        TextView body = body("先别打开“" + appName + "”，比比正在核对今晚的守卫状态。");
        card.addView(body);
        addOverlay(card);
    }

    private void showGuardOverlay(String appName, int attempts, String stage, boolean unlocksRevoked) {
        cancelPendingScreenLock();
        removeOverlay();
        preferences.showGuardPage(appName);
        LinearLayout card = baseCard();
        card.addView(title("被比比抓到了"));

        TextView stageLabel = body(stageLabel(stage));
        stageLabel.setTextSize(14f);
        stageLabel.setTextColor(stage.equals("refused_sleep") ? Color.rgb(255, 157, 166) : Color.rgb(171, 154, 244));
        stageLabel.setTypeface(Typeface.DEFAULT_BOLD);
        stageLabel.setPadding(0, dp(16), 0, 0);
        card.addView(stageLabel);

        TextView body = body(messageFor(appName, attempts, stage, unlocksRevoked));
        card.addView(body);

        Button sleep = actionButton("回去睡觉", Color.rgb(125, 79, 232), Color.WHITE);
        sleep.setOnClickListener(view -> goBackToSleep());
        LinearLayout.LayoutParams primaryParams = matchWrap();
        primaryParams.topMargin = dp(26);
        card.addView(sleep, primaryParams);

        if (!unlocksRevoked) {
            Button unlock = actionButton("申请临时解锁", Color.TRANSPARENT, Color.rgb(211, 203, 241));
            unlock.setOnClickListener(view -> requestTemporaryUnlock(appName, unlock));
            LinearLayout.LayoutParams secondaryParams = matchWrap();
            secondaryParams.topMargin = dp(10);
            card.addView(unlock, secondaryParams);
        }

        TextView note = body(unlocksRevoked
                ? "今晚的临时解锁资格已经取消，守卫会持续到设定的结束时间。"
                : "申请记录成功后会回到桌面，不会清掉今晚的偷开次数。");
        note.setText(note.getText() + "\n当前版本 v" + BuildConfig.VERSION_NAME);
        note.setTextSize(13f);
        note.setTextColor(Color.rgb(139, 148, 177));
        note.setPadding(0, dp(16), 0, 0);
        card.addView(note);

        addOverlay(card);
    }

    private LinearLayout baseCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(30), dp(48), dp(30), dp(42));
        card.setBackgroundColor(Color.rgb(14, 19, 32));
        return card;
    }

    private TextView title(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(28f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView body(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.rgb(202, 210, 235));
        view.setTextSize(18f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(18), 0, 0);
        return view;
    }

    private Button actionButton(String value, int background, int foreground) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(17f);
        button.setTextColor(foreground);
        button.setMinHeight(dp(54));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), background == Color.TRANSPARENT ? Color.rgb(85, 91, 118) : background);
        button.setBackground(drawable);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
        );
    }

    private void addOverlay(View card) {

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(card, params);
            overlay = card;
        } catch (Exception ignored) {
            overlay = null;
        }
    }

    private String stageLabel(String stage) {
        if ("first_warning".equals(stage)) return "FIRST WARNING · 第一次提醒";
        if ("locked".equals(stage)) return "LOCKED · 第二次警告";
        if ("refused_sleep".equals(stage)) return "REFUSED SLEEP · 第三次及以后";
        return "SLEEP GUARD · 守卫中";
    }

    private String messageFor(String appName, int attempts, String stage, boolean unlocksRevoked) {
        if (unlocksRevoked) {
            return "第三次了。今晚的临时解锁资格已经取消。\n“" + appName + "”先放下，乖乖回去睡觉。";
        }
        if ("refused_sleep".equals(stage)) {
            return "第三次及以后了，还在偷偷回来。\n“" + appName + "”先放下；临时解锁申请一共只有三次机会。";
        }
        if ("locked".equals(stage) || attempts == 2) {
            return "第二次了，还敢回来？\n“" + appName + "”先放下，警告不许再偷偷清零。";
        }
        return "第一次。晚安说过了，就不许偷偷跑回来。\n“" + appName + "”先放下。";
    }

    private String stageFor(int attempts) {
        if (attempts <= 1) return "first_warning";
        if (attempts == 2) return "locked";
        return "refused_sleep";
    }

    private void cancelPendingScreenLock() {
        main.removeCallbacks(lockAfterReturningHome);
        screenLockRequestedByUser = false;
    }

    private void lockScreenIfRequested() {
        if (!screenLockRequestedByUser) return;
        screenLockRequestedByUser = false;
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, GuardDeviceAdminReceiver.class);
        if (policy != null && policy.isAdminActive(admin)) {
            try {
                policy.lockNow();
            } catch (SecurityException ignored) {
                dismissGuardPage();
            }
        } else {
            dismissGuardPage();
        }
    }

    private void restoreGuardPage() {
        if (preferences == null || overlay != null) return;
        if (!preferences.cachedActive() || !preferences.guardPageVisible()) return;
        int attempts = Math.max(1, preferences.attempts());
        showGuardOverlay(
                preferences.guardedAppName(),
                attempts,
                stageFor(attempts),
                preferences.unlocksRevoked()
        );
    }

    private void dismissGuardPage() {
        if (preferences != null) preferences.dismissGuardPage();
        removeOverlay();
    }

    private void registerStatusReceiver() {
        if (statusReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        statusReceiverRegistered = true;
    }

    private void registerNetworkCallback() {
        if (networkCallbackRegistered) return;
        connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) return;
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        } catch (RuntimeException ignored) {
        }
    }

    private void removeOverlay() {
        if (overlay == null || windowManager == null) return;
        try {
            windowManager.removeView(overlay);
        } catch (Exception ignored) {
        }
        overlay = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (screenShare != null) screenShare.close();
        main.removeCallbacksAndMessages(null);
        screenLockRequestedByUser = false;
        if (statusReceiverRegistered) {
            try {
                unregisterReceiver(statusReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            statusReceiverRegistered = false;
        }
        if (networkCallbackRegistered && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
            networkCallbackRegistered = false;
        }
        removeOverlay();
        super.onDestroy();
    }
}
