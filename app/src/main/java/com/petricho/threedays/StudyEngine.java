package com.petricho.threedays;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure Java state and interval arithmetic. Android lifecycle and persistence stay outside. */
public final class StudyEngine {
    public enum State { STOPPED, RUNNING, AUTO_PAUSED, MANUAL_PAUSED }
    public static final class Interval {
        public final long id, start, end;
        public Interval(long id, long start, long end) {
            this.id = id; this.start = start; this.end = end;
        }
    }
    public final List<Interval> records = new ArrayList<>();
    public State state = State.STOPPED;
    public long activeStart;
    private long nextId = 1;

    public void start(long now) {
        if (state == State.RUNNING) return;
        activeStart = now;
        state = State.RUNNING;
    }
    public void pause(boolean automatic, long now) {
        if (state != State.RUNNING) {
            if (!automatic && state == State.AUTO_PAUSED) state = State.MANUAL_PAUSED;
            return;
        }
        finishSegment(now);
        state = automatic ? State.AUTO_PAUSED : State.MANUAL_PAUSED;
    }
    public void stop(long now) {
        if (state == State.RUNNING) finishSegment(now);
        state = State.STOPPED;
    }
    public void returnToApp(long now) { if (state == State.AUTO_PAUSED) start(now); }
    public void leaveScreen(boolean interactive, boolean locked, long now) {
        if (interactive && !locked) pause(true, now);
    }
    /** Persist elapsed segments without changing the running/paused state. */
    public void checkpoint(long now) {
        if (state == State.RUNNING) { finishSegment(now); activeStart = now; }
    }
    private void finishSegment(long now) {
        if (now > activeStart) {
            // Coalesce adjacent checkpoints so a long session remains one editable record.
            if (!records.isEmpty() && records.get(records.size() - 1).end == activeStart) {
                Interval last = records.remove(records.size() - 1);
                records.add(new Interval(last.id, last.start, now));
            } else records.add(new Interval(nextId++, activeStart, now));
        }
        activeStart = 0;
    }
    public long add(long start, long end) {
        validate(start, end);
        long id = nextId++;
        records.add(new Interval(id, start, end));
        return id;
    }
    public void replace(long id, long start, long end) {
        validate(start, end);
        for (int i = 0; i < records.size(); i++) if (records.get(i).id == id) {
            records.set(i, new Interval(id, start, end)); return;
        }
        throw new IllegalArgumentException("기록을 찾을 수 없습니다.");
    }
    public void remove(long id) { records.removeIf(i -> i.id == id); }
    public void restore(List<Interval> saved) {
        records.clear(); records.addAll(saved);
        nextId = saved.stream().mapToLong(i -> i.id).max().orElse(0) + 1;
    }
    private static void validate(long start, long end) {
        if (start < 0 || end <= start) throw new IllegalArgumentException("종료 시각은 시작 시각보다 늦어야 합니다.");
    }
    public long totalForDay(LocalDate date, ZoneId zone, long now) {
        return totalBetween(date.atStartOfDay(zone).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now);
    }
    public long totalBetween(long from, long to, long now) {
        List<Interval> all = new ArrayList<>(records);
        if (state == State.RUNNING && now > activeStart) all.add(new Interval(-1, activeStart, now));
        return unionDuration(all, from, to);
    }
    public static long unionDuration(List<Interval> all, long from, long to) {
        List<Interval> clipped = new ArrayList<>();
        for (Interval item : all) {
            long s = Math.max(item.start, from), e = Math.min(item.end, to);
            if (e > s) clipped.add(new Interval(item.id, s, e));
        }
        clipped.sort(Comparator.comparingLong(i -> i.start));
        long sum = 0, s = -1, e = -1;
        for (Interval item : clipped) {
            if (s < 0) { s = item.start; e = item.end; }
            else if (item.start <= e) e = Math.max(e, item.end);
            else { sum += e - s; s = item.start; e = item.end; }
        }
        return sum + (s < 0 ? 0 : e - s);
    }
    public static String clock(long millis) {
        long sec = Math.max(0, millis) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);
    }
    public static String readable(long millis) {
        long min = Math.max(0, millis) / 60000;
        return (min / 60) + "시간 " + (min % 60) + "분";
    }
}
