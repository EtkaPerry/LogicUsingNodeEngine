package com.etka.lune.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaserReachTest {

    private static final BlockPos MOB = new BlockPos(0, 64, 0);

    private static double walked(TestLevel level, BlockPos to) {
        return ChaserReach.from(level, List.of(MOB)).walked(to);
    }

    @Test
    void openGroundIsWalkedStraight() {
        TestLevel level = TestLevel.scene().floor(-2, 8, -2, 2, 63);
        assertEquals(5.0, walked(level, new BlockPos(5, 64, 0)), 1.0E-9);
        assertEquals(0.0, walked(level, MOB), 1.0E-9, "where it stands");
    }

    /** What the user's island-hopping relied on (2026-10-18): nothing that walks follows a body across a gap. */
    @Test
    void aGapIsOutOfReachWithNoWayRound() {
        TestLevel level = TestLevel.scene().floor(-2, 0, -2, 2, 63).floor(3, 8, -2, 2, 63);
        assertEquals(Double.POSITIVE_INFINITY, walked(level, new BlockPos(5, 64, 0)));
    }

    @Test
    void aGapWithAWayRoundIsTheLongWay() {
        TestLevel level = TestLevel.scene().floor(-2, 0, -2, 2, 63).floor(3, 8, -2, 2, 63)
                .floor(-2, 8, 3, 4, 63);
        double round = walked(level, new BlockPos(5, 64, 0));
        assertTrue(round > 8.0 && round < ChaserReach.REACH, "round by the bridge: " + round);
    }

    /**
     * The four-seed batch of 2026-10-18 died of this twice: a spot a block or two across a gap is out
     * of a chaser's walk and still inside its reach. A Zombie at the edge hits across one block of
     * gap and not across two; a Creeper lights its fuse across two.
     */
    @Test
    void aGapIsOnlyOutOfReachWhenItIsWiderThanTheArm() {
        TestLevel one = TestLevel.scene().floor(-2, 0, -2, 2, 63).floor(2, 6, -2, 2, 63);
        assertEquals(Double.POSITIVE_INFINITY, walked(one, new BlockPos(2, 64, 0)), "never walked onto");
        assertEquals(0.0, ChaserReach.from(one, List.of(MOB)).toStrike(new BlockPos(2, 64, 0), 2.5), 1.0E-9,
                "but hit from the edge, two blocks off");

        TestLevel two = TestLevel.scene().floor(-2, 0, -2, 2, 63).floor(3, 6, -2, 2, 63);
        ChaserReach zombie = ChaserReach.from(two, List.of(MOB));
        assertEquals(Double.POSITIVE_INFINITY, zombie.toStrike(new BlockPos(3, 64, 0), 2.5), "past the arm");
        assertEquals(0.0, zombie.toStrike(new BlockPos(3, 64, 0), 3.5), 1.0E-9, "inside the fuse");
    }

    @Test
    void aStepUpIsWalkedAndAWallIsNot() {
        TestLevel step = TestLevel.scene().floor(-2, 2, -2, 2, 63).floor(3, 8, -2, 2, 64);
        assertTrue(walked(step, new BlockPos(5, 65, 0)) < 7.0, "one block up");

        TestLevel wall = TestLevel.scene().floor(-2, 2, -2, 2, 63)
                .fill(3, 8, 64, 65, -2, 2, Blocks.STONE);
        assertEquals(Double.POSITIVE_INFINITY, walked(wall, new BlockPos(5, 66, 0)), "two blocks up");
    }

    @Test
    void aMobDropsThreeBlocksAndNoMore() {
        TestLevel three = TestLevel.scene().floor(-2, 2, -2, 2, 63).floor(3, 8, -2, 2, 60);
        assertTrue(walked(three, new BlockPos(5, 61, 0)) < 7.0, "three down");

        TestLevel four = TestLevel.scene().floor(-2, 2, -2, 2, 63).floor(3, 8, -2, 2, 59);
        assertEquals(Double.POSITIVE_INFINITY, walked(four, new BlockPos(5, 60, 0)), "four down");
    }

    /** Two islands a block apart: the bot jumps what a mob never does. */
    @Test
    void theBotJumpsTheGapAMobCannotWalk() {
        TestLevel level = TestLevel.scene().floor(-2, 0, -2, 2, 63).floor(2, 6, -2, 2, 63);
        BlockPos across = new BlockPos(2, 64, 0);
        assertEquals(Double.POSITIVE_INFINITY, walked(level, across), "no mob gets there");
        ChaserReach runner = ChaserReach.runner(level, MOB, GapJumpPolicy.MAX_GAP, null);
        assertEquals(3.0, runner.walked(across), 1.0E-9, "two blocks of jump and the run-up");
        assertEquals(List.of(MOB, across), runner.way(across), "taken off from the edge");
    }

    /**
     * The same islands with lava under the gap and a bridge eight blocks along. A route pays forty
     * blocks of walking for a jump over lava and walks round; a run pays a fifth of that and jumps,
     * and the bot's own way goes as its route would.
     */
    @Test
    void theBotGoesTheWayItsRouteWould() {
        TestLevel level = TestLevel.scene()
                .floor(-2, 0, -2, 7, 63).floor(2, 6, -2, 7, 63).floor(-2, 6, 8, 10, 63)
                .fill(1, 1, 62, 62, -2, 2, Blocks.LAVA);
        BlockPos across = new BlockPos(2, 64, 0);
        double walking = ChaserReach.runner(level, MOB, GapJumpPolicy.MAX_GAP, null).walked(across);
        assertTrue(walking > 12.0 && walking < ChaserReach.REACH, "round by the bridge: " + walking);
        AStarPathfinder.Avoid run = new AStarPathfinder.Avoid(List.of(), 0.2);
        assertEquals(3.0, ChaserReach.runner(level, MOB, GapJumpPolicy.MAX_GAP, run).walked(across), 1.0E-9,
                "over the lava");
    }

    /** Lava level with the floor between two islands: the bot's own way jumps it on a run, as its route does. */
    @Test
    void theBotJumpsLavaLevelWithTheFloorOnlyOnARun() {
        TestLevel level = TestLevel.scene().floor(-2, 0, -2, 2, 63).floor(2, 6, -2, 2, 63)
                .fill(1, 1, 63, 63, -2, 2, Blocks.LAVA);
        BlockPos across = new BlockPos(2, 64, 0);
        assertEquals(Double.POSITIVE_INFINITY,
                ChaserReach.runner(level, MOB, GapJumpPolicy.MAX_GAP, null).walked(across), "an ordinary route");
        AStarPathfinder.Avoid run = new AStarPathfinder.Avoid(List.of(), 0.2);
        assertEquals(3.0, ChaserReach.runner(level, MOB, GapJumpPolicy.MAX_GAP, run).walked(across), 1.0E-9, "a run");
    }

    /** The bot's own way prices a jump by what it flies over, as its route does. */
    @Test
    void theBotsWayDoesNotJumpThroughACore() {
        TestLevel level = TestLevel.scene().floor(-3, 0, 0, 0, 63).floor(-3, -3, 0, 6, 63)
                .floor(4, 7, 0, 0, 63).floor(7, 7, 0, 6, 63).floor(-3, 7, 6, 6, 63);
        BlockPos across = new BlockPos(4, 64, 0);
        AStarPathfinder.Avoid nothing = new AStarPathfinder.Avoid(List.of(), 0.2);
        AStarPathfinder.Avoid overTheGap = new AStarPathfinder.Avoid(List.of(
                new AStarPathfinder.Avoid.Zone(2.5, 64.0, 0.5, 1.2, 1.0, 1.2)), 0.2);
        assertEquals(5.0, ChaserReach.runner(level, MOB, GapJumpPolicy.MAX_SPRINT_GAP, nothing).walked(across), 1.0E-9,
                "four blocks of jump and the run-up");
        assertTrue(ChaserReach.runner(level, MOB, GapJumpPolicy.MAX_SPRINT_GAP, overTheGap).walked(across) > 12.0,
                "round by the bridge");
    }

    @Test
    void theBotsWayBendsRoundAChaser() {
        TestLevel level = TestLevel.scene().floor(-2, 14, -9, 9, 63);
        AStarPathfinder.Avoid zombie = new AStarPathfinder.Avoid(6.5, 64.0, 0.5, 5.0, 30.0);
        BlockPos target = new BlockPos(12, 64, 0);
        ChaserReach runner = ChaserReach.runner(level, MOB, GapJumpPolicy.MAX_GAP, zombie);
        assertTrue(runner.walked(target) > 13.0, "longer than the straight line: " + runner.walked(target));
        assertTrue(runner.way(target).stream().anyMatch(pos -> Math.abs(pos.getZ()) >= 4), "round it");
        assertEquals(12.0, ChaserReach.runner(level, MOB, GapJumpPolicy.MAX_GAP, null).walked(target), 1.0E-9,
                "straight with nobody there");
    }

    /** A body in the air is reckoned from where it comes down. */
    @Test
    void theBotInTheAirStartsWhereItLands() {
        TestLevel level = TestLevel.scene().floor(-2, 6, -2, 2, 63);
        ChaserReach runner = ChaserReach.runner(level, new BlockPos(3, 66, 0), GapJumpPolicy.MAX_GAP, null);
        assertEquals(0.0, runner.walked(new BlockPos(3, 64, 0)), 1.0E-9);
        assertTrue(ChaserReach.runner(level, new BlockPos(3, 70, 0), GapJumpPolicy.MAX_GAP, null).isEmpty(),
                "six blocks up is a fall, not a start");
    }
}
