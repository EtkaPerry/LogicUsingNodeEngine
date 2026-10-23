package com.etka.lune.bot.path;

import com.etka.lune.bot.LavaMaze;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The step an escape takes, on the lava-maze fixture's own floor plans: walkway over a sea of lava,
 * a hole wherever a maze has a wall, and on the bastion rooms at three heights with the lava level
 * with the lowest of them, lava pockets in their corners and walls between them.
 *
 * <p>The game run is what says whether the bot falls in - a hit, a sprint carried round a corner, a
 * jump that comes up short - and this is the half of it that needs no game: whatever way the bot is
 * running and however far the stall rotation has turned the order, the step it is given lands on
 * floor at that tile's own height, never in lava and never in a hole. And there is always one,
 * because a tile always has floor beside it that it can walk or step to.</p>
 */
class SafeStepLavaMazeTest {

    private static final int FLOOR = 63;
    private static final int DIRECTIONS = 16;
    private static final long SEED = 20261017L;

    /**
     * The maze laid the way the fixture lays it: the flat maze's lava a block under its walkway, the
     * bastion's level with its lowest floor, and every floor and wall on a column of its own.
     */
    static TestLevel lay(LavaMaze maze) {
        TestLevel level = TestLevel.scene();
        int seaTop = maze.style() == LavaMaze.Style.BASTION ? 0 : -1;
        for (int x = -1; x <= maze.size(); x++) {
            for (int z = -1; z <= maze.size(); z++) {
                level.set(x, FLOOR - 2, z, Blocks.STONE);
                for (int dy = -1; dy <= seaTop; dy++) {
                    level.set(x, FLOOR + dy, z, Blocks.LAVA);
                }
                if (!maze.walkway(x, z) && !maze.wall(x, z)) {
                    continue;
                }
                int top = maze.height(x, z);
                for (int dy = 0; dy < top; dy++) {
                    level.set(x, FLOOR + dy, z, Blocks.BLACKSTONE);
                }
                level.set(x, FLOOR + top, z, maze.wall(x, z)
                        ? Blocks.POLISHED_BLACKSTONE_BRICK_WALL : Blocks.POLISHED_BLACKSTONE_BRICKS);
            }
        }
        return level;
    }

    static BlockPos feetOn(LavaMaze maze, int x, int z) {
        return new BlockPos(x, FLOOR + maze.height(x, z) + 1, z);
    }

    private static void everyStepLandsOnTheWalkway(LavaMaze maze) {
        TestLevel level = lay(maze);
        for (int x = 0; x < maze.size(); x++) {
            for (int z = 0; z < maze.size(); z++) {
                if (!maze.walkway(x, z)) {
                    continue;
                }
                BlockPos feet = feetOn(maze, x, z);
                for (int d = 0; d < DIRECTIONS; d++) {
                    double angle = d * Math.PI * 2.0 / DIRECTIONS + 0.1;
                    Vec3 desired = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
                    for (int turn = 0; turn < 8; turn++) {
                        SafeStep.Step step = SafeStep.choose(level, feet, desired, turn);
                        String where = maze.style() + " width " + maze.width() + ", from " + x + "," + z
                                + " running " + d + "/" + DIRECTIONS + ", turn " + turn;
                        assertNotNull(step, "a tile always has floor beside it: " + where);
                        BlockPos landing = step.landing();
                        assertTrue(maze.walkway(landing.getX(), landing.getZ()),
                                "a step into lava or a hole, " + landing.toShortString() + ": " + where);
                        assertEquals(feetOn(maze, landing.getX(), landing.getZ()), landing,
                                "a step to the wrong height: " + where);
                        if (step.dx() != 0 && step.dz() != 0) {
                            assertTrue(maze.walkway(x + step.dx(), z) && maze.walkway(x, z + step.dz()),
                                    "a diagonal across the corner of a hole: " + where);
                        }
                    }
                }
            }
        }
    }

    @Test
    void everyStepOnTheFlatMazeLandsOnTheWalkway() {
        everyStepLandsOnTheWalkway(LavaMaze.generate(LavaMaze.Style.FLAT, 1, SEED));
        everyStepLandsOnTheWalkway(LavaMaze.generate(LavaMaze.Style.FLAT, 2, SEED));
    }

    @Test
    void everyStepOnTheBastionLandsOnFloorAtItsHeight() {
        everyStepLandsOnTheWalkway(LavaMaze.generate(LavaMaze.Style.BASTION, 3, SEED));
        everyStepLandsOnTheWalkway(LavaMaze.generate(LavaMaze.Style.BASTION, 3, 42L));
        everyStepLandsOnTheWalkway(LavaMaze.generate(LavaMaze.Style.BASTION, 3, 7L));
    }

    @Test
    void theWalkwayIsGroundAndNotBesideLava() {
        LavaMaze maze = LavaMaze.generate(1, SEED);
        TestLevel level = lay(maze);
        int[] start = maze.start();
        BlockPos feet = feetOn(maze, start[0], start[1]);
        // The lava is under the walkway, not beside the feet: a rule that counted it would leave
        // no ground on the whole maze, and the escape would stand still on it.
        assertFalse(MovementHelper.nearLava(level, feet));
        assertTrue(MovementHelper.canStandAt(level, feet, false));
        assertTrue(SafeStep.onTheLip(level, feet), "every tile of a one-wide maze has a hole beside it");
    }

    @Test
    void aFloorSetInLavaIsGroundAndItsEdgeIsALip() {
        LavaMaze maze = LavaMaze.generate(LavaMaze.Style.BASTION, 3, SEED);
        TestLevel level = lay(maze);
        // Lava standing level with a floor is beside the floor block, under the feet's corner: not
        // near the feet, so the floor is still somewhere to stand, and a hit from there is a step
        // into lava, so its edge is a lip.
        for (int x = 0; x < maze.size(); x++) {
            for (int z = 0; z < maze.size(); z++) {
                if (maze.walkway(x, z) && maze.height(x, z) == 0) {
                    BlockPos feet = feetOn(maze, x, z);
                    assertFalse(MovementHelper.nearLava(level, feet), feet.toShortString());
                    assertTrue(MovementHelper.canStandAt(level, feet, false), feet.toShortString());
                    if (!maze.walkway(x + 1, z) && !maze.wall(x + 1, z)) {
                        assertTrue(SafeStep.onTheLip(level, feet), feet.toShortString());
                        assertTrue(MovementHelper.isLava(level, new BlockPos(x + 1, FLOOR, z)),
                                "the lava stands level with the lowest floor");
                    }
                }
            }
        }
    }
}
