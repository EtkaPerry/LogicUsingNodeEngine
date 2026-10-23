package com.etka.lune.bot.task;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stepping round a tree before the next throw, on a flat field with one thing in the way. */
class EnderEyeClearingTest {

    private static final BlockPos HERE = new BlockPos(0, 70, 0);
    /** Flat ground everywhere: every column is stood on at the height asked for. */
    private static final EnderEyeClearing.Floor FLAT = (x, y, z) -> new BlockPos(x, y, z);

    /** A sight line is blocked when it crosses a wall at x in [2, 3] between the z given. */
    private static EnderEyeClearing.Sight wall(double minZ, double maxZ, double top) {
        return (from, to) -> {
            // Sample the line finely enough that a block cannot be stepped over.
            int steps = (int) Math.ceil(from.distanceTo(to) / 0.25);
            for (int step = 0; step <= steps; step++) {
                Vec3 point = from.lerp(to, steps == 0 ? 0.0 : step / (double) steps);
                if (point.x >= 2.0 && point.x <= 4.0 && point.z >= minZ && point.z <= maxZ
                        && point.y <= top) {
                    return false;
                }
            }
            return true;
        };
    }

    @Test
    void anOpenViewMeansNoMove() {
        assertNull(EnderEyeClearing.choose(HERE, 1.0, 0.0, FLAT, (from, to) -> true));
    }

    @Test
    void aTreeInTheWayIsSteppedRound() {
        // A trunk and crown two blocks east, three wide, up to y 90: nothing thrown east from here
        // is seen past it, and a step a few blocks to either side clears it.
        EnderEyeClearing.Sight tree = wall(-1.5, 1.5, 90.0);
        assertEquals(0, EnderEyeClearing.seenFrom(HERE, 1.0, 0.0, tree));
        EnderEyeClearing.Spot spot = EnderEyeClearing.choose(HERE, 1.0, 0.0, FLAT, tree);
        assertNotNull(spot);
        assertEquals(EnderEyeClearing.SAMPLES.length, spot.seen(), "the chosen spot sees the whole flight");
        assertTrue(spot.distance() <= 5, "a few blocks, not a march: " + spot);
        assertTrue(Math.abs(spot.feet().getZ()) >= 2, "it steps to the side of the tree: " + spot);
    }

    @Test
    void aLowHedgeIsSeenOverFromFurtherBack() {
        // Something only up to the eyes' own height: the flight climbs over it, and the first
        // sample is the only one lost. Nowhere nearby sees more than here does, so no move.
        EnderEyeClearing.Sight hedge = wall(-20.0, 20.0, 72.0);
        int here = EnderEyeClearing.seenFrom(HERE, 1.0, 0.0, hedge);
        assertTrue(here >= EnderEyeClearing.SAMPLES.length - 1, "seen from here: " + here);
    }

    @Test
    void nowhereToStandMeansNoMove() {
        EnderEyeClearing.Sight tree = wall(-1.5, 1.5, 90.0);
        assertNull(EnderEyeClearing.choose(HERE, 1.0, 0.0, (x, y, z) -> null, tree));
    }

    @Test
    void theBestPartialViewIsTakenWhenNothingSeesItAll() {
        // A wall across the whole way, higher than the flight: the spots along the way see less
        // of it the nearer they stand, so the choice is one that sees more than here, or none.
        EnderEyeClearing.Sight cliff = wall(-40.0, 40.0, 200.0);
        EnderEyeClearing.Spot spot = EnderEyeClearing.choose(HERE, 1.0, 0.0, FLAT, cliff);
        if (spot != null) {
            assertTrue(spot.seen() > EnderEyeClearing.seenFrom(HERE, 1.0, 0.0, cliff));
        }
    }
}
