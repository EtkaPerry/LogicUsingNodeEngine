package com.etka.lune.bot.catalog;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * What a crop is ready for and what plants it again: the one answer for the player's Harvest and a
 * Lumi's. Pure - registries and block states only - so a server can ask it too.
 *
 * <p>Vanilla crops are mapped explicitly; modded crops fall back to the usual naming conventions
 * ({@code crop_name + "_seeds"} or the crop's own item for things like carrots and potatoes).</p>
 */
public final class Crops {

    private static final Map<Block, Item> VANILLA_SEEDS = Map.of(
            Blocks.WHEAT, Items.WHEAT_SEEDS,
            Blocks.CARROTS, Items.CARROT,
            Blocks.POTATOES, Items.POTATO,
            Blocks.BEETROOTS, Items.BEETROOT_SEEDS,
            Blocks.NETHER_WART, Items.NETHER_WART);

    private Crops() {
    }

    /** True when the crop is fully grown and ready to break. */
    public static boolean isMature(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        // Nether wart isn't a CropBlock but still has an AGE property.
        if (block == Blocks.NETHER_WART) {
            return state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        }
        return false;
    }

    /** Tries to find the seed item that plants this crop; air when there is none. */
    public static Item seedFor(Block crop) {
        Item explicit = VANILLA_SEEDS.get(crop);
        if (explicit != null) {
            return explicit;
        }

        Identifier id = BuiltInRegistries.BLOCK.getKey(crop);
        if (id == null) {
            return Items.AIR;
        }

        // Many modded crops use "tomato" -> "tomato_seeds".
        Identifier seedId = Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_seeds");
        Item fromName = BuiltInRegistries.ITEM.getOptional(seedId).orElse(null);
        if (fromName != null && fromName != Items.AIR) {
            return fromName;
        }

        // Some crops (carrots, potatoes) are planted with the crop item itself.
        Item fromBlock = crop.asItem();
        if (fromBlock != null && fromBlock != Items.AIR) {
            return fromBlock;
        }

        // Plural fallback: "tomatoes" -> "tomato_seeds".
        String path = id.getPath();
        if (path.endsWith("s")) {
            String singular = path.substring(0, path.length() - 1);
            seedId = Identifier.fromNamespaceAndPath(id.getNamespace(), singular + "_seeds");
            fromName = BuiltInRegistries.ITEM.getOptional(seedId).orElse(null);
            if (fromName != null && fromName != Items.AIR) {
                return fromName;
            }
        }

        return Items.AIR;
    }
}
