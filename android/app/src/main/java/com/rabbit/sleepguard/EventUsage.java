package com.rabbit.sleepguard;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

/** Reads the system event log into candidate figures. Read-only and on demand. */
public final class EventUsage {
    public static final String METHOD = "events_screen_v1";
    // Events before the range only establish whether the screen and apps were already on.
    private static final long LOOKBACK = 24L * 3600_000L;

    public final EventAccounting overlapping, exclusive;
    public final long firstEvent;

    private EventUsage(EventAccounting overlapping, EventAccounting exclusive, long firstEvent) {
        this.overlapping = overlapping;
        this.exclusive = exclusive;
        this.firstEvent = firstEvent;
    }

    public static EventUsage read(Context context, long begin, long end) {
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) throw new IllegalStateException("No usage service");
        EventAccounting overlapping = new EventAccounting(begin, end, false);
        EventAccounting exclusive = new EventAccounting(begin, end, true);
        long first = Long.MAX_VALUE;
        UsageEvents events = manager.queryEvents(begin - LOOKBACK, end);
        if (events == null) throw new IllegalStateException("Usage events unavailable");
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.getNextEvent(event)) {
            long time = event.getTimeStamp();
            first = Math.min(first, time);
            overlapping.accept(time, event.getEventType(), event.getPackageName(), event.getClassName());
            exclusive.accept(time, event.getEventType(), event.getPackageName(), event.getClassName());
        }
        overlapping.finish();
        exclusive.finish();
        return new EventUsage(overlapping, exclusive, first);
    }
}
