package com.etka.lune.client.gui.widget;

import com.etka.lune.Constants;
import com.etka.lune.games.RiddleMatcher;
import com.etka.lune.games.RiddleRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every crafting recipe Recipe Riddle can ask about, read from the game as it stands.
 *
 * <p>Lune keeps no list of recipes; it asks. In a world of the player's own the integrated server
 * holds every recipe the game and its mods define, unlocked or not, and those are asked. On a
 * server the client only ever hears of the recipes its recipe book has unlocked, so those are the
 * riddles there, and there are more of them the longer the player plays. Either way each comes as
 * the recipe book's own display - a shape and, for each square, what may go in it - which is the
 * same form in both places.</p>
 */
final class RiddleCatalog {

    /** The recipes, the items decoys are drawn from, and an item to draw for every id. */
    record Catalog(List<RiddleRecipe> recipes, List<String> universe, Map<String, ItemStack> stacks) {
        static final Catalog EMPTY = new Catalog(List.of(), List.of(), Map.of());
    }

    private static Catalog catalog = Catalog.EMPTY;
    /** The world the catalog was read in; weakly, so a world left behind is not kept alive by it. */
    private static WeakReference<ClientLevel> readIn = new WeakReference<>(null);
    private static int readFrom = -1;

    private RiddleCatalog() {}

    /** The catalog, read again when the world, or the number of recipes behind it, has changed. */
    static Catalog refresh() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return catalog;
        }
        List<RecipeDisplay> displays = displays(mc);
        if (readIn.get() != level || readFrom != displays.size()) {
            catalog = read(level, displays);
            readIn = new WeakReference<>(level);
            readFrom = displays.size();
        }
        return catalog;
    }

    static Catalog current() {
        return catalog;
    }

    /** What {@code grid} makes by any recipe the game knows, or null for nothing. */
    static RiddleRecipe crafts(String[] grid) {
        for (RiddleRecipe recipe : catalog.recipes()) {
            if (RiddleMatcher.matches(recipe, grid)) {
                return recipe;
            }
        }
        return null;
    }

    /** An item to draw for {@code id}: the one a recipe showed, or else the registry's. */
    static ItemStack stack(String id) {
        ItemStack shown = catalog.stacks().get(id);
        return shown != null ? shown : new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
    }

    private static List<RecipeDisplay> displays(Minecraft mc) {
        List<RecipeDisplay> displays = new ArrayList<>();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                try {
                    if (holder.value().getType() == RecipeType.CRAFTING) {
                        displays.addAll(holder.value().display());
                    }
                } catch (RuntimeException e) {
                    // A mod's recipe that cannot say what it looks like is left out, not the game.
                    Constants.LOG.debug("Recipe Riddle skipped {}: {}", holder.id(), e.toString());
                }
            }
        } else if (mc.player != null) {
            for (RecipeCollection collection : mc.player.getRecipeBook().getCollections()) {
                for (RecipeDisplayEntry entry : collection.getRecipes()) {
                    displays.add(entry.display());
                }
            }
        }
        return displays;
    }

    private static Catalog read(ClientLevel level, List<RecipeDisplay> displays) {
        ContextMap context = SlotDisplayContext.fromLevel(level);
        Map<String, ItemStack> stacks = new HashMap<>();
        List<RiddleRecipe> recipes = new ArrayList<>();
        Set<String> universe = new LinkedHashSet<>();
        for (RecipeDisplay display : displays) {
            RiddleRecipe recipe;
            try {
                recipe = recipe(display, context, stacks);
            } catch (RuntimeException e) {
                Constants.LOG.debug("Recipe Riddle skipped a recipe it could not read: {}", e.toString());
                continue;
            }
            if (recipe == null) {
                continue;
            }
            recipes.add(recipe);
            universe.add(recipe.result());
            for (Set<String> cell : recipe.cells()) {
                if (!cell.isEmpty()) {
                    universe.add(cell.iterator().next());
                }
            }
        }
        return new Catalog(List.copyOf(recipes), List.copyOf(universe), Map.copyOf(stacks));
    }

    /** A crafting display as a riddle, or null for anything else, or anything a player could not make. */
    private static RiddleRecipe recipe(RecipeDisplay display, ContextMap context, Map<String, ItemStack> stacks) {
        if (!(display instanceof ShapedCraftingRecipeDisplay) && !(display instanceof ShapelessCraftingRecipeDisplay)) {
            return null;
        }
        ItemStack result = display.result().resolveForFirstStack(context);
        if (result.isEmpty()) {
            return null;
        }
        String made = id(result, stacks);
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            List<Set<String>> cells = squares(shaped.ingredients(), context, stacks);
            if (cells == null || shaped.width() > RiddleMatcher.SIDE || shaped.height() > RiddleMatcher.SIDE
                    || cells.size() != shaped.width() * shaped.height()) {
                return null;
            }
            return RiddleRecipe.shaped(made, result.getCount(), shaped.width(), shaped.height(), cells);
        }
        ShapelessCraftingRecipeDisplay shapeless = (ShapelessCraftingRecipeDisplay) display;
        List<Set<String>> ingredients = squares(shapeless.ingredients(), context, stacks);
        if (ingredients == null || ingredients.isEmpty() || ingredients.size() > RiddleMatcher.CELLS
                || ingredients.contains(Set.of())) {
            return null;
        }
        return RiddleRecipe.shapeless(made, result.getCount(), ingredients);
    }

    /**
     * What each square may hold, as ids in the order the game lists them; an empty set for a square
     * that stays empty. Null if a square can be filled by nothing at all.
     */
    private static List<Set<String>> squares(List<SlotDisplay> slots, ContextMap context, Map<String, ItemStack> stacks) {
        List<Set<String>> squares = new ArrayList<>(slots.size());
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) {
                squares.add(Set.of());
                continue;
            }
            Set<String> ids = new LinkedHashSet<>();
            for (ItemStack stack : slot.resolveForStacks(context)) {
                if (!stack.isEmpty()) {
                    ids.add(id(stack, stacks));
                }
            }
            if (ids.isEmpty()) {
                return null;
            }
            squares.add(ids);
        }
        return squares;
    }

    /** The item's registry id, keeping the first stack seen of it to draw it by. */
    private static String id(ItemStack stack, Map<String, ItemStack> stacks) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!stacks.containsKey(id)) {
            ItemStack one = stack.copy();
            one.setCount(1);
            stacks.put(id, one);
        }
        return id;
    }
}
