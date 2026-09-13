package com.etka.lune.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one duration formatter the Main tab's run timer and the Countdown card both read.
 *
 * <p>They used to be two, and the Main tab's stopped at hours - so the day a card offered "Days"
 * as a unit, a two-day countdown would have described itself as "51h 0m 0s".</p>
 */
class DurationsTest {

    @AfterEach
    void backToTheGameDefault() {
        Lang.select(LuneLanguages.GAME_DEFAULT);
    }

    @Test
    void onlyTheUnitsThatHaveSomethingInThemAreShown() {
        assertEquals("0s", Durations.describe(0));
        assertEquals("45s", Durations.describe(45));
        assertEquals("1m 0s", Durations.describe(60));
        assertEquals("20m 0s", Durations.describe(20 * 60));
        assertEquals("1h 0m 0s", Durations.describe(3_600));
        assertEquals("5h 30m 15s", Durations.describe(5 * 3_600 + 30 * 60 + 15));
    }

    /** The tier the Main tab never had, and the reason this class exists. */
    @Test
    void daysAreAUnitAndDropTheSeconds() {
        assertEquals("1d 0h 0m", Durations.describe(86_400));
        assertEquals("2d 0h 0m", Durations.describe(2 * 86_400));
        assertEquals("2d 3h 4m", Durations.describe(2 * 86_400 + 3 * 3_600 + 4 * 60 + 59));
    }

    @Test
    void ticksAreSecondsAtTwentyToOne() {
        assertEquals("1m 0s", Durations.ofTicks(1_200));
        assertEquals("0s", Durations.ofTicks(19), "part of a second is not a second yet");
        assertEquals("1s", Durations.ofTicks(20));
    }

    /** A negative reads as none left rather than as a wrapped-around eternity. */
    @Test
    void negativeDurationsReadAsZero() {
        assertEquals("0s", Durations.describe(-500));
        assertEquals("0s", Durations.ofTicks(-500));
    }

    @Test
    void everyTierHasATurkishLineBehindIt() {
        Lang.select("tr_tr");
        assertEquals("30 sn", Durations.describe(30));
        assertEquals("2 dk 0 sn", Durations.describe(120));
        assertEquals("1 sa 0 dk 0 sn", Durations.describe(3_600));
        assertEquals("2 g 3 sa 0 dk", Durations.describe(2 * 86_400 + 3 * 3_600));
    }
}
