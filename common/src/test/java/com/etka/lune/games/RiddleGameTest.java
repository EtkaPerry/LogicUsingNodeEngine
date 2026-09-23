package com.etka.lune.games;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.etka.lune.games.RiddleMatcherTest.BLACKSTONE;
import static com.etka.lune.games.RiddleMatcherTest.FLINT;
import static com.etka.lune.games.RiddleMatcherTest.IRON;
import static com.etka.lune.games.RiddleMatcherTest.NONE;
import static com.etka.lune.games.RiddleMatcherTest.PLANKS;
import static com.etka.lune.games.RiddleMatcherTest.STICK;
import static com.etka.lune.games.RiddleMatcherTest.STONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiddleGameTest {

    /** Things crafted with, a blackstone among them that the pickaxe would take and so may not be a decoy. */
    private static List<String> universe() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            items.add("minecraft:thing_" + i);
        }
        items.add(BLACKSTONE);
        items.add("minecraft:stone_pickaxe");
        return items;
    }

    private static RiddleGame pickaxeGame(long seed) {
        Random random = new Random(seed);
        RiddleRecipe recipe = RiddleMatcherTest.pickaxe();
        return new RiddleGame(recipe, RiddleDeck.pool(recipe, universe(), random), random);
    }

    private static int slotOf(RiddleGame game, String item) {
        for (int slot = 0; slot < RiddleGame.POOL; slot++) {
            if (item.equals(game.item(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /** Takes {@code item}'s whole stack in hand, unless it is in hand already, and puts one in {@code cell}. */
    private static RiddleGame.Outcome put(RiddleGame game, String item, int cell) {
        if (!item.equals(game.heldItem())) {
            game.clickPool(slotOf(game, item), false);
        }
        return game.place(cell);
    }

    // --- dealing -------------------------------------------------------------------

    @Test
    void thePoolHoldsExactlyWhatTheRecipeNeedsAmongDecoys() {
        RiddleGame game = pickaxeGame(1);
        int stones = game.count(slotOf(game, STONE));
        int sticks = game.count(slotOf(game, STICK));
        assertEquals(3, stones, "one kind of stone, as many as the recipe takes");
        assertEquals(2, sticks);
        int filled = 0;
        for (int slot = 0; slot < RiddleGame.POOL; slot++) {
            String item = game.item(slot);
            if (item == null) {
                continue;
            }
            filled++;
            if (game.decoy(slot)) {
                assertFalse(game.recipe().accepts(item), item + " would fit the recipe, so it is no decoy");
                assertFalse(item.equals(game.recipe().result()), "the answer is never among the decoys");
                assertTrue(game.count(slot) >= 1);
            }
        }
        assertEquals(RiddleGame.POOL, filled, "twenty stacks in all");
        assertEquals(-1, slotOf(game, BLACKSTONE));
    }

    @Test
    void halfTheDecoysComeFromTheRecipesOwnMod() {
        RiddleRecipe modded = RiddleRecipe.shapeless("create:gear", 1, List.of(Set.of("create:shaft"), Set.of(PLANKS)));
        List<String> universe = new ArrayList<>(universe());
        for (int i = 0; i < 30; i++) {
            universe.add("create:part_" + i);
        }
        List<RiddleGame.Stack> pool = RiddleDeck.pool(modded, universe, new Random(3));
        long fromCreate = pool.stream().filter(RiddleGame.Stack::decoy).filter(s -> s.item().startsWith("create:")).count();
        assertTrue(fromCreate >= (RiddleGame.POOL - 2) / 2, fromCreate + " from the recipe's own mod");
    }

    @Test
    void aRecipeIsNotAskedTwiceUntilAllHaveBeen() {
        RiddleRecipe pickaxe = RiddleMatcherTest.pickaxe();
        RiddleRecipe axe = RiddleMatcherTest.axe();
        Set<Integer> dealt = new HashSet<>(Set.of(pickaxe.fingerprint()));
        for (long seed = 0; seed < 20; seed++) {
            RiddleGame game = RiddleDeck.deal(List.of(pickaxe, axe), universe(), dealt::contains, new Random(seed));
            assertEquals(axe, game.recipe());
        }
        assertFalse(RiddleDeck.exhausted(List.of(pickaxe, axe), dealt::contains));
        dealt.add(axe.fingerprint());
        assertTrue(RiddleDeck.exhausted(List.of(pickaxe, axe), dealt::contains));
        assertFalse(RiddleDeck.exhausted(List.of(), dealt::contains), "nothing to ask is no reason to forget");
        assertNull(RiddleDeck.deal(List.of(), universe(), dealt::contains, new Random(0)));
    }

    @Test
    void onlyRecipesThatMakeARiddleAreAsked() {
        assertTrue(RiddleDeck.worthAsking(RiddleMatcherTest.pickaxe()));
        assertFalse(RiddleDeck.worthAsking(RiddleRecipe.shaped("minecraft:oak_button", 1, 1, 1, List.of(Set.of(PLANKS)))),
                "one square is no riddle");
        List<Set<String>> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(Set.of(IRON));
        }
        assertFalse(RiddleDeck.worthAsking(RiddleRecipe.shaped("minecraft:iron_block", 1, 3, 3, nine)),
                "nine of one thing is a storage block");
        assertFalse(RiddleDeck.worthAsking(RiddleRecipe.shapeless("lune:dust", 2, List.of(Set.of(FLINT), Set.of(FLINT)))),
                "one kind in any order");
        assertFalse(RiddleDeck.worthAsking(RiddleRecipe.shapeless("minecraft:iron_ingot", 1,
                List.of(Set.of(IRON), Set.of(FLINT)))), "made from itself");
    }

    // --- playing -------------------------------------------------------------------

    @Test
    void putTheRightItemsInTheRightSquaresAndItIsCrafted() {
        RiddleGame game = pickaxeGame(2);
        for (int cell = 0; cell < 3; cell++) {
            assertEquals(RiddleGame.Outcome.CHANGED, put(game, STONE, cell));
        }
        assertEquals(RiddleGame.Outcome.CHANGED, put(game, STICK, 4));
        assertEquals(0, game.count(slotOf(game, STONE)), "every stone has been used");
        assertTrue(game.inProgress());
        assertEquals(RiddleGame.Outcome.SOLVED, put(game, STICK, 7));
        assertTrue(game.solved());
        assertEquals(RiddleGame.Outcome.REFUSED, game.take(7), "a solved riddle stays solved");
    }

    // --- the hand, as in the game's own inventories ------------------------------

    @Test
    void aLeftClickTakesTheStackAndARightClickHalf() {
        RiddleGame game = pickaxeGame(3);
        int stones = slotOf(game, STONE);
        assertTrue(game.clickPool(stones, true));
        assertEquals(2, game.heldCount(), "half of three, rounded up");
        assertEquals(1, game.count(stones), "the slot keeps the rest");
        assertTrue(game.clickPool(stones, true));
        assertEquals(1, game.heldCount(), "a right click on its own stack puts one back");
        assertTrue(game.clickPool(stones, false));
        assertEquals(0, game.heldCount(), "a left click puts the lot back");
        assertNull(game.heldItem());
        assertEquals(3, game.count(stones));
        assertTrue(game.clickPool(stones, false));
        assertEquals(3, game.heldCount());
        assertEquals(0, game.count(stones), "the whole stack is in hand");
    }

    @Test
    void clickingAnotherStackPutsTheHandBackFirst() {
        RiddleGame game = pickaxeGame(4);
        game.clickPool(slotOf(game, STONE), false);
        game.clickPool(slotOf(game, STICK), false);
        assertEquals(STICK, game.heldItem());
        assertEquals(3, game.count(slotOf(game, STONE)), "the stones went home, not into another slot");
    }

    @Test
    void aDragLaysOneInEveryEmptySquareItPasses() {
        RiddleGame game = pickaxeGame(5);
        game.clickPool(slotOf(game, STONE), false);
        assertEquals(RiddleGame.Outcome.CHANGED, game.place(0));
        assertEquals(RiddleGame.Outcome.CHANGED, game.spread(1));
        assertEquals(RiddleGame.Outcome.REFUSED, game.spread(1), "a square holds one");
        assertEquals(RiddleGame.Outcome.CHANGED, game.spread(2));
        assertEquals(RiddleGame.Outcome.REFUSED, game.spread(3), "the hand is empty");
        assertNull(game.heldItem());
        put(game, STICK, 4);
        assertEquals(RiddleGame.Outcome.REFUSED, game.spread(0), "a drag passes over a square already filled");
        assertEquals(RiddleGame.Outcome.SOLVED, game.spread(7));
    }

    @Test
    void aSingleItemInHandSwapsWithTheSquare() {
        RiddleGame game = pickaxeGame(6);
        put(game, STICK, 4);
        game.returnHeld();
        game.clickPool(slotOf(game, STONE), true);
        game.clickPool(slotOf(game, STONE), true);
        assertEquals(1, game.heldCount());
        assertEquals(RiddleGame.Outcome.CHANGED, game.place(4));
        assertEquals(STONE, game.grid(4));
        assertEquals(STICK, game.heldItem(), "the stick the stone replaced is in hand now");
    }

    @Test
    void anEmptyHandPicksASquareUpAndAShiftClickSendsItHome() {
        RiddleGame game = pickaxeGame(7);
        put(game, STICK, 4);
        put(game, STICK, 8);
        assertNull(game.heldItem(), "both sticks are down");
        assertEquals(RiddleGame.Outcome.CHANGED, game.pickUp(4));
        assertEquals(STICK, game.heldItem());
        assertEquals(1, game.heldCount());
        assertNull(game.grid(4));
        assertEquals(RiddleGame.Outcome.REFUSED, game.pickUp(8), "with something in hand a click puts down");
        assertEquals(RiddleGame.Outcome.CHANGED, game.take(8));
        assertEquals(1, game.count(slotOf(game, STICK)), "sent home, not into the hand");
        assertEquals(1, game.heldCount());
    }

    @Test
    void aShiftClickSendsAStackAcrossIntoTheEmptySquaresInReadingOrder() {
        RiddleGame game = pickaxeGame(9);
        put(game, STICK, 1);
        game.returnHeld();
        assertEquals(RiddleGame.Outcome.CHANGED, game.quickMove(slotOf(game, STONE)));
        assertEquals(STONE, game.grid(0));
        assertEquals(STICK, game.grid(1), "a filled square is passed over");
        assertEquals(STONE, game.grid(2));
        assertEquals(STONE, game.grid(3));
        assertEquals(0, game.count(slotOf(game, STONE)), "the whole stack went across");
        game.clickPool(slotOf(game, STICK), false);
        assertEquals(RiddleGame.Outcome.REFUSED, game.quickMove(slotOf(game, STICK)),
                "the last stick is in hand, and a shift-click moves only what is in the slot");
    }

    @Test
    void aShiftClickLeavesTheHandAlone() {
        RiddleGame game = pickaxeGame(10);
        game.clickPool(slotOf(game, STICK), true);
        assertEquals(RiddleGame.Outcome.CHANGED, game.quickMove(slotOf(game, STONE)));
        assertEquals(STICK, game.heldItem(), "the stick is still in hand");
        assertEquals(1, game.heldCount());
        assertEquals(RiddleGame.Outcome.REFUSED, game.quickMove(slotOf(game, STONE)), "nothing left to move");
    }

    /** Where the order does not matter, a shift-click per ingredient is the whole answer. */
    @Test
    void aShapelessRecipeCanBeAnsweredWithShiftClicksAlone() {
        Random random = new Random(11);
        RiddleRecipe book = RiddleRecipe.shapeless("minecraft:book", 1,
                List.of(Set.of("minecraft:paper"), Set.of("minecraft:paper"), Set.of("minecraft:paper"),
                        Set.of("minecraft:leather")));
        RiddleGame game = new RiddleGame(book, RiddleDeck.pool(book, universe(), random), random);
        assertEquals(RiddleGame.Outcome.CHANGED, game.quickMove(slotOf(game, "minecraft:paper")));
        assertEquals(RiddleGame.Outcome.SOLVED, game.quickMove(slotOf(game, "minecraft:leather")));
    }

    @Test
    void helpEmptiesTheHandFirst() {
        RiddleGame game = pickaxeGame(8);
        game.clickPool(slotOf(game, STONE), false);
        game.clickPool(slotOf(game, STICK), false);
        assertEquals(RiddleGame.Outcome.CHANGED, game.hint(RiddleGame.Hint.PLACE));
        assertNull(game.heldItem(), "the sticks went home, and the hint found what it needed");
    }

    // --- help ------------------------------------------------------------------------

    @Test
    void placeOnePutsTheRightItemInTheRightSquareAndLocksIt() {
        RiddleGame game = pickaxeGame(4);
        assertEquals(RiddleGame.Outcome.CHANGED, game.hint(RiddleGame.Hint.PLACE));
        int hinted = -1;
        for (int cell = 0; cell < RiddleMatcher.CELLS; cell++) {
            if (game.hinted(cell)) {
                hinted = cell;
            }
        }
        assertTrue(hinted >= 0);
        List<Set<String>> layout = game.layout();
        assertTrue(layout.get(hinted).contains(game.grid(hinted)), "the hint put the right item there");
        assertEquals(RiddleGame.Outcome.REFUSED, game.take(hinted), "a hint's item stays where it was put");
        assertEquals(1, game.hints());
    }

    @Test
    void theLastSquareIsAlwaysThePlayersOwn() {
        RiddleGame game = pickaxeGame(5);
        int given = 0;
        while (game.canHint(RiddleGame.Hint.PLACE)) {
            game.hint(RiddleGame.Hint.PLACE);
            given++;
        }
        assertEquals(4, given, "five squares, four given");
        assertFalse(game.solved());
        int open = -1;
        for (int cell = 0; cell < RiddleMatcher.CELLS; cell++) {
            Set<String> wanted = game.layout().get(cell);
            if (wanted != null && (game.grid(cell) == null || !wanted.contains(game.grid(cell)))) {
                open = cell;
            }
        }
        String item = game.layout().get(open).contains(STICK) ? STICK : STONE;
        assertEquals(RiddleGame.Outcome.SOLVED, put(game, item, open));
    }

    /** Help lands beside what the player built: an axe started in the right-hand columns is finished there. */
    @Test
    void placeOneBuildsOnThePlayersOwnPlacement() {
        Random random = new Random(6);
        RiddleRecipe axe = RiddleMatcherTest.axe();
        RiddleGame game = new RiddleGame(axe, RiddleDeck.pool(axe, universe(), random), random);
        put(game, STONE, 1);
        put(game, STONE, 2);
        put(game, STONE, 4);
        game.hint(RiddleGame.Hint.PLACE);
        for (int cell : new int[]{0, 3, 6}) {
            assertNull(game.grid(cell), "the left column stays out of it");
        }
    }

    @Test
    void fewerDecoysTakesHalfOfThemAwayAndOutOfTheGrid() {
        RiddleGame game = pickaxeGame(7);
        int decoy = -1;
        int decoys = 0;
        for (int slot = 0; slot < RiddleGame.POOL; slot++) {
            if (game.decoy(slot)) {
                decoys++;
                decoy = decoy < 0 ? slot : decoy;
            }
        }
        String placed = game.item(decoy);
        game.clickPool(decoy, false);
        game.place(8);
        assertEquals(RiddleGame.Outcome.CHANGED, game.hint(RiddleGame.Hint.DECOYS));
        int left = 0;
        for (int slot = 0; slot < RiddleGame.POOL; slot++) {
            if (game.decoy(slot) && game.item(slot) != null) {
                left++;
            }
        }
        assertEquals(decoys / 2, left);
        boolean stillThere = placed.equals(game.grid(8));
        assertEquals(slotOf(game, placed) >= 0, stillThere, "a decoy taken away goes from the grid with it");
        assertFalse(game.canHint(RiddleGame.Hint.DECOYS), "once is all");
        assertEquals(3, game.count(slotOf(game, STONE)), "the recipe's own stacks are never touched");
    }

    @Test
    void showTheShapeOnceAndNothingWorksAfterTheRiddleIsSolved() {
        RiddleGame game = pickaxeGame(8);
        assertEquals(RiddleGame.Outcome.CHANGED, game.hint(RiddleGame.Hint.SHAPE));
        assertTrue(game.shapeShown());
        assertFalse(game.canHint(RiddleGame.Hint.SHAPE));
        for (int cell = 0; cell < 3; cell++) {
            put(game, STONE, cell);
        }
        put(game, STICK, 4);
        put(game, STICK, 7);
        assertTrue(game.solved());
        for (RiddleGame.Hint hint : RiddleGame.Hint.values()) {
            assertFalse(game.canHint(hint));
        }
    }

    @Test
    void aShapelessHintAnswersAnIngredientNotYetAnswered() {
        Random random = new Random(9);
        RiddleRecipe recipe = RiddleRecipe.shapeless("minecraft:book", 1,
                List.of(Set.of("minecraft:paper"), Set.of("minecraft:paper"), Set.of("minecraft:paper"),
                        Set.of("minecraft:leather")));
        RiddleGame game = new RiddleGame(recipe, RiddleDeck.pool(recipe, universe(), random), random);
        put(game, "minecraft:leather", 4);
        assertEquals(RiddleGame.Outcome.CHANGED, game.hint(RiddleGame.Hint.PLACE));
        int papers = 0;
        for (int cell = 0; cell < RiddleMatcher.CELLS; cell++) {
            if ("minecraft:paper".equals(game.grid(cell))) {
                papers++;
            }
        }
        assertEquals(1, papers, "the leather was there already; a paper was not");
        assertNotNull(game.grid(4));
    }

    @Test
    void theNoneSetMeansAnEmptySquare() {
        assertTrue(NONE.isEmpty());
        assertEquals(5, RiddleMatcherTest.pickaxe().filled());
    }
}
