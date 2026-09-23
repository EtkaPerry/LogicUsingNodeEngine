package com.etka.lune.bot.task;

import com.etka.lune.bot.task.FirePolicy.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirePolicyTest {

    /** A player standing square on the block at (10, 63, 10), feet at 64. */
    private static final AABB STANDING = body(10.5, 64.0, 10.5);

    private static AABB body(double x, double feetY, double z) {
        return new AABB(x - 0.3, feetY, z - 0.3, x + 0.3, feetY + 1.8, z + 0.3);
    }

    @Test
    void theBucketWinsWhereverItWorks() {
        assertEquals(Method.POUR, FirePolicy.choose(true, true));
        assertEquals(Method.POUR, FirePolicy.choose(true, false));
    }

    @Test
    void withoutAPourThatStaysTheBotRunsForWater() {
        // The Nether with a bucket in the bag and no bucket at all look the same from here: the
        // pour is off the table, and water in sight is what is left.
        assertEquals(Method.RUN, FirePolicy.choose(false, true));
    }

    @Test
    void nothingToPourAndNoWaterInSightLeavesTheFireAlone() {
        assertEquals(Method.NONE, FirePolicy.choose(false, false));
    }

    @Test
    void waterPouredAtTheFeetReachesTheBody() {
        assertTrue(FirePolicy.reachesBody(STANDING, new BlockPos(10, 64, 10)));
    }

    @Test
    void waterSoakedIntoTheFloorDoesNot() {
        // Leaves or a full slab underfoot take the water inside themselves, a whole block below
        // the feet. That is the pour the crouch exists to prevent.
        assertFalse(FirePolicy.reachesBody(STANDING, new BlockPos(10, 63, 10)));
    }

    @Test
    void waterPouredAHeadAboveStillCounts() {
        // Grass or a flame at the feet moves the pour up a cell, which is still inside the body.
        assertTrue(FirePolicy.reachesBody(STANDING, new BlockPos(10, 65, 10)));
        assertFalse(FirePolicy.reachesBody(STANDING, new BlockPos(10, 66, 10)));
    }

    @Test
    void aBottomSlabHoldsWaterTheBodyIsStandingIn() {
        // Feet at 64.5 on the slab; the water fills the slab's own cell up to 64.89.
        assertTrue(FirePolicy.reachesBody(body(10.5, 64.5, 10.5), new BlockPos(10, 64, 10)));
    }

    @Test
    void straddlingTwoColumnsReachesWaterInEitherButNotBeyond() {
        AABB straddling = body(11.0, 64.0, 10.5);
        assertTrue(FirePolicy.reachesBody(straddling, new BlockPos(10, 64, 10)));
        assertTrue(FirePolicy.reachesBody(straddling, new BlockPos(11, 64, 10)));
        assertFalse(FirePolicy.reachesBody(straddling, new BlockPos(12, 64, 10)));
    }
}
