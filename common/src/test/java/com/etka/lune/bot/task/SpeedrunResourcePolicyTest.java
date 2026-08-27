package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeedrunResourcePolicyTest {

    @Test
    void asksOnlyForTheCostsStillAhead() {
        assertEquals(13, SpeedrunResourcePolicy.remainingCobblestone(true, true, true));
        assertEquals(10, SpeedrunResourcePolicy.remainingCobblestone(false, true, true));
        assertEquals(0, SpeedrunResourcePolicy.remainingCobblestone(false, false, false));
    }

    @Test
    void reservesPlanksForTheDragonDefenseOnlyWhenNeeded() {
        assertEquals(14, SpeedrunResourcePolicy.woodProductsNeeded(true));
        assertEquals(8, SpeedrunResourcePolicy.woodProductsNeeded(false));
    }

    @Test
    void resumedResourceHuntsOnlyRequestWhatIsMissing() {
        assertEquals(7, SpeedrunResourcePolicy.remaining(7, 0));
        assertEquals(4, SpeedrunResourcePolicy.remaining(7, 3));
        assertEquals(0, SpeedrunResourcePolicy.remaining(7, 7));
        assertEquals(0, SpeedrunResourcePolicy.remaining(7, 9));
    }

    @Test
    void buysTheAxeAsPartOfTheKitRatherThanOutOfSurplus() {
        // Three cobble more than the same kit without one; the old code only made an axe when
        // something else had already over-gathered, which the budget never does.
        int withoutAxe = SpeedrunResourcePolicy.remainingCobblestone(true, true, false, false);
        int withAxe = SpeedrunResourcePolicy.remainingCobblestone(true, true, false, true);

        assertEquals(withoutAxe + 3, withAxe);
    }

    @Test
    void carryingAnAxeAlreadyCostsNothingExtra() {
        assertEquals(SpeedrunResourcePolicy.remainingCobblestone(false, false, false, false),
                SpeedrunResourcePolicy.remainingCobblestone(false, false, false, false));
        assertEquals(0, SpeedrunResourcePolicy.remainingCobblestone(false, false, false, false));
    }
}
