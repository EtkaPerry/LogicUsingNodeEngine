package com.etka.lune.bot.task;

/**
 * Pure rules for reconsidering a mining target mid-walk; kept testable without Minecraft.
 *
 * <p>Committing to a target is right - a bot that retargets every tick oscillates between two
 * equally good blocks and never reaches either. Committing <em>forever</em> is not: a run was seen
 * walking three hundred blocks to one sighted tree, arriving in a forest, and carrying on to the
 * original tree as though the forest were not there. The world only loads as you approach it, so
 * the best target at the moment of choosing is routinely not the best target one chunk later.</p>
 *
 * <p>The rule is therefore hysteresis rather than freshness: a new candidate has to be dramatically
 * closer before it wins, and short approaches are never second-guessed at all.</p>
 */
final class MinePolicy {

    /** A new target must be at most this fraction of the remaining distance to displace the old. */
    static final double SWITCH_RATIO = 0.5;
    /** Below this there is nothing to save, and switching would only cause dithering. */
    static final double MIN_REMAINING_TO_RECONSIDER = 24.0;

    private MinePolicy() {}

    /**
     * Whether a newly visible block is worth abandoning the committed one for.
     *
     * @param remainingToCommitted how far the bot still has to walk to the target it chose
     * @param distanceToCandidate  how far the newly sighted block is
     */
    static boolean shouldSwitchTarget(double remainingToCommitted, double distanceToCandidate) {
        if (remainingToCommitted < MIN_REMAINING_TO_RECONSIDER) {
            return false;
        }
        if (distanceToCandidate < 0.0) {
            return false;
        }
        return distanceToCandidate <= remainingToCommitted * SWITCH_RATIO;
    }
}
