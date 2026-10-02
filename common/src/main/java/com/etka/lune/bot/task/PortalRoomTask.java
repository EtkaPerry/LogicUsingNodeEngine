package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.knowledge.StrongholdKnowledge;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.util.SightMap;
import com.etka.lune.bot.util.Vision;
import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.Discovery;
import com.etka.lune.waypoint.DiscoveryStore;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gets into a stronghold that has already been found and walks it until the End portal is in
 * front of the bot.
 *
 * <p>An eye of ender only ever points at where a stronghold starts: the spiral staircase in its
 * first chunk. The portal room is somewhere else, several rooms in, and nothing points at it. A
 * player finds it by going down and looking, and so does this card. It digs down into the
 * staircase at the middle of that first chunk, and from there it walks the corridors, choosing
 * each next step only from what its eyes have passed through - every look is written into a
 * {@link SightMap}, and {@link PortalRoomSearch} picks where to go from that alone. It knows it
 * has arrived when it sees a portal frame, never before.</p>
 *
 * <p>The stronghold comes from the one Find Stronghold remembered, or from whoever built the card
 * with one in hand. Without either it fails at once: finding the stronghold is that card's job.
 * Silverfish, lava and whatever else lives down there are Self Preservation's.</p>
 */
public final class PortalRoomTask implements Task {

    /** How far a look carries the way the head points: the length of a long corridor. */
    private static final double SIGHT_RANGE = 32.0;
    /** Within this, sight needs no turned head - the same close quarters {@link Vision} allows. */
    private static final double CLOSE_RANGE = 6.0;
    private static final int SCAN_INTERVAL = 3;
    /** Digging goes down this far at a time, then asks again. */
    private static final int DESCENT_STEP = 12;
    /**
     * The game sinks every stronghold until its top is ten blocks under sea level, so nothing above
     * that is one - and a ruin on the surface, stone bricks and all, is never taken for it.
     */
    private static final int UNDER_SEA_LEVEL = 8;
    /** Frames tried and failed before admitting the room cannot be got into from here. */
    private static final int MAX_UNREACHABLE_FRAMES = 3;
    /**
     * How far from a drowned staircase to look for dry ground to dig in from, in blocks.
     *
     * <p>Two of the first measured strongholds had water over the staircase - a lake in one, open
     * sea in the other - and digging down from inside water is a flooded shaft and no air. A player
     * walks to the shore and digs in sideways, under the water, and so does this; the route search
     * already refuses to break a block that holds water back.</p>
     */
    private static final int SHORE_SEARCH = 48;
    /**
     * Rock kept between a tunnel and the water over it. The first dig in from a shore took the
     * cheapest line, which hugged the lake bed at one block of sand; it flooded, and then refused -
     * rightly - to break the sand holding the rest of the lake back.
     */
    private static final int UNDER_THE_BED = 6;
    private static final int EXPLORE_LIMIT_TICKS = 20 * 60 * 15;
    private static final double FRAME_REACH = 3.5;
    /** Near enough to a frame it cannot quite reach to count as standing in the room. */
    private static final double IN_THE_ROOM = 6.0;
    private static final int LEAD_RADIUS = 2;
    /**
     * When nothing in sight is left to walk to, dig down this far and look again, this many times.
     * The start staircase is where it shows: its spiral hides the steps below from the top, and
     * its corridor only begins at the bottom.
     */
    private static final int DEEPER_STEP = 4;
    private static final int MAX_DEEPER = 6;
    /**
     * Failed dig legs in a row, none getting two blocks deeper than the last, before the way down is
     * given up. One leg timing out on a detour round a cave ended a run thirty-six blocks into a
     * forty-block dig.
     */
    private static final int MAX_DIG_FAILURES_HERE = 3;
    /** How often, in blocks walked, a walked-past mark is left. */
    private static final double MARK_SPACING = 3.0;

    private static final float[] AHEAD_YAWS = {-54, -42, -30, -18, -6, 6, 18, 30, 42, 54};
    private static final float[] AHEAD_PITCHES = {-30, -18, -6, 6, 18, 30};
    /** Up, level, down, and steeply down: the last two see the floor at arm's length and a stairwell. */
    private static final float[] AROUND_PITCHES = {-40, -5, 30, 55, 80};
    private static final int AROUND_STEPS = 12;

    private enum Phase { LOCATE, APPROACH, DESCEND, EXPLORE, CLOSE_IN, DONE }

    private final BlockPos given;
    private final StatusText status = new StatusText();
    private final HeadScanner scanner = new HeadScanner(HeadScanner.Style.GLANCE);

    private Phase phase = Phase.LOCATE;
    private int centreX;
    private int centreZ;
    /** Where the digging starts: the staircase column, or the nearest dry ground to it. */
    private BlockPos entry;
    /** How deep to dig straight down at a shore before heading under the water; none when dry. */
    private int safeDepth = Integer.MAX_VALUE;
    private SightMap sight;
    private PortalRoomSearch search;
    private GotoTask leg;
    private BlockPos legTarget;
    private boolean lookingRound;
    private int frontierBeforeLook;
    private final List<BlockPos> frames = new ArrayList<>();
    private final Set<BlockPos> unreachableFrames = new HashSet<>();
    private final Set<BlockPos> checkedLeads = new HashSet<>();
    private BlockPos lead;
    /** What the current close-in walk is heading for: a frame, or a lead to look round from. */
    private BlockPos closingOn;
    private boolean closingOnFrame;
    private BlockPos lastMark;
    /** The spot just walked to, done with once the look round from it is over. */
    private BlockPos visiting;
    private double headingX;
    private double headingZ;
    private int scanCooldown;
    private int exploreTicks;
    private int deeperDigs;
    private int digFailuresHere;
    private int lowestAtDigFailure;
    /**
     * The last dig leg failed, so the next one goes three blocks straight down where the bot is.
     * A retry of the same leg plans the same route: one run pressed three times at a diagonal step
     * down it could not take, thirty-six blocks into the dig, and gave up there.
     */
    private boolean digStraightNext;
    /** The current leg is a dig down to look again, not a walk to a spot. */
    private boolean diggingDeeper;
    /**
     * Whether the eyes have reached a stone brick floor yet. A cobbled floor on its own is not
     * enough to stop digging for: a dungeon is cobbled too, and one met on the way down would
     * otherwise be explored as if it were the stronghold.
     */
    private boolean bricksSeen;
    /**
     * Every block the bot's own feet have been in on the way down. Standing on the staircase's roof
     * puts a stone brick floor under a cell the eyes have seen - the bottom of the bot's own shaft -
     * and that is not seeing into the stronghold. The first run to dig all the way down stopped
     * there, looked round a hole of its own making, and gave up.
     */
    private final Set<Long> shaft = new HashSet<>();

    /**
     * @param corner the chunk corner an eye of ender pointed at, or null to use the stronghold
     *               remembered for this world
     */
    public PortalRoomTask(BlockPos corner) {
        this.given = corner == null ? null : corner.immutable();
    }

    @Override
    public String name() {
        return Lang.get("lune.task.portal_room.name");
    }

    /** English on purpose: this is the learner's row key, and is never shown. */
    @Override
    public String learningId() {
        return Task.learningName("Find Portal Room");
    }

    /** Exploring is not measured as one skill; the walks and digs inside it are. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        phase = Phase.LOCATE;
        entry = null;
        safeDepth = Integer.MAX_VALUE;
        sight = null;
        search = null;
        leg = null;
        legTarget = null;
        lookingRound = false;
        frames.clear();
        unreachableFrames.clear();
        checkedLeads.clear();
        lead = null;
        closingOn = null;
        lastMark = null;
        visiting = null;
        headingX = 0.0;
        headingZ = 0.0;
        scanCooldown = 0;
        exploreTicks = 0;
        deeperDigs = 0;
        digFailuresHere = 0;
        lowestAtDigFailure = Integer.MAX_VALUE;
        digStraightNext = false;
        diggingDeeper = false;
        bricksSeen = false;
        shaft.clear();
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (phase == Phase.DESCEND) {
            shaft.add(MovementHelper.feetPosition(ctx.player).asLong());
        }
        if (phase == Phase.DESCEND || phase == Phase.EXPLORE || phase == Phase.CLOSE_IN) {
            lookIfDue(ctx);
        }
        return switch (phase) {
            case LOCATE -> locate(ctx);
            case APPROACH -> approach(ctx);
            case DESCEND -> descend(ctx);
            case EXPLORE -> explore(ctx);
            case CLOSE_IN -> closeIn(ctx);
            case DONE -> TaskStatus.SUCCESS;
        };
    }

    private TaskStatus locate(BotContext ctx) {
        BlockPos corner = given;
        if (corner == null) {
            corner = DiscoveryStore.get()
                    .find(Discovery.STRUCTURE, EnderEyeTask.STRONGHOLD, ctx.level.dimension().identifier().toString())
                    .map(found -> new BlockPos(found.x(), 0, found.z()))
                    .orElse(null);
        }
        if (corner == null) {
            return fail("lune.status.portal_room.no_stronghold_known");
        }
        centreX = corner.getX() + StrongholdKnowledge.STAIRCASE_OFFSET;
        centreZ = corner.getZ() + StrongholdKnowledge.STAIRCASE_OFFSET;
        sight = new SightMap();
        BlockPos feet = MovementHelper.feetPosition(ctx.player);
        search = new PortalRoomSearch(centreX, centreZ, ctx.level.getSeaLevel() - UNDER_SEA_LEVEL);
        if (inside(ctx, feet)) {
            // Started again part way through: carry on from here rather than from the surface.
            beginExploring(ctx);
            return TaskStatus.RUNNING;
        }
        phase = Phase.APPROACH;
        return approach(ctx);
    }

    /**
     * The staircase column when there is dry ground over it, otherwise the nearest dry ground within
     * {@link #SHORE_SEARCH}, or null when there is none - open sea.
     *
     * <p>Read from the surface the way the shore is seen from the water: the first solid or fluid
     * block from the sky down, trees looked through, in loaded chunks only.</p>
     */
    private BlockPos dryEntry(BotContext ctx) {
        for (int ring = 0; ring <= SHORE_SEARCH; ring++) {
            BlockPos best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = centreX + dx;
                    int z = centreZ + dz;
                    if (!ctx.level.hasChunkAt(new BlockPos(x, ctx.level.getSeaLevel(), z))) {
                        continue;
                    }
                    BlockPos feet = new BlockPos(x,
                            ctx.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    if (!ctx.level.getFluidState(feet.below()).isEmpty()
                            || !MovementHelper.canStandAt(ctx.level, feet, false)) {
                        continue;
                    }
                    int distance = dx * dx + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = feet;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * The lowest the bed of the water goes between the shore and the staircase, sampled a block at
     * a time along the line and round the staircase itself: what the tunnel across has to stay
     * under.
     */
    private int lowestBedBetween(BotContext ctx, BlockPos shore) {
        int lowest = Integer.MAX_VALUE;
        int steps = Math.max(Math.abs(shore.getX() - centreX), Math.abs(shore.getZ() - centreZ));
        for (int step = 0; step <= steps; step++) {
            double t = steps == 0 ? 0.0 : (double) step / steps;
            int x = (int) Math.round(shore.getX() + (centreX - shore.getX()) * t);
            int z = (int) Math.round(shore.getZ() + (centreZ - shore.getZ()) * t);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    lowest = Math.min(lowest, bedAt(ctx, x + dx, z + dz));
                }
            }
        }
        return lowest;
    }

    /** The first block under any water at a column, looked for from the surface down. */
    private int bedAt(BotContext ctx, int x, int z) {
        int y = ctx.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        while (y > ctx.level.getMinY() && !ctx.level.getFluidState(new BlockPos(x, y, z)).isEmpty()) {
            y--;
        }
        return y;
    }

    /** Standing on a stronghold floor with its walls in arm's reach, near enough to be this one. */
    private boolean inside(BotContext ctx, BlockPos feet) {
        if (Math.abs(feet.getX() - centreX) > StrongholdKnowledge.REACH
                || Math.abs(feet.getZ() - centreZ) > StrongholdKnowledge.REACH
                || !StrongholdKnowledge.isFloor(ctx.level.getBlockState(feet.below()))) {
            return false;
        }
        BlockPos head = feet.above();
        return StrongholdKnowledge.isMasonry(ctx.level.getBlockState(head.above()))
                || StrongholdKnowledge.isMasonry(ctx.level.getBlockState(head.north()))
                || StrongholdKnowledge.isMasonry(ctx.level.getBlockState(head.south()))
                || StrongholdKnowledge.isMasonry(ctx.level.getBlockState(head.east()))
                || StrongholdKnowledge.isMasonry(ctx.level.getBlockState(head.west()));
    }

    private TaskStatus approach(BotContext ctx) {
        if (entry == null) {
            entry = dryEntry(ctx);
            if (entry == null) {
                return fail("lune.status.portal_room.under_water", SHORE_SEARCH);
            }
            if (entry.getX() != centreX || entry.getZ() != centreZ) {
                safeDepth = lowestBedBetween(ctx, entry) - UNDER_THE_BED;
            }
        }
        BlockPos feet = MovementHelper.feetPosition(ctx.player);
        // On the spot itself: one block off it can be the water beside the shore, and digging
        // from there is exactly what the spot was chosen to avoid.
        if (feet.getX() == entry.getX() && feet.getZ() == entry.getZ()
                && !MovementHelper.isWater(ctx.level, feet)) {
            stopLeg(ctx);
            phase = Phase.DESCEND;
            return TaskStatus.RUNNING;
        }
        if (leg == null) {
            // May swim: the bot often arrives standing in the lake over the staircase.
            startLeg(ctx, new Goals.Block(entry), null, true, false);
        }
        TaskStatus result = leg.tick(ctx);
        status.set("lune.status.portal_room.walking_to_stronghold", centreX, centreZ);
        if (result == TaskStatus.FAILED) {
            stopLeg(ctx);
            return fail("lune.status.ender_eye.cannot_reach_stronghold", centreX, centreZ);
        }
        if (result == TaskStatus.SUCCESS) {
            stopLeg(ctx);
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus descend(BotContext ctx) {
        if (bricksSeen || !frames.isEmpty()) {
            // The eyes are inside: the digging is done, and the looking starts.
            stopLeg(ctx);
            beginExploring(ctx);
            return TaskStatus.RUNNING;
        }
        if (leg == null) {
            BlockPos here = MovementHelper.feetPosition(ctx.player);
            int feetY = here.getY();
            if (digStraightNext && feetY - 3 > ctx.level.getMinY() + 1) {
                digStraightNext = false;
                startLeg(ctx, new Goals.Below(here.getX(), here.getZ(), 0, feetY - 3), null, false, true);
            } else if (feetY > safeDepth) {
                // Straight down at the shore first, so the way across is well under the water.
                startLeg(ctx, new Goals.Below(entry.getX(), entry.getZ(), 1, safeDepth), null, false, true);
            } else {
                int target = feetY - DESCENT_STEP;
                if (target <= ctx.level.getMinY() + 1) {
                    return fail("lune.status.portal_room.reached_bottom");
                }
                startLeg(ctx, new Goals.Below(centreX, centreZ, 1, target), null, false, true);
            }
        }
        TaskStatus result = leg.tick(ctx);
        status.set("lune.status.portal_room.digging_down", leg.statusLine());
        if (result == TaskStatus.FAILED) {
            StatusText why = new StatusText().set(leg.statusLine());
            stopLeg(ctx);
            int y = MovementHelper.feetPosition(ctx.player).getY();
            if (y < lowestAtDigFailure - 1) {
                digFailuresHere = 0;
            }
            lowestAtDigFailure = Math.min(lowestAtDigFailure, y);
            if (++digFailuresHere >= MAX_DIG_FAILURES_HERE) {
                return fail("lune.status.portal_room.cannot_dig_down", why);
            }
            // The next tick plans a different leg from wherever this one left off.
            digStraightNext = true;
            ctx.debug.decide("dig leg failed at y " + y + "; going straight down here first ("
                    + digFailuresHere + "/" + MAX_DIG_FAILURES_HERE + ")");
            return TaskStatus.RUNNING;
        }
        if (result == TaskStatus.SUCCESS) {
            stopLeg(ctx);
        }
        return TaskStatus.RUNNING;
    }

    private byte seen(long cell) {
        return sight.state(cell);
    }

    private void beginExploring(BotContext ctx) {
        // The way in is not somewhere to explore: its bottom has a brick floor, and going back up
        // to stand on the roof was the first thing a run inside ever did.
        for (long cell : shaft) {
            search.exclude(BlockPos.of(cell));
        }
        phase = Phase.EXPLORE;
        exploreTicks = 0;
        lastMark = MovementHelper.feetPosition(ctx.player);
        startLookingRound(ctx);
    }

    private TaskStatus explore(BotContext ctx) {
        if (++exploreTicks > EXPLORE_LIMIT_TICKS) {
            return fail("lune.status.portal_room.took_too_long", EXPLORE_LIMIT_TICKS / (20 * 60));
        }
        BlockPos frame = nearestFrame(ctx);
        if (frame != null) {
            return beginClosingIn(ctx, frame, true);
        }
        if (lead != null && leg == null && !lookingRound) {
            BlockPos checking = lead;
            lead = null;
            checkedLeads.add(checking);
            return beginClosingIn(ctx, checking, false);
        }
        if (leg != null) {
            TaskStatus result = leg.tick(ctx);
            markWalked(ctx);
            if (result == TaskStatus.RUNNING) {
                status.set(diggingDeeper ? "lune.status.portal_room.digging_down"
                        : "lune.status.portal_room.exploring", leg.statusLine());
                return TaskStatus.RUNNING;
            }
            stopLeg(ctx);
            diggingDeeper = false;
            if (result == TaskStatus.FAILED && legTarget != null) {
                // Behind something the route could not get through: somewhere else first.
                ctx.debug.decide("explore: no way to " + legTarget.getX() + "," + legTarget.getY() + ","
                        + legTarget.getZ() + "; refused");
                search.refuse(legTarget);
                status.set("lune.status.portal_room.could_not_get_there");
                return TaskStatus.RUNNING;
            }
            visiting = legTarget;
            startLookingRound(ctx);
            return TaskStatus.RUNNING;
        }
        if (lookingRound) {
            lookRound(ctx);
            return TaskStatus.RUNNING;
        }
        BlockPos feet = MovementHelper.feetPosition(ctx.player);
        BlockPos next = search.next(feet, headingX, headingZ);
        if (next == null) {
            int deeper = feet.getY() - DEEPER_STEP;
            if (deeperDigs < MAX_DEEPER && deeper > ctx.level.getMinY() + 1) {
                deeperDigs++;
                ctx.debug.decide("explore: nothing in sight to walk to; digging down to look again ("
                        + deeperDigs + "/" + MAX_DEEPER + ")");
                startLeg(ctx, new Goals.Below(feet.getX(), feet.getZ(), 1, deeper), null, false, true);
                diggingDeeper = true;
                status.set("lune.status.portal_room.digging_down", leg.statusLine());
                return TaskStatus.RUNNING;
            }
            ctx.debug.decide("explore: nothing left to walk to; " + sight.size() + " cells seen");
            return fail("lune.status.portal_room.explored_everything");
        }
        ctx.debug.decide("explore: " + search.frontierSize() + " spots to see; heading for "
                + next.getX() + "," + next.getY() + "," + next.getZ());
        legTarget = next;
        startLeg(ctx, goalFor(ctx, next), next, false, true);
        status.set("lune.status.portal_room.exploring", leg.statusLine());
        return TaskStatus.RUNNING;
    }

    /**
     * Near enough to see from, for an open spot; the doorway itself, for a door. Standing beside a
     * closed door sees nothing new, so a route to one has to go through it.
     */
    private Goal goalFor(BotContext ctx, BlockPos target) {
        boolean open = MovementHelper.isPassable(ctx.level, target)
                && MovementHelper.isPassable(ctx.level, target.above());
        return open ? new Goals.Near(target, 1) : new Goals.Block(target);
    }

    private void markWalked(BotContext ctx) {
        BlockPos feet = MovementHelper.feetPosition(ctx.player);
        double dx = feet.getX() - lastMark.getX();
        double dz = feet.getZ() - lastMark.getZ();
        double moved = Math.sqrt(dx * dx + dz * dz);
        if (moved < MARK_SPACING) {
            return;
        }
        headingX = dx / moved;
        headingZ = dz / moved;
        lastMark = feet;
        search.looked(feet, PortalRoomSearch.WALKED_PAST, this::seen);
    }

    private void startLookingRound(BotContext ctx) {
        scanner.reset(ctx.player);
        lookingRound = true;
        frontierBeforeLook = search.frontierSize();
        lookIfDue(ctx);
    }

    private void lookRound(BotContext ctx) {
        boolean settled = true;
        if (scanner.isTurning() || scanner.isVerticalGlance()) {
            settled = scanner.tickTurn(ctx);
        }
        status.set("lune.status.portal_room.looking_round", scanner.statusLine());
        if (!settled || scanner.advance()) {
            return;
        }
        // A glance that turned up nothing new is the moment to look all the way round.
        if (search.frontierSize() <= frontierBeforeLook && scanner.escalate(ctx.player)) {
            return;
        }
        lookingRound = false;
        search.looked(MovementHelper.feetPosition(ctx.player), PortalRoomSearch.LOOKED_ROUND, this::seen);
        if (visiting != null) {
            search.visited(visiting);
            visiting = null;
        }
    }

    private TaskStatus beginClosingIn(BotContext ctx, BlockPos target, boolean frame) {
        stopLeg(ctx);
        lookingRound = false;
        closingOn = target;
        closingOnFrame = frame;
        Goal goal = frame ? new Goals.Adjacent(target, FRAME_REACH) : new Goals.Near(target, LEAD_RADIUS);
        startLeg(ctx, goal, target, false, true);
        phase = Phase.CLOSE_IN;
        closingStatus(ctx);
        return TaskStatus.RUNNING;
    }

    private void closingStatus(BotContext ctx) {
        if (closingOnFrame) {
            status.set("lune.status.portal_room.portal_in_sight", leg.statusLine());
        } else {
            // What caught the eye, by the game's own name for it.
            status.set("lune.status.portal_room.something_ahead",
                    ctx.level.getBlockState(closingOn).getBlock().getName().getString(), leg.statusLine());
        }
    }

    private TaskStatus closeIn(BotContext ctx) {
        if (!closingOnFrame) {
            BlockPos frame = nearestFrame(ctx);
            if (frame != null) {
                return beginClosingIn(ctx, frame, true);
            }
        }
        TaskStatus result = leg.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            closingStatus(ctx);
            return TaskStatus.RUNNING;
        }
        stopLeg(ctx);
        if (closingOnFrame) {
            boolean there = result == TaskStatus.SUCCESS
                    || ctx.player.getEyePosition().distanceTo(Vec3.atCenterOf(closingOn)) <= IN_THE_ROOM;
            if (there) {
                status.set("lune.status.portal_room.found", closingOn.getX(), closingOn.getY(), closingOn.getZ());
                phase = Phase.DONE;
                return TaskStatus.SUCCESS;
            }
            unreachableFrames.add(closingOn);
            if (nearestFrame(ctx) == null || unreachableFrames.size() >= MAX_UNREACHABLE_FRAMES) {
                return fail("lune.status.portal_room.cannot_reach_portal");
            }
            phase = Phase.EXPLORE;
            return TaskStatus.RUNNING;
        }
        // Whatever caught the eye, look round from beside it: in the portal room that is enough to
        // bring the frames into view.
        phase = Phase.EXPLORE;
        lastMark = MovementHelper.feetPosition(ctx.player);
        startLookingRound(ctx);
        scanner.escalate(ctx.player);
        return TaskStatus.RUNNING;
    }

    private BlockPos nearestFrame(BotContext ctx) {
        Vec3 eye = ctx.player.getEyePosition();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos frame : frames) {
            if (unreachableFrames.contains(frame)) {
                continue;
            }
            double distance = eye.distanceToSqr(Vec3.atCenterOf(frame));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = frame;
            }
        }
        return best;
    }

    /**
     * Casts the bot's sight: a fan across where the head points, far, and a ring all round, near.
     * Everything the lines pass through goes into the map and on to the search; what they stop at
     * is checked for the one thing being looked for.
     */
    private void lookIfDue(BotContext ctx) {
        if (sight == null || search == null || --scanCooldown > 0) {
            return;
        }
        scanCooldown = SCAN_INTERVAL;
        Vec3 eye = ctx.player.getEyePosition();
        float yaw = ctx.player.getYRot();
        float pitch = ctx.player.getXRot();
        for (float yawOffset : AHEAD_YAWS) {
            for (float pitchOffset : AHEAD_PITCHES) {
                look(ctx, eye, Mth.clamp(pitch + pitchOffset, -89.0F, 89.0F), yaw + yawOffset, SIGHT_RANGE);
            }
        }
        for (int step = 0; step < AROUND_STEPS; step++) {
            for (float aroundPitch : AROUND_PITCHES) {
                look(ctx, eye, aroundPitch, step * (360.0F / AROUND_STEPS), CLOSE_RANGE);
            }
        }
    }

    private void look(BotContext ctx, Vec3 eye, float pitch, float yaw, double range) {
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(range));
        BlockHitResult hit = Vision.firstSeen(ctx, eye, end);
        BlockPos stopped = hit == null ? null : hit.getBlockPos().immutable();
        sight.trace(eye, hit == null ? end : hit.getLocation(), stopped,
                cell -> search.seen(cell, feet -> standable(ctx, feet)));
        if (stopped != null) {
            noticed(ctx, stopped);
        }
    }

    private void noticed(BotContext ctx, BlockPos pos) {
        BlockState state = ctx.level.getBlockState(pos);
        if (StrongholdKnowledge.isPortal(state)) {
            if (!frames.contains(pos)) {
                frames.add(pos);
            }
        } else if (StrongholdKnowledge.isPortalRoomSign(state)) {
            if (!checkedLeads.contains(pos) && lead == null) {
                lead = pos;
            }
        } else if (StrongholdKnowledge.isPassage(state)) {
            // A door stops the eyes, not the feet: the doorway is somewhere to go and look from.
            search.seen(pos.asLong(), feet -> standable(ctx, feet));
        }
    }

    /** Somewhere to stand on a stronghold floor, counting a door there as already out of the way. */
    private boolean standable(BotContext ctx, BlockPos feet) {
        if (shaft.contains(feet.asLong())) {
            return false;
        }
        BlockPos floor = feet.below();
        BlockState floorState = ctx.level.getBlockState(floor);
        if (!StrongholdKnowledge.isFloor(floorState) || !MovementHelper.isSolidFloor(ctx.level, floor)) {
            return false;
        }
        boolean room = clear(ctx, feet) && clear(ctx, feet.above());
        if (room && StrongholdKnowledge.isMasonry(floorState) && builtNotDug(ctx, feet)) {
            bricksSeen = true;
        }
        return room;
    }

    /**
     * Whether a cell is the stronghold's own space rather than the bot's way in. Its rooms and
     * corridors are generated as cave air, which a dug block never is; failing that, a cell at
     * least a step clear of the shaft - the one being dug next is always right beside it.
     */
    private boolean builtNotDug(BotContext ctx, BlockPos feet) {
        if (shaft.contains(feet.asLong())) {
            return false;
        }
        if (ctx.level.getBlockState(feet).is(net.minecraft.world.level.block.Blocks.CAVE_AIR)) {
            return true;
        }
        for (long cell : shaft) {
            BlockPos dug = BlockPos.of(cell);
            if (Math.abs(dug.getX() - feet.getX()) <= 1 && Math.abs(dug.getZ() - feet.getZ()) <= 1
                    && Math.abs(dug.getY() - feet.getY()) <= 2) {
                return false;
            }
        }
        return true;
    }

    private boolean clear(BotContext ctx, BlockPos pos) {
        return MovementHelper.isPassable(ctx.level, pos)
                || StrongholdKnowledge.isPassage(ctx.level.getBlockState(pos));
    }

    /**
     * @param dry never plan a swimming route. Every leg underground is dry: a route that falls
     *            back to swimming surfaces into the lake over the stronghold, and a stronghold's
     *            own fountain is not a way anywhere.
     */
    private void startLeg(BotContext ctx, Goal goal, BlockPos target, boolean sprint, boolean dry) {
        stopLeg(ctx);
        legTarget = target;
        // Digging is allowed on every leg: the way in is dug, and a stronghold's iron doors and
        // grates are only in the way of a route that may not break them. Its wooden doors are
        // opened, as every walk opens them (Doorways).
        leg = new GotoTask(goal, sprint, true);
        if (dry) {
            leg.keepDry();
        }
        leg.start(ctx);
    }

    private void stopLeg(BotContext ctx) {
        if (leg != null) {
            leg.stop(ctx);
            leg = null;
        }
    }

    private TaskStatus fail(String key, Object... args) {
        status.set(key, args);
        return TaskStatus.FAILED;
    }

    @Override
    public void onPause(BotContext ctx) {
        if (leg != null) {
            leg.onPause(ctx);
        }
        Task.super.onPause(ctx);
    }

    @Override
    public void onStop(BotContext ctx) {
        stopLeg(ctx);
        ctx.input.reset();
    }
}
