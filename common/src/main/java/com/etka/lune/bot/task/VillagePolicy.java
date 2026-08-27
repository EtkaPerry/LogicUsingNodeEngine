package com.etka.lune.bot.task;

/**
 * Pure rules for taking what a village already has; kept testable without Minecraft.
 *
 * <p>A village is the single best thing that can happen to a speedrun, and the reason is always the
 * same: somebody else already did the work. The houses are cobblestone that nobody has to dig for,
 * the farms are food that nobody has to hunt, and the smithy chest is iron that nobody has to mine
 * - which matters more than the rest put together, because iron is where a run spends most of its
 * time.</p>
 *
 * <p>The thresholds exist so that "opportunistic" does not become "distracted". Walking twenty
 * blocks to break four cobblestone out of a wall is not faster than the staircase it replaces.</p>
 */
final class VillagePolicy {

    /** One hay bale crafts back into nine wheat. */
    static final int WHEAT_PER_HAY = 9;
    /** Three wheat make one bread. */
    static final int WHEAT_PER_BREAD = 3;
    /** A village worth stopping at is worth clearing properly; twelve bales is thirty-six bread. */
    static final int HAY_WANTED = 12;
    /**
     * Below this a hoe costs more than it saves. A hoe roughly halves the time to break a bale, so
     * the two planks, two sticks and a trip to the table only pay for themselves over a real haul.
     */
    static final int HAY_FOR_HOE = 6;
    /** Placed stone is only a shortcut if there is enough of it to skip the staircase entirely. */
    static final int MIN_PLACED_STONE = 4;

    private VillagePolicy() {}

    /**
     * Whether to break stone out of what is already built instead of digging for it.
     * <p>
     * Village walls, paths and exposed mountain faces are all the same thing to this: cobblestone
     * standing in the open, at ground level, with no staircase and no risk of opening a cave.
     */
    static boolean worthTakingPlacedStone(int visibleBlocks, int stillNeeded) {
        if (stillNeeded <= 0) {
            return false;
        }
        return visibleBlocks >= Math.min(stillNeeded, MIN_PLACED_STONE);
    }

    /** How many bales to take, given what is in sight and how much food the run already carries. */
    static int hayWorthTaking(int visibleHay, int carriedFood) {
        if (visibleHay <= 0 || carriedFood >= HAY_WANTED * 2) {
            return 0;
        }
        return Math.min(visibleHay, HAY_WANTED);
    }

    /** Whether a hoe pays for itself on this many bales. */
    static boolean hoeWorthCrafting(int hayToBreak, boolean haveHoe, int planks, int sticks) {
        if (haveHoe || hayToBreak < HAY_FOR_HOE) {
            return false;
        }
        return planks >= 2 && sticks >= 2;
    }

    /** Loaves obtainable from a number of bales, once they are unpacked into wheat. */
    static int breadFrom(int hayBales) {
        return Math.max(0, hayBales) * WHEAT_PER_HAY / WHEAT_PER_BREAD;
    }

    /**
     * Whether a tool is now worth more as inventory space than as a tool. A hoe exists to strip a
     * farm; once the farm is stripped it is a stack slot.
     */
    static boolean shouldDropHoe(boolean haveHoe, int hayLeftToBreak, boolean inventoryFull) {
        return haveHoe && inventoryFull && hayLeftToBreak <= 0;
    }
}
