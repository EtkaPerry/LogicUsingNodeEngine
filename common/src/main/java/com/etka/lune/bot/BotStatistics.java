package com.etka.lune.bot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counters shown by the Main dashboard.
 *
 * <p>The values are deliberately plain data: the engine owns the live run, while
 * {@link BotStatisticsStore} owns the last-run and all-time copies.</p>
 *
 * <p>Everything here is measured by the bot itself. Minecraft's own statistics were used at first
 * and are not usable for this: the client's {@code StatsCounter} is only filled in when the vanilla
 * statistics screen asks the server for it, so a dashboard fed from it shows zeroes for every run
 * nobody happened to open that screen during.</p>
 */
public final class BotStatistics {

    /**
     * Counter keys with this prefix hold a high-water mark - the longest task, the biggest hit -
     * rather than a total, so lifetime totals merge them with max instead of sum.
     */
    public static final String PEAK_PREFIX = "peak.";

    public long workedTicks;
    public long blocksBroken;
    public long blocksPlaced;
    public long foodEaten;
    public long tasksCompleted;
    public long tasksFailed;
    public long repaths;
    /** Named counters the bot raised while working; see {@link DebugInfo#counters}. */
    public Map<String, Long> counters = new LinkedHashMap<>();

    public BotStatistics() {}

    public static BotStatistics from(DebugInfo debug) {
        BotStatistics statistics = new BotStatistics();
        if (debug == null) {
            return statistics;
        }
        statistics.workedTicks = Math.max(0L, debug.workedTicks);
        statistics.blocksBroken = Math.max(0L, debug.blocksBroken);
        statistics.blocksPlaced = Math.max(0L, debug.blocksPlaced);
        statistics.foodEaten = Math.max(0L, debug.foodEaten);
        statistics.tasksCompleted = Math.max(0L, debug.tasksCompleted);
        statistics.tasksFailed = Math.max(0L, debug.tasksFailed);
        statistics.repaths = Math.max(0L, debug.runRepaths);
        statistics.mergeCounters(debug.counters);
        return statistics;
    }

    public long counter(String key) {
        return counters == null ? 0L : Math.max(0L, counters.getOrDefault(key, 0L));
    }

    /** Reads a high-water mark stored by {@link DebugInfo#peak}. */
    public long peak(String key) {
        return counter(PEAK_PREFIX + key);
    }

    public BotStatistics copy() {
        BotStatistics copy = new BotStatistics();
        copy.workedTicks = workedTicks;
        copy.blocksBroken = blocksBroken;
        copy.blocksPlaced = blocksPlaced;
        copy.foodEaten = foodEaten;
        copy.tasksCompleted = tasksCompleted;
        copy.tasksFailed = tasksFailed;
        copy.repaths = repaths;
        if (counters != null) {
            copy.counters.putAll(counters);
        }
        return copy;
    }

    public void add(BotStatistics other) {
        if (other == null) {
            return;
        }
        workedTicks += Math.max(0L, other.workedTicks);
        blocksBroken += Math.max(0L, other.blocksBroken);
        blocksPlaced += Math.max(0L, other.blocksPlaced);
        foodEaten += Math.max(0L, other.foodEaten);
        tasksCompleted += Math.max(0L, other.tasksCompleted);
        tasksFailed += Math.max(0L, other.tasksFailed);
        repaths += Math.max(0L, other.repaths);
        mergeCounters(other.counters);
    }

    /** Totals ordinary counters and keeps the larger of two {@link #PEAK_PREFIX} marks. */
    private void mergeCounters(Map<String, Long> values) {
        if (values == null) {
            return;
        }
        if (counters == null) {
            counters = new LinkedHashMap<>();
        }
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null) {
                continue;
            }
            long value = Math.max(0L, entry.getValue());
            Long existing = counters.get(key);
            if (existing == null) {
                counters.put(key, value);
            } else if (key.startsWith(PEAK_PREFIX)) {
                counters.put(key, Math.max(existing, value));
            } else {
                counters.put(key, existing + value);
            }
        }
    }
}
