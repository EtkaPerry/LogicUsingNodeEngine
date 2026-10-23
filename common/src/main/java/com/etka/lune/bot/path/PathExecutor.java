package com.etka.lune.bot.path;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.util.BlockBreaker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Walks the player along a finished path by pressing virtual keys - it never teleports or sets
 * velocity directly, so movement stays subject to normal physics and normal server validation.
 * <p>
 * When the route was planned with breaking allowed, this also mines the blocks in the way. That
 * pairing is not optional: a path through rock that nobody digs is just a bot walking into a wall.
 * <p>
 * Steering is deliberately decoupled from looking. The view turns at a limited rate (see
 * {@link com.etka.lune.bot.input.LookController}), so a bot that only ever pressed "forward" would
 * walk the wrong way for several ticks after every sharp corner. The desired direction is instead
 * converted into the player's local frame and expressed as the full eight-way forward/back/strafe
 * combination, which moves correctly no matter where the head happens to point.
 */
public final class PathExecutor {

    /** How many nodes ahead an arrival check may jump, to absorb overshoot and corner-cutting. */
    private static final int LOOKAHEAD = 4;
    /** Ticks without getting closer to the current node before declaring the bot stuck. */
    private static final int STUCK_TICKS = 60;
    /** Distance improvement that counts as real progress, in blocks. */
    private static final double PROGRESS_EPSILON = 0.02;
    /** Squared distance to a node centre that counts as arrival even from the wrong block. */
    private static final double CENTRE_RADIUS_SQR = 0.25;
    /** Squared horizontal distance within which we count as standing in the node's column. */
    private static final double COLUMN_RADIUS_SQR = 0.36;
    /** How far up a breath looks for the top of the water before settling for what it found. */
    private static final int SURFACE_SEARCH = 16;
    /**
     * How far ahead a swimmer looks, at the least. Depth follows the pitch, and a waypoint a block
     * away pitches the head straight down or straight up; looking further along the same line
     * changes depth on a slope instead.
     */
    private static final double SWIM_LEAD = 3.0;
    /** How far under a water waypoint a swimmer may pass and still have passed it. */
    private static final int SWIM_UNDER_REACH = 4;
    /** A normal jump has less than this much fall distance; beyond it, a route has lost its floor. */
    private static final double UNEXPECTED_FALL_DISTANCE = 1.1;
    /** Give the player a short window to steer back onto the last safe route node. */
    private static final int FALL_RECOVERY_TICKS = 12;

    public enum Status {
        RUNNING,
        DONE,
        STUCK,
        /** A block is in the way that nothing in the hotbar can harvest. */
        NO_TOOL,
        /** Water or lava entered a route after it was planned; discard it immediately. */
        REPLAN,
        /** Continuing would open or enter a forbidden hazard. */
        HAZARD,
        /**
         * The server would not let a block on the route be broken. It is remembered as solid
         * ({@link com.etka.lune.bot.memory.RefusedBreaks}), so a fresh search goes round it.
         */
        REFUSED
    }

    private final List<BlockPos> path;
    private final Set<Long> initiallyWet = new HashSet<>();
    private final boolean tracksFluidChanges;
    /** Whether this route is allowed to enter water; dry routes use emergency escape instead. */
    private final boolean allowSwim;
    /** Whether this route may deliberately press jump for ascent, climbing, or a gap. */
    private final boolean allowJump;
    private final BlockBreaker breaker = new BlockBreaker();
    /** Opens the doors on the route and shuts them behind; null for a route that has none to mind. */
    private final DoorKeeper doors;

    private int index = 1; // path[0] is the block we're already standing on
    /** The node a gap jump under way is landing on, so the head stays on it across; -1 for none. */
    private int leapIndex = -1;
    /** The node a run-up under way is for, so a begun run is not called back to the middle; -1 for none. */
    private int runUpIndex = -1;
    private int noProgressTicks;
    private int fallRecoveryTicks;
    private double bestDistanceToTarget = Double.MAX_VALUE;
    private final StatusText blockedBy = new StatusText();
    private boolean unexpectedWaterRecovery;

    public PathExecutor(List<BlockPos> path) {
        this.path = path;
        this.tracksFluidChanges = false;
        this.allowSwim = true;
        this.allowJump = true;
        this.doors = null;
    }

    public PathExecutor(List<BlockPos> path, BlockGetter level) {
        this(path, level, true);
    }

    public PathExecutor(List<BlockPos> path, BlockGetter level, boolean allowSwim) {
        this(path, level, allowSwim, true);
    }

    public PathExecutor(List<BlockPos> path, BlockGetter level, boolean allowSwim,
                        boolean allowJump) {
        this(path, level, allowSwim, allowJump, null);
    }

    /**
     * @param doors the walk's own door keeper, which outlives this route: what it opened under
     *              this one is still to be shut under the next
     */
    public PathExecutor(List<BlockPos> path, BlockGetter level, boolean allowSwim,
                        boolean allowJump, DoorKeeper doors) {
        this.path = path;
        this.tracksFluidChanges = true;
        this.allowSwim = allowSwim;
        this.allowJump = allowJump;
        this.doors = doors;
        for (BlockPos pos : path) {
            if (MovementHelper.isWater(level, pos)) {
                initiallyWet.add(pos.asLong());
            }
        }
    }

    public List<BlockPos> getPath() {
        return path;
    }

    public BlockPos getCurrentTarget() {
        return index < path.size() ? path.get(index) : null;
    }

    public int getIndex() {
        return index;
    }

    public int getNoProgressTicks() {
        return noProgressTicks;
    }

    public int remainingNodes() {
        return Math.max(0, path.size() - index);
    }

    /**
     * The key of a {@link Status#REPLAN} asked for because the body was carried off the route, or is
     * cutting back onto it across a hole: the walk counts these ({@code GotoTask.offRouteReplans}).
     */
    public static final String OFF_ROUTE = "lune.status.route.off_route_replanning";

    /** Name of the block that triggered {@link Status#NO_TOOL}, for the failure message. */
    /** Why the route stopped, keyed so the owning task keeps the meaning. */
    public StatusText getBlockedBy() {
        return blockedBy;
    }

    public Status tick(BotContext ctx, boolean allowSprint, boolean allowBreak) {
        LocalPlayer player = ctx.player;
        if (WaterEscape.tick(ctx)) {
            breaker.stop(ctx);
            noProgressTicks = 0;
            blockedBy.set("lune.status.route.escaping_to_air");
            return Status.RUNNING;
        }
        if (path.size() <= 1) {
            return Status.DONE;
        }

        // A dry route can still be invalidated after planning by falling sand, fluid updates, or
        // another world change. Get out immediately while there is air left, then discard the stale
        // route. The next dry tick returns REPLAN so every caller gets a fresh route.
        if (!allowSwim && player.isInWater()) {
            if (!WaterEscape.hasBreathableExit(ctx.level, player.blockPosition())) {
                breaker.stop(ctx);
                blockedBy.set("lune.status.route.water_no_safe_exit");
                return Status.HAZARD;
            }
            WaterEscape.tickToAir(ctx);
            breaker.stop(ctx);
            noProgressTicks = 0;
            unexpectedWaterRecovery = true;
            blockedBy.set("lune.status.route.water_escaping");
            return Status.RUNNING;
        }
        if (unexpectedWaterRecovery) {
            unexpectedWaterRecovery = false;
            breaker.stop(ctx);
            blockedBy.set("lune.status.route.escaped_water_replanning");
            return Status.REPLAN;
        }

        int previousIndex = index;
        advance(ctx, player);
        if (index >= path.size()) {
            breaker.stop(ctx);
            return Status.DONE;
        }
        if (index != previousIndex) {
            // New node: progress is measured against it, not the one we just left.
            bestDistanceToTarget = Double.MAX_VALUE;
            noProgressTicks = 0;
        }

        BlockPos target = path.get(index);
        ctx.debug.movement(target, "route waypoint");
        ctx.debug.breaking(null, "", "");
        ctx.debug.obstruction = "";
        ctx.debug.obstructionPos = null;
        if (routeFluidChanged(ctx)) {
            breaker.stop(ctx);
            blockedBy.set("lune.status.route.fluid_replanning");
            return Status.REPLAN;
        }
        Vec3 centre = Vec3.atCenterOf(target);
        BlockPos feet = MovementHelper.feetPosition(player);
        int feetY = feet.getY();

        // A route may contain an explicit, bounded descent, but a player who is falling while the
        // next waypoint is level with or above them has lost the floor the route was planned on.
        // Continuing to press toward that waypoint turns a one-block navigation mistake into a
        // thirty-block fall. Release the stale route input and steer back toward the last safe
        // node for a few ticks; if that does not recover the ledge, let GotoTask replan from the
        // new position. This is shared by every job that follows a GotoTask route.
        if (unexpectedFall(player, target, feetY)) {
            breaker.stop(ctx);
            ctx.input.reset();
            BlockPos recovery = index > 0 ? path.get(index - 1) : player.blockPosition();
            Vec3 recoveryCentre = Vec3.atCenterOf(recovery);
            look(ctx, recoveryCentre, false);
            steer(player, ctx, recoveryCentre);
            ctx.input.sprint = false;
            ctx.input.jump = false;
            blockedBy.set("lune.status.route.unexpected_fall");
            if (fallRecoveryTicks++ >= FALL_RECOVERY_TICKS) {
                return Status.REPLAN;
            }
            return Status.RUNNING;
        }
        fallRecoveryTicks = 0;

        // A door on the next steps is opened, and one left behind is shut, before anything else is
        // done about the way ahead. Doors are opened, never dug; the clearing below skips them.
        if (doors != null) {
            DoorKeeper.Work work = doors.tick(ctx, feet,
                    path.subList(index, Math.min(path.size(), index + 3)));
            if (work == DoorKeeper.Work.REFUSED) {
                breaker.stop(ctx);
                blockedBy.set(doors.status());
                return Status.HAZARD;
            }
            if (work == DoorKeeper.Work.BUSY) {
                // Standing at a door is not a stall. It ends on its own: a door gets three clicks.
                breaker.stop(ctx);
                noProgressTicks = 0;
                return Status.RUNNING;
            }
        }

        // Clear the way before trying to walk into it. Mining counts as progress, so the stall
        // timer must not run while a block is being chewed through.
        //
        // A no-break route must never turn an ordinary route obstruction into a mining request. This
        // matters for Harvest: the farm's stairs, supports, and irrigation edges are part of the
        // player's floor and are not disposable just because the route starts beside them. If the
        // body is genuinely clipped into a block, replan from the normalized feet position; the
        // shared movement task can then choose an open exit or report that it is trapped.
        BlockPos obstruction = allowBreak
                ? findObstruction(ctx, target)
                : MovementHelper.blockedBodyPos(ctx.level, MovementHelper.feetPosition(ctx.player),
                        ctx.player.getOnPos(), ctx.player.onGround());
        {
            if (obstruction != null) {
                String blockName = ctx.level.getBlockState(obstruction).getBlock()
                        .getName().getString();
                ctx.debug.obstruction = blockName;
                ctx.debug.obstructionPos = obstruction.immutable();
                ctx.debug.breaking(obstruction, blockName, "route obstruction");
            }
            if (obstruction != null && !allowBreak) {
                breaker.stop(ctx);
                blockedBy.set("lune.status.route.blocked_by",
                        ctx.level.getBlockState(obstruction).getBlock().getName().getString());
                ctx.debug.decide("route blocked; replanning without digging");
                return Status.REPLAN;
            }
            if (obstruction != null && !allowSwim
                    && MovementHelper.wouldOpenWater(ctx.level, obstruction)) {
                breaker.stop(ctx);
                blockedBy.set("lune.status.route.refusing_open_water");
                return Status.HAZARD;
            }
            if (obstruction != null && MovementHelper.nearLava(ctx.level, obstruction)
                    && !ctx.player.isInLava()) {
                breaker.stop(ctx);
                blockedBy.set("lune.status.route.refusing_dig_beside_lava");
                return Status.HAZARD;
            }
            if (obstruction != null && !breaker.isOutOfReach(ctx, obstruction)) {
                BlockBreaker.Progress progress = breaker.tick(ctx, obstruction, true);
                if (progress == BlockBreaker.Progress.NO_TOOL) {
                    blockedBy.set("lune.status.route.needs_better_tool",
                            ctx.level.getBlockState(obstruction).getBlock().getName().getString());
                    ctx.debug.breaking(obstruction, blockedBy.text(), "no suitable tool");
                    ctx.debug.decide("cannot clear " + blockedBy.text()
                            + "; route needs a better tool");
                    return Status.NO_TOOL;
                }
                if (progress == BlockBreaker.Progress.HAZARD) {
                    blockedBy.set(breaker.getFailureReason());
                    ctx.debug.breaking(obstruction, ctx.debug.breakBlock, blockedBy.text());
                    ctx.debug.decide("stopped breaking: " + blockedBy.text());
                    return breaker.wasRefused() ? Status.REFUSED : Status.HAZARD;
                }
                if (progress == BlockBreaker.Progress.WORKING) {
                    // Mining is progress. Turning toward a block is not: the aim tolerance can go
                    // unmet indefinitely - a block directly under the feet needs a near-vertical
                    // pitch and has no stable yaw - and resetting the stall timer for it let the
                    // bot stand and swivel forever without ever swinging.
                    if (breaker.isDestroying()) {
                        noProgressTicks = 0;
                    } else {
                        noProgressTicks++;
                    }
                    holdWhileBreaking(ctx, player, target.getY() > feetY);
                    return noProgressTicks > STUCK_TICKS ? Status.STUCK : Status.RUNNING;
                }
            }
        }
        if (obstruction == null) {
            breaker.stop(ctx);
        }

        double dx = centre.x - player.getX();
        double dz = centre.z - player.getZ();
        boolean inColumn = dx * dx + dz * dz < COLUMN_RADIUS_SQR;
        boolean descending = target.getY() < feetY;
        boolean climbing = target.getY() > feetY;
        boolean onClimbable = MovementHelper.isClimbable(ctx.level, feet)
                || MovementHelper.isClimbable(ctx.level, feet.above())
                || MovementHelper.isClimbable(ctx.level, target);

        // Standing on solid ground directly on top of the block we're supposed to be standing *in*,
        // with nothing to dig. The route assumed a drop the world doesn't allow, so no key press can
        // help - ask for a new route now instead of grinding out the stall timer.
        // If the target is a ladder or vine we can simply climb down, so it is not stuck.
        // When there are more nodes after this one, the steer logic will use the following node as a
        // target so the bot can walk off the ledge it needs to drop from.
        if (inColumn && descending && player.onGround() && !onClimbable && index + 1 >= path.size()) {
            return Status.STUCK;
        }

        // In water the keys and the eyes are the swim's. SwimPolicy decides both, so that how the
        // bot dives, swims, breathes and climbs out is one set of rules for every route.
        SwimPolicy.Water water = player.isInWater()
                ? waterAround(ctx, player, feet, target, allowSprint, climbing, descending,
                        onClimbable)
                : null;
        SwimPolicy.Stroke stroke = water != null ? SwimPolicy.decide(water) : null;
        if (stroke != null) {
            // The rule's own name, lower-cased with ROOT so a Turkish client does not write "dıve".
            ctx.debug.movement(target, "route waypoint, "
                    + stroke.kind().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }

        // A node directly above or below gives no horizontal direction to walk in, which would
        // otherwise leave the bot pressing "forward" into whatever it happened to be facing. Steer
        // at the following node instead, so it walks off the ledge it needs to drop from.
        //
        // Unless the drop is there to take: over a landing that is open, the feet go to its middle
        // and down into it. Steered on at the next node instead, a step down on the bastion
        // (2026-10-18) with the next node round a corner came off the edge of its own landing, into
        // the hole beside it. Only a drop the world does not have - a floor where the route planned
        // air - is walked off toward the next node.
        boolean dropThere = descending && !player.isInWater() && MovementHelper.isPassable(ctx.level, target);
        BlockPos steerNode = inColumn && !dropThere && index + 1 < path.size() ? path.get(index + 1) : target;
        Vec3 steerTarget = Vec3.atCenterOf(steerNode);

        // A gap to jump. Asked before the head is aimed, because it decides where the head goes.
        boolean gapJump = isGapJump(ctx, feet, target);

        // Off the route: the next waypoint is more than a step away and no jump from here reaches it.
        // That is a body carried off the take-off - a jump that overshot onto the block beside the
        // next one's take-off, on the bastion (2026-10-18) - and walking straight at the waypoint
        // walks across whatever lies between, which there was a lava pocket. Plan again from here; a
        // jump that is still the way is found again, from where the feet are. The same for a
        // diagonal back onto the route across the corner of a hole that hurts, which no route plans.
        int offX = target.getX() - feet.getX();
        int offZ = target.getZ() - feet.getZ();
        if (!gapJump && player.onGround() && !player.isInWater() && !onClimbable
                && (Math.max(Math.abs(offX), Math.abs(offZ)) > 1
                        || MovementHelper.cutsAHurtingCorner(ctx.level, feet, offX, offZ))) {
            breaker.stop(ctx);
            blockedBy.set(OFF_ROUTE);
            return Status.REPLAN;
        }

        // Aim one node further than we're walking, at eye height, so turns lead the movement
        // instead of chasing it. Swimming is the exception: a swimmer's depth follows the pitch, so
        // in water the head aims where the stroke says - level, so the keys hold the depth; along
        // the route's own depth under a roof; or up at the air. A jump across a gap is the other:
        // the head goes on the landing itself, before the take-off and all the way across, because
        // vanilla sends a sprint jump the way the head faces and not the way the body is going. And
        // a waypoint the body was carried past is looked at, not past (behind); and beside a drop
        // that hurts the head goes where the feet are steered, because the keys walk up to 22.5
        // degrees off wherever the head points: led a node ahead, a step down past a lava pocket on
        // the bastion (2026-10-18) came down on the pocket's lip and slid in.
        boolean leaping = leapIndex == index && !player.onGround();
        BlockPos following = path.get(Math.min(index + 1, path.size() - 1));
        boolean nearADrop = !player.isInWater()
                && (SafeStep.onTheLip(ctx.level, target) || SafeStep.onTheLip(ctx.level, feet));
        boolean stepUp = climbing && !gapJump && !onClimbable && !inColumn && !player.isInWater();
        BlockPos lookNode = gapJump || leaping || stepUp ? target
                : behind(player, centre, following) || nearADrop ? steerNode : following;
        look(ctx, aimFor(ctx, stroke, lookNode), player.isInWater());

        steer(player, ctx, steerTarget);

        boolean headroom = MovementHelper.isPassable(ctx.level, feet.above())
                && MovementHelper.isPassable(ctx.level, feet.above(2));

        // Jumping only ever helps from the ground, and never when the point is to get lower -
        // hopping while trying to descend just keeps the bot on the ledge it's trying to leave.
        // We also require headroom: in a 1-wide 2-high tunnel, jumping just mashes the bot into the
        // ceiling and looks twitchy.
        // A jump up across a gap is not a step up: it waits for its take-off like any gap jump.
        //
        // A step up is taken along the head too, because vanilla sends a jump the way the head faces:
        // until the head is round onto the step the bot holds where it is, Shift down, and a step up
        // onto a block beside a drop that hurts is walked, never run at - a run ends only when
        // forward is let go, so a body running at one holds a tick first. On the bastion
        // (2026-10-18) two runs jumped a step up at a corner on the tick the waypoint changed, the
        // head still sixty degrees round, and the jump carried them sideways off the landing into
        // the hole beside it.
        boolean walkUp = stepUp && SafeStep.onTheLip(ctx.level, target);
        if (allowJump && player.onGround() && !descending && headroom && climbing && !gapJump) {
            boolean facingStep = !stepUp
                    || GapJumpPolicy.facesTheJump(player.getYRot(), centre.x - player.getX(), centre.z - player.getZ());
            if (facingStep && !(walkUp && player.isSprinting())) {
                ctx.input.jump = true;
            } else {
                ctx.input.forward = false;
                ctx.input.backward = false;
                ctx.input.left = false;
                ctx.input.right = false;
                // Shift only while the body still slides, on a lip a slide could carry it off; a
                // body standing still stays put with no key down (GapJumpPolicy.holdsWithShift).
                ctx.input.sneak = SafeStep.onTheLip(ctx.level, feet)
                        && player.getDeltaMovement().horizontalDistance() > GapJumpPolicy.STILL;
            }
        }
        // Clearing a gap. The pathfinder can plan a jump across a hole, but the node on the far side
        // is at the same height as this one or lower, so none of the tests above press anything and
        // the bot would simply walk into the hole. A node more than one block away horizontally is
        // only ever reachable by jumping, so treat the distance itself as the instruction.
        //
        // `descending` is deliberately not consulted here. The search plans gap jumps that land a
        // block low as well as level ones - see AStarPathfinder.relaxGapJumps, which tries drop 0
        // and drop 1 - and gating this on `!descending` meant the executor pressed nothing for
        // exactly the half of them that drop. The bot walked into the hole it had planned to jump,
        // every time, which is why a two-block gap looked like something it could not do.
        // isGapJump does its own vertical check, so nothing here needs to repeat it.
        //
        // The head first. A route that turns at the edge arrives looking the way it walked, and
        // until the head is round onto the landing the bot holds on the take-off block: nothing
        // pressed toward the hole, and Shift, which is vanilla's own guard against walking off an
        // edge. The jump that taught this was taken on the tick the bot arrived, seventy degrees
        // off the line, and came down beside the landing in lava (GapJumpPolicy.MAX_TAKEOFF_TURN).
        //
        // And the room to run. A body nearer the edge than the middle of the take-off block is
        // walked back toward the middle while the head comes round, and one that has stopped nearer
        // the edge goes back there before it runs, head round or not - the walk up to the turn used
        // to carry it to the lip, where the run had no room left to reach jumping speed and the feet
        // went over first (GapJumpPolicy.RUN_UP_ROOM). A run once begun carries on to its jump.
        double towardX = centre.x - player.getX();
        double towardZ = centre.z - player.getZ();
        boolean facing = GapJumpPolicy.facesTheJump(player.getYRot(), towardX, towardZ);
        Vec3 velocity = player.getDeltaMovement();
        double along = GapJumpPolicy.speedToward(velocity.x, velocity.z, towardX, towardZ);
        int jumpX = Integer.signum(target.getX() - feet.getX());
        int jumpZ = Integer.signum(target.getZ() - feet.getZ());
        double toEdge = gapJump
                ? GapJumpPolicy.toEdge(feet.getX(), feet.getZ(), jumpX, jumpZ, player.getX(), player.getZ())
                : Double.MAX_VALUE;
        // And the lane. The search plans a jump down the middle of its column; a body that came onto
        // the take-off block on a diagonal is to one side of it, where its shoulder flies along the
        // next column over and catches whatever stands beside the gap (GapJumpPolicy.LANE). The
        // hold walks it back to the middle first.
        double offLane = gapJump
                ? GapJumpPolicy.offLane(feet.getX(), feet.getZ(), jumpX, player.getX(), player.getZ())
                : 0.0;
        // And the far end of it: the head's line has to bring the body down in the lane as well,
        // because the jump goes the way the head faces and a wall can stand beside the landing too
        // (GapJumpPolicy.landingOffLane). A head a few degrees out holds a tick or two longer.
        boolean linesUp = gapJump && GapJumpPolicy.linesUp(player.getYRot(), jumpX, jumpZ,
                player.getX(), player.getZ(), centre.x, centre.z);
        // And the pace. A jump up a block, or over one block onto a landing with a hole close past
        // it, is walked: run at, the first meets the landing's face and the second comes down past
        // the landing (GapJumpPolicy.walkJump). A body sprinting at one holds, which ends the sprint.
        // When to leave the ground is where the jump would come down: a long gap waits for the lip,
        // a short one goes from further back (GapJumpPolicy.reaches).
        int rise = target.getY() - feet.getY();
        int gapLength = Math.abs(target.getX() - feet.getX()) + Math.abs(target.getZ() - feet.getZ()) - 1;
        boolean walkJump = gapJump
                && GapJumpPolicy.walkJump(rise, gapLength, floorBeyond(ctx, target, jumpX, jumpZ));
        boolean paced = !(walkJump && player.isSprinting());
        boolean reaches = gapJump
                && GapJumpPolicy.reaches(toEdge, along, player.isSprinting(), rise, gapLength);
        boolean ready = GapJumpPolicy.readyToRun(facing, linesUp, paced, runUpIndex == index, toEdge,
                offLane, along);
        if (gapJump && player.onGround()) {
            if (ready) {
                runUpIndex = index;
            } else {
                runUpIndex = -1;
                ctx.input.forward = false;
                ctx.input.backward = false;
                ctx.input.left = false;
                ctx.input.right = false;
                boolean backingOff = toEdge < GapJumpPolicy.RUN_UP_ROOM || offLane > GapJumpPolicy.LANE;
                ctx.input.sneak = backingOff || GapJumpPolicy.holdsWithShift(toEdge, along);
                if (backingOff) {
                    steer(player, ctx, Vec3.atBottomCenterOf(feet));
                }
            }
        } else if (gapJump && leapIndex != index && index > 0) {
            // Still coming down onto the take-off - a step down onto it, the landing of the jump
            // before. The feet go to the take-off block until they are on it, never on toward the
            // landing over the hole: on the bastion (2026-10-18) a step down onto a one-block
            // take-off, turning there for a jump at right angles, was steered at the landing in
            // the air and came down past the take-off's edge, in the gap, without a jump.
            runUpIndex = -1;
            ctx.input.forward = false;
            ctx.input.backward = false;
            ctx.input.left = false;
            ctx.input.right = false;
            steer(player, ctx, Vec3.atBottomCenterOf(path.get(index - 1)));
        }
        // A standing jump does not clear two blocks; a sprinting one does. But pressing sprint on
        // the take-off tick is not the same as arriving with speed - the boost vanilla adds is
        // proportional to nothing, it is the run-up that carries you - so wait a tick or two for the
        // run rather than committing to a hop that was always going to come up short. Steering
        // continues meanwhile, which is what builds the speed being waited for. The speed is the
        // speed toward the landing: the walk up to a turn is fast, and none of it goes across.
        // Waiting ends at the lip: on the last tick the feet can still push off, the run jumps
        // whatever its speed, because the next tick is a step into the hole.
        if (GapJumpPolicy.shouldTakeOff(allowJump, player.onGround(), headroom, gapJump, ready, linesUp,
                reaches, along, toEdge)) {
            ctx.input.jump = true;
            leapIndex = index;
        }
        if (allowJump && onClimbable && climbing) {
            ctx.input.jump = true;
        }
        // Nothing is pressed to go *down* a ladder or a vine: let go and gravity slides the player
        // down one block at a time. Sneak is the opposite of what it looks like here - on a
        // climbable it is the "hold on where I am" key, so pressing it while trying to descend is
        // exactly how a bot ends up hanging in a jungle canopy until its stall counter runs out.
        //
        // In water, Shift and Space are the stroke's: Shift to get under and start swimming, or to
        // sink to a lower waypoint, and Space to climb out, to come up for air, or to hold a
        // swimmer's feet just under the top of the water, where it breathes and strokes at once.
        // Holding Space just because a surface waypoint is above the feet is what kept the bot
        // bobbing upright across whole oceans. Through a turn, where steering lets go of forward,
        // the stroke is kept the way vanilla allows it to be kept: Shift held, Space beside it.
        if (player.isInLava() || (!player.isInWater() && MovementHelper.isWater(ctx.level, target))) {
            ctx.input.jump = true;
        } else if (stroke != null) {
            SwimPolicy.Stroke keys = SwimPolicy.throughTurn(stroke, water, ctx.input.forward);
            ctx.input.jump = keys.jump();
            ctx.input.sneak = keys.sneak();
        }
        // Sprinting is what turns paddling into the crawl stroke, which is twice as fast. Vanilla
        // starts the swimming pose when sprint is held while the eyes are under - which is what
        // the stroke's Shift is for - and keeps it for as long as the body is in water, so the run
        // key is held for the whole crossing, the dive included.
        //
        // The rule itself lives in SprintPolicy; all that happens here is reading the situation off
        // the player and the route.
        boolean waterTravel = player.isInWater() || MovementHelper.isWater(ctx.level, target);
        int dropAhead = Math.max(0, feetY - target.getY());
        ctx.input.sprint = SprintPolicy.shouldSprint(new SprintPolicy.Movement(
                allowSprint, ctx.input.forward, player.onGround(), player.isInWater(), waterTravel,
                onClimbable && !player.onGround(), climbing, descending, dropAhead, 0.0));
        // A jump to be walked is walked up to, and across.
        if (walkJump || walkUp) {
            ctx.input.sprint = false;
        }

        updateProgress(player, centre);
        return noProgressTicks > STUCK_TICKS ? Status.STUCK : Status.RUNNING;
    }

    private boolean routeFluidChanged(BotContext ctx) {
        int limit = Math.min(path.size(), index + LOOKAHEAD + 1);
        for (int i = index; i < limit; i++) {
            BlockPos pos = path.get(i);
            if (MovementHelper.isLava(ctx.level, pos) && !ctx.player.isInLava()) {
                return true;
            }
            if (tracksFluidChanges && MovementHelper.isWater(ctx.level, pos)
                    && !initiallyWet.contains(pos.asLong())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps the player where the route left them while the breaker is busy.
     * <p>
     * Pressing nothing while mining is right on solid ground - you do not walk and dig at the same
     * time. Hanging on a vine is different: letting go there is not standing still, it is sliding
     * back down, so the bot loses the height it just climbed and has to climb it again on the next
     * tick. If the route is heading up, keep climbing into the gap as it is cleared; otherwise sneak,
     * which is the key that holds a climber in place. In water the same job falls to jump, which is
     * what stops a break turning into a slow sink.
     */
    private static void holdWhileBreaking(BotContext ctx, LocalPlayer player, boolean climbing) {
        BlockPos feet = player.blockPosition();
        if (MovementHelper.isClimbable(ctx.level, feet)
                || MovementHelper.isClimbable(ctx.level, feet.above())) {
            if (climbing) {
                ctx.input.jump = true;
            } else {
                ctx.input.sneak = true;
            }
            return;
        }
        if (player.isInWater()) {
            ctx.input.jump = true;
        }
    }

    private static boolean unexpectedFall(LocalPlayer player, BlockPos target, int feetY) {
        return !player.onGround()
                && !player.isInWater()
                && !player.isInLava()
                && !MovementHelper.isClimbable(player.level(), player.blockPosition())
                && player.getDeltaMovement().y < -0.1
                && player.fallDistance > UNEXPECTED_FALL_DISTANCE
                && target.getY() >= feetY;
    }

    /**
     * Whether the next node is across a gap rather than the next step along.
     *
     * <p>Adjacent nodes - including diagonals - are one block away in each axis. Anything further
     * is a jump the pathfinder planned deliberately, because no walking move produces it.
     */
    /** How many blocks of floor follow a landing along its jump, at its level: none, one, or two and more. */
    private static int floorBeyond(BotContext ctx, BlockPos landing, int stepX, int stepZ) {
        int floor = 0;
        while (floor < 2 && MovementHelper.canStandAt(ctx.level,
                landing.offset(stepX * (floor + 1), 0, stepZ * (floor + 1)))) {
            floor++;
        }
        return floor;
    }

    private static boolean isGapJump(BotContext ctx, BlockPos feet, BlockPos target) {
        int dx = target.getX() - feet.getX();
        int dz = target.getZ() - feet.getZ();
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);
        // The shape of the move is GapJumpPolicy's to decide, so that what the executor acts on and
        // what the search plans cannot drift apart again. All this supplies is the world lookup.
        return GapJumpPolicy.isGap(dx, target.getY() - feet.getY(), dz,
                step -> MovementHelper.isSolidFloor(ctx.level,
                        feet.offset(stepX * step, -1, stepZ * step)));
    }

    /**
     * Reads the water round the player for {@link SwimPolicy}, which decides what to do with it.
     *
     * <p>The stroke is the run key, so it is only on offer when this route may sprint and vanilla
     * would let it: the same food, blindness and riding rules {@code LocalPlayer} checks before it
     * starts a sprint. Diving for a stroke that will never start would only spend the air.</p>
     *
     * <p>The top of the water is the top face of the highest water block straight above the feet,
     * as far as the fluid fills it - 0.889 of a source block - and it is open to the air when the
     * block over it is passable. Under ice or a roof there is no breath to be had there, and the
     * policy holds nothing.</p>
     */
    private static SwimPolicy.Water waterAround(BotContext ctx, LocalPlayer player, BlockPos feet,
                                                BlockPos target, boolean allowSprint,
                                                boolean climbing, boolean descending,
                                                boolean onClimbable) {
        boolean canStroke = allowSprint
                && !player.isPassenger()
                && !player.isMobilityRestricted()
                && (player.getFoodData().hasEnoughFood() || player.getAbilities().mayfly);
        boolean deep = MovementHelper.isDeepWater(ctx.level, feet)
                && MovementHelper.isDeepWater(ctx.level, target);
        BlockPos column = player.blockPosition();
        BlockPos top = new BlockPos(column.getX(), surfaceAbove(ctx.level, column) - 1,
                column.getZ());
        FluidState fluid = ctx.level.getFluidState(top);
        double waterTop = top.getY() + (fluid.isEmpty() ? 1.0 : fluid.getHeight(ctx.level, top));
        boolean airAbove = MovementHelper.isWater(ctx.level, column)
                && MovementHelper.isPassable(ctx.level, top.above());
        return new SwimPolicy.Water(player.isSwimming(), player.isUnderWater(),
                player.getAirSupply(), player.getMaxAirSupply(), canStroke, deep, airAbove,
                waterTop - player.getY(), player.getDeltaMovement().y,
                MovementHelper.isWater(ctx.level, target), climbing, descending, onClimbable);
    }

    /**
     * Where the eyes go this tick: the waypoint, or what the stroke wants instead.
     *
     * <p>The route across water runs along its top layer, because every node there is somewhere to
     * breathe, and a swimmer holding the top of the water looks level along it: that is where it
     * is going, and a level look steers its depth nowhere. A swimmer under a roof follows the
     * route's depth with the look instead, which is all that steers a swimmer's depth when there
     * is no top to hold.</p>
     */
    private static Vec3 aimFor(BotContext ctx, SwimPolicy.Stroke stroke, BlockPos node) {
        Vec3 centre = Vec3.atCenterOf(node);
        if (stroke == null) {
            return centre;
        }
        return switch (stroke.aim()) {
            case NODE -> centre;
            case LEVEL -> ahead(ctx.player, centre, ctx.player.getEyeY());
            case LANE -> ahead(ctx.player, centre, centre.y);
            case SURFACE -> new Vec3(centre.x,
                    surfaceAbove(ctx.level, ctx.player.blockPosition()) + 0.5, centre.z);
        };
    }

    /**
     * A point at height {@code y} on the way to {@code toward}, at least {@link #SWIM_LEAD} blocks
     * off. Aiming at the next waypoint itself, a block away, turned every dive into a plunge two
     * blocks past the lane, and every climb back into a stall with the head pointing at the sky.
     */
    private static Vec3 ahead(LocalPlayer player, Vec3 toward, double y) {
        double dx = toward.x - player.getX();
        double dz = toward.z - player.getZ();
        double reach = Math.hypot(dx, dz);
        if (reach < 1.0E-3) {
            return new Vec3(toward.x, y, toward.z);
        }
        double scale = Math.max(1.0, SWIM_LEAD / reach);
        return new Vec3(player.getX() + dx * scale, y, player.getZ() + dz * scale);
    }

    /**
     * The first block above {@code pos} that is not water: the air a breath is taken in.
     *
     * <p>Bounded, so a flooded cave with no top answers somewhere above the head rather than
     * scanning to the build limit. There is no breath to be had in one, and {@link WaterEscape}
     * is what goes looking for an air pocket when the bar gets that low.</p>
     */
    private static int surfaceAbove(BlockGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int step = 0; step < SURFACE_SEARCH && MovementHelper.isWater(level, cursor); step++) {
            cursor.move(0, 1, 0);
        }
        return cursor.getY();
    }

    /**
     * Whether the waypoint at {@code waypoint} is back the way the route goes on: more than a
     * column's width off, and the node after it more than a right angle the other way. The body
     * has been carried past it - a landing, a sprint into a corner - and the head goes on the
     * waypoint until the feet are back on it, so the walk back is a walk forward and not a backward
     * shuffle with the eyes on where the route goes next. On the bastion (2026-10-18) a run backed
     * like that onto a step down it had sprinted past, and slid into the hole beside it.
     */
    static boolean behind(LocalPlayer player, Vec3 waypoint, BlockPos following) {
        return behind(player.getX(), player.getZ(), waypoint, Vec3.atCenterOf(following));
    }

    static boolean behind(double x, double z, Vec3 waypoint, Vec3 following) {
        double toX = waypoint.x - x;
        double toZ = waypoint.z - z;
        if (toX * toX + toZ * toZ < COLUMN_RADIUS_SQR) {
            return false;
        }
        return toX * (following.x - x) + toZ * (following.z - z) < 0.0;
    }

    private void look(BotContext ctx, Vec3 lookCentre, boolean includePitch) {
        Vec3 aim = includePitch
                ? lookCentre
                : new Vec3(lookCentre.x, ctx.player.getEyePosition().y, lookCentre.z);
        ctx.look.lookAt(ctx.player, aim);
    }

    /**
     * The next block that has to go before this step is walkable, or null when the way is clear.
     * <p>
     * Order matters, and so does the fact that this is more than the destination. A step up is
     * priced by the pathfinder as three blocks - the destination's feet and head space, plus the
     * headroom above the player's own head to jump through - and clearing only the destination
     * leaves the bot bumping its head on a ceiling it never dug, so the route looks unwalkable and
     * it re-routes for no visible reason.
     */
    private static BlockPos findObstruction(BotContext ctx, BlockPos target) {
        BlockPos feet = MovementHelper.feetPosition(ctx.player);

        // A lava escape path deliberately contains lava nodes. Lava is not a block to mine; swim
        // toward the next node and keep jumping until safe ground is reached.
        if (ctx.player.isInLava() && MovementHelper.isLava(ctx.level, target)) {
            return null;
        }

        // Clipped into a solid block, or trapped in our own head space. Leaves around a tree are
        // the classic case: the player is inside them, so movement stalls until they go, and
        // nothing else is worth doing until that's clear.
        BlockPos body = MovementHelper.blockedBodyPos(ctx.level, feet, ctx.player.getOnPos(),
                ctx.player.onGround());
        if (body != null) {
            return body;
        }

        // Headroom for a step up, the corners of a diagonal, then the body space ahead.
        List<BlockPos> inTheWay = MovementHelper.stepClearance(ctx.level, feet, target);
        return inTheWay.isEmpty() ? null : inTheWay.get(0);
    }

    /**
     * Presses the key combination that moves toward {@code target} in world space, regardless of
     * facing.
     */
    private static void steer(LocalPlayer player, BotContext ctx, Vec3 target) {
        ctx.input.steerToward(player, target);
    }

    /**
     * Advances past every node already reached, scanning from the furthest candidate backwards so
     * that overshooting a node skips forward rather than turning the bot around.
     */
    private void advance(BotContext ctx, LocalPlayer player) {
        int limit = Math.min(path.size() - 1, index + LOOKAHEAD);
        for (int i = limit; i >= index; i--) {
            if (hasReached(ctx, player, path.get(i))) {
                index = i + 1;
                return;
            }
        }
    }

    /**
     * Progress is measured as "got closer to the current node", not "moved at all" - a bot
     * oscillating around a corner is moving the whole time, and a distance-travelled check could
     * never catch it.
     */
    private void updateProgress(LocalPlayer player, Vec3 centre) {
        double distance = player.position().distanceTo(centre);
        if (distance < bestDistanceToTarget - PROGRESS_EPSILON) {
            bestDistanceToTarget = distance;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }
    }

    private static boolean hasReached(BotContext ctx, LocalPlayer player, BlockPos pos) {
        // Height must match exactly on land. In water the player bobs, so allow one block of
        // vertical slop as long as the path node and the player are both in water - and more
        // below it, because the dive that starts the stroke carries a swimmer well under the
        // surface layer the route runs along. Passing under a waypoint is passing it.
        // Counting only one block made the bot stop under a node two blocks up and back-pedal
        // to it, with the head tipped at the sky.
        boolean inWater = player.isInWater() && MovementHelper.isWater(ctx.level, pos);
        int y = MovementHelper.feetPosition(player).getY();
        boolean yOk = y == pos.getY() || (inWater && Math.abs(y - pos.getY()) <= 1)
                || (inWater && swimmingUnder(ctx.level, pos, y));
        if (!yOk) {
            return false;
        }
        // Standing in the node's own column is the primary test; the radius check is a fallback for
        // diagonal shortcuts that clip the corner without ever entering the block.
        if (Mth.floor(player.getX()) == pos.getX() && Mth.floor(player.getZ()) == pos.getZ()) {
            return true;
        }
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz < CENTRE_RADIUS_SQR;
    }

    /**
     * Whether feet at {@code feetY} are in the same body of water as {@code pos}, a few blocks
     * straight under it: water all the way up, so it is the swim's own column and not a cave
     * beneath a pond.
     */
    private static boolean swimmingUnder(BlockGetter level, BlockPos pos, int feetY) {
        int depth = pos.getY() - feetY;
        if (depth < 2 || depth > SWIM_UNDER_REACH) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(pos.getX(), feetY, pos.getZ());
        for (int y = feetY; y < pos.getY(); y++) {
            cursor.setY(y);
            if (!MovementHelper.isWater(level, cursor)) {
                return false;
            }
        }
        return true;
    }

    /** Releases any half-finished break. Must be called when the path is abandoned. */
    public void stop(BotContext ctx) {
        breaker.stop(ctx);
    }
}
