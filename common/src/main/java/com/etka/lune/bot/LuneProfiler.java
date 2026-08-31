package com.etka.lune.bot;

import com.etka.lune.Constants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where Lune's own time goes, measured rather than guessed.
 *
 * <p>A stall that shows one frame at a time is the client thread being held for hundreds of
 * milliseconds, and there is no way to reason that back to a line of code from the outside: the
 * symptom is identical whether it is a pathfinder search, a block sweep, an entity query or a
 * raycast. So this measures it. Sections nest, each one records the time spent inside it and the
 * time spent inside it <em>excluding</em> its children, and the worst offenders are shown live and
 * written to the log when a single tick blows past its budget.</p>
 *
 * <h2>Cost when off</h2>
 *
 * <p>One volatile read per section. {@link #enabled} is checked before anything is allocated or
 * timed, so an instrumented method that is not being profiled pays a predictable branch and
 * nothing else. Everything here runs on the client thread and nowhere else, which is why the
 * stack is a plain field rather than a thread local.</p>
 */
public final class LuneProfiler {

    /** A tick slower than this is not a slow tick, it is a stall worth a line in the log. */
    private static final long STALL_MILLIS = 40;
    /** How many rows the overlay and the log line are willing to show. */
    private static final int TOP_ROWS = 8;
    /** Windows are a second long, so the numbers on screen are readable rather than flickering. */
    private static final long WINDOW_MILLIS = 1000;

    /** One measured label over the reporting window. */
    public record Row(String label, long totalMicros, long selfMicros, int calls) {
        public long averageMicros() {
            return calls == 0 ? 0 : totalMicros / calls;
        }
    }

    private record Frame(String label, long began, long childNanos) {}

    private static volatile boolean enabled;

    private static final List<Frame> STACK = new ArrayList<>();
    private static final Map<String, long[]> WINDOW = new LinkedHashMap<>();
    private static List<Row> lastReport = List.of();

    private static long windowBegan;
    private static long worstTickMicros;
    private static String worstTickLabel = "";
    private static long tickBegan;
    private static int ticksInWindow;
    // What the last closed window measured. Kept separate from the running counters because the
    // overlay divides the reported totals by the reported tick count, and dividing last window's
    // totals by this window's growing count is how a readout lies for a second after every reset.
    private static int reportedTicks;
    private static long reportedWorstMicros;
    private static String reportedWorstLabel = "";

    private LuneProfiler() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        STACK.clear();
        WINDOW.clear();
        lastReport = List.of();
        windowBegan = System.nanoTime();
        worstTickMicros = 0;
        worstTickLabel = "";
        ticksInWindow = 0;
        reportedTicks = 0;
        reportedWorstMicros = 0;
        reportedWorstLabel = "";
    }

    /** Opens a measured section. Must be matched by {@link #pop()}, ideally in a finally block. */
    public static void push(String label) {
        if (!enabled) {
            return;
        }
        STACK.add(new Frame(label, System.nanoTime(), 0L));
    }

    public static void pop() {
        if (!enabled || STACK.isEmpty()) {
            return;
        }
        Frame frame = STACK.remove(STACK.size() - 1);
        long elapsed = System.nanoTime() - frame.began();
        // A parent's self time is its own span minus everything its children took, so a row that
        // is large but whose self time is small tells you to look one level further in.
        if (!STACK.isEmpty()) {
            int parent = STACK.size() - 1;
            Frame above = STACK.get(parent);
            STACK.set(parent, new Frame(above.label(), above.began(), above.childNanos() + elapsed));
        }
        long[] totals = WINDOW.computeIfAbsent(frame.label(), ignored -> new long[3]);
        totals[0] += elapsed;
        totals[1] += elapsed - frame.childNanos();
        totals[2]++;
    }

    /**
     * Times a block of work that returns a value.
     *
     * <p>Preferred over the raw push/pop pair wherever the work is an expression, because an early
     * return between the two would otherwise leave the stack unbalanced.</p>
     */
    public static <T> T measure(String label, java.util.function.Supplier<T> work) {
        if (!enabled) {
            return work.get();
        }
        push(label);
        try {
            return work.get();
        } finally {
            pop();
        }
    }

    /** Marks the start of one client tick, so a single stalling tick can be named in the log. */
    public static void beginTick() {
        if (!enabled) {
            return;
        }
        STACK.clear();
        tickBegan = System.nanoTime();
    }

    public static void endTick() {
        if (!enabled) {
            return;
        }
        // An unbalanced section would otherwise carry its start time into the next tick and report
        // an enormous, meaningless span.
        while (!STACK.isEmpty()) {
            pop();
        }
        long micros = (System.nanoTime() - tickBegan) / 1000;
        ticksInWindow++;
        if (micros > worstTickMicros) {
            worstTickMicros = micros;
            worstTickLabel = heaviestLabel();
        }
        if (micros > STALL_MILLIS * 1000) {
            Constants.LOG.warn("Lune stalled the client for {} ms in one tick. Worst section: {}",
                    micros / 1000, describe(report(false)));
        }
        if ((System.nanoTime() - windowBegan) / 1_000_000 >= WINDOW_MILLIS) {
            lastReport = report(true);
            reportedTicks = ticksInWindow;
            reportedWorstMicros = worstTickMicros;
            reportedWorstLabel = worstTickLabel;
            windowBegan = System.nanoTime();
            // Reset with the window. An all-time worst would keep showing one spike from a minute
            // ago long after the thing that caused it stopped, which is the opposite of useful
            // while somebody is toggling settings to find out what helps.
            worstTickMicros = 0;
            worstTickLabel = "";
            ticksInWindow = 0;
        }
    }

    private static String heaviestLabel() {
        String worst = "";
        long most = 0;
        for (var entry : WINDOW.entrySet()) {
            if (entry.getValue()[1] > most) {
                most = entry.getValue()[1];
                worst = entry.getKey();
            }
        }
        return worst;
    }

    private static List<Row> report(boolean reset) {
        List<Row> rows = new ArrayList<>(WINDOW.size());
        for (var entry : WINDOW.entrySet()) {
            long[] totals = entry.getValue();
            rows.add(new Row(entry.getKey(), totals[0] / 1000, totals[1] / 1000, (int) totals[2]));
        }
        rows.sort(Comparator.comparingLong(Row::selfMicros).reversed());
        if (rows.size() > TOP_ROWS) {
            rows = new ArrayList<>(rows.subList(0, TOP_ROWS));
        }
        if (reset) {
            WINDOW.clear();
        }
        return List.copyOf(rows);
    }

    /** The last completed window, worst self-time first. Empty until a window has closed. */
    public static List<Row> rows() {
        return lastReport;
    }

    /** Ticks measured in the last window, so a per-tick average can be read off the totals. */
    public static int ticksInWindow() {
        return Math.max(1, reportedTicks);
    }

    public static long worstTickMicros() {
        return reportedWorstMicros;
    }

    public static String worstTickLabel() {
        return reportedWorstLabel;
    }

    /** One line naming the heaviest sections, for the log and for pasting into a bug report. */
    public static String describe(List<Row> rows) {
        if (rows.isEmpty()) {
            return "nothing measured";
        }
        StringBuilder text = new StringBuilder();
        for (Row row : rows) {
            if (text.length() > 0) {
                text.append("  |  ");
            }
            text.append(row.label()).append(' ').append(row.selfMicros() / 1000).append("ms/")
                    .append(row.calls()).append("x");
        }
        return text.toString();
    }
}
