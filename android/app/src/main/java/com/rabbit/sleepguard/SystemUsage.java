package com.rabbit.sleepguard;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import java.util.List;

/** Read Android's own totals; never reconstruct sessions or run a timer. */
public final class SystemUsage {
    public static final String SOURCE = "android_usage_stats";

    public static UsageSummary read(Context context, long begin, long end, long capturedAt) {
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) throw new IllegalStateException("No usage service");
        UsageSummary result = new UsageSummary(begin, end);
        if (begin == end) return result;
        List<UsageStats> records = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end);
        if (records == null) throw new IllegalStateException("Usage stats unavailable");
        for (UsageStats record : records) {
            result.accept(record.getPackageName(), record.getFirstTimeStamp(),
                    Math.min(record.getLastTimeStamp(), capturedAt), record.getTotalTimeInForeground());
        }
        return result;
    }
}
