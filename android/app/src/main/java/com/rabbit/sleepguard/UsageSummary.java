package com.rabbit.sleepguard;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Sum system-provided daily totals, preserving their coverage (no prorating). */
public final class UsageSummary {
    public final long requestedStart, requestedEnd;
    public long start, end, firstRecord = Long.MAX_VALUE;
    private final Map<String, Long> totals = new HashMap<>();

    public UsageSummary(long begin, long finish) {
        if (finish < begin) throw new IllegalArgumentException("Invalid range");
        requestedStart = start = begin;
        requestedEnd = end = finish;
    }
    public void accept(String pkg, long first, long last, long duration) {
        if (pkg == null || pkg.isEmpty() || first < 0 || last < first || duration < 0)
            throw new IllegalArgumentException("Invalid system stats");
        // Daily buckets can extend outside the request. Report the actual union,
        // rather than fabricate a midnight split from a pre-aggregated number.
        start = Math.min(start, first);
        end = Math.max(end, last);
        firstRecord = Math.min(firstRecord, first);
        if (duration > 0) totals.merge(pkg, duration, Math::addExact);
    }
    public Map<String, Long> totals() { return Collections.unmodifiableMap(totals); }
    public boolean expanded() { return start < requestedStart || end > requestedEnd; }
}
