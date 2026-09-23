package com.etka.lune.games;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One crafting recipe as Recipe Riddle asks about it: what it makes, and what each square of the
 * grid may hold, as item ids.
 *
 * <p>A shaped recipe lists its squares row by row, {@code width} by {@code height}, with an empty
 * set for a square that stays empty; the game trims a pattern to its used rows and columns, so the
 * shape is only as big as it has to be. A shapeless one lists one set per ingredient and has no
 * shape. Either way a set holds every item the square accepts - every member of a tag - in the
 * order the game gave them, so the first is the one a player would think of first.</p>
 *
 * @param result the id of what it makes
 * @param count  how many it makes
 */
public record RiddleRecipe(String result, int count, boolean shapeless, int width, int height,
                           List<Set<String>> cells) {

    public RiddleRecipe {
        List<Set<String>> copies = new ArrayList<>(cells.size());
        for (Set<String> cell : cells) {
            copies.add(Collections.unmodifiableSet(new LinkedHashSet<>(cell)));
        }
        cells = List.copyOf(copies);
    }

    public static RiddleRecipe shaped(String result, int count, int width, int height, List<Set<String>> cells) {
        if (cells.size() != width * height || width > RiddleMatcher.SIDE || height > RiddleMatcher.SIDE) {
            throw new IllegalArgumentException(width + "x" + height + " / " + cells.size());
        }
        return new RiddleRecipe(result, count, false, width, height, cells);
    }

    public static RiddleRecipe shapeless(String result, int count, List<Set<String>> ingredients) {
        if (ingredients.size() > RiddleMatcher.CELLS || ingredients.contains(Set.of())) {
            throw new IllegalArgumentException(String.valueOf(ingredients.size()));
        }
        return new RiddleRecipe(result, count, true, 0, 0, ingredients);
    }

    /** How many squares the recipe fills. */
    public int filled() {
        int filled = 0;
        for (Set<String> cell : cells) {
            if (!cell.isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    /** Whether any square of the recipe would take {@code item}. */
    public boolean accepts(String item) {
        for (Set<String> cell : cells) {
            if (cell.contains(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A 32-bit fingerprint of the recipe, what the record of riddles asked keeps: the result, the
     * shape and every square, each square's items sorted so the order a tag lists them in does not
     * make two recipes of one.
     */
    public int fingerprint() {
        long hash = 0xCBF29CE484222325L;
        hash = mix(hash, result);
        hash = mix(hash, shapeless + ":" + width + "x" + height);
        for (Set<String> cell : cells) {
            List<String> sorted = new ArrayList<>(cell);
            Collections.sort(sorted);
            hash = mix(hash, String.join(",", sorted) + ";");
        }
        return (int) (hash ^ (hash >>> 32));
    }

    private static long mix(long hash, String text) {
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }
}
