import com.rabbit.sleepguard.UsageTimeline;
import java.util.Map;

public class UsageTimelineTest {
    static int count;
    static void eq(long actual, long expected) {
        count++;
        if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }
    static long get(Map<String, Long> map, String pkg) { return map.getOrDefault(pkg, 0L); }
    public static void main(String[] args) {
        UsageTimeline t = new UsageTimeline(100, 200);
        t.accept(50, 1, "a", "A"); t.accept(130, 2, "a", "A");
        eq(get(t.finish(), "a"), 30); // cross midnight, clip left boundary
        t = new UsageTimeline(100, 200);
        t.accept(110, 1, "a", "A"); t.accept(150, 1, "b", "B");
        Map<String, Long> m = t.finish();
        eq(get(m, "a"), 40); eq(get(m, "b"), 50); // still open at snapshot
        eq(get(t.finish(), "b"), 50); // idempotent
        t = new UsageTimeline(100, 200);
        t.accept(110, 1, "a", "A"); t.accept(120, 1, "a", "B");
        t.accept(125, 2, "a", "A"); t.accept(150, 2, "a", "B");
        eq(get(t.finish(), "a"), 40); // late pause for previous activity
        for (int stop : new int[]{16, 17, 26, 27}) {
            t = new UsageTimeline(100, 200);
            t.accept(110, 1, "a", "A"); t.accept(130, stop, null, null);
            eq(get(t.finish(), "a"), 20);
        }
        t = new UsageTimeline(100, 200);
        t.accept(120, 2, "a", "A"); eq(get(t.finish(), "a"), 0); // no invented start
        t = new UsageTimeline(100, 200);
        t.accept(110, 1, "a", "A"); t.accept(110, 1, "a", "A");
        t.accept(150, 2, "unrelated", "A"); t.accept(250, 1, "b", "B");
        eq(get(t.finish(), "a"), 90);
        t = new UsageTimeline(100, 200);
        t.accept(10, 1, "a", "A"); t.accept(90, 2, "a", "A");
        eq(get(t.finish(), "a"), 0);
        eq(new UsageTimeline(100, 200).finish().size(), 0);
        if (!UsageTimeline.duration(59999).equals("不足 1 分钟")
                || !UsageTimeline.duration(3660000).equals("1 小时 1 分钟")
                || !UsageTimeline.duration(0).equals("0 分钟")) throw new AssertionError("format");
        System.out.println("UsageTimeline: " + count + " assertions + duration formatting passed");
    }
}
