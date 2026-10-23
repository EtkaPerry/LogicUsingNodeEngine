package com.etka.lune.bot.task;

import net.minecraft.core.BlockPos;

/**
 * The way a run is making to one spot, and how often the body has been thrown off it.
 *
 * <p>A run plans a fresh route every second, and as often as not to the same spot, because the spot
 * that left the most room a second ago still does. A route whose body keeps being carried off it -
 * over the lip of a hole at a step down, by a hit - is planned again from where the feet are
 * ({@code GotoTask.offRouteReplans}), comes round to the same place, and is thrown off again. On
 * the bastion (2026-10-18) a run went round that loop at a step down beside a hole for three
 * seconds while a Zombie standing at the corner hit it every time round; the user asked for such
 * a spot to count as one the run failed to reach. Each route forgets what the one before learned,
 * so the tally is kept here, across them, for as long as the run makes for the same spot - within
 * the reach of its goal of the spot the tally began with.</p>
 *
 * <p>Pure, so the tally is tested without a world.</p>
 */
final class RunApproach {

    /** Throw-offs on the way to one spot before the spot is written off. */
    static final int MAX_THROWN_OFF = 2;

    private final int reach;
    private BlockPos spot;
    private int thrownOff;
    private int counted;

    /** @param reach how near a spot counts as the same one: the reach of the run's goal */
    RunApproach(int reach) {
        this.reach = reach;
    }

    /**
     * A new route has been planned to {@code target}. Its own count starts from nothing; the tally
     * carries on when the target is the same spot as before, and starts again when it is not.
     */
    void begin(BlockPos target) {
        if (spot == null || !near(spot, target)) {
            spot = target.immutable();
            thrownOff = 0;
        }
        counted = 0;
    }

    /**
     * Reads the current route's count of throw-offs, and says whether the spot is now one the run has
     * failed to reach.
     *
     * @param routeThrownOff how many times the current route has been thrown off since it was planned
     */
    boolean failed(int routeThrownOff) {
        if (spot == null) {
            return false;
        }
        thrownOff += Math.max(0, routeThrownOff - counted);
        counted = Math.max(counted, routeThrownOff);
        return thrownOff >= MAX_THROWN_OFF;
    }

    /** Whether {@code a} and {@code b} are the same spot to a goal of this reach. */
    boolean near(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= reach && Math.abs(a.getY() - b.getY()) <= reach
                && Math.abs(a.getZ() - b.getZ()) <= reach;
    }

    /** Forgets the spot: written off, or the chase is over. */
    void clear() {
        spot = null;
        thrownOff = 0;
        counted = 0;
    }
}
