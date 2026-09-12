package com.etka.lune.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

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
