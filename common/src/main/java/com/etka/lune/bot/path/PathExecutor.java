package com.etka.lune.bot.path;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.util.BlockBreaker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
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

    /** Must match the pathfinder's own gap reach; a longer hop was never planned. */
    private static final int MAX_GAP_JUMP = 2;
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
    /** Only sprint when roughly aimed the way we're travelling. */
    private static final double SPRINT_MAX_ANGLE = 30.0;
    /** How steeply to aim below the horizon to get the eyes under and start the swim. */
    private static final double DIVE_SLOPE = 0.75;
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
        HAZARD
    }

    private final List<BlockPos> path;
    private final Set<Long> initiallyWet = new HashSet<>();
    private final boolean tracksFluidChanges;
    /** Whether this route is allowed to enter water; dry routes use emergency escape instead. */
    private final boolean allowSwim;
    /** Whether this route may deliberately press jump for ascent, climbing, or a gap. */
    private final boolean allowJump;
    private final BlockBreaker breaker = new BlockBreaker();

    private int index = 1; // path[0] is the block we're already standing on
    private int noProgressTicks;
    private int fallRecoveryTicks;
    private double bestDistanceToTarget = Double.MAX_VALUE;
    private double lastRelativeAngle;
    private String blockedBy = "";
    private boolean unexpectedWaterRecovery;

    public PathExecutor(List<BlockPos> path) {
        this.path = path;
        this.tracksFluidChanges = false;
        this.allowSwim = true;
        this.allowJump = true;
    }

    public PathExecutor(List<BlockPos> path, BlockGetter level) {
        this(path, level, true);
    }

    public PathExecutor(List<BlockPos> path, BlockGetter level, boolean allowSwim) {
        this(path, level, allowSwim, true);
    }

    public PathExecutor(List<BlockPos> path, BlockGetter level, boolean allowSwim,
                        boolean allowJump) {
        this.path = path;
        this.tracksFluidChanges = true;
        this.allowSwim = allowSwim;
        this.allowJump = allowJump;
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

    /** Name of the block that triggered {@link Status#NO_TOOL}, for the failure message. */
    public String getBlockedBy() {
        return blockedBy;
    }

    public Status tick(BotContext ctx, boolean allowSprint, boolean allowBreak) {
        LocalPlayer player = ctx.player;
        if (WaterEscape.tick(ctx)) {
            breaker.stop(ctx);
            noProgressTicks = 0;
            blockedBy = "escaping to breathable air";
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
                blockedBy = "dry route entered water with no safe exit";
                return Status.HAZARD;
            }
            WaterEscape.tickToAir(ctx);
            breaker.stop(ctx);
            noProgressTicks = 0;
            unexpectedWaterRecovery = true;
            blockedBy = "dry route entered water - escaping";
            return Status.RUNNING;
        }
        if (unexpectedWaterRecovery) {
            unexpectedWaterRecovery = false;
            breaker.stop(ctx);
            blockedBy = "escaped unexpected water - replanning";
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
            blockedBy = "fluid entered the route - replanning";
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
            blockedBy = "unexpected fall; returning to the last safe route block";
            if (fallRecoveryTicks++ >= FALL_RECOVERY_TICKS) {
                return Status.REPLAN;
            }
            return Status.RUNNING;
        }
        fallRecoveryTicks = 0;

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
                blockedBy = "route blocked by "
                        + ctx.level.getBlockState(obstruction).getBlock().getName().getString();
                ctx.debug.decide("route blocked; replanning without digging");
                return Status.REPLAN;
            }
            if (obstruction != null && !allowSwim
                    && MovementHelper.wouldOpenWater(ctx.level, obstruction)) {
                breaker.stop(ctx);
                blockedBy = "refusing to open water into a dry route";
                return Status.HAZARD;
            }
            if (obstruction != null && MovementHelper.nearLava(ctx.level, obstruction)
                    && !ctx.player.isInLava()) {
                breaker.stop(ctx);
                blockedBy = "refusing to dig beside lava";
                return Status.HAZARD;
            }
            if (obstruction != null && !breaker.isOutOfReach(ctx, obstruction)) {
                BlockBreaker.Progress progress = breaker.tick(ctx, obstruction, true);
                if (progress == BlockBreaker.Progress.NO_TOOL) {
                    blockedBy = ctx.level.getBlockState(obstruction).getBlock().getName().getString();
                    ctx.debug.breaking(obstruction, blockedBy, "no suitable tool");
                    ctx.debug.decide("cannot clear " + blockedBy + "; route needs a better tool");
                    return Status.NO_TOOL;
                }
                if (progress == BlockBreaker.Progress.HAZARD) {
                    blockedBy = breaker.getFailureReason();
                    ctx.debug.breaking(obstruction, ctx.debug.breakBlock, blockedBy);
                    ctx.debug.decide("stopped breaking: " + blockedBy);
                    return Status.HAZARD;
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

        // A node directly above or below gives no horizontal direction to walk in, which would
        // otherwise leave the bot pressing "forward" into whatever it happened to be facing. Steer
        // at the following node instead, so it walks off the ledge it needs to drop from.
        Vec3 steerTarget = inColumn && index + 1 < path.size()
                ? Vec3.atCenterOf(path.get(index + 1))
                : centre;

        // Aim one node further than we're walking, at eye height, so turns lead the movement
        // instead of chasing it. Swimming is the exception: a swimming player travels along their
        // whole line of sight, pitch included, so in water the head has to aim at the node itself
        // or the bot will only ever swim dead level.
        Vec3 lookCentre = Vec3.atCenterOf(path.get(Math.min(index + 1, path.size() - 1)));
        look(ctx, diveAim(player, lookCentre), player.isInWater());

        lastRelativeAngle = steer(player, ctx, steerTarget);
        boolean moving = ctx.input.forward || ctx.input.backward || ctx.input.left || ctx.input.right;

        boolean headroom = MovementHelper.isPassable(ctx.level, feet.above())
                && MovementHelper.isPassable(ctx.level, feet.above(2));

        // Jumping only ever helps from the ground, and never when the point is to get lower -
        // hopping while trying to descend just keeps the bot on the ledge it's trying to leave.
        // We also require headroom: in a 1-wide 2-high tunnel, jumping just mashes the bot into the
        // ceiling and looks twitchy.
        if (allowJump && player.onGround() && !descending && headroom && climbing) {
            ctx.input.jump = true;
        }
        // Clearing a gap. The pathfinder can now plan a jump across a hole, but the node on the far
        // side is at the same height as this one, so none of the tests above press anything and the
        // bot would simply walk into the hole. A node more than one block away horizontally is only
        // ever reachable by jumping, so treat the distance itself as the instruction - and sprint,
        // because a standing jump does not clear two blocks.
        if (allowJump && player.onGround() && !descending && headroom
                && isGapJump(ctx, feet, target)) {
            ctx.input.jump = true;
            ctx.input.sprint = true;
        }
        if (allowJump && onClimbable && climbing) {
            ctx.input.jump = true;
        }
        // Nothing is pressed to go *down* a ladder or a vine: let go and gravity slides the player
        // down one block at a time. Sneak is the opposite of what it looks like here - on a
        // climbable it is the "hold on where I am" key, so pressing it while trying to descend is
        // exactly how a bot ends up hanging in a jungle canopy until its stall counter runs out.
        // Crossing water: sprint, and steer depth with the head.
        //
        // Sprint is the swim. It turns paddling upright into the crawl stroke, and a swimming
        // player travels along their whole line of sight - so the look above, which is already
        // aiming at the next node, is what decides depth. That is how a person crosses a river:
        // face where you are going and hold the run key.
        //
        // Space is the safety net rather than the technique. Holding it every tick is what pins
        // the bot to the surface bobbing upright, and the swim stroke only ever starts while the
        // eyes are under. So it takes over only when air actually runs low, or when the route
        // wants to climb out onto a bank.
        if (player.isInLava() || (!player.isInWater() && MovementHelper.isWater(ctx.level, target))) {
            ctx.input.jump = true;
        } else if (player.isInWater()) {
            ctx.input.jump = WaterEscape.needsAir(player) || climbing;
        }
        // Sprinting is what turns paddling into the crawl stroke, which is roughly twice as fast.
        // Vanilla latches the swimming pose when sprint is held while the eyes are under - which
        // happens on the way in - and then keeps it for as long as the body is in water, so the
        // bot goes on swimming properly even once the jump above has brought it back to the
        // surface. Refusing to sprint in water gave up that speed on every crossing.
        // A swimmer can keep the crawl stroke while turning. Applying the land steering-angle
        // gate here drops sprint at the edge of a river exactly when the route is still trying to
        // settle its heading, leaving only diagonal paddling against the bank and making a valid
        // water route look stalled. Deliberate water routes should keep sprint through that turn;
        // the look controller still eases the yaw and the waypoint check still decides progress.
        boolean waterTravel = player.isInWater() || MovementHelper.isWater(ctx.level, target);
        ctx.input.sprint = allowSprint && moving
                && (player.onGround() || player.isInWater())
                && !climbing && !descending
                && (waterTravel || Math.abs(lastRelativeAngle) < SPRINT_MAX_ANGLE);

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
     * Ducks the head under before a surface crossing, because that is the only way the swim starts.
     *
     * <p>Holding sprint at the surface does nothing at all. Vanilla only latches the swimming pose
     * while the eyes are already submerged, and - worse - {@code LocalPlayer.aiStep} actively
     * cancels sprint on any tick where the body is in water but the head is not. A route across a
     * river is flat, so aiming at the next node keeps the head level, the eyes stay up, and the bot
     * wades the whole way at about half speed. That is the "doesn't swim like a human" behaviour.
     *
     * <p>A person dips their head and pushes off. So while in water and not yet swimming, aim below
     * the horizon to drive the body under; once vanilla reports the pose, the aim goes straight
     * back to the node so the crossing stays on its line - a swimmer travels along their whole line
     * of sight, so leaving the head down would bury the route in the riverbed.
     */
    /**
     * Whether the next node is across a gap rather than the next step along.
     *
     * <p>Adjacent nodes - including diagonals - are one block away in each axis. Anything further
     * is a jump the pathfinder planned deliberately, because no walking move produces it.
     */
    private static boolean isGapJump(BotContext ctx, BlockPos feet, BlockPos target) {
        int dx = target.getX() - feet.getX();
        int dz = target.getZ() - feet.getZ();
        int distance = Math.abs(dx) + Math.abs(dz);
        // Far enough to need a jump, close enough to be one the search actually planned.
        //
        // Distance alone is not enough: a bot that has stalled or drifted off its route is also
        // "far from the next node", and treating that as a jump makes it hop on the spot - which
        // is what a journal showed as y bobbing between 62 and 65 with waypoint stalls, and looks
        // exactly like pointless digging on a hillside.
        if (distance <= 1 || distance > MAX_GAP_JUMP + 1
                || target.getY() > feet.getY()
                || (dx != 0 && dz != 0)) {
            return false;
        }
        // And there has to be a real hole. If the ground between is solid this is ordinary walking,
        // however far away the node is.
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);
        for (int step = 1; step < distance; step++) {
            if (MovementHelper.isSolidFloor(ctx.level, feet.offset(stepX * step, -1, stepZ * step))) {
                return false;
            }
        }
        return true;
    }

    private static Vec3 diveAim(LocalPlayer player, Vec3 lookCentre) {
        if (!player.isInWater() || player.isSwimming() || player.isUnderWater()) {
            return lookCentre;
        }
        double reach = Math.hypot(lookCentre.x - player.getX(), lookCentre.z - player.getZ());
        return new Vec3(lookCentre.x,
                player.getEyePosition().y - Math.max(1.0, reach * DIVE_SLOPE),
                lookCentre.z);
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

        // Headroom to jump. Only needed when the step actually goes up.
        if (target.getY() > feet.getY() && !MovementHelper.isPassable(ctx.level, feet.above(2))) {
            return feet.above(2);
        }

        // Diagonal moves also need the two corner columns clear, otherwise the player clips a leaf.
        int dx = Mth.clamp(target.getX() - feet.getX(), -1, 1);
        int dz = Mth.clamp(target.getZ() - feet.getZ(), -1, 1);
        if (dx != 0 && dz != 0) {
            BlockPos sideX = feet.offset(dx, 0, 0);
            BlockPos sideZ = feet.offset(0, 0, dz);
            if (!MovementHelper.isPassable(ctx.level, sideX)) {
                return sideX;
            }
            if (!MovementHelper.isPassable(ctx.level, sideX.above())) {
                return sideX.above();
            }
            if (!MovementHelper.isPassable(ctx.level, sideZ)) {
                return sideZ;
            }
            if (!MovementHelper.isPassable(ctx.level, sideZ.above())) {
                return sideZ.above();
            }
        }

        if (!MovementHelper.isPassable(ctx.level, target)) {
            return target;
        }
        if (!MovementHelper.isPassable(ctx.level, target.above())) {
            return target.above();
        }
        return null;
    }

    /**
     * Presses the key combination that moves toward {@code target} in world space, regardless of
     * facing.
     *
     * @return the target's bearing relative to the player, in degrees; 0 is straight ahead
     */
    private static double steer(LocalPlayer player, BotContext ctx, Vec3 target) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            // Already on the spot horizontally. Pressing anything here would be a guess.
            return 0.0;
        }

        // Minecraft yaw: 0 faces +Z, and increasing yaw turns right.
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        double relative = Mth.wrapDegrees(desiredYaw - player.getYRot());

        if (Math.abs(relative) < 67.5) {
            ctx.input.forward = true;
        }
        if (Math.abs(relative) > 112.5) {
            ctx.input.backward = true;
        }
        if (relative >= 22.5 && relative <= 157.5) {
            ctx.input.right = true;
        }
        if (relative <= -22.5 && relative >= -157.5) {
            ctx.input.left = true;
        }
        return relative;
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
        // vertical slop as long as the path node and the player are both in water.
        boolean inWater = player.isInWater() && MovementHelper.isWater(ctx.level, pos);
        int y = MovementHelper.feetPosition(player).getY();
        boolean yOk = y == pos.getY() || (inWater && Math.abs(y - pos.getY()) <= 1);
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

    /** Releases any half-finished break. Must be called when the path is abandoned. */
    public void stop(BotContext ctx) {
        breaker.stop(ctx);
    }
}
