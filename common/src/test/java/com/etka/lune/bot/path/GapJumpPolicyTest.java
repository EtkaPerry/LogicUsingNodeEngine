package com.etka.lune.bot.path;

import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertFalse(GapJumpPolicy.isGap(GapJumpPolicy.MAX_SPRINT_GAP + 2, 0, 0, OPEN));
        assertFalse(GapJumpPolicy.isGap(8, 0, 0, OPEN));
    }

    @Test
    void aJumpGoesUpOneBlockOnlyAcrossOne() {
        assertTrue(GapJumpPolicy.isGap(2, 1, 0, OPEN), "a hop up onto a ledge across a hole");
        assertFalse(GapJumpPolicy.isGap(3, 1, 0, OPEN), "up across two is past what a walk reaches");
        assertFalse(GapJumpPolicy.isGap(2, 2, 0, OPEN), "two up is past any jump");
    }

    @Test
    void threeBlocksOfGapAreALevelRunningJump() {
        assertTrue(GapJumpPolicy.isGap(4, 0, 0, OPEN));
        assertTrue(GapJumpPolicy.isGap(0, 0, -4, OPEN));
        assertFalse(GapJumpPolicy.isGap(4, -1, 0, OPEN), "a running jump to a block lower lands past it");
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

    /** Far enough back from the edge that the lip has nothing to say. */
    private static final double ROOM = 0.8;

    @Test
    void aRunEnteringTheTakeOffBlockReachesTwoBlocksAtOnce() {
        // What getDeltaMovement() reads after the ground's friction: 0.2806 a tick, times 0.546.
        assertTrue(GapJumpPolicy.reaches(1.0, 0.153, true, 0, 2), "a running jump comes down 3.6 out");
        assertTrue(GapJumpPolicy.shouldTakeOff(true, true, true, true, true, true, true, 0.153, ROOM));
    }

    @Test
    void aWalkJumpsTwoBlocksFromTheLip() {
        // 0.2159 a tick, times 0.546: a walking jump never comes down on a two-block gap's middle,
        // and walked into the hole it had planned to jump before the lip was a rule.
        assertFalse(GapJumpPolicy.reaches(0.0, 0.118, false, 0, 2));
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, true, true, true, false, 0.118, 0.0),
                "at the edge a walk still has one more tick on the block");
        assertTrue(GapJumpPolicy.shouldTakeOff(true, true, true, true, true, true, false, 0.118, -0.1),
                "a tenth of a block over, the next tick of a walk is off it");
    }

    @Test
    void aStandingStartWaitsForTheRunUpInsteadOfHoppingIntoTheHole() {
        assertFalse(GapJumpPolicy.reaches(0.5, 0.0, false, 0, 2), "a standing hop comes down in the hole");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, true, true, true, false, 0.0, ROOM));
        assertTrue(GapJumpPolicy.reaches(0.5, 0.07, true, 0, 2), "a few ticks of run from the middle do");
    }

    @Test
    void aLongGapWaitsForTheLipAndAShortOneGoesFromFurtherBack() {
        assertFalse(GapJumpPolicy.reaches(1.0, 0.153, true, 0, 3), "from the back of the block, short");
        assertTrue(GapJumpPolicy.reaches(0.1, 0.153, true, 0, 3), "from the lip, the middle of the landing");
        assertTrue(GapJumpPolicy.reaches(0.5, 0.118, false, 0, 1), "a walk over one goes from the middle");
        assertFalse(GapJumpPolicy.reaches(1.0, 0.118, false, 0, 1), "and not from the back");
    }

    @Test
    void theTakeOffNeedsGroundHeadroomAndPermission() {
        assertFalse(GapJumpPolicy.shouldTakeOff(false, true, true, true, true, true, true, 0.3, ROOM), "jumping disallowed");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, false, true, true, true, true, true, 0.3, ROOM), "already airborne");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, false, true, true, true, true, 0.3, ROOM), "no headroom");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, false, true, true, true, 0.3, ROOM), "not a gap");
    }

    @Test
    void aBodyNotReadyToRunNeverJumpsEvenAtTheLip() {
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, true, false, true, true, 0.3, ROOM),
                "the sprint jump goes the way the head faces");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, true, false, true, false, 0.0, -0.2),
                "a hold at the lip is Shift's to keep: a crouched hop from there lands in the hole");
    }

    @Test
    void aBodySprintingAtAJumpToBeWalkedHoldsFirst() {
        assertFalse(GapJumpPolicy.readyToRun(true, true, false, false, ROOM, 0.0, 0.153),
                "the hold lets go of forward, which is what ends a sprint");
        assertTrue(GapJumpPolicy.readyToRun(true, true, true, false, ROOM, 0.0, 0.153));
    }

    @Test
    void whichJumpsAreWalked() {
        assertTrue(GapJumpPolicy.walkJump(1, 1, 2), "every jump up");
        assertTrue(GapJumpPolicy.walkJump(0, 1, 1), "one block, with a hole a block past the landing");
        assertFalse(GapJumpPolicy.walkJump(0, 1, 2), "one block, with room past the landing");
        assertFalse(GapJumpPolicy.walkJump(0, 2, 0), "two blocks need the run");
        assertFalse(GapJumpPolicy.walkJump(0, 3, 0));
        assertTrue(GapJumpPolicy.walkJump(-1, 2, 0), "two blocks and down one, onto a block with a hole past it");
        assertFalse(GapJumpPolicy.walkJump(-1, 2, 2), "two blocks and down one, with room past the landing");
    }

    /**
     * Two blocks of gap, a landing a block down and a block long, then a hole: the bastion's fall
     * of 2026-10-18. Run at from the middle of the take-off block the jump comes down past the
     * landing; walked from the lip it comes down on it.
     */
    @Test
    void aDropJumpOntoAShortLandingIsWalked() {
        Jumper ran = new Jumper(-1.0, 0.153, false);
        ran.sprinting = true;
        ran.gap = 2;
        ran.rise = -1;
        ran.landingLength = 1;
        assertFalse(ran.jumpsAtARunFrom(-GapJumpPolicy.RUN_UP_ROOM), "run at, it overshoots: " + ran);

        Jumper walked = new Jumper(-1.0, 0.153, false);
        walked.sprinting = true;
        walked.gap = 2;
        walked.rise = -1;
        walked.landingLength = 1;
        assertTrue(walked.lands(), walked.toString());
        assertFalse(walked.sprintAtTakeOff, walked.toString());
    }

    /**
     * The user, watching the lava maze (2026-10-18), saw a bot crouch before every jump. Shift is for
     * a body whose slide would carry it off the edge, and for nothing else.
     */
    @Test
    void aHoldPressesShiftOnlyForABodyThatWouldSlideOff() {
        assertFalse(GapJumpPolicy.holdsWithShift(0.5, 0.0), "standing in the middle");
        assertFalse(GapJumpPolicy.holdsWithShift(0.0, 0.0), "standing at the lip");
        assertFalse(GapJumpPolicy.holdsWithShift(0.5, 0.153), "running, with the block to stop on");
        assertFalse(GapJumpPolicy.holdsWithShift(0.4, -0.1), "going back from the edge");
        assertTrue(GapJumpPolicy.holdsWithShift(0.1, 0.153), "running at the lip");
        assertTrue(GapJumpPolicy.holdsWithShift(-0.15, 0.05), "over the edge and still going");
    }

    @Test
    void aHeadOffTheLineIsNeverReadyHoweverFastTheBotIs() {
        assertFalse(GapJumpPolicy.readyToRun(false, true, true, true, ROOM, 0.0, 0.3));
        assertFalse(GapJumpPolicy.readyToRun(false, true, true, false, ROOM, 0.0, 0.3));
    }

    @Test
    void aBodyNearTheEdgeGoesBackToTheMiddleBeforeItRuns() {
        assertTrue(GapJumpPolicy.readyToRun(true, true, true, false, 0.5, 0.0, 0.0), "the middle of the block");
        assertFalse(GapJumpPolicy.readyToRun(true, true, true, false, 0.2, 0.0, 0.0), "stopped short of the edge");
        assertFalse(GapJumpPolicy.readyToRun(true, true, true, false, -0.2, 0.0, 0.05), "a route let go at the lip");
        assertTrue(GapJumpPolicy.readyToRun(true, true, true, true, 0.2, 0.0, 0.05), "a run once begun carries on");
        assertTrue(GapJumpPolicy.readyToRun(true, true, true, false, 0.2, 0.0, 0.153), "arriving at a run");
    }

    @Test
    void aBodyOffTheLaneGoesBackToTheMiddleHoweverFastItArrives() {
        assertFalse(GapJumpPolicy.readyToRun(true, true, true, false, ROOM, 0.4, 0.153),
                "its shoulder would fly along the next column over");
        assertTrue(GapJumpPolicy.readyToRun(true, true, true, false, ROOM, GapJumpPolicy.LANE, 0.153));
        assertTrue(GapJumpPolicy.readyToRun(true, true, true, true, ROOM, 0.4, 0.153),
                "a run begun on the lane is steered at the landing and stays on it");
    }

    @Test
    void aBodyOnTheLaneFliesInsideTheJumpsColumn() {
        assertTrue(GapJumpPolicy.LANE + GapJumpPolicy.HALF_WIDTH <= 0.5,
                "half a width either side of the lane is still inside the block's column");
    }

    @Test
    void theLaneIsMeasuredAcrossTheJump() {
        // Take-off block (5, 7). Going east or west the lane is z 7.5; going north or south, x 5.5.
        assertEquals(0.3, GapJumpPolicy.offLane(5, 7, 1, 5.9, 7.8), 1.0E-9);
        assertEquals(0.3, GapJumpPolicy.offLane(5, 7, -1, 5.9, 7.2), 1.0E-9);
        assertEquals(0.4, GapJumpPolicy.offLane(5, 7, 0, 5.1, 7.9), 1.0E-9);
        assertEquals(0.0, GapJumpPolicy.offLane(5, 7, 0, 5.5, 7.1), 1.0E-9);
    }

    /**
     * The fall on the bastion's second run of 2026-10-18: the route came onto the take-off block
     * (11, 20) on a diagonal from the south-west, sprinting, and jumped north for the landing two
     * blocks on with the body at x 11.1 - the wall standing beside the gap at x 10 took its
     * shoulder on the tick it left. The head was on the landing, so only the lane says no.
     */
    @Test
    void theBastionTakeOffThatClippedTheWallWaitsForTheLane() {
        double x = 11.1;
        double z = 20.6;
        double towardX = 11.5 - x;
        double towardZ = 18.5 - z;
        boolean facing = GapJumpPolicy.facesTheJump(-167.9F, towardX, towardZ);
        double along = GapJumpPolicy.speedToward(0.03, -0.15, towardX, towardZ);
        double toEdge = GapJumpPolicy.toEdge(11, 20, 0, -1, x, z);
        double offLane = GapJumpPolicy.offLane(11, 20, 0, x, z);
        assertTrue(facing, "the head was on the landing");
        assertTrue(along >= GapJumpPolicy.MIN_TAKEOFF_SPEED, "and the run was fast enough");
        boolean ready = GapJumpPolicy.readyToRun(facing, true, true, false, toEdge, offLane, along);
        assertFalse(ready, "four tenths off the lane");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, true, ready, true, true, along, toEdge));
    }

    @Test
    void theEdgeIsMeasuredAlongTheJump() {
        // Take-off block (5, 7): its far edge is x 6 going east, x 5 west, z 8 south and z 7 north.
        assertEquals(0.5, GapJumpPolicy.toEdge(5, 7, 1, 0, 5.5, 7.5), 1.0E-9);
        assertEquals(0.5, GapJumpPolicy.toEdge(5, 7, -1, 0, 5.5, 7.5), 1.0E-9);
        assertEquals(0.5, GapJumpPolicy.toEdge(5, 7, 0, 1, 5.5, 7.5), 1.0E-9);
        assertEquals(0.5, GapJumpPolicy.toEdge(5, 7, 0, -1, 5.5, 7.5), 1.0E-9);
        assertEquals(-0.2, GapJumpPolicy.toEdge(5, 7, -1, 0, 4.8, 7.5), 1.0E-9, "hanging over");
        assertEquals(0.9, GapJumpPolicy.toEdge(5, 7, 0, -1, 5.5, 7.9), 1.0E-9, "the back of the block");
    }

    @Test
    void theLipIsTheLastTickTheFeetCanPushOff() {
        assertFalse(GapJumpPolicy.atTheLip(0.5, 0.0), "the middle of the block");
        assertFalse(GapJumpPolicy.atTheLip(-0.1, 0.0), "one tick from a standstill cannot carry it off");
        assertTrue(GapJumpPolicy.atTheLip(-0.2, 0.0), "a sprinter's push from here can");
        assertFalse(GapJumpPolicy.atTheLip(0.0, 0.118));
        assertTrue(GapJumpPolicy.atTheLip(-0.1, 0.118));
    }

    @Test
    void theHeadIsReadTheWayMinecraftTurnsIt() {
        // Yaw 0 faces +Z, -90 faces +X, 90 faces -X, 180 faces -Z.
        assertTrue(GapJumpPolicy.facesTheJump(0.0F, 0.0, 3.0));
        assertTrue(GapJumpPolicy.facesTheJump(-90.0F, 3.0, 0.0));
        assertTrue(GapJumpPolicy.facesTheJump(90.0F, -3.0, 0.0));
        assertTrue(GapJumpPolicy.facesTheJump(180.0F, 0.0, -3.0));
        assertFalse(GapJumpPolicy.facesTheJump(90.0F, 3.0, 0.0), "facing away");
        // A yaw the game has wound round twice reads the same as the one it stands for.
        assertTrue(GapJumpPolicy.facesTheJump(-90.0F - 720.0F, 3.0, 0.0));
    }

    @Test
    void tenDegreesOffTheLineIsOnItAndFifteenIsNot() {
        assertTrue(GapJumpPolicy.facesTheJump(-85.0F, 3.0, 0.0));
        assertTrue(GapJumpPolicy.facesTheJump(-99.0F, 3.0, 0.0));
        assertFalse(GapJumpPolicy.facesTheJump(-75.0F, 3.0, 0.0));
        assertFalse(GapJumpPolicy.facesTheJump(-105.0F, 3.0, 0.0));
    }

    @Test
    void speedAcrossTheLineIsNoRunUp() {
        assertTrue(GapJumpPolicy.speedToward(0.25, 0.0, 3.0, 0.0) > 0.24, "straight at the landing");
        assertTrue(Math.abs(GapJumpPolicy.speedToward(0.0, -0.25, 3.0, 0.0)) < 1.0E-9,
                "the walk up to a turn goes across the line, not along it");
        assertTrue(GapJumpPolicy.speedToward(-0.25, 0.0, 3.0, 0.0) < 0.0, "backing away");
    }

    /**
     * The leap that fell on the lava maze's bastion (2026-10-17), as the journal had it: the route
     * walked north up to the take-off and turned east for a two-block leap, and the bot jumped on
     * the tick it arrived, still facing north-north-east and moving north.
     */
    @Test
    void theBastionLeapThatFellWaitsForTheHead() {
        float yaw = -880.73F;
        double towardX = 3.0;
        double towardZ = 0.0;
        boolean facing = GapJumpPolicy.facesTheJump(yaw, towardX, towardZ);
        double along = GapJumpPolicy.speedToward(0.03, -0.2, towardX, towardZ);
        assertFalse(facing, "seventy degrees off the line");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, true,
                GapJumpPolicy.readyToRun(facing, true, true, false, ROOM, 0.0, along), true, true, along, ROOM));
        // Round onto the line and running at the landing, the same leap goes.
        double running = GapJumpPolicy.speedToward(0.2, 0.0, towardX, towardZ);
        assertTrue(GapJumpPolicy.shouldTakeOff(true, true, true, true,
                GapJumpPolicy.readyToRun(GapJumpPolicy.facesTheJump(-90.0F, towardX, towardZ), true, true, false,
                        ROOM, 0.0, running),
                true, true, running, ROOM));
    }

    @Test
    void theHeadsLineIsReadAtTheFarEdgeOfTheLanding() {
        // Jumping south (+z) from the take-off block (24, 0) for the landing (24, 3).
        assertEquals(0.0, GapJumpPolicy.landingOffLane(0.0F, 0, 1, 24.5, 0.5, 24.5, 3.5), 1.0E-9,
                "on the lane, facing straight along it");
        double aimed = GapJumpPolicy.landingOffLane(1.6847F, 0, 1, 24.6, 0.1, 24.5, 3.5);
        assertTrue(aimed < 0.02, "a tenth off the lane, the head on the landing's middle: " + aimed);
        assertEquals(Double.POSITIVE_INFINITY, GapJumpPolicy.landingOffLane(180.0F, 0, 1, 24.5, 0.5, 24.5, 3.5),
                "facing back the way it came");
        // Jumping east (+x): the lane is the landing's z.
        assertEquals(0.1, GapJumpPolicy.landingOffLane(-90.0F, 1, 0, 5.5, 7.6, 8.5, 7.5), 1.0E-6);
    }

    /**
     * The fall on the bastion with two Zombies and a Creeper (2026-10-18): a sprint into the take-off
     * block (24, 0) on the lane, the head six and a half degrees off straight - facing, by the ten the
     * take-off allows - and the body came down three tenths west, where the wall beside the landing
     * (24, 3) took its shoulder. Every other rule said go.
     */
    @Test
    void theBastionLandingThatClippedTheWallWaitsForTheHead() {
        float yaw = 6.55F;
        double x = 24.6;
        double z = 0.1;
        boolean facing = GapJumpPolicy.facesTheJump(yaw, 24.5 - x, 3.5 - z);
        double offLane = GapJumpPolicy.offLane(24, 0, 0, x, z);
        double toEdge = GapJumpPolicy.toEdge(24, 0, 0, 1, x, z);
        double landing = GapJumpPolicy.landingOffLane(yaw, 0, 1, x, z, 24.5, 3.5);
        assertTrue(facing && offLane <= GapJumpPolicy.LANE && toEdge >= GapJumpPolicy.RUN_UP_ROOM);
        assertTrue(landing > 0.3, "three tenths off at the landing: " + landing);
        boolean linesUp = GapJumpPolicy.linesUp(yaw, 0, 1, x, z, 24.5, 3.5);
        boolean ready = GapJumpPolicy.readyToRun(facing, linesUp, true, false, toEdge, offLane, 0.153);
        assertFalse(ready, "the head comes round first");
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, true, ready, linesUp, true, 0.153, toEdge));
    }

    @Test
    void aRunWhoseLineHasDriftedRunsOnUntilTheLip() {
        assertFalse(GapJumpPolicy.shouldTakeOff(true, true, true, true, true, false, true, 0.153, 0.6),
                "fast enough, but the head is coming back");
        assertTrue(GapJumpPolicy.shouldTakeOff(true, true, true, true, true, false, true, 0.153, -0.1),
                "at the lip any jump lands further than none");
    }

    /**
     * The hold after a turn at the edge: the head is round, Shift was down last tick - so vanilla
     * has the body crouched for one more, a third of the speed and no sprint - and the body stands
     * where the hold put it, in the middle of the block.
     */
    @Test
    void aRunFromTheMiddleAfterAHoldLandsTheJump() {
        Jumper body = new Jumper(-0.5, 0.0, true);
        assertTrue(body.lands(), body.toString());
        assertTrue(body.sprintAtTakeOff, "the boost is the sprint's: " + body);
    }

    /**
     * The fall on the bastion (2026-10-18): the walk up to the turn carried the body to the lip,
     * the hold kept it there, and the route let go and came back with it hanging over the hole at
     * a crawl. It goes back to the middle first now, and runs from there.
     */
    @Test
    void aBodyStoppedAtTheLipBacksOffAndThenLandsTheJump() {
        Jumper afterAHold = new Jumper(0.15, 0.0, true);
        assertTrue(afterAHold.lands(), afterAHold.toString());
        assertTrue(afterAHold.sprintAtTakeOff, afterAHold.toString());

        Jumper afterALetGo = new Jumper(0.2, 0.05, false);
        assertTrue(afterALetGo.lands(), afterALetGo.toString());
        assertTrue(afterALetGo.sprintAtTakeOff, afterALetGo.toString());
    }

    /** A route that may not sprint: a walk never comes down on a two-block gap's middle, and goes from the lip. */
    @Test
    void aWalkClearsTwoBlocksFromTheLip() {
        Jumper body = new Jumper(-1.0, 0.118, false);
        body.mayRun = false;
        assertTrue(body.lands(), body.toString());
        assertTrue(body.takeOffAt > -0.05, "from the lip, where a walking jump has to start: " + body);
    }

    /** Three blocks of gap - "people jump 4 blocks wide sometimes" - from a hold in the middle of the block. */
    @Test
    void aRunningJumpClearsThreeBlocksFromTheLip() {
        Jumper body = new Jumper(-0.5, 0.0, true);
        body.gap = 3;
        assertTrue(body.lands(), body.toString());
        assertTrue(body.sprintAtTakeOff, body.toString());
        assertTrue(body.takeOffAt > -0.2, "a long gap waits for the lip: " + body);
    }

    /**
     * One block of gap, one block of landing, then a hole: the shape of the bastion's first fall in
     * the four-seed batch (2026-10-18). A running jump comes down two blocks past the edge - in the
     * hole beyond. The hold ends the sprint, and the walk lands it.
     */
    @Test
    void aShortGapBeforeAHoleIsWalkedSoTheBodyComesDownOnTheLanding() {
        Jumper ran = new Jumper(-1.0, 0.153, false);
        ran.sprinting = true;
        ran.gap = 1;
        ran.landingLength = 1;
        assertFalse(ran.jumpsAtARunFrom(-1.0), "run at, it overshoots: " + ran);

        Jumper walked = new Jumper(-1.0, 0.153, false);
        walked.sprinting = true;
        walked.gap = 1;
        walked.landingLength = 1;
        assertTrue(walked.lands(), walked.toString());
        assertFalse(walked.sprintAtTakeOff, walked.toString());
    }

    /** One block up across one, the way the user hopped onto ledges from a standstill (2026-10-18). */
    @Test
    void aJumpUpABlockIsWalkedAndComesDownOnTop() {
        Jumper body = new Jumper(-1.0, 0.118, false);
        body.gap = 1;
        body.rise = 1;
        assertTrue(body.lands(), body.toString());
        assertFalse(body.hitTheFace, body.toString());
        assertFalse(body.sprintAtTakeOff, "walked: " + body);
    }

    /**
     * Why a jump up is walked: run at from the lip, the body meets the landing's face below its top
     * and only gets onto it by still rising when the feet clear the top - scrambling over with
     * nothing to spare. Walked, it never touches the face.
     */
    @Test
    void aJumpUpABlockAtARunMeetsTheLandingsFace() {
        Jumper body = new Jumper(-1.0, 0.153, false);
        body.sprinting = true;
        body.gap = 1;
        body.rise = 1;
        body.jumpsAtARunFrom(-0.1);
        assertTrue(body.hitTheFace, "below its top, into its side: " + body);
    }

    @Test
    void theFlightModelIsVanillaStepByStep() {
        for (double speed : new double[] {0.0, 0.07, 0.118, 0.153}) {
            for (boolean sprinting : new boolean[] {false, true}) {
                for (int rise = -1; rise <= 1; rise++) {
                    String what = speed + (sprinting ? " running" : " walking") + " to " + rise;
                    assertEquals(Jumper.flightOf(speed, sprinting, rise),
                            GapJumpPolicy.flight(speed, sprinting, rise), 0.05, what);
                }
            }
        }
        assertEquals(3.6, GapJumpPolicy.flight(0.153, true, 0), 0.1, "a running jump");
        assertEquals(2.0, GapJumpPolicy.flight(0.118, false, 0), 0.1, "a walking jump");
        assertEquals(1.5, GapJumpPolicy.flight(0.0, false, 0), 0.1, "a standing jump");
    }

    /**
     * What vanilla does with the walker's keys along the line of a jump, a tick at a time - read out
     * of LocalPlayer.aiStep, LivingEntity.jumpFromGround, LivingEntity.travel and
     * Player.maybeBackOffFromEdge - so the take-off rules are held to whether the body lands.
     *
     * <p>Along the jump only. The middle of the body is {@code x}; the take-off block's far edge is at
     * 0, with ground behind it; then {@code gap} blocks of hole, and a landing {@code rise} blocks up
     * or down, {@code landingLength} blocks long, with a hole past it. The feet stand on ground while
     * the body overlaps it by any amount, half a width either side of the middle, and a landing above
     * the take-off is a wall below its top. The keys are pressed the way PathExecutor presses them: a
     * hold is Shift with the body steered back toward the middle of the block at -0.5, a run is
     * forward, with the run key unless the jump is one to walk, and the head is on the line
     * throughout.</p>
     */
    private static final class Jumper {
        double x;
        double y;
        /** getDeltaMovement() along the jump: what the walker reads, after last tick's friction. */
        double speed;
        double up;
        double lastUp;
        boolean onGround = true;
        boolean sprinting;
        boolean shiftLastTick;
        boolean mayRun = true;
        boolean runningUp;
        boolean inTheHole;
        boolean hitTheFace;
        int gap = 2;
        int rise;
        int landingLength = 100;
        double takeOffAt = Double.NaN;
        boolean sprintAtTakeOff;
        int ticks;

        Jumper(double x, double speed, boolean shiftLastTick) {
            this.x = x;
            this.speed = speed;
            this.shiftLastTick = shiftLastTick;
        }

        /** Where the take-off rules have it jump: ticks until the body is down on the landing, or in a hole. */
        boolean lands() {
            while (ticks++ < 200) {
                tick();
                if (inTheHole) {
                    return false;
                }
                if (onGround && x > gap - GapJumpPolicy.HALF_WIDTH) {
                    return true;
                }
            }
            throw new AssertionError("never jumped: " + this);
        }

        /** Running, the run key down, and jumping as soon as the body is past {@code atX}: no rules. */
        boolean jumpsAtARunFrom(double atX) {
            while (ticks++ < 200) {
                boolean jump = onGround && x >= atX && x < GapJumpPolicy.HALF_WIDTH;
                vanilla(true, false, false, true, jump);
                if (inTheHole) {
                    return false;
                }
                if (onGround && x > gap - GapJumpPolicy.HALF_WIDTH) {
                    return true;
                }
            }
            throw new AssertionError("never jumped: " + this);
        }

        /** How far a jump goes until the feet come down to {@code rise}, stepped tick by tick in open air. */
        static double flightOf(double speed, boolean sprinting, int rise) {
            Jumper body = new Jumper(0.0, speed, false);
            body.gap = 1000;
            body.sprinting = sprinting;
            body.vanilla(true, false, false, sprinting, true);
            while (!(body.lastUp < 0.0 && body.y <= rise)) {
                body.vanilla(true, false, false, sprinting, false);
            }
            return body.x;
        }

        private void tick() {
            boolean forward = true;
            boolean backward = false;
            boolean sneak = false;
            boolean jump = false;
            boolean walk = GapJumpPolicy.walkJump(rise, gap, Math.min(2, landingLength - 1));
            if (onGround && x < GapJumpPolicy.HALF_WIDTH) {
                double toEdge = -x;
                boolean ready = GapJumpPolicy.readyToRun(true, true, !(walk && sprinting), runningUp, toEdge,
                        0.0, speed);
                runningUp = ready;
                if (!ready) {
                    forward = false;
                    backward = toEdge < GapJumpPolicy.RUN_UP_ROOM;
                    sneak = backward || GapJumpPolicy.holdsWithShift(toEdge, speed);
                }
                boolean reaches = GapJumpPolicy.reaches(toEdge, speed, sprinting, rise, gap);
                jump = GapJumpPolicy.shouldTakeOff(true, true, true, true, ready, true, reaches, speed, toEdge);
            }
            vanilla(forward, backward, sneak, forward && mayRun && !walk, jump);
        }

        private double floorAt(double at) {
            if (at < GapJumpPolicy.HALF_WIDTH) {
                return 0.0;
            }
            if (at > gap - GapJumpPolicy.HALF_WIDTH && at < gap + landingLength + GapJumpPolicy.HALF_WIDTH) {
                return rise;
            }
            return Double.NEGATIVE_INFINITY;
        }

        private void vanilla(boolean forward, boolean backward, boolean sneak, boolean runKey,
                             boolean jump) {
            // Crouching is read off last tick's keys, before this tick's are taken in.
            boolean crouching = shiftLastTick;
            shiftLastTick = sneak;
            double impulse = ((forward ? 1 : 0) - (backward ? 1 : 0)) * 0.98 * (crouching ? 0.3 : 1.0);
            if (!sprinting && forward && runKey && !crouching) {
                sprinting = true;
            }
            if (sprinting && !forward) {
                sprinting = false;
            }
            boolean grounded = onGround;
            if (jump && grounded) {
                up = 0.42;
                if (sprinting) {
                    speed += 0.2;
                }
                takeOffAt = x;
                sprintAtTakeOff = sprinting;
            }
            speed += impulse * (grounded ? 0.1 * (sprinting ? 1.3 : 1.0) : (sprinting ? 0.026 : 0.02));
            double move = speed;
            if (sneak && grounded && up <= 0.0 && x + move >= GapJumpPolicy.HALF_WIDTH) {
                move = Math.max(0.0, GapJumpPolicy.HALF_WIDTH - 0.001 - x);
            }
            // A landing above the take-off is a wall below its top: the body stops against it.
            if (rise > 0 && y < rise && x + GapJumpPolicy.HALF_WIDTH <= gap + 1.0E-9
                    && x + move + GapJumpPolicy.HALF_WIDTH > gap) {
                move = Math.max(0.0, gap - GapJumpPolicy.HALF_WIDTH - 0.001 - x);
                speed = 0.0;
                hitTheFace = true;
            }
            x += move;
            double before = y;
            y += up;
            lastUp = up;
            up = (up - 0.08) * 0.98;
            speed *= grounded ? 0.546 : 0.91;
            double floor = floorAt(x);
            if (y <= floor && before >= floor - 1.0E-9) {
                y = floor;
                up = 0.0;
                onGround = true;
            } else {
                onGround = false;
                inTheHole |= floor == Double.NEGATIVE_INFINITY && y < Math.min(0.0, rise) - 1.0E-9;
            }
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "gap %d rise %d: tick %d at x %.3f y %.3f speed %.3f, took off at %.3f%s%s", gap, rise,
                    ticks, x, y, speed, takeOffAt, sprintAtTakeOff ? " sprinting" : "",
                    hitTheFace ? ", hit the landing's face" : "");
        }
    }
}
