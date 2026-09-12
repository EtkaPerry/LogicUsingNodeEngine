package com.etka.lune.bot.task;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stepping sideways without turning, checked against the game's own frame rather than against my
 * reading of it: the keys are rotated by the yaw before they become motion, so every claim here is
 * "press these, and vanilla moves the player that way".
 */
class StepPolicyTest {

    /**
     * The game's forward transform, written out so the policy can be checked against it. A forward
     * press {@code f} and a left press {@code s} become this world offset - see
     * {@code Entity.getInputVector}.
     */
    private static double[] vanillaMotion(double forward, double left, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        return new double[]{left * cos - forward * sin, forward * cos + left * sin};
    }

    private static double impulseValue(boolean positive, boolean negative) {
        return (positive ? 1 : 0) - (negative ? 1 : 0);
    }

    /** Facing south, your right hand points west. Clockwise from south is west. */
    @Test
    void rightIsTheHandTheBotWouldRaise() {
        assertEquals(Direction.WEST, StepPolicy.axis(Direction.SOUTH, StepPolicy.Side.RIGHT));
        assertEquals(Direction.EAST, StepPolicy.axis(Direction.SOUTH, StepPolicy.Side.LEFT));
        assertEquals(Direction.EAST, StepPolicy.axis(Direction.NORTH, StepPolicy.Side.RIGHT));
        assertEquals(Direction.SOUTH, StepPolicy.axis(Direction.EAST, StepPolicy.Side.RIGHT));
        assertEquals(Direction.NORTH, StepPolicy.axis(Direction.NORTH, StepPolicy.Side.FORWARD));
        assertEquals(Direction.SOUTH, StepPolicy.axis(Direction.NORTH, StepPolicy.Side.BACK));
    }

    /** A vertical facing is not a direction to walk in, so it is treated as one that is. */
    @Test
    void aVerticalFacingStillGivesAHorizontalStep() {
        assertTrue(StepPolicy.axis(Direction.UP, StepPolicy.Side.RIGHT).getAxis().isHorizontal());
        assertTrue(StepPolicy.axis(Direction.DOWN, StepPolicy.Side.FORWARD).getAxis().isHorizontal());
    }

    /**
     * The heart of it: whatever keys the policy picks, feeding them through the game's own
     * transform has to move the player towards where it was asked to go. Checked at angles that
     * are not multiples of ninety, because those are the ones a hand-written mapping gets wrong.
     */
    @Test
    void theKeysItPicksMoveTheBotTowardsTheOffset() {
        double[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {3, 4}, {-2, 5}, {-6, -1}};
        float[] yaws = {0, 45, 90, 137.5F, 180, -90, -172.25F, 359};
        for (float yaw : yaws) {
            for (double[] offset : offsets) {
                StepPolicy.Impulse impulse = StepPolicy.toward(offset[0], offset[1], yaw, 0.06);
                assertTrue(impulse.any(), "nothing pressed for " + offset[0] + "," + offset[1]);
                double[] motion = vanillaMotion(
                        impulseValue(impulse.forward(), impulse.backward()),
                        impulseValue(impulse.left(), impulse.right()),
                        yaw);
                // Positive dot product: the motion those keys produce has a component pointing at
                // the target. Eight key combinations cannot be exact, but none may point away.
                double dot = motion[0] * offset[0] + motion[1] * offset[1];
                assertTrue(dot > 0, "yaw " + yaw + " moved away from " + offset[0] + "," + offset[1]
                        + " (motion " + motion[0] + "," + motion[1] + ")");
            }
        }
    }

    /** Facing south with the target due west, the bot presses right - and never turns to look. */
    @Test
    void aSidestepIsAStrafeNotATurn() {
        // Yaw 0 is facing south. West is -X.
        StepPolicy.Impulse impulse = StepPolicy.toward(-1, 0, 0, 0.06);
        assertTrue(impulse.right(), "west is on the right when facing south");
        assertFalse(impulse.left());
        assertFalse(impulse.forward());
        assertFalse(impulse.backward());
    }

    /** Inside the dead zone nothing is pressed, which is what lets the bot coast to a stop. */
    @Test
    void tinyOffsetsPressNothing() {
        assertFalse(StepPolicy.toward(0.01, -0.02, 33, 0.06).any());
        assertEquals(StepPolicy.Impulse.NONE, StepPolicy.toward(0, 0, 90, 0.06));
    }

    @Test
    void anUnknownSideFallsBackToTheOneTheCardOpensOn() {
        assertEquals(StepPolicy.Side.RIGHT, StepPolicy.Side.fromLabel("nonsense"));
        assertEquals(StepPolicy.Side.BACK, StepPolicy.Side.fromLabel("back"));
        assertEquals(java.util.List.of("Right", "Left", "Forward", "Back"), StepPolicy.Side.labels());
    }
}
