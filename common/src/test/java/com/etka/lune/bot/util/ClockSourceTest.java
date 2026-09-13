package com.etka.lune.bot.util;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which clock a Check Clock card is reading, and that both of them read the same way. */
class ClockSourceTest {

    @Test
    void everyOfferedNameReadsBackToItsClock() {
        for (String name : ClockSource.labels()) {
            assertEquals(name, ClockSource.fromLabel(name).label());
        }
        assertEquals(ClockSource.values().length, ClockSource.labels().size(),
                "the picker should offer every clock");
    }

    /**
     * A saved task naming a clock that no longer exists still runs, on the clock that is always
     * available. Refusing would mean a card that silently never fires.
     */
    @Test
    void anUnknownNameFallsBackToTheSystemClock() {
        assertEquals(ClockSource.SYSTEM, ClockSource.fromLabel("Sundial"));
        assertEquals(ClockSource.SYSTEM, ClockSource.fromLabel(null));
        assertEquals(ClockSource.SYSTEM, ClockSource.fromLabel(""));
    }

    /**
     * No world to ask reads as tick zero rather than throwing, and tick zero is six in the
     * morning. {@link com.etka.lune.bot.BotContext} never hands a task a null level, so this is
     * about the card being asked outside a run rather than about a case a bot can reach.
     */
    @Test
    void theGameClockWithoutAWorldReadsSunrise() {
        assertEquals(6 * 60L, ClockSource.GAME.minuteOfDay(null));
    }

    @Test
    void theSystemClockAgreesWithTheComputersOwnClock() {
        LocalTime before = LocalTime.now();
        long reading = ClockSource.SYSTEM.minuteOfDay(null);
        LocalTime after = LocalTime.now();
        long low = before.getHour() * 60L + before.getMinute();
        long high = after.getHour() * 60L + after.getMinute();
        // Crossing a minute between the two samples is fine; crossing midnight makes low > high.
        assertTrue(low > high || (reading >= low && reading <= high),
                "system clock read " + reading + ", expected between " + low + " and " + high);
        assertTrue(reading >= 0L && reading < 24L * 60L, "a clock reading is inside one day");
    }

    /** The in-game hour a player reads off the sky, as a number a card can compare. */
    @Test
    void theGameClockCountsFromMidnightTheWayTheWallClockDoes() {
        assertEquals(6 * 60L, WorldClock.minuteOfDay(0), "tick zero is six in the morning");
        assertEquals(12 * 60L, WorldClock.minuteOfDay(6_000));
        assertEquals(18 * 60L, WorldClock.minuteOfDay(12_000));
        assertEquals(0L, WorldClock.minuteOfDay(18_000));
        assertEquals(18 * 60L + 30L, WorldClock.minuteOfDay(12_500));
    }

    /** One definition of the reading, so the rendered clock and the compared number cannot drift. */
    @Test
    void theRenderedClockIsTheNumberTheCardCompares() {
        for (long tick = 0; tick < WorldClock.TICKS_PER_DAY; tick += 37) {
            assertEquals(WorldClock.clock(tick), WorldClock.clockOf(WorldClock.minuteOfDay(tick)),
                    "tick " + tick);
        }
    }

    @Test
    void minutesPastMidnightRenderAsAWallClock() {
        assertEquals("00:00", WorldClock.clockOf(0));
        assertEquals("20:00", WorldClock.clockOf(20 * 60));
        assertEquals("23:59", WorldClock.clockOf(23 * 60 + 59));
        assertEquals("00:00", WorldClock.clockOf(24 * 60), "a full day wraps to midnight");
    }
}
