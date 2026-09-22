package com.etka.lune.bot.util;

import com.etka.lune.bot.task.ConditionTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The readings a Check Player card offers, and the two that answer something other than a count. */
class PlayerMetricTest {

    @Test
    void everyOfferedNameReadsBackToItsMetric() {
        for (String name : PlayerMetric.labels()) {
            assertEquals(name, PlayerMetric.fromLabel(name).label());
        }
        assertEquals(PlayerMetric.values().length, PlayerMetric.labels().size(),
                "the picker should offer every reading");
    }

    /**
     * A saved task naming a reading nobody recognises fails rather than guessing. There is no
     * sensible stand-in for "health" the way there is for a clock, and a card that silently read
     * something else would take the wrong branch forever.
     */
    @Test
    void anUnknownNameIsNotAReading() {
        assertNull(PlayerMetric.fromLabel("Mood"));
        assertNull(PlayerMetric.fromLabel(null));
        assertNull(PlayerMetric.fromLabel(""));
    }

    @Test
    void durabilityIsThePercentLeftOnTheItem() {
        assertEquals(100, PlayerMetric.durabilityPercent(1561, 0));
        assertEquals(50, PlayerMetric.durabilityPercent(100, 50));
        assertEquals(0, PlayerMetric.durabilityPercent(100, 100));
    }

    /**
     * What has no durability bar cannot be about to break, so it reads as full - a torch, a stack
     * of cobble, an empty hand. Select Item already filters on that rule; the two cards would be
     * describing different items if this one disagreed.
     */
    @Test
    void whatCannotBeDamagedReadsAsFull() {
        assertEquals(100, PlayerMetric.durabilityPercent(0, 0));
        assertEquals(100, PlayerMetric.durabilityPercent(-1, 7));
    }

    /** Damage past the maximum is still nothing left, not a negative percent. */
    @Test
    void anOverDamagedItemStopsAtNothingLeft() {
        assertEquals(0, PlayerMetric.durabilityPercent(100, 250));
    }

    /**
     * The whole point of the sentinel: alone in a world, "nobody is within 32" and "everybody is
     * further than 64" must both come out the way a player would predict, for every threshold the
     * card can hold.
     */
    @Test
    void beingAloneIsFurtherAwayThanAnyThresholdTheCardCanHold() {
        for (int threshold = 0; threshold <= 9999; threshold++) {
            assertFalse(ConditionTask.compare(PlayerMetric.NOBODY, "At most", threshold),
                    "nobody should not count as within " + threshold);
            assertTrue(ConditionTask.compare(PlayerMetric.NOBODY, "At least", threshold),
                    "nobody should count as further than " + threshold);
        }
    }
}
