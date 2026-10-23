package com.etka.lune.bot;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lava maze's floor plan, which is what makes the test fair: every corridor reachable, a hole
 * wherever a maze has a wall, no corridor wider than asked, and loops to run round.
 */
class LavaMazeTest {

    private static final long[] SEEDS = {1L, 42L, -875873231082164763L, 20261017L, 7L};

    @Test
    void everyCorridorCanBeReachedFromTheStart() {
        for (int width = 1; width <= LavaMaze.MAX_WIDTH; width++) {
            for (long seed : SEEDS) {
                LavaMaze maze = LavaMaze.generate(width, seed);
                int[] start = maze.start();
                assertNotNull(start);
                assertTrue(maze.walkway(start[0], start[1]));
                assertEquals(maze.walkwayCount(), reachable(maze, start),
                        "width " + width + ", seed " + seed + ":\n" + maze.plan());
            }
        }
    }

    @Test
    void theEdgeIsAllHoles() {
        for (int width = 1; width <= LavaMaze.MAX_WIDTH; width++) {
            LavaMaze maze = LavaMaze.generate(width, 42L);
            int last = maze.size() - 1;
            for (int i = 0; i <= last; i++) {
                assertFalse(maze.walkway(i, 0));
                assertFalse(maze.walkway(i, last));
                assertFalse(maze.walkway(0, i));
                assertFalse(maze.walkway(last, i));
            }
            assertFalse(maze.walkway(-1, 3), "outside the maze is not walkway");
            assertFalse(maze.walkway(3, maze.size()));
        }
    }

    @Test
    void whereFourCellsMeetThereIsAlwaysAHole() {
        for (int width = 1; width <= LavaMaze.MAX_WIDTH; width++) {
            for (long seed : SEEDS) {
                LavaMaze maze = LavaMaze.generate(width, seed);
                for (int x = 0; x < maze.size(); x += width + 1) {
                    for (int z = 0; z < maze.size(); z += width + 1) {
                        assertFalse(maze.walkway(x, z),
                                "a corridor turns round a hole, never across a corner: " + x + "," + z);
                    }
                }
            }
        }
    }

    @Test
    void noCorridorIsWiderThanAsked() {
        for (int width = 1; width <= LavaMaze.MAX_WIDTH; width++) {
            for (long seed : SEEDS) {
                LavaMaze maze = LavaMaze.generate(width, seed);
                int square = width + 1;
                for (int x = 0; x + square <= maze.size(); x++) {
                    for (int z = 0; z + square <= maze.size(); z++) {
                        assertFalse(solid(maze, x, z, square),
                                "a " + square + "x" + square + " floor at " + x + "," + z + ":\n" + maze.plan());
                    }
                }
            }
        }
    }

    @Test
    void theDeadEndsAreHalfOpenedIntoLoops() {
        for (long seed : SEEDS) {
            LavaMaze braided = LavaMaze.generate(14, 1, new Random(seed), 0.5);
            LavaMaze tree = LavaMaze.generate(14, 1, new Random(seed), 0.0);
            int cells = 14 * 14;
            assertEquals(cells - 1, openings(tree), "carved as a tree, every cell joined once");
            assertTrue(openings(braided) > cells - 1, "braiding adds the loops a chase runs round");
            assertTrue(deadEnds(braided) < deadEnds(tree));
        }
    }

    @Test
    void theSameSeedLaysTheSameMaze() {
        assertEquals(LavaMaze.generate(1, 99L).plan(), LavaMaze.generate(1, 99L).plan());
        assertNotEquals(LavaMaze.generate(1, 99L).plan(), LavaMaze.generate(1, 100L).plan());
    }

    @Test
    void everyWidthIsAboutTheSameSize() {
        for (int width = 1; width <= LavaMaze.MAX_WIDTH; width++) {
            int size = LavaMaze.generate(width, 5L).size();
            assertTrue(size >= 25 && size <= 33, "width " + width + " is " + size + " across");
        }
        assertEquals(1, LavaMaze.generate(0, 5L).width(), "a width under one is one");
        assertEquals(LavaMaze.MAX_WIDTH, LavaMaze.generate(9, 5L).width());
    }

    @Test
    void aChaserIsPutOnTheWalkwayInsideTheRing() {
        LavaMaze maze = LavaMaze.generate(1, 42L);
        int[] start = maze.start();
        double x = start[0] + 0.5;
        double z = start[1] + 0.5;
        List<int[]> ring = maze.tilesBetween(x, z, 9.0, 16.0);
        assertFalse(ring.isEmpty(), "the keeper's ring has walkway in it");
        for (int[] tile : ring) {
            assertTrue(maze.walkway(tile[0], tile[1]));
            double distance = Math.hypot(tile[0] + 0.5 - x, tile[1] + 0.5 - z);
            assertTrue(distance >= 9.0 && distance <= 16.0, "at " + distance);
        }
    }

    @Test
    void aFlatMazeIsOneIslandOnOneLevel() {
        LavaMaze maze = LavaMaze.generate(1, 42L);
        assertEquals(LavaMaze.Style.FLAT, maze.style());
        assertEquals(1, maze.islandCount());
        int[] start = maze.start();
        assertEquals(0, maze.height(start[0], start[1]));
        assertEquals(-1, maze.height(0, 0), "a hole has no height");
        assertEquals(-1, maze.island(0, 0));
    }

    @Test
    void theBastionCanBeCrossedByJumpsTheRouteSearchMakes() {
        for (long seed : SEEDS) {
            LavaMaze maze = bastion(seed);
            assertEquals(maze.walkwayCount(), reachableJumping(maze, maze.start()),
                    "seed " + seed + ": every island a jump from another\n" + maze.plan());
        }
    }

    @Test
    void theBastionHasToBeJumped() {
        for (long seed : SEEDS) {
            LavaMaze maze = bastion(seed);
            assertTrue(maze.islandCount() >= LavaMaze.MIN_ISLANDS,
                    "seed " + seed + ": nothing but a jump between islands\n" + maze.plan());
            assertTrue(reachable(maze, maze.start()) < maze.walkwayCount(),
                    "walking and stepping alone leave some of it out of reach");
            assertTrue(maze.gaps() > 0 && jumpAcross(maze, 1),
                    "seed " + seed + ": a one-block gap between raised rooms");
            assertTrue(maze.leaps() > 0 && jumpAcross(maze, 2),
                    "seed " + seed + ": a two-block leap, which only a running jump clears");
        }
    }

    @Test
    void aFlatMazeHasNothingToJump() {
        LavaMaze maze = LavaMaze.generate(1, 42L);
        assertEquals(0, maze.gaps());
        assertEquals(0, maze.leaps());
    }

    @Test
    void theBastionKeepsTheMazeRules() {
        for (long seed : SEEDS) {
            LavaMaze maze = bastion(seed);
            int last = maze.size() - 1;
            int stride = LavaMaze.ROOM + 1;
            for (int i = 0; i <= last; i++) {
                assertFalse(maze.walkway(i, 0) || maze.walkway(i, last) || maze.walkway(0, i) || maze.walkway(last, i));
                assertFalse(maze.wall(i, 0) || maze.wall(i, last) || maze.wall(0, i) || maze.wall(last, i),
                        "the edge is lava, never a wall to stand against");
                for (int j = 0; j <= last; j += stride) {
                    if (i % stride == 0) {
                        assertFalse(maze.walkway(i, j), "where four rooms meet is never floor: " + i + "," + j);
                    }
                }
            }
            for (int cx = 0; cx < maze.cells(); cx++) {
                for (int cz = 0; cz < maze.cells(); cz++) {
                    assertTrue(maze.walkway(2 + cx * stride, 2 + cz * stride), "a room keeps its middle");
                }
            }
        }
    }

    @Test
    void floorsMeetAStepApartAndNeverMore() {
        int[][] ways = {{1, 0}, {0, 1}};
        for (long seed : SEEDS) {
            LavaMaze maze = bastion(seed);
            boolean stepped = false;
            for (int x = 0; x < maze.size(); x++) {
                for (int z = 0; z < maze.size(); z++) {
                    if (!maze.walkway(x, z)) {
                        continue;
                    }
                    int height = maze.height(x, z);
                    assertTrue(height >= 0 && height <= LavaMaze.MAX_HEIGHT);
                    for (int[] way : ways) {
                        if (maze.walkway(x + way[0], z + way[1])) {
                            int rise = Math.abs(height - maze.height(x + way[0], z + way[1]));
                            assertTrue(rise <= 1, "a " + rise + "-block wall at " + x + "," + z);
                            stepped |= rise == 1;
                        }
                    }
                }
            }
            assertTrue(stepped, "seed " + seed + ": some rooms are a step apart");
        }
    }

    @Test
    void aWallStandsOutOfReachOfTheFloorBesideIt() {
        int[][] ways = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int walls = 0;
        for (long seed : SEEDS) {
            LavaMaze maze = bastion(seed);
            for (int x = 0; x < maze.size(); x++) {
                for (int z = 0; z < maze.size(); z++) {
                    if (!maze.wall(x, z)) {
                        continue;
                    }
                    walls++;
                    for (int[] way : ways) {
                        if (maze.walkway(x + way[0], z + way[1])) {
                            assertTrue(maze.height(x, z) >= maze.height(x + way[0], z + way[1]) + LavaMaze.WALL_RISE,
                                    "seed " + seed + ": a wall a jump could get onto at " + x + "," + z);
                        }
                    }
                }
            }
        }
        assertTrue(walls > 0, "some joins and corners are walls");
    }

    @Test
    void roomsHaveLavaInTheirCorners() {
        int pockets = 0;
        int corners = 0;
        for (long seed : SEEDS) {
            LavaMaze maze = bastion(seed);
            int stride = LavaMaze.ROOM + 1;
            for (int cx = 0; cx < maze.cells(); cx++) {
                for (int cz = 0; cz < maze.cells(); cz++) {
                    for (int[] corner : new int[][] {{0, 0}, {2, 0}, {0, 2}, {2, 2}}) {
                        corners++;
                        pockets += maze.walkway(1 + cx * stride + corner[0], 1 + cz * stride + corner[1]) ? 0 : 1;
                    }
                }
            }
        }
        double share = pockets / (double) corners;
        assertTrue(share > 0.3 && share < 0.65, "about half the corners are lava: " + share);
    }

    @Test
    void everyIslandHasRoomForAChaser() {
        for (long seed : SEEDS) {
            LavaMaze maze = bastion(seed);
            int stride = LavaMaze.ROOM + 1;
            int[] rooms = new int[maze.islandCount()];
            for (int cx = 0; cx < maze.cells(); cx++) {
                for (int cz = 0; cz < maze.cells(); cz++) {
                    rooms[maze.island(2 + cx * stride, 2 + cz * stride)]++;
                }
            }
            for (int island = 0; island < rooms.length; island++) {
                assertTrue(rooms[island] >= LavaMaze.MIN_ISLAND,
                        "seed " + seed + ": island " + island + " has " + rooms[island] + " rooms");
            }
        }
    }

    @Test
    void aBastionOfAnySizeKeepsEveryRule() {
        for (int rooms : new int[] {LavaMaze.MIN_ROOMS, 9, LavaMaze.MAX_ROOMS}) {
            for (long seed : SEEDS) {
                LavaMaze maze = LavaMaze.bastion(rooms, seed);
                String where = rooms + " rooms, seed " + seed;
                assertEquals(rooms * (LavaMaze.ROOM + 1) + 1, maze.size(), where);
                assertTrue(maze.islandCount() >= LavaMaze.MIN_ISLANDS, where);
                assertTrue(maze.gaps() > 0 && maze.leaps() > 0, where);
                assertEquals(maze.walkwayCount(), reachableJumping(maze, maze.start()), where + "\n" + maze.plan());
            }
        }
        assertEquals(LavaMaze.MIN_ROOMS, LavaMaze.bastion(1, 3L).cells(), "too few rooms is the fewest");
        assertEquals(LavaMaze.MAX_ROOMS, LavaMaze.bastion(40, 3L).cells(), "too many is the most");
    }

    @Test
    void theSevenRoomBastionIsTheOneEveryRunWasMeasuredOn() {
        for (long seed : SEEDS) {
            assertEquals(bastion(seed).plan(), LavaMaze.bastion(LavaMaze.BASTION_ROOMS, seed).plan(),
                    "a seed written down on 2026-10-17 still lays the same course");
        }
    }

    @Test
    void theBastionPlanShowsHeightsAndWalls() {
        String plan = bastion(42L).plan();
        assertTrue(plan.chars().allMatch(c -> c == '~' || c == 'S' || c == 'W' || c == '\n'
                || (c >= '0' && c <= '2')), plan);
        assertTrue(plan.indexOf('W') >= 0, plan);
    }

    private static LavaMaze bastion(long seed) {
        return LavaMaze.generate(LavaMaze.Style.BASTION, 3, seed);
    }

    /** Whether a straight jump crosses {@code holes} blocks of hole between two raised floors of one height. */
    private static boolean jumpAcross(LavaMaze maze, int holes) {
        for (int x = 0; x + holes + 1 < maze.size(); x++) {
            for (int z = 0; z < maze.size(); z++) {
                if (across(maze, x, z, 1, 0, holes) || across(maze, z, x, 0, 1, holes)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean across(LavaMaze maze, int x, int z, int dx, int dz, int holes) {
        int far = holes + 1;
        if (!maze.walkway(x, z) || !maze.walkway(x + dx * far, z + dz * far)
                || maze.height(x, z) < 1 || maze.height(x, z) != maze.height(x + dx * far, z + dz * far)) {
            return false;
        }
        for (int k = 1; k <= holes; k++) {
            if (maze.walkway(x + dx * k, z + dz * k) || maze.wall(x + dx * k, z + dz * k)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void thePlanMarksTheStart() {
        LavaMaze maze = LavaMaze.generate(2, 3L);
        String[] rows = maze.plan().split("\n");
        assertEquals(maze.size(), rows.length);
        int[] start = maze.start();
        assertEquals('S', rows[start[1]].charAt(start[0]));
        assertEquals(1, maze.plan().chars().filter(c -> c == 'S').count());
    }

    /** Walkway tiles a walk can reach from {@code start}, four ways at a time. */
    private static int reachable(LavaMaze maze, int[] start) {
        boolean[] seen = new boolean[maze.size() * maze.size()];
        Deque<int[]> open = new ArrayDeque<>();
        open.add(start);
        seen[start[1] * maze.size() + start[0]] = true;
        int count = 0;
        int[][] ways = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            int[] tile = open.poll();
            count++;
            for (int[] way : ways) {
                int x = tile[0] + way[0];
                int z = tile[1] + way[1];
                if (maze.walkway(x, z) && !seen[z * maze.size() + x]) {
                    seen[z * maze.size() + x] = true;
                    open.add(new int[] {x, z});
                }
            }
        }
        return count;
    }

    /**
     * Walkway tiles the bot can reach from {@code start} with the moves its route search plans:
     * walking and stepping, and a jump straight over one or two blocks of hole, landing level or a
     * block down, as {@code AStarPathfinder} jumps a gap. Never from the lowest floor, where the
     * hole is lava to the brim and the search will not jump over lava at its feet, and never over a
     * wall.
     */
    private static int reachableJumping(LavaMaze maze, int[] start) {
        boolean[] seen = new boolean[maze.size() * maze.size()];
        Deque<int[]> open = new ArrayDeque<>();
        open.add(start);
        seen[start[1] * maze.size() + start[0]] = true;
        int count = 0;
        int[][] ways = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            int[] tile = open.poll();
            count++;
            int height = maze.height(tile[0], tile[1]);
            for (int[] way : ways) {
                for (int distance = 1; distance <= 3; distance++) {
                    int x = tile[0] + distance * way[0];
                    int z = tile[1] + distance * way[1];
                    if (!maze.walkway(x, z) || seen[z * maze.size() + x]) {
                        continue;
                    }
                    if (distance > 1) {
                        boolean overHoles = height >= 1;
                        for (int k = 1; k < distance; k++) {
                            int hx = tile[0] + k * way[0];
                            int hz = tile[1] + k * way[1];
                            overHoles &= !maze.walkway(hx, hz) && !maze.wall(hx, hz);
                        }
                        int drop = height - maze.height(x, z);
                        if (!overHoles || drop < 0 || drop > 1) {
                            continue;
                        }
                    }
                    seen[z * maze.size() + x] = true;
                    open.add(new int[] {x, z});
                }
            }
        }
        return count;
    }

    private static boolean solid(LavaMaze maze, int x0, int z0, int square) {
        for (int x = x0; x < x0 + square; x++) {
            for (int z = z0; z < z0 + square; z++) {
                if (!maze.walkway(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Joins between cells, read off the plan of a maze with one-block corridors. */
    private static int openings(LavaMaze maze) {
        int joins = 0;
        for (int x = 0; x < maze.size(); x++) {
            for (int z = 0; z < maze.size(); z++) {
                // Exactly one coordinate even: a gap between two cells, walkway when they are joined.
                if ((x % 2 == 0) != (z % 2 == 0) && maze.walkway(x, z)) {
                    joins++;
                }
            }
        }
        return joins;
    }

    /** Cells of a one-wide maze with a single way out. */
    private static int deadEnds(LavaMaze maze) {
        int count = 0;
        for (int x = 1; x < maze.size(); x += 2) {
            for (int z = 1; z < maze.size(); z += 2) {
                int ways = 0;
                ways += maze.walkway(x + 1, z) ? 1 : 0;
                ways += maze.walkway(x - 1, z) ? 1 : 0;
                ways += maze.walkway(x, z + 1) ? 1 : 0;
                ways += maze.walkway(x, z - 1) ? 1 : 0;
                count += ways == 1 ? 1 : 0;
            }
        }
        return count;
    }
}
