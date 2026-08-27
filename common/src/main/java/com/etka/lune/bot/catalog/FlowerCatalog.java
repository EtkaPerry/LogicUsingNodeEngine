package com.etka.lune.bot.catalog;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which dye each flower crafts into, and how many.
 * <p>
 * This is the one thing the recipe book cannot answer in the order the bot needs it. Crafting is
 * data-driven everywhere else in the mod, but choosing <em>which flower to walk to</em> has to
 * happen before any crafting is attempted, and that decision needs the colour up front.
 * <p>
 * Only vanilla's unambiguous single-flower dyes are listed. Anything that needs mixing (green from
 * smelted cactus, light grey from bone meal and ink) is deliberately absent: a bot that has to
 * combine two dyes to reach a colour is no longer taking a short detour, and the whole point of the
 * flower path is that it costs one stop.
 */
public final class FlowerCatalog {

    /** A flower and what one of it makes. */
    public record Dye(DyeColor colour, int perFlower) {}

    private static final Map<Block, Dye> FLOWERS = new LinkedHashMap<>();

    static {
        // One flower, one dye.
        single(Blocks.DANDELION, DyeColor.YELLOW);
        single(Blocks.GOLDEN_DANDELION, DyeColor.YELLOW);
        single(Blocks.WILDFLOWERS, DyeColor.YELLOW);
        single(Blocks.POPPY, DyeColor.RED);
        single(Blocks.RED_TULIP, DyeColor.RED);
        single(Blocks.BLUE_ORCHID, DyeColor.LIGHT_BLUE);
        single(Blocks.ALLIUM, DyeColor.MAGENTA);
        single(Blocks.AZURE_BLUET, DyeColor.LIGHT_GRAY);
        single(Blocks.WHITE_TULIP, DyeColor.LIGHT_GRAY);
        single(Blocks.OXEYE_DAISY, DyeColor.LIGHT_GRAY);
        single(Blocks.ORANGE_TULIP, DyeColor.ORANGE);
        single(Blocks.TORCHFLOWER, DyeColor.ORANGE);
        single(Blocks.OPEN_EYEBLOSSOM, DyeColor.ORANGE);
        single(Blocks.CLOSED_EYEBLOSSOM, DyeColor.GRAY);
        single(Blocks.PINK_TULIP, DyeColor.PINK);
        single(Blocks.PINK_PETALS, DyeColor.PINK);
        single(Blocks.CACTUS_FLOWER, DyeColor.PINK);
        single(Blocks.CORNFLOWER, DyeColor.BLUE);
        single(Blocks.LILY_OF_THE_VALLEY, DyeColor.WHITE);
        // The tall ones are two blocks and two dyes, which halves how many have to be found.
        tall(Blocks.SUNFLOWER, DyeColor.YELLOW);
        tall(Blocks.LILAC, DyeColor.MAGENTA);
        tall(Blocks.ROSE_BUSH, DyeColor.RED);
        tall(Blocks.PEONY, DyeColor.PINK);
        tall(Blocks.PITCHER_PLANT, DyeColor.CYAN);
        // Wither roses are black dye, and are never worth touching: standing next to one applies
        // the wither effect. Left out on purpose rather than forgotten.
    }

    private FlowerCatalog() {}

    private static void single(Block flower, DyeColor colour) {
        FLOWERS.put(flower, new Dye(colour, 1));
    }

    private static void tall(Block flower, DyeColor colour) {
        FLOWERS.put(flower, new Dye(colour, 2));
    }

    /** Every flower the bot knows how to turn into a dye. */
    public static Set<Block> flowers() {
        return Set.copyOf(FLOWERS.keySet());
    }

    /** What one of this flower crafts into, or null when it is not a dye source we use. */
    public static Dye dyeOf(Block flower) {
        return FLOWERS.get(flower);
    }

    /** Every flower that produces {@code colour}, so a search can accept any of them. */
    public static Set<Block> flowersFor(DyeColor colour) {
        Set<Block> matching = new LinkedHashSet<>();
        for (Map.Entry<Block, Dye> entry : FLOWERS.entrySet()) {
            if (entry.getValue().colour() == colour) {
                matching.add(entry.getKey());
            }
        }
        return matching;
    }

    /** The plain colour name shared with {@code BedPolicy}, e.g. {@code light_gray}. */
    public static String colourName(DyeColor colour) {
        return colour.getSerializedName();
    }

    /** The colour of a wool stack, taken from its registered name, or null when it is not wool. */
    public static String woolColourName(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ItemTags.WOOL)) {
            return null;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        int suffix = path.lastIndexOf("_wool");
        return suffix <= 0 ? null : path.substring(0, suffix);
    }

    /** Resolves a colour name back to a {@link DyeColor}, or null when nothing matches. */
    public static DyeColor colourByName(String name) {
        for (DyeColor colour : DyeColor.values()) {
            if (colour.getSerializedName().equals(name)) {
                return colour;
            }
        }
        return null;
    }

    /** True when the stack is the dye for {@code colour}, whatever mod registered it. */
    public static boolean isDye(ItemStack stack, DyeColor colour) {
        return !stack.isEmpty()
                && stack.get(net.minecraft.core.component.DataComponents.DYE) == colour;
    }

    /** True when the stack is wool of {@code colour}. */
    public static boolean isWool(ItemStack stack, DyeColor colour) {
        return colour.getSerializedName().equals(woolColourName(stack));
    }

    /** The item form of a flower, for counting what has been picked up. */
    public static Item itemOf(Block flower) {
        return flower.asItem();
    }
}
