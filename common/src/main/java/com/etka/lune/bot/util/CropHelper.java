package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.compat.Hands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Knows how mature crops relate to their seed items and how to replant them.
 * <p>
 * Vanilla crops are mapped explicitly; modded crops fall back to the usual naming conventions
 * ({@code crop_name + "_seeds"} or the crop's own item for things like carrots and potatoes).
 */
public final class CropHelper {

    private static final Map<Block, Item> VANILLA_SEEDS = new HashMap<>();

    static {
        VANILLA_SEEDS.put(Blocks.WHEAT, Items.WHEAT_SEEDS);
        VANILLA_SEEDS.put(Blocks.CARROTS, Items.CARROT);
        VANILLA_SEEDS.put(Blocks.POTATOES, Items.POTATO);
        VANILLA_SEEDS.put(Blocks.BEETROOTS, Items.BEETROOT_SEEDS);
        VANILLA_SEEDS.put(Blocks.NETHER_WART, Items.NETHER_WART);
    }

    private CropHelper() {}

    /** True when the crop is fully grown and ready to break. */
    public static boolean isMature(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        // Nether wart isn't a CropBlock but still has an AGE property.
        if (block == Blocks.NETHER_WART) {
            return state.getValue(net.minecraft.world.level.block.NetherWartBlock.AGE) >= 3;
        }
        return false;
    }

    /** The farmland, soul sand, or similar block the crop is growing on. */
    public static BlockPos soilFor(BlockPos crop) {
        return crop.below();
    }

    /** Tries to find the seed item that plants this crop. */
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

    /** Whether the bot has at least one of the right seed in its inventory. */
    public static boolean hasSeed(BotContext ctx, Block crop) {
        Item seed = seedFor(crop);
        return seed != Items.AIR && InventoryHelper.has(ctx.player, seed, 1);
    }

    /**
     * Equips the seed and right-clicks the soil below the crop. Returns true if a crop now exists.
     */
    public static boolean plant(BotContext ctx, BlockPos cropPos) {
        return plant(ctx, cropPos, ctx.level.getBlockState(cropPos).getBlock());
    }

    /**
     * Plants a crop whose block has just been broken and is therefore no longer present at
     * {@code cropPos}. Callers that harvest first must retain the old crop block so seed lookup
     * does not accidentally inspect the air/soil left behind.
     */
    public static boolean plant(BotContext ctx, BlockPos cropPos, Block crop) {
        Item seed = seedFor(crop);
        if (seed == Items.AIR) {
            return false;
        }
        if (InventoryHelper.equip(ctx, stack -> stack.is(seed)) < 0) {
            return false;
        }

        BlockPos soil = soilFor(cropPos);
        Vec3 hit = Vec3.atCenterOf(soil).add(0, 0.5, 0);
        ctx.look.lookAt(ctx.player, hit);
        if (!ctx.look.isLookingAt(ctx.player, hit, 15.0F)) {
            return false;
        }

        ctx.gameMode.useItemOn(ctx.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, soil, false));
        Hands.swing(ctx.player, InteractionHand.MAIN_HAND);
        return !ctx.level.getBlockState(cropPos).isAir();
    }
}
