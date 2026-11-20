package com.etka.lune.bot.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelRouteTest {

    @Test
    void theNetherCompressesEightToOne() {
        assertEquals(new BlockPos(10, 64, -3),
                TravelTask.scaledExit("minecraft:overworld", new BlockPos(80, 64, -24)));
        assertEquals(new BlockPos(8, 64, 8),
                TravelTask.scaledExit("minecraft:the_nether", new BlockPos(1, 64, 1)));
    }

    @Test
    void scalingRoundsLikeThePortalForcerNotLikeDivision() {
        // floorDiv: a negative coordinate lands one block further out than x / 8 would put it,
        // which is the column vanilla's link search is built around.
        assertEquals(-2, TravelTask.scaledExit("minecraft:overworld", new BlockPos(-9, 64, 0)).getX());
        assertEquals(-1, TravelTask.scaledExit("minecraft:overworld", new BlockPos(-8, 64, 0)).getX());
    }

    @Test
    void twoRecordsOfOneFrameAreTheSamePortal() {
        BlockPos bottom = new BlockPos(100, 70, 100);
        assertTrue(TravelTask.samePortal(bottom, new BlockPos(102, 73, 100)));
        assertFalse(TravelTask.samePortal(bottom, new BlockPos(110, 70, 100)));
        assertFalse(TravelTask.samePortal(bottom, new BlockPos(100, 80, 100)));
    }

    @Test
    void aTripIsPricedByTheWholeWalkNotJustTheNearEnd() {
        BlockPos feet = new BlockPos(0, 64, 0);
        BlockPos portal = new BlockPos(30, 64, 40);
        BlockPos exit = new BlockPos(100, 64, 100);
        BlockPos target = new BlockPos(160, 64, 100);
        assertEquals(50.0 + 60.0, TravelTask.routeCost(feet, portal, exit, target), 0.001);
    }
}
