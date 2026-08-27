package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockBreaker;
import com.etka.lune.bot.util.BoatHelper;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Crosses water or ice in a boat, building one first if necessary.
 *
 * <p>Ice is the reason this exists. A frozen river is not a hazard the router can price - it is
 * walkable, so the pathfinder happily crosses it on foot at walking pace, and a run can spend
 * minutes edging around a lake it could have crossed in seconds. A boat on ice is the fastest
 * travel in Minecraft, and it costs five planks.</p>
 *
 * <p>Steering needs no new machinery. {@code LocalPlayer} feeds a ridden boat straight from
 * {@code player.input.keyPresses}, which is the same {@code ClientInput} the engine already swaps
 * in to drive walking - so pressing forward/left/right steers the boat exactly as it steers the
 * player, and sneak is what gets back out.</p>
 */
public final class BoatTask implements Task {

    /** How far to look for a launch surface on the way to the destination. */
    private static final int LAUNCH_SEARCH_RADIUS = 24;
    /** Clear blocks needed above a launch site before it counts as open water rather than a cabin. */
    private static final int OPEN_WATER_HEADROOM = 3;
    /** Ticks spent trying to place or board before the spot is written off. */
    private static final int MAX_ATTEMPT_TICKS = 60;
    /** Ticks of crossing without getting closer before the boat is abandoned as stuck. */
    private static final int MAX_STUCK_TICKS = 100;
    /** How far out to trace a body of water when looking for its far bank. */
    private static final int CROSSING_SCAN_LIMIT = 128;
    /** Planks a crafting table costs, when the boat recipe needs one and none is around. */
    private static final int PLANKS_PER_TABLE = 4;
    /** How far a usable table counts as "already here" rather than worth four fresh planks. */
    private static final int TABLE_SEARCH_RADIUS = 6;
    /** Ticks spent getting wet before the recipe is declared unreachable. */
    private static final int MAX_UNLOCK_TICKS = 600;
    /** Ticks spent swimming back to dry land before the whole crossing is written off. */
    private static final int MAX_BANK_TICKS = 200;
    /** How far from the player a hull counts as "the one we just put down". */
    private static final double BOAT_SEARCH_RADIUS = 6.0;

    private enum State {
        FIND_WATER, UNLOCK_RECIPE, ENSURE_BOAT, APPROACH_LAUNCH, PLACE, BOARD, CROSS, LAND, RECLAIM
    }

    /** Where to end up, or null for "get me across whatever is in front of me". */
    private final BlockPos requestedDestination;
    private BlockPos destination;
    private final boolean reclaim;

    private State state = State.ENSURE_BOAT;
    private Task current;
    private BlockPos launchSurface;
    /** Previous hull yaw, so the turn already under way can be measured for the steering lead. */
    private Float lastBoatYaw;
    private BlockPos launchStand;
    private final Set<Long> badLaunchSpots = new HashSet<>();
    private final BlockBreaker breaker = new BlockBreaker();
    private int attemptTicks;
    private int stuckTicks;
    /** Ticks spent wading back to a bank before the crossing is abandoned. */
    private int bankTicks;
    private double bestDistance = Double.MAX_VALUE;
    private String status = "";

    /**
     * @param destination where to land, or null to cross to the far side of the nearest water or
     *                    ice. Null is the more useful default in practice: "get across this river"
     *                    is the actual request, and the coordinates of the far bank are exactly
     *                    what the caller does not know.
     */
    public BoatTask(BlockPos destination, boolean reclaim) {
        this.requestedDestination = destination == null ? null : destination.immutable();
        this.destination = this.requestedDestination;
        this.reclaim = reclaim;
    }

    @Override
    public String name() {
        return "Boat";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        state = State.FIND_WATER;
        current = null;
        destination = requestedDestination;
        launchSurface = null;
        launchStand = null;
        badLaunchSpots.clear();
        attemptTicks = 0;
        stuckTicks = 0;
        bankTicks = 0;
        bestDistance = Double.MAX_VALUE;
        status = "checking for a boat";
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        ctx.debug.giveUp = "boat state " + state + "; attempt " + attemptTicks + "/" + MAX_ATTEMPT_TICKS;

        if (current != null) {
            TaskStatus result = current.tick(ctx);
            status = state + " - " + current.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            String reason = current.status();
            current.stop(ctx);
            current = null;
            if (result == TaskStatus.FAILED) {
                ctx.debug.recordFailure(name() + "/" + state, reason);
                return finish(ctx, TaskStatus.FAILED, "could not " + state + ": " + reason);
            }
            return TaskStatus.RUNNING;
        }

        // A boat is launched from a bank, and every step before CROSS assumes dry feet. Checking
        // that only when the task was chosen is not enough: the bot walks about in between, and
        // UNLOCK_RECIPE deliberately wades in, so by the time PLACE runs it can be standing in the
        // water - or inside a flooded shipwreck, which is where this was seen putting a boat down.
        if (state != State.CROSS && state != State.LAND && ridingBoat(ctx) == null
                && ctx.player.isInWater() && state != State.UNLOCK_RECIPE) {
            return returnToBank(ctx);
        }

        return switch (state) {
            case FIND_WATER -> findWater(ctx);
            case UNLOCK_RECIPE -> unlockRecipe(ctx);
            case ENSURE_BOAT -> ensureBoat(ctx);
            case APPROACH_LAUNCH -> approachLaunch(ctx);
            case PLACE -> placeBoat(ctx);
            case BOARD -> boardBoat(ctx);
            case CROSS -> cross(ctx);
            case LAND -> land(ctx);
            case RECLAIM -> reclaimBoat(ctx);
        };
    }

    @Override
    public void onPause(BotContext ctx) {
        if (current != null) {
            current.onPause(ctx);
        }
        ctx.input.reset();
    }

    @Override
    public void onStop(BotContext ctx) {
        if (current != null) {
            current.stop(ctx);
            current = null;
        }
        ctx.input.reset();
    }

    /**
     * Wades back to the bank before doing anything that needs dry land.
     * <p>
     * Bounded: if there is no bank to return to, the crossing is abandoned rather than treading
     * water indefinitely. Getting out is also the right answer when the caller was wrong to ask for
     * a boat at all - the run continues on foot instead of trying to build one while swimming.
     */
    private TaskStatus returnToBank(BotContext ctx) {
        if (++bankTicks > MAX_BANK_TICKS) {
            return finish(ctx, TaskStatus.FAILED, "in the water with no bank to launch from");
        }
        if (launchStand == null) {
            launchStand = findStandBeside(ctx, ctx.player.blockPosition());
        }
        if (launchStand == null) {
            return finish(ctx, TaskStatus.FAILED, "in the water with no bank to launch from");
        }
        if (current == null) {
            current = new GotoTask(new Goals.Block(launchStand), true, false, true);
            current.start(ctx);
        }
        status = "getting out of the water before launching";
        return TaskStatus.RUNNING;
    }

    // --- getting a boat ---------------------------------------------------------------------

    /**
     * Locates the crossing before anything is built, because the water is also where the recipe
     * comes from and where the boat has to be launched.
     */
    private TaskStatus findWater(BotContext ctx) {
        if (ridingBoat(ctx) != null) {
            state = State.CROSS;
            return TaskStatus.RUNNING;
        }
        if (launchSurface == null || badLaunchSpots.contains(launchSurface.asLong())) {
            launchSurface = findLaunchSurface(ctx);
            if (launchSurface == null) {
                return finish(ctx, TaskStatus.FAILED, "no water or ice within reach to cross");
            }
            launchStand = findStandBeside(ctx, launchSurface);
            if (launchStand == null) {
                badLaunchSpots.add(launchSurface.asLong());
                launchSurface = null;
                return TaskStatus.RUNNING;
            }
            if (requestedDestination == null) {
                destination = findFarShore(ctx, launchSurface);
                if (destination == null) {
                    badLaunchSpots.add(launchSurface.asLong());
                    launchSurface = null;
                    status = "that water has no far side worth crossing to";
                    return TaskStatus.RUNNING;
                }
                ctx.debug.decide("cross to the far shore at " + destination.toShortString());
            }
        }
        state = knowsBoatRecipe(ctx) || carryingBoat(ctx) ? State.ENSURE_BOAT : State.UNLOCK_RECIPE;
        return TaskStatus.RUNNING;
    }

    /**
     * Gets the player's feet into water, which is the only thing that teaches them the recipe.
     * <p>
     * On a frozen river there is no water to stand in until a block of ice is broken, which is
     * exactly what a player does - and why this exists rather than the task simply reporting that
     * boats cannot be crafted.
     */
    private TaskStatus unlockRecipe(BotContext ctx) {
        if (knowsBoatRecipe(ctx)) {
            state = State.ENSURE_BOAT;
            return TaskStatus.RUNNING;
        }
        if (ctx.player.isInWater()) {
            // The advancement fires on entering the block; give the server a moment to send it.
            status = "standing in the water so the boat recipe unlocks";
            return TaskStatus.RUNNING;
        }
        if (++attemptTicks > MAX_UNLOCK_TICKS) {
            return finish(ctx, TaskStatus.FAILED,
                    "could not reach water, so the boat recipe never unlocked");
        }

        BlockPos water = findOpenWater(ctx);
        if (water == null) {
            // Frozen over. Plain ice turns back into water when broken; packed and blue ice do not,
            // so those are honestly hopeless rather than worth swinging at.
            if (ctx.level.getBlockState(launchSurface).is(Blocks.ICE)) {
                if (breaker.tick(ctx, launchSurface, false, Set.of()) == BlockBreaker.Progress.NO_TOOL) {
                    return finish(ctx, TaskStatus.FAILED, "cannot break the ice to reach water");
                }
                status = "breaking the ice to reach the water under it";
                return TaskStatus.RUNNING;
            }
            return finish(ctx, TaskStatus.FAILED, "no open water to unlock the boat recipe");
        }

        breaker.stop(ctx);
        status = "stepping into the water so the boat recipe unlocks";
        current = new GotoTask(new Goals.Block(water), false, false);
        current.start(ctx);
        return TaskStatus.RUNNING;
    }

    private static boolean knowsBoatRecipe(BotContext ctx) {
        return CraftTask.knowsRecipeFor(ctx, stack -> stack.is(ItemTags.BOATS));
    }

    /** A water block near the launch point that the player could actually stand in. */
    private BlockPos findOpenWater(BotContext ctx) {
        for (int radius = 0; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -2; dy <= 1; dy++) {
                        BlockPos candidate = launchSurface.offset(dx, dy, dz);
                        if (ctx.level.getBlockState(candidate).getFluidState().isSource()
                                && MovementHelper.isPassable(ctx.level, candidate.above())) {
                            return candidate.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    private TaskStatus ensureBoat(BotContext ctx) {
        if (carryingBoat(ctx) || ridingBoat(ctx) != null) {
            state = ridingBoat(ctx) != null ? State.CROSS : State.APPROACH_LAUNCH;
            return TaskStatus.RUNNING;
        }
        // Five planks in a 3x2 does not fit the inventory grid, so a boat always needs a table -
        // and a table is four more planks on top of the boat's five when there is none around.
        boolean haveTable = InventoryHelper.has(ctx.player, Items.CRAFTING_TABLE, 1)
                || CraftTask.tableInReach(ctx, TABLE_SEARCH_RADIUS);
        int planks = InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.PLANKS));
        int planksWanted = BoatPolicy.PLANKS_PER_BOAT + (haveTable ? 0 : PLANKS_PER_TABLE);
        if (planks < planksWanted) {
            status = "making planks for a boat";
            current = CraftTask.ofTag(ItemTags.PLANKS, "planks", planksWanted, false);
            current.start(ctx);
            return TaskStatus.RUNNING;
        }
        if (!haveTable) {
            status = "making a crafting table for the boat";
            current = CraftTask.of(Items.CRAFTING_TABLE, 1, false);
            current.start(ctx);
            return TaskStatus.RUNNING;
        }
        status = "crafting a boat";
        current = CraftTask.ofTag(ItemTags.BOATS, "boat", 1, true);
        current.start(ctx);
        return TaskStatus.RUNNING;
    }

    // --- launching --------------------------------------------------------------------------

    private TaskStatus approachLaunch(BotContext ctx) {
        if (launchSurface == null || badLaunchSpots.contains(launchSurface.asLong())) {
            launchSurface = findLaunchSurface(ctx);
            if (launchSurface == null) {
                return finish(ctx, TaskStatus.FAILED, "no water or ice to launch from");
            }
            launchStand = findStandBeside(ctx, launchSurface);
            if (launchStand == null) {
                badLaunchSpots.add(launchSurface.asLong());
                launchSurface = null;
                return TaskStatus.RUNNING;
            }
            if (requestedDestination == null) {
                destination = findFarShore(ctx, launchSurface);
                if (destination == null) {
                    badLaunchSpots.add(launchSurface.asLong());
                    launchSurface = null;
                    status = "that water has no far side worth crossing to";
                    return TaskStatus.RUNNING;
                }
                ctx.debug.decide("cross to the far shore at " + destination.toShortString());
            }
        }
        if (ctx.player.blockPosition().closerThan(launchStand, 2.0)) {
            state = State.PLACE;
            attemptTicks = 0;
            return TaskStatus.RUNNING;
        }
        status = "walking to the water's edge";
        current = new GotoTask(new Goals.Near(launchStand, 1), true, false);
        current.start(ctx);
        return TaskStatus.RUNNING;
    }

    /**
     * Places the boat by looking at the surface and using the item.
     * <p>
     * A boat is not placed against a block face like an ordinary block - {@code BoatItem} runs its
     * own ray cast from the eyes and puts the boat wherever that lands, so aiming is the whole
     * interaction.
     */
    private TaskStatus placeBoat(BotContext ctx) {
        AbstractBoat existing = nearbyBoat(ctx);
        if (existing != null) {
            state = State.BOARD;
            attemptTicks = 0;
            return TaskStatus.RUNNING;
        }
        if (++attemptTicks > MAX_ATTEMPT_TICKS) {
            badLaunchSpots.add(launchSurface.asLong());
            launchSurface = null;
            state = State.APPROACH_LAUNCH;
            return TaskStatus.RUNNING;
        }
        if (BoatHelper.equip(ctx) < 0) {
            state = State.ENSURE_BOAT;
            return TaskStatus.RUNNING;
        }
        Vec3 aim = Vec3.atCenterOf(launchSurface).add(0.0, 0.4, 0.0);
        ctx.look.lookAt(ctx.player, aim);
        status = "placing the boat";
        if (ctx.look.isLookingAt(ctx.player, aim, 12.0F)) {
            BoatHelper.place(ctx);
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus boardBoat(BotContext ctx) {
        if (ridingBoat(ctx) != null) {
            state = State.CROSS;
            stuckTicks = 0;
            bestDistance = Double.MAX_VALUE;
            return TaskStatus.RUNNING;
        }
        AbstractBoat boat = nearbyBoat(ctx);
        if (boat == null || ++attemptTicks > MAX_ATTEMPT_TICKS) {
            state = State.PLACE;
            attemptTicks = 0;
            return TaskStatus.RUNNING;
        }
        Vec3 centre = boat.getBoundingBox().getCenter();
        ctx.look.lookAt(ctx.player, centre);
        status = "getting into the boat";
        if (!ctx.look.isLookingAt(ctx.player, centre, 15.0F)) {
            return TaskStatus.RUNNING;
        }
        if (!BoatHelper.withinBoardingRange(ctx, boat)) {
            ctx.input.forward = true;
            return TaskStatus.RUNNING;
        }
        BoatHelper.board(ctx, boat);
        return TaskStatus.RUNNING;
    }

    // --- crossing ---------------------------------------------------------------------------

    private TaskStatus cross(BotContext ctx) {
        AbstractBoat boat = ridingBoat(ctx);
        if (boat == null) {
            state = State.BOARD;
            attemptTicks = 0;
            return TaskStatus.RUNNING;
        }

        double dx = destination.getX() + 0.5 - boat.getX();
        double dz = destination.getZ() + 0.5 - boat.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (BoatPolicy.arrived(distance)) {
            state = State.LAND;
            return TaskStatus.RUNNING;
        }

        if (distance < bestDistance - 0.5) {
            bestDistance = distance;
            stuckTicks = 0;
        } else if (++stuckTicks > MAX_STUCK_TICKS) {
            // Beached, or pushing at a shoreline it cannot climb. Walking the rest is better than
            // rowing into the bank for the remainder of the mission.
            state = State.LAND;
            return TaskStatus.RUNNING;
        }

        double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double yawError = Mth.wrapDegrees(desiredYaw - boat.getYRot());
        // How fast the hull is already coming round, measured rather than assumed. Sign matches
        // yawError: a positive rate is closing a positive error.
        double turnRate = lastBoatYaw == null
                ? 0.0 : -Mth.wrapDegrees(boat.getYRot() - lastBoatYaw);
        lastBoatYaw = boat.getYRot();
        int steer = BoatPolicy.steer(yawError, turnRate);
        ctx.input.right = steer > 0;
        ctx.input.left = steer < 0;
        ctx.input.forward = BoatPolicy.shouldAccelerate(yawError);
        // Keep the view on the heading so the overlay and any screenshot show where it is going.
        ctx.look.lookAt(ctx.player, new Vec3(destination.getX() + 0.5,
                ctx.player.getEyePosition().y, destination.getZ() + 0.5));
        ctx.debug.movement(destination, "boat crossing");
        status = String.format(java.util.Locale.ROOT,
                "crossing by boat - %.0f blocks left, heading off by %.0f degrees", distance, yawError);
        return TaskStatus.RUNNING;
    }

    private TaskStatus land(BotContext ctx) {
        if (ridingBoat(ctx) != null) {
            // Sneak is how a passenger leaves a vehicle, and the input layer already owns it.
            ctx.input.sneak = true;
            status = "getting out of the boat";
            return TaskStatus.RUNNING;
        }
        state = reclaim ? State.RECLAIM : State.LAND;
        if (!reclaim) {
            return finish(ctx, TaskStatus.SUCCESS, "crossed by boat");
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus reclaimBoat(BotContext ctx) {
        AbstractBoat boat = nearbyBoat(ctx);
        if (boat == null) {
            return finish(ctx, TaskStatus.SUCCESS, "crossed by boat");
        }
        if (++attemptTicks > MAX_ATTEMPT_TICKS) {
            // The boat is not worth a long fight; five planks is cheaper than the time.
            return finish(ctx, TaskStatus.SUCCESS, "crossed by boat, left it behind");
        }
        Vec3 centre = boat.getBoundingBox().getCenter();
        ctx.look.lookAt(ctx.player, centre);
        status = "picking the boat back up";
        if (ctx.look.isLookingAt(ctx.player, centre, 15.0F)
                && BoatHelper.withinBoardingRange(ctx, boat)) {
            BoatHelper.strike(ctx, boat);
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus finish(BotContext ctx, TaskStatus result, String reason) {
        ctx.input.reset();
        status = reason;
        return result;
    }

    // --- world queries ----------------------------------------------------------------------

    private static boolean carryingBoat(BotContext ctx) {
        return BoatHelper.carrying(ctx.player);
    }

    private static AbstractBoat ridingBoat(BotContext ctx) {
        return BoatHelper.ridden(ctx);
    }

    private static AbstractBoat nearbyBoat(BotContext ctx) {
        return BoatHelper.nearest(ctx, BOAT_SEARCH_RADIUS);
    }

    /** True for the two surfaces a boat can sit on: still water, and any kind of ice. */
    static boolean isBoatable(BlockState state) {
        return state.is(BlockTags.ICE) || state.getFluidState().isSource();
    }

    /**
     * Whether there is open water or ice close enough to be worth launching onto.
     * <p>
     * Callers use this to choose the boat over digging. Standing on a shoreline with a search that
     * has already failed on land, the water is the unexplored direction - and the far bank is a
     * fresh beach, which is where the thing being looked for usually is.
     */
    public static boolean crossingNearby(BotContext ctx, int radius) {
        // A boat is launched from a bank, standing on dry land. Inside a shipwreck - or anywhere
        // else already in the water - every direction is "water nearby", and answering yes there
        // makes the caller try to put a boat down in a flooded cabin.
        if (ctx.player.isInWater() || ctx.player.isUnderWater()) {
            return false;
        }
        BlockPos feet = ctx.player.blockPosition();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!ctx.level.isLoaded(candidate)) {
                        continue;
                    }
                    if (isBoatable(ctx.level.getBlockState(candidate))
                            && MovementHelper.isPassable(ctx.level, candidate.above())
                            && isOpenWater(ctx, candidate)
                            && isWideEnoughToBoat(ctx, candidate)
                            && Vision.isVisible(ctx, candidate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether a boatable block is water you could actually row on, rather than water with a
     * building on top of it.
     *
     * <p>The dry-feet guard is not enough on its own to keep boats out of shipwrecks. A wreck is
     * only partly flooded and sits on the sea floor, so the bot can be stood on a dry deck plank
     * with a flooded cabin next to it: {@code isInWater()} is false, the cabin is boatable, and a
     * boat goes down below decks. What separates the ocean from a cabin is headroom - open water
     * has nothing but air above it, while anything inside a hull has planks a block or two up.
     */
    /**
     * Whether this water is a crossing rather than a puddle.
     *
     * <p>The width rule existed but was applied far too late - only once a launch site had been
     * chosen and the planks were already spent. The question "is a boat worth it here" was answered
     * by "is there any water within twenty-four blocks", which is true of every stream, so a run
     * would gather five planks, find a table and build a boat to get over four blocks of river it
     * could have waded in a second. Measure the water before committing to any of that.
     */
    private static boolean isWideEnoughToBoat(BotContext ctx, BlockPos surface) {
        boolean ice = ctx.level.getBlockState(surface).is(BlockTags.ICE);
        int required = ice ? BoatPolicy.MIN_ICE_CROSSING : BoatPolicy.MIN_WATER_CROSSING;
        for (int[] direction : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int run = 0;
            while (run < required) {
                BlockPos ahead = surface.offset(direction[0] * (run + 1), 0, direction[1] * (run + 1));
                if (!ctx.level.isLoaded(ahead) || !isBoatable(ctx.level.getBlockState(ahead))) {
                    break;
                }
                run++;
            }
            if (run >= required) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOpenWater(BotContext ctx, BlockPos surface) {
        for (int dy = 1; dy <= OPEN_WATER_HEADROOM; dy++) {
            BlockPos above = surface.above(dy);
            if (!ctx.level.isLoaded(above)) {
                return false;
            }
            if (!MovementHelper.isPassable(ctx.level, above)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The nearest boatable surface that is on the way, preferring one that actually points at the
     * destination rather than the closest puddle in the opposite direction.
     */
    private BlockPos findLaunchSurface(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -LAUNCH_SEARCH_RADIUS; dx <= LAUNCH_SEARCH_RADIUS; dx++) {
            for (int dz = -LAUNCH_SEARCH_RADIUS; dz <= LAUNCH_SEARCH_RADIUS; dz++) {
                for (int dy = -4; dy <= 2; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (badLaunchSpots.contains(candidate.asLong()) || !ctx.level.isLoaded(candidate)) {
                        continue;
                    }
                    if (!isBoatable(ctx.level.getBlockState(candidate))) {
                        continue;
                    }
                    if (!MovementHelper.isPassable(ctx.level, candidate.above())
                            || !isOpenWater(ctx, candidate)) {
                        continue;
                    }
                    // Score by "how much closer to the destination does launching here leave us",
                    // so the bot walks along the bank rather than launching backwards. With no
                    // destination yet the nearest shore is the only sensible answer.
                    double toHere = Math.sqrt(feet.distSqr(candidate));
                    double score = destination == null
                            ? toHere : toHere + Math.sqrt(candidate.distSqr(destination));
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return best;
    }

    /**
     * Walks outward from the launch point, away from the player, until the water or ice ends on
     * dry land - the far bank. Returns null when the crossing is too short to be worth a boat, so
     * a puddle does not become a voyage.
     */
    private BlockPos findFarShore(BotContext ctx, BlockPos surface) {
        BlockPos feet = ctx.player.blockPosition();
        int dx = Integer.signum(surface.getX() - feet.getX());
        int dz = Integer.signum(surface.getZ() - feet.getZ());
        if (dx == 0 && dz == 0) {
            dx = 1;
        }
        boolean ice = ctx.level.getBlockState(surface).is(BlockTags.ICE);
        BlockPos lastWater = surface;
        for (int step = 1; step <= CROSSING_SCAN_LIMIT; step++) {
            BlockPos ahead = surface.offset(dx * step, 0, dz * step);
            if (!ctx.level.isLoaded(ahead)) {
                break;
            }
            if (isBoatable(ctx.level.getBlockState(ahead))) {
                lastWater = ahead;
                continue;
            }
            // First non-boatable block: the bank. Land one step short so the goal is reachable
            // from the water rather than inside the hillside behind it.
            int crossing = Math.max(Math.abs(lastWater.getX() - surface.getX()),
                    Math.abs(lastWater.getZ() - surface.getZ())) + 1;
            if (!BoatPolicy.worthLaunching(crossing, ice, true, 0)) {
                return null;
            }
            return lastWater.immutable();
        }
        int crossing = Math.max(Math.abs(lastWater.getX() - surface.getX()),
                Math.abs(lastWater.getZ() - surface.getZ())) + 1;
        return BoatPolicy.worthLaunching(crossing, ice, true, 0) ? lastWater.immutable() : null;
    }

    /** A solid block beside the water where the bot can stand while it places and boards. */
    private BlockPos findStandBeside(BotContext ctx, BlockPos surface) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos candidate = surface.offset(dx, dy, dz);
                    if (isBoatable(ctx.level.getBlockState(candidate))) {
                        continue;
                    }
                    if (MovementHelper.isPassable(ctx.level, candidate)
                            && MovementHelper.isPassable(ctx.level, candidate.above())
                            && MovementHelper.isSolidFloor(ctx.level, candidate.below())) {
                        return candidate.immutable();
                    }
                }
            }
        }
        return null;
    }
}
