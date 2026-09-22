package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.compat.Hands;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Filling and emptying buckets.
 *
 * <p>A fluid bucket is the one ordinary item that does nothing at all when it is clicked on a
 * block. {@code BucketItem} implements {@code use} and never {@code useOn}, so the block-click
 * interaction every other placement in this mod uses passes straight through it: no water, no
 * lava, no bucket filled, and no error either. Vanilla hides the difference because
 * {@code Minecraft.startUseItem} falls through to {@code useItem} whenever the block click is not
 * consumed - a bot that sends only the block click just gets silence. That fallthrough lives here
 * so no caller has to remember it.
 *
 * <p>The other half of the same fact is that {@code use} does not accept a target. It re-casts its
 * own ray from the player's eyes, and the server casts it a third time from the yaw and pitch in
 * the packet, so where the fluid lands is decided by where the head is actually pointing and not
 * by any hit result the caller builds. {@link #placementTarget} and {@link #pickupTarget} answer
 * that question with the same ray the game will use, which is the only reach and line-of-sight
 * test worth running before spending a bucket.
 */
public final class BucketHelper {

    private BucketHelper() {
    }

    /** The ray {@code BucketItem.use} is about to cast: eyes, view vector, capped at block reach. */
    public static BlockHitResult aimRay(BotContext ctx) {
        // An empty bucket stops at a fluid source because it is looking for one; a full bucket
        // ignores fluids and looks for the block behind them. Matching that is what makes this
        // agree with the game rather than merely resemble it.
        ClipContext.Fluid fluid = isEmpty(ctx) ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE;
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 end = eye.add(ctx.player.calculateViewVector(ctx.player.getXRot(), ctx.player.getYRot())
                .scale(ctx.player.blockInteractionRange()));
        return ctx.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, ctx.player));
    }

    /**
     * Where the held fluid would land if the bucket were emptied this tick, or {@code null} when
     * the ray reaches nothing - which is also the answer to "is the target in reach yet".
     */
    public static BlockPos placementTarget(BotContext ctx) {
        BlockHitResult hit = aimRay(ctx);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos clickedPos = hit.getBlockPos();
        BlockPos inFront = clickedPos.relative(hit.getDirection());
        BlockState clicked = ctx.level.getBlockState(clickedPos);
        // Water poured at something waterloggable - leaves, slabs, stairs, fences - fills that
        // block instead of the space in front of it. Vanilla falls through to the space when the
        // block cannot take it, and, crucially, when the player is crouching: that is the whole
        // technique for landing on a canopy. One already-waterlogged block is not a second sink.
        return !ctx.player.isShiftKeyDown()
                && ctx.player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.WATER_BUCKET)
                && clicked.getBlock() instanceof LiquidBlockContainer container
                && container.canPlaceLiquid(ctx.player, ctx.level, clickedPos, clicked, Fluids.WATER)
                ? clickedPos : inFront;
    }

    /**
     * Whether water aimed at this block would end up inside it. Nothing is left to fall into when
     * it does, so a clutch that lands on one of these is not a clutch at all.
     */
    public static boolean soaksUpWater(BotContext ctx, BlockPos pos) {
        BlockState state = ctx.level.getBlockState(pos);
        return state.getBlock() instanceof LiquidBlockContainer container
                && container.canPlaceLiquid(ctx.player, ctx.level, pos, state, Fluids.WATER);
    }

    /** The source block an empty bucket would draw from this tick, or {@code null}. */
    public static BlockPos pickupTarget(BotContext ctx) {
        BlockHitResult hit = aimRay(ctx);
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    /** Sends the one interaction a bucket answers to. */
    public static void use(BotContext ctx) {
        ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
        Hands.swing(ctx.player, InteractionHand.MAIN_HAND);
    }

    private static boolean isEmpty(BotContext ctx) {
        ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        return held.is(Items.BUCKET);
    }
}
