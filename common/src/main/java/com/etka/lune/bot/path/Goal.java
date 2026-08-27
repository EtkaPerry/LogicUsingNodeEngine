package com.etka.lune.bot.path;

import net.minecraft.core.BlockPos;

/**
 * What the pathfinder is trying to achieve. Separating "where do I want to be" from "how do I get
 * there" is what lets one A* implementation serve goto, mine, kill and fish alike - each task just
 * supplies a different goal.
 */
public interface Goal {

    /** True when standing at {@code pos} satisfies this goal. */
    boolean isReached(BlockPos pos);

    /**
     * Estimated remaining cost from {@code pos}. Must never overestimate, or A* can return a
     * non-optimal path.
     */
    double heuristic(BlockPos pos);

    /** Short human-readable form, shown on the Main tab while the bot runs. */
    String describe();
}
