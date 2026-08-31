package com.etka.lune.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotStatisticsStoreTest {

    @Test
    void keepsLastRunSeparateFromAllTimeTotals(@TempDir Path temp) {
        BotStatisticsStore store = new BotStatisticsStore(temp.resolve("statistics.json"));
        BotStatistics first = new BotStatistics();
        first.workedTicks = 40;
        first.foodEaten = 2;
        first.blocksBroken = 8;
        first.tasksCompleted = 1;
        first.counters.put("distance_cm", 125L);
        store.recordRun(first);

        BotStatistics second = new BotStatistics();
        second.workedTicks = 20;
        second.blocksPlaced = 3;
        second.tasksFailed = 1;
        second.counters.put("distance_cm", 75L);
        store.recordRun(second);

        assertEquals(20, store.lastRun().workedTicks);
        assertEquals(2, store.allTime().foodEaten);
        assertEquals(8, store.allTime().blocksBroken);
        assertEquals(3, store.allTime().blocksPlaced);
        assertEquals(1, store.allTime().tasksCompleted);
        assertEquals(1, store.allTime().tasksFailed);
        assertEquals(200L, store.allTime().counter("distance_cm"));

        BotStatistics reloaded = new BotStatisticsStore(temp.resolve("statistics.json"))
                .allTime();
        assertEquals(60, reloaded.workedTicks);
        assertEquals(200L, reloaded.counter("distance_cm"));
    }

    /** A lifetime "longest task" is the longest one ever seen, not the sum of every run's longest. */
    @Test
    void keepsTheLargerOfTwoPeaks(@TempDir Path temp) {
        BotStatisticsStore store = new BotStatisticsStore(temp.resolve("statistics.json"));
        DebugInfo debug = new DebugInfo();
        debug.peak("task_ticks", 400L);
        store.recordRun(BotStatistics.from(debug));

        debug.clearRunStatistics();
        debug.peak("task_ticks", 120L);
        store.recordRun(BotStatistics.from(debug));

        assertEquals(400L, store.allTime().peak("task_ticks"));
        assertEquals(120L, store.lastRun().peak("task_ticks"));
    }

    @Test
    void ignoresCountsThatWouldMakeATotalMeaningless() {
        DebugInfo debug = new DebugInfo();
        debug.count("jumps");
        debug.count("jumps", 4L);
        debug.count("jumps", -3L);
        debug.count("jumps", 0L);
        debug.peak("hit_tenths", 60L);
        debug.peak("hit_tenths", 25L);

        BotStatistics statistics = BotStatistics.from(debug);
        assertEquals(5L, statistics.counter("jumps"));
        assertEquals(60L, statistics.peak("hit_tenths"));
        assertEquals(0L, statistics.counter("never_counted"));
    }
}
