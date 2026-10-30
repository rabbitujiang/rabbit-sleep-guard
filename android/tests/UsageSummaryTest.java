import com.rabbit.sleepguard.UsageSummary;

public class UsageSummaryTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        UsageSummary empty = new UsageSummary(1000, 2000);
        check(empty.totals().isEmpty()); check(!empty.expanded());
        UsageSummary s = new UsageSummary(1000, 3000);
        s.accept("a", 1000, 2000, 200); s.accept("a", 2000, 3000, 300);
        check(s.totals().get("a") == 500); check(s.firstRecord == 1000); check(!s.expanded());
        s.accept("b", 900, 3200, 2300);
        check(s.start == 900 && s.end == 3200 && s.expanded());
        check(s.totals().get("a") == 500); // no proportional recomputation
        s.accept("c", 900, 3200, 2300); // overlaps are system data, not discarded
        check(s.totals().get("c") == 2300);
        s.accept("zero", 900, 3200, 0); check(!s.totals().containsKey("zero"));
        boolean rejected = false;
        try { s.accept("bad", 2000, 1000, 500); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected);
        rejected = false;
        try { s.accept("bad", 1000, 2000, -1); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected);
        check(new UsageSummary(1000,1000).totals().isEmpty());
        System.out.println("UsageSummaryTest: all checks passed");
    }
}
