package com.rabbit.sleepguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays system usage events, counting app time only while the screen is on.
 * Candidate figures awaiting comparison with the phone's own statistics page.
 * Time before the first screen event is unknown and is never guessed.
 */
public final class EventAccounting {
    // Stable android.app.usage.UsageEvents.Event values.
    private static final int RESUMED = 1, PAUSED = 2, SCREEN_ON = 15, SCREEN_OFF = 16,
            KEYGUARD_SHOWN = 17, KEYGUARD_HIDDEN = 18, STOPPED = 23, SHUTDOWN = 26, STARTUP = 27;

    private final long begin, end;
    private final boolean exclusive;
    private final Map<String, Set<String>> resumed = new HashMap<>();
    private final Map<String, Long> since = new HashMap<>();
    private final Map<String, Long> totals = new HashMap<>();
    private boolean screenOn, keyguardShown = true, finished;
    private long screenSince, unlockedFrom = Long.MIN_VALUE, lastEvent = Long.MIN_VALUE;
    private long screenOnMillis, unlockedMillis;

    /** @param exclusive true keeps one foreground package; false lets split-screen apps overlap. */
    public EventAccounting(long begin, long end, boolean exclusive) {
        if (end < begin) throw new IllegalArgumentException("Invalid time range");
        this.begin = begin;
        this.end = end;
        this.exclusive = exclusive;
    }

    /** Events must arrive in time order, as UsageEvents delivers them. */
    public void accept(long time, int type, String pkg, String cls) {
        if (finished || time > end) return;
        switch (type) {
            case RESUMED:
                if (pkg == null) break;
                if (exclusive) closeOthers(pkg, time);
                resumed.computeIfAbsent(pkg, key -> new HashSet<>()).add(String.valueOf(cls));
                since.putIfAbsent(pkg, time);
                break;
            case PAUSED:
            case STOPPED:
                if (pkg == null || !resumed.containsKey(pkg)) break;
                Set<String> classes = resumed.get(pkg);
                if (cls == null) classes.clear();
                else classes.remove(cls);
                if (classes.isEmpty()) closePackage(pkg, time);
                break;
            case SCREEN_ON:
                if (!screenOn) {
                    screenOn = true;
                    screenSince = time;
                    // Anything resumed while dark only starts counting from here.
                    for (String key : since.keySet()) since.put(key, time);
                }
                break;
            case SCREEN_OFF:
                screenOff(time);
                break;
            case KEYGUARD_SHOWN:
                settleUnlocked(time);
                keyguardShown = true;
                break;
            case KEYGUARD_HIDDEN:
                settleUnlocked(time);
                keyguardShown = false;
                break;
            case SHUTDOWN:
                screenOff(time);
                keyguardShown = true;
                break;
            case STARTUP:
                // A missing shutdown event means the real stop time is unknown; the
                // last recorded event is the most we can claim.
                screenOff(lastEvent == Long.MIN_VALUE ? time : lastEvent);
                keyguardShown = true;
                screenOn = true;
                screenSince = time;
                break;
            default:
                break;
        }
        lastEvent = Math.max(lastEvent, time);
    }

    public void finish() {
        if (finished) return;
        screenOff(end);
        finished = true;
    }

    public long screenOnMillis() { return screenOnMillis; }
    public long unlockedMillis() { return unlockedMillis; }
    public Map<String, Long> totals() { return Collections.unmodifiableMap(totals); }

    private void screenOff(long time) {
        settleUnlocked(time);
        if (screenOn) screenOnMillis += overlap(screenSince, time);
        for (String pkg : new ArrayList<>(since.keySet())) closePackage(pkg, time);
        screenOn = false;
    }

    private void settleUnlocked(long time) {
        if (screenOn && !keyguardShown) unlockedMillis += overlap(Math.max(screenSince, unlockedFrom), time);
        unlockedFrom = time;
    }

    private void closeOthers(String keep, long time) {
        List<String> others = new ArrayList<>(since.keySet());
        for (String pkg : others) if (!pkg.equals(keep)) closePackage(pkg, time);
    }

    private void closePackage(String pkg, long time) {
        Long start = since.remove(pkg);
        resumed.remove(pkg);
        if (start == null || !screenOn) return;
        long duration = overlap(start, time);
        if (duration > 0L) totals.merge(pkg, duration, Long::sum);
    }

    private long overlap(long from, long to) {
        return Math.max(0L, Math.min(to, end) - Math.max(from, begin));
    }
}
