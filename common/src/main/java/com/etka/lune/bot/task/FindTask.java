package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.knowledge.OreKnowledge;
import com.etka.lune.bot.memory.BlockMemory;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.util.TargetIndex;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Reports where the nearest matching blocks or living entities are without touching them - the
 * "is there any diamond/sheep around here" query, and a quick way to sanity-check a Mine or Hunt
 * selection before committing to it.
 * <p>
 * With prospecting enabled it digs safe descending stairs and reports the first match it sees.
 */
public final class FindTask implements Task {

    private static final int PROSPECT_MAX_ATTEMPTS = 8;
    private static final int PROSPECT_STAIR_STEPS = 6;
    /** How far the bot must move before a look around counts as a look from somewhere new. */
    private static final int RESCAN_DISTANCE = 3;
    /** A stair that ends this quickly was refused by the terrain rather than dug. */
    private static final int PROSPECT_REJECT_TICKS = 20;
    /**
     * Refusals are free, so they need their own ceiling. Without one, ground that keeps shifting
     * the bot far enough to forget its refused headings could rotate here indefinitely.
     */
    private static final int MAX_PROSPECT_REFUSALS = 12;

    private final Set<Block> targets;
    private final Set<EntityType<?>> entityTargets;
    private final boolean entityMode;
    private final int radius;
    private final int yMin;
    private final int yMax;
    private final boolean prospect;
    private final boolean checkAround;

    private String status = "";
    private Task prospectTask;
    private int prospectAttempts;
    private Direction prospectBaseDirection;
    /** Headings the terrain refused without any digging, and where that was measured. */
    private final java.util.EnumSet<Direction> prospectRejected =
            java.util.EnumSet.noneOf(Direction.class);
    private BlockPos prospectRejectAnchor;
    private int prospectRefusals;
    private Direction prospectDirection;
    private int prospectSegmentTicks;
    private int prospectStepsSinceStart;
    private BlockPos scanOrigin;
    private final HeadScanner headScanner = new HeadScanner();
    private final TargetIndex index = new TargetIndex();

    public FindTask(Set<Block> targets, int radius) {
        this(targets, Set.of(), radius, -64, 320, false, false, false);
    }

    public FindTask(Set<Block> targets, int radius, int yMin, int yMax, boolean prospect) {
        this(targets, Set.of(), radius, yMin, yMax, prospect, false, false);
    }

    public FindTask(Set<Block> targets, int radius, int yMin, int yMax, boolean prospect,
                    boolean checkAround) {
        this(targets, Set.of(), radius, yMin, yMax, prospect, checkAround, false);
    }

    /** Creates a Find task that searches visible living entities instead of blocks. */
    public static FindTask forEntities(Set<EntityType<?>> targets, int radius, boolean checkAround) {
        return new FindTask(Set.of(), targets, radius, -64, 320, false, checkAround, true);
    }

    private FindTask(Set<Block> targets, Set<EntityType<?>> entityTargets, int radius,
                     int yMin, int yMax, boolean prospect, boolean checkAround, boolean entityMode) {
        this.targets = Set.copyOf(targets);
        this.entityTargets = Set.copyOf(entityTargets);
        this.entityMode = entityMode;
        this.radius = radius;
        this.yMin = Math.min(yMin, yMax);
        this.yMax = Math.max(yMin, yMax);
        this.prospect = prospect;
        this.checkAround = checkAround;
    }

    @Override
    public String name() {
        return "Find";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public boolean madeProgress() {
        return status != null && status.startsWith("found") && !status.contains("0");
    }

    @Override
    public void onStart(BotContext ctx) {
        prospectAttempts = 0;
        prospectBaseDirection = ctx.player.getDirection();
        prospectRejected.clear();
        prospectRejectAnchor = null;
        prospectRefusals = 0;
        stopProspect(ctx);
        resetScan(ctx);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (entityMode) {
            return findEntity(ctx);
        }

        if (targets.isEmpty()) {
            status = "no blocks selected";
            return TaskStatus.FAILED;
        }

        // Once prospecting starts, let movement run without restarting a world scan at every
        // intermediate block. A fresh incremental scan begins when the destination is reached.
        if (prospectTask != null) {
            return continueProspecting(ctx);
        }

        ScanResult scan = scanAndReport(ctx);
        if (scan == ScanResult.FOUND) {
            return TaskStatus.SUCCESS;
        }
        if (scan == ScanResult.SEARCHING) {
            return TaskStatus.RUNNING;
        }

        if (prospect) {
            return continueProspecting(ctx);
        }

        BlockPos memory = BlockMemory.get().findNearest(ctx, ctx.player.blockPosition(),
                targets, radius);
        if (memory != null) {
            Block block = ctx.level.getBlockState(memory).getBlock();
            int distance = (int) Math.sqrt(memory.distSqr(ctx.player.blockPosition()));
            status = "remembered " + block.getName().getString() + " at "
                    + memory.getX() + ", " + memory.getY() + ", " + memory.getZ()
                    + " (" + distance + " blocks away)";
            ctx.chat(status);
            return TaskStatus.SUCCESS;
        }

        status = "nothing within " + radius + " blocks";
        ctx.chat(status);
        return TaskStatus.SUCCESS;
    }

    private TaskStatus findEntity(BotContext ctx) {
        if (entityTargets.isEmpty()) {
            status = "no mobs selected";
            return TaskStatus.FAILED;
        }

        if (checkAround && !Vision.isPanoramic()
                && (headScanner.isTurning() || headScanner.isVerticalGlance())) {
            if (!headScanner.tickTurn(ctx)) {
                status = headScanner.status().replace("target blocks", "mobs");
                return TaskStatus.RUNNING;
            }
        }

        LivingEntity found = findNearestEntity(ctx);
        if (found != null) {
            int distance = (int) Math.sqrt(found.distanceToSqr(ctx.player));
            String description = found.getType().getDescription().getString();
            ctx.chat(description + " at " + found.blockPosition().getX() + ", "
                    + found.blockPosition().getY() + ", " + found.blockPosition().getZ()
                    + " (" + distance + " blocks away)");
            status = "found 1 " + description;
            return TaskStatus.SUCCESS;
        }

        if (checkAround && !Vision.isPanoramic() && headScanner.advance()) {
            status = headScanner.status().replace("target blocks", "mobs");
            return TaskStatus.RUNNING;
        }

        status = "nothing visible within " + radius + " blocks";
        ctx.chat(status);
        return TaskStatus.SUCCESS;
    }

    private LivingEntity findNearestEntity(BotContext ctx) {
        AABB box = ctx.player.getBoundingBox().inflate(radius);
        List<Entity> found = ctx.level.getEntities(ctx.player, box,
                entity -> entity instanceof LivingEntity living
                        && living.isAlive()
                        && entityTargets.contains(entity.getType())
                        && Vision.isEntityVisible(ctx, living));
        return found.stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(ctx.player)))
                .map(LivingEntity.class::cast)
                .orElse(null);
    }

    private enum ScanResult { SEARCHING, FOUND, EXHAUSTED }

    private ScanResult scanAndReport(BotContext ctx) {
        BlockPos origin = ctx.player.blockPosition();
        // Restart the scan only on a real move, not on any change of block position. Treading water
        // or sliding down a slope shifts the block position every tick, and restarting on that
        // would reset the scan forever and leave the bot turning on the spot without ever reporting
        // what it can see. A player who shuffles a block does not start their search over either.
        if (scanOrigin == null || scanOrigin.distSqr(origin) > RESCAN_DISTANCE * RESCAN_DISTANCE) {
            scanOrigin = origin;
            headScanner.reset(ctx.player);
        }

        if (checkAround && !ctx.omniscientMining() && !Vision.isPanoramic()
                && (headScanner.isTurning() || headScanner.isVerticalGlance())) {
            if (!headScanner.tickTurn(ctx)) {
                status = headScanner.status().replace("target blocks", "blocks");
                return ScanResult.SEARCHING;
            }
        }

        int scanLimit = ctx.omniscientMining()
                ? radius
                : Math.min(radius, (int) Vision.maxRange(ctx));

        // Key the index on the settled scan origin, not the live position. A rebuild walks every
        // chunk section in the cube, so keying it on a position that drifts every tick would throw
        // the cache away every tick and undo the point of caching it at all.
        if (!index.isUsable(scanOrigin, targets, scanLimit, yMin, yMax)) {
            index.rebuild(ctx.level, scanOrigin, targets, scanLimit, yMin, yMax);
        }

        java.util.function.BiPredicate<BlockPos, BlockState> filter =
                (pos, state) -> ctx.omniscientMining() || Vision.isVisible(ctx, pos);
        BlockPos hit = index.nearest(ctx.level, scanOrigin, Set.of(), filter);
        if (hit != null) {
            int distance = (int) Math.sqrt(hit.distSqr(origin));
            Block found = ctx.level.getBlockState(hit).getBlock();
            BlockMemory.get().remember(hit, found);
            ctx.chat(found.getName().getString()
                    + " at " + hit.getX() + ", " + hit.getY() + ", " + hit.getZ()
                    + " (" + distance + " blocks away)");
            status = "found 1";
            return ScanResult.FOUND;
        }

        if (checkAround && !ctx.omniscientMining() && !Vision.isPanoramic()
                && headScanner.advance()) {
            status = headScanner.status().replace("target blocks", "blocks");
            return ScanResult.SEARCHING;
        }
        return ScanResult.EXHAUSTED;
    }

    private TaskStatus continueProspecting(BotContext ctx) {
        if (prospectTask == null) {
            if (prospectAttempts >= PROSPECT_MAX_ATTEMPTS) {
                status = "nothing within " + radius + " blocks after " + PROSPECT_MAX_ATTEMPTS
                        + " staircase segments";
                ctx.chat(status);
                return TaskStatus.SUCCESS;
            }
            forgetRejectionsAfterMoving(ctx);
            Direction direction = nextProspectDirection(ctx);
            if (direction == null || prospectRefusals >= MAX_PROSPECT_REFUSALS) {
                status = "nothing within " + radius
                        + " blocks; no diggable stair direction from here";
                ctx.chat(status);
                return TaskStatus.SUCCESS;
            }
            int targetY = OreKnowledge.prospectYFor(ctx, targets);
            int bottom = Math.max(targetY, ctx.level.getMinY() + 2);
            int available = ctx.player.blockPosition().getY() - bottom;
            prospectDirection = direction;
            prospectSegmentTicks = 0;
            prospectStepsSinceStart = 0;
            if (available <= 0) {
                // Already at the ore layer. Branch horizontally to expose blocks before giving up.
                prospectTask = new TunnelTask(direction, null, PROSPECT_STAIR_STEPS, 2);
                prospectTask.start(ctx);
                status = "branching at y " + targetY + " toward " + direction.getName();
            } else {
                prospectTask = new StaircaseProspectTask(direction,
                        Math.min(PROSPECT_STAIR_STEPS, available));
                prospectTask.start(ctx);
                status = "digging prospecting stairs " + direction.getName();
            }
        }

        prospectSegmentTicks++;
        TaskStatus result = prospectTask.tick(ctx);
        String segmentStatus = prospectTask.status();
        if (prospectTask instanceof StaircaseProspectTask stairs) {
            prospectStepsSinceStart += stairs.drainCompletedSteps();
        }
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }

        boolean refused = result == TaskStatus.FAILED
                && prospectStepsSinceStart == 0
                && prospectSegmentTicks <= PROSPECT_REJECT_TICKS;
        Direction attempted = prospectDirection;
        stopProspect(ctx);
        resetScan(ctx);

        if (refused) {
            // The heading was refused, not tried. Rotate away from it without spending one of the
            // eight segments this search is allowed to dig.
            if (attempted != null) {
                prospectRejected.add(attempted);
            }
            prospectRefusals++;
            status = "stairs " + (attempted == null ? "here" : attempted.getName())
                    + " refused (" + segmentStatus + "); trying another heading";
            return TaskStatus.RUNNING;
        }

        prospectAttempts++;
        return TaskStatus.RUNNING;
    }

    /** The next heading to dig, or null once this spot has refused all four. */
    private Direction nextProspectDirection(BotContext ctx) {
        Direction facing = prospectBaseDirection == null
                ? ctx.player.getDirection() : prospectBaseDirection;
        Direction[] rotation = {
                facing, facing.getClockWise(), facing.getOpposite(), facing.getCounterClockWise()
        };
        for (int offset = 0; offset < rotation.length; offset++) {
            Direction candidate = rotation[Math.floorMod(prospectAttempts + offset, rotation.length)];
            if (!prospectRejected.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A refused heading describes the ground underfoot, so moving off it clears the refusals. */
    private void forgetRejectionsAfterMoving(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        if (prospectRejectAnchor == null) {
            prospectRejectAnchor = feet.immutable();
            return;
        }
        if (prospectRejectAnchor.distSqr(feet) >= (double) RESCAN_DISTANCE * RESCAN_DISTANCE) {
            prospectRejected.clear();
            prospectRejectAnchor = feet.immutable();
        }
    }

    private void resetScan(BotContext ctx) {
        scanOrigin = null;
        index.invalidate();
        headScanner.reset(ctx.player);
    }

    private void stopProspect(BotContext ctx) {
        if (prospectTask != null) {
            prospectTask.stop(ctx);
            prospectTask = null;
        }
        prospectDirection = null;
        prospectSegmentTicks = 0;
        prospectStepsSinceStart = 0;
    }

    @Override
    public void onPause(BotContext ctx) {
        stopProspect(ctx);
    }

    @Override
    public void onStop(BotContext ctx) {
        stopProspect(ctx);
        ctx.input.reset();
    }
}
