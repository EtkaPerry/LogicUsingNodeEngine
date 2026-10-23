package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.catalog.BlockCatalog;
import com.etka.lune.bot.memory.RefusedBreaks;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.path.WaterEscape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
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
    /**
     * Swinging ticks past twice what a block should take before the server is taken to be keeping
     * it. Three seconds of slack covers lag and the game's own pause between two blocks.
     */
    private static final int REFUSAL_SLACK_TICKS = 60;
    /** How soon a block she broke must stand there again to count as put back rather than placed. */
    private static final int RETURN_WINDOW_TICKS = 40;
    /** Breaks put back before the block is refused; one can be a lag desync, two is the server. */
    private static final int RETURNS_BEFORE_REFUSED = 2;
    /**
     * A second without a swing at it, and the clock on a block starts again: the break was really
     * let go of - a fight, a breath - and its progress with it, so the next swing starts from zero.
     */
    private static final int WORK_GAP_TICKS = 20;

    private BlockPos current;
    private boolean destroying;
    /** What is under the swing, kept because a finished break can only be read as air. */
    private BlockState destroyingState;
    private final StatusText failureReason = new StatusText();
    /**
     * The block the swing clock is running on, its swinging ticks, and when it last swung. Kept
     * across {@link #stop} on purpose: only ticks that swung are counted, so a pause never runs it,
     * and a walk that lets go and comes back to the same block every few ticks must not wind it
     * back to zero.
     */
    private BlockPos workPos;
    private int workTicks;
    private int workLastTick;
    /** The last block she finished breaking, what stood there, and when, to notice it put back. */
    private BlockPos lastBroken;
    private Block lastBrokenBlock;
    private int lastBrokenTick;
    private BlockPos returnsAt;
    private int returns;
    private boolean refused;

    /** Outcome of one tick of work on a block. */
    public enum Progress {
        /** Still aiming, clearing an obstruction, or mining. */
        WORKING,
        /** The requested block is gone. */
        FINISHED,
        /** Nothing in the hotbar can harvest it; mining anyway would destroy the drop. */
        NO_TOOL,
        /**
         * The resolved block must not be broken: it would release unsafe water or lava, or the
         * server will not allow it ({@link #wasRefused()}). Either way, go round it or leave it.
         */
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
        failureReason.clear();
        refused = false;
        if (WaterEscape.needsAir(ctx.player)) {
            stop(ctx);
            WaterEscape.tick(ctx);
            return Progress.WORKING;
        }
        noteFinishedBreak(ctx);
        if (ctx.level.getBlockState(wanted).isAir()) {
            boolean completedByBot = destroying;
            BlockState broken = destroyingState;
            stop(ctx);
            workPos = null;
            if (completedByBot) {
                ctx.debug.blocksBroken++;
                countHarvest(ctx, broken);
            }
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
        if (RefusedBreaks.get().isRefused(pos, state) || putBack(ctx, pos, state)) {
            return refuse(ctx, pos, state, blockName);
        }
        if (protectedPositions.contains(pos.asLong())) {
            stop(ctx);
            failureReason.set("lune.status.break.refusing_route_support");
            ctx.debug.breaking(pos, blockName, failureReason.text());
            return Progress.HAZARD;
        }
        if (MovementHelper.wouldOpenLava(ctx.level, pos) && !ctx.player.isInLava()) {
            stop(ctx);
            failureReason.set("lune.status.break.refusing_lava_flow",
                    state.getBlock().getName().getString());
            ctx.debug.breaking(pos, blockName, failureReason.text());
            return Progress.HAZARD;
        }
        if (MovementHelper.wouldOpenWater(ctx.level, pos)) {
            boolean waterBreachAllowed = allowNecessaryWaterBreach || ctx.player.isUnderWater();
            boolean enoughAir = ctx.player.getAirSupply() > ctx.player.getMaxAirSupply() * 3 / 5;
            boolean hasExit = WaterEscape.hasBreathableExit(ctx.level, pos);
            if (!waterBreachAllowed || !enoughAir || !hasExit) {
                stop(ctx);
                failureReason.set(!waterBreachAllowed
                        ? "lune.status.break.refusing_water_breach"
                        : !enoughAir
                                ? "lune.status.break.not_enough_air_for_breach"
                                : "lune.status.break.water_has_no_exit");
                ctx.debug.breaking(pos, blockName, failureReason.text());
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
            ctx.gameMode.stopUsingItem();
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
        // The swing clock. A protected block - a claim, LOTR's loot barrels - is mined to the end
        // on the client, put back by the server before the next tick, and mined again, forever;
        // from here that looks exactly like a slow block, so the only tell is the time it takes.
        // Ticks the mouse was not hers did not swing, and do not count.
        if (ctx.gameMode.mayAct()) {
            int now = ctx.player.tickCount;
            if (!pos.equals(workPos) || now - workLastTick > WORK_GAP_TICKS) {
                workPos = pos.immutable();
                workTicks = 0;
            }
            workLastTick = now;
            if (++workTicks > refusalTicks(ctx, pos, state)) {
                return refuse(ctx, pos, state, blockName);
            }
        }
        if (!accepted) {
            // MultiPlayerGameMode rejects a stale or out-of-range destroy packet. Do not keep
            // claiming that a break is in progress: callers would reset their stall timer forever
            // while the player only swings at an unchanged block.
            boolean completedByBot = destroying;
            BlockState broken = destroyingState;
            destroying = false;
            destroyingState = null;
            current = null;
            if (ctx.level.getBlockState(pos).isAir()) {
                workPos = null;
                if (completedByBot) {
                    ctx.debug.blocksBroken++;
                    countHarvest(ctx, broken);
                }
                return Progress.FINISHED;
            }
            ctx.debug.breaking(pos, blockName, "game rejected break input");
            return Progress.WORKING;
        }
        destroying = true;
        destroyingState = state;
        ctx.gameMode.swing(InteractionHand.MAIN_HAND);
        ctx.debug.breaking(pos, blockName, "breaking");
        return Progress.WORKING;
    }

    /** Remembers a break she saw through to air, so the same block standing there again is noticed. */
    private void noteFinishedBreak(BotContext ctx) {
        if (destroying && current != null && destroyingState != null
                && ctx.level.getBlockState(current).isAir()) {
            lastBroken = current.immutable();
            lastBrokenBlock = destroyingState.getBlock();
            lastBrokenTick = ctx.player.tickCount;
        }
    }

    /**
     * Whether the block she just broke is standing there again: the server put it back. The
     * slower half of the swing clock's job, for a server far enough away that the client shows
     * the air for a tick first. Sand and gravel are excluded, because falling into the hole she
     * just made is what they do.
     */
    private boolean putBack(BotContext ctx, BlockPos pos, BlockState state) {
        if (lastBroken == null || !lastBroken.equals(pos)) {
            return false;
        }
        boolean back = state.getBlock() == lastBrokenBlock
                && !(state.getBlock() instanceof FallingBlock)
                && ctx.player.tickCount - lastBrokenTick <= RETURN_WINDOW_TICKS;
        lastBroken = null;
        if (!pos.equals(returnsAt)) {
            returnsAt = pos.immutable();
            returns = 0;
        }
        if (!back) {
            returns = 0;
            return false;
        }
        return ++returns >= RETURNS_BEFORE_REFUSED;
    }

    /** Swinging ticks after which a block that is still standing is taken to be kept by the server. */
    private static int refusalTicks(BotContext ctx, BlockPos pos, BlockState state) {
        float perTick = state.getDestroyProgress(ctx.player, ctx.level, pos);
        return perTick <= 0.0F ? Integer.MAX_VALUE
                : 2 * Mth.ceil(1.0F / perTick) + REFUSAL_SLACK_TICKS;
    }

    /**
     * Gives the block up for good: remembered in {@link RefusedBreaks}, so that no route plans
     * through it and no card swings at it again. Sneaking would get past some of these - LOTR's
     * loot barrels ask for exactly that - and is deliberately not tried: the guard is there to
     * make a player choose to break it, and a bot on its way somewhere has not chosen.
     */
    private Progress refuse(BotContext ctx, BlockPos pos, BlockState state, String blockName) {
        stop(ctx);
        RefusedBreaks.get().remember(pos, state);
        refused = true;
        workPos = null;
        workTicks = 0;
        lastBroken = null;
        returnsAt = null;
        returns = 0;
        ctx.debug.count("breaks_refused");
        failureReason.set("lune.status.break.refused", blockName);
        ctx.debug.breaking(pos, blockName, failureReason.text());
        return Progress.HAZARD;
    }

    /**
     * True when the last {@link Progress#HAZARD} was the server keeping the block rather than a
     * fluid behind it. A walk replans round it; a card that wanted that block leaves it.
     */
    public boolean wasRefused() {
        return refused;
    }

    /**
     * Splits the plain break counter into the two kinds of block a run is actually judged by. A
     * thousand broken blocks says nothing; four ores and sixteen logs says what the run was.
     *
     * <p>An ore is what {@link BlockCatalog#isOre} says, the same as the Mine card's picker. This
     * asked for a depth profile once, which counted nether quartz as no ore, and would count every
     * block of a staircase through deepslate as one now that deepslate has a depth.</p>
     */
    private static void countHarvest(BotContext ctx, BlockState state) {
        if (state == null) {
            return;
        }
        if (state.is(BlockTags.LOGS)) {
            ctx.debug.count("logs_chopped");
        } else if (BlockCatalog.isOre(state.getBlock())) {
            ctx.debug.count("ores_mined");
        }
    }

    /** Why the last break was refused, keyed so a task can adopt it without losing meaning. */
    public StatusText getFailureReason() {
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
        destroyingState = null;
        current = null;
    }
}
