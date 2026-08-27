package com.etka.lune.bot.task;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure rules for turning a flock of sheep into a bed; kept testable without Minecraft.
 *
 * <p>A bed needs three wool <em>of one colour</em>, and a flock is not one colour. The interesting
 * decision is therefore not "kill sheep" but "given the wool in the bag and the flowers in sight,
 * which colour is cheapest to end up with" - and the answer is often not the colour the bot has
 * most of, because the dye for that colour may not grow anywhere nearby. A dandelion turns three
 * mismatched wool into a bed; a lily of the valley turns two white and one brown into a bed for a
 * single dye. Both are better than walking away from the flock.</p>
 *
 * <p>Dyeing part of a mismatched set is safe, which is not obvious and is what makes the cheap plan
 * worth having: vanilla's {@code dye_&lt;colour&gt;_wool} recipe lists the other fifteen wools as its
 * ingredient and excludes the target colour, so a craft can never consume a wool that already
 * matches. Every dye therefore raises the count by one, and asking for one more white cannot loop.</p>
 *
 * <p>Colours are plain lowercase names ({@code white}, {@code light_gray}) so this stays free of
 * Minecraft types and the arithmetic can be tested on its own.</p>
 */
final class BedPolicy {

    static final int WOOL_PER_BED = 3;
    static final int PLANKS_PER_BED = 3;

    private BedPolicy() {}

    /** What still has to happen before three matching wool exist. */
    enum Step {
        /** Not enough wool of any colour yet; kill more sheep. */
        NEED_WOOL,
        /** Three of one colour are already in the bag. */
        READY,
        /** Three can be reached by dyeing what is carried. */
        DYE,
        /** Enough wool, but no colour can be made to match with what is in reach. */
        NO_MATCHING_DYE
    }

    /**
     * @param step          what to do next
     * @param colour        the colour the bed will be made of, empty unless READY or DYE
     * @param woolToDye     how many carried wool have to be dyed into {@code colour}
     * @param woolStillNeeded how many more wool of any colour are needed, for NEED_WOOL
     */
    record Plan(Step step, String colour, int woolToDye, int woolStillNeeded) {

        static Plan needWool(int missing) {
            return new Plan(Step.NEED_WOOL, "", 0, missing);
        }

        static Plan ready(String colour) {
            return new Plan(Step.READY, colour, 0, 0);
        }

        static Plan dye(String colour, int woolToDye) {
            return new Plan(Step.DYE, colour, woolToDye, 0);
        }

        static Plan noMatchingDye() {
            return new Plan(Step.NO_MATCHING_DYE, "", 0, 0);
        }
    }

    /**
     * Whether a flock in view is worth leaving the route for.
     *
     * <p>Three sheep is the headline case because three sheep is a bed. The general form is "enough
     * animals in sight to finish the bed from here": a run already carrying one wool only needs
     * two, and should not walk past them waiting for a flock of three.</p>
     */
    static boolean worthDivertingForSheep(int sheepInView, int carriedWool, boolean carryingBed) {
        if (carryingBed || carriedWool >= WOOL_PER_BED) {
            return false;
        }
        return sheepInView > 0 && sheepInView >= WOOL_PER_BED - carriedWool;
    }

    /**
     * Chooses the cheapest colour to end up with three of.
     *
     * @param woolByColour  carried wool, counted per colour
     * @param availableDye  how many dyes of each colour the bot can have shortly: what it already
     *                      carries plus what the flowers it can see would craft into
     */
    static Plan plan(Map<String, Integer> woolByColour, Map<String, Integer> availableDye) {
        int total = 0;
        for (int count : woolByColour.values()) {
            total += Math.max(0, count);
        }
        if (total < WOOL_PER_BED) {
            return Plan.needWool(WOOL_PER_BED - total);
        }

        Set<String> candidates = new TreeSet<>();
        candidates.addAll(woolByColour.keySet());
        candidates.addAll(availableDye.keySet());

        String best = null;
        int bestDye = Integer.MAX_VALUE;
        for (String colour : candidates) {
            int have = Math.min(WOOL_PER_BED, Math.max(0, woolByColour.getOrDefault(colour, 0)));
            int needed = WOOL_PER_BED - have;
            if (needed > Math.max(0, availableDye.getOrDefault(colour, 0))) {
                // Cannot make enough of this colour's dye here, so this colour is not a candidate
                // however much wool of it is already carried.
                continue;
            }
            if (needed < bestDye) {
                bestDye = needed;
                best = colour;
            }
        }

        if (best == null) {
            return Plan.noMatchingDye();
        }
        return bestDye == 0 ? Plan.ready(best) : Plan.dye(best, bestDye);
    }

    /** How many flowers to pick for {@code dyeNeeded} dyes, given one flower's yield. */
    static int flowersNeeded(int dyeNeeded, int dyePerFlower) {
        if (dyeNeeded <= 0) {
            return 0;
        }
        int yield = Math.max(1, dyePerFlower);
        return (dyeNeeded + yield - 1) / yield;
    }

    /** Colours the bot could dye to, given carried dye and the flowers it can currently see. */
    static Set<String> reachableColours(Map<String, Integer> availableDye) {
        Set<String> colours = new HashSet<>();
        for (Map.Entry<String, Integer> entry : availableDye.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                colours.add(entry.getKey());
            }
        }
        return colours;
    }
}
