package com.etka.lune.games;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Deals riddles: picks a recipe the player has not been asked, and lays out the items to answer it
 * with among decoys.
 *
 * <p>Every recipe the game knows can be asked, a mod's as readily as the game's own; which recipes
 * those are is the catalog's business. The decoys are things crafted with somewhere else, and never
 * anything the recipe would take - a decoy that fitted would not be a decoy.</p>
 */
public final class RiddleDeck {

    private RiddleDeck() {}

    /**
     * Whether a recipe makes a riddle: two squares at least, not nine of one thing - a storage block
     * is a count, not a riddle - not a single kind of item in any order, and not something made from
     * itself, as a recolouring or a repair is.
     */
    public static boolean worthAsking(RiddleRecipe recipe) {
        if (recipe.filled() < 2) {
            return false;
        }
        Set<Set<String>> kinds = new HashSet<>();
        for (Set<String> cell : recipe.cells()) {
            if (!cell.isEmpty()) {
                kinds.add(cell);
            }
        }
        if (kinds.size() == 1 && (recipe.shapeless() || recipe.filled() == RiddleMatcher.CELLS)) {
            return false;
        }
        return !recipe.accepts(recipe.result());
    }

    /**
     * Whether every recipe worth asking has been asked, so the record can start over. False when
     * there is nothing worth asking at all: an empty recipe book is no reason to forget.
     */
    public static boolean exhausted(List<RiddleRecipe> recipes, IntPredicate dealtBefore) {
        boolean any = false;
        for (RiddleRecipe recipe : recipes) {
            if (worthAsking(recipe)) {
                if (!dealtBefore.test(recipe.fingerprint())) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    /**
     * A riddle from {@code recipes}, one {@code dealtBefore} does not remember, or null when none is
     * worth asking. {@code universe} is what decoys are drawn from.
     */
    public static RiddleGame deal(List<RiddleRecipe> recipes, List<String> universe, IntPredicate dealtBefore,
                                  Random random) {
        List<RiddleRecipe> worth = new ArrayList<>();
        List<RiddleRecipe> fresh = new ArrayList<>();
        for (RiddleRecipe recipe : recipes) {
            if (worthAsking(recipe)) {
                worth.add(recipe);
                if (!dealtBefore.test(recipe.fingerprint())) {
                    fresh.add(recipe);
                }
            }
        }
        if (worth.isEmpty()) {
            return null;
        }
        List<RiddleRecipe> from = fresh.isEmpty() ? worth : fresh;
        RiddleRecipe recipe = from.get(random.nextInt(from.size()));
        return new RiddleGame(recipe, pool(recipe, universe, random), random);
    }

    /**
     * The items to answer {@code recipe} with, shuffled: one kind of item per kind of square, a stack
     * of as many as the recipe needs, and decoys making the pool up to {@link RiddleGame#POOL}.
     */
    static List<RiddleGame.Stack> pool(RiddleRecipe recipe, List<String> universe, Random random) {
        Map<String, Integer> needed = new LinkedHashMap<>();
        Map<Set<String>, String> chosen = new HashMap<>();
        for (Set<String> cell : recipe.cells()) {
            if (cell.isEmpty()) {
                continue;
            }
            String item = chosen.computeIfAbsent(cell, set -> representative(set, needed.keySet()));
            needed.merge(item, 1, Integer::sum);
        }
        List<RiddleGame.Stack> stacks = new ArrayList<>(RiddleGame.POOL);
        needed.forEach((item, count) -> stacks.add(new RiddleGame.Stack(item, count, false)));

        List<String> candidates = new ArrayList<>();
        for (String item : new LinkedHashSet<>(universe)) {
            if (!recipe.accepts(item) && !item.equals(recipe.result())) {
                candidates.add(item);
            }
        }
        Collections.shuffle(candidates, random);
        int wanted = Math.max(0, RiddleGame.POOL - stacks.size());
        // Half from the recipe's own mod, the game counting as one: otherwise a riddle from a mod is
        // solved by spotting the only items from that mod, and one of the game's by the reverse.
        String home = namespace(recipe.result());
        List<String> decoys = new ArrayList<>(wanted);
        for (String item : candidates) {
            if (decoys.size() < wanted / 2 && namespace(item).equals(home)) {
                decoys.add(item);
            }
        }
        for (String item : candidates) {
            if (decoys.size() < wanted && !decoys.contains(item)) {
                decoys.add(item);
            }
        }
        for (String item : decoys) {
            stacks.add(new RiddleGame.Stack(item, decoyCount(random), true));
        }
        Collections.shuffle(stacks, random);
        return stacks;
    }

    /**
     * How many a decoy stack holds: mostly one to three, like most of a recipe's own, and now and
     * then more, like the planks of a chest - so the counts do not tell the two apart.
     */
    private static int decoyCount(Random random) {
        return random.nextInt(4) == 0 ? 2 + random.nextInt(7) : 1 + random.nextInt(3);
    }

    /**
     * The item a square is answered with: one the pool already offers, if the square takes it - one
     * kind of planks, not three - or else the first the game lists for it.
     */
    private static String representative(Set<String> set, Set<String> offered) {
        for (String item : offered) {
            if (set.contains(item)) {
                return item;
            }
        }
        return set.iterator().next();
    }

    static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }
}
