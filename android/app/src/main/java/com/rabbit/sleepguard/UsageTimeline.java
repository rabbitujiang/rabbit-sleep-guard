package com.rabbit.sleepguard;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Single-foreground estimate. Unknown/missing intervals are never guessed. */
public final class UsageTimeline {
    private final long begin, end;
    private final Map<String, Long> totals = new HashMap<>();
    private String activePackage, activeClass;
    private long since;

    public UsageTimeline(long begin, long end) {
        if (end < begin) throw new IllegalArgumentException("Invalid time range");
        this.begin = begin;
        this.end = end;
    }

    // Numeric values are stable Android UsageEvents constants (also works on API 26).
    public void accept(long time, int type, String pkg, String cls) {
        if (time > end) return;
        if (type == 1 && pkg != null) { // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
            close(time);
            activePackage = pkg;
            activeClass = cls;
            since = time;
        } else if ((type == 2 || type == 23) && pkg != null && pkg.equals(activePackage)
                && (cls == null || activeClass == null || cls.equals(activeClass))) {
            close(time); // PAUSED / STOPPED; don't close a newer activity for an old pause
        } else if (type == 16 || type == 17 || type == 26 || type == 27) {
            close(time); // screen off, keyguard, shutdown, startup
        }
    }

    private void close(long time) {
        if (activePackage != null) {
            long duration = Math.max(0L, Math.min(time, end) - Math.max(since, begin));
            if (duration > 0L) totals.merge(activePackage, duration, Long::sum);
        }
        activePackage = null;
        activeClass = null;
    }

    public Map<String, Long> finish() {
        close(end);
        return Collections.unmodifiableMap(new HashMap<>(totals));
    }

    public static String duration(long millis) {
        if (millis <= 0) return "0 分钟";
        long minutes = millis / 60_000;
        if (minutes == 0) return "不足 1 分钟";
        if (minutes < 60) return minutes + " 分钟";
        return minutes / 60 + " 小时 " + minutes % 60 + " 分钟";
    }
}
