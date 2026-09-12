package com.etka.lune.bot.input;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The virtual keyboard the bot "presses". Tasks and the path executor write to this each tick;
 * {@link BotClientInput} reads it and feeds it to the player.
 */
public final class BotInput {

    public boolean forward;
    public boolean backward;
    public boolean left;
    public boolean right;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;

    /** Cleared at the start of every bot tick so stale presses can never stick. */
    public void reset() {
        forward = backward = left = right = jump = sneak = sprint = false;
    }

    /**
     * Presses the key combination that moves toward {@code target} in world space, whatever the head
     * is doing.
     *
     * <p>This is the half of movement that does not belong to {@link LookController}. Holding W and
     * turning is the only way to travel somewhere the bot is also looking at, but plenty of moments
     * need the two separated - sidestepping a fireball while watching the Ghast that threw it, or
     * swimming to the surface while the camera stays on the exit. Which keys those are is a question
     * about the player's yaw and nothing else, so it lives on the keyboard rather than in each
     * caller.
     */
    public void steerToward(LocalPlayer player, Vec3 target) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            // Already on the spot horizontally. Pressing anything here would be a guess.
            return;
        }

        // Minecraft yaw: 0 faces +Z, and increasing yaw turns right.
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        double relative = Mth.wrapDegrees(desiredYaw - player.getYRot());

        if (Math.abs(relative) < 67.5) {
            forward = true;
        }
        if (Math.abs(relative) > 112.5) {
            backward = true;
        }
        if (relative >= 22.5 && relative <= 157.5) {
            right = true;
        }
        if (relative <= -22.5 && relative >= -157.5) {
            left = true;
        }
    }
}
