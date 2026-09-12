package com.etka.lune.bot.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one definition of "what time is it" that the Check Time card and the mascot both read. */
class WorldClockTest {

    private static final List<WorldClock.Phase> BANDS = List.of(WorldClock.Phase.DAY,
            WorldClock.Phase.DUSK, WorldClock.Phase.NIGHT, WorldClock.Phase.DAWN);

    @Test
    void theFourBandsTileTheWholeDayWithoutOverlapping() {
        for (long tick = 0; tick < WorldClock.TICKS_PER_DAY; tick++) {
            long at = tick;
            List<WorldClock.Phase> matched = BANDS.stream()
                    .filter(phase -> phase.matches(at))
                    .toList();
            assertEquals(1, matched.size(),
                    "tick " + tick + " should fall in exactly one band, not " + matched);
        }
    }

    @Test
    void theBandsRunInTheOrderTheDayDoes() {
        assertEquals(WorldClock.Phase.DAY, WorldClock.bandOf(0));
        assertEquals(WorldClock.Phase.DAY, WorldClock.bandOf(6_000));
        assertEquals(WorldClock.Phase.DUSK, WorldClock.bandOf(12_000));
        assertEquals(WorldClock.Phase.NIGHT, WorldClock.bandOf(18_000));
        assertEquals(WorldClock.Phase.DAWN, WorldClock.bandOf(23_000));
        assertEquals(WorldClock.Phase.DAY, WorldClock.bandOf(23_999 + 1));
    }

    /**
     * The bed rule is vanilla's, not a rounding of the bands: sleeping opens partway through dusk
     * and closes partway through dawn. A Check Time card that said "dark enough to sleep" while a
     * bed still refused would be worse than no card at all.
     */
    @Test
    void bedtimeStraddlesTheBandsExactlyWhereVanillaDoes() {
        assertFalse(WorldClock.isNight(12_541), "a bed refuses one tick before 12542");
        assertTrue(WorldClock.isNight(12_542));
        assertTrue(WorldClock.isNight(23_460));
        assertFalse(WorldClock.isNight(23_461), "a bed refuses one tick after 23460");

        assertTrue(WorldClock.Phase.DUSK.matches(12_542),
                "sleeping opens while it is still dusk, which is why the two are separate cards");
        assertTrue(WorldClock.Phase.DAWN.matches(23_460),
                "and closes while it is already dawn");
        assertFalse(WorldClock.isNight(0));
    }

    @Test
    void negativeAndOverlongTicksWrapIntoTheDay() {
        assertEquals(WorldClock.Phase.NIGHT, WorldClock.bandOf(18_000 + WorldClock.TICKS_PER_DAY * 7));
        assertEquals(WorldClock.Phase.NIGHT, WorldClock.bandOf(18_000 - WorldClock.TICKS_PER_DAY * 7));
        assertTrue(WorldClock.isNight(-24_000 + 18_000));
    }

    /** Tick zero is sunrise, which is six in the morning, not midnight. */
    @Test
    void theWallClockReadsTheWayAPlayerExpects() {
        assertEquals("06:00", WorldClock.clock(0));
        assertEquals("12:00", WorldClock.clock(6_000));
        assertEquals("18:00", WorldClock.clock(12_000));
        assertEquals("00:00", WorldClock.clock(18_000));
        assertEquals("18:30", WorldClock.clock(12_500));
        assertEquals("Dusk (18:00)", WorldClock.describe(12_000));
    }

    @Test
    void everyOfferedNameReadsBackToItsPhase() {
        for (String name : WorldClock.phaseNames()) {
            assertNotNull(WorldClock.fromLabel(name), name + " is offered but does not read back");
        }
        assertEquals(WorldClock.Phase.values().length, WorldClock.phaseNames().size(),
                "the picker should offer every phase, including the bed rule");
        assertNull(WorldClock.fromLabel("Teatime"));
        assertNull(WorldClock.fromLabel(null));
    }
}
