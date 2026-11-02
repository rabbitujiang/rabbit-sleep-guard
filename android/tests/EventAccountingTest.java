import com.rabbit.sleepguard.EventAccounting;

public final class EventAccountingTest {
    private static final int RESUMED = 1, PAUSED = 2, SCREEN_ON = 15, SCREEN_OFF = 16,
            KEYGUARD_SHOWN = 17, KEYGUARD_HIDDEN = 18, STOPPED = 23, SHUTDOWN = 26, STARTUP = 27;

    private static void equal(long actual, long expected, String name) {
        if (actual != expected) throw new AssertionError(name + ": expected " + expected + " got " + actual);
    }
    private static long app(EventAccounting a, String pkg) { return a.totals().getOrDefault(pkg, 0L); }

    public static void main(String[] args) {
        // An activity whose pause never arrives stops counting at screen off and
        // is not charged again on the next wake. This is the 26-hour failure shape.
        EventAccounting stuck = new EventAccounting(0, 1000, false);
        stuck.accept(10, SCREEN_ON, null, null);
        stuck.accept(20, RESUMED, "music", "Main");
        stuck.accept(120, SCREEN_OFF, null, null);
        stuck.accept(900, SCREEN_ON, null, null);
        stuck.accept(950, SCREEN_OFF, null, null);
        stuck.finish();
        equal(app(stuck, "music"), 100, "stuck activity");
        equal(stuck.screenOnMillis(), 160, "screen total excludes dark time");

        // Time before the first screen event is unknown, not assumed.
        EventAccounting unknown = new EventAccounting(0, 1000, false);
        unknown.accept(10, RESUMED, "a", "A");
        unknown.accept(50, PAUSED, "a", "A");
        unknown.accept(60, SCREEN_OFF, null, null);
        unknown.finish();
        equal(app(unknown, "a"), 0, "unknown state");
        equal(unknown.screenOnMillis(), 0, "unknown screen");

        // State carried in from a lookback window counts only from the range start.
        EventAccounting lookback = new EventAccounting(100, 300, false);
        lookback.accept(10, SCREEN_ON, null, null);
        lookback.accept(20, RESUMED, "a", "A");
        lookback.accept(150, PAUSED, "a", "A");
        lookback.accept(200, SCREEN_OFF, null, null);
        lookback.finish();
        equal(app(lookback, "a"), 50, "lookback app");
        equal(lookback.screenOnMillis(), 100, "lookback screen");

        // Resumed just before the wake event: counts from the wake.
        EventAccounting early = new EventAccounting(0, 1000, false);
        early.accept(10, RESUMED, "a", "A");
        early.accept(30, SCREEN_ON, null, null);
        early.accept(80, PAUSED, "a", "A");
        early.finish();
        equal(app(early, "a"), 50, "resume before wake");

        // Open sessions close at the end of the range.
        EventAccounting open = new EventAccounting(0, 100, false);
        open.accept(10, SCREEN_ON, null, null);
        open.accept(20, RESUMED, "a", "A");
        open.finish();
        equal(app(open, "a"), 80, "open at end");
        equal(open.screenOnMillis(), 90, "screen open at end");
        open.accept(90, PAUSED, "a", "A");
        equal(app(open, "a"), 80, "events after finish ignored");

        // Split screen overlaps in one mode only.
        for (boolean exclusive : new boolean[]{false, true}) {
            EventAccounting split = new EventAccounting(0, 1000, exclusive);
            split.accept(0, SCREEN_ON, null, null);
            split.accept(0, RESUMED, "a", "A");
            split.accept(10, RESUMED, "b", "B");
            split.accept(50, PAUSED, "b", "B");
            split.accept(60, PAUSED, "a", "A");
            split.accept(70, SCREEN_OFF, null, null);
            split.finish();
            equal(app(split, "a"), exclusive ? 10 : 60, "split a");
            equal(app(split, "b"), 40, "split b");
        }

        // A second activity keeps the package open; pause then stop is not double counted.
        EventAccounting activities = new EventAccounting(0, 1000, false);
        activities.accept(0, SCREEN_ON, null, null);
        activities.accept(0, RESUMED, "a", "One");
        activities.accept(5, RESUMED, "a", "Two");
        activities.accept(10, PAUSED, "a", "One");
        activities.accept(30, PAUSED, "a", "Two");
        activities.accept(35, STOPPED, "a", "Two");
        activities.accept(40, SCREEN_OFF, null, null);
        activities.finish();
        equal(app(activities, "a"), 30, "activities in one package");

        // Unlocked time starts at keyguard hidden and pauses across the dark period.
        EventAccounting lock = new EventAccounting(0, 1000, false);
        lock.accept(0, SCREEN_ON, null, null);
        lock.accept(10, KEYGUARD_HIDDEN, null, null);
        lock.accept(50, KEYGUARD_SHOWN, null, null);
        lock.accept(60, SCREEN_OFF, null, null);
        lock.accept(100, SCREEN_ON, null, null);
        lock.accept(110, KEYGUARD_HIDDEN, null, null);
        lock.accept(130, SCREEN_OFF, null, null);
        lock.accept(200, SCREEN_ON, null, null);
        lock.accept(220, SCREEN_OFF, null, null);
        lock.finish();
        equal(lock.unlockedMillis(), 80, "unlocked with resumed session after wake");
        equal(lock.screenOnMillis(), 110, "screen with lock");

        // A reboot without a shutdown event stops at the last known event.
        EventAccounting reboot = new EventAccounting(0, 1000, false);
        reboot.accept(0, SCREEN_ON, null, null);
        reboot.accept(10, RESUMED, "a", "A");
        reboot.accept(20, PAUSED, "a", "A");
        reboot.accept(500, STARTUP, null, null);
        reboot.accept(510, SCREEN_OFF, null, null);
        reboot.accept(600, SCREEN_ON, null, null);
        reboot.accept(650, SHUTDOWN, null, null);
        reboot.finish();
        equal(reboot.screenOnMillis(), 80, "reboot");
        System.out.println("EventAccountingTest passed");
    }
}
