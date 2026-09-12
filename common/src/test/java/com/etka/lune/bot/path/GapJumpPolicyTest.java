package com.etka.lune.bot.path;

import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GapJumpPolicyTest {

    /** Open air the whole way across: the ordinary case for a planned jump. */
    private static final IntPredicate OPEN = step -> false;
    /** Solid ground the whole way: this is walking, however far the node is. */
    private static final IntPredicate PAVED = step -> true;

    /**
     * The assertion the original bug needed.
     * <p>
     * The search planned jumps that land a block lower; the executor refused to press jump on any
     * tick it considered a descent. Every one of those routes was a hole the bot walked into. Both
     * sides now come from this class, and this pins them to each other.
     */
    @Test
    void everyJumpTheSearchCanPlanIsOneTheExecutorWillPressFor() {
        for (int[] jump : GapJumpPolicy.plannedJumps()) {
            assertTrue(GapJumpPolicy.isGap(jump[0], jump[1], jump[2], OPEN),
                    "the search plans dx=" + jump[0] + " dy=" + jump[1] + " dz=" + jump[2]
                            + " but the executor would not jump for it");
        }
    }

    @Test
    void theSearchPlansBothLevelJumpsAndOnesThatLandABlockLower() {
        boolean level = false;
        boolean dropping = false;
        for (int[] jump : GapJumpPolicy.plannedJumps()) {
            level |= jump[1] == 0;
            dropping |= jump[1] == -1;
        }
        assertTrue(level, "level jumps must still be planned");
        assertTrue(dropping, "the drop case is the half that used to be unwalkable");
    }

    @Test
    void aTwoBlockGapIsAJump() {
        assertTrue(GapJumpPolicy.isGap(2, 0, 0, OPEN));
        assertTrue(GapJumpPolicy.isGap(-2, 0, 0, OPEN));
        assertTrue(GapJumpPolicy.isGap(0, 0, 2, OPEN));
        assertTrue(GapJumpPolicy.isGap(0, 0, -2, OPEN));
    }

    @Test
    void aGapThatLandsOneBlockLowerIsStillAJump() {
        assertTrue(GapJumpPolicy.isGap(2, -1, 0, OPEN),
                "planned by the search; refusing it is what made two-block gaps look impossible");
        assertTrue(GapJumpPolicy.isGap(3, -1, 0, OPEN));
    }

    @Test
    void theNextBlockAlongIsAStepNotAJump() {
        assertFalse(GapJumpPolicy.isGap(1, 0, 0, OPEN));
        assertFalse(GapJumpPolicy.isGap(0, 0, 1, OPEN));
        assertFalse(GapJumpPolicy.isGap(0, 0, 0, OPEN));
    }

    @Test
    void nothingBeyondTheSearchesOwnReachIsTreatedAsAPlannedJump() {
        // A bot that has stalled or drifted is also "far from the next node". Treating that as a
        // jump is what made it hop on the spot on a hillside.
        assertFalse(GapJumpPolicy.isGap(GapJumpPolicy.MAX_GAP + 2, 0, 0, OPEN));
        assertFalse(GapJumpPolicy.isGap(8, 0, 0, OPEN));
    }

    @Test
    void aJumpNeverGoesUpwards() {
        assertFalse(GapJumpPolicy.isGap(2, 1, 0, OPEN), "gaining height is a step up, not a gap");
    }

    @Test
    void aDropDeeperThanPlannedIsNotAGapJump() {
        assertFalse(GapJumpPolicy.isGap(2, -(GapJumpPolicy.MAX_DROP + 1), 0, OPEN),
                "a longer drop is a fall the route priced separately");
    }

    @Test
    void diagonalsAreRefusedBecauseTheyCatchOnCorners() {
        assertFalse(GapJumpPolicy.isGap(1, 0, 1, OPEN));
        assertFalse(GapJumpPolicy.isGap(2, 0, 1, OPEN));
    }

    @Test
    void solidGroundBetweenMeansWalkingNotJumping() {
        assertFalse(GapJumpPolicy.isGap(2, 0, 0, PAVED));
        assertFalse(GapJumpPolicy.isGap(3, 0, 0, step -> step == 2),
                "one floor block anywhere in the span is enough to walk it");
    }

    @Test
    void onlyTheBlocksBetweenAreChecked() {
        // The landing itself is the route's business, not this predicate's, and the take-off is
        // where the bot is already standing.
        assertTrue(GapJumpPolicy.isGap(3, 0, 0, step -> step >= 3));
    }

    @Test
    void arrivingOnFootIsFastEnoughToTakeOff() {
        assertTrue(GapJumpPolicy.shouldTakeOff(true, true, true, true, 0.215),
                "a walk is above the threshold, so approaching normally always clears");
    }

    @Test
    void aStandingStartWaitsForTheRunUpInsteadOfHoppingIntoTheHole() {
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, true, 0.0));
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, true,
                GapJumpPolicy.MIN_TAKEOFF_SPEED - 0.01));
        assertTrue(GapJumpPolicy.shouldTakeOff(true, true, true, true,
                GapJumpPolicy.MIN_TAKEOFF_SPEED));
    }

    @Test
    void theTakeOffNeedsGroundHeadroomAndPermission() {
        assertFalse(GapJumpPolicy.shouldTakeOff(false, true, true, true, 0.3), "jumping disallowed");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, false, true, true, 0.3), "already airborne");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, false, true, 0.3), "no headroom");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, false, 0.3), "not a gap");
    }
}
