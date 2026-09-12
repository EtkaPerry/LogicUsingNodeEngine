package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.catalog.BlockCatalog;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.path.AStarPathfinder;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.path.PathExecutor;
import com.etka.lune.bot.path.WaterEscape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks to a {@link Goal}. Every other movement-based task delegates here rather than steering the
 * player itself.
 * <p>
 * Because the pathfinder returns partial paths under its node budget, arriving at the end of a path
 * is not the same as arriving at the goal - so this re-paths on arrival and keeps going, and only
 * gives up after several consecutive searches make no progress.
 */
public final class GotoTask implements Task {

    /** Consecutive useless searches before admitting the goal is unreachable. */
    private static final int MAX_FAILED_PATHS = 3;
    /** Consecutive stalls before giving up rather than shuffling in place forever. */
    private static final int MAX_STUCKS = 4;
    /**
     * Stalls tolerated before a route that may dig is planned instead.
     * <p>
     * One stall can be a mob shoving the player or a slab lip; two in a row on the same goal means
     * the walking route is not walkable, and re-running the identical search cannot discover that.
     * Escalating on the second stall leaves two of the four attempts for the digging route to work.
     */
    private static final int STALLS_BEFORE_DIGGING = 2;
    /**
     * Goal-level watchdog across partial-path rebuilds. A* can return a useful-looking partial
     * path forever, resetting the executor's local stall counter while the player remains at the
     * same goal distance. Every movement caller needs a bound that survives those rebuilds.
     */
    private static final int MAX_GOAL_STALL_TICKS = 300;
    /**
     * A minute of moving without ever getting closer. Long enough for a genuine detour around a
     * ravine or a lake; short enough that a bot circling something it cannot reach gives up and
     * lets its caller pick a different target.
     *
     * <p>Deliberately flat, and measured that way. Scaling it to the starting distance looks
     * obviously right - ten blocks that have not closed in ten seconds are not going to close -
     * and on the six seeds that used to collapse it cut the harvest from 210 logs back to 54. A
     * tree approach that has to walk around a pond legitimately takes most of a minute, and a
     * short fuse spends the run thrashing between trees instead of reaching one. The cheap
     * intuition was wrong; the flat minute is what the runs support.</p>
     */
    private static final int MAX_TICKS_WITHOUT_CLOSING = 1200;
    private static final double GOAL_PROGRESS_EPSILON = 0.05;
    /** Give an explicit swim-to-air recovery enough time to reach a nearby surface. */
    private static final int WATER_RECOVERY_TIMEOUT_TICKS = 240;
    /** Blocks to climb before re-checking for a route; a staircase step is one or two. */
    private static final int CLIMB_STEP = 3;
    /**
     * Total climb allowed per goal. Enough to get out of a self-dug staircase or a ravine ledge,
     * but not so much that a bot with a stack of cobble towers into the sky chasing something it
     * was never going to reach.
     */
    private static final int MAX_CLIMB_BLOCKS = 12;
    /** A short visible ceiling breach is enough to reconnect a self-dug staircase to open ground. */
    private static final int MAX_CEILING_BLOCKS = 8;

    private final Goal goal;
    private final boolean sprint;
    private final boolean allowBreak;
    /** Whether this movement may use bridge/pillar/ceiling recovery when the route stalls. */
    private final boolean allowRecovery;
    /** Whether this route may contain voluntary step-up, climb, or gap-jump nodes. */
    private final boolean allowJump;
    /**
     * Whether water is a first-class part of this goal, as it is for a shipwreck chest. Ordinary
     * travel leaves this off so a route never walks into a river merely to save blocks - but see
     * {@link #repath}, which will still swim when the dry search has run out of land.
     */
    private final boolean allowSwim;

    private PathExecutor executor;
    /** False when the current route came from the preferred open walking/swimming search. */
    private boolean executorAllowsBreak;
    /** True when the current route deliberately crosses water, so the executor must not flee it. */
    private boolean executorAllowsSwim;
    private BridgeTask bridge;
    private PillarUpTask climb;
    private CeilingBreakTask ceilingBreak;
    /** Height gained by climbing on this goal, so one hole cannot be climbed out of forever. */
    private int climbedBlocks;
    private int ticksSincePath;
    private int failedPaths;
    private int consecutiveStucks;
    /** Armed by a repeated stall, consumed by the next search; never latched. */
    private boolean escalateToDigging;
    private int waterRecoveryTicks;
    private int repaths;
    private double bestGoalHeuristic = Double.POSITIVE_INFINITY;
    private BlockPos lastProgressFeet;
    private int goalNoProgressTicks;
    /** Ticks since the bot was last closer to the goal than it had ever been. */
    private int ticksSinceClosest;
    private final StatusText status = new StatusText();
    /** Search-effort tactic learned across every movement caller. */
    private String routeStrategy = MovementPolicy.DEFAULT;

    public GotoTask(Goal goal, boolean sprint, boolean allowBreak) {
        this(goal, sprint, allowBreak, false, true, true);
    }

    /**
     * Creates a route that treats water as ordinary ground from the first search. This is for goals
     * that deliberately approach a visible water target, such as a shipwreck chest. Other callers
     * leave it off and get a dry route whenever one exists, falling back to swimming only when the
     * land runs out.
     */
    public GotoTask(Goal goal, boolean sprint, boolean allowBreak, boolean allowSwim) {
        this(goal, sprint, allowBreak, allowSwim, true, true);
    }

    /**
     * Creates a route with explicit recovery permission.
     *
     * <p>Most jobs should keep recovery enabled: it lets a shared movement task escape a pocket
     * left by an earlier staircase. A tree approach is different. It is a local walk to a visible
     * trunk, and building a bridge or pillar there is never a useful way to reach the log. Keeping
     * this opt-out in GotoTask prevents the same bad recovery from being reimplemented by each
     * gathering job.</p>
     */
    public GotoTask(Goal goal, boolean sprint, boolean allowBreak, boolean allowSwim,
                    boolean allowRecovery) {
        this(goal, sprint, allowBreak, allowSwim, allowRecovery, true);
    }

    /** Creates a route with explicit recovery and voluntary-jump permissions. */
    public GotoTask(Goal goal, boolean sprint, boolean allowBreak, boolean allowSwim,
                    boolean allowRecovery, boolean allowJump) {
        this.goal = goal;
        this.sprint = sprint;
        this.allowBreak = allowBreak;
        this.allowSwim = allowSwim;
        this.allowRecovery = allowRecovery;
        this.allowJump = allowJump;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.goto.name", goal.describe());
    }

    /** English on purpose: this is the learner's row key, and is never shown. */
    @Override
    public String learningId() {
        return Task.learningName("Go to " + goal.describe());
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public LearningContext learningContext(BotContext ctx) {
        double distance = goal.heuristic(MovementHelper.feetPosition(ctx.player));
        String phase = "goal=" + goal.getClass().getSimpleName()
                + ";distance=" + MovementPolicy.distanceBucket(distance)
                + ";break=" + allowBreak + ";swim=" + allowSwim
                + ";recovery=" + allowRecovery;
        return new LearningContext("skill", "movement", ctx.level.dimension().identifier().toString(), phase);
    }

    @Override
    public List<String> learningActions(BotContext ctx) {
        return MovementPolicy.ACTIONS;
    }

    @Override
    public void onLearningAction(BotContext ctx, String action) {
        routeStrategy = MovementPolicy.ACTIONS.contains(action) ? action : MovementPolicy.DEFAULT;
    }

    @Override
    public void onStart(BotContext ctx) {
        routeStrategy = MovementPolicy.DEFAULT;
        bestGoalHeuristic = Double.POSITIVE_INFINITY;
        lastProgressFeet = null;
        goalNoProgressTicks = 0;
        ticksSinceClosest = 0;
        ctx.debug.goal = goal.describe();
        ctx.debug.clearPath();
        ctx.debug.goalNoProgressTicks = 0;
        ctx.debug.intent = "finding a route to " + goal.describe();
        ctx.debug.giveUp = goalLimits();
        ctx.debug.decide("trying an open walking route first");
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        BlockPos feet = MovementHelper.feetPosition(ctx.player);
        ctx.debug.goal = goal.describe();
        boolean goalStalled = !goal.isReached(feet) && updateGoalProgress(feet);
        ctx.debug.goalNoProgressTicks = goalNoProgressTicks;
        if (goalStalled) {
            if (ticksSinceClosest >= MAX_TICKS_WITHOUT_CLOSING) {
                status.set("lune.status.goto.moving_but_never_getting_closer");
            } else {
                status.set("lune.status.goto.goal_made_no_progress");
            }
            ctx.debug.giveUp = goalLimits();
            ctx.debug.lastEvent = "goal stalled for " + goalNoProgressTicks + " ticks";
            ctx.debug.decide("give up: goal-level movement watchdog reached");
            return TaskStatus.FAILED;
        }
        ctx.debug.giveUp = goalLimits();
        if (goal.isReached(feet)) {
            ctx.debug.intent = "goal reached; returning to caller";
            return TaskStatus.SUCCESS;
        }

        // Active bridge/scaffold: one step across water or a gap. On success we re-path from the
        // new floor, on failure we give up here.
        if (bridge != null) {
            return tickBridge(ctx);
        }

        // Active climb out of a hole. Same shape as the bridge: build, then re-path from the new
        // position and let the search decide what to do with it.
        if (climb != null) {
            return tickClimb(ctx);
        }

        // A staircase can leave the player under a two-block ceiling. Clear only that directly
        // visible obstruction, then let the ordinary search decide whether walking or pillar-up is
        // the right next move. This recovery is shared by every movement caller, including the
        // food scout whose destination must never become a reason to tunnel underground.
        if (ceilingBreak != null) {
            return tickCeilingBreak(ctx);
        }

        // A route can legitimately enter water, but path following should not keep declaring a
        // stall there and then abandon the whole task. Once it has stalled in water, take over
        // long enough to get the head into breathable air, then plan the route again from there.
        if (waterRecoveryTicks > 0) {
            return tickWaterRecovery(ctx);
        }

        // Standing inside a block is reported, not routed around: PathExecutor clears the two blocks
        // the body occupies on any route, digging or not, so the search is free to go on preferring
        // the open one. Forcing a digging route here made the bot answer "I am in some leaves" with
        // a tunnel, which is the opposite of what a player does.
        BlockPos trapped = blockedBody(ctx);
        if (trapped != null) {
            ctx.debug.lastEvent = "body inside " + blockName(ctx, trapped);
        }

        ticksSincePath++;
        // Only re-path on the timer when the current route has actually stopped working. A
        // budget-limited search returns whichever partial route looked best, and those differ
        // between runs - re-pathing mid-stride was making the bot turn around and walk back over
        // ground it had just covered.
        boolean stalling = executor != null && executor.getNoProgressTicks() > 5;
        if (executor == null || (ticksSincePath > ctx.config.repathInterval && stalling)) {
            ctx.debug.intent = "searching for a route";
            if (!repath(ctx, feet)) {
                // A search can exhaust its retry budget while the player is still standing in a
                // pocket left by an earlier digging phase. An open headroom check is not enough:
                // the player may still need the same shared pillar recovery used by the normal
                // empty-path branch. Failing before trying it strands every caller at "empty path
                // x3" even though the inventory and the space above are both usable.
                if (tryCeilingBreak(ctx, feet) || tryClimbOut(ctx, feet)) {
                    return TaskStatus.RUNNING;
                }
                status.set("lune.status.goto.no_route_found");
                ctx.debug.decide("give up: route search budget exhausted");
                return TaskStatus.FAILED;
            }
            if (executor == null) {
                // repath() reports "not out of retries yet" without necessarily producing a path -
                // a search that can't leave the start block yields nothing to follow. That is the
                // signature of being walled in, which is what digging your own staircase and then
                // standing at the bottom of it looks like to the pathfinder.
                if (tryCeilingBreak(ctx, feet) || tryClimbOut(ctx, feet)) {
                    return TaskStatus.RUNNING;
                }
                // Wait for the next attempt instead of dereferencing a route we don't have.
                status.set("lune.status.goto.looking_route");
                ctx.debug.decide("no movable path yet; attempting recovery if possible");
                return TaskStatus.RUNNING;
            }
        }

        PathExecutor.Status result = executor.tick(ctx, sprint && ctx.config.allowSprint, executorAllowsBreak);

        ctx.debug.pathIndex = executor.getIndex();
        ctx.debug.currentNode = executor.getCurrentTarget();
        ctx.debug.noProgressTicks = executor.getNoProgressTicks();
        ctx.debug.intent = "following route toward " + goal.describe();

        // The executor can report STUCK on the same tick that physics carries the player into a
        // satisfied radius (especially on the last upward stair step). Test the real goal before
        // routing that status through the failure counter; otherwise a successful arrival can be
        // turned into a failed parent job merely because the route snapshot was one tick stale.
        if (goal.isReached(MovementHelper.feetPosition(ctx.player))) {
            executor.stop(ctx);
            executor = null;
            consecutiveStucks = 0;
        escalateToDigging = false;
            return TaskStatus.SUCCESS;
        }

        switch (result) {
            case RUNNING -> {
                status.set("lune.status.goto.blocks_left", executor.remainingNodes());
                return TaskStatus.RUNNING;
            }
            case DONE -> {
                consecutiveStucks = 0;
        escalateToDigging = false;
                if (goal.isReached(MovementHelper.feetPosition(ctx.player))) {
                    return TaskStatus.SUCCESS;
                }
                // End of a partial path - keep going from here.
                if (!repath(ctx, MovementHelper.feetPosition(ctx.player))) {
                    status.set("lune.status.goto.no_route_found");
                    return TaskStatus.FAILED;
                }
                return TaskStatus.RUNNING;
            }
            case NO_TOOL -> {
                // No amount of re-routing fixes a missing pickaxe; say so instead of thrashing.
                status.set("lune.status.goto.break", executor.getBlockedBy());
                return TaskStatus.FAILED;
            }
            case REPLAN -> {
                status.set(executor.getBlockedBy());
                executor.stop(ctx);
                executor = null;
                if (!repath(ctx, MovementHelper.feetPosition(ctx.player))) {
                    status.set("lune.status.goto.fluid_changed_no_safe_route_remains");
                    return TaskStatus.FAILED;
                }
                return TaskStatus.RUNNING;
            }
            case HAZARD -> {
                status.set(executor.getBlockedBy());
                return TaskStatus.FAILED;
            }
            case STUCK -> {
                // Never treat swimming as an ordinary walking stall. The direct escape helper
                // climbs or follows connected water to air without breaking blocks, so it cannot
                // tunnel farther underwater or make a leak worse.
                if (ctx.player.isInWater()) {
                    waterRecoveryTicks = 1;
                    return tickWaterRecovery(ctx);
                }
                // If the next floor is open air, try a one-step bridge instead of shuffling.
                if (shouldBridge(ctx) && tryBridge(ctx)) {
                    return TaskStatus.RUNNING;
                }
                consecutiveStucks++;
                ctx.debug.count("stalls");
                ctx.debug.lastEvent = "stuck x" + consecutiveStucks;
                ctx.debug.decide("movement stalled; replanning (" + consecutiveStucks + "/"
                        + MAX_STUCKS + ")");
                // Exactly at the threshold, so the escalation is armed once per stall run rather
                // than on every stall after the second.
                if (consecutiveStucks == STALLS_BEFORE_DIGGING) {
                    escalateToDigging = true;
                }
                if (consecutiveStucks >= MAX_STUCKS) {
                    status.set("lune.status.goto.stuck_cant_recover");
                    ctx.debug.decide("give up: stall limit reached");
                    return TaskStatus.FAILED;
                }
                // Ordinary path recovery must not turn into repeated back-jumps. Release the stale
                // route and plan again while standing still; jumping is reserved for an explicit
                // one-block ascent in PathExecutor.
                executor.stop(ctx);
                executor = null;
                if (!repath(ctx, MovementHelper.feetPosition(ctx.player))) {
                    status.set("lune.status.goto.stuck_no_walking_route_remains");
                    return TaskStatus.FAILED;
                }
                status.set("lune.status.goto.replanning_after_movement_stalled");
                return TaskStatus.RUNNING;
            }
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void onPause(BotContext ctx) {
        onStop(ctx);
    }

    @Override
    public void onStop(BotContext ctx) {
        if (executor != null) {
            // Release any half-finished break, or the bot appears frozen mid-swing.
            executor.stop(ctx);
            executor = null;
        }
        if (bridge != null) {
            bridge.stop(ctx);
            bridge = null;
        }
        if (climb != null) {
            climb.stop(ctx);
            climb = null;
        }
        if (ceilingBreak != null) {
            ceilingBreak.stop(ctx);
            ceilingBreak = null;
        }
        ctx.input.reset();
    }

    /** True when the next path node is over open air and a one-block bridge would help. */
    private boolean shouldBridge(BotContext ctx) {
        if (!allowRecovery || ctx.player.isInWater()) {
            return false;
        }
        if (executor == null) {
            return false;
        }
        BlockPos target = executor.getCurrentTarget();
        return target != null && ctx.level.getBlockState(target.below()).isAir();
    }

    /** Starts a one-block bridge in the horizontal direction of the current path node. */
    private boolean tryBridge(BotContext ctx) {
        if (!allowRecovery || bridge != null) {
            return false;
        }
        if (executor == null) {
            return false;
        }
        BlockPos target = executor.getCurrentTarget();
        if (target == null) {
            return false;
        }
        double dx = target.getX() + 0.5 - ctx.player.getX();
        double dz = target.getZ() + 0.5 - ctx.player.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return false;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        Direction dir = Direction.fromYRot(yaw);
        if (!dir.getAxis().isHorizontal()) {
            return false;
        }
        bridge = new BridgeTask(dir.getName(), 1, new HashSet<>(BlockCatalog.buildingBlocks()));
        bridge.start(ctx);
        return true;
    }

    /**
     * Starts building a way out when the search cannot leave the block the bot is standing on.
     * <p>
     * Only when up is actually open: under a ceiling this would be pointless, and refusing here
     * lets the normal failed-path counter end the goal instead of pretending to make progress.
     */
    private boolean tryClimbOut(BotContext ctx, BlockPos feet) {
        if (!allowRecovery || climb != null || climbedBlocks >= MAX_CLIMB_BLOCKS
                || !isWalledIn(ctx, feet)) {
            return false;
        }
        if (!MovementHelper.isPassable(ctx.level, feet.above(2))) {
            return false;
        }
        climb = new PillarUpTask(Math.min(CLIMB_STEP, MAX_CLIMB_BLOCKS - climbedBlocks));
        climb.start(ctx);
        status.set("lune.status.goto.walled_building_way_out");
        return true;
    }

    /**
     * A pillar is an escape from a pocket, not a way to gain height toward an unreachable goal.
     * Open air at the sides means the player can choose a horizontal exit; it must never be
     * treated as evidence that climbing is needed. This distinction matters for floating farms:
     * their crop target can be above the player while the player is standing safely in an open
     * field, and building upward there would create a tower with no route back down.
     */
    private static boolean isWalledIn(BotContext ctx, BlockPos feet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            if (MovementHelper.isPassable(ctx.level, side)
                    && MovementHelper.isPassable(ctx.level, side.above())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Starts the bounded direct recovery when the player's own body space, or the headroom they
     * need to jump through, is blocked.
     */
    private boolean tryCeilingBreak(BotContext ctx, BlockPos feet) {
        if (!allowRecovery || ceilingBreak != null || climbedBlocks >= MAX_CLIMB_BLOCKS) {
            return false;
        }
        BlockPos target = blockedBody(ctx);
        if (target == null) {
            if (MovementHelper.isPassable(ctx.level, feet.above(2))) {
                return false;
            }
            target = feet.above(2);
        }
        if (!MovementHelper.isBreakable(ctx.level, target)
                || MovementHelper.wouldOpenLava(ctx.level, target)
                || MovementHelper.wouldOpenWater(ctx.level, target)) {
            return false;
        }
        ceilingBreak = new CeilingBreakTask(Math.min(MAX_CEILING_BLOCKS,
                MAX_CLIMB_BLOCKS - climbedBlocks));
        ceilingBreak.start(ctx);
        status.set("lune.status.goto.walled_clearing", blockName(ctx, target));
        return true;
    }

    /** The block of the player's own body space that has to go before any route can be followed. */
    private static BlockPos blockedBody(BotContext ctx) {
        return MovementHelper.blockedBodyPos(ctx.level, MovementHelper.feetPosition(ctx.player),
                ctx.player.getOnPos(), ctx.player.onGround());
    }

    private static String blockName(BotContext ctx, BlockPos pos) {
        return ctx.level.getBlockState(pos).getBlock().getName().getString();
    }

    /** Ticks the direct ceiling recovery, then reconnects the normal route. */
    private TaskStatus tickCeilingBreak(BotContext ctx) {
        TaskStatus result = ceilingBreak.tick(ctx);
        status.set(ceilingBreak.statusLine());
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }

        ceilingBreak.stop(ctx);
        ceilingBreak = null;
        if (result == TaskStatus.FAILED) {
            failedPaths++;
            ctx.debug.lastEvent = "ceiling recovery failed x" + failedPaths;
            ctx.debug.decide("ceiling recovery failed; retry " + failedPaths + "/"
                    + MAX_FAILED_PATHS);
            if (failedPaths >= MAX_FAILED_PATHS) {
                status.set("lune.status.goto.could_not_clear_ceiling");
                return TaskStatus.FAILED;
            }
            status.set("lune.status.goto.ceiling_recovery_made_no_progress");
            return TaskStatus.RUNNING;
        }

        failedPaths = 0;
        consecutiveStucks = 0;
        escalateToDigging = false;
        if (executor != null) {
            executor.stop(ctx);
            executor = null;
        }
        if (!repath(ctx, MovementHelper.feetPosition(ctx.player))) {
            // One ceiling clear may only expose the next ledge. Keep the shared recovery alive
            // while it is still making vertical progress instead of failing at the first gap.
            BlockPos feet = MovementHelper.feetPosition(ctx.player);
            if (tryCeilingBreak(ctx, feet) || tryClimbOut(ctx, feet)) {
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.goto.no_route_after_clearing_ceiling");
            return TaskStatus.FAILED;
        }
        status.set("lune.status.goto.replanning_after_clearing_ceiling");
        return TaskStatus.RUNNING;
    }

    /** Ticks an active climb and repaths from the higher ground if it gained any. */
    private TaskStatus tickClimb(BotContext ctx) {
        TaskStatus result = climb.tick(ctx);
        status.set(climb.statusLine());
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }

        int gained = climb.gained();
        climb.stop(ctx);
        climb = null;
        climbedBlocks += gained;

        if (gained <= 0) {
            // Could not build at all - no blocks, a ceiling, or nowhere to put them. Count this
            // as a useless recovery attempt. Recreating the same PillarUpTask on the next tick
            // would reset its placement timeout and leave every caller circling in a pocket
            // forever, which is precisely the failure this recovery is meant to prevent.
            failedPaths++;
            ctx.debug.lastEvent = "climb recovery failed x" + failedPaths;
            ctx.debug.decide("climb recovery made no progress; retry " + failedPaths + "/"
                    + MAX_FAILED_PATHS);
            if (failedPaths >= MAX_FAILED_PATHS) {
                status.set("lune.status.goto.could_not_climb_out");
                return TaskStatus.FAILED;
            }
            status.set("lune.status.goto.climb_recovery_made_no_progress");
            return TaskStatus.RUNNING;
        }

        consecutiveStucks = 0;
        escalateToDigging = false;
        failedPaths = 0;
        if (executor != null) {
            executor.stop(ctx);
            executor = null;
        }
        if (!repath(ctx, MovementHelper.feetPosition(ctx.player))) {
            // A one-block pillar can leave the player in another shallow pocket. Try the next
            // recovery from the new feet position before declaring a genuinely failed escape.
            BlockPos feet = MovementHelper.feetPosition(ctx.player);
            if (tryCeilingBreak(ctx, feet) || tryClimbOut(ctx, feet)) {
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.goto.no_route_after_climbing_out");
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    /** Ticks an active one-step bridge and repaths if it succeeds. */
    private TaskStatus tickBridge(BotContext ctx) {
        TaskStatus result = bridge.tick(ctx);
        status.set("lune.status.goto.bridging", bridge.statusLine());
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }

        bridge.stop(ctx);
        bridge = null;

        if (result == TaskStatus.FAILED) {
            status.set("lune.status.goto.bridge_failed");
            return TaskStatus.FAILED;
        }

        // Bridge succeeded: reset stall state and find a new route from the new position.
        consecutiveStucks = 0;
        escalateToDigging = false;
        if (executor != null) {
            executor.stop(ctx);
            executor = null;
        }
        if (!repath(ctx, MovementHelper.feetPosition(ctx.player))) {
            status.set("lune.status.goto.no_route_after_bridge");
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    /**
     * Escapes a water stall before resuming normal pathfinding. This is deliberately separate
     * from the regular stuck counter: a swimmer may need several seconds to rise or cross to a
     * nearby air pocket, and failing after four path ticks was far too aggressive.
     */
    private TaskStatus tickWaterRecovery(BotContext ctx) {
        ctx.debug.intent = "recovering from water before replanning";
        ctx.debug.giveUp = "water escape " + waterRecoveryTicks + "/"
                + WATER_RECOVERY_TIMEOUT_TICKS + " ticks, goal stall "
                + goalNoProgressTicks + "/" + MAX_GOAL_STALL_TICKS;
        if (!ctx.player.isUnderWater()) {
            waterRecoveryTicks = 0;
            consecutiveStucks = 0;
        escalateToDigging = false;
            if (executor != null) {
                executor.stop(ctx);
                executor = null;
            }
            if (!repath(ctx, MovementHelper.feetPosition(ctx.player))) {
                status.set("lune.status.goto.reached_air_but_no_route_remains");
                return TaskStatus.FAILED;
            }
            status.set("lune.status.goto.replanning_after_reaching_air");
            return TaskStatus.RUNNING;
        }

        if (waterRecoveryTicks++ >= WATER_RECOVERY_TIMEOUT_TICKS) {
            status.set("lune.status.goto.couldnt_reach_breathable_air");
            ctx.debug.lastEvent = "water escape timed out";
            ctx.debug.decide("give up: water escape timed out");
            return TaskStatus.FAILED;
        }

        if (executor != null) {
            executor.stop(ctx);
            executor = null;
        }
        // Do not combine stale path input with the rescue steering; it can cause the player to
        // fight the current and remain underwater even when an upward path exists.
        ctx.input.reset();
        WaterEscape.tickToAir(ctx);
        status.set("lune.status.goto.swimming_breathable_air");
        return TaskStatus.RUNNING;
    }

    /** Looks for the nearest standable block around {@code centre} when the exact feet pos is invalid. */
    private static BlockPos findStandableNearby(BotContext ctx, BlockPos centre) {
        BlockPos best = centre;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = centre.offset(dx, dy, dz);
                    if (MovementHelper.canStandAt(ctx.level, candidate)) {
                        double dist = ctx.player.position().distanceToSqr(
                                candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Runs a fresh search. Returns false once repeated searches stop producing any usable path,
     * which is how a genuinely unreachable goal is distinguished from a merely distant one.
     */
    private boolean repath(BotContext ctx, BlockPos from) {
        ticksSincePath = 0;
        ctx.debug.intent = "searching for a route to " + goal.describe();
        ctx.debug.giveUp = goalLimits();

        // Falling or clipped into a block leaves the feet position unstandable; nudging the start
        // onto the nearest sane block stops the very first search coming back empty.
        //
        // Hanging on a ladder or a vine is not one of those cases. There is no floor under the
        // player and there is not supposed to be, and the search knows how to climb from exactly
        // where they are - so moving the start to some standable block a few metres away only
        // hands the executor a first step the player is in no position to take.
        BlockPos start = from;
        if (!MovementHelper.canStandAt(ctx.level, start)
                && !MovementHelper.isClimbable(ctx.level, start)) {
            if (MovementHelper.canStandAt(ctx.level, start.below())) {
                start = start.below();
            } else if (MovementHelper.canStandAt(ctx.level, start.above())) {
                start = start.above();
            } else {
                start = findStandableNearby(ctx, from);
            }
        }

        // Routes are searched for in the order a player would consider them: walk there on dry
        // land, swim across what is in the way, and only then pick up a pickaxe.
        //
        // The swimming tier is what stops a river turning into a hole. The dry search returns the
        // nearest point on this bank and then, standing on it, returns nothing at all - so without
        // a wet route in between, the only thing left is the digging search, which cheerfully
        // tunnels into the bank chasing a target it can never reach by land. Water is priced by
        // AStarPathfinder.WATER_ENTRY_COST, so a route still walks round a pond; it takes the
        // plunge only when going round is genuinely much longer, or impossible.
        boolean swimAllowed = ctx.config.allowSwim;
        boolean swimPreferred = allowSwim && swimAllowed;

        // A route the search likes but the body cannot walk is the signature of the cheap tier
        // being wrong, and re-running the same search returns the same route every time. That is
        // how a bot ends up two blocks above its own dropped log, pressing no keys, replanning an
        // identical "dry route" four times and giving up - with a pickaxe in its hand. So once
        // movement has stalled twice, skip straight past the tiers that already failed to work.
        AStarPathfinder.Result result;
        if (allowBreak && escalateToDigging) {
            // One shot, consumed here. Leaving it latched turns "this walk stalled twice" into
            // "dig everywhere from now on": the flag would then be true for every later repath,
            // including the task ones, and the bot tunnels its way across the world instead of
            // walking. Measured at 1351 escalations in a single run before this was one-shot.
            escalateToDigging = false;
            result = AStarPathfinder.find(ctx.level, start, goal,
                    tune(ctx.config.miningSettings()).withAllowSwim(swimAllowed)
                            .withAllowJump(allowJump).withMiner(ctx.player));
            executorAllowsBreak = true;
            executorAllowsSwim = swimAllowed;
            ctx.debug.decide("walking route stalled twice; planning one that can dig, once");
            return finishRoute(ctx, result, goal, start);
        }

        result = AStarPathfinder.find(ctx.level, start, goal,
                tune(ctx.config.walkingSettings()).withAllowSwim(swimPreferred)
                        .withAllowJump(allowJump));
        executorAllowsBreak = false;
        executorAllowsSwim = swimPreferred;
        if (usableRoute(result, ctx)) {
            ctx.debug.decide(swimPreferred
                    ? "open route found; following it without digging"
                    : "dry route found; following it without digging");
        } else if (!swimPreferred && swimAllowed) {
            result = AStarPathfinder.find(ctx.level, start, goal,
                    tune(ctx.config.walkingSettings()).withAllowSwim(true)
                            .withAllowJump(allowJump));
            executorAllowsSwim = true;
            ctx.debug.decide(!usableRoute(result, ctx)
                    ? "no dry or swimming route from here"
                    : "no dry route; crossing the water instead of digging");
        }

        if (!usableRoute(result, ctx) && allowBreak) {
            result = AStarPathfinder.find(ctx.level, start, goal,
                    tune(ctx.config.miningSettings()).withAllowSwim(swimPreferred)
                            .withAllowJump(allowJump).withMiner(ctx.player));
            executorAllowsBreak = true;
            executorAllowsSwim = swimPreferred;
            ctx.debug.decide("no route on foot or through water; trying a route that can dig");
        }

        return finishRoute(ctx, result, goal, start);
    }

    private AStarPathfinder.Settings tune(AStarPathfinder.Settings settings) {
        return settings.withHeuristicWeight(
                MovementPolicy.heuristicWeight(routeStrategy, settings.heuristicWeight()));
    }

    /** Publishes a chosen route and hands it to a fresh executor. */
    private boolean finishRoute(BotContext ctx, AStarPathfinder.Result result, Goal goal,
                                BlockPos start) {
        repaths++;
        ctx.debug.nodesExpanded = result.nodesExpanded();
        ctx.debug.nodeBudget = result.nodeBudget();
        ctx.debug.searchMillis = result.searchMillis();
        ctx.debug.reachedGoal = result.reachedGoal();
        ctx.debug.pathLength = result.path().size();
        ctx.debug.repaths = repaths;
        ctx.debug.runRepaths++;
        ctx.debug.goal = goal.describe();
        ctx.debug.count("path_nodes", result.nodesExpanded());
        ctx.debug.count("path_micros", Math.round(result.searchMillis() * 1000.0));
        ctx.debug.peak("path_length", result.path().size());

        if (result.isEmpty()) {
            // A path of just the start block means the search couldn't move at all.
            failedPaths++;
            ctx.debug.count("path_empty");
            ctx.debug.lastEvent = "empty path x" + failedPaths;
            ctx.debug.giveUp = goalLimits();
            return failedPaths < MAX_FAILED_PATHS;
        }

        failedPaths = 0;
        executor = new PathExecutor(result.path(), ctx.level, executorAllowsSwim, allowJump);
        ctx.debug.nextDecision = executorAllowsBreak
                ? "follow route and clear obstructions when safe"
                : "follow route; avoid digging";
        return true;
    }

    /**
     * A non-empty path is not automatically a route. When A* exhausts the reachable component
     * before finding the goal, it returns the best partial path so a budget-limited search can
     * make forward progress. That same shape also appears when a dry search is genuinely cut off
     * by a wall: following it forever only walks back and forth around the wall while preventing
     * the swimming or digging fallback from running. Accept partial paths only when the search
     * actually consumed its node budget; an exhausted open set means this movement tier failed.
     */
    private static boolean usableRoute(AStarPathfinder.Result result, BotContext ctx) {
        if (result.isEmpty()) {
            return false;
        }
        return result.reachedGoal() || result.budgetExhausted();
    }

    /**
     * Counts progress against the destination rather than against the current A* route. A player
     * can move around a detour, so a meaningful feet-position change also resets the watchdog; a
     * player staring at one block, however, cannot reset it by repeatedly rebuilding partial paths.
     *
     * <p>Two clocks, because moving is not the same as arriving. The first forgives a detour and
     * only catches a bot standing still. On its own it forgives far too much: pacing two blocks
     * back and forth resets it forever, and one recorded run spent 8,987 of its 9,000 ticks -
     * seven and a half minutes - "coming next to the tree" without ever getting there, because the
     * shuffling never stopped. The second clock only resets when the bot is genuinely closer to
     * the goal than it has ever been, so a real detour gets a full minute of rope and circling
     * gets exactly one minute.</p>
     */
    private boolean updateGoalProgress(BlockPos feet) {
        double heuristic = goal.heuristic(feet);
        boolean improved = heuristic + GOAL_PROGRESS_EPSILON < bestGoalHeuristic;
        boolean moved = lastProgressFeet != null && feet.distSqr(lastProgressFeet) >= 4.0;
        if (lastProgressFeet == null || improved || moved) {
            if (improved) {
                bestGoalHeuristic = heuristic;
            }
            lastProgressFeet = feet.immutable();
            goalNoProgressTicks = 0;
        } else {
            goalNoProgressTicks++;
        }
        if (lastProgressFeet == null || improved) {
            ticksSinceClosest = 0;
        } else {
            ticksSinceClosest++;
        }
        return goalNoProgressTicks >= MAX_GOAL_STALL_TICKS
                || ticksSinceClosest >= MAX_TICKS_WITHOUT_CLOSING;
    }

    private String goalLimits() {
        return "empty paths " + failedPaths + "/" + MAX_FAILED_PATHS
                + ", stalls " + consecutiveStucks + "/" + MAX_STUCKS
                + ", goal stall " + goalNoProgressTicks + "/" + MAX_GOAL_STALL_TICKS
                + ", not closing " + ticksSinceClosest + "/" + MAX_TICKS_WITHOUT_CLOSING
                + ", climb " + climbedBlocks + "/" + MAX_CLIMB_BLOCKS;
    }
}
