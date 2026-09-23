package com.etka.lune.games;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * One riddle of Recipe Riddle: the recipe asked about, the items to answer it with, the grid, and
 * the hints taken.
 *
 * <p>The items come as stacks, the way they would sit in an inventory: each of the recipe's own
 * exactly as many as it needs, among decoys that carry counts of their own, so counting what is
 * there gives nothing away. Items are ids; nothing here knows about Minecraft.</p>
 *
 * <p>The hand works the way it does in any of the game's own inventories. A left click takes a
 * whole stack and a right click half of it, leaving the slot the poorer; clicking the stack it came
 * from puts it all back, or one back. With something in hand, a click on a square puts one down, and
 * a drag across the grid - either button - puts one in every empty square it passes, as far as the
 * hand goes. With the hand empty, a click on a square picks its item up. A shift-click moves things
 * across, as in the game: a stack into the empty squares, a square back to its stack. Each square
 * holds one item, so that is all a click, a drag or a shift-click ever lays in one.</p>
 *
 * <p>Help is given against the answer the player seems to be building - the placement of the
 * recipe that agrees best with the grid - so a hint lands beside their own work rather than
 * starting a second answer somewhere else. The last square is always left to the player: a riddle
 * the hints finished is not one they solved.</p>
 */
public final class RiddleGame {

    public static final int POOL = 20;

    /** The three helps on offer. */
    public enum Hint { PLACE, SHAPE, DECOYS }

    /** What an action did; {@code SOLVED} only on the action that finished the riddle. */
    public enum Outcome { REFUSED, CHANGED, SOLVED }

    /** One slot of the items to answer with. */
    public record Stack(String item, int count, boolean decoy) {}

    /**
     * The riddle in play.
     *
     * <p>Static on purpose: the panel is rebuilt every time it opens, and a half-built answer that
     * vanished whenever the player went to check on Lune would be a riddle nobody finished.</p>
     */
    private static RiddleGame current;

    private final RiddleRecipe recipe;
    private final String[] items = new String[POOL];
    private final int[] counts = new int[POOL];
    private final boolean[] decoys = new boolean[POOL];
    private final String[] grid = new String[RiddleMatcher.CELLS];
    private final boolean[] hinted = new boolean[RiddleMatcher.CELLS];
    private final List<List<Set<String>>> layouts;
    /** Where the ties between layouts start, so help does not always favour the top left corner. */
    private final int preferred;
    private final Random random;
    /** The pool slot what is in hand came from, or -1 with nothing in hand; and how many of it. */
    private int heldSlot = -1;
    private int heldCount;
    private int hints;
    private boolean shapeShown;
    private boolean decoysThinned;
    private boolean touched;
    private boolean solved;
    private int version;

    RiddleGame(RiddleRecipe recipe, List<Stack> pool, Random random) {
        this.recipe = recipe;
        this.random = random;
        for (int slot = 0; slot < Math.min(POOL, pool.size()); slot++) {
            items[slot] = pool.get(slot).item();
            counts[slot] = pool.get(slot).count();
            decoys[slot] = pool.get(slot).decoy();
        }
        this.layouts = RiddleMatcher.layouts(recipe);
        this.preferred = layouts.isEmpty() ? 0 : random.nextInt(layouts.size());
    }

    public static RiddleGame current() {
        return current;
    }

    public static void setCurrent(RiddleGame game) {
        current = game;
    }

    // --- reading ---------------------------------------------------------------

    public RiddleRecipe recipe() {
        return recipe;
    }

    /** The item in pool {@code slot}, or null for an empty slot. */
    public String item(int slot) {
        return items[slot];
    }

    public int count(int slot) {
        return counts[slot];
    }

    /** Whether pool {@code slot} is a decoy. For the tests: the page must never tell. */
    boolean decoy(int slot) {
        return decoys[slot];
    }

    /** The item in grid square {@code cell}, row by row, or null. */
    public String grid(int cell) {
        return grid[cell];
    }

    /** A copy of the grid, for asking what it makes. */
    public String[] gridItems() {
        return grid.clone();
    }

    /** Whether a hint put the item in {@code cell}: it stays, and the player cannot move it. */
    public boolean hinted(int cell) {
        return hinted[cell];
    }

    /** The pool slot what is in hand came from, or -1 with nothing in hand. */
    public int heldSlot() {
        return heldSlot;
    }

    /** How many are in hand. */
    public int heldCount() {
        return heldCount;
    }

    /** What is in hand, or null. */
    public String heldItem() {
        return heldCount > 0 && heldSlot >= 0 ? items[heldSlot] : null;
    }

    public int hints() {
        return hints;
    }

    public boolean shapeShown() {
        return shapeShown;
    }

    public boolean decoysThinned() {
        return decoysThinned;
    }

    public boolean solved() {
        return solved;
    }

    /** Something has been put down or asked for, and it is not solved: what a new riddle would throw away. */
    public boolean inProgress() {
        return touched && !solved;
    }

    /** Goes up on every change, so a page can tell when to ask again what the grid makes. */
    public int version() {
        return version;
    }

    // --- playing ---------------------------------------------------------------

    /**
     * A click on pool {@code slot}; {@code half} for the right button. With nothing in hand it takes
     * the stack, or half of it rounded up. On the stack the hand came from it puts everything back,
     * or one. Anywhere else the hand is emptied back where it came from first: the pool keeps its
     * order, so a stack is never swapped out of its slot. False when nothing changed.
     */
    public boolean clickPool(int slot, boolean half) {
        if (solved || slot < 0 || slot >= POOL) {
            return false;
        }
        if (heldCount > 0 && heldSlot == slot) {
            int back = half ? 1 : heldCount;
            counts[slot] += back;
            heldCount -= back;
            if (heldCount == 0) {
                heldSlot = -1;
            }
            return true;
        }
        boolean changed = returnHeld();
        if (items[slot] == null || counts[slot] <= 0) {
            return changed;
        }
        int take = half ? (counts[slot] + 1) / 2 : counts[slot];
        counts[slot] -= take;
        heldSlot = slot;
        heldCount = take;
        return true;
    }

    /** Puts whatever is in hand back on the stack it came from. False when the hand was empty. */
    public boolean returnHeld() {
        if (heldCount <= 0) {
            heldSlot = -1;
            return false;
        }
        if (heldSlot >= 0 && items[heldSlot] != null) {
            counts[heldSlot] += heldCount;
        }
        heldSlot = -1;
        heldCount = 0;
        return true;
    }

    /**
     * Puts one from the hand in {@code cell}. A different item already there is swapped into the
     * hand when the hand held just the one, as the game swaps a slot with the cursor, and otherwise
     * goes back to its stack.
     */
    public Outcome place(int cell) {
        if (solved || !inGrid(cell) || hinted[cell] || heldCount <= 0) {
            return Outcome.REFUSED;
        }
        String item = items[heldSlot];
        if (item.equals(grid[cell])) {
            return Outcome.REFUSED;
        }
        String old = grid[cell];
        grid[cell] = item;
        heldCount--;
        if (heldCount == 0) {
            heldSlot = -1;
        }
        if (old != null) {
            int home = slotOf(old);
            if (heldCount == 0 && home >= 0) {
                heldSlot = home;
                heldCount = 1;
            } else if (home >= 0) {
                counts[home]++;
            }
        }
        touched = true;
        return changed();
    }

    /** One from the hand in {@code cell} as a drag passes over it: an empty square only, as in the game. */
    public Outcome spread(int cell) {
        if (solved || !inGrid(cell) || hinted[cell] || heldCount <= 0 || grid[cell] != null) {
            return Outcome.REFUSED;
        }
        grid[cell] = items[heldSlot];
        heldCount--;
        if (heldCount == 0) {
            heldSlot = -1;
        }
        touched = true;
        return changed();
    }

    /**
     * A shift-click on pool {@code slot}: the stack goes across into the grid, one in each empty
     * square in reading order, as far as it goes - the way the game moves a stack across. What is
     * in hand stays in hand, as it does in the game.
     */
    public Outcome quickMove(int slot) {
        if (solved || slot < 0 || slot >= POOL || items[slot] == null || counts[slot] <= 0) {
            return Outcome.REFUSED;
        }
        boolean moved = false;
        for (int cell = 0; cell < RiddleMatcher.CELLS && counts[slot] > 0; cell++) {
            if (grid[cell] == null && !hinted[cell]) {
                grid[cell] = items[slot];
                counts[slot]--;
                moved = true;
            }
        }
        if (!moved) {
            return Outcome.REFUSED;
        }
        touched = true;
        return changed();
    }

    /** Sends the item in {@code cell} straight back to its stack, as a shift-click moves it in the game. */
    public Outcome take(int cell) {
        if (solved || !inGrid(cell) || hinted[cell] || grid[cell] == null) {
            return Outcome.REFUSED;
        }
        giveBack(cell);
        return changed();
    }

    /** Picks the item in {@code cell} up into an empty hand. */
    public Outcome pickUp(int cell) {
        if (solved || !inGrid(cell) || hinted[cell] || grid[cell] == null || heldCount > 0) {
            return Outcome.REFUSED;
        }
        int home = slotOf(grid[cell]);
        grid[cell] = null;
        if (home >= 0) {
            heldSlot = home;
            heldCount = 1;
        }
        return changed();
    }

    // --- help ------------------------------------------------------------------

    public boolean canHint(Hint hint) {
        if (solved) {
            return false;
        }
        return switch (hint) {
            case PLACE -> open() > 1;
            case SHAPE -> !shapeShown;
            case DECOYS -> !decoysThinned && decoysLeft() > 0;
        };
    }

    public Outcome hint(Hint hint) {
        if (!canHint(hint)) {
            return Outcome.REFUSED;
        }
        // Help works from the pool and the grid, so whatever is in hand goes back first.
        returnHeld();
        boolean done = switch (hint) {
            case PLACE -> recipe.shapeless() ? placeShapeless() : placeShaped();
            case SHAPE -> shapeShown = true;
            case DECOYS -> thinDecoys();
        };
        if (!done) {
            return Outcome.REFUSED;
        }
        hints++;
        touched = true;
        return changed();
    }

    /**
     * The placement of a shaped recipe that agrees best with the grid: nine squares, each the set
     * it needs or null where it stays empty. Null for a shapeless recipe, which has no placement.
     */
    public List<Set<String>> layout() {
        if (layouts.isEmpty()) {
            return null;
        }
        List<Set<String>> best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < layouts.size(); i++) {
            List<Set<String>> layout = layouts.get((preferred + i) % layouts.size());
            int score = 0;
            for (int cell = 0; cell < RiddleMatcher.CELLS; cell++) {
                if (grid[cell] == null) {
                    continue;
                }
                Set<String> wanted = layout.get(cell);
                boolean agrees = wanted != null && wanted.contains(grid[cell]);
                // A hint is never contradicted; the player's own items are only outvoted.
                score += agrees ? 2 : hinted[cell] ? -1000 : -1;
            }
            if (score > bestScore) {
                best = layout;
                bestScore = score;
            }
        }
        return best;
    }

    /** How many squares, or ingredients for a shapeless recipe, are still to be answered. */
    private int open() {
        if (recipe.shapeless()) {
            return unmet().size();
        }
        return openCells(layout()).size();
    }

    private List<Integer> openCells(List<Set<String>> layout) {
        List<Integer> open = new ArrayList<>();
        for (int cell = 0; cell < RiddleMatcher.CELLS; cell++) {
            Set<String> wanted = layout.get(cell);
            if (wanted != null && (grid[cell] == null || !wanted.contains(grid[cell]))) {
                open.add(cell);
            }
        }
        return open;
    }

    private boolean placeShaped() {
        List<Set<String>> layout = layout();
        List<Integer> open = openCells(layout);
        int cell = open.get(random.nextInt(open.size()));
        Set<String> wanted = layout.get(cell);
        // From the stacks if any is left; otherwise from a square where it is not wanted.
        int slot = slotFor(wanted);
        int from = -1;
        if (slot < 0) {
            for (int other = 0; other < RiddleMatcher.CELLS && from < 0; other++) {
                Set<String> there = layout.get(other);
                if (other != cell && !hinted[other] && grid[other] != null && wanted.contains(grid[other])
                        && (there == null || !there.contains(grid[other]))) {
                    from = other;
                }
            }
            if (from < 0) {
                return false;
            }
        }
        put(cell, slot, from);
        return true;
    }

    /** The ingredients of a shapeless recipe the grid does not yet answer. */
    private List<Set<String>> unmet() {
        List<String> placed = new ArrayList<>();
        for (String item : grid) {
            if (item != null) {
                placed.add(item);
            }
        }
        int[] takenBy = RiddleMatcher.assignment(recipe.cells(), placed);
        boolean[] answered = new boolean[recipe.cells().size()];
        for (int ingredient : takenBy) {
            if (ingredient >= 0) {
                answered[ingredient] = true;
            }
        }
        List<Set<String>> unmet = new ArrayList<>();
        for (int ingredient = 0; ingredient < answered.length; ingredient++) {
            if (!answered[ingredient]) {
                unmet.add(recipe.cells().get(ingredient));
            }
        }
        return unmet;
    }

    /**
     * One ingredient the grid does not answer yet, from its stack. The stacks hold exactly what the
     * recipe needs, so an unanswered ingredient always has one waiting there: had they all been put
     * down, each would answer an ingredient.
     */
    private boolean placeShapeless() {
        List<Set<String>> unmet = new ArrayList<>(unmet());
        Collections.shuffle(unmet, random);
        for (Set<String> wanted : unmet) {
            int slot = slotFor(wanted);
            int target = slot < 0 ? -1 : shapelessTarget();
            if (target >= 0) {
                put(target, slot, -1);
                return true;
            }
        }
        return false;
    }

    /** Where a shapeless hint goes: an empty square, or failing that one whose item answers nothing. */
    private int shapelessTarget() {
        List<Integer> cells = new ArrayList<>();
        List<String> placed = new ArrayList<>();
        for (int cell = 0; cell < RiddleMatcher.CELLS; cell++) {
            if (grid[cell] == null) {
                return cell;
            }
            cells.add(cell);
            placed.add(grid[cell]);
        }
        int[] takenBy = RiddleMatcher.assignment(recipe.cells(), placed);
        for (int i = 0; i < cells.size(); i++) {
            if (takenBy[i] < 0 && !hinted[cells.get(i)]) {
                return cells.get(i);
            }
        }
        return -1;
    }

    /** Fills {@code cell} for a hint, with one from pool {@code slot}, or else the item in {@code from}. */
    private void put(int cell, int slot, int from) {
        String item;
        if (slot >= 0) {
            item = items[slot];
            giveBack(cell);
            counts[slot]--;
        } else {
            item = grid[from];
            grid[from] = null;
            giveBack(cell);
        }
        grid[cell] = item;
        hinted[cell] = true;
    }

    /** Half the decoys, rounded up, gone from the pool, and from any square they were put in. */
    private boolean thinDecoys() {
        List<Integer> left = new ArrayList<>();
        for (int slot = 0; slot < POOL; slot++) {
            if (decoys[slot] && items[slot] != null) {
                left.add(slot);
            }
        }
        Collections.shuffle(left, random);
        for (int i = 0; i < (left.size() + 1) / 2; i++) {
            int slot = left.get(i);
            for (int cell = 0; cell < RiddleMatcher.CELLS; cell++) {
                if (items[slot].equals(grid[cell])) {
                    grid[cell] = null;
                }
            }
            items[slot] = null;
            counts[slot] = 0;
            if (heldSlot == slot) {
                heldSlot = -1;
                heldCount = 0;
            }
        }
        decoysThinned = true;
        return true;
    }

    private int decoysLeft() {
        int left = 0;
        for (int slot = 0; slot < POOL; slot++) {
            if (decoys[slot] && items[slot] != null) {
                left++;
            }
        }
        return left;
    }

    // --- bookkeeping -----------------------------------------------------------

    /** A pool slot holding an item {@code wanted} accepts, with one left; -1 if none. */
    private int slotFor(Set<String> wanted) {
        for (int slot = 0; slot < POOL; slot++) {
            if (items[slot] != null && counts[slot] > 0 && wanted.contains(items[slot])) {
                return slot;
            }
        }
        return -1;
    }

    private int slotOf(String item) {
        for (int slot = 0; slot < POOL; slot++) {
            if (item.equals(items[slot])) {
                return slot;
            }
        }
        return -1;
    }

    /** Empties {@code cell} back into its stack; an item whose stack was taken away just goes. */
    private void giveBack(int cell) {
        String item = grid[cell];
        if (item == null) {
            return;
        }
        grid[cell] = null;
        int slot = slotOf(item);
        if (slot >= 0) {
            counts[slot]++;
        }
    }

    private static boolean inGrid(int cell) {
        return cell >= 0 && cell < RiddleMatcher.CELLS;
    }

    private Outcome changed() {
        boolean was = solved;
        version++;
        solved = RiddleMatcher.matches(recipe, grid);
        if (solved) {
            returnHeld();
        }
        return solved && !was ? Outcome.SOLVED : Outcome.CHANGED;
    }
}
