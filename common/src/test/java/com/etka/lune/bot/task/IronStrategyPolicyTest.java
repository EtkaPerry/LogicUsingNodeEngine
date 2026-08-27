package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IronStrategyPolicyTest {

    @Test
    void aKnownStructureOutranksEveryDiggingOption() {
        // Iron somebody else already mined and smelted, sitting in a chest. Nothing done with a
        // pickaxe competes, so this wins even with a cave underfoot and a coast alongside.
        assertEquals(IronStrategyPolicy.Strategy.STRUCTURE,
                IronStrategyPolicy.choose(0, true, false, true, false, true));
        assertEquals(IronStrategyPolicy.Strategy.STRUCTURE,
                IronStrategyPolicy.choose(9, false, false, true, true, true));
    }

    @Test
    void aCoastMeansWrecks_soTheSeaComesBeforeGoingUnderground() {
        // The sea leads to shipwrecks, and their holds hold iron. Going down a hole instead is
        // choosing the slower, more dangerous option while a better one is in sight.
        assertEquals(IronStrategyPolicy.Strategy.SAIL,
                IronStrategyPolicy.choose(0, true, false, true, false, false));
    }

    @Test
    void staysInAVillageRatherThanSailingAwayFromIt() {
        assertEquals(IronStrategyPolicy.Strategy.SCOUT,
                IronStrategyPolicy.choose(0, true, true, false, false, false));
    }

    @Test
    void doesNotSailTwiceInOneRun() {
        assertEquals(IronStrategyPolicy.Strategy.SCOUT,
                IronStrategyPolicy.choose(0, true, false, false, true, false));
    }

    @Test
    void inlandTakesAnOpenCaveOnlyAfterTheSurfaceSearchIsSpent() {
        assertEquals(IronStrategyPolicy.Strategy.SCOUT,
                IronStrategyPolicy.choose(0, false, false, true, false, false));
        assertEquals(IronStrategyPolicy.Strategy.DIVE,
                IronStrategyPolicy.choose(2, false, false, true, false, false));
    }

    @Test
    void inlandWithNoCaveKeepsScouting() {
        assertEquals(IronStrategyPolicy.Strategy.SCOUT,
                IronStrategyPolicy.choose(4, false, false, false, false, false));
    }

    @Test
    void diggingWaitsUntilLookingHasActuallyBeenTried() {
        // The shaft used to fire on any tick with nothing in view, so a run could be underground
        // before it had ever gone looking for a village or a wreck.
        assertFalse(IronStrategyPolicy.mayDigForIron(0, false, false));
        assertFalse(IronStrategyPolicy.mayDigForIron(
                IronStrategyPolicy.SEARCHES_BEFORE_DIGGING - 1, false, false));
        assertTrue(IronStrategyPolicy.mayDigForIron(
                IronStrategyPolicy.SEARCHES_BEFORE_DIGGING, false, false));
    }

    @Test
    void neverDigsWhileSomethingBetterIsAvailable() {
        assertFalse(IronStrategyPolicy.mayDigForIron(99, true, false));
        assertFalse(IronStrategyPolicy.mayDigForIron(99, false, true));
    }

    @Test
    void aKnownSmithyIsReasonEnoughToStay() {
        assertTrue(IronStrategyPolicy.worthStayingForVillage(false, true));
        assertTrue(IronStrategyPolicy.worthStayingForVillage(true, false));
        assertFalse(IronStrategyPolicy.worthStayingForVillage(false, false));
    }
}
