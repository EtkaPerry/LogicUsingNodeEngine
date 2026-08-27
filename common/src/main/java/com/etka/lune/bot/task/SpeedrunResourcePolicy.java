package com.etka.lune.bot.task;

/**
 * Small, testable resource budgets for the speedrun route.
 *
 * <p>The route asks for resources that pay for a concrete next action. It must not mine a round
 * number just because an older route happened to carry that much.</p>
 */
public final class SpeedrunResourcePolicy {

    private static final int COBBLE_PER_STONE_PICKAXE = 3;
    private static final int COBBLE_PER_STONE_SWORD = 2;
    /**
     * An axe is three cobble and pays for itself on the next tree.
     * <p>
     * It used to be bought only out of surplus, which meant it was never bought at all: the budget
     * below asks for exactly what the required kit costs, so there is no surplus by construction.
     * Meanwhile the route keeps going back for wood - planks for tables, a bed, a boat, more sticks
     * - and does all of it by hand. Three cobble is one extra stone block.
     */
    private static final int COBBLE_PER_STONE_AXE = 3;
    /**
     * A shovel is one cobble and it is the difference between digging dirt and scraping it.
     *
     * <p>Every staircase starts through several blocks of dirt, every gravel bank in the flint
     * phase is dirt-speed work, and a pickaxe is the wrong tool for all of it - roughly three times
     * slower than a shovel and it was what the bot reached for, because it was the only digging
     * tool it owned. One cobble is the cheapest item in the kit.
     */
    private static final int COBBLE_PER_STONE_SHOVEL = 1;
    private static final int COBBLE_PER_FURNACE = 8;
    private static final int PLANKS_FOR_TABLE_AND_STICKS = 8;
    private static final int PLANKS_FOR_SHIELD = 6;

    private SpeedrunResourcePolicy() {}

    /** Remaining cobble costs, not a padded stockpile target. */
    public static int remainingCobblestone(boolean needsStonePickaxe,
                                           boolean needsWeapon,
                                           boolean needsFurnace) {
        return remainingCobblestone(needsStonePickaxe, needsWeapon, needsFurnace, false);
    }

    /** As above, including the axe once it is treated as part of the kit rather than a spare. */
    public static int remainingCobblestone(boolean needsStonePickaxe,
                                           boolean needsWeapon,
                                           boolean needsFurnace,
                                           boolean needsAxe) {
        return remainingCobblestone(needsStonePickaxe, needsWeapon, needsFurnace, needsAxe, false);
    }

    /** As above, including the shovel. */
    public static int remainingCobblestone(boolean needsStonePickaxe,
                                           boolean needsWeapon,
                                           boolean needsFurnace,
                                           boolean needsAxe,
                                           boolean needsShovel) {
        int total = 0;
        if (needsShovel) {
            total += COBBLE_PER_STONE_SHOVEL;
        }
        if (needsStonePickaxe) {
            total += COBBLE_PER_STONE_PICKAXE;
        }
        if (needsWeapon) {
            total += COBBLE_PER_STONE_SWORD;
        }
        if (needsAxe) {
            total += COBBLE_PER_STONE_AXE;
        }
        if (needsFurnace) {
            total += COBBLE_PER_FURNACE;
        }
        return total;
    }

    /** Planks needed before leaving the wood phase. */
    public static int woodProductsNeeded(boolean needsShield) {
        return PLANKS_FOR_TABLE_AND_STICKS + (needsShield ? PLANKS_FOR_SHIELD : 0);
    }

    /** Amount still worth gathering when a resumed run already carries part of a target. */
    public static int remaining(int target, int carried) {
        return Math.max(0, target - Math.max(0, carried));
    }
}
