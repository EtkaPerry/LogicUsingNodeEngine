package com.etka.lune.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real search over hand-built terrain. See {@link TestLevel} for why these are scenes rather
 * than a restatement of the search's own rules.
 */
class AStarPathfinderTest {

    private static AStarPathfinder.Result walk(TestLevel level, BlockPos from, BlockPos to) {
        return AStarPathfinder.find(level, from, new Goals.Block(to),
                AStarPathfinder.Settings.walking());
    }

    /** The largest step between consecutive waypoints, which is what says a jump happened. */
    private static int longestStride(List<BlockPos> path) {
        int longest = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            longest = Math.max(longest,
                    Math.abs(b.getX() - a.getX()) + Math.abs(b.getZ() - a.getZ()));
        }
        return longest;
    }

    @Test
    void itWalksAcrossOpenGround() {
        TestLevel level = TestLevel.scene().floor(0, 8, 0, 0, 63);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(8, 64, 0));

        assertTrue(result.reachedGoal());
        assertEquals(new BlockPos(8, 64, 0), result.path().get(result.path().size() - 1));
        assertEquals(1, longestStride(result.path()), "flat ground needs no jumping");
    }

    @Test
    void itJumpsATwoBlockGapRatherThanFailing() {
        // Ledge, two blocks of nothing, ledge. No way round: the strip is one block wide.
        TestLevel level = TestLevel.scene()
                .floor(0, 1, 0, 0, 63)
                .floor(4, 6, 0, 0, 63);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(6, 64, 0));

        assertTrue(result.reachedGoal(), "a person clears this without breaking stride");
        assertTrue(result.path().contains(new BlockPos(4, 64, 0)), "must land on the far ledge");
        assertEquals(3, longestStride(result.path()), "crossed in one stride, not walked round");
    }

    /**
     * The half of the move that the executor used to refuse. The search has always planned these;
     * see {@link GapJumpPolicy} for what the disagreement cost.
     */
    @Test
    void itJumpsAGapOntoALedgeOneBlockLower() {
        TestLevel level = TestLevel.scene()
                .floor(0, 1, 0, 0, 63)
                .floor(4, 6, 0, 0, 62);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(6, 63, 0));

        assertTrue(result.reachedGoal());
        assertTrue(result.path().contains(new BlockPos(4, 63, 0)),
                "the landing is a block lower, which is a jump the search plans deliberately");
    }

    @Test
    void itWalksRoundAGapTooWideToJump() {
        // Four blocks of nothing ahead, but a detour exists one row over.
        TestLevel level = TestLevel.scene()
                .floor(0, 8, 1, 1, 63)
                .floor(0, 1, 0, 0, 63)
                .floor(6, 8, 0, 0, 63);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(8, 64, 0));

        assertTrue(result.reachedGoal());
        assertTrue(result.path().stream().anyMatch(pos -> pos.getZ() == 1),
                "the only way across a four-block hole is around it");
    }

    @Test
    void itDoesNotJumpAGapItCannotLandOn() {
        // Take-off, a hole, and nothing at all on the far side.
        TestLevel level = TestLevel.scene().floor(0, 1, 0, 0, 63);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(6, 64, 0));

        assertFalse(result.reachedGoal(), "there is nowhere to land, so there is no route");
        assertTrue(result.path().stream().noneMatch(pos -> pos.getX() > 1),
                "it must not plan to step out over the hole");
    }

    /**
     * A strip with a two-block gap, and a walk round it three blocks out. Over a trench the jump is
     * the short way and is taken; over nothing - the lava sea under a bastion, the void round the
     * End - a jump that comes up short is the end of the run, and the walk round is taken instead.
     */
    private static TestLevel gapWithAWayRound() {
        return TestLevel.scene()
                .floor(0, 1, 0, 0, 63)
                .floor(4, 6, 0, 0, 63)
                .floor(1, 1, 1, 3, 63)
                .floor(1, 4, 3, 3, 63)
                .floor(4, 4, 1, 3, 63);
    }

    @Test
    void itWalksRoundAGapItWouldHurtToMiss() {
        AStarPathfinder.Result result = walk(gapWithAWayRound(),
                new BlockPos(0, 64, 0), new BlockPos(6, 64, 0));

        assertTrue(result.reachedGoal());
        assertTrue(longestStride(result.path()) < 3, "no jump over a fall that hurts");
        assertTrue(result.path().stream().anyMatch(pos -> pos.getZ() == 3),
                "the walk round is a few blocks longer and nothing worse");
    }

    @Test
    void itStillJumpsAGapOverATrench() {
        TestLevel level = gapWithAWayRound().floor(2, 3, 0, 0, 61);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(6, 64, 0));

        assertTrue(result.reachedGoal());
        assertEquals(3, longestStride(result.path()),
                "missing it is a two-block drop, which costs nothing, so the short way wins");
    }

    @Test
    void itStepsUpASingleBlockButNotTwo() {
        TestLevel oneUp = TestLevel.scene()
                .floor(0, 2, 0, 0, 63)
                .floor(3, 5, 0, 0, 64);
        assertTrue(walk(oneUp, new BlockPos(0, 64, 0), new BlockPos(5, 65, 0)).reachedGoal(),
                "a one-block step is an ordinary jump");

        TestLevel twoUp = TestLevel.scene()
                .floor(0, 2, 0, 0, 63)
                .fill(3, 5, 63, 64, 0, 0, Blocks.STONE);
        assertFalse(walk(twoUp, new BlockPos(0, 64, 0), new BlockPos(5, 66, 0)).reachedGoal(),
                "nobody jumps two blocks; that needs a block placed or a way round");
    }

    @Test
    void aWalkingRouteWillNotTunnelThroughRock() {
        TestLevel level = TestLevel.scene()
                .floor(0, 8, 0, 0, 63)
                .fill(4, 4, 64, 65, 0, 0, Blocks.STONE);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(8, 64, 0));

        assertFalse(result.reachedGoal(), "Goto must not mine; the wall seals the corridor");
    }

    @Test
    void aMiningRouteDigsThroughTheSameWall() {
        TestLevel level = TestLevel.scene()
                .floor(0, 8, 0, 0, 63)
                .fill(4, 4, 64, 65, 0, 0, Blocks.STONE);

        AStarPathfinder.Result result = AStarPathfinder.find(level, new BlockPos(0, 64, 0),
                new Goals.Block(new BlockPos(8, 64, 0)), AStarPathfinder.Settings.mining());

        assertTrue(result.reachedGoal());
        assertTrue(result.path().contains(new BlockPos(4, 64, 0)), "straight through the wall");
    }

    @Test
    void itRefusesToWalkIntoLava() {
        TestLevel level = TestLevel.scene()
                .floor(0, 8, 0, 0, 63)
                .set(4, 64, 0, Blocks.LAVA);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(8, 64, 0));

        assertTrue(result.path().stream().noneMatch(pos -> pos.equals(new BlockPos(4, 64, 0))),
                "the corridor is blocked by lava, and lava is never a step");
    }

    /** Documented intent: a shaft is still the right answer when the target is genuinely below. */
    @Test
    void aShaftIsAcceptableWhenTheTargetIsStraightDown() {
        TestLevel level = TestLevel.scene()
                .fill(-8, 8, 50, 63, -8, 8, Blocks.STONE)
                .carve(0, 0, 64, 65, 0, 0);

        AStarPathfinder.Result result = AStarPathfinder.find(level, new BlockPos(0, 64, 0),
                new Goals.Block(new BlockPos(0, 55, 0)), AStarPathfinder.Settings.mining());

        assertTrue(result.reachedGoal());
        assertTrue(result.path().stream().allMatch(pos -> pos.getX() == 0 && pos.getZ() == 0),
                "nothing is gained by wandering sideways to reach a block directly underfoot");
    }

    /**
     * Digging is priced, walking is not, so a route to something off to one side should cross the
     * surface and descend at the end rather than tunnel the whole way from where it stands. This is
     * the behaviour that keeps a mining trip looking like a trip rather than a burrow.
     */
    @Test
    void itCrossesOnTheSurfaceRatherThanTunnellingTheWholeWay() {
        TestLevel level = TestLevel.scene()
                .fill(-8, 12, 50, 63, -8, 8, Blocks.STONE)
                .carve(0, 0, 64, 65, 0, 0);

        AStarPathfinder.Result result = AStarPathfinder.find(level, new BlockPos(0, 64, 0),
                new Goals.Block(new BlockPos(8, 55, 0)), AStarPathfinder.Settings.mining());

        assertTrue(result.reachedGoal());
        int firstDescent = 0;
        while (firstDescent < result.path().size()
                && result.path().get(firstDescent).getY() == 64) {
            firstDescent++;
        }
        assertTrue(result.path().get(firstDescent - 1).getX() >= 8,
                "the horizontal leg should be walked on top before any digging starts");
    }

    /**
     * Two ways across, the same length: a ford one block deep, and a channel deep enough to swim.
     * The ford is waded on its bottom at a paddle's pace, because one block of water never covers
     * the eyes; the channel is swum at twice that. The channel costs a detour of four blocks at
     * each end on dry land and is still the quicker crossing - and it is the one taken only because
     * its top layer is priced as swimming. Priced by the air above each node, the whole channel
     * read as wading and the ford won.
     */
    @Test
    void itSwimsADeepChannelRatherThanWadeAFordOfTheSameLength() {
        TestLevel level = TestLevel.scene()
                .floor(0, 0, 0, 4, 63)
                .floor(12, 12, 0, 4, 63)
                .fill(1, 11, 61, 70, 1, 3, Blocks.STONE)
                .floor(1, 11, 0, 0, 63)
                .fill(1, 11, 64, 64, 0, 0, Blocks.WATER)
                .floor(1, 11, 4, 4, 61)
                .fill(1, 11, 62, 64, 4, 4, Blocks.WATER);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(12, 64, 0));

        assertTrue(result.reachedGoal());
        assertTrue(result.path().stream().anyMatch(node -> node.getZ() == 4 && node.getX() == 6),
                "the deep channel is the quicker crossing: " + result.path());
        // The executor swims a block under the route's water nodes; it relies on them being the
        // top layer, where every one of them is a breath away.
        assertTrue(result.path().stream()
                        .filter(node -> MovementHelper.isWater(level, node))
                        .allMatch(node -> node.getY() == 64),
                "a crossing is planned along the surface: " + result.path());
    }

    /**
     * Two blocks under the surface is where a dive leaves the bot, and a replan from there has to
     * be able to get out. In water the search only moved sideways and stepped up onto nodes it could
     * stand on, and nothing two blocks down qualifies, so the route ran along the bottom to the bank
     * and stopped against it. A swim test failed five blocks from dry land that way.
     */
    @Test
    void aRouteFromUnderTheSurfaceSwimsUpAndClimbsOut() {
        TestLevel level = TestLevel.scene()
                .floor(0, 13, 0, 0, 59)
                .fill(0, 10, 60, 64, 0, 0, Blocks.WATER)
                .fill(11, 13, 60, 64, 0, 0, Blocks.STONE);

        AStarPathfinder.Result result = walk(level, new BlockPos(2, 62, 0), new BlockPos(13, 65, 0));

        assertTrue(result.reachedGoal(), "a swim up and a climb onto the bank: " + result.path());
        // And it comes up to the top layer early, where every node is a breath away, rather than
        // swimming the length of the pool along the bottom first.
        int firstAtTheTop = 0;
        while (result.path().get(firstAtTheTop).getY() < 64) {
            firstAtTheTop++;
        }
        assertTrue(result.path().get(firstAtTheTop).getX() <= 4,
                "up to the surface layer before the swim, not after it: " + result.path());
    }

    /**
     * Where the walk to a death 111 blocks under a snowy plain gave up: on the surface straight
     * above it, with nowhere left to walk. A route that may dig goes down to it - in legs, each
     * search handing back the deepest point it reached, the way GotoTask follows one - and digs a
     * shaft rather than wandering off across the map to do it.
     */
    @Test
    void aRouteThatMayDigGoesDownToAPlaceFarUnderTheSurface() {
        Underground world = Underground.world().carve(-2, 2, -46, -43, -2, 2);
        Goal death = new Goals.Near(new BlockPos(0, -45, 0), 2);
        BlockPos at = new BlockPos(0, 66, 0);

        assertTrue(AStarPathfinder.find(world, at, death, AStarPathfinder.Settings.walking())
                .isEmpty(), "walking, there is nowhere nearer to go: this is where it stopped");

        int legs = 0;
        while (!death.isReached(at) && legs < 12) {
            List<BlockPos> leg = AStarPathfinder.find(world, at, death, digging()).path();
            assertTrue(leg.size() > 1, "every leg gets further down, and this one did not: " + at);
            for (int step = 1; step < leg.size(); step++) {
                BlockPos node = leg.get(step);
                // Dug as the executor would dig it, so the next leg starts in the real hole.
                MovementHelper.stepClearance(world, leg.get(step - 1), node).forEach(world::dig);
                // A step or two aside to come into the cave from its edge, and no further.
                assertTrue(Math.abs(node.getX()) <= 3 && Math.abs(node.getZ()) <= 3,
                        "down, not across: " + node);
            }
            at = leg.get(leg.size() - 1);
            legs++;
        }
        assertTrue(death.isReached(at), "still short of the cave after " + legs + " legs: " + at);
    }

    /**
     * The same walk from a respawn, with nothing in the pack. The search never plans through a
     * block the bot cannot harvest, so the way down ends at the stone under the dirt. Planned as
     * if any tool were carried it goes on, and the first block on it a bare hand cannot take is
     * the reason to give: that stone, which a pickaxe answers and "no route found" does not.
     */
    @Test
    void withNothingToDigWithTheWayDownStopsAtTheStone() {
        Underground world = Underground.world().carve(-2, 2, -46, -43, -2, 2);
        Goal death = new Goals.Near(new BlockPos(0, -45, 0), 2);

        List<BlockPos> anyTool = AStarPathfinder.find(world, new BlockPos(0, 66, 0), death,
                digging()).path();
        BlockPos stuck = MovementHelper.firstUndiggable(world, anyTool,
                state -> !state.requiresCorrectToolForDrops());

        assertEquals(new BlockPos(0, 61, 0), stuck, "grass and dirt come up by hand; stone does not");
        assertEquals(Blocks.STONE, world.getBlockState(stuck).getBlock());
    }

    /** The doorway of {@link #hut}, in the middle of its south wall. */
    private static final BlockPos HUT_DOOR = new BlockPos(0, 64, 2);

    /**
     * A roofed stone hut, three blocks square inside, with one way in: a door in the south wall, on
     * ground that runs out to the south of it. {@code floorY} is the hut's own floor; the ground
     * outside is always at 63, so a floor of 64 puts the doorway a step up.
     */
    private static TestLevel hut(net.minecraft.world.level.block.Block door, int floorY) {
        int y = floorY + 1;
        TestLevel level = TestLevel.scene()
                .floor(-4, 4, -4, 7, 63)
                .floor(-2, 2, -2, 2, floorY)
                .fill(-2, 2, y, y + 1, -2, -2, Blocks.STONE)
                .fill(-2, 2, y, y + 1, 2, 2, Blocks.STONE)
                .fill(-2, -2, y, y + 1, -2, 2, Blocks.STONE)
                .fill(2, 2, y, y + 1, -2, 2, Blocks.STONE)
                .fill(-2, 2, y + 2, y + 2, -2, 2, Blocks.STONE);
        return DoorwaysTest.door(level, new BlockPos(0, y, 2), door, false);
    }

    /**
     * A shut door is a way in. To the search it used to be a wall - a full-height slab, open or
     * shut - so a house had no inside a walk could reach.
     */
    @Test
    void aWalkIntoAShutHouseGoesThroughItsDoor() {
        TestLevel level = hut(Blocks.OAK_DOOR, 63);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 6), new BlockPos(0, 64, 0));

        assertTrue(result.reachedGoal(), "the door is the way in: " + result.path());
        assertTrue(result.path().contains(HUT_DOOR), "through the doorway: " + result.path());
    }

    @Test
    void anIronDoorStillKeepsAWalkOut() {
        TestLevel level = hut(Blocks.IRON_DOOR, 63);

        assertFalse(walk(level, new BlockPos(0, 64, 6), new BlockPos(0, 64, 0)).reachedGoal(),
                "it needs redstone, and Lune presses no buttons");
    }

    /**
     * A route that may dig goes in by the door as well. It used to have a choice of the wall or the
     * door itself to dig through, and took the door.
     */
    @Test
    void aRouteThatMayDigStillGoesInByTheDoor() {
        TestLevel level = hut(Blocks.OAK_DOOR, 63);

        List<BlockPos> path = AStarPathfinder.find(level, new BlockPos(0, 64, 6),
                new Goals.Block(new BlockPos(0, 64, 0)), AStarPathfinder.Settings.mining()).path();

        assertTrue(path.contains(HUT_DOOR), "in by the door: " + path);
        for (int step = 1; step < path.size(); step++) {
            assertTrue(MovementHelper.stepClearance(level, path.get(step - 1), path.get(step)).isEmpty(),
                    "nothing dug on the way in: " + path);
        }
    }

    /** A doorway a step up from the ground outside, the way a house on a raised floor has one. */
    @Test
    void aDoorwayAStepUpIsSteppedInto() {
        TestLevel level = hut(Blocks.OAK_DOOR, 64);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 6), new BlockPos(0, 65, 0));

        assertTrue(result.reachedGoal(), "up into the doorway: " + result.path());
        assertTrue(result.path().contains(HUT_DOOR.above()), "through it: " + result.path());
    }

    /**
     * A pen with a gate. A fence used to be read as a floor, so the cheapest way in was a hop onto
     * it - a block and a half, which no jump clears - rather than the gate, which could not open.
     */
    @Test
    void aGateIsTheWayIntoAPenAndTheFenceIsNot() {
        TestLevel level = TestLevel.scene().floor(-4, 4, -4, 7, 63)
                .fill(-2, 2, 64, 64, -2, -2, Blocks.OAK_FENCE)
                .fill(-2, 2, 64, 64, 2, 2, Blocks.OAK_FENCE)
                .fill(-2, -2, 64, 64, -2, 2, Blocks.OAK_FENCE)
                .fill(2, 2, 64, 64, -2, 2, Blocks.OAK_FENCE);
        DoorwaysTest.gate(level, HUT_DOOR, false);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 6), new BlockPos(0, 64, 0));

        assertTrue(result.reachedGoal(), "in by the gate: " + result.path());
        assertTrue(result.path().contains(HUT_DOOR), "through it: " + result.path());
        assertTrue(result.path().stream().allMatch(node -> node.getY() == 64),
                "never on top of the fence: " + result.path());
    }

    /** Standing in an open doorway, the way out is straight along it, not across the door's slab. */
    @Test
    void aDoorwayIsLeftTheWayItWasComeInBy() {
        TestLevel level = DoorwaysTest.door(TestLevel.scene().floor(-3, 3, -3, 3, 63),
                new BlockPos(0, 64, 0), Blocks.OAK_DOOR, true);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(3, 64, 0));

        assertTrue(result.reachedGoal(), result.path().toString());
        BlockPos first = result.path().get(1);
        assertEquals(0, first.getX(), "out along the doorway first, then round: " + result.path());
    }

    /** What GotoTask plans once walking has run out, for a walk that may not swim. */
    private static AStarPathfinder.Settings digging() {
        return AStarPathfinder.Settings.mining().withAllowSwim(false);
    }

    /**
     * Rock without end under a surface at y 65, laid the way the overworld lays it: grass, three
     * blocks of dirt, stone down to y 0, deepslate under that and bedrock at the floor. A scene that
     * size is millions of blocks, so it is worked out rather than stored, and only what a test
     * opens up is kept.
     */
    private static final class Underground implements BlockGetter {

        private final Set<Long> open = new HashSet<>();

        static Underground world() {
            TestLevel.scene();  // boots the registries the block states come from
            return new Underground();
        }

        Underground carve(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        open.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
            return this;
        }

        void dig(BlockPos pos) {
            open.add(pos.asLong());
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int y = pos.getY();
            if (y > 65 || open.contains(pos.asLong())) {
                return Blocks.AIR.defaultBlockState();
            }
            if (y == 65) {
                return Blocks.GRASS_BLOCK.defaultBlockState();
            }
            if (y >= 62) {
                return Blocks.DIRT.defaultBlockState();
            }
            if (y >= 0) {
                return Blocks.STONE.defaultBlockState();
            }
            return y > -64 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.BEDROCK.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    @Test
    void aPartialPathStillMakesProgressTowardAnUnreachableGoal() {
        TestLevel level = TestLevel.scene()
                .floor(0, 6, 0, 0, 63)
                .fill(7, 7, 64, 70, -4, 4, Blocks.BEDROCK);

        AStarPathfinder.Result result = walk(level, new BlockPos(0, 64, 0), new BlockPos(20, 64, 0));

        assertFalse(result.reachedGoal());
        assertTrue(result.path().size() > 1, "a partial route is still worth walking");
        assertTrue(result.path().get(result.path().size() - 1).getX() > 0,
                "it should end up nearer the goal than it started");
    }
}
