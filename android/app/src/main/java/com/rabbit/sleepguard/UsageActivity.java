package com.rabbit.sleepguard;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
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
        root.addView(text("应用使用时长 · 仅在本机查看", 14, MUTED), margin(8));
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
        root.addView(text("按系统保留的前台切换记录估算，不含后台播放。系统可能只保留最近几天；缺失记录不会补成全天时长。分屏、系统桌面和厂商统计口径可能造成差异。", 12, MUTED), margin(24));
        setContentView(frame);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("range", selected); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        generation++; worker.shutdownNow(); super.onDestroy();
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
        final long scanFrom = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
                .minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        worker.execute(() -> {
            try {
                UsageStatsManager manager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                if (manager == null) throw new IllegalStateException("服务不可用");
                UsageEvents events = manager.queryEvents(scanFrom, end);
                if (events == null) throw new IllegalStateException("系统暂未返回记录");
                UsageTimeline timeline = new UsageTimeline(begin, end);
                UsageEvents.Event event = new UsageEvents.Event();
                long earliest = Long.MAX_VALUE;
                while (events.hasNextEvent()) {
                    if (Thread.currentThread().isInterrupted()) return;
                    events.getNextEvent(event);
                    earliest = Math.min(earliest, event.getTimeStamp());
                    timeline.accept(event.getTimeStamp(), event.getEventType(),
                            event.getPackageName(), event.getClassName());
                }
                List<Row> rows = new ArrayList<>();
                for (Map.Entry<String, Long> item : timeline.finish().entrySet()) {
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
                final long first = earliest;
                runOnUiThread(() -> {
                    if (ticket != generation || isFinishing() || isDestroyed()) return;
                    if (!hasAccess()) { refresh(); return; }
                    render(rows, begin, end, first, zone);
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
        results.addView(text("已记录的前台使用合计（含桌面等系统界面）", 13, MUTED), margin(4));
        if (first > begin && first != Long.MAX_VALUE) {
            results.addView(text("本次最早可读记录：" + date.format(Instant.ofEpochMilli(first))
                    + "；更早的时段无法确认。", 12, MUTED), margin(10));
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
