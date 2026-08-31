package com.etka.lune.bot.task;

/**
 * Notices when walking to a block and being able to work it have stopped agreeing, and stops the
 * bot repeating the walk about it. Kept free of Minecraft so the rule itself can be tested.
 *
 * <h2>What goes wrong without it</h2>
 *
 * <p>Arriving and being in position are two different tests made of two different measurements.
 * The route arrives at a whole block - a goal built from the standing positions the miner thinks
 * are close enough, compared against the feet block - while the swing gate judges the player's
 * continuous x/z against a reach in fractions of a block. A spot the route accepts can therefore be
 * one the gate refuses. Neither is wrong on its own; they simply disagree, and nothing counted the
 * disagreement.</p>
 *
 * <p>The cycle that falls out of it makes no progress and never ends: the route reports success,
 * the miner releases it, the gate refuses the block, the miner builds the same route again, and it
 * succeeds again on its first tick because the bot is already standing on the goal. One recorded
 * run entered that loop at tick 524 and was still in it at tick 1,443 when the run ended - 919
 * consecutive ticks stood in the canopy of a tree it never cut.</p>
 *
 * <h2>Why movement is the thing counted</h2>
 *
 * <p>Not elapsed ticks, and not arrivals alone. An approach that ends where it started did not walk
 * anywhere - it was satisfied before a single key was pressed - so it has changed nothing the gate
 * is going to look at, and the next one will change nothing either. A real walk that ends in the
 * same refusal has at least changed where the bot is standing, and deserves to be judged afresh
 * from there. That distinction is also what makes this immune to a bot being jostled a block back
 * and forth: what matters is whether the approach moved it, not where it happens to be.</p>
 */
final class ApproachWatch {

    /**
     * Arrivals that may end without the block becoming workable before the target is written off.
     * Small, because every one of them is a tick in which the bot demonstrably did nothing.
     */
    static final int MAX_FRUITLESS_ARRIVALS = 8;
    /**
     * Targets a committed tree may lose this way before the standing spot, not the log, is the
     * problem. Written off one at a time the bot would work through a whole canopy repeating a
     * geometry that has already failed three times.
     */
    static final int MAX_FRUITLESS_TARGETS = 3;

    private int fruitlessArrivals;
    private int fruitlessTargets;

    /**
     * Records one arrival the swing gate has not yet accepted.
     *
     * @param sameTarget whether this is the same block the previous arrivals were counted against
     * @param moved      whether the approach that just finished actually moved the bot
     * @return true when this target has to be given up on rather than approached again
     */
    boolean arrived(boolean sameTarget, boolean moved) {
        if (moved) {
            fruitlessArrivals = 0;
            return false;
        }
        fruitlessArrivals = sameTarget ? fruitlessArrivals + 1 : 1;
        if (fruitlessArrivals < MAX_FRUITLESS_ARRIVALS) {
            return false;
        }
        // The target is about to be blacklisted, so the run of arrivals against it ends here.
        fruitlessArrivals = 0;
        fruitlessTargets++;
        return true;
    }

    /** Whether enough targets have gone this way that the work site is the thing to leave. */
    boolean siteIsTheProblem() {
        return fruitlessTargets >= MAX_FRUITLESS_TARGETS;
    }

    /** How long the current run of fruitless arrivals is, for the status line. */
    int fruitlessArrivals() {
        return fruitlessArrivals;
    }

    /** An arrival that ends in a swing settles everything that came before it. */
    void paidOff() {
        fruitlessArrivals = 0;
        fruitlessTargets = 0;
    }
}
