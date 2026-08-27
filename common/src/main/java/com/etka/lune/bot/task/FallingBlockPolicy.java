package com.etka.lune.bot.task;

/**
 * Pure rules for mining gravel and sand, which do not stay where they are put.
 *
 * <p>Everything else in the world waits to be broken. A gravel column does not: break the bottom of
 * one and the blocks above collapse into the hole, which is why a bot that treats gravel as an
 * ordinary block ends up jumping at a wall of it, and why the flint it just earned is buried under
 * the block that fell on top of it. A player digs a gravel bank from the top down for exactly this
 * reason.
 *
 * <p>Kept free of Minecraft types so it can be tested; the caller supplies the column measurements.
 */
final class FallingBlockPolicy {

    /**
     * How far above a target to look for more of the same falling material.
     *
     * <p>Gravel banks are rarely deeper than this, and the scan costs a block lookup per level.
     */
    static final int MAX_COLUMN_SCAN = 8;

    private FallingBlockPolicy() {}

    /**
     * How far above the chosen block the bot should actually dig.
     *
     * @param stackedAbove how many falling blocks of the same kind sit directly on top of it
     * @return the offset to add to the target's Y; zero when the block is already the top one
     */
    static int digOffset(int stackedAbove) {
        return Math.max(0, Math.min(stackedAbove, MAX_COLUMN_SCAN));
    }

    /**
     * Whether breaking here would drop material onto the bot or onto the drop it is about to make.
     *
     * <p>Used to decide whether the target needs raising at all, so an isolated gravel block on the
     * beach is mined where it lies rather than paying for a column scan.
     */
    static boolean wouldCollapse(int stackedAbove) {
        return stackedAbove > 0;
    }

    /**
     * Where a drop from a falling-block dig ends up relative to the block that was broken.
     *
     * <p>Breaking the top of a column leaves the drop at the top, but the rest of the column then
     * settles and the item falls with it. Looking only at the broken position is how the bot loses
     * sight of its own flint and reports "checking whether the drop landed" at empty air.
     *
     * @param stackedBelow blocks of the same falling material underneath the broken one
     * @return how far below the broken position the drop is likely to come to rest
     */
    static int expectedDropFall(int stackedBelow) {
        return Math.max(0, stackedBelow);
    }
}
