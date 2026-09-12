package com.etka.lune.bot.catalog;

import com.etka.lune.util.Lang;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A crafting grid drawn by hand: which item belongs in which cell of a 2×2 or 3×3 layout.
 * <p>
 * Recipes are server-authoritative, so the client only knows the ones the server has told it about -
 * which is to say the ones the player has already unlocked. A pattern is the answer to that: laying
 * the ingredients out cell by cell is what a human does at a table, and the server matches whatever
 * ends up in the grid whether or not the recipe was ever unlocked. It is also the only way to reach
 * a recipe Lune cannot see, and it works unchanged for a modded one.
 * <p>
 * Stored as namespaced item ids so a pattern survives being shared with another player, or a
 * different mod set: a cell naming an item this instance does not have reads back empty rather than
 * refusing the whole task.
 */
public final class CraftPattern {

    /** The player's own 2×2 grid; anything larger needs a crafting table. */
    public static final int SMALL = 2;
    /** A crafting table's 3×3 grid. */
    public static final int LARGE = 3;

    /** Cells in row-major order, {@code null} where the cell is empty. */
    private final List<Item> cells;
    private final int size;

    private CraftPattern(int size, List<Item> cells) {
        this.size = Math.clamp(size, SMALL, LARGE);
        List<Item> copy = new ArrayList<>(this.size * this.size);
        for (int index = 0; index < this.size * this.size; index++) {
            Item item = index < cells.size() ? cells.get(index) : null;
            copy.add(item == Items.AIR ? null : item);
        }
        // Not List.copyOf: an empty cell is a null entry, and that rejects nulls outright.
        this.cells = java.util.Collections.unmodifiableList(copy);
    }

    public static CraftPattern empty(int size) {
        return new CraftPattern(size, List.of());
    }

    /** Builds a pattern from row-major cells; {@code null} means an empty cell. */
    public static CraftPattern of(int size, List<Item> cells) {
        return new CraftPattern(size, cells);
    }

    public int size() {
        return size;
    }

    /** How many cells this grid has, which is also how many container slots it fills. */
    public int cellCount() {
        return size * size;
    }

    /** The item in a cell, or {@code null} when that cell is left empty. */
    public Item cell(int row, int column) {
        if (row < 0 || column < 0 || row >= size || column >= size) {
            return null;
        }
        return cells.get(row * size + column);
    }

    /** The item in a row-major cell index, or {@code null}. */
    public Item cell(int index) {
        return index < 0 || index >= cells.size() ? null : cells.get(index);
    }

    public CraftPattern withCell(int row, int column, Item item) {
        if (row < 0 || column < 0 || row >= size || column >= size) {
            return this;
        }
        List<Item> updated = new ArrayList<>(cells);
        updated.set(row * size + column, item == Items.AIR ? null : item);
        return new CraftPattern(size, updated);
    }

    /**
     * The same drawing on a different grid.
     * <p>
     * Cells are kept where both grids have one - the top-left corner - so switching to 3×3 to add a
     * third column does not wipe what is already drawn. Shrinking drops whatever falls outside,
     * which is the honest answer: those cells no longer exist.
     */
    public CraftPattern withSize(int newSize) {
        int target = Math.clamp(newSize, SMALL, LARGE);
        if (target == size) {
            return this;
        }
        List<Item> moved = new ArrayList<>(java.util.Collections.nCopies(target * target, (Item) null));
        int shared = Math.min(size, target);
        for (int row = 0; row < shared; row++) {
            for (int column = 0; column < shared; column++) {
                moved.set(row * target + column, cell(row, column));
            }
        }
        return new CraftPattern(target, moved);
    }

    public CraftPattern cleared() {
        return empty(size);
    }

    public boolean isEmpty() {
        return cells.stream().allMatch(java.util.Objects::isNull);
    }

    public int filledCells() {
        return (int) cells.stream().filter(java.util.Objects::nonNull).count();
    }

    /** Columns spanned by the filled cells; 0 when nothing is drawn. */
    public int boundingWidth() {
        int min = size;
        int max = -1;
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                if (cell(row, column) != null) {
                    min = Math.min(min, column);
                    max = Math.max(max, column);
                }
            }
        }
        return max < 0 ? 0 : max - min + 1;
    }

    /** Rows spanned by the filled cells; 0 when nothing is drawn. */
    public int boundingHeight() {
        int min = size;
        int max = -1;
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                if (cell(row, column) != null) {
                    min = Math.min(min, row);
                    max = Math.max(max, row);
                }
            }
        }
        return max < 0 ? 0 : max - min + 1;
    }

    /**
     * Whether the drawing would fit in a grid of this width.
     * <p>
     * Asked of the 2×2 grid, this is the question "does this need a crafting table?" - and it is
     * about the drawing, not about the paper it was drawn on. Two planks side by side are two
     * planks side by side whether they were placed on the small grid or in the middle of the
     * large one, and sending the bot off to build a table for them would be theatre.
     */
    public boolean fitsIn(int gridSize) {
        return boundingWidth() <= gridSize && boundingHeight() <= gridSize;
    }

    /**
     * The same drawing pushed into the top-left corner.
     * <p>
     * Vanilla's shaped matching already slides a recipe around the grid, so this changes nothing
     * about what matches. It matters for where the items are actually put: a shape drawn in the
     * bottom-right of a 3×3 has to start at the first cell to be laid into a 2×2 at all.
     */
    public CraftPattern normalized() {
        int firstRow = size;
        int firstColumn = size;
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                if (cell(row, column) != null) {
                    firstRow = Math.min(firstRow, row);
                    firstColumn = Math.min(firstColumn, column);
                }
            }
        }
        if (firstRow == 0 && firstColumn == 0 || isEmpty()) {
            return this;
        }
        List<Item> moved = new ArrayList<>(java.util.Collections.nCopies(size * size, (Item) null));
        for (int row = firstRow; row < size; row++) {
            for (int column = firstColumn; column < size; column++) {
                moved.set((row - firstRow) * size + (column - firstColumn), cell(row, column));
            }
        }
        return new CraftPattern(size, moved);
    }

    /** How many of each item one craft of this pattern consumes. */
    public Map<Item, Integer> ingredients() {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        for (Item item : cells) {
            if (item != null) {
                needed.merge(item, 1, Integer::sum);
            }
        }
        return needed;
    }

    /** How the pattern reads in a parameter row: "3×3, 5 items". */
    public String describe() {
        if (isEmpty()) {
            return Lang.get("lune.gui.recipe.grid_empty", size, size);
        }
        int filled = filledCells();
        return Lang.get("lune.gui.recipe.grid_items", size, size, filled);
    }

    /**
     * Serialises as {@code <size>;<id>,<id>,...} in row-major order, with empty cells written as
     * nothing between two commas.
     */
    public String serialize() {
        StringBuilder out = new StringBuilder().append(size).append(';');
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            Item item = cells.get(index);
            if (item != null) {
                out.append(BuiltInRegistries.ITEM.getKey(item));
            }
        }
        return out.toString();
    }

    /** Reads {@link #serialize}, dropping any cell naming an item this instance does not have. */
    public static CraftPattern deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return empty(LARGE);
        }
        int split = raw.indexOf(';');
        int size = LARGE;
        String body = raw;
        if (split > 0) {
            try {
                size = Integer.parseInt(raw.substring(0, split).trim());
            } catch (NumberFormatException e) {
                size = LARGE;
            }
            body = raw.substring(split + 1);
        }
        size = Math.clamp(size, SMALL, LARGE);

        List<Item> cells = new ArrayList<>(size * size);
        // -1 keeps the trailing empty cells: a pattern whose last row is blank still has that row.
        for (String part : Arrays.asList(body.split(",", -1))) {
            String id = part.trim();
            if (id.isEmpty()) {
                cells.add(null);
                continue;
            }
            Identifier key = Identifier.tryParse(id);
            cells.add(key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null));
        }
        return new CraftPattern(size, cells);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CraftPattern pattern
                && pattern.size == size && pattern.cells.equals(cells);
    }

    @Override
    public int hashCode() {
        return 31 * size + cells.hashCode();
    }

    @Override
    public String toString() {
        return "CraftPattern[" + serialize() + "]";
    }
}
