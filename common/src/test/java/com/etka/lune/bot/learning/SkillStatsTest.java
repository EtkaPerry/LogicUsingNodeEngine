package com.etka.lune.bot.learning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillStatsTest {

    private static SkillStats.Row row(String phase, long runs, long completed, long units,
                                      long ticks, double best, long lastUnits, long lastTicks,
                                      long lastOutcome) {
        return new SkillStats.Row(
                new LearningContext("skill", "tree-chopping", "minecraft:overworld", phase),
                runs, completed, units, ticks, best, lastUnits, lastTicks, lastOutcome, null);
    }

    /**
     * The average is pooled over the work, not averaged over the rows: a situation the bot has
     * been in four hundred times must outweigh one it has seen twice.
     */
    @Test
    void poolsTheWorkAndTakesTheNewestLastAndTheFastestBest() {
        SkillStats stats = SkillStats.of(List.of(
                row("tool=hand", 2, 2, 10, 400, 40.0, 5, 200, 7),
                row("tool=axe", 400, 390, 3900, 39_000, 8.0, 12, 96, 9)));

        assertTrue(stats.measured());
        assertEquals(402, stats.runs());
        assertEquals(392, stats.completedRuns());
        // 3910 units in 39400 ticks: 1.98 a second, nowhere near the mean of the two rows' rates.
        assertEquals(3910 * 20.0 / 39_400, stats.averageUnitsPerSecond(), 1e-9);
        assertEquals(8.0, stats.bestTicksPerUnit());
        assertEquals(2.5, stats.bestUnitsPerSecond(), 1e-9);
        // Outcome nine came after outcome seven, so the axe row is "last time".
        assertTrue(stats.hasLast());
        assertEquals(12 * 20.0 / 96, stats.lastUnitsPerSecond(), 1e-9);
        // Busiest first.
        assertEquals("tool=axe", stats.rows().get(0).context().phase());
        assertEquals("tool=hand", stats.rows().get(1).context().phase());
    }

    @Test
    void aRowThatNeverFinishedCountsItsRunsAndHasNoRate() {
        SkillStats.Row failing = row("tool=hand", 40, 0, 0, 0, 0.0, 0, 0, 0);
        SkillStats stats = SkillStats.of(List.of(failing));

        assertFalse(failing.measured());
        assertFalse(stats.measured());
        assertEquals(40, stats.runs());
        assertEquals(0.0, stats.averageUnitsPerSecond());
        assertFalse(stats.hasLast());
    }

    /** Rows written before "last time" was kept have their totals and no last; say so, not "0". */
    @Test
    void anOlderProfileHasTotalsButNoLastTime() {
        SkillStats stats = SkillStats.of(List.of(row("tool=hand", 3, 3, 30, 600, 15.0, 0, 0, 0)));

        assertTrue(stats.measured());
        assertFalse(stats.hasLast());
        assertEquals(0.0, stats.lastUnitsPerSecond());
        assertEquals(1.0, stats.averageUnitsPerSecond(), 1e-9);
        assertEquals(1.0, stats.averageSecondsPerUnit(), 1e-9);
        assertEquals(0.75, stats.bestSecondsPerUnit(), 1e-9);
    }

    @Test
    void nothingIsTheOneSharedEmptyAnswer() {
        assertSame(SkillStats.EMPTY, SkillStats.of(List.of()));
        assertSame(SkillStats.EMPTY, SkillStats.of(null));
        assertFalse(SkillStats.EMPTY.measured());
    }

    /** Two decimals where they mean something, none where they would only cost the card width. */
    @Test
    void formatSpendsDigitsWhereTheyMatter() {
        assertEquals("1.39", SkillStats.format(1.39));
        assertEquals("3.30", SkillStats.format(3.3));
        assertEquals("12.3", SkillStats.format(12.34));
        assertEquals("123", SkillStats.format(123.4));
        assertEquals("0.00", SkillStats.format(-1.0), "a negative pace is a bug upstream, not a sign");
    }
}
