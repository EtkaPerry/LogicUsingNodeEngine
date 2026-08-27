package com.etka.lune.bot.util;

/**
 * Pure rules for holding an area; kept testable without Minecraft.
 *
 * <p>The only real decision here is where to hand control back, and it must not be the boundary
 * itself. A monitor that releases the moment the bot is back on the line hands over to a job that
 * was already walking outwards, which crosses again on the next step and takes the controls back -
 * a boundary with no hysteresis is a stutter at the edge of the circle rather than a leash.
 */
public final class LeashPolicy {

    /** Share of the radius the bot must be back inside before work resumes. */
    private static final double RETURN_SHARE = 0.8;
    /** However large the area, never insist on walking back more than this to resume. */
    private static final int MAX_RETURN_MARGIN = 16;

    private LeashPolicy() {}

    /** Whether a distance from the anchor counts as having left the area. */
    public static boolean outside(double distance, int radius) {
        return distance > radius;
    }

    /**
     * How far in the bot has to come before the job gets its controls back: a fifth of the radius,
     * capped so a large area does not demand a long walk to the middle for a step over the line.
     */
    public static int returnRadius(int radius) {
        int safe = Math.max(1, radius);
        int margin = Math.min(MAX_RETURN_MARGIN, Math.max(1, (int) Math.round(safe * (1.0 - RETURN_SHARE))));
        return Math.max(1, safe - margin);
    }
}
