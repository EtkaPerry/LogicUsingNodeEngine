package com.etka.lune.bot.learning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the learned profile has measured for one {@link LearningScope}: every row it covers, and
 * the three numbers a card shows - how fast the work usually goes, how fast it went last time,
 * and the fastest it has ever gone.
 *
 * <p>Rates are work units per second, and the average is pooled - total units over total ticks -
 * rather than a mean of per-episode rates, so a long tree and a short one weigh by the logs they
 * produced rather than one each. A row that never finished anything has no rate and still counts
 * its runs, because "tried forty times, finished none" is the most useful thing a card can say
 * about a job.</p>
 *
 * @param bestTicksPerUnit fastest measured pace across all rows, 0 when nothing has finished
 * @param lastUnits        work of the most recent finished episode across all rows
 * @param lastTicks        its length; 0 when no row remembers one, as rows written before
 *                         "last time" was kept do not
 */
public record SkillStats(long runs, long completedRuns, long totalUnits, long totalTicks,
                         double bestTicksPerUnit, long lastUnits, long lastTicks, List<Row> rows) {

    public static final SkillStats EMPTY = new SkillStats(0, 0, 0, 0, 0.0, 0, 0, List.of());

    private static final double TICKS_PER_SECOND = 20.0;

    /**
     * One persisted row - one skill, in one dimension, in one situation - as it stands.
     *
     * @param lastOutcome   ordinal of the profile's outcome that wrote {@code lastUnits}; the
     *                      highest across rows is the most recent, whatever the clock said
     * @param leadingTactic the tactic the learner currently rates highest here, or "" where the
     *                      job never had a choice
     */
    public record Row(LearningContext context, long runs, long completedRuns, long totalUnits,
                      long totalTicks, double bestTicksPerUnit, long lastUnits, long lastTicks,
                      long lastOutcome, String leadingTactic) {

        public Row {
            leadingTactic = leadingTactic == null ? "" : leadingTactic;
        }

        public boolean measured() {
            return totalUnits > 0 && totalTicks > 0;
        }

        public double averageUnitsPerSecond() {
            return measured() ? totalUnits * TICKS_PER_SECOND / totalTicks : 0.0;
        }

        public double bestUnitsPerSecond() {
            return bestTicksPerUnit > 0.0 ? TICKS_PER_SECOND / bestTicksPerUnit : 0.0;
        }

        public double averageSecondsPerUnit() {
            return measured() ? totalTicks / (double) totalUnits / TICKS_PER_SECOND : 0.0;
        }

        public double bestSecondsPerUnit() {
            return Math.max(0.0, bestTicksPerUnit) / TICKS_PER_SECOND;
        }
    }

    public SkillStats {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** Pools rows into one set of numbers. Rows come back busiest first. */
    public static SkillStats of(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return EMPTY;
        }
        long runs = 0;
        long completedRuns = 0;
        long totalUnits = 0;
        long totalTicks = 0;
        double bestTicksPerUnit = 0.0;
        Row newest = null;
        for (Row row : rows) {
            runs += row.runs();
            completedRuns += row.completedRuns();
            totalUnits += row.totalUnits();
            totalTicks += row.totalTicks();
            if (row.bestTicksPerUnit() > 0.0
                    && (bestTicksPerUnit <= 0.0 || row.bestTicksPerUnit() < bestTicksPerUnit)) {
                bestTicksPerUnit = row.bestTicksPerUnit();
            }
            if (row.lastTicks() > 0 && (newest == null || row.lastOutcome() > newest.lastOutcome())) {
                newest = row;
            }
        }
        List<Row> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparingLong(Row::runs).reversed()
                .thenComparing(row -> row.context().key()));
        return new SkillStats(runs, completedRuns, totalUnits, totalTicks, bestTicksPerUnit,
                newest == null ? 0 : newest.lastUnits(), newest == null ? 0 : newest.lastTicks(),
                ordered);
    }

    /** True when at least one episode finished with something to show, so a rate exists. */
    public boolean measured() {
        return totalUnits > 0 && totalTicks > 0;
    }

    /** True when some row remembers its most recent episode. Older profiles do not. */
    public boolean hasLast() {
        return lastUnits > 0 && lastTicks > 0;
    }

    public double averageUnitsPerSecond() {
        return measured() ? totalUnits * TICKS_PER_SECOND / totalTicks : 0.0;
    }

    public double lastUnitsPerSecond() {
        return hasLast() ? lastUnits * TICKS_PER_SECOND / lastTicks : 0.0;
    }

    public double bestUnitsPerSecond() {
        return bestTicksPerUnit > 0.0 ? TICKS_PER_SECOND / bestTicksPerUnit : 0.0;
    }

    public double averageSecondsPerUnit() {
        return measured() ? totalTicks / (double) totalUnits / TICKS_PER_SECOND : 0.0;
    }

    public double lastSecondsPerUnit() {
        return hasLast() ? lastTicks / (double) lastUnits / TICKS_PER_SECOND : 0.0;
    }

    public double bestSecondsPerUnit() {
        return Math.max(0.0, bestTicksPerUnit) / TICKS_PER_SECOND;
    }

    /**
     * A rate or a duration with as many digits as a card has room for: "3.30", "12.3", "123".
     *
     * <p>Two decimals matter at the low end - 1.39 and 1.20 logs a second are different trees -
     * and are noise at the high end, where the card would rather keep the width.</p>
     */
    public static String format(double value) {
        double safe = Math.max(0.0, value);
        if (safe >= 100.0) {
            return String.format(Locale.ROOT, "%.0f", safe);
        }
        if (safe >= 10.0) {
            return String.format(Locale.ROOT, "%.1f", safe);
        }
        return String.format(Locale.ROOT, "%.2f", safe);
    }
}
