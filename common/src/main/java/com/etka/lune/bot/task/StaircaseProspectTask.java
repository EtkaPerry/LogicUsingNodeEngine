package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.knowledge.OreKnowledge;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockBreaker;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.ExposedVein;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/** Digs a two-block-wide movement envelope as a descending, human-walkable staircase. */
public final class StaircaseProspectTask implements Task {

    /** How many air blocks in the 3×3×3 around the next step count as "we opened a cave". */
    private static final int CAVE_AIR_THRESHOLD = 12;
    /** How close a hostile mob can be before we seal the stair and back out. */
    private static final int HOSTILE_SCAN_RADIUS = 8;
    /** Ticks to keep trying to place the seal block before giving up and just leaving. */
    private static final int MAX_SEAL_TICKS = 20;
    /** A stair block must either break or be abandoned; never swing at one rejected block forever. */
    private static final int MAX_BLOCK_WORK_TICKS = 60;

    private final Direction direction;
    private final int steps;
    private final Set<Block> countedTargets;
    private final Set<Long> protectedRoute;
    private final BlockBreaker breaker = new BlockBreaker();

    private BlockPos origin;
    private int completed;
    private BlockPos requestedBreak;
    private boolean requestedBreakCounts;
    private int matchingBlocksBroken;
    private int newlyCompletedSteps;
    private int walkStuckTicks;
    private BlockPos lastWalkFeet;
    /** Distance to the intended next step at the last walking sample. */
    private int lastWalkDistance = Integer.MAX_VALUE;
    private BlockPos workTarget;
    private int workTicks;
    private GotoTask stagingTask;
    private BlockPos stagingPosition;

    private boolean caveSeen;
    private boolean retreating;
    private GotoTask retreat;
    private int sealTicks;
    private boolean caveSealed;
    private BlockPos sealPos;

    private final StatusText status = new StatusText();

    public StaircaseProspectTask(Direction direction, int steps) {
        this(direction, steps, Set.of(), new HashSet<>());
    }

    public StaircaseProspectTask(Direction direction, int steps, Set<Block> countedTargets) {
        this(direction, steps, countedTargets, new HashSet<>());
    }

    public StaircaseProspectTask(Direction direction, int steps, Set<Block> countedTargets,
                                 Set<Long> protectedRoute) {
        this.direction = direction.getAxis().isHorizontal() ? direction : Direction.NORTH;
        this.steps = Math.max(1, steps);
        this.countedTargets = Set.copyOf(countedTargets);
        this.protectedRoute = protectedRoute;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.staircase_prospect.dig_prospecting_stairs");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Dig prospecting stairs");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        if (origin != null || stagingTask != null) {
            return;
        }
        BlockPos current = ctx.player.blockPosition();
        if (MovementHelper.isStableMiningStart(ctx.level, current)) {
            beginAt(current);
            return;
        }
        stagingPosition = MovementHelper.findStableMiningStart(ctx.level, current,
                24, -32, 2, Set.of(), false);
        if (stagingPosition == null) {
            status.set("lune.status.staircase_prospect.no_stable_ground");
            return;
        }
        stagingTask = new GotoTask(new Goals.Block(stagingPosition), true, true);
        stagingTask.start(ctx);
        status.set("lune.status.staircase_prospect.moving_stable_ground_before_prospecting");
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (stagingTask != null) {
            TaskStatus result = stagingTask.tick(ctx);
            status.set("lune.status.staircase_prospect.moving_stable_ground_before_prospecting_2", stagingTask.statusLine());
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            stagingTask.stop(ctx);
            stagingTask = null;
            if (result == TaskStatus.FAILED) {
                status.set("lune.status.staircase_prospect.could_not_reach_stable_ground");
                return TaskStatus.FAILED;
            }
            beginAt(ctx.player.blockPosition());
            status.set("lune.status.staircase_prospect.starting_prospecting_stair_from_stable");
        }
        if (origin == null) {
            if (status.isBlank()) {
                status.set("lune.status.staircase_prospect.no_stable_ground");
            }
            return TaskStatus.FAILED;
        }
        if (retreating) {
            return tickRetreat(ctx);
        }

        creditCompletedBreak(ctx);

        // After the player has stepped down, mine the side walls of the step it is standing in. This
        // is done *after* the step because the view from inside the stair is the only one that cleanly
        // exposes both side walls; the angle during the approach makes the side wall a blind spot.
        if (completed > 0) {
            BlockPos currentFeet = origin.relative(direction, completed).below(completed);
            if (ctx.player.blockPosition().equals(currentFeet)) {
                TaskStatus exposed = breakExposedTargets(ctx, currentFeet, completed);
                if (exposed != null) {
                    return exposed;
                }
            }
        }

        if (completed >= steps) {
            status.set("lune.status.staircase_prospect.descended_steps", completed);
            return TaskStatus.SUCCESS;
        }

        BlockPos nextFeet = origin.relative(direction, completed + 1).below(completed + 1);
        // This is the floor the new stair must stand on. Protect it before aiming because the ray
        // toward a lower block can otherwise resolve to the lip of the stair the bot is using.
        protectedRoute.add(nextFeet.below().asLong());
        // A descending transition needs the destination's feet/head plus the forward block at the
        // current head height. Clear from the top down: the ray from the player's eye enters the
        // column at the ceiling and works downward, so targeting the lowest block first makes the
        // breaker hit the wrong (higher) block and the player looks at one block while breaking another.
        for (int height = 2; height >= 0; height--) {
            BlockPos obstruction = nextFeet.above(height);
            if (MovementHelper.isPassable(ctx.level, obstruction)) {
                continue;
            }
            if (!MovementHelper.isBreakable(ctx.level, obstruction)) {
                status.set("lune.status.staircase_prospect.stairs_blocked_by", ctx.level.getBlockState(obstruction).getBlock().getName().getString());
                return TaskStatus.FAILED;
            }
            if (protectedRoute.contains(obstruction.asLong())) {
                status.set("lune.status.staircase_prospect.refusing_destroy_existing_staircase");
                return TaskStatus.FAILED;
            }
            if (MovementHelper.fallingBlocksAbove(ctx.level, obstruction) >= 1) {
                status.set("lune.status.staircase_prospect.stairs_would_open_falling", ctx.level.getBlockState(obstruction.above()).getBlock().getName().getString());
                return TaskStatus.FAILED;
            }
            if (breaker.isOutOfReach(ctx, obstruction)) {
                // Something moved the bot away from the stairwell - the drop sweep walking off after
                // a cobblestone is the usual one. A break has no timeout of its own, so swinging at
                // a block that is now several blocks below simply never lands and never stops. Say
                // so instead: the caller is standing in a half-dug shaft and can do something
                // useful with that, which is more than this loop will ever manage.
                status.set("lune.status.staircase_prospect.pulled_away_from_stairwell");
                return TaskStatus.FAILED;
            }
            requestedBreak = obstruction;
            requestedBreakCounts = countedTargets.contains(
                    ctx.level.getBlockState(obstruction).getBlock());
            BlockBreaker.Progress progress = breaker.tick(ctx, obstruction, false, protectedRoute);
            if (progress == BlockBreaker.Progress.NO_TOOL) {
                status.set("lune.status.staircase_prospect.staircase_block");
                return TaskStatus.FAILED;
            }
            if (progress == BlockBreaker.Progress.HAZARD) {
                status.set(breaker.getFailureReason());
                return TaskStatus.FAILED;
            }
            if (trackBlockWork(obstruction)) {
                breaker.stop(ctx);
                status.set("lune.status.staircase_prospect.stair_block_stalled_abandoning_step");
                return TaskStatus.FAILED;
            }
            status.set("lune.status.staircase_prospect.digging_stair", (completed + 1), steps);
            return TaskStatus.RUNNING;
        }
        breaker.stop(ctx);

        // Never deliberately open a drop beneath the staircase. A solid floor makes every segment
        // reversible on foot and avoids falling into caves, water or lava that was not visible.
        if (!MovementHelper.isSolidFloor(ctx.level, nextFeet.below())) {
            status.set("lune.status.staircase_prospect.stairs_reached_unsupported_opening");
            return TaskStatus.FAILED;
        }

        TaskStatus caveDecision = checkCave(ctx, nextFeet);
        if (caveDecision != null) {
            return caveDecision;
        }

        if (ctx.player.blockPosition().equals(nextFeet)) {
            completed++;
            newlyCompletedSteps++;
            walkStuckTicks = 0;
            lastWalkFeet = null;
            lastWalkDistance = Integer.MAX_VALUE;
            return TaskStatus.RUNNING;
        }

        // The path is only one block forward and one block down, and it was just cleared. A direct
        // forward input is more reliable than a full A* search in a tight stairwell, where the
        // search budget can fail to see the one-block drop and return an empty path.
        BlockPos now = ctx.player.blockPosition();
        int distance = manhattan(now, nextFeet);
        if (lastWalkFeet == null) {
            walkStuckTicks = 0;
        } else if (distance < lastWalkDistance) {
            // A partial step still counts as progress even when the player has not crossed into the
            // next block yet. This is the useful signal in a one-block stair, where block position
            // can wobble between the same few cells while the input is pressed against a lip.
            walkStuckTicks = 0;
        } else {
            walkStuckTicks++;
        }
        lastWalkFeet = now;
        lastWalkDistance = distance;
        if (walkStuckTicks > 40) {
            status.set("lune.status.staircase_prospect.could_not_enter_new_stair");
            return TaskStatus.FAILED;
        }
        ctx.look.lookAt(ctx.player, Vec3.atCenterOf(nextFeet));
        ctx.input.forward = true;
        status.set("lune.status.staircase_prospect.walking_down_stair", (completed + 1), steps);
        return TaskStatus.RUNNING;
    }

    private void beginAt(BlockPos feet) {
        origin = feet.immutable();
        protectedRoute.add(origin.below().asLong());
    }

    private static int manhattan(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
                + Math.abs(first.getY() - second.getY())
                + Math.abs(first.getZ() - second.getZ());
    }

    /** Returns stair steps entered since the last call, consuming the count. */
    public int drainCompletedSteps() {
        int result = newlyCompletedSteps;
        newlyCompletedSteps = 0;
        return result;
    }

    /** Returns newly broken blocks that also satisfy the parent Mine task, consuming the count. */
    public int drainMatchingBlocksBroken() {
        int result = matchingBlocksBroken;
        matchingBlocksBroken = 0;
        return result;
    }

    /**
     * Breaks the first reachable target block exposed around the step (sides, front, back and ceiling).
     * Which one that is, is {@link ExposedVein}'s question - the corridor a Stripmine cuts asks it
     * the same way. What is left here is what only a staircase can answer: a block that will not
     * break, or a hazard behind one, is fatal to a stair the bot has to walk back up.
     */
    private TaskStatus breakExposedTargets(BotContext ctx, BlockPos stepFeet, int stepIndex) {
        if (countedTargets.isEmpty()) {
            return null;
        }

        BlockPos candidate = ExposedVein.next(ctx, stepFeet, countedTargets, protectedRoute);
        if (candidate == null) {
            return null;
        }

        requestedBreak = candidate;
        requestedBreakCounts = true;
        BlockBreaker.Progress progress = breaker.tick(ctx, candidate, false, protectedRoute);
        if (progress == BlockBreaker.Progress.NO_TOOL) {
            status.set("lune.status.staircase_prospect.stair_target");
            return TaskStatus.FAILED;
        }
        if (progress == BlockBreaker.Progress.HAZARD) {
            status.set(breaker.getFailureReason());
            return TaskStatus.FAILED;
        }
        if (trackBlockWork(candidate)) {
            breaker.stop(ctx);
            status.set("lune.status.staircase_prospect.stair_target_stalled_abandoning_step");
            return TaskStatus.FAILED;
        }
        status.set("lune.status.staircase_prospect.mining_stair_target", stepIndex, steps);
        return TaskStatus.RUNNING;
    }

    private void creditCompletedBreak(BotContext ctx) {
        if (requestedBreak == null) {
            return;
        }
        if (!countedTargets.contains(ctx.level.getBlockState(requestedBreak).getBlock())) {
            if (requestedBreakCounts) {
                matchingBlocksBroken++;
            }
            requestedBreak = null;
            requestedBreakCounts = false;
            workTarget = null;
            workTicks = 0;
        }
    }

    /**
     * Counts work on one physical block. Turning and rejected break inputs are deliberately
     * included: neither changes the world, and both must eventually hand control back to the
     * parent so it can choose a different stair or mining pocket.
     */
    private boolean trackBlockWork(BlockPos pos) {
        if (!pos.equals(workTarget)) {
            workTarget = pos.immutable();
            workTicks = 0;
        }
        workTicks++;
        return workTicks >= MAX_BLOCK_WORK_TICKS;
    }

    /**
     * Detects when the next step opens into a large open space. If the cave looks useful and safe the
     * stair stops so the parent can scan it; if it is dangerous the bot seals the entrance and backs
     * out along the stair it came down.
     */
    private TaskStatus checkCave(BotContext ctx, BlockPos nextFeet) {
        if (caveSeen) {
            return null;
        }
        // Water is passable to the pathfinder, but a dark flooded pocket is not an ordinary cave:
        // entering it and hoping the surface is nearby is how a short stone detour turns into a
        // drowning. Stop before the player crosses the opening and retry from dry ground instead.
        if (waterAhead(ctx, nextFeet)) {
            caveSeen = true;
            status.set("lune.status.staircase_prospect.water_ahead_sealing_retreating");
            retreating = true;
            sealPos = nextFeet;
            return tickRetreat(ctx);
        }
        int open = countOpenNeighbors(ctx, nextFeet);
        if (open < CAVE_AIR_THRESHOLD) {
            return null;
        }
        caveSeen = true;

        if (hostileMobsNearby(ctx, nextFeet)) {
            status.set("lune.status.staircase_prospect.cave_with_hostiles_sealing_retreating");
            retreating = true;
            sealPos = nextFeet;
            return tickRetreat(ctx);
        }

        // No hostiles. Stop here so the parent task can scan the exposed cave and decide whether to
        // enter. If the wanted ore is visible, the next Mine/Find tick will walk in; if not, it will
        // try another direction instead of blindly descending into the cave.
        status.set("lune.status.staircase_prospect.opened_cave_scanning_before_entering");
        return TaskStatus.SUCCESS;
    }

    private boolean waterAhead(BotContext ctx, BlockPos centre) {
        // A river beside the stair is not a flooded stair. The old 3x4 footprint treated any
        // water one block to the side as an opening and made every direction from a shoreline
        // fail before it reached stone. Only water in the destination column, or immediately in
        // the forward face that the next step would enter, can flood this staircase. A side wall
        // is already guarded by BlockBreaker.wouldOpenWater when it is actually mined through.
        for (int dy = -1; dy <= 2; dy++) {
            if (MovementHelper.isWater(ctx.level, centre.above(dy))) {
                return true;
            }
        }
        BlockPos forward = centre.relative(direction);
        for (int dy = -1; dy <= 1; dy++) {
            if (MovementHelper.isWater(ctx.level, forward.above(dy))) {
                return true;
            }
        }
        return false;
    }

    private int countOpenNeighbors(BotContext ctx, BlockPos centre) {
        int open = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = centre.offset(dx, dy, dz);
                    if (protectedRoute.contains(pos.asLong())) {
                        continue;
                    }
                    if (MovementHelper.isPassable(ctx.level, pos)) {
                        open++;
                    }
                }
            }
        }
        return open;
    }

    private boolean hostileMobsNearby(BotContext ctx, BlockPos pos) {
        AABB box = new AABB(pos).inflate(HOSTILE_SCAN_RADIUS);
        for (Entity entity : ctx.level.getEntities(ctx.player, box, e -> e instanceof LivingEntity living && living.isAlive() && e instanceof Enemy)) {
            double dx = entity.getX() - pos.getX();
            double dz = entity.getZ() - pos.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance <= HOSTILE_SCAN_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private TaskStatus tickRetreat(BotContext ctx) {
        if (!caveSealed && sealPos != null) {
            sealTicks++;
            BlockPlacer.PlacementResult sealing = BlockPlacer.tryPlace(
                    ctx, Blocks.COBBLESTONE, sealPos);
            if (sealing == BlockPlacer.PlacementResult.PLACED
                    || sealing == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
                caveSealed = true;
                sealTicks = 0;
                status.set("lune.status.staircase_prospect.sealed_cave_leaving_by_stair");
            } else if (!sealing.isTransient() || sealTicks >= MAX_SEAL_TICKS) {
                caveSealed = true;
                sealTicks = 0;
                status.set("lune.status.staircase_prospect.could_not_seal_cave_leaving_anyway", sealing.name().toLowerCase(Locale.ROOT));
            } else {
                status.set("lune.status.staircase_prospect.sealing_cave_entrance");
                return TaskStatus.RUNNING;
            }
        }

        if (retreat == null) {
            retreat = new GotoTask(new Goals.Block(origin), false, false);
            retreat.start(ctx);
        }

        TaskStatus result = retreat.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }

        retreat.stop(ctx);
        retreat = null;
        status.set("lune.status.staircase_prospect.retreated_from_cave");
        return TaskStatus.FAILED;
    }

    @Override
    public void onPause(BotContext ctx) {
        breaker.stop(ctx);
        if (stagingTask != null) {
            stagingTask.onPause(ctx);
        }
        walkStuckTicks = 0;
        lastWalkFeet = null;
        lastWalkDistance = Integer.MAX_VALUE;
    }

    @Override
    public void onStop(BotContext ctx) {
        onPause(ctx);
        if (stagingTask != null) {
            stagingTask.stop(ctx);
            stagingTask = null;
        }
        ctx.input.reset();
    }
}
