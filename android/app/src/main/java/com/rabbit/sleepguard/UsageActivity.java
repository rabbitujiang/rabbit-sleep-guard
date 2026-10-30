package com.rabbit.sleepguard;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only local usage screen; deliberately has no GuardPreferences/API writes. */
public final class UsageActivity extends Activity {
    private static final int INK = Color.rgb(244, 247, 255);
    private static final int MUTED = Color.rgb(174, 187, 220);
    private static final int MINT = Color.rgb(128, 236, 213);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout results;
    private TextView syncStatus;
    private final List<Button> tabs = new ArrayList<>();
    private int selected = 0;
    private int generation = 0;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) selected = saved.getInt("range", 0);
        getWindow().setStatusBarColor(Color.rgb(7, 10, 24));
        getWindow().setNavigationBarColor(Color.rgb(7, 10, 24));
        FrameLayout frame = new FrameLayout(this);
        frame.addView(new MoonlightBackgroundView(this));
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(48));
        scroll.addView(root);
        frame.addView(scroll);
        Button back = button("‹ 返回守卫");
        back.setOnClickListener(v -> finish());
        root.addView(back, wrap());
        TextView title = text("时间去哪儿了", 30, INK);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title, margin(18));
        root.addView(text("应用使用时长 · 伴侣可直接查询", 14, MUTED), margin(8));
        GuardPreferences prefs = new GuardPreferences(this);
        android.widget.Switch sharing = new android.widget.Switch(this);
        sharing.setText("同步使用时长给伴侣"); sharing.setTextColor(INK);
        sharing.setChecked(prefs.usageSharing());
        sharing.setOnCheckedChangeListener((v, enabled) -> {
            prefs.setUsageSharing(enabled); syncUsage();
        });
        root.addView(sharing, margin(16));
        root.addView(text("只同步应用名称和时长，不传页面内容；无障碍服务运行时约每五分钟更新。关闭会在联网后清除服务器上的统计。", 13, MUTED), margin(8));
        syncStatus = text(prefs.usageSyncMessage(), 13, MINT);
        root.addView(syncStatus, margin(8));
        Button sync = button("立即同步给伴侣");
        sync.setOnClickListener(v -> syncUsage());
        root.addView(sync, margin(8));
        LinearLayout ranges = new LinearLayout(this);
        String[] labels = {"今天", "昨天", "近七天"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button tab = button(labels[i]);
            tab.setOnClickListener(v -> { selected = index; refresh(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1f);
            p.setMargins(dp(2), 0, dp(2), 0);
            ranges.addView(tab, p);
            tabs.add(tab);
        }
        root.addView(ranges, margin(22));
        Button reload = button("刷新统计");
        reload.setOnClickListener(v -> refresh());
        root.addView(reload, margin(8));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results, margin(18));
        root.addView(text("直接读取安卓系统统计的应用前台使用时长，不再自行计时。分屏时各应用时长可重叠，合计不等于屏幕亮起时长。", 12, MUTED), margin(24));
        setContentView(frame);
    }

    @Override protected void onResume() { super.onResume(); refresh(); syncUsage(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("range", selected); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        generation++; worker.shutdownNow(); super.onDestroy();
    }

    private void syncUsage() {
        if (syncStatus == null) return;
        syncStatus.setText("正在同步…");
        UsageSync.schedule(this, true, message -> {
            if (!isFinishing() && !isDestroyed()) syncStatus.setText(message);
        });
    }

    private boolean hasAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private void refresh() {
        if (results == null) return;
        final int ticket = ++generation;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).setTextColor(i == selected ? MINT : MUTED);
            tabs.get(i).setSelected(i == selected);
            tabs.get(i).setBackground(box(i == selected));
        }
        results.removeAllViews();
        if (!hasAccess()) {
            results.addView(text("先允许读取应用使用记录", 20, INK));
            results.addView(text("点下方按钮，在系统列表中找到「兔酱睡眠守卫」，开启“使用情况访问权限”。返回这里就会刷新，不需要改无障碍设置。", 14, MUTED), margin(12));
            Button grant = button("打开使用情况访问设置");
            grant.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (android.content.ActivityNotFoundException e) {
                    try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                    catch (android.content.ActivityNotFoundException ignored) {
                        results.addView(text("此手机未提供快捷入口，请到系统设置搜索“使用情况访问权限”。", 14, MUTED));
                    }
                }
            });
            results.addView(grant, margin(16));
            return;
        }
        results.addView(text("正在读取手机里的使用记录…", 15, MUTED));
        final ZoneId zone = ZoneId.systemDefault();
        final long now = System.currentTimeMillis();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        final long begin = today.minusDays(selected == 2 ? 6 : selected == 1 ? 1 : 0)
                .atStartOfDay(zone).toInstant().toEpochMilli();
        final long end = selected == 1 ? today.atStartOfDay(zone).toInstant().toEpochMilli() : now;
        worker.execute(() -> {
            try {
                UsageSummary summary = SystemUsage.read(this, begin, end, now);
                List<Row> rows = new ArrayList<>();
                for (Map.Entry<String, Long> item : summary.totals().entrySet()) {
                    String label = item.getKey();
                    try {
                        ApplicationInfo info = getPackageManager().getApplicationInfo(item.getKey(), 0);
                        label = getPackageManager().getApplicationLabel(info).toString();
                    } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                        // Invisible/uninstalled packages remain counted, identified by package name.
                    }
                    rows.add(new Row(item.getKey(), label, item.getValue()));
                }
                rows.sort((a, b) -> {
                    int diff = Long.compare(b.millis, a.millis);
                    return diff == 0 ? a.pkg.compareTo(b.pkg) : diff;
                });
                runOnUiThread(() -> {
                    if (ticket != generation || isFinishing() || isDestroyed()) return;
                    if (!hasAccess()) { refresh(); return; }
                    render(rows, summary.start, summary.end, summary.firstRecord, zone);
                    if (summary.expanded()) results.addView(text("系统按整日统计，以上显示系统实际覆盖的区间。", 12, MUTED), margin(8));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (ticket != generation || isFinishing() || isDestroyed()) return;
                    results.removeAllViews();
                    results.addView(text("暂时没能读取统计，请确认使用情况访问权限已开启，再点刷新。", 15, MUTED));
                });
            }
        });
    }

    private void render(List<Row> rows, long begin, long end, long first, ZoneId zone) {
        results.removeAllViews();
        DateTimeFormatter date = DateTimeFormatter.ofPattern("MM月dd日 HH:mm").withZone(zone);
        results.addView(text(date.format(Instant.ofEpochMilli(begin)) + " — "
                + date.format(Instant.ofEpochMilli(end)), 12, MUTED));
        if (rows.isEmpty()) {
            results.addView(text("这个时段还没有可统计的使用记录", 19, INK), margin(18));
            results.addView(text("这不代表完全没用手机。可以使用其他应用后回来刷新，或切换查看今天。", 14, MUTED), margin(8));
            return;
        }
        long total = 0;
        for (Row row : rows) total += row.millis;
        results.addView(text(UsageTimeline.duration(total), 30, MINT), margin(18));
        results.addView(text("系统统计的应用前台时长合计（含桌面等系统界面）", 13, MUTED), margin(4));
        if (first > begin && first != Long.MAX_VALUE) {
            results.addView(text("本次最早系统统计：" + date.format(Instant.ofEpochMilli(first))
                    + "；不代表其余时段记录完整。", 12, MUTED), margin(10));
        }
        long max = rows.get(0).millis;
        for (Row row : rows) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackground(box(false));
            card.addView(text(row.label, 17, INK));
            // Package identity stays available even for duplicate app labels.
            card.addView(text(row.pkg, 11, MUTED), margin(3));
            card.addView(text(UsageTimeline.duration(row.millis), 15, MINT), margin(8));
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress((int) Math.max(1, row.millis * 1000 / max));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));
            bar.setContentDescription(row.label + "，" + UsageTimeline.duration(row.millis));
            card.addView(bar, margin(8));
            results.addView(card, margin(12));
        }
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private Button button(String title) {
        Button b = new Button(this); b.setText(title); b.setTextSize(14); b.setTextColor(INK);
        b.setAllCaps(false); b.setBackground(box(false)); b.setMinHeight(dp(48)); return b;
    }
    private GradientDrawable box(boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(active ? Color.argb(65, 128, 236, 213) : Color.argb(76, 35, 42, 70));
        d.setCornerRadius(dp(18)); d.setStroke(dp(1), Color.argb(60, 211, 220, 255)); return d;
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private LinearLayout.LayoutParams margin(int top) {
        LinearLayout.LayoutParams p = wrap(); p.topMargin = dp(top); return p;
    }
    private static final class Row {
        final String pkg, label; final long millis;
        Row(String pkg, String label, long millis) { this.pkg = pkg; this.label = label; this.millis = millis; }
    }
}
