package com.etka.lune.bot.path;

import com.etka.lune.bot.LavaMaze;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real route search on the lava maze's bastion, which is what Self Preservation's run leans on
 * with fighting back switched off: a step only ever looks one block away, and on the bastion one
 * block away is often lava or a wall, so getting away from a chaser is a route - round it, along a
 * bridge, over a gap to another island.
 *
 * <p>Every island has to be reachable that way from the start, with a jump to cross to it, and no
 * route may set a foot anywhere but on floor: the bastion's lava stands level with its lowest
 * floor, so a waypoint off the floor is a waypoint in lava.</p>
 */
class LavaMazeRouteTest {

    private static final long[] SEEDS = {20261017L, 42L, 7L, 5595022547495685603L};

    @Test
    void aRouteReachesEveryIslandOfTheBastionByJumping() {
        int routes = 0;
        for (long seed : SEEDS) {
            // The nine-room course too, which is the one three Zombies are dropped on.
            LavaMaze maze = seed == 42L ? LavaMaze.bastion(9, seed) : LavaMaze.generate(LavaMaze.Style.BASTION, 3, seed);
            TestLevel level = SafeStepLavaMazeTest.lay(maze);
            int[] start = maze.start();
            BlockPos from = SafeStepLavaMazeTest.feetOn(maze, start[0], start[1]);
            int home = maze.island(start[0], start[1]);
            for (int island = 0; island < maze.islandCount(); island++) {
                if (island == home) {
                    continue;
                }
                int[] tile = middleOf(maze, island);
                BlockPos to = SafeStepLavaMazeTest.feetOn(maze, tile[0], tile[1]);
                AStarPathfinder.Result result = AStarPathfinder.find(level, from, new Goals.Block(to),
                        AStarPathfinder.Settings.walking());
                String where = "seed " + seed + ", from " + from.toShortString() + " to island " + island
                        + " at " + to.toShortString();
                assertTrue(result.reachedGoal(), "no route: " + where + "\n" + maze.plan());
                assertTrue(longestStride(result.path()) >= 2, "another island without a jump: " + where);
                routes++;
                for (BlockPos node : result.path()) {
                    assertTrue(maze.walkway(node.getX(), node.getZ()),
                            "a waypoint off the floor, " + node.toShortString() + ": " + where);
                    assertFalse(level.getBlockState(node.below()).is(Blocks.LAVA),
                            "a waypoint on lava, " + node.toShortString() + ": " + where);
                }
            }
        }
        assertTrue(routes >= 2 * SEEDS.length, "every bastion has at least three islands to route to: " + routes);
    }

    /** The floor tile of an island nearest its middle, as {@code {x, z}}. */
    private static int[] middleOf(LavaMaze maze, int island) {
        double sx = 0;
        double sz = 0;
        int count = 0;
        for (int x = 0; x < maze.size(); x++) {
            for (int z = 0; z < maze.size(); z++) {
                if (maze.island(x, z) == island) {
                    sx += x;
                    sz += z;
                    count++;
                }
            }
        }
        double mx = sx / count;
        double mz = sz / count;
        List<int[]> tiles = maze.tilesBetween(mx + 0.5, mz + 0.5, 0.0, Double.MAX_VALUE, island);
        int[] best = tiles.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (int[] tile : tiles) {
            double distance = (tile[0] - mx) * (tile[0] - mx) + (tile[1] - mz) * (tile[1] - mz);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = tile;
            }
        }
        return best;
    }

    /** The largest step between consecutive waypoints, which is what says a jump happened. */
    private static int longestStride(List<BlockPos> path) {
        int longest = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            longest = Math.max(longest, Math.abs(b.getX() - a.getX()) + Math.abs(b.getZ() - a.getZ()));
        }
        return longest;
    }
}
