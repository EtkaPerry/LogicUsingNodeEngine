package com.etka.lune.bot.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sight ray's block-stepping, which decides whether the bot can see a log through a leaf.
 *
 * <p>The old implementation marched twenty steps of 0.05 - one block of travel - and declared the
 * target hidden if that had not left the cell. A cube is only crossed in one block of travel when
 * the ray runs square down an axis; anything oblique needs up to √3. So looking at the next log up
 * through a single leaf, at any normal angle, reported "not visible", the tree was declared finished
 * after a couple of cuts, and the bot walked off to another one. These are the angles that failed.
 */
class VisionRayTest {

    private static final BlockPos CELL = new BlockPos(10, 64, -5);

    /** Where a ray entering the cell in this direction leaves it. */
    private static Vec3 exit(Vec3 entry, Vec3 direction) {
        return Vision.pastBlock(CELL, entry, direction.normalize());
    }

    private static boolean insideCell(Vec3 point) {
        return BlockPos.containing(point).equals(CELL);
    }

    /** The face of the cell a ray travelling {@code dir} enters through, roughly centred. */
    private static Vec3 entryFaceFor(Vec3 dir) {
        double x = dir.x > 0 ? CELL.getX() : CELL.getX() + 1.0;
        return new Vec3(x, CELL.getY() + 0.5, CELL.getZ() + 0.5);
    }

    @Test
    void aRaySquareDownAnAxisLeavesTheBlock() {
        Vec3 out = exit(new Vec3(CELL.getX(), CELL.getY() + 0.5, CELL.getZ() + 0.5), new Vec3(1, 0, 0));
        assertTrue(out.x > CELL.getX() + 1.0, "should be past the far face, was " + out);
        assertTrue(!insideCell(out));
    }

    /**
     * The regression. A face diagonal needs √2 ≈ 1.41 blocks of travel and a corner-to-corner line
     * needs √3 ≈ 1.73; the old march gave up after 1.0 and called the target hidden.
     */
    @Test
    void anObliqueRayStillLeavesTheBlock() {
        Vec3[] directions = {
                new Vec3(1, 0, 1),      // face diagonal, needs sqrt(2)
                new Vec3(1, 1, 1),      // corner to corner, needs sqrt(3)
                new Vec3(1, -1, 1),
                new Vec3(3, 1, 2),
                new Vec3(1, 0.2, 0.05), // shallow, the case that used to fail most
                new Vec3(0.05, 1, 0.05),
        };
        for (Vec3 dir : directions) {
            Vec3 out = exit(entryFaceFor(dir), dir);
            assertTrue(!insideCell(out), "ray " + dir + " did not leave the block; landed " + out);
        }
    }

    @Test
    void everyDirectionOnASweepLeavesTheBlock() {
        // A full sweep of yaw and pitch: the exit must exist for all of them, not most.
        for (int yaw = 0; yaw < 360; yaw += 7) {
            for (int pitch = -80; pitch <= 80; pitch += 11) {
                double y = Math.toRadians(yaw);
                double p = Math.toRadians(pitch);
                Vec3 dir = new Vec3(Math.cos(p) * Math.cos(y), Math.sin(p), Math.cos(p) * Math.sin(y));
                Vec3 out = exit(entryFaceFor(dir), dir);
                assertTrue(!insideCell(out),
                        "yaw " + yaw + " pitch " + pitch + " stayed inside at " + out);
            }
        }
    }

    @Test
    void theExitIsJustPastTheFaceAndNotAWildJump() {
        // Leaving by a hair matters: overshooting skips whatever is in the next cell, which would
        // let the ray see straight through a wall standing against the leaf.
        for (int yaw = 0; yaw < 360; yaw += 13) {
            double y = Math.toRadians(yaw);
            Vec3 dir = new Vec3(Math.cos(y), 0.35, Math.sin(y)).normalize();
            Vec3 entry = entryFaceFor(dir);
            Vec3 out = exit(entry, dir);
            double travelled = out.subtract(entry).length();
            assertTrue(travelled <= Math.sqrt(3.0) + 0.01,
                    "travelled " + travelled + ", further than the cell's own diagonal");
        }
    }

    @Test
    void aRayStartingOnTheFaceItWouldLeaveByCrossesTheCellInstead() {
        // Entry point on the +X face while travelling +X. Returning "zero distance" here would
        // leave the caller clipping the same block for ever.
        Vec3 entry = new Vec3(CELL.getX() + 1.0, CELL.getY() + 0.5, CELL.getZ() + 0.5);
        Vec3 out = exit(entry, new Vec3(1, 0, 0));
        assertNotEquals(entry, out);
        assertTrue(!insideCell(out));
    }

    @Test
    void aZeroDirectionDoesNotReturnTheSamePoint() {
        Vec3 entry = new Vec3(CELL.getX() + 0.5, CELL.getY() + 0.5, CELL.getZ() + 0.5);
        assertNotEquals(entry, Vision.pastBlock(CELL, entry, Vec3.ZERO));
    }
}
