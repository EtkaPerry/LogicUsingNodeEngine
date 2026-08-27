package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.memory.BlockMemory;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockBreaker;
import com.etka.lune.bot.util.CropHelper;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.util.TargetIndex;
import com.etka.lune.bot.util.Vision;
import com.etka.lune.bot.util.WorkSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds mature crops within range, walks close to them, breaks them, and optionally collects and
 * replants each crop before moving on.
 * <p>
 * Search is physical: the bot glances ahead first, widens to a sweep when that fails, and may walk
 * to another nearby farm to check it. A selected crop is approached closely enough that the bot
 * works beside the seed rather than mining from the far edge of interaction reach. The work-site
 * anchor keeps the next choice in the same farm instead of ranking again from the moving player.
 */
public final class HarvestTask implements Task {

    /** Vanilla interaction reach is 4.5; harvesting deliberately settles much closer than that. */
    /** Close enough to work locally, while allowing a second-block position around a water row. */
    private static final double STAND_REACH = 2.5;
    /** A crop already in the player's immediate view should win before the site anchor is consulted. */
    private static final int IMMEDIATE_CROP_RADIUS = 3;
    private static final int RESCAN_DISTANCE = 3;
    private static final int COLLECT_RADIUS = 4;
    private static final int COLLECT_DEADLINE = 80;
    private static final int REPLANT_DEADLINE = 40;
    private static final int FARM_SCAN_STEP = 8;

    private final Set<Block> targets;
    private final int radius;
    private final int limit;
    private final boolean collectDrops;
    private final boolean replant;

    private final BlockBreaker breaker = new BlockBreaker();
    private final Set<Long> unreachable = new HashSet<>();
    private final Set<Long> unsuitableApproaches = new HashSet<>();
    private final TargetIndex index = new TargetIndex();
    private final WorkSite site = new WorkSite();
    private final HeadScanner headScanner = new HeadScanner(HeadScanner.Style.GLANCE);

    private BlockPos target;
    private Block targetCrop;
    private BlockPos scanOrigin;
    private GotoTask approach;
    private ExploreTask farmScout;
    private LootTask collector;
    private BlockPos postBreakCrop;
    private Block postBreakType;
    private boolean breaking;
    private boolean scanDone;
    private boolean scoutingDone;
    private int replantTicks;
    private int harvested;
    private String collectionStrategy = CollectionPolicy.DEFAULT;
    private String status = "";

    public HarvestTask(Set<Block> targets, int radius, int limit) {
        this(targets, radius, limit, true, true);
    }

    /** Creates a harvest run with independent collection and replanting choices. */
    public HarvestTask(Set<Block> targets, int radius, int limit,
                       boolean collectDrops, boolean replant) {
        this.targets = Set.copyOf(targets);
        this.radius = Math.max(1, radius);
        this.limit = Math.max(0, limit);
        this.collectDrops = collectDrops;
        this.replant = replant;
    }

    @Override
    public String name() {
        return "Harvest";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return limit <= 0 ? null : new TaskProgress(harvested, limit, "crops");
    }

    @Override
    public TaskProgress learningProgress() {
        return new TaskProgress(harvested, limit <= 0 ? Math.max(1, harvested) : limit, "crops");
    }

    @Override
    public LearningContext learningContext(BotContext ctx) {
        String crops = targets.stream()
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .sorted()
                .limit(4)
                .collect(Collectors.joining(","));
        String phase = "crops=" + (crops.isBlank() ? "none" : crops)
                + ";radius=" + CollectionPolicy.radiusBucket(radius)
                + ";limit=" + (limit <= 0 ? "all" : limit)
                + ";drops=" + collectDrops + ";replant=" + replant;
        return new LearningContext("skill", "crop-harvesting",
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
    public void onStart(BotContext ctx) {
        collectionStrategy = CollectionPolicy.DEFAULT;
        harvested = 0;
        unreachable.clear();
        unsuitableApproaches.clear();
        unreachable.addAll(BlockMemory.get().getUnreachable());
        site.leave();
        scanOrigin = null;
        target = null;
        targetCrop = null;
        postBreakCrop = null;
        postBreakType = null;
        breaking = false;
        scanDone = false;
        scoutingDone = false;
        replantTicks = 0;
        index.invalidate();
        headScanner.reset(ctx.player);
        stopFarmScout(ctx);
        stopCollector(ctx);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (targets.isEmpty()) {
            status = "no crops selected";
            return TaskStatus.FAILED;
        }

        // A crop that just disappeared owns the next few ticks. This prevents the limit check
        // from finishing the task before its requested collection/replant work has happened.
        if (postBreakCrop != null && !finishPostBreak(ctx)) {
            return TaskStatus.RUNNING;
        }

        if (target != null && isGone(ctx, target)) {
            BlockPos finished = target.immutable();
            Block finishedType = targetCrop;
            boolean completedBreak = breaking;
            stopBreaking(ctx);
            clearTarget(ctx);
            if (completedBreak && finishedType != null) {
                harvested++;
                site.workedAt(finished);
                beginPostBreak(ctx, finished, finishedType);
                return TaskStatus.RUNNING;
            }
        }

        if (limit > 0 && harvested >= limit) {
            stopBreaking(ctx);
            status = "harvested " + harvested;
            return TaskStatus.SUCCESS;
        }

        if (farmScout != null) {
            TaskStatus result = farmScout.tick(ctx);
            status = "checking another farm - " + farmScout.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }

            BlockPos found = farmScout.getFound();
            stopFarmScout(ctx);
            if (result == TaskStatus.SUCCESS && found != null
                    && targets.contains(ctx.level.getBlockState(found).getBlock())
                    && CropHelper.isMature(ctx.level.getBlockState(found))
                    && Vision.isVisible(ctx, found)
                    && !unreachable.contains(found.asLong())) {
                selectTarget(ctx, found);
            } else {
                scoutingDone = true;
            }
        }

        if (target == null) {
            BlockPos found = findMatureCrop(ctx);
            if (found != null) {
                selectTarget(ctx, found);
            } else if (!scanDone) {
                return TaskStatus.RUNNING;
            } else if (!scoutingDone && canScoutForAnotherFarm()) {
                startFarmScout(ctx);
                return TaskStatus.RUNNING;
            } else {
                status = "harvested " + harvested + ", nothing visible left to harvest";
                return TaskStatus.SUCCESS;
            }
        }

        ctx.debug.target("harvest " + ctx.level.getBlockState(target).getBlock().getName().getString(),
                target, ctx.omniscientHarvesting() ? "omniscient mode" : Vision.inspect(ctx, target).verdict());

        // Being in interaction reach is not the same as being beside the crop. Settling close makes
        // the break, drop pickup, and replant all happen in the same small farm area.
        Vec3 aim = Vision.blockAimPoint(ctx, target);
        if (ctx.player.getEyePosition().distanceToSqr(aim) <= STAND_REACH * STAND_REACH) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
            // Finish walking before using the crosshair. Swinging while the body is still in the
            // air makes the eye ray bob past short crops and can trample the soil on landing.
            if (!ctx.player.onGround()) {
                ctx.input.reset();
                status = "settling beside crop";
                return TaskStatus.RUNNING;
            }
            // A candidate can be geometrically close while another row hides the crop from the
            // player's exact position. Remember this side and walk around instead of breaking the
            // immature crop that happened to intercept the ray.
            if (!Vision.isReachable(ctx, target)) {
                unsuitableApproaches.add(MovementHelper.feetPosition(ctx.player).asLong());
                status = "moving to a clear side of crop";
                return walkToTarget(ctx);
            }
            return breakTarget(ctx);
        }

        return walkToTarget(ctx);
    }

    /**
     * Looks from the current position, then physically walks a bounded distance to find another
     * visible farm. This keeps a second field reachable without treating an indexed hidden crop as
     * permission to break through the world toward it.
     */
    private boolean canScoutForAnotherFarm() {
        return radius >= FARM_SCAN_STEP;
    }

    private void startFarmScout(BotContext ctx) {
        scoutingDone = true;
        int stops = Math.max(1, Math.min(8, radius / FARM_SCAN_STEP));
        farmScout = new ExploreTask(targets, radius, stops, FARM_SCAN_STEP, true,
                HeadScanner.Style.GLANCE, false, CropHelper::isMature);
        farmScout.start(ctx);
        status = "walking between farms to check for visible crops";
        ctx.debug.nextDecision = "glance ahead, sweep, then walk to another nearby farm";
    }

    private BlockPos findMatureCrop(BotContext ctx) {
        BlockPos live = ctx.player.blockPosition();
        if (scanOrigin == null || scanOrigin.distSqr(live) > RESCAN_DISTANCE * RESCAN_DISTANCE) {
            scanOrigin = live;
            index.invalidate();
            headScanner.reset(ctx.player);
            scanDone = false;
            // Moving to a new farm starts a fresh bounded physical search.
            scoutingDone = false;
        }

        int scanLimit = ctx.omniscientHarvesting()
                ? radius
                : Math.min(radius, (int) Vision.maxRange(ctx));
        if (!index.isUsable(scanOrigin, targets, scanLimit, scanOrigin.getY() - 2,
                scanOrigin.getY() + 2)) {
            index.rebuild(ctx.level, scanOrigin, targets, scanLimit,
                    scanOrigin.getY() - 2, scanOrigin.getY() + 2);
        }

        boolean settled = true;
        if (!ctx.omniscientHarvesting()
                && (headScanner.isTurning() || headScanner.isVerticalGlance())) {
            // Visibility is tested below on every tick, including while turning. A player notices
            // a crop as it swings into view; settled is only used to decide when to turn farther.
            settled = headScanner.tickTurn(ctx);
            status = headScanner.status();
            ctx.debug.searchHeading = headScanner.status();
        }

        java.util.function.BiPredicate<BlockPos, BlockState> filter =
                (pos, state) -> CropHelper.isMature(state)
                        && (ctx.omniscientHarvesting() || Vision.isVisible(ctx, pos));

        // The work-site anchor keeps a farm stable while the bot moves, but it must not make the
        // bot walk around a crop that is already directly in front of it. Give the current view a
        // small, local priority first; once that pocket is clear, continue ranking from the stable
        // work site as before. This is deliberately bounded so a moving player cannot make the
        // whole farm ping-pong between two nearest blocks.
        if (CollectionPolicy.checksImmediatePocket(collectionStrategy)) {
            BlockPos immediate = index.nearest(ctx.level, live, unreachable, filter);
            if (immediate != null
                    && isWithinHorizontalRadius(live, immediate, IMMEDIATE_CROP_RADIUS)) {
                BlockMemory.get().remember(immediate, ctx.level.getBlockState(immediate).getBlock());
                return immediate;
            }
        }

        BlockPos rankingOrigin = CollectionPolicy.ranksFromPlayer(collectionStrategy)
                ? live : site.focus(ctx, scanOrigin);
        BlockPos found = index.nearest(ctx.level, rankingOrigin, unreachable, filter);
        if (found != null) {
            BlockMemory.get().remember(found, ctx.level.getBlockState(found).getBlock());
            return found;
        }

        // A previously seen crop may have gone out of the current index as the bot crossed a farm;
        // it is still acceptable only when it is visible again from this position.
        BlockPos memory = BlockMemory.get().findNearest(ctx, rankingOrigin, targets, radius,
                (pos, state) -> CropHelper.isMature(state)
                        && !unreachable.contains(pos.asLong())
                        && (ctx.omniscientHarvesting() || Vision.isVisible(ctx, pos)));
        if (memory != null) {
            return memory;
        }

        if (ctx.omniscientHarvesting()) {
            scanDone = true;
            return null;
        }
        if (!settled) {
            scanDone = false;
            return null;
        }
        if (headScanner.advance()) {
            scanDone = false;
            status = "looking " + headScanner.status();
            return null;
        }
        if (headScanner.escalate(ctx.player)) {
            scanDone = false;
            status = "widening the farm scan";
            return null;
        }
        scanDone = true;
        return null;
    }

    private void selectTarget(BotContext ctx, BlockPos pos) {
        target = pos.immutable();
        targetCrop = ctx.level.getBlockState(target).getBlock();
        unsuitableApproaches.clear();
        scanDone = true;
        breaking = false;
        // A crop may be found during the scanner's upward glance. Stop that scan immediately so
        // the navigation task, and then BlockBreaker, own the head from this point onward.
        headScanner.reset(ctx.player);
        // Do not leave the camera at the scanner's last vertical angle while the first route tick
        // is being planned. The crop outline is at the height the breaker will actually use, so this
        // also gives the route a natural, useful starting look instead of staring into the sky.
        ctx.look.lookAt(ctx.player, Vision.blockAimPoint(ctx, target));
    }

    private static boolean isWithinHorizontalRadius(BlockPos origin, BlockPos pos, int radius) {
        long dx = (long) pos.getX() - origin.getX();
        long dz = (long) pos.getZ() - origin.getZ();
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private boolean isGone(BotContext ctx, BlockPos pos) {
        return !targets.contains(ctx.level.getBlockState(pos).getBlock());
    }

    private TaskStatus breakTarget(BotContext ctx) {
        String name = ctx.level.getBlockState(target).getBlock().getName().getString();
        BlockBreaker.Progress progress = breaker.tick(ctx, target);
        if (progress == BlockBreaker.Progress.NO_TOOL) {
            status = "can't harvest " + name;
            return TaskStatus.FAILED;
        }
        if (progress == BlockBreaker.Progress.HAZARD) {
            status = breaker.getFailureReason();
            unreachable.add(target.asLong());
            BlockMemory.get().markUnreachable(target);
            clearTarget(ctx);
            return TaskStatus.RUNNING;
        }
        breaking = true;
        status = "harvesting " + name + " (" + harvested + " done)";
        return TaskStatus.RUNNING;
    }

    private TaskStatus walkToTarget(BotContext ctx) {
        stopBreaking(ctx);
        if (approach == null) {
            // No tunnelling is needed for a visible farm crop. Route around rows and arrive beside
            // the seed so the subsequent break/loot/replant sequence stays local.
            Goal safePosition = safeHarvestPosition(ctx);
            if (safePosition == null) {
                unreachable.add(target.asLong());
                BlockMemory.get().markUnreachable(target);
                clearTarget(ctx);
                status = "no safe footing beside crop, skipping it";
                return TaskStatus.RUNNING;
            }
            // A crop does not justify building a bridge or pillar. If this floating farm has no
            // walkable connection, fail the approach safely instead of climbing into the void.
            approach = new GotoTask(safePosition, false, false, false, false, false);
            approach.start(ctx);
        }
        TaskStatus result = approach.tick(ctx);
        if (result == TaskStatus.SUCCESS) {
            approach.stop(ctx);
            approach = null;
            // Reaching one member of Goals.Any is only a navigation result. If the actual player
            // position is still too far away or the eye ray is blocked, exclude this footing and
            // choose another side instead of rebuilding an already-satisfied goal forever.
            if (!ctx.player.onGround()) {
                ctx.input.reset();
                status = "settling beside crop";
                return TaskStatus.RUNNING;
            }
            Vec3 aim = Vision.blockAimPoint(ctx, target);
            boolean closeEnough = ctx.player.getEyePosition().distanceToSqr(aim)
                    <= STAND_REACH * STAND_REACH;
            if (!closeEnough || !Vision.isReachable(ctx, target)) {
                unsuitableApproaches.add(MovementHelper.feetPosition(ctx.player).asLong());
                status = "route reached, trying a clear side of crop";
            }
            return TaskStatus.RUNNING;
        }
        if (result == TaskStatus.FAILED) {
            unreachable.add(target.asLong());
            BlockMemory.get().markUnreachable(target);
            clearTarget(ctx);
            status = "crop unreachable, checking the next farm";
            return TaskStatus.RUNNING;
        }
        status = "walking close to crop - " + approach.status();
        return TaskStatus.RUNNING;
    }

    /** Finds same-level feet positions that are both reachable from the crop and supported by a floor. */
    private Goal safeHarvestPosition(BotContext ctx) {
        List<Goal> positions = new ArrayList<>();
        Vec3 aim = Vision.blockAimPoint(ctx, target);
        int y = target.getY();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos feet = new BlockPos(target.getX() + dx, y, target.getZ() + dz);
                if (unsuitableApproaches.contains(feet.asLong())
                        || !MovementHelper.canStandAt(ctx.level, feet, false)) {
                    continue;
                }
                Vec3 candidateEye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62,
                        feet.getZ() + 0.5);
                if (!Vision.isReachableFrom(ctx, target, candidateEye)) {
                    continue;
                }
                // The route goal is exact, but this cheap geometric check keeps it from
                // selecting a supported block that would still leave the crop out of reach.
                double dxEye = candidateEye.x - aim.x;
                double dyEye = candidateEye.y - aim.y;
                double dzEye = candidateEye.z - aim.z;
                if (dxEye * dxEye + dyEye * dyEye + dzEye * dzEye <= STAND_REACH * STAND_REACH) {
                    positions.add(new Goals.Block(feet));
                }
            }
        }
        return positions.isEmpty() ? null : new Goals.Any(positions);
    }

    /** Ticks the post-break collection and replant sequence to completion. */
    private boolean finishPostBreak(BotContext ctx) {
        if (collectDrops && collector != null) {
            TaskStatus result = collector.tick(ctx);
            if (result == TaskStatus.RUNNING) {
                status = "collecting harvest drops";
                return false;
            }
            stopCollector(ctx);
        }

        if (replant && postBreakCrop != null) {
            if (!replantCrop(ctx)) {
                return false;
            }
        }

        postBreakCrop = null;
        postBreakType = null;
        replantTicks = 0;
        headScanner.reset(ctx.player);
        scanDone = false;
        scoutingDone = false;
        status = replant ? "replanted crop; checking this farm" : "crop finished; checking this farm";
        return true;
    }

    private boolean replantCrop(BotContext ctx) {
        if (!ctx.level.getBlockState(postBreakCrop).isAir()) {
            return true;
        }
        if (postBreakType == null || !CropHelper.hasSeed(ctx, postBreakType)) {
            status = "no seed for the harvested crop; moving on";
            return true;
        }
        if (++replantTicks > REPLANT_DEADLINE) {
            status = "replanting took too long; moving on";
            return true;
        }
        if (CropHelper.plant(ctx, postBreakCrop, postBreakType)) {
            status = "replanted crop; checking this farm";
            return true;
        }
        status = "replanting crop";
        return false;
    }

    private void beginPostBreak(BotContext ctx, BlockPos crop, Block cropType) {
        postBreakCrop = crop.immutable();
        postBreakType = cropType;
        replantTicks = 0;
        if (collectDrops) {
            collector = new LootTask(COLLECT_RADIUS, COLLECT_DEADLINE);
            collector.start(ctx);
        }
    }

    private void clearTarget(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        target = null;
        targetCrop = null;
        breaking = false;
    }

    private void stopBreaking(BotContext ctx) {
        breaker.stop(ctx);
        breaking = false;
    }

    private void stopCollector(BotContext ctx) {
        if (collector != null) {
            collector.stop(ctx);
            collector = null;
        }
    }

    private void stopFarmScout(BotContext ctx) {
        if (farmScout != null) {
            farmScout.stop(ctx);
            farmScout = null;
        }
    }

    @Override
    public void onPause(BotContext ctx) {
        stopBreaking(ctx);
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        if (collector != null) {
            collector.onPause(ctx);
        }
        if (farmScout != null) {
            farmScout.onPause(ctx);
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        stopBreaking(ctx);
        stopCollector(ctx);
        stopFarmScout(ctx);
        clearTarget(ctx);
        postBreakCrop = null;
        postBreakType = null;
        ctx.input.reset();
    }
}
