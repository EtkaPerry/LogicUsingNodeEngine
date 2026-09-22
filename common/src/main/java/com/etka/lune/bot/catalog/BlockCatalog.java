package com.etka.lune.bot.catalog;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

import java.util.Comparator;
import java.util.List;

/**
 * Suggested block lists, derived from the live registry rather than hardcoded.
 * <p>
 * This is what makes the pickers mod-agnostic: another mod's ores and crops show up in the Mine and
 * Harvest selectors without Lune knowing anything about that mod. Vanilla sorts first because
 * that's what most players are looking for.
 */
public final class BlockCatalog {

    private static List<Block> all;
    private static List<Block> ores;
    private static List<Block> crops;
    private static List<Block> logs;
    private static List<Block> planks;
    private static List<Block> deadBushes;
    private static List<Block> buildingBlocks;

    private BlockCatalog() {}

    /** Anything the registry calls an ore, plus ancient debris, which isn't named like one. */
    public static List<Block> ores() {
        if (ores == null) {
            ores = BuiltInRegistries.BLOCK.stream()
                    .filter(block -> {
                        String path = key(block).getPath();
                        return path.endsWith("_ore") || path.equals("ancient_debris");
                    })
                    .sorted(vanillaFirst())
                    .toList();
        }
        return ores;
    }

    /**
     * Everything that grows through age stages. {@link CropBlock} covers vanilla and virtually all
     * modded crops; nether wart is the notable vanilla exception, as it has its own block class.
     */
    public static List<Block> crops() {
        if (crops == null) {
            crops = BuiltInRegistries.BLOCK.stream()
                    .filter(block -> block instanceof CropBlock || block == Blocks.NETHER_WART)
                    .sorted(vanillaFirst())
                    .toList();
        }
        return crops;
    }

    /**
     * Tree trunks. Driven by the {@code minecraft:logs} tag rather than a name pattern, because
     * that's the tag mods are expected to add their own wood to.
     */
    public static List<Block> logs() {
        if (logs == null) {
            logs = BuiltInRegistries.BLOCK.stream()
                    .filter(block -> block.defaultBlockState().is(BlockTags.LOGS))
                    .sorted(vanillaFirst())
                    .toList();
        }
        return logs;
    }

    /**
     * Plank blocks - useful for badlands or mineshafts where there are no trees but pre-built wood
     * exists. Uses the {@code minecraft:planks} tag.
     */
    public static List<Block> planks() {
        if (planks == null) {
            planks = BuiltInRegistries.BLOCK.stream()
                    .filter(block -> block.defaultBlockState().is(BlockTags.PLANKS))
                    .sorted(vanillaFirst())
                    .toList();
        }
        return planks;
    }

    /**
     * Desert-like plants that drop sticks when broken. In badlands with no trees these are a viable
     * stick source.
     */
    public static List<Block> deadBushes() {
        if (deadBushes == null) {
            deadBushes = List.of(Blocks.DEAD_BUSH);
        }
        return deadBushes;
    }

    /** Common blocks the bridge command can place. */
    public static List<Block> buildingBlocks() {
        if (buildingBlocks == null) {
            buildingBlocks = List.of(
                    Blocks.COBBLESTONE, Blocks.DIRT, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS,
                    Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS, Blocks.ACACIA_PLANKS,
                    Blocks.DARK_OAK_PLANKS, Blocks.MANGROVE_PLANKS, Blocks.CHERRY_PLANKS,
                    Blocks.BAMBOO_PLANKS, Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE,
                    Blocks.ANDESITE, Blocks.DEEPSLATE, Blocks.CALCITE, Blocks.TUFF,
                    Blocks.NETHERRACK, Blocks.BLACKSTONE, Blocks.END_STONE, Blocks.SANDSTONE,
                    // Logs last, and logs at all.
                    //
                    // Last, because every caller takes the first of these it is carrying four of,
                    // and wood is the one thing here that is usually wanted for something else.
                    //
                    // At all, because the bot spends most of its life carrying nothing but logs.
                    // A measured run met a Zombie with fifty-one spruce logs in the bag and
                    // reported "moving away from Zombie without building material": the pillar,
                    // the emergency wall and the one-block bridge were all unavailable to it
                    // because planks were on this list and the logs they are made from were not.
                    // A person puts a log down.
                    Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
                    Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG,
                    Blocks.CHERRY_LOG, Blocks.PALE_OAK_LOG,
                    Blocks.CRIMSON_STEM, Blocks.WARPED_STEM
            );
        }
        return buildingBlocks;
    }

    /** Every registered block, sorted vanilla first. Useful for an unconstrained picker. */
    public static List<Block> all() {
        if (all == null) {
            all = BuiltInRegistries.BLOCK.stream()
                    .sorted(vanillaFirst())
                    .toList();
        }
        return all;
    }

    private static Identifier key(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block);
    }

    private static Comparator<Block> vanillaFirst() {
        return Comparator
                .comparing((Block block) -> !key(block).getNamespace().equals("minecraft"))
                .thenComparing(block -> key(block).toString());
    }
}
