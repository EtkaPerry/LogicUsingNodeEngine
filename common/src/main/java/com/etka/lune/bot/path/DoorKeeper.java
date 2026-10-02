package com.etka.lune.bot.path;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.compat.Hands;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Opens the doors on a walk, and shuts them again behind it.
 *
 * <p>It belongs to the walk ({@link com.etka.lune.bot.task.GotoTask}), not to the route. A walk
 * plans a fresh route every few dozen blocks, and a door opened under one route is still standing
 * open when the next one takes over: what was opened, and how often each door has been tried, have
 * to outlive the rebuild, or the door behind is forgotten and left open.</p>
 *
 * <ul>
 *   <li><b>A door is opened the way a player opens it.</b> Stop at it, turn to the slab itself and
 *       right-click it. Never through anything, and never from out of reach.</li>
 *   <li><b>A door opened is a door shut.</b> Once Lune is through and out of the doorway, what it
 *       opened is shut again if it is still in reach. A door left open in a base at night lets the
 *       zombies in, and a gate left open lets the sheep out. A door is never walked back to, never
 *       shut on something standing in it, and a door Lune found open is left open.</li>
 *   <li><b>A door that will not open is a wall.</b> After three clicks the walk ends and names the
 *       door. A server that protects it answers every click the same way, and the route search
 *       would only plan the same door again.</li>
 * </ul>
 */
public final class DoorKeeper {

    /** What a tick at a door came to. */
    public enum Work {
        /** Nothing to do at a door: walk on. */
        NONE,
        /** Turning to a door, clicking it, or waiting on it: hold still. */
        BUSY,
        /** A door on the route will not open. */
        REFUSED
    }

    private static final int MAX_CLICKS = 3;
    /** Ticks between two clicks on a door, so one click is never undone by the next. */
    private static final int CLICK_SPACING = 5;
    /** Turning towards a door this long without getting a clean click at it counts as a click. */
    private static final int MAX_AIM_TICKS = 40;
    /** How far the eyes may be from the slab; a little inside vanilla's own reach. */
    private static final double REACH = 4.0;
    /** How near a door has to be before Lune stops for it rather than walking on. */
    private static final double AT_THE_DOOR = 2.5;
    private static final float AIM_TOLERANCE = 10.0F;

    /** Clicks spent opening each door, by its base block. */
    private final Map<Long, Integer> openClicks = new HashMap<>();
    /** Clicks spent shutting each door, so one a server keeps open is let go of. */
    private final Map<Long, Integer> shutClicks = new HashMap<>();
    /** Doors this walk opened and has yet to shut, oldest first. */
    private final List<BlockPos> behind = new ArrayList<>();
    private final StatusText status = new StatusText();
    private int sinceClick = CLICK_SPACING;
    private int aimTicks;
    private boolean busy;

    /** Forgets everything, for a walk starting over. */
    public void reset() {
        openClicks.clear();
        shutClicks.clear();
        behind.clear();
        status.clear();
        sinceClick = CLICK_SPACING;
        aimTicks = 0;
        busy = false;
    }

    /** Whether the last tick was spent at a door, so the walk's line is about the door. */
    public boolean isBusy() {
        return busy;
    }

    /** What is being done at a door, or why one ended the walk. */
    public StatusText status() {
        return status;
    }

    /**
     * One tick on a route: shut a door left behind if one is due, or open one that is in the way of
     * the next steps. {@code ahead} is the route from the node being walked to onwards.
     */
    public Work tick(BotContext ctx, BlockPos feet, List<BlockPos> ahead) {
        sinceClick++;
        busy = false;
        if (shutBehind(ctx, ahead)) {
            return Work.BUSY;
        }
        BlockPos door = inTheWay(ctx, feet, ahead);
        return door == null ? Work.NONE : open(ctx, door);
    }

    /**
     * At the end of the walk: shut what was opened to get here, then let it end. Arriving just
     * inside a door is the commonest way to finish beside one.
     */
    public Work arrive(BotContext ctx) {
        sinceClick++;
        busy = false;
        return shutBehind(ctx, List.of()) ? Work.BUSY : Work.NONE;
    }

    /** The nearest shut door on the next steps that a hand opens, if Lune is at it. */
    private static BlockPos inTheWay(BotContext ctx, BlockPos feet, List<BlockPos> ahead) {
        BlockPos from = feet;
        for (int step = 0; step < Math.min(2, ahead.size()); step++) {
            BlockPos to = ahead.get(step);
            BlockPos door = Doorways.shutAcross(ctx.level, from, to);
            if (door != null) {
                double dx = door.getX() + 0.5 - ctx.player.getX();
                double dz = door.getZ() + 0.5 - ctx.player.getZ();
                return dx * dx + dz * dz <= AT_THE_DOOR * AT_THE_DOOR ? door : null;
            }
            from = to;
        }
        return null;
    }

    private Work open(BotContext ctx, BlockPos door) {
        String name = name(ctx, door);
        if (openClicks.getOrDefault(door.asLong(), 0) >= MAX_CLICKS) {
            status.set("lune.status.route.door_wont_open", name);
            ctx.debug.decide(name + " at " + door.toShortString() + " will not open; the route ends here");
            return Work.REFUSED;
        }
        busy = true;
        status.set("lune.status.route.opening_door", name);
        ctx.input.reset();
        if (click(ctx, door)) {
            openClicks.merge(door.asLong(), 1, Integer::sum);
            if (!behind.contains(door)) {
                behind.add(door);
            }
            ctx.debug.decide("opening " + name + " at " + door.toShortString());
        } else if (++aimTicks > MAX_AIM_TICKS) {
            // Turned to it all this time and never had a clean click: that is a try too, or a door
            // nothing can see would hold the walk here for good.
            aimTicks = 0;
            openClicks.merge(door.asLong(), 1, Integer::sum);
        }
        return Work.BUSY;
    }

    /**
     * Shuts the oldest door that is due: open still, behind Lune or no longer on the route, clear of
     * Lune's body, and in reach. True while it is at it.
     */
    private boolean shutBehind(BotContext ctx, List<BlockPos> ahead) {
        Iterator<BlockPos> doors = behind.iterator();
        while (doors.hasNext()) {
            BlockPos door = doors.next();
            BlockState state = ctx.level.getBlockState(door);
            if (!Doorways.isDoorway(state) || !Doorways.isOpen(state)
                    || shutClicks.getOrDefault(door.asLong(), 0) >= MAX_CLICKS) {
                // Shut already - by Lune, by somebody else - gone, or kept open by a server.
                doors.remove();
                continue;
            }
            if (occupied(ctx, door, true)) {
                // Lune is in it, so it opened: the clicks it took are spent, not a door refusing.
                openClicks.remove(door.asLong());
                continue;
            }
            if (ahead.contains(door)) {
                continue;
            }
            Vec3 eye = ctx.player.getEyePosition();
            if (eye.distanceToSqr(Doorways.handle(ctx.level, door, eye.y)) > REACH * REACH) {
                // Out of reach now. Not worth walking back for.
                doors.remove();
                continue;
            }
            if (occupied(ctx, door, false)) {
                // Something is standing in it. Leave it for now; once it is out of reach, it stays open.
                continue;
            }
            busy = true;
            String name = name(ctx, door);
            status.set("lune.status.route.closing_door", name);
            ctx.input.reset();
            if (click(ctx, door)) {
                shutClicks.merge(door.asLong(), 1, Integer::sum);
                ctx.debug.decide("shutting " + name + " behind, at " + door.toShortString());
            } else if (++aimTicks > MAX_AIM_TICKS) {
                aimTicks = 0;
                shutClicks.merge(door.asLong(), 1, Integer::sum);
            }
            return true;
        }
        return false;
    }

    /**
     * Turns to the door and right-clicks it once the eyes are on it, with a clear line and in reach.
     * True on the tick the click goes.
     */
    private boolean click(BotContext ctx, BlockPos door) {
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 aim = Doorways.handle(ctx.level, door, eye.y);
        ctx.look.lookAt(ctx.player, aim);
        if (sinceClick < CLICK_SPACING || !ctx.look.isLookingAt(ctx.player, aim, AIM_TOLERANCE)
                || eye.distanceToSqr(aim) > REACH * REACH) {
            return false;
        }
        // The line to the middle of the slab meets the slab before it gets there. Whatever it meets
        // first is what the hand would touch, and it has to be this door.
        BlockHitResult hit = ctx.level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, ctx.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockState touched = ctx.level.getBlockState(hit.getBlockPos());
        if (!Doorways.isDoorway(touched) || !Doorways.base(hit.getBlockPos(), touched).equals(door)) {
            return false;
        }
        ctx.input.sneak = false;
        ctx.gameMode.useItemOn(ctx.player, InteractionHand.MAIN_HAND, hit);
        Hands.swing(ctx.player, InteractionHand.MAIN_HAND);
        sinceClick = 0;
        aimTicks = 0;
        return true;
    }

    /**
     * Whether somebody is in the doorway: Lune herself when {@code self}, anyone else otherwise - a
     * villager, a dog that followed her in, another player. A dropped item does not count; a door
     * shuts on that without harm.
     */
    private static boolean occupied(BotContext ctx, BlockPos door, boolean self) {
        AABB doorway = new AABB(door.getX(), door.getY(), door.getZ(),
                door.getX() + 1.0, door.getY() + 2.0, door.getZ() + 1.0);
        if (self) {
            return ctx.player.getBoundingBox().intersects(doorway);
        }
        for (Entity entity : ctx.level.getEntities(ctx.player, doorway)) {
            if (entity instanceof LivingEntity living && living.isAlive() && !living.isSpectator()) {
                return true;
            }
        }
        return false;
    }

    private static String name(BotContext ctx, BlockPos door) {
        return ctx.level.getBlockState(door).getBlock().getName().getString();
    }
}
