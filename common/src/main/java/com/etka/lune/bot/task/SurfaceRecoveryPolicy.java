package com.etka.lune.bot.task;

/**
 * Whether a phase that prefers dry ground may still spend an attempt on another surface recovery.
 *
 * <p>The gate itself is worth having: a fresh spawn can be standing in water, and a no-swim tree
 * search that starts in the sea is hopeless. What it must not do is consume the phase's whole retry
 * budget. A recovery that fails, or one that reports success without changing the answer, has
 * proved that this position cannot be improved by asking again - and the phase is far better off
 * working from where it is than failing the mission over a route it was never going to find.</p>
 *
 * <p>Kept separate from {@link SpeedrunTask} so the rule is testable without a Minecraft runtime.</p>
 */
public final class SurfaceRecoveryPolicy {

    private SurfaceRecoveryPolicy() {}

    /** True while a recovery is both wanted and still believed to be able to help. */
    public static boolean shouldRecover(boolean needsDryGround, boolean recoveryUnavailable) {
        return needsDryGround && !recoveryUnavailable;
    }

    /**
     * True when a finished recovery has shown it cannot improve this position: it either failed
     * outright, or it succeeded and the caller still needs dry ground.
     */
    public static boolean recoveryUnavailable(boolean recoveryFailed, boolean stillNeedsDryGround) {
        return recoveryFailed || stillNeedsDryGround;
    }
}
