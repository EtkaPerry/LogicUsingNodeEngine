package com.etka.lune.bot.knowledge;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;
import java.util.Set;

/**
 * The coarse kind of thing the bot is out looking for.
 * <p>
 * A player does not think "I need 12 oak logs, therefore biome X"; they think "I need wood, so head
 * for the trees and not the mesa". {@link Need} is that middle layer: block targets collapse into a
 * handful of needs, and {@link BiomeKnowledge} rates biomes per need rather than per block. It also
 * means a modded log the bot has never heard of still resolves to {@link #WOOD} through its tag.
 */
public enum Need {

    WOOD("wood"),
    /** Crops, animals, anything edible growing on the surface. */
    FOOD("food"),
    /** Exposed stone, cliffs, cave mouths - where you dig without building a mineshaft first. */
    STONE("stone"),
    SAND("sand"),
    WATER("water"),
    /** Anything the bot has no biome opinion about; every biome scores neutral. */
    ANY("anything");

    private final String label;

    Need(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Collapses a block target set into the needs it implies.
     * <p>
     * Tags come first so modded blocks classify correctly, with a few explicit blocks for the cases
     * vanilla has no useful tag for.
     */
    public static Set<Need> of(Set<Block> targets) {
        EnumSet<Need> needs = EnumSet.noneOf(Need.class);
        if (targets == null || targets.isEmpty()) {
            return Set.of(ANY);
        }

        for (Block block : targets) {
            var state = block.defaultBlockState();
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.SAPLINGS)) {
                needs.add(WOOD);
            } else if (state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS)
                    || block == Blocks.PUMPKIN || block == Blocks.MELON
                    || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.HAY_BLOCK) {
                needs.add(FOOD);
            } else if (state.is(BlockTags.SAND) || block == Blocks.CACTUS || block == Blocks.DEAD_BUSH) {
                needs.add(SAND);
            } else if (block == Blocks.WATER || block == Blocks.KELP || block == Blocks.SEAGRASS) {
                needs.add(WATER);
            } else if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.STONE_ORE_REPLACEABLES)
                    || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES) || block == Blocks.GRAVEL) {
                needs.add(STONE);
            }
        }

        return needs.isEmpty() ? Set.of(ANY) : needs;
    }
}
