package com.etka.lune.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one step of a route has to clear, in the order the executor clears it. A route read for the
 * block it would stop at has to meet the blocks in the same order the walk does, or the reason it
 * gives is a block the bot never reached.
 */
class MovementHelperTest {

    @Test
    void anOpenStepHasNothingInTheWay() {
        TestLevel level = TestLevel.scene().floor(0, 1, 0, 0, 63);

        assertTrue(MovementHelper.stepClearance(level, new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0)).isEmpty());
    }

    @Test
    void aStepUpClearsTheHeadroomBeforeTheBlockAhead() {
        TestLevel level = TestLevel.scene()
                .floor(0, 1, 0, 0, 63)
                .fill(1, 1, 64, 66, 0, 0, Blocks.STONE)
                .set(0, 66, 0, Blocks.DIRT);

        assertEquals(List.of(new BlockPos(0, 66, 0), new BlockPos(1, 65, 0), new BlockPos(1, 66, 0)),
                MovementHelper.stepClearance(level, new BlockPos(0, 64, 0), new BlockPos(1, 65, 0)));
    }

    /** The corners come first because the player clips them on the way, before arriving. */
    @Test
    void aDiagonalClearsItsCornersBeforeTheFarEnd() {
        TestLevel level = TestLevel.scene()
                .floor(0, 1, 0, 1, 63)
                .set(1, 65, 0, Blocks.OAK_LEAVES)
                .set(0, 64, 1, Blocks.DIRT)
                .set(1, 64, 1, Blocks.STONE);

        assertEquals(List.of(new BlockPos(1, 65, 0), new BlockPos(0, 64, 1), new BlockPos(1, 64, 1)),
                MovementHelper.stepClearance(level, new BlockPos(0, 64, 0), new BlockPos(1, 64, 1)));
    }

    /**
     * A fence, a wall and a shut gate stand a block and a half high, so nothing stands on one with
     * its feet in the block above: that is half a block into it, and no step or jump gets there.
     */
    @Test
    void nothingStandsOnAFenceAWallOrAShutGate() {
        TestLevel level = TestLevel.scene()
                .floor(0, 3, 0, 0, 63)
                .set(0, 64, 0, Blocks.OAK_FENCE)
                .set(1, 64, 0, Blocks.COBBLESTONE_WALL)
                .set(2, 64, 0, Blocks.OAK_FENCE_GATE)
                .set(3, 64, 0, Blocks.STONE);

        for (int x = 0; x < 3; x++) {
            assertFalse(MovementHelper.canStandAt(level, new BlockPos(x, 65, 0)),
                    level.getBlockState(new BlockPos(x, 64, 0)) + " is not a floor");
        }
        assertTrue(MovementHelper.canStandAt(level, new BlockPos(3, 65, 0)), "stone still is");
    }

    @Test
    void theBlockARouteStopsAtIsTheFirstItCannotDigInDiggingOrder() {
        // Down a shaft: dirt, then stone, then dirt again under it.
        TestLevel level = TestLevel.scene()
                .fill(0, 0, 60, 63, 0, 0, Blocks.DIRT)
                .set(0, 61, 0, Blocks.STONE);
        List<BlockPos> shaft = List.of(new BlockPos(0, 64, 0), new BlockPos(0, 63, 0),
                new BlockPos(0, 62, 0), new BlockPos(0, 61, 0), new BlockPos(0, 60, 0));

        assertEquals(new BlockPos(0, 61, 0), MovementHelper.firstUndiggable(level, shaft,
                state -> !state.requiresCorrectToolForDrops()));
        assertNull(MovementHelper.firstUndiggable(level, shaft, state -> true),
                "with the right tool nothing on it stops the walk");
    }
}
