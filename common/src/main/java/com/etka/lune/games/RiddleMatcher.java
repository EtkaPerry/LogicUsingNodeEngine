package com.etka.lune.games;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Whether a grid crafts a recipe, decided the way the game decides it.
 *
 * <p>A shaped recipe is laid over what was actually put in the grid: the grid is trimmed to its
 * filled rows and columns, which must come out the recipe's size, and the pattern then has to fit
 * either as it is or mirrored left to right - so a recipe that fits in a corner fits in any corner,
 * and an axe works facing either way. A shapeless one takes its items in any squares, each item
 * answering a different ingredient.</p>
 */
public final class RiddleMatcher {

    public static final int SIDE = 3;
    public static final int CELLS = SIDE * SIDE;

    private RiddleMatcher() {}

    /** Whether {@code grid} - nine squares row by row, null for empty - crafts {@code recipe}. */
    public static boolean matches(RiddleRecipe recipe, String[] grid) {
        return recipe.shapeless() ? shapeless(recipe, grid) : shaped(recipe, grid);
    }

    private static boolean shaped(RiddleRecipe recipe, String[] grid) {
        int left = SIDE;
        int top = SIDE;
        int right = -1;
        int bottom = -1;
        for (int cell = 0; cell < CELLS; cell++) {
            if (grid[cell] != null) {
                int x = cell % SIDE;
                int y = cell / SIDE;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < 0 || right - left + 1 != recipe.width() || bottom - top + 1 != recipe.height()) {
            return false;
        }
        return fits(recipe, grid, left, top, false) || fits(recipe, grid, left, top, true);
    }

    private static boolean fits(RiddleRecipe recipe, String[] grid, int left, int top, boolean mirrored) {
        for (int dy = 0; dy < recipe.height(); dy++) {
            for (int dx = 0; dx < recipe.width(); dx++) {
                Set<String> wanted = recipe.cells().get(dy * recipe.width() + (mirrored ? recipe.width() - 1 - dx : dx));
                String placed = grid[(top + dy) * SIDE + left + dx];
                if (wanted.isEmpty() ? placed != null : placed == null || !wanted.contains(placed)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean shapeless(RiddleRecipe recipe, String[] grid) {
        List<String> placed = new ArrayList<>(CELLS);
        for (String item : grid) {
            if (item != null) {
                placed.add(item);
            }
        }
        if (placed.size() != recipe.cells().size()) {
            return false;
        }
        for (int takenBy : assignment(recipe.cells(), placed)) {
            if (takenBy < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Which ingredient each of {@code placed} answers, -1 for none: every item used at most once,
     * and as many ingredients answered as can be. Nine at most each side, so this is instant.
     */
    public static int[] assignment(List<Set<String>> ingredients, List<String> placed) {
        int[] takenBy = new int[placed.size()];
        Arrays.fill(takenBy, -1);
        for (int ingredient = 0; ingredient < ingredients.size(); ingredient++) {
            claim(ingredients, placed, ingredient, takenBy, new boolean[placed.size()]);
        }
        return takenBy;
    }

    /** Finds {@code ingredient} an item, moving an ingredient already answered on to another if it must. */
    private static boolean claim(List<Set<String>> ingredients, List<String> placed, int ingredient,
                                 int[] takenBy, boolean[] seen) {
        for (int item = 0; item < placed.size(); item++) {
            if (seen[item] || !ingredients.get(ingredient).contains(placed.get(item))) {
                continue;
            }
            seen[item] = true;
            if (takenBy[item] < 0 || claim(ingredients, placed, takenBy[item], takenBy, seen)) {
                takenBy[item] = ingredient;
                return true;
            }
        }
        return false;
    }

    /**
     * Every way a shaped recipe can lie on the grid - each offset, as it is and mirrored - as nine
     * squares: the set a square needs, or null where it must stay empty. Empty for a shapeless recipe.
     */
    public static List<List<Set<String>>> layouts(RiddleRecipe recipe) {
        List<List<Set<String>>> layouts = new ArrayList<>();
        if (recipe.shapeless()) {
            return layouts;
        }
        for (int mirrored = 0; mirrored < 2; mirrored++) {
            for (int top = 0; top + recipe.height() <= SIDE; top++) {
                for (int left = 0; left + recipe.width() <= SIDE; left++) {
                    List<Set<String>> layout = new ArrayList<>(Collections.nCopies(CELLS, null));
                    for (int dy = 0; dy < recipe.height(); dy++) {
                        for (int dx = 0; dx < recipe.width(); dx++) {
                            int from = dy * recipe.width() + (mirrored == 1 ? recipe.width() - 1 - dx : dx);
                            Set<String> wanted = recipe.cells().get(from);
                            layout.set((top + dy) * SIDE + left + dx, wanted.isEmpty() ? null : wanted);
                        }
                    }
                    if (!layouts.contains(layout)) {
                        layouts.add(Collections.unmodifiableList(layout));
                    }
                }
            }
        }
        return layouts;
    }
}
