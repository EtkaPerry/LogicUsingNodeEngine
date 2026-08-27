package com.etka.lune.bot.task;

/** Pure transition rules for the speedrun's overworld iron/bucket phase. */
final class SpeedrunIronPolicy {

    /**
     * Give a visible prospect enough depth to reach ordinary overworld iron layers. The search is
     * still bounded and rotates to a new loaded area after the pocket is exhausted; the staircase
     * only exposes blocks the player can physically reach, so this is not an omniscient quarry.
     */
    static final int PROSPECT_STAIR_STEPS = 6;
    static final int PROSPECT_MAX_ATTEMPTS = 8;
    /** Vanilla bucket recipe. */
    static final int BUCKET_IRON = 3;
    /** Vanilla flint-and-steel recipe. */
    static final int LIGHTER_IRON = 1;
    /**
     * A full 4x5 frame, corners included.
     * <p>
     * Ten is the famous number, but ten is what you need when you can take the corners back out
     * afterwards. A bot placing a frame has to click each block against something solid, and the
     * side columns have nothing under them until the bottom corners are there - and obsidian needs
     * a diamond pickaxe to reclaim, which a run at this stage does not have. So the corners stay,
     * and the frame costs fourteen.
     */
    static final int PORTAL_OBSIDIAN = 14;

    private SpeedrunIronPolicy() {
    }

    static boolean suppliesReady(int ingots, int needed) {
        return ingots >= needed;
    }

    /**
     * How much iron this run still has to dig up, given what it is already carrying.
     * <p>
     * The old fixed budget of four ingots was right only for a run that starts with nothing. A
     * flint and steel out of a ruined portal chest, or a bucket out of a village, removes its own
     * cost from the total - and a run that has enough obsidian and a way to light it does not need
     * either tool, so it needs no iron at all. Charging for tools already in the bag is how a run
     * ends up mining four deterministic surplus ingots before it will look at anything else.
     */
    static int ironNeeded(boolean hasBucket, boolean hasLighter, boolean obsidianRouteReady) {
        if (obsidianRouteReady) {
            return 0;
        }
        return (hasBucket ? 0 : BUCKET_IRON) + (hasLighter ? 0 : LIGHTER_IRON);
    }

    /**
     * True when the run can raise a portal frame from carried obsidian instead of casting one out
     * of lava, which skips the bucket, the gravel search and the lava pool entirely.
     */
    static boolean obsidianRouteReady(int obsidian, boolean hasLighter) {
        return hasLighter && obsidian >= PORTAL_OBSIDIAN;
    }

    static boolean needsMiningTool(boolean canHarvestIronOre) {
        return !canHarvestIronOre;
    }

    /**
     * An empty mine search did not satisfy the phase. The next attempt must scout a different
     * area before rebuilding the same MineTask.
     */
    static boolean shouldScoutAfterMine(boolean mineMadeProgress, boolean suppliesReady) {
        return !suppliesReady && !mineMadeProgress;
    }

    static int prospectStepBudget() {
        return PROSPECT_STAIR_STEPS * PROSPECT_MAX_ATTEMPTS;
    }
}
