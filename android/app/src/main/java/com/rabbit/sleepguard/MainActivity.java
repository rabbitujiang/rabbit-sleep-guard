package com.rabbit.sleepguard;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(244, 247, 255);
    private static final int MUTED = Color.rgb(174, 187, 220);
    private static final int SUBTLE = Color.rgb(126, 141, 179);
    private static final int ACCENT = Color.rgb(159, 142, 255);
    private static final int MINT = Color.rgb(128, 236, 213);

    private GuardPreferences preferences;
    private GuardApiClient api;
    private EditText serverUrl;
    private EditText deviceToken;
    private Switch lockScreen;
    private LinearLayout appList;
    private TextView connectionState;
    private TextView lastUpdated;
    private TextView chargingValue;
    private TextView temperatureValue;
    private TextView networkValue;
    private TextView screenValue;
    private TextView guardStatus;
    private TextView guardDetail;
    private BatteryMoonView batteryMoon;
    private final List<AppChoice> appChoices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = new GuardPreferences(this);
        api = new GuardApiClient(preferences);
        GuardNotification.createChannels(this);
        requestNotificationPermission();
        styleSystemBars();
        setContentView(buildContent());
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (guardStatus != null) refreshStatus();
    }

    private void styleSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(7, 10, 24));
        window.setNavigationBarColor(Color.rgb(7, 10, 24));
        window.getDecorView().setSystemUiVisibility(0);
    }

    private View buildContent() {
        FrameLayout frame = new FrameLayout(this);
        frame.addView(new MoonlightBackgroundView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(48));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView eyebrow = label("RABBIT DEVICE LINK");
        root.addView(eyebrow);
        TextView title = text("月光守卫", 33, INK);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLetterSpacing(-.025f);
        root.addView(title, topMargin(4));
        TextView intro = text("手机状态与睡眠守卫，都安静地待在这里。", 15, MUTED);
        root.addView(intro, topMargin(6));

        LinearLayout hero = card(true);
        LinearLayout topLine = new LinearLayout(this);
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        topLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView deviceLabel = label("THIS PHONE");
        topLine.addView(deviceLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        connectionState = chip("等待连接", false);
        topLine.addView(connectionState);
        hero.addView(topLine, matchWrap());

        batteryMoon = new BatteryMoonView(this);
        LinearLayout.LayoutParams moonParams = new LinearLayout.LayoutParams(dp(176), dp(176));
        moonParams.gravity = Gravity.CENTER_HORIZONTAL;
        moonParams.topMargin = dp(8);
        hero.addView(batteryMoon, moonParams);
        lastUpdated = text("还没有上报", 13, SUBTLE);
        lastUpdated.setGravity(Gravity.CENTER);
        hero.addView(lastUpdated, topMargin(0));

        GridLayout metrics = new GridLayout(this);
        metrics.setColumnCount(2);
        metrics.setRowCount(2);
        chargingValue = metric(metrics, "充电", "读取中");
        temperatureValue = metric(metrics, "电池温度", "读取中");
        networkValue = metric(metrics, "网络", "读取中");
        screenValue = metric(metrics, "屏幕", "读取中");
        hero.addView(metrics, topMargin(18));
        root.addView(hero, topMargin(24));

        LinearLayout guardCard = card(false);
        LinearLayout guardHeader = new LinearLayout(this);
        guardHeader.setOrientation(LinearLayout.HORIZONTAL);
        guardHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView guardTitle = text("睡眠守卫", 20, INK);
        guardTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        guardHeader.addView(guardTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        guardStatus = chip("读取中", false);
        guardHeader.addView(guardStatus);
        guardCard.addView(guardHeader);
        guardDetail = text("正在同步守卫状态…", 14, MUTED);
        guardCard.addView(guardDetail, topMargin(12));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("今晚开始", true);
        Button stop = button("结束守卫", false);
        start.setOnClickListener(view -> runAction(true));
        stop.setOnClickListener(view -> runAction(false));
        actions.addView(start, weighted());
        actions.addView(stop, weightedWithStartMargin());
        guardCard.addView(actions, topMargin(18));
        root.addView(guardCard, topMargin(14));

        addSectionTitle(root, "连接");
        LinearLayout connectionCard = card(false);
        serverUrl = input("https://sleep.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverUrl.setText(preferences.serverUrl());
        connectionCard.addView(serverUrl, matchWrap());
        deviceToken = input("设备令牌（ANDROID_DEVICE_TOKEN）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        deviceToken.setText(preferences.deviceToken());
        connectionCard.addView(deviceToken, topMargin(10));
        Button save = button("保存并测试连接", true);
        save.setOnClickListener(view -> saveAndTest());
        connectionCard.addView(save, topMargin(12));
        root.addView(connectionCard, topMargin(10));

        addSectionTitle(root, "执行权限");
        LinearLayout permissions = card(false);
        TextView permissionHint = text("无障碍服务负责识别受限应用，并在状态变化时及时上报。", 14, MUTED);
        permissions.addView(permissionHint);
        Button accessibility = button("打开无障碍设置", true);
        accessibility.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permissions.addView(accessibility, topMargin(14));

        lockScreen = new Switch(this);
        lockScreen.setText("回去睡觉后熄屏");
        lockScreen.setTextColor(INK);
        lockScreen.setTextSize(15f);
        lockScreen.setChecked(preferences.lockScreen());
        lockScreen.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{MINT, Color.rgb(117, 128, 158)}));
        lockScreen.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.argb(110, 128, 236, 213), Color.argb(75, 117, 128, 158)}));
        lockScreen.setOnCheckedChangeListener((button, checked) -> preferences.setLockScreen(checked));
        permissions.addView(lockScreen, topMargin(10));

        Button admin = button("允许设备管理锁屏", false);
        admin.setOnClickListener(view -> requestDeviceAdmin());
        permissions.addView(admin, topMargin(8));
        Button background = button("打开应用后台设置", false);
        background.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        permissions.addView(background, topMargin(8));
        root.addView(permissions, topMargin(10));

        addSectionTitle(root, "陪伴 · 看屏幕");
        LinearLayout shareCard = card(false);
        Switch sharing = new Switch(this);
        sharing.setText("让我们看屏幕");
        sharing.setTextColor(INK);
        sharing.setTextSize(15f);
        sharing.setEnabled(Build.VERSION.SDK_INT >= 30);
        sharing.setChecked(preferences.screenSharing());
        sharing.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                sharing.setChecked(false);
                new android.app.AlertDialog.Builder(this)
                    .setTitle("开启屏幕共享")
                    .setMessage("开启后，连接这台服务器的伴侣可以在手机亮屏解锁时按需获取一张屏幕，画面可能包含正在显示的消息。每次截图会提示是谁在看，不连续录制；随时关闭这个开关即可停止。")
                    .setPositiveButton("开启共享", (dialog, which) -> {
                        preferences.setScreenSharing(true);
                        sharing.setOnCheckedChangeListener(null);
                        sharing.setChecked(true);
                        sharing.setOnCheckedChangeListener((v, on) -> {
                            preferences.setScreenSharing(on);
                            GuardNotification.screenSharing(this, on);
                        });
                        GuardNotification.screenSharing(this, true);
                    }).setNegativeButton("暂不开启", null).show();
            } else {
                preferences.setScreenSharing(false);
                GuardNotification.screenSharing(this, false);
            }
        });
        shareCard.addView(sharing);
        shareCard.addView(text(Build.VERSION.SDK_INT >= 30
            ? "开启后按需分享一张屏幕，不录屏。锁屏、息屏时不截图；截图后会提示是谁在看。需要启用无障碍服务。"
            : "此功能需要 Android 11 或以上，睡眠守卫仍可正常使用。", 13, MUTED), topMargin(10));
        root.addView(shareCard, topMargin(10));

        addSectionTitle(root, "受限应用");
        LinearLayout appsCard = card(false);
        TextView hint = text("睡眠守卫只识别勾选的应用；屏幕共享由上方独立开关控制。", 14, MUTED);
        appsCard.addView(hint);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appsCard.addView(appList, topMargin(10));
        populateApps();
        Button saveApps = button("保存应用名单", true);
        saveApps.setOnClickListener(view -> saveApps());
        appsCard.addView(saveApps, topMargin(12));
        root.addView(appsCard, topMargin(10));

        TextView footer = text("状态变化时上报 · 30 分钟轻量心跳 · 离线时沿用最近一次有效守卫状态\n兔酱睡眠守卫 v" + BuildConfig.VERSION_NAME, 12, SUBTLE);
        footer.setGravity(Gravity.CENTER);
        footer.setLineSpacing(dp(3), 1f);
        root.addView(footer, topMargin(24));

        renderLocal(DeviceStatusReader.read(this));
        renderCachedGuard();
        return frame;
    }

    private TextView metric(GridLayout grid, String name, String initial) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(12), dp(13), dp(12));
        box.setBackground(glassDrawable(Color.argb(28, 228, 235, 255), Color.argb(28, 230, 236, 255), dp(15)));
        TextView label = text(name, 12, SUBTLE);
        label.setLetterSpacing(.04f);
        TextView value = text(initial, 16, INK);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(label);
        box.addView(value, topMargin(5));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        grid.addView(box, params);
        return value;
    }

    private void populateApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcher, 0);
        resolved.sort(Comparator.comparing(info -> info.loadLabel(getPackageManager()).toString(), String.CASE_INSENSITIVE_ORDER));
        Set<String> selected = preferences.blockedPackages();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : resolved) {
            String packageName = info.activityInfo.packageName;
            if (packageName.equals(getPackageName()) || !seen.add(packageName)) continue;
            CheckBox check = new CheckBox(this);
            check.setText(info.loadLabel(getPackageManager()) + "\n" + packageName);
            check.setTextColor(INK);
            check.setTextSize(14f);
            check.setButtonTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{ACCENT, Color.rgb(112, 124, 157)}));
            check.setChecked(selected.contains(packageName));
            check.setPadding(0, dp(5), 0, dp(5));
            appList.addView(check, matchWrap());
            appChoices.add(new AppChoice(packageName, check));
        }
    }

    private void saveApps() {
        // Keep saved packages that are temporarily absent from PackageManager's launcher
        // query instead of silently deleting them when the visible list is saved.
        Set<String> selected = new HashSet<>(preferences.blockedPackages());
        for (AppChoice choice : appChoices) {
            if (choice.checkBox.isChecked()) selected.add(choice.packageName);
            else selected.remove(choice.packageName);
        }
        preferences.saveBlockedPackages(selected);
        toast("已保存 " + selected.size() + " 个受限应用");
    }

    private void saveAndTest() {
        String url = serverUrl.getText().toString().trim().replaceAll("/+$", "");
        String token = deviceToken.getText().toString().trim();
        if (!url.startsWith("https://")) {
            toast("服务器地址必须是 HTTPS");
            return;
        }
        if (token.length() < 24) {
            toast("设备令牌太短，请复制完整值");
            return;
        }
        preferences.saveConnection(url, token);
        connectionState.setText("连接中");
        api.reportDeviceStatus(DeviceStatusReader.read(this), result -> runOnUiThread(() -> {
            if (result.requestOk) {
                renderRemote(result);
                toast("连接成功，状态已上报");
            } else {
                connectionState.setText("连接失败");
                connectionState.setBackground(chipDrawable(false));
                lastUpdated.setText("连接失败 · " + result.error);
            }
        }));
    }

    private void refreshStatus() {
        DeviceStatusReader.Snapshot snapshot = DeviceStatusReader.read(this);
        renderLocal(snapshot);
        renderCachedGuard();
        if (!preferences.configured()) {
            connectionState.setText("等待配置");
            lastUpdated.setText("保存服务器连接后开始上报");
            return;
        }
        connectionState.setText("同步中");
        api.reportDeviceStatus(snapshot, result -> runOnUiThread(() -> {
            if (result.requestOk) renderRemote(result);
            else {
                connectionState.setText("暂时离线");
                connectionState.setBackground(chipDrawable(false));
                renderCachedGuard();
                updateLastReportedLabel();
            }
        }));
    }

    private void renderLocal(DeviceStatusReader.Snapshot value) {
        batteryMoon.setBattery(value.batteryLevel, value.charging);
        chargingValue.setText(value.charging ? "正在充电" : "未在充电");
        temperatureValue.setText(value.batteryTemperatureC == null
                ? "未知" : String.format(Locale.CHINA, "%.1f°C", value.batteryTemperatureC));
        networkValue.setText(value.networkConnected ? networkLabel(value.networkType) : "未连接");
        screenValue.setText(value.screenOn ? "亮着" : "已熄灭");
    }

    private void renderCachedGuard() {
        boolean active = preferences.cachedActive();
        guardStatus.setText(active ? "守卫中" : "未开启");
        guardStatus.setBackground(chipDrawable(active));
        String service = accessibilityEnabled() ? "无障碍已开启" : "无障碍未开启";
        guardDetail.setText(active
                ? "今晚已拦截 " + preferences.attempts() + " 次 · " + service
                : service + " · 可用下方按钮手动开启");
    }

    private void renderRemote(GuardApiClient.Result result) {
        connectionState.setText(result.deviceStatus != null && result.deviceStatus.online ? "在线" : "已连接");
        connectionState.setBackground(chipDrawable(true));
        if (result.deviceStatus != null && !result.deviceStatus.lastUpdatedAt.isEmpty()) {
            lastUpdated.setText("刚刚上报 · " + formatMoment(result.deviceStatus.lastUpdatedAt));
        } else {
            updateLastReportedLabel();
        }
        guardStatus.setText(result.active ? "守卫中" : "未开启");
        guardStatus.setBackground(chipDrawable(result.active));
        String service = accessibilityEnabled() ? "无障碍已开启" : "无障碍未开启";
        if (result.active) {
            String end = formatEnd(result.endsAt);
            String unlock = result.unlocksRevoked ? "临时解锁已取消" : "临时解锁 " + result.unlockRequestCount + "/3";
            guardDetail.setText("已拦截 " + result.attempts + " 次 · " + unlock
                    + (end.isEmpty() ? "" : " · " + end + "结束") + "\n" + service);
        } else {
            guardDetail.setText(service + " · 可用下方按钮手动开启");
        }
    }

    private void updateLastReportedLabel() {
        long value = preferences.lastDeviceReport();
        if (value == 0) lastUpdated.setText("还没有成功上报");
        else lastUpdated.setText("最后上报 · " + relativeTime(value));
    }

    private void runAction(boolean start) {
        GuardApiClient.Callback callback = result -> runOnUiThread(() -> {
            if (result.requestOk) {
                renderRemote(result);
                toast(start ? "今晚的守卫已开启" : "守卫已结束");
            } else {
                toast("操作失败：" + result.error);
            }
        });
        if (start) api.start(callback); else api.stop(callback);
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName component = new ComponentName(this, GuardAccessibilityService.class);
        return enabled.toLowerCase(Locale.ROOT).contains(component.flattenToString().toLowerCase(Locale.ROOT));
    }

    private void requestDeviceAdmin() {
        ComponentName component = new ComponentName(this, GuardDeviceAdminReceiver.class);
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (policy != null && policy.isAdminActive(component)) {
            toast("设备管理锁屏权限已经开启");
            return;
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "仅在睡眠守卫拦截受限应用时锁定屏幕。");
        startActivity(intent);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2103);
        }
    }

    private String formatEnd(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String formatMoment(String value) {
        try {
            return DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value));
        } catch (Exception ignored) {
            return "刚刚";
        }
    }

    private String relativeTime(long millis) {
        long minutes = Math.max(0, Duration.ofMillis(System.currentTimeMillis() - millis).toMinutes());
        if (minutes == 0) return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        return (minutes / 60) + " 小时前";
    }

    private String networkLabel(String type) {
        if ("wifi".equals(type)) return "Wi-Fi";
        if ("cellular".equals(type)) return "移动网络";
        if ("ethernet".equals(type)) return "有线网络";
        if ("vpn".equals(type)) return "VPN";
        return "已连接";
    }

    private void addSectionTitle(LinearLayout root, String value) {
        TextView heading = text(value, 13, Color.rgb(191, 202, 234));
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setLetterSpacing(.1f);
        root.addView(heading, topMargin(24));
    }

    private LinearLayout card(boolean hero) {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setPadding(dp(hero ? 18 : 17), dp(hero ? 18 : 17), dp(hero ? 18 : 17), dp(hero ? 20 : 17));
        value.setBackground(glassDrawable(
                hero ? Color.argb(96, 41, 49, 87) : Color.argb(76, 35, 42, 70),
                hero ? Color.argb(84, 202, 215, 255) : Color.argb(52, 211, 220, 255),
                dp(hero ? 26 : 21)));
        value.setElevation(dp(hero ? 10 : 5));
        return value;
    }

    private TextView label(String value) {
        TextView label = text(value, 11, Color.rgb(180, 194, 232));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setLetterSpacing(.16f);
        return label;
    }

    private TextView chip(String value, boolean positive) {
        TextView chip = text(value, 12, positive ? MINT : Color.rgb(205, 211, 234));
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(11), dp(6), dp(11), dp(6));
        chip.setBackground(chipDrawable(positive));
        return chip;
    }

    private GradientDrawable chipDrawable(boolean positive) {
        return glassDrawable(
                positive ? Color.argb(42, 128, 236, 213) : Color.argb(36, 202, 211, 239),
                positive ? Color.argb(100, 128, 236, 213) : Color.argb(55, 210, 220, 248),
                dp(30));
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }

    private EditText input(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(Color.rgb(118, 132, 169));
        field.setTextColor(INK);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setTextSize(14f);
        field.setPadding(dp(14), dp(13), dp(14), dp(13));
        field.setBackground(glassDrawable(Color.argb(35, 224, 231, 255), Color.argb(42, 220, 228, 255), dp(13)));
        return field;
    }

    private Button button(String label, boolean primary) {
        Button value = new Button(this);
        value.setText(label);
        value.setAllCaps(false);
        value.setTextSize(14f);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setTextColor(primary ? Color.rgb(16, 18, 35) : INK);
        value.setMinHeight(dp(50));
        GradientDrawable shape = glassDrawable(
                primary ? Color.rgb(178, 165, 255) : Color.argb(24, 225, 232, 255),
                primary ? Color.rgb(200, 192, 255) : Color.argb(54, 224, 231, 255),
                dp(14));
        value.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(45, 255, 255, 255)), shape, null));
        value.setStateListAnimator(null);
        return value;
    }

    private GradientDrawable glassDrawable(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int margin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(margin);
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightedWithStartMargin() {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = dp(9);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final class AppChoice {
        final String packageName;
        final CheckBox checkBox;

        AppChoice(String packageName, CheckBox checkBox) {
            this.packageName = packageName;
            this.checkBox = checkBox;
        }
    }
}
