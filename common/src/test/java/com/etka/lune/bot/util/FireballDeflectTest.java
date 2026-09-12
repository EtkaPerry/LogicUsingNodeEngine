package com.etka.lune.bot.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two sums a fireball answer rests on: whether it is coming, and where to point it back.
 *
 * <p>Both are geometry on plain vectors, which is the whole reason they are separated from the
 * entity lookups - getting them wrong in a live Nether costs a bot, and nothing about the question
 * needs a world to ask it.
 */
class FireballDeflectTest {

    private static final Vec3 BODY = new Vec3(0.0, 1.0, 0.0);

    @Test
    void aShotHeadedAtTheBodyIsComing() {
        // Twelve blocks out on the +X axis, travelling straight at us.
        assertTrue(FireballDeflect.onCourse(BODY, new Vec3(12.0, 1.0, 0.0), new Vec3(-1.2, 0.0, 0.0)));
    }

    @Test
    void aShotThatHasGonePastIsNotComingBackByItself() {
        // Half a block away, but travelling away: this is the tick after a successful deflection,
        // and treating it as a threat is how the bot swings at its own kill shot.
        assertFalse(FireballDeflect.onCourse(BODY, new Vec3(0.5, 1.0, 0.0), new Vec3(1.2, 0.0, 0.0)));
    }

    @Test
    void aShotCrossingTheValleyIsSomebodyElsesProblem() {
        // Twelve blocks out and aimed twenty blocks wide of us. The distance alone would have
        // called this a threat; the flight path is what says otherwise.
        assertFalse(FireballDeflect.onCourse(BODY, new Vec3(12.0, 1.0, 0.0),
                new Vec3(-1.2, 0.0, 2.0)));
    }

    @Test
    void aNearMissStillCountsBecauseItStillExplodes() {
        // Lined up to pass about a block and a half to the side at closest approach. A Ghast's
        // fireball takes hearts out several blocks from where it lands, so this is worth answering.
        Vec3 shot = new Vec3(10.0, 1.0, 1.5);
        assertTrue(FireballDeflect.onCourse(BODY, shot, new Vec3(-1.2, 0.0, 0.0)));

        // Six blocks wide of the body is past anything the blast reaches.
        assertFalse(FireballDeflect.onCourse(BODY, new Vec3(10.0, 1.0, 6.0), new Vec3(-1.2, 0.0, 0.0)));
    }

    @Test
    void aStoppedShotIsNotAHeading() {
        assertFalse(FireballDeflect.onCourse(BODY, new Vec3(4.0, 1.0, 0.0), Vec3.ZERO));
        assertEquals(Double.POSITIVE_INFINITY, FireballDeflect.ticksToArrive(4.0, 0.0));
    }

    @Test
    void theArrivalEstimateIsTheGapOverTheSpeed() {
        assertEquals(5.0, FireballDeflect.ticksToArrive(6.0, 1.2), 1.0E-9);
    }

    @Test
    void aStillGhastIsAimedAtDirectly() {
        Vec3 ghast = new Vec3(30.0, 20.0, 0.0);
        assertHeading(ghast, FireballDeflect.leadShooter(BODY, ghast, Vec3.ZERO));
    }

    @Test
    void aDriftingGhastIsLedByTheDeflectionsOwnFlightTime() {
        // Twenty-five blocks out, drifting a third of a block a tick. The batted shot needs about
        // nineteen ticks to get there, so the aim has to sit six-odd blocks ahead of the Ghast -
        // more than its own width, which is why aiming at where it is misses entirely.
        Vec3 shot = new Vec3(4.0, 1.0, 0.0);
        Vec3 ghast = new Vec3(29.0, 1.0, 0.0);
        Vec3 drift = new Vec3(0.0, 0.0, 0.33);

        int flight = FireballDeflect.deflectedFlightTicks(ghast.distanceTo(shot));
        Vec3 aim = FireballDeflect.leadShooter(shot, ghast, drift);
        Vec3 lead = aim.subtract(ghast);

        assertTrue(flight >= 15 && flight <= 25, "flight time out of the expected band: " + flight);
        // The aim sits straight downrange of the drift and nowhere else.
        assertEquals(0.0, lead.normalize().distanceTo(drift.normalize()), 1.0E-9);
        // Sized by the flight, allowing for the correction pass: leading pushes the target further
        // away, which buys it another tick or two of drift.
        assertTrue(lead.length() >= drift.length() * flight,
                "the lead is shorter than the first estimate: " + lead.length());
        assertTrue(lead.length() <= drift.length() * (flight + 3),
                "the correction pass ran away: " + lead.length());
        assertTrue(lead.length() > 4.0,
                "the lead has to exceed the Ghast's own width or it is not doing anything: "
                        + lead.length());
    }

    @Test
    void aResyncJumpIsNotMotionToLead() {
        // A position correction arrives as one tick of enormous apparent speed. Multiplied by a
        // twenty-tick flight it would aim hundreds of blocks into the sky, so it is ignored.
        Vec3 ghast = new Vec3(29.0, 1.0, 0.0);
        assertHeading(ghast, FireballDeflect.leadShooter(new Vec3(4.0, 1.0, 0.0), ghast,
                new Vec3(0.0, 18.0, 0.0)));
    }

    @Test
    void theFlightEstimateGrowsWithTheRangeAndIsBounded() {
        assertTrue(FireballDeflect.deflectedFlightTicks(10.0)
                < FireballDeflect.deflectedFlightTicks(30.0));
        assertEquals(0, FireballDeflect.deflectedFlightTicks(0.0));
        // The ceiling stops a shooter on the far side of the world spinning the loop.
        assertTrue(FireballDeflect.deflectedFlightTicks(1.0E9) <= 200);
    }

    @Test
    void withNoShooterOnRecordTheShotGoesBackTheWayItCame() {
        // One block back up the flight path from the eye, which is a look direction rather than a
        // place: the deflection only uses the angle.
        assertHeading(BODY.add(1.0, 0.0, 0.0),
                FireballDeflect.backAlongFlight(BODY, new Vec3(-1.2, 0.0, 0.0)));
    }

    @Test
    void nothingToGoOnLeavesTheAimAlone() {
        assertNull(FireballDeflect.backAlongFlight(BODY, Vec3.ZERO));
    }

    /** Normalising divides by a square root, so aims are compared to a tolerance, not exactly. */
    private static void assertHeading(Vec3 expected, Vec3 actual) {
        assertEquals(0.0, expected.distanceTo(actual), 1.0E-9,
                "expected aim " + expected + " but was " + actual);
    }
}
