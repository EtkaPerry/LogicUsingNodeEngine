package com.etka.lune.games;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiddleMatcherTest {

    static final String STONE = "minecraft:cobblestone";
    static final String BLACKSTONE = "minecraft:blackstone";
    static final String STICK = "minecraft:stick";
    static final String PLANKS = "minecraft:oak_planks";
    static final String FLINT = "minecraft:flint";
    static final String IRON = "minecraft:iron_ingot";
    /** Ordered, as the game lists a tag: the first is the one a riddle offers. */
    static final Set<String> STONES = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(STONE, BLACKSTONE)));
    static final Set<String> NONE = Set.of();

    /** Three stones over two sticks: the game's stone pickaxe, the stone a tag. */
    static RiddleRecipe pickaxe() {
        return RiddleRecipe.shaped("minecraft:stone_pickaxe", 1, 3, 3, List.of(
                STONES, STONES, STONES,
                NONE, Set.of(STICK), NONE,
                NONE, Set.of(STICK), NONE));
    }

    /** Two by three, and not the same mirrored: the stone axe. */
    static RiddleRecipe axe() {
        return RiddleRecipe.shaped("minecraft:stone_axe", 1, 2, 3, List.of(
                STONES, STONES,
                STONES, Set.of(STICK),
                NONE, Set.of(STICK)));
    }

    static String[] grid(String... cells) {
        String[] grid = new String[RiddleMatcher.CELLS];
        for (int i = 0; i < cells.length; i++) {
            grid[i] = cells[i];
        }
        return grid;
    }

    @Test
    void theShapeAsTheGameLaysItOut() {
        assertTrue(RiddleMatcher.matches(pickaxe(), grid(STONE, STONE, STONE, null, STICK, null, null, STICK, null)));
        assertTrue(RiddleMatcher.matches(pickaxe(), grid(STONE, BLACKSTONE, STONE, null, STICK, null, null, STICK, null)),
                "any member of the tag will do");
        assertFalse(RiddleMatcher.matches(pickaxe(), grid(STONE, STONE, STONE, null, STICK, null, null, null, null)),
                "a stick short");
        assertFalse(RiddleMatcher.matches(pickaxe(), grid(STONE, STONE, STONE, null, STICK, FLINT, null, STICK, null)),
                "something extra");
        assertFalse(RiddleMatcher.matches(pickaxe(), grid()), "an empty grid makes nothing");
    }

    @Test
    void aSmallerShapeFitsWhereverItIsPutAndEitherWayRound() {
        RiddleRecipe axe = axe();
        assertTrue(RiddleMatcher.matches(axe, grid(STONE, STONE, null, STONE, STICK, null, null, STICK, null)));
        assertTrue(RiddleMatcher.matches(axe, grid(null, STONE, STONE, null, STONE, STICK, null, null, STICK)),
                "moved one column over");
        assertTrue(RiddleMatcher.matches(axe, grid(STONE, STONE, null, STICK, STONE, null, STICK, null, null)),
                "mirrored");
        assertFalse(RiddleMatcher.matches(axe, grid(STICK, STICK, null, STONE, STONE, null, STONE, STONE, null)),
                "upside down is not a mirror");
    }

    @Test
    void aShapelessRecipeTakesItsItemsAnywhere() {
        RiddleRecipe flintAndSteel = RiddleRecipe.shapeless("minecraft:flint_and_steel", 1,
                List.of(Set.of(IRON), Set.of(FLINT)));
        assertTrue(RiddleMatcher.matches(flintAndSteel, grid(IRON, FLINT)));
        assertTrue(RiddleMatcher.matches(flintAndSteel, grid(null, null, null, null, FLINT, null, null, null, IRON)));
        assertFalse(RiddleMatcher.matches(flintAndSteel, grid(IRON, FLINT, FLINT)), "one too many");
        assertFalse(RiddleMatcher.matches(flintAndSteel, grid(IRON, IRON)), "the flint is missing");
    }

    /** Each item answers one ingredient, so a looser ingredient has to take whatever the stricter one leaves. */
    @Test
    void eachItemAnswersADifferentIngredient() {
        RiddleRecipe recipe = RiddleRecipe.shapeless("lune:thing", 1, List.of(STONES, Set.of(STONE)));
        assertTrue(RiddleMatcher.matches(recipe, grid(STONE, BLACKSTONE)));
        assertTrue(RiddleMatcher.matches(recipe, grid(BLACKSTONE, STONE)), "the tag takes the blackstone");
        assertFalse(RiddleMatcher.matches(recipe, grid(BLACKSTONE, BLACKSTONE)), "only the tag takes blackstone");
        assertArrayEquals(new int[]{0, -1}, RiddleMatcher.assignment(recipe.cells(), List.of(BLACKSTONE, BLACKSTONE)));
    }

    @Test
    void everyPlacementOfAShapeIsALayout() {
        assertEquals(1, RiddleMatcher.layouts(pickaxe()).size(), "a full grid lies one way, and it mirrors onto itself");
        // Two columns fit two ways across, one down, and the axe is different mirrored.
        assertEquals(4, RiddleMatcher.layouts(axe()).size());
        RiddleRecipe square = RiddleRecipe.shaped("minecraft:crafting_table", 1, 2, 2,
                List.of(Set.of(PLANKS), Set.of(PLANKS), Set.of(PLANKS), Set.of(PLANKS)));
        assertEquals(4, RiddleMatcher.layouts(square).size(), "a square of one thing is its own mirror");
        List<Set<String>> first = RiddleMatcher.layouts(axe()).get(0);
        assertEquals(Arrays.asList(STONES, STONES, null, STONES, Set.of(STICK), null, null, Set.of(STICK), null), first);
    }

    @Test
    void theFingerprintIgnoresTheOrderATagListsItsItemsIn() {
        RiddleRecipe one = RiddleRecipe.shapeless("lune:thing", 1, List.of(Set.of(STONE, BLACKSTONE), Set.of(STICK)));
        RiddleRecipe other = RiddleRecipe.shapeless("lune:thing", 1, List.of(Set.of(BLACKSTONE, STONE), Set.of(STICK)));
        assertEquals(one.fingerprint(), other.fingerprint());
        assertFalse(one.fingerprint() == pickaxe().fingerprint());
    }
}
