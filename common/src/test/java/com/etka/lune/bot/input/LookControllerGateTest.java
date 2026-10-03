package com.etka.lune.bot.input;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * While the camera is not hers, nothing turns and she is aimed at nothing - which is what keeps
 * every aimed click a card makes off the player's own crosshair.
 */
class LookControllerGateTest {

    @Test
    void aClosedCameraTurnsNothingAndAimsAtNothing() {
        LookController look = new LookController(() -> false);
        assertFalse(look.mayTurn());
        // No player is touched on the way: the answer comes before anything is read off one.
        look.lookAt(null, Vec3.ZERO);
        look.lookAtRotation(null, 90.0F, 10.0F);
        assertFalse(look.isLookingAt(null, Vec3.ZERO, 180.0F),
                "where the player points is not where she aimed");
        assertFalse(look.isLookingAtRotation(null, 0.0F, 0.0F, 180.0F));
    }

    @Test
    void theCameraFollowsWhatTheEngineSaysThisTick() {
        boolean[] hers = {false};
        LookController look = new LookController(() -> hers[0]);
        assertFalse(look.mayTurn());
        hers[0] = true;
        assertTrue(look.mayTurn(), "asked every tick, so a handover takes effect at once");
        assertTrue(new LookController().mayTurn(), "a controller with no gate always turns");
    }
}
