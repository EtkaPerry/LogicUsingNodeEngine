package com.etka.lune.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The step an escape takes when nothing has planned the ground, over hand-built scenes. Every
 * scene here is one a bastion has: a gap in the floor, lava beside the walkway, a bridge with
 * nothing either side of it.
 */
class SafeStepTest {

    private static final BlockPos FEET = new BlockPos(0, 64, 0);
    private static final Vec3 EAST = new Vec3(1.0, 0.0, 0.0);

    /** Stone at y=63 over the inclusive square, so the feet stand at y=64. */
    private static TestLevel floor(int radius) {
        return TestLevel.scene().floor(-radius, radius, -radius, radius, 63);
    }

    @Test
    void openGroundIsSteppedOntoTheWayItWasAskedFor() {
        SafeStep.Step step = SafeStep.choose(floor(3), FEET, EAST, 0);

        assertNotNull(step);
        assertEquals(new BlockPos(1, 64, 0), step.landing());
        assertEquals(SafeStep.Rise.LEVEL, step.rise());
    }

    @Test
    void aGapInTheFloorIsWalkedRoundNotInto() {
        TestLevel level = floor(3).carve(1, 1, 63, 63, 0, 0);

        SafeStep.Step step = SafeStep.choose(level, FEET, EAST, 0);

        assertNotNull(step, "there is floor either side; standing still is not the answer");
        assertEquals(new BlockPos(0, 64, 1), step.landing(),
                "straight on is the hole, and both diagonals cross its corner");
    }

    @Test
    void noStepEndsBesideLava() {
        TestLevel level = floor(3).set(2, 64, 0, Blocks.LAVA);

        SafeStep.Step step = SafeStep.choose(level, FEET, EAST, 0);

        assertNotNull(step);
        assertFalse(MovementHelper.nearLava(level, step.landing()),
                "one knock from there is a step into it");
        assertEquals(new BlockPos(0, 64, 1), step.landing());
    }

    @Test
    void aDiagonalNeverCutsTheCornerOfAHole() {
        TestLevel level = floor(3).carve(1, 1, 63, 63, 0, 0);

        assertNull(SafeStep.along(level, FEET, 1, 1),
                "the body crosses the hole's corner on the way to the far block");
        assertNotNull(SafeStep.along(floor(3), FEET, 1, 1));
    }

    @Test
    void aStepUpNeedsRoomToJump() {
        TestLevel level = floor(3).set(1, 64, 0, Blocks.STONE);

        SafeStep.Step up = SafeStep.along(level, FEET, 1, 0);
        assertNotNull(up);
        assertEquals(SafeStep.Rise.UP, up.rise());
        assertEquals(new BlockPos(1, 65, 0), up.landing());

        level.set(0, 66, 0, Blocks.STONE);
        assertNull(SafeStep.along(level, FEET, 1, 0), "a jump under a ceiling goes nowhere");
    }

    @Test
    void aStepDownNeedsTheGroundPastItAsWell() {
        // Floor at 63 under the feet; one block lower from x=1 on.
        TestLevel level = TestLevel.scene()
                .floor(-2, 0, -2, 2, 63)
                .floor(1, 2, -2, 2, 62);
        SafeStep.Step down = SafeStep.along(level, FEET, 1, 0);
        assertNotNull(down);
        assertEquals(SafeStep.Rise.DOWN, down.rise());
        assertEquals(new BlockPos(1, 63, 0), down.landing());

        // The block it would come down in, one further on, is the top of a long drop.
        TestLevel overTheEdge = TestLevel.scene()
                .floor(-2, 0, -2, 2, 63)
                .floor(1, 1, -2, 2, 62);
        assertNull(SafeStep.along(overTheEdge, FEET, 1, 0),
                "a walk off a ledge lands past the block below it");

        // Unless a wall is there to stop it.
        overTheEdge.fill(2, 2, 63, 64, -2, 2, Blocks.STONE);
        assertNotNull(SafeStep.along(overTheEdge, FEET, 1, 0));
    }

    @Test
    void groundAwayFromALipComesFirst() {
        // Open floor that ends at x=1: past it there is nothing.
        TestLevel level = TestLevel.scene().floor(-3, 1, -3, 3, 63);

        assertTrue(SafeStep.onTheLip(level, new BlockPos(1, 64, 0)));
        SafeStep.Step step = SafeStep.choose(level, FEET, EAST, 0);

        assertNotNull(step);
        assertEquals(new BlockPos(0, 64, 1), step.landing(),
                "a hit knocks the body the way it was going, and that way is over the edge");
    }

    @Test
    void aLipIsStillGroundWhenThereIsNothingElse() {
        // A one-wide bridge over nothing at all.
        TestLevel level = TestLevel.scene().floor(-3, 3, 0, 0, 63);

        SafeStep.Step step = SafeStep.choose(level, FEET, EAST, 0);

        assertNotNull(step, "the bridge is the only way off it");
        assertEquals(new BlockPos(1, 64, 0), step.landing());
    }

    @Test
    void aShallowHollowIsNotALip() {
        TestLevel level = floor(3).carve(2, 2, 63, 63, 0, 0).set(2, 61, 0, Blocks.STONE);

        assertFalse(SafeStep.onTheLip(level, new BlockPos(1, 64, 0)),
                "a fall of two costs nothing");
    }

    @Test
    void aStallTurnsTheOrderOfPreference() {
        assertEquals(new BlockPos(1, 64, 0), SafeStep.choose(floor(3), FEET, EAST, 0).landing());
        assertEquals(new BlockPos(1, 64, 1), SafeStep.choose(floor(3), FEET, EAST, 1).landing(),
                "one turn starts the order at the next way round");
    }

    @Test
    void nowhereSafeIsNoStep() {
        TestLevel level = TestLevel.scene().set(0, 63, 0, Blocks.STONE);

        assertNull(SafeStep.choose(level, FEET, EAST, 0), "a pillar top has no step off it");
    }

    @Test
    void theJumpWaitsForTheFace() {
        SafeStep.Step east = new SafeStep.Step(FEET, 1, 0, SafeStep.Rise.UP, new BlockPos(1, 65, 0));
        assertFalse(SafeStep.atTheFace(0.05, 0.5, 0.3, east), "a jump from back here clears the block");
        assertTrue(SafeStep.atTheFace(0.5, 0.5, 0.3, east));

        SafeStep.Step north = new SafeStep.Step(FEET, 0, -1, SafeStep.Rise.UP, new BlockPos(0, 65, -1));
        assertFalse(SafeStep.atTheFace(0.5, 0.95, 0.3, north));
        assertTrue(SafeStep.atTheFace(0.5, 0.4, 0.3, north));
    }

    @Test
    void aDriftIsHazardousOnlyWhereItHurts() {
        assertFalse(SafeStep.hazardousToward(floor(3), FEET, EAST));
        assertTrue(SafeStep.hazardousToward(floor(3).set(1, 63, 0, Blocks.LAVA), FEET, EAST),
                "a drop floating on lava stays there");
        assertTrue(SafeStep.hazardousToward(floor(3).carve(1, 1, 63, 63, 0, 0), FEET, EAST));
        assertFalse(SafeStep.hazardousToward(
                        floor(3).carve(1, 1, 63, 63, 0, 0).set(1, 62, 0, Blocks.STONE), FEET, EAST),
                "a hollow a drop has rolled into is somewhere a body can go");
        assertFalse(SafeStep.hazardousToward(floor(3).set(1, 64, 0, Blocks.STONE), FEET, EAST),
                "a wall stops the body");
        assertTrue(SafeStep.hazardousToward(floor(3).set(1, 63, 0, Blocks.LAVA), FEET,
                        new Vec3(1.0, 0.0, 1.0)),
                "a diagonal drift crosses both corners");
    }
}
