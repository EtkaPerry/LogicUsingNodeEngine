package com.etka.lune.bot.catalog;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * What the Craft card is asked to make: an item named from the recipe book, or a grid drawn by hand.
 * <p>
 * One value rather than three parameters. The card used to carry a mode switch, an item, a drawing
 * and two different counts, and reading it took longer than using it - while the drawing, the part
 * worth seeing, was the row nobody found. A recipe is one question with two ways of answering it, so
 * it is one row that opens one editor.
 * <p>
 * Both answers are kept, not just the one in force: someone who names an item, tries a drawing and
 * changes their mind should find their item still there.
 */
public final class CraftRecipe {

    private final boolean drawn;
    private final Item item;
    private final CraftPattern pattern;

    private CraftRecipe(boolean drawn, Item item, CraftPattern pattern) {
        this.drawn = drawn;
        this.item = item;
        this.pattern = pattern == null ? CraftPattern.empty(CraftPattern.LARGE) : pattern;
    }

    public static CraftRecipe empty() {
        return new CraftRecipe(false, null, CraftPattern.empty(CraftPattern.LARGE));
    }

    public static CraftRecipe ofItem(Item item) {
        return new CraftRecipe(false, item, CraftPattern.empty(CraftPattern.LARGE));
    }

    public static CraftRecipe ofPattern(CraftPattern pattern) {
        return new CraftRecipe(true, null, pattern);
    }

    /** True when the recipe is a hand-drawn grid rather than an item from the book. */
    public boolean isDrawn() {
        return drawn;
    }

    /** The named item, or null when nothing is named. Meaningful only when not {@link #isDrawn()}. */
    public Item item() {
        return item;
    }

    /** The drawing. Never null, and empty until something is drawn. */
    public CraftPattern pattern() {
        return pattern;
    }

    /** Keeps the drawing while switching to a named item, so neither answer is lost. */
    public CraftRecipe withItem(Item item) {
        return new CraftRecipe(false, item, pattern);
    }

    /** Keeps the named item while switching to the drawing. */
    public CraftRecipe withPattern(CraftPattern pattern) {
        return new CraftRecipe(true, item, pattern);
    }

    /** Whether this recipe says enough to be built into work. */
    public boolean isReady() {
        return drawn ? !pattern.isEmpty() : item != null;
    }

    /** How the recipe reads in the card's row. */
    public String describe() {
        if (drawn) {
            return pattern.isEmpty() ? Lang.get("lune.gui.recipe.empty_grid") : pattern.describe();
        }
        return item == null ? Lang.get("lune.gui.param.not_set") : InventoryHelper.itemName(item);
    }

    /**
     * Serialises as {@code <mode>|<item id>|<pattern>}. Both halves are written, so the answer the
     * player is not currently using survives being saved and loaded.
     */
    public String serialize() {
        return (drawn ? "grid" : "item") + "|"
                + (item == null ? "" : BuiltInRegistries.ITEM.getKey(item)) + "|"
                + pattern.serialize();
    }

    /** Reads {@link #serialize}, tolerating an item or a cell this instance does not have. */
    public static CraftRecipe deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return empty();
        }
        String[] parts = raw.split("\\|", 3);
        boolean drawn = parts.length > 0 && "grid".equals(parts[0].trim());
        Item named = null;
        if (parts.length > 1 && !parts[1].isBlank()) {
            Identifier key = Identifier.tryParse(parts[1].trim());
            named = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        }
        CraftPattern drawing = parts.length > 2
                ? CraftPattern.deserialize(parts[2]) : CraftPattern.empty(CraftPattern.LARGE);
        return new CraftRecipe(drawn, named, drawing);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CraftRecipe recipe && recipe.drawn == drawn
                && recipe.item == item && recipe.pattern.equals(pattern);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(drawn, item, pattern);
    }

    @Override
    public String toString() {
        return "CraftRecipe[" + serialize() + "]";
    }
}
