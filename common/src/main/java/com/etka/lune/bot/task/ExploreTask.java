package com.etka.lune.bot.task;

import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.knowledge.BiomeScout;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.memory.BlockMemory;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.util.TargetIndex;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Predicate;

/**
 * Walks around and looks for a matching block. When it finds one it succeeds and remembers the
 * position, but it does not mine or harvest. This is a separate "explore" box so it can be wired
 * into a task before a Mine/Farm/Harvest step.
 * <p>
 * It searches the way a player does rather than the way a lawnmower does. It looks where it is
 * already facing before turning anywhere; it picks a direction from what the land actually looks
 * like - trees that way, mesa the other way - and then commits to it for a while instead of
 * re-deciding every stop; and it only turns all the way round once the cheaper looks have failed.
 */
public final class ExploreTask implements Task {

    /** Blocked routes in a row that mean the spot, not the search, is the problem. */
    private static final int BLOCKED_ROUTES_BEFORE_GIVING_UP = 3;
    /** Blocked routes on one heading before the heading itself is treated as the problem. */
    private static final int FAILURES_BEFORE_TURNING = 2;
    /** Random wobble per step, so a committed heading is not a ruler-straight line. */
    private static final float DRIFT_DEGREES = 12.0F;
    /** How far the bot must move before a look around counts as a look from somewhere new. */
    private static final int RESCAN_DISTANCE = 3;
    /**
     * How far the bot travels before refreshing the candidate index while walking.
     * <p>
     * Coarser than {@link #RESCAN_DISTANCE} on purpose. Travel covers ground quickly and a rebuild
     * walks every chunk section in the search cube, so anchoring it to a walking player at the
     * standing-still distance would spend the whole trip rebuilding. The visibility test still runs
     * every tick against the cached candidates, and the search radius is far wider than this step,
     * so nothing comes into view between refreshes without being noticed.
     */
    private static final int TRAVEL_RESCAN_DISTANCE = 8;
    /**
     * Candidates ray-cast per tick while walking. A stopped bot checks every indexed block, because
     * looking around is the only thing it is doing; a walking one takes a slice per tick so the
     * sight tests never compete with movement and rendering. A large set is still covered within a
     * second or two of travel, which is well inside one walking step.
     */
    private static final int TRAVEL_SIGHT_CHECKS = 64;
    /** Vertical half-height of the scan cube; see {@link #visibleTarget} for why it is capped. */
    private static final int VERTICAL_SCAN_LIMIT = 48;

    private final Set<Block> targets;
    private final int radius;
    private final int maxAttempts;
    private final int stepDistance;
    private final boolean checkAround;
    private final boolean smartDirection;
    /** Optional state-level requirement for targets such as fluid sources, not just block IDs. */
    private final Predicate<BlockState> targetFilter;

    private final HeadScanner headScanner;
    private final TargetIndex index = new TargetIndex();
    private final RandomSource random = RandomSource.create();

    private BlockPos scanCentre;
    private boolean scanDone;
    private GotoTask walk;
    private int attempts;
    private int directionIndex;
    private BlockPos found;
    /** The last indexed match, even when it was behind a visible blocker. */
    private BlockPos lastCandidate;
    private final StatusText status = new StatusText();

    /** The heading currently being followed, or null before the first choice. */
    private Float committedYaw;
    /** The last two headings walked, so a new one is not allowed to simply undo them. */
    private Float previousYaw;
    private Float olderYaw;
    private int stepsOnHeading;
    private final StatusText headingReason = new StatusText();
    /** Rebuilt per ask; the heading line is derived, never stored. */
    private final StatusText travel = new StatusText();
    /** Force one geometric alternative after a route/waypoint was unusable. */
    private boolean tryAlternateHeading;
    /** The last two headings whose routes failed, so the next choice does not re-pick them. */
    private Float blockedYaw;
    private Float olderBlockedYaw;
    /** Blocked routes since the last one that worked; resets as soon as a route succeeds. */
    private int consecutiveBlocked;
    /** Failures on the current heading, so one obstacle does not discard a chosen direction. */
    private int failuresOnHeading;
    /** Learned search horizon; all variants preserve glance-first, visible-only searching. */
    private String searchStrategy = ExplorePolicy.DEFAULT;

    public ExploreTask(Set<Block> targets, int radius, int maxAttempts, int stepDistance) {
        this(targets, radius, maxAttempts, stepDistance, true);
    }

    public ExploreTask(Set<Block> targets, int radius, int maxAttempts, int stepDistance,
                       boolean checkAround) {
        this(targets, radius, maxAttempts, stepDistance, checkAround, HeadScanner.Style.GLANCE, true);
    }

    /**
     * @param scanStyle      how wide the first look at each stop is. Narrow styles still widen on
     *                       their own when they turn up nothing.
     * @param smartDirection consult biome knowledge when choosing where to walk, instead of
     *                       cycling through the four compass directions.
     */
    public ExploreTask(Set<Block> targets, int radius, int maxAttempts, int stepDistance,
                       boolean checkAround, HeadScanner.Style scanStyle, boolean smartDirection) {
        this(targets, radius, maxAttempts, stepDistance, checkAround, scanStyle, smartDirection,
                state -> true);
    }

    /**
     * Creates a physical search with an additional state-level condition. The block index still
     * provides the cheap candidate set, while the predicate decides whether the visible block is
     * actually useful to the caller (for example, a source fluid rather than flowing fluid).
     */
    public ExploreTask(Set<Block> targets, int radius, int maxAttempts, int stepDistance,
                       boolean checkAround, HeadScanner.Style scanStyle, boolean smartDirection,
                       Predicate<BlockState> targetFilter) {
        this.targets = Set.copyOf(targets);
        this.radius = Math.max(8, radius);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.stepDistance = Math.max(4, stepDistance);
        this.checkAround = checkAround;
        this.smartDirection = smartDirection;
        this.targetFilter = targetFilter == null ? state -> true : targetFilter;
        this.headScanner = new HeadScanner(scanStyle);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.explore.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Explore");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public boolean madeProgress() {
        return found != null;
    }

    @Override
    public LearningContext learningContext(BotContext ctx) {
        String targetKey = targets.stream()
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .sorted()
                .limit(4)
                .collect(Collectors.joining(","));
        String phase = "targets=" + (targetKey.isBlank() ? "none" : targetKey)
                + ";radius=" + radiusBucket(radius)
                + ";smart=" + smartDirection + ";look=" + checkAround;
        return new LearningContext("skill", "visible-search",
                ctx.level.dimension().identifier().toString(), phase);
    }

    @Override
    public List<String> learningActions(BotContext ctx) {
        return ExplorePolicy.ACTIONS;
    }

    @Override
    public void onLearningAction(BotContext ctx, String action) {
        searchStrategy = ExplorePolicy.ACTIONS.contains(action) ? action : ExplorePolicy.DEFAULT;
    }

    @Override
    public void onStart(BotContext ctx) {
        searchStrategy = ExplorePolicy.DEFAULT;
        resetScan(ctx);
        stopWalk(ctx);
        attempts = 0;
        directionIndex = 0;
        found = null;
        lastCandidate = null;
        committedYaw = null;
        previousYaw = null;
        olderYaw = null;
        stepsOnHeading = 0;
        headingReason.clear();
        tryAlternateHeading = false;
        blockedYaw = null;
        olderBlockedYaw = null;
        consecutiveBlocked = 0;
        failuresOnHeading = 0;
        ctx.debug.intent = "looking for " + targetNames();
        ctx.debug.searchAttempt = 0;
        ctx.debug.searchLimit = maxAttempts;
        ctx.debug.giveUp = "search stops " + attempts + "/" + maxAttempts;
        ctx.debug.decide("glance ahead before widening the scan");
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (targets.isEmpty()) {
            status.set("lune.status.explore.no_targets_selected");
            return TaskStatus.FAILED;
        }

        if (walk != null) {
            // Look while walking. A person heading west to find iron does not stop every few blocks
            // to turn their head - they notice the ore as they walk past it, and only stop and look
            // properly when the whole trip turned up nothing. Scanning only at the stops is what
            // made the bot stand still for seconds at a time between short hops.
            //
            // The head belongs to the route here, so this is purely the visibility test against
            // whatever is already in front of the bot. Nothing turns, so nothing stalls.
            BlockPos enRoute = visibleTarget(ctx, TRAVEL_RESCAN_DISTANCE, TRAVEL_SIGHT_CHECKS);
            if (enRoute != null) {
                stopWalk(ctx);
                return spotted(ctx, enRoute, Lang.get("lune.reason.spotted_while_walking"));
            }

            TaskStatus result = walk.tick(ctx);
            status.set("lune.status.detail", travelStatus(), walk.statusLine());
            ctx.debug.intent = "walking to the next " + targetNames() + " scan point";
            ctx.debug.searchAttempt = attempts;
            ctx.debug.searchLimit = maxAttempts;
            ctx.debug.searchHeading = travelStatus().text();
            ctx.debug.giveUp = "search stops " + attempts + "/" + maxAttempts;
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }

            walk.stop(ctx);
            walk = null;
            if (result == TaskStatus.FAILED) {
                // One blocked route is a tree, a pond, a rock - not a verdict on the direction.
                //
                // Abandoning the heading on the first failure is why a search that had settled on
                // "south" kept ending up somewhere else: any obstacle at all threw the decision
                // away and rotated ninety degrees, so the run never travelled far in the direction
                // it had chosen. A person steps round the obstacle and carries on south. Only when
                // the same heading fails twice is the direction itself the problem.
                if (++failuresOnHeading < FAILURES_BEFORE_TURNING) {
                    stepsOnHeading = 0;
                    ctx.debug.decide("route blocked; stepping round it and keeping this heading");
                } else {
                    // A biome recommendation is useful until the world proves that this particular
                    // approach is blocked. Keeping the same recommendation after a failed GotoTask
                    // sends every retry back into the same cave, cliff, or river crossing.
                    tryAlternateHeading = true;
                    olderBlockedYaw = blockedYaw;
                    blockedYaw = committedYaw;
                    failuresOnHeading = 0;
                    ctx.debug.decide("this heading has failed twice; try a different one");
                }
                // Several blocked routes in a row is not a search that needs more stops, it is a
                // search that cannot walk out of where it is standing - a coastline, a canyon rim.
                // Grinding out the remaining stops there just spends the phase turning around, so
                // report the failure now and let the caller pick a different approach entirely,
                // which is where the boat gets chosen.
                if (++consecutiveBlocked >= BLOCKED_ROUTES_BEFORE_GIVING_UP) {
                    status.set("lune.status.explore.blocked_ways_out_here_needs_different", consecutiveBlocked);
                    ctx.debug.decide("give up: every way out of this spot is blocked");
                    return TaskStatus.FAILED;
                }
            } else {
                consecutiveBlocked = 0;
                failuresOnHeading = 0;
            }
            attempts++;
            resetScan(ctx);

            if (attempts >= maxAttempts) {
                status.set("lune.status.explore.searched_stops_found_nothing", attempts);
                ctx.debug.decide("give up: search stop limit reached");
                return TaskStatus.FAILED;
            }

            status.set("lune.status.explore.stopping_look_around");
            return TaskStatus.RUNNING;
        }

        BlockPos seen = findTarget(ctx);
        if (seen != null) {
            return spotted(ctx, seen, Lang.get("lune.reason.spotted"));
        }

        if (!scanDone) {
            // Still looking around from here; keep going.
            return TaskStatus.RUNNING;
        }

        scanDone = false;
        return startWalk(ctx);
    }

    @Override
    public void onPause(BotContext ctx) {
        stopWalk(ctx);
    }

    @Override
    public void onStop(BotContext ctx) {
        stopWalk(ctx);
        ctx.input.reset();
        ctx.look.relax();
    }

    /** Returns the block the explore saw, or null when nothing is visible/remembered yet. */
    public BlockPos getFound() {
        return found;
    }

    /**
     * Returns the last matching block the scan indexed, including an occluded match. Callers that
     * can perform a bounded physical prospect may use this as a remembered approach point; it is
     * not permission to mine the block through the wall.
     */
    public BlockPos getLastCandidate() {
        return lastCandidate;
    }

    /** Records a sighting and ends the search. Shared by the walking and standing scans. */
    private TaskStatus spotted(BotContext ctx, BlockPos seen, String how) {
        found = seen;
        String name = ctx.level.getBlockState(seen).getBlock().getName().getString();
        BlockMemory.get().remember(seen, ctx.level.getBlockState(seen).getBlock());
        ctx.debug.target(name, seen, Vision.inspect(ctx, seen).verdict());
        ctx.debug.intent = "approaching the visible target";
        ctx.debug.decide("target found; stop scanning and approach it");
        status.set("lune.status.explore.at", how, name, seen.getX(), seen.getY(), seen.getZ());
        return TaskStatus.SUCCESS;
    }

    /**
     * The nearest indexed target that passes the caller's condition and is actually in view right
     * now. Refreshes the candidate index when the anchor has drifted more than {@code rescanDistance}
     * blocks; the visibility test itself runs on every call.
     */
    private BlockPos visibleTarget(BotContext ctx, int rescanDistance) {
        return visibleTarget(ctx, rescanDistance, 0);
    }

    /**
     * @param maxChecks how many candidates may be ray-cast this tick, or 0 for all of them. A walking
     *                  bot uses a bounded sweep so the visibility tests do not compete with movement
     *                  and rendering; a bot that has stopped specifically to look checks everything.
     */
    private BlockPos visibleTarget(BotContext ctx, int rescanDistance, int maxChecks) {
        BlockPos centre = ctx.player.blockPosition();
        if (scanCentre == null || scanCentre.distSqr(centre) > (double) rescanDistance * rescanDistance) {
            scanCentre = centre;
            headScanner.reset(ctx.player);
        }

        int scanLimit = Math.min(radius, (int) Vision.maxRange(ctx));
        // Height is capped well below the horizontal reach. Every candidate still has to pass a
        // line-of-sight ray from the eye, and there is no line of sight to a block a hundred metres
        // straight down through rock - so a full-radius vertical band only ever adds chunk sections
        // to walk, and walking them is what makes a wide search expensive. This is deliberately not
        // the old y +/- 2, which did hide targets in ravines, caves and ore layers; it is far enough
        // to see the bottom of any of those from the rim.
        int verticalLimit = Math.min(scanLimit, VERTICAL_SCAN_LIMIT);
        // Key the index on the settled anchor, not the live position. A rebuild walks every chunk
        // section in the cube, so keying it on a position that drifts every tick would throw the
        // cache away every tick and undo the point of caching it at all. The vertical band is taken
        // from the anchor too, or bobbing in water would rebuild even while standing put.
        int y = scanCentre.getY();
        // A player can spot a target above or below the current floor while looking around - a lava
        // pool in a cave is the important example. Restricting this to y +/- 2 made the shared
        // explorer silently miss visible targets in ravines, caves, fortresses and ore layers even
        // though Vision would have accepted their ray cast. Keep the same configured radius in all
        // three axes; TargetIndex still prunes empty chunk sections and Vision rejects anything
        // outside the actual view cone/line of sight.
        int yMin = Math.max(ctx.level.getMinY(), y - verticalLimit);
        int yMax = Math.min(ctx.level.getMaxY() - 1, y + verticalLimit);
        if (!index.isUsable(scanCentre, targets, scanLimit, yMin, yMax)) {
            index.rebuild(ctx.level, scanCentre, targets, scanLimit, yMin, yMax);
        }
        ctx.debug.searchAnchor = scanCentre;
        ctx.debug.searchCandidates = index.size();

        java.util.function.BiPredicate<BlockPos, BlockState> inSight =
                (pos, state) -> targetFilter.test(state)
                        && (ctx.omniscientMining() || Vision.isVisible(ctx, pos));
        BlockPos visible = maxChecks > 0
                ? index.nearestBounded(ctx.level, scanCentre, Set.of(), inSight, maxChecks)
                : index.nearest(ctx.level, scanCentre, Set.of(), inSight);
        if (visible != null) {
            BlockMemory.get().remember(visible, ctx.level.getBlockState(visible).getBlock());
        }
        return visible;
    }

    private TaskStatus startWalk(BotContext ctx) {
        if (attempts >= maxAttempts) {
            status.set("lune.status.explore.searched_stops_found_nothing", attempts);
            ctx.debug.decide("give up: search stop limit reached");
            return TaskStatus.FAILED;
        }

        BlockPos start = ctx.player.blockPosition();
        float heading = chooseHeading(ctx);
        int step = stepDistance * ((attempts / 4) + 1);

        int x = start.getX() + Math.round(-Mth.sin(heading * Mth.DEG_TO_RAD) * step);
        int z = start.getZ() + Math.round(Mth.cos(heading * Mth.DEG_TO_RAD) * step);
        // Put the goal on the ground rather than at the bot's own Y. Standing in water or at the
        // foot of a hill, the bot's Y is either underwater or buried in the slope, and the
        // pathfinder can only answer a goal like that with an empty path.
        BlockPos goalPos = BiomeScout.groundAt(ctx.level, x, z, start.getY());

        // Biome scoring is a direction hint, not permission to swim. A loaded river/ocean column
        // can still be the best sample when every nearby biome is barren for the requested block;
        // do not hand that water waypoint to a no-swim GotoTask and then let its recovery climb or
        // tunnel at the shoreline. Count it as one examined stop and choose a fresh direction.
        if (!MovementHelper.canStandAt(ctx.level, goalPos, false)) {
            attempts++;
            tryAlternateHeading = true;
            resetScan(ctx);
            status.set("lune.status.explore.way_water_choosing_another_walkable");
            return TaskStatus.RUNNING;
        }

        Goal goal = new Goals.Near(goalPos, 2);
        // Explore only walks; it must not dig or tunnel through sand/terrain.
        walk = new GotoTask(goal, true, false);
        walk.start(ctx);
        ctx.debug.intent = "walking a committed search heading";
        ctx.debug.searchAttempt = attempts;
        ctx.debug.searchLimit = maxAttempts;
        ctx.debug.searchHeading = travelStatus().text();
        ctx.debug.giveUp = "search stops " + attempts + "/" + maxAttempts;
        ctx.debug.decide("walk " + travelStatus().text() + "; rescan after reaching "
                + goalPos.toShortString());
        status.set(travelStatus());
        return TaskStatus.RUNNING;
    }

    /** Records a heading as walked and returns it, so the next choice can avoid undoing it. */
    private float rememberHeading(float yaw) {
        olderYaw = previousYaw;
        previousYaw = yaw;
        return yaw;
    }

    /**
     * Decides which way to walk next.
     * <p>
     * Once a heading is chosen it is kept for several stops. Re-deciding at every stop
     * is what made the old four-direction cycle wander in circles; a player who decides to try west
     * actually goes west for a while before admitting it was the wrong call.
     */
    private float chooseHeading(BotContext ctx) {
        if (tryAlternateHeading) {
            tryAlternateHeading = false;
            float base = committedYaw != null
                    ? committedYaw
                    : Direction.from2DDataValue(directionIndex % 4).toYRot();
            directionIndex++;
            // Turning ninety degrees off a blocked route never reverses it on its own, but two
            // corrections in a row do - and that is the spin. The guard sees the older heading too.
            // The blocked headings are held separately: the reversal guard would happily alternate
            // between two directions that are both walls, which on a coastline it did.
            committedYaw = rememberHeading(ExplorePolicy.avoidDoublingBack(
                    ExplorePolicy.avoidBlocked(Mth.wrapDegrees(base + 90.0F),
                            blockedYaw, olderBlockedYaw),
                    previousYaw, olderYaw));
            headingReason.set("lune.status.explore.previous_route_blocked");
            stepsOnHeading = 1;
            return committedYaw;
        }
        if (committedYaw != null
                && stepsOnHeading < ExplorePolicy.commitSteps(searchStrategy)) {
            stepsOnHeading++;
            return committedYaw + (random.nextFloat() - 0.5F) * DRIFT_DEGREES;
        }

        Optional<BiomeScout.Heading> pick = smartDirection
                ? BiomeScout.choose(ctx, targets, committedYaw, committedYaw)
                : Optional.empty();

        if (pick.isPresent()) {
            // The blocked headings apply here too. Biome knowledge is about what is *worth*
            // walking to, and knows nothing about whether the route there exists - so left to
            // itself it recommends the taiga to the west, the route fails on the water in the way,
            // and it recommends the forest to the north-west, then the taiga again. That pair
            // alternated for the whole of a flint phase.
            BiomeScout.Heading heading = pick.get();
            committedYaw = rememberHeading(ExplorePolicy.avoidDoublingBack(
                    ExplorePolicy.avoidBlocked(heading.yaw(), blockedYaw, olderBlockedYaw),
                    previousYaw, olderYaw));
            headingReason.set(heading.reason());
            stepsOnHeading = 1;
            return committedYaw;
        }

        // Nothing loaded to judge by, or a target biome knowledge has no opinion about. Fall back
        // to the plain outward spiral over the four compass directions.
        Direction direction = Direction.from2DDataValue(directionIndex % 4);
        directionIndex++;
        committedYaw = rememberHeading(ExplorePolicy.avoidDoublingBack(
                ExplorePolicy.avoidBlocked(direction.toYRot(), blockedYaw, olderBlockedYaw),
                previousYaw, olderYaw));
        headingReason.clear();
        stepsOnHeading = 1;
        return committedYaw;
    }

    private StatusText travelStatus() {
        if (committedYaw == null) {
            return travel.set("lune.status.explore.exploring");
        }
        String where = BiomeScout.compassKey(committedYaw);
        return headingReason.isBlank()
                ? travel.set("lune.status.explore.heading", Lang.get(where))
                : travel.set("lune.status.explore.heading_because", Lang.get(where),
                        headingReason);
    }

    private BlockPos findTarget(BotContext ctx) {
        BlockPos centre = ctx.player.blockPosition();
        boolean areaCheck = checkAround && !ctx.omniscientMining();

        // Keep the head moving, but scan on every tick either way. A player notices a tree as it
        // swings into view, not only once their head has come to a stop.
        boolean settled = true;
        if (areaCheck && !Vision.isPanoramic()
                && (headScanner.isTurning() || headScanner.isVerticalGlance())) {
            settled = headScanner.tickTurn(ctx);
            status.set(headScanner.statusLine());
        }

        ctx.debug.intent = "scanning loaded blocks for " + targetNames();
        ctx.debug.searchAttempt = attempts;
        ctx.debug.searchLimit = maxAttempts;
        ctx.debug.searchHeading = headScanner.status();
        ctx.debug.giveUp = "search stops " + attempts + "/" + maxAttempts;

        // Restart the scan only on a real move, not on any change of block position. Treading water
        // or sliding down a slope shifts the block position every tick, and restarting on that
        // would reset the scan forever and leave the bot turning on the spot without ever deciding
        // to walk. A player who shuffles a block does not start their search over either.
        BlockPos visible = visibleTarget(ctx, RESCAN_DISTANCE);
        if (visible != null) {
            return visible;
        }

        // Do not let an immature/otherwise unusable block hide a useful candidate behind it. The
        // indexed candidate shown in the overlay should satisfy the caller's state condition before
        // we spend more scan stops turning toward it.
        BlockPos candidate = index.nearest(ctx.level, scanCentre, Set.of(),
                (pos, state) -> targetFilter.test(state));
        if (candidate != null) {
            lastCandidate = candidate.immutable();
            var sight = Vision.inspect(ctx, candidate);
            String verdict = sight.verdict();
            if (!ctx.omniscientMining() && !sight.inView()) {
                // A player who notices a mature crop behind their shoulder turns toward it before
                // giving up on the farm. Stop the scanner's vertical glance for this short turn, but
                // still require Vision to accept the target before Explore returns it to the caller.
                Vec3 aim = Vision.blockAimPoint(ctx, candidate);
                headScanner.finish();
                ctx.look.lookAt(ctx.player, aim);
                status.set("lune.status.explore.turning_toward_behind_current_view", targetNames());
                ctx.debug.decide("candidate is out of view; turning toward it before widening scan");
                return null;
            }
            ctx.debug.target(ctx.level.getBlockState(candidate).getBlock().getName().getString(),
                    candidate, verdict);
            ctx.debug.decide("candidate exists, but is not actionable yet; keep looking/turning");
        } else {
            ctx.debug.target("matching block", null, "none in loaded scan cube");
            ctx.debug.decide("no matching block indexed; walk to the next committed heading");
        }

        if (!settled) {
            return null;
        }

        if (areaCheck && !Vision.isPanoramic() && headScanner.advance()) {
            status.set(headScanner.statusLine());
            return null;
        }

        // This look found nothing. Widen it - check the sides, then behind - before giving up on
        // this spot and walking somewhere else, which is the expensive option.
        if (areaCheck && !Vision.isPanoramic() && headScanner.escalate(ctx.player)) {
            status.set("lune.status.explore.nothing_ahead_taking_proper_look_around");
            return null;
        }

        // Walking back to something already seen does not require seeing it again from here; only
        // the original sighting had to be honest, and it was. Demanding current line of sight made
        // the memory useless exactly when it mattered - after the approach had moved the bot and
        // taken the target out of view.
        BlockPos memory = BlockMemory.get().findNearest(ctx, centre, targets, radius);
        if (memory != null) {
            ctx.debug.target("remembered " + ctx.level.getBlockState(memory).getBlock()
                    .getName().getString(), memory, "seen earlier from elsewhere");
            ctx.debug.memory = BlockMemory.get().size() + " remembered positions; returning to this one";
            ctx.debug.decide("go back to the target spotted earlier instead of walking farther");
            return memory;
        }

        ctx.debug.memory = BlockMemory.get().size() + " remembered positions; none currently usable";

        scanDone = true;
        return null;
    }

    private String targetNames() {
        return targets.stream()
                .map(block -> block.getName().getString())
                .sorted()
                .limit(3)
                .reduce((a, b) -> a + ", " + b)
                .orElse("targets");
    }

    private static String radiusBucket(int radius) {
        if (radius <= 32) {
            return "local";
        }
        if (radius <= 96) {
            return "regional";
        }
        return "long-range";
    }

    private void resetScan(BotContext ctx) {
        scanCentre = null;
        scanDone = false;
        index.invalidate();
        headScanner.reset(ctx.player);
    }

    private void stopWalk(BotContext ctx) {
        if (walk != null) {
            walk.stop(ctx);
            walk = null;
        }
    }
}
