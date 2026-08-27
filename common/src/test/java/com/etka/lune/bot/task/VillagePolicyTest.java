package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagePolicyTest {

    @Test
    void takesStoneOutOfWallsRatherThanDiggingForIt() {
        assertTrue(VillagePolicy.worthTakingPlacedStone(4, 11));
        assertFalse(VillagePolicy.worthTakingPlacedStone(3, 11));
    }

    @Test
    void oneVisibleBlockIsEnoughWhenOnlyOneIsStillNeeded() {
        assertTrue(VillagePolicy.worthTakingPlacedStone(1, 1));
    }

    @Test
    void doesNotStripAWallItNoLongerNeeds() {
        assertFalse(VillagePolicy.worthTakingPlacedStone(40, 0));
    }

    @Test
    void clearsAFarmUpToTheBaleLimit() {
        assertEquals(12, VillagePolicy.hayWorthTaking(30, 0));
        assertEquals(5, VillagePolicy.hayWorthTaking(5, 0));
        assertEquals(0, VillagePolicy.hayWorthTaking(0, 0));
    }

    @Test
    void leavesTheFarmAloneWhenAlreadyCarryingPlentyOfFood() {
        assertEquals(0, VillagePolicy.hayWorthTaking(30, 64));
    }

    @Test
    void makesAHoeOnlyWhenTheHaulPaysForIt() {
        assertTrue(VillagePolicy.hoeWorthCrafting(12, false, 4, 4));
        assertFalse(VillagePolicy.hoeWorthCrafting(3, false, 4, 4));
        assertFalse(VillagePolicy.hoeWorthCrafting(12, true, 4, 4));
        assertFalse(VillagePolicy.hoeWorthCrafting(12, false, 1, 4));
        assertFalse(VillagePolicy.hoeWorthCrafting(12, false, 4, 1));
    }

    @Test
    void twelveBalesIsThirtySixBread() {
        assertEquals(36, VillagePolicy.breadFrom(12));
        assertEquals(3, VillagePolicy.breadFrom(1));
        assertEquals(0, VillagePolicy.breadFrom(0));
    }

    @Test
    void dropsTheHoeOnlyWhenTheFarmIsDoneAndSpaceIsGone() {
        assertTrue(VillagePolicy.shouldDropHoe(true, 0, true));
        assertFalse(VillagePolicy.shouldDropHoe(true, 4, true));
        assertFalse(VillagePolicy.shouldDropHoe(true, 0, false));
        assertFalse(VillagePolicy.shouldDropHoe(false, 0, true));
    }
}
