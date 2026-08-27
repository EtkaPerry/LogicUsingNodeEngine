package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.path.WaterEscape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Aims at a block and mines it, exactly the way holding left-click does: look at it, start the
 * break, then keep the break going every tick while swinging.
 * <p>
 * Crucially it mines what the player could <em>actually hit</em>, not the block that was asked for.
 * Distance alone is not reach: a ray from the eye can be stopped by dirt, leaves or a vine, and
 * sending a break for the block behind them mines straight through solid terrain. That leaves a
 * pocket the bot can neither walk into nor collect from, which looks exactly like the bot freezing.
 * So each tick it casts the same ray the game uses for the crosshair and works on whatever that ray
 * lands on - which also means it naturally tunnels toward a buried target rather than reaching
 * through the rock in front of it.
 * <p>
 * Shared by every digging task so they all pick the same face, respect the same reach, and release
 * the break properly when the target changes. A dangling break is what makes a bot appear to freeze
 * mid-swing.
 */
public final class BlockBreaker {

    /** Vanilla block interaction range. */
    public static final double REACH = 4.5;
    /** How closely the view must be aimed before swinging, in degrees. */
    private static final float AIM_TOLERANCE = 12.0F;

    private BlockPos current;
    private boolean destroying;
    private String failureReason = "";

    /** Outcome of one tick of work on a block. */
    public enum Progress {
        /** Still aiming, clearing an obstruction, or mining. */
        WORKING,
        /** The requested block is gone. */
        FINISHED,
        /** Nothing in the hotbar can harvest it; mining anyway would destroy the drop. */
        NO_TOOL,
        /** Breaking the resolved block would release unsafe water or lava. */
        HAZARD
    }

    /** True while the target is out of range and the caller should walk closer instead. */
    public boolean isOutOfReach(BotContext ctx, BlockPos pos) {
        return ctx.player.getEyePosition().distanceToSqr(Vision.blockAimPoint(ctx, pos))
                > REACH * REACH;
    }

    /** Works toward clearing {@code wanted} for one tick. */
    public Progress tick(BotContext ctx, BlockPos wanted) {
        return tick(ctx, wanted, false);
    }

    /**
     * Works toward clearing {@code wanted}. Water breaches are only enabled for a route that has
     * already failed to find an open walking/swimming alternative; lava remains forbidden.
     */
    public Progress tick(BotContext ctx, BlockPos wanted, boolean allowNecessaryWaterBreach) {
        return tick(ctx, wanted, allowNecessaryWaterBreach, Set.of());
    }

    /** As above, but refuses to break route-support blocks owned by the calling task. */
    public Progress tick(BotContext ctx, BlockPos wanted, boolean allowNecessaryWaterBreach,
                         Set<Long> protectedPositions) {
        return tick(ctx, wanted, allowNecessaryWaterBreach, protectedPositions, null);
    }

    /**
     * As above, but use {@code toolState} when choosing the item to hold. This is needed when the
     * visible block being opened is only a blocker: dirt and stone do not require a tool, so the
     * ordinary selector could leave food in the hand even though the eventual ore does require a
     * pickaxe. The blocker is still the only block resolved and broken by this method.
     */
    public Progress tick(BotContext ctx, BlockPos wanted, boolean allowNecessaryWaterBreach,
                         Set<Long> protectedPositions, BlockState toolState) {
        failureReason = "";
        if (WaterEscape.needsAir(ctx.player)) {
            stop(ctx);
            WaterEscape.tick(ctx);
            return Progress.WORKING;
        }
        if (ctx.level.getBlockState(wanted).isAir()) {
            stop(ctx);
            return Progress.FINISHED;
        }

        Vec3 eye = ctx.player.getEyePosition();
        Vec3 wantedAim = Vision.blockAimPoint(ctx, wanted);
        // OUTLINE, not COLLIDER: it's the shape the crosshair uses, and it's why a vine or a leaf
        // blocks a player's reach even though neither has a collision box.
        BlockHitResult hit = ctx.level.clip(new ClipContext(eye, wantedAim,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player));

        boolean blocked = hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(wanted);
        BlockPos pos = blocked ? hit.getBlockPos() : wanted;

        BlockState state = ctx.level.getBlockState(pos);
        String blockName = state.getBlock().getName().getString();
        ctx.debug.breaking(pos, blockName, blocked ? "visible obstruction before requested block" : "resolving visible face");
        if (state.isAir()) {
            // The obstruction went in the same tick it was found; try again next tick.
            stop(ctx);
            return Progress.WORKING;
        }
        if (protectedPositions.contains(pos.asLong())) {
            stop(ctx);
            failureReason = "refusing to destroy an existing route-support block";
            ctx.debug.breaking(pos, blockName, failureReason);
            return Progress.HAZARD;
        }
        if (MovementHelper.wouldOpenLava(ctx.level, pos) && !ctx.player.isInLava()) {
            stop(ctx);
            failureReason = "refusing to break " + state.getBlock().getName().getString()
                    + " because lava would flow through";
            ctx.debug.breaking(pos, blockName, failureReason);
            return Progress.HAZARD;
        }
        if (MovementHelper.wouldOpenWater(ctx.level, pos)) {
            boolean waterBreachAllowed = allowNecessaryWaterBreach || ctx.player.isUnderWater();
            boolean enoughAir = ctx.player.getAirSupply() > ctx.player.getMaxAirSupply() * 3 / 5;
            boolean hasExit = WaterEscape.hasBreathableExit(ctx.level, pos);
            if (!waterBreachAllowed || !enoughAir || !hasExit) {
                stop(ctx);
                failureReason = !waterBreachAllowed
                        ? "refusing to open a water breach while mining"
                        : !enoughAir
                                ? "not enough air to open this water breach"
                                : "water behind this block has no nearby breathable exit";
                ctx.debug.breaking(pos, blockName, failureReason);
                return Progress.HAZARD;
            }
        }
        // Equip before swinging: the wrong pickaxe on diamond ore doesn't mine it slowly, it
        // destroys it outright. For a visible blocker, use the requested ore's state so a
        // non-tool block cannot leave food selected while the prospect is trying to open the wall.
        if (!ToolSelector.equipFor(ctx, toolState == null ? state : toolState)) {
            stop(ctx);
            ctx.debug.breaking(pos, blockName, "no suitable tool");
            return Progress.NO_TOOL;
        }
        // A nested food action can leave the player using the item while the next mining tick is
        // already resolving a visible blocker. Breaking must own the main hand for this tick;
        // otherwise the game ignores the destroy input and the caller appears frozen.
        if (ctx.player.isUsingItem()) {
            ctx.player.stopUsingItem();
        }
        if (!pos.equals(current)) {
            // Changing target mid-break leaves the old one half-mined unless we let go first.
            stop(ctx);
            current = pos;
        }

        Vec3 centre = Vision.blockAimPoint(ctx, pos);
        ctx.look.lookAt(ctx.player, centre);
        if (!ctx.look.isLookingAt(ctx.player, centre, AIM_TOLERANCE)) {
            ctx.debug.breaking(pos, blockName, "turning to block");
            return Progress.WORKING;
        }

        // Prefer the face the ray actually struck; fall back to the one pointing at the player.
        Direction face = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                ? hit.getDirection()
                : Direction.getApproximateNearest(eye.x - centre.x, eye.y - centre.y, eye.z - centre.z);

        boolean accepted;
        if (destroying) {
            accepted = ctx.gameMode.continueDestroyBlock(pos, face);
        } else {
            accepted = ctx.gameMode.startDestroyBlock(pos, face);
        }
        if (!accepted) {
            // MultiPlayerGameMode rejects a stale or out-of-range destroy packet. Do not keep
            // claiming that a break is in progress: callers would reset their stall timer forever
            // while the player only swings at an unchanged block.
            destroying = false;
            current = null;
            if (ctx.level.getBlockState(pos).isAir()) {
                return Progress.FINISHED;
            }
            ctx.debug.breaking(pos, blockName, "game rejected break input");
            return Progress.WORKING;
        }
        destroying = true;
        ctx.player.swing(InteractionHand.MAIN_HAND);
        ctx.debug.breaking(pos, blockName, "breaking");
        return Progress.WORKING;
    }

    public String getFailureReason() {
        return failureReason;
    }

    /**
     * True while a break is actually under way, as opposed to still turning toward the block.
     * <p>
     * Both report {@link Progress#WORKING}, and callers that treat the two the same have no way to
     * notice a turn that never completes - the aim tolerance is never met, the stall timer is reset
     * every tick because "mining is progress", and the bot waits forever on a block it is not
     * mining.
     */
    public boolean isDestroying() {
        return destroying;
    }

    public void stop(BotContext ctx) {
        if (destroying) {
            ctx.gameMode.stopDestroyBlock();
            destroying = false;
        }
        current = null;
    }
}
