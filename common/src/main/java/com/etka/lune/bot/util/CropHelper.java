package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.catalog.Crops;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Knows how to replant crops. What a crop is ready for and which seed plants it are {@link Crops}',
 * which a Lumi's Harvest asks too.
 */
public final class CropHelper {

    private CropHelper() {}

    /** True when the crop is fully grown and ready to break. */
    public static boolean isMature(BlockState state) {
        return Crops.isMature(state);
    }

    /** The farmland, soul sand, or similar block the crop is growing on. */
    public static BlockPos soilFor(BlockPos crop) {
        return crop.below();
    }

    /** Tries to find the seed item that plants this crop. */
    public static Item seedFor(Block crop) {
        return Crops.seedFor(crop);
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
        Planting result = plantOn(ctx, cropPos, seed);
        return result == Planting.PLANTED
                || result == Planting.CLICKED && !ctx.level.getBlockState(cropPos).isAir();
    }

    /** How one planting click went. */
    public enum Planting {
        /** Something now grows where it was planted. */
        PLANTED,
        /** Clicked, and the world has not shown the result yet; ask again next tick. */
        CLICKED,
        /** Still turning to face the ground. */
        AIMING,
        /** Nothing of that kind is carried. */
        NO_ITEM
    }

    /**
     * Equips {@code item} and right-clicks the top of the ground under {@code spot}, the way a
     * player plants anything: a seed in farmland, a sapling in the grass where a tree stood.
     *
     * <p>Shared by Harvest and Replant Trees, because planting is the same gesture whatever goes in
     * the ground. It deliberately does not share Place Block's collision guard - nothing that grows
     * from a seed or a sapling has a hitbox to stand inside, so standing over the spot is no reason
     * to wait.</p>
     */
    public static Planting plantOn(BotContext ctx, BlockPos spot, Item item) {
        if (InventoryHelper.equip(ctx, stack -> stack.is(item)) < 0) {
            return Planting.NO_ITEM;
        }
        BlockPos soil = soilFor(spot);
        Vec3 hit = Vec3.atCenterOf(soil).add(0, 0.5, 0);
        ctx.look.lookAt(ctx.player, hit);
        if (!ctx.look.isLookingAt(ctx.player, hit, 15.0F)) {
            return Planting.AIMING;
        }
        ctx.gameMode.useItemOn(ctx.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, soil, false));
        ctx.gameMode.swing(InteractionHand.MAIN_HAND);
        return item instanceof BlockItem planted && ctx.level.getBlockState(spot).is(planted.getBlock())
                ? Planting.PLANTED : Planting.CLICKED;
    }
}
