package com.etka.lune.bot.util;

import com.etka.lune.bot.catalog.BlockCatalog;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The item filters the storage cards offer, in one place so a chest and a backpack agree on what
 * "ores" means.
 *
 * <p>The names are identifiers, written into saved tasks and compared against here; only their
 * rendering is translated. They are deliberately broad - a long mining or harvesting run should
 * be followed by one deposit step, not five.</p>
 */
public final class ItemFilters {

    public static final String ORES = "Ores";
    public static final String LOGS = "Logs";
    public static final String CROPS = "Crops";
    public static final String STONE = "Stone";
    /** Everything that is not a tool, a weapon, armour or food: what a trip brought back. */
    public static final String LOOT = "Loot";
    public static final String ALL = "All";
    /** The chosen item only. */
    public static final String ITEM = "Item";
    public static final String ANY_FOOD = "Any food";
    public static final String TOOLS = "Tools and weapons";
    public static final String BLOCKS = "Blocks";
    public static final String EVERYTHING = "Everything";

    /** What a deposit may put away. */
    public static final List<String> DEPOSIT = List.of(ORES, LOGS, CROPS, STONE, LOOT, ALL);
    /** What may be taken back out. */
    public static final List<String> TAKE = List.of(ITEM, ANY_FOOD, TOOLS, BLOCKS, EVERYTHING);

    private ItemFilters() {}

    /**
     * The test for a filter name. {@code chosen} is the item the {@link #ITEM} filter means; the
     * other filters ignore it. An unknown name matches nothing rather than everything.
     */
    public static Predicate<ItemStack> matcher(String filter, Item chosen) {
        String name = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "ores" -> ItemFilters::isOre;
            case "logs" -> ItemFilters::isLog;
            case "crops" -> ItemFilters::isCrop;
            case "stone" -> ItemFilters::isStone;
            case "loot" -> ItemFilters::isLoot;
            case "all", "everything" -> stack -> !stack.isEmpty();
            case "item" -> stack -> chosen != null && stack.is(chosen);
            case "any food" -> ItemFilters::isFood;
            case "tools and weapons" -> ItemFilters::isToolOrWeapon;
            case "blocks" -> ItemFilters::isBlock;
            default -> stack -> false;
        };
    }

    public static boolean isOre(ItemStack stack) {
        return inBlockList(stack, BlockCatalog.ores()) || path(stack).endsWith("_ore");
    }

    public static boolean isLog(ItemStack stack) {
        return hasBlockTag(stack, BlockTags.LOGS)
                || path(stack).endsWith("_log")
                || path(stack).endsWith("_stem")
                || path(stack).endsWith("_wood");
    }

    public static boolean isCrop(ItemStack stack) {
        return inBlockList(stack, BlockCatalog.crops())
                || path(stack).matches("^(wheat|carrot|potato|beetroot|melon|pumpkin|cocoa|sugar_cane|cactus|nether_wart).*");
    }

    public static boolean isStone(ItemStack stack) {
        return path(stack).matches(".*(stone|cobble|deepslate|granite|andesite|diorite|tuff|calcite|netherrack|blackstone|end_stone|sandstone).*");
    }

    public static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    /** Anything worn: armour, an elytra, a carved pumpkin - whatever the game lets the player equip. */
    public static boolean isArmour(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.EQUIPPABLE);
    }

    /**
     * The things a player keeps rather than stores. The tool tags cover the modded ones too; the
     * named items are the vanilla oddities no tag collects.
     */
    public static boolean isToolOrWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (TagKey<Item> tag : TOOL_TAGS) {
            if (stack.is(tag)) {
                return true;
            }
        }
        return stack.has(DataComponents.TOOL) || InventoryHelper.isCombatWeapon(stack)
                || stack.is(Items.SHEARS) || stack.is(Items.FISHING_ROD) || stack.is(Items.FLINT_AND_STEEL)
                || stack.is(Items.SHIELD) || stack.is(Items.BOW) || stack.is(Items.CROSSBOW)
                || stack.is(Items.TRIDENT) || stack.is(Items.MACE) || stack.is(Items.BRUSH);
    }

    private static final List<TagKey<Item>> TOOL_TAGS = List.of(
            ItemTags.PICKAXES, ItemTags.AXES, ItemTags.SHOVELS, ItemTags.HOES, ItemTags.SWORDS);

    public static boolean isLoot(ItemStack stack) {
        return !stack.isEmpty() && !isToolOrWeapon(stack) && !isArmour(stack) && !isFood(stack);
    }

    public static boolean isBlock(ItemStack stack) {
        return !stack.isEmpty() && Block.byItem(stack.getItem()) != Blocks.AIR;
    }

    private static boolean inBlockList(ItemStack stack, List<Block> blocks) {
        Block block = Block.byItem(stack.getItem());
        return block != Blocks.AIR && blocks.contains(block);
    }

    private static boolean hasBlockTag(ItemStack stack, TagKey<Block> tag) {
        Block block = Block.byItem(stack.getItem());
        return block != Blocks.AIR && block.defaultBlockState().is(tag);
    }

    private static String path(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.getPath();
    }
}
