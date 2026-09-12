package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.WorkSite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Walks over nearby dropped items until there are none left in range.
 * <p>
 * There is no "pick up" action - standing on a drop collects it - so this is purely navigation. The
 * interesting part is everything that can go wrong, because a gathering step that never returns
 * blocks the whole task behind it:
 * <ul>
 *   <li><b>Arrived but not collected.</b> The approach goal is the item's own block, not "near" it.
 *       A looser goal can be satisfied while still outside pickup range, and then nothing ever
 *       changes: the walk reports success, the pickup check stays false, and the task spins.</li>
 *   <li><b>Every sub-task outcome is handled.</b> SUCCESS is not "keep going" - a finished
 *       {@link GotoTask} ticked again just keeps saying SUCCESS without pressing a key.</li>
 *   <li><b>Items move.</b> Water and explosions push drops, so the route is rebuilt when the target
 *       leaves the block it was heading for.</li>
 *   <li><b>Unreachable drops are remembered</b>, or the next scan picks the same one straight back
 *       up.</li>
 *   <li><b>A per-item deadline</b> backs all of it up. Whatever else goes wrong - an item wedged in
 *       a wall, a pickup that silently fails - the task gives up on that drop and moves on rather
 *       than hanging.</li>
 *   <li><b>A full inventory</b> makes collection impossible, so it finishes instead of trying.</li>
 * </ul>
 */
public final class LootTask implements Task {

    /**
     * How far vanilla grows the player's box when looking for items to collect. It is much flatter
     * than it is wide, which is why "one block away" is not the same as "close enough": a drop one
     * block below your feet is adjacent, and still outside this box.
     */
    private static final double PICKUP_REACH_XZ = 1.0;
    private static final double PICKUP_REACH_Y = 0.5;
    /** Ticks to spend on one drop before writing it off. */
    private static final int ATTEMPT_DEADLINE = 200;
    /** How near a drop has to be before walking at it by hand is worth trying. */
    private static final double NUDGE_RADIUS = 4.0;
    /** Ticks of hand-walking allowed per drop; two seconds crosses four blocks with room to spare. */
    private static final int MAX_NUDGE_TICKS = 40;

    private final int radius;
    private final int attemptDeadline;
    private final Predicate<ItemStack> wanted;

    /** Drops we failed to reach, so the next scan doesn't pick the same one straight back up. */
    private final Set<Integer> unreachable;
    private ItemEntity target;
    private GotoTask approach;
    /** Block the current route was aimed at, so drift can be detected. */
    private BlockPos approachAim;
    /** True once being beside the drop proved not to be close enough and we aim at the block itself. */
    private boolean standOnDrop;
    /** Ticks spent closing on a drop by hand after the router gave up on it. */
    private int nudgeTicks;
    private int attemptTicks;
    private int collected;
    private String collectionStrategy = CollectionPolicy.DEFAULT;
    private final StatusText status = new StatusText();

    /** Keeps the sweep working through one pile instead of zigzagging between the far ends of two. */
    private final WorkSite site = new WorkSite();

    public LootTask(int radius) {
        this(radius, ATTEMPT_DEADLINE, stack -> true);
    }

    /**
     * Creates a pickup sweep with a caller-specific patience limit. Small, opportunistic sweeps
     * (for example after felling a tree) should give up sooner than a deliberate Loot command.
     */
    public LootTask(int radius, int attemptDeadline) {
        this(radius, attemptDeadline, stack -> true);
    }

    /**
     * Creates a pickup sweep that only considers item stacks accepted by {@code wanted}. A
     * bounded filter matters for short gathering detours: a stone staircase should collect the
     * cobblestone it just exposed, not spend its whole deadline chasing an unrelated apple or
     * stick that happened to fall nearby from an earlier tree.
     */
    public LootTask(int radius, int attemptDeadline, Predicate<ItemStack> wanted) {
        this(radius, attemptDeadline, wanted, null);
    }

    /**
     * Creates a sweep whose write-offs survive being rebuilt.
     * <p>
     * A caller that recreates its sweeper - after every mined block, say - hands a fresh LootTask an
     * empty unreachable set, and the new one cheerfully re-chases the drop the old one had already
     * proved it could not get to. That is not a slow sweep, it is a permanent one: a run was caught
     * repeating "collecting drops" on a beach for two thousand ticks, pressing no keys, because each
     * hundred-tick sweep started over on the same wedged gravel.
     */
    public LootTask(int radius, int attemptDeadline, Predicate<ItemStack> wanted,
                    Set<Integer> sharedUnreachable) {
        this.radius = Math.max(1, radius);
        this.attemptDeadline = Math.max(1, attemptDeadline);
        this.wanted = wanted == null ? stack -> true : wanted;
        this.unreachable = sharedUnreachable == null ? new HashSet<>() : sharedUnreachable;
    }

    /** Number of item entities this sweep has seen disappear into the player's inventory. */
    public int collectedCount() {
        return collected;
    }

    /** Number of item entities this sweep had to write off as unreachable. */
    public int unreachableCount() {
        return unreachable.size();
    }

    @Override
    public TaskProgress learningProgress() {
        return new TaskProgress(collected, Math.max(1, collected), Lang.get("lune.unit.items"));
    }

    @Override
    public LearningContext learningContext(BotContext ctx) {
        String phase = "radius=" + CollectionPolicy.radiusBucket(radius)
                + ";patience=" + (attemptDeadline <= 80 ? "short" : "normal");
        return new LearningContext("skill", "item-collection",
                ctx.level.dimension().identifier().toString(), phase);
    }

    @Override
    public List<String> learningActions(BotContext ctx) {
        return CollectionPolicy.ACTIONS;
    }

    @Override
    public void onLearningAction(BotContext ctx, String action) {
        collectionStrategy = CollectionPolicy.ACTIONS.contains(action)
                ? action : CollectionPolicy.DEFAULT;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.loot.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Loot");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        collectionStrategy = CollectionPolicy.DEFAULT;
        clearTarget(ctx);
        site.leave();
        nudgeTicks = 0;
        attemptTicks = 0;
        collected = 0;
        status.clear();
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        // Nothing can be collected into a full bag; spinning here would stall the whole task.
        if (InventoryHelper.isFull(ctx.player)) {
            status.set("lune.status.loot.inventory_full_collected", collected);
            return TaskStatus.SUCCESS;
        }

        if (target != null && (!target.isAlive() || target.isRemoved())) {
            collected++;
            ctx.debug.count("drops_swept");
            // Rank whatever is left from where this one was picked up, so the sweep works outward
            // through the pile rather than re-measuring from the player every time it steps.
            site.workedAt(target.blockPosition());
            clearTarget(ctx);
        }

        if (target == null) {
            target = findNearest(ctx);
            if (target == null) {
                status.set("lune.status.loot.collected_nothing_left_within_blocks", collected, radius);
                return TaskStatus.SUCCESS;
            }
            attemptTicks = 0;
        }

        // The backstop. Every failure mode below is meant to be handled explicitly, but this is what
        // guarantees the task cannot hang on one stubborn item no matter what was missed.
        if (++attemptTicks > attemptDeadline) {
            giveUpOnTarget(ctx, Lang.get("lune.reason.took_too_long"));
            return TaskStatus.RUNNING;
        }

        if (withinPickupRange(ctx, target)) {
            // Standing on it; vanilla collects next tick and the isAlive check above notices.
            status.set("lune.status.loot.collecting");
            return TaskStatus.RUNNING;
        }

        return walkToTarget(ctx);
    }

    private TaskStatus walkToTarget(BotContext ctx) {
        BlockPos where = target.blockPosition();
        // Rebuild when the item has drifted out of the block we were routing to.
        if (approach == null || !where.equals(approachAim)) {
            if (approach != null) {
                approach.stop(ctx);
            }
            approachAim = where;
            // Walk to a drop; never dig for one.
            //
            // A dropped item is worth seconds, and the two ways of "reaching" it with a pickaxe are
            // both worse than not reaching it: tunnelling sideways abandons the work site, and
            // digging down - which is what a route to a block at or below foot level asks for -
            // drops the bot into a hole it then has to climb out of, next to an item that has
            // usually fallen further in the meantime. Getting as close as the terrain allows and
            // letting vanilla's pickup radius do the rest collects almost everything; the rest is
            // swept up on the next pass through.
            approach = new GotoTask(approachGoal(where), true, false);
            approach.start(ctx);
        }

        switch (approach.tick(ctx)) {
            case FAILED -> {
                // No walking route, but a drop three blocks away is usually not behind a wall - it
                // is in a hollow, under a lip, or in the roots the bot is standing in, where the
                // pathfinder cannot name a stand-on block even though the item is nearly in reach.
                // Vanilla's pickup box is generous, so closing the last couple of blocks by hand
                // collects it. Bounded, and only ever a nudge: no route, no digging, no chase.
                if (nudgeTowardDrop(ctx)) {
                    return TaskStatus.RUNNING;
                }
                giveUpOnTarget(ctx, Lang.get(standOnDrop ? "lune.reason.unreachable"
                    : "lune.reason.no_route_to_it"));
                return TaskStatus.RUNNING;
            }
            case SUCCESS -> {
                approach.stop(ctx);
                approach = null;
                approachAim = null;
                if (!standOnDrop) {
                    // Arrived, but still not collected. "Near" counts a block above or below as
                    // reached, and vanilla's pickup box is far flatter than it is wide, so those
                    // two can both be true at once - which is a standstill, not progress. Rebuilding
                    // the same satisfied goal would just report success again every tick until the
                    // deadline wrote off a drop that was never actually out of reach. Aim at nearby
                    // player feet positions instead: the item occupies the floor block, so asking
                    // the pathfinder to stand on that block is usually impossible.
                    standOnDrop = true;
                    status.set("lune.status.loot.stepping_onto_drop");
                    return TaskStatus.RUNNING;
                }
                if (withinPickupRange(ctx, target)) {
                    // Arrived on the block this very tick. Vanilla collects on its own next tick,
                    // and the alive-check at the top of onTick notices; writing the drop off here
                    // would throw away an item the bot is literally standing on.
                    status.set("lune.status.loot.collecting");
                    return TaskStatus.RUNNING;
                }
                // The route arrived and the item is still out of reach - the pickup box is much
                // flatter than it is wide, so "stood where I asked" and "close enough to collect"
                // routinely disagree by half a block. That is the same last-gap problem the failed
                // route has, and the same walk closes it; giving up here is how a sheep gets killed
                // and its wool left on the ground.
                if (nudgeTowardDrop(ctx)) {
                    return TaskStatus.RUNNING;
                }
                giveUpOnTarget(ctx, Lang.get("lune.reason.cannot_be_picked_up_here"));
                return TaskStatus.RUNNING;
            }
            default -> {
                status.set("lune.status.loot.walking_collected", target.getItem().getHoverName().getString(), collected);
                return TaskStatus.RUNNING;
            }
        }
    }

    /**
     * A drop can sit on an edge, behind a ledge, or in a block the player cannot occupy, so the
     * first attempt only asks to get beside it. The fallback offers the small set of player feet
     * positions whose bounding boxes could intersect the item; the exact box test still runs after
     * arrival, so an accessible but unsuitable position cannot become a satisfied-goal standstill.
     */
    /**
     * Walks the last blocks straight at a drop the router could not reach.
     *
     * @return true while the nudge is still worth continuing
     */
    private boolean nudgeTowardDrop(BotContext ctx) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        double distance = ctx.player.distanceTo(target);
        if (distance > NUDGE_RADIUS || ++nudgeTicks > MAX_NUDGE_TICKS) {
            return false;
        }
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
            approachAim = null;
        }
        ctx.look.lookAt(ctx.player, target.position());
        ctx.input.forward = true;
        ctx.debug.intent = "closing the last few blocks to a drop the router could not reach";
        status.set("lune.status.loot.edging_toward_drop_blocks", distance);
        return true;
    }

    private Goal approachGoal(BlockPos where) {
        if (!standOnDrop) {
            return new Goals.Near(where, 1);
        }

        List<Goal> pickupPositions = new ArrayList<>(27);
        for (int y = where.getY() - 1; y <= where.getY() + 1; y++) {
            for (int x = where.getX() - 1; x <= where.getX() + 1; x++) {
                for (int z = where.getZ() - 1; z <= where.getZ() + 1; z++) {
                    pickupPositions.add(new Goals.Block(new BlockPos(x, y, z)));
                }
            }
        }
        return new Goals.Any(pickupPositions);
    }

    /** The test vanilla itself uses: the item's box touching the player's box, grown for reach. */
    private static boolean withinPickupRange(BotContext ctx, ItemEntity item) {
        return ctx.player.getBoundingBox()
                .inflate(PICKUP_REACH_XZ, PICKUP_REACH_Y, PICKUP_REACH_XZ)
                .intersects(item.getBoundingBox());
    }

    private void giveUpOnTarget(BotContext ctx, String why) {
        nudgeTicks = 0;
        unreachable.add(target.getId());
        clearTarget(ctx);
        status.set("lune.status.loot.drop_trying_next_one", why);
    }

    /** Whether there is anything worth sweeping up within {@code radius}. */
    public static boolean hasDropsNearby(BotContext ctx, int radius) {
        AABB box = ctx.player.getBoundingBox().inflate(radius);
        return !ctx.level.getEntities(ctx.player, box,
                entity -> entity instanceof ItemEntity item && item.isAlive() && !item.hasPickUpDelay())
                .isEmpty();
    }

    private ItemEntity findNearest(BotContext ctx) {
        AABB box = ctx.player.getBoundingBox().inflate(radius);
        List<Entity> found = ctx.level.getEntities(ctx.player, box,
                entity -> entity instanceof ItemEntity item && item.isAlive() && !item.hasPickUpDelay()
                        && !unreachable.contains(item.getId())
                        // A drop that is wholly in a fluid is not a reason to start swimming.
                        // The shared loot sweep is used after mining, hunting, and harvesting;
                        // chasing a pushed item into water made every one of those jobs stall in
                        // the same emergency escape loop. Keep a surface item only when a dry
                        // standable pickup position is actually available beside it.
                        && (!MovementHelper.isLiquid(ctx.level, item.blockPosition())
                                || hasDryPickupPosition(ctx, item.blockPosition()))
                        && wanted.test(item.getItem()));
        BlockPos origin = CollectionPolicy.ranksFromPlayer(collectionStrategy)
                ? ctx.player.blockPosition() : site.focus(ctx);
        Vec3 from = Vec3.atCenterOf(origin);
        return found.stream()
                .min(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(from)))
                .map(ItemEntity.class::cast)
                .orElse(null);
    }

    /** True when the item can be collected from land without entering its fluid block. */
    private static boolean hasDryPickupPosition(BotContext ctx, BlockPos item) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos feet = item.offset(dx, dy, dz);
                    if (!MovementHelper.canStandAt(ctx.level, feet, false)) {
                        continue;
                    }
                    double distance = ctx.player.position().distanceToSqr(
                            feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    if (distance <= (ctx.player.blockPosition().distSqr(item) + 4.0)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void clearTarget(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        approachAim = null;
        standOnDrop = false;
        target = null;
    }

    @Override
    public void onStop(BotContext ctx) {
        clearTarget(ctx);
        ctx.input.reset();
    }
}
