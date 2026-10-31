import com.petricho.threedays.StudyEngine;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class StudyEngineTest {
    static int checks;
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static void equal(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
    }
    static long at(String text) { return ZonedDateTime.parse(text + "+09:00[Asia/Seoul]").toInstant().toEpochMilli(); }
    public static void main(String[] args) {
        long morning = at("2026-10-31T09:00:00");
        StudyEngine e = new StudyEngine();
        e.start(morning); e.stop(morning + 7200000);
        e.start(morning + 14400000); e.stop(morning + 18000000);
        equal(10800000L, e.totalForDay(LocalDate.of(2026, 10, 31), SEOUL, morning + 18000000), "End then restart retains today's total");
        e.returnToApp(morning + 19000000);
        equal(StudyEngine.State.STOPPED, e.state, "End never auto-resumes");

        e = new StudyEngine(); e.start(morning);
        e.pause(true, morning + 60000); e.returnToApp(morning + 3600000); e.stop(morning + 3660000);
        equal(120000L, e.totalForDay(LocalDate.of(2026, 10, 31), SEOUL, morning + 3660000), "Time in other apps excluded");
        e.start(morning + 4000000); e.pause(false, morning + 4060000); e.returnToApp(morning + 5000000);
        equal(StudyEngine.State.MANUAL_PAUSED, e.state, "Manual pause never auto-resumes");

        e = new StudyEngine(); e.start(morning);
        e.leaveScreen(false, false, morning + 10000);
        equal(StudyEngine.State.RUNNING, e.state, "Screen-off before keyguard event keeps counting");
        for (int i = 1; i <= 3; i++) {
            e.leaveScreen(true, true, morning + i * 60000);
            e.leaveScreen(false, true, morning + i * 60000 + 10000);
        }
        equal(StudyEngine.State.RUNNING, e.state, "Repeated lockscreen on/off keeps counting");
        equal(200000L, e.totalForDay(LocalDate.of(2026, 10, 31), SEOUL, morning + 200000), "Lockscreen duration counted");
        e.leaveScreen(true, false, morning + 210000);
        equal(StudyEngine.State.AUTO_PAUSED, e.state, "Home or other app pauses");
        e.leaveScreen(false, true, morning + 220000);
        equal(StudyEngine.State.AUTO_PAUSED, e.state, "Screen-off after Home never resumes");
        e.returnToApp(morning + 300000); e.stop(morning + 310000);
        equal(220000L, e.totalForDay(LocalDate.of(2026, 10, 31), SEOUL, morning + 310000), "Return resumes and excludes background interval");

        e = new StudyEngine(); long night = at("2026-10-31T23:30:00"); e.start(night);
        equal(1800000L, e.totalForDay(LocalDate.of(2026, 10, 31), SEOUL, night + 3600000), "Midnight splits previous day");
        equal(1800000L, e.totalForDay(LocalDate.of(2026, 11, 1), SEOUL, night + 3600000), "Midnight splits current day");
        e.checkpoint(night + 3600000); e.checkpoint(night + 7200000); e.stop(night + 10800000);
        equal(1, e.records.size(), "Checkpoints coalesce into editable session");
        equal(10800000L, e.totalBetween(night, night + 10800000, night + 10800000), "Checkpointing doesn't double count");

        e = new StudyEngine(); e.add(morning, morning + 7200000); long id = e.add(morning + 3600000, morning + 10800000);
        equal(10800000L, e.totalBetween(morning, morning + 20000000, morning + 20000000), "Overlapping edits union, not sum");
        e.replace(id, morning + 7200000, morning + 14400000);
        equal(14400000L, e.totalBetween(morning, morning + 20000000, morning + 20000000), "Edit recalculates totals");
        e.remove(id);
        equal(7200000L, e.totalBetween(morning, morning + 20000000, morning + 20000000), "Delete recalculates totals");
        boolean invalid = false; try { e.add(morning, morning); } catch (IllegalArgumentException ex) { invalid = true; }
        equal(true, invalid, "Empty intervals rejected");

        ZoneId ny = ZoneId.of("America/New_York");
        LocalDate dst = LocalDate.of(2026, 11, 1);
        long ds = dst.atStartOfDay(ny).toInstant().toEpochMilli(), de = dst.plusDays(1).atStartOfDay(ny).toInstant().toEpochMilli();
        e = new StudyEngine(); e.add(ds, de);
        equal(25 * 3600000L, e.totalForDay(dst, ny, de), "DST days use real local midnight boundaries");
        equal("27:01:05", StudyEngine.clock(27 * 3600000L + 65000), "Clock doesn't wrap at 24 hours");
        equal(0L, StudyEngine.unionDuration(List.of(), 0, 100), "Empty history");
        equal("0시간 0분", StudyEngine.readable(0), "Empty total display");
        System.out.println("PASS: " + checks + " study timer checks");
    }
}
