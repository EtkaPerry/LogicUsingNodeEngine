package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.compat.Hands;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Putting a boat down, getting into it, and getting it back.
 *
 * <p>A boat is placed the same odd way a bucket is emptied - see {@link BucketHelper} for the long
 * version. {@code BoatItem} implements {@code use} and never {@code useOn}, so the block click every
 * other placement in this mod sends does nothing at all, and the hull ends up wherever the item's
 * own ray cast from the eyes lands. Aiming is the whole interaction.
 *
 * <p>Two facts decide everything a caller can do with that:
 *
 * <ul>
 *   <li><b>A hull needs its whole footprint free.</b> It appears centred on the point the ray hit,
 *       and vanilla refuses the placement outright when anything - a wall, a ledge one block wide,
 *       the player's own body - overlaps the 1.375 by 0.5625 box that lands there. That is why a
 *       boat cannot be stuck to a wall the way a torch can: the hull would be half inside it.
 *   <li><b>Only the server spawns it.</b> {@code BoatItem.use} adds the entity and takes the item
 *       inside a server-side branch, so the client that sent the use packet sees neither until the
 *       spawn comes back. A refused placement and a slow one look identical from here; the only
 *       honest test is whether a hull turns up, which is what {@link #nearest} is for.
 * </ul>
 *
 * <p>Riding is also the only part of a boat that stops a fall. A passenger's fall distance is reset
 * every tick they are seated, so a boat boarded mid-air cancels the whole drop - while landing on
 * top of the hull is an ordinary landing on whatever block is under it, at full damage. "Get in the
 * boat" is therefore not a nicety of the technique, it <em>is</em> the technique.
 */
public final class BoatHelper {

    /** Half of the 1.375-block hull, which is the same width for every wood and for a raft. */
    private static final double HULL_HALF_WIDTH = 0.6875;
    private static final double HULL_HEIGHT = 0.5625;
    /**
     * Slack allowed when deciding a hull is close enough to board. The server checks the same
     * distance against the position the last movement packet gave it, which is a tick behind a
     * falling player, and allows three blocks of its own on top - so a little optimism here is
     * repaid by boarding a tick earlier, and a tick is most of what a clutch has.
     */
    private static final double BOARDING_SLACK = 1.5;
    /**
     * What the server itself allows: {@code handleInteract} tests entity range plus three blocks.
     * Half a block is kept back for the position packet it is testing against being a tick old.
     */
    private static final double SERVER_BOARDING_SLACK = 2.5;

    private BoatHelper() {
    }

    public static boolean carrying(Player player) {
        return InventoryHelper.anyMatch(player, stack -> stack.is(ItemTags.BOATS));
    }

    /** Moves a boat into the hand, returning its hotbar slot or -1 when there is none to move. */
    public static int equip(BotContext ctx) {
        return InventoryHelper.equip(ctx, stack -> stack.is(ItemTags.BOATS));
    }

    public static boolean inHand(BotContext ctx) {
        return ctx.player.getItemInHand(InteractionHand.MAIN_HAND).is(ItemTags.BOATS);
    }

    /** The boat the player is sitting in, or {@code null}. */
    public static AbstractBoat ridden(BotContext ctx) {
        return ctx.player.getVehicle() instanceof AbstractBoat boat ? boat : null;
    }

    /** The closest live hull within {@code radius} of the player, or {@code null}. */
    public static AbstractBoat nearest(BotContext ctx, double radius) {
        AABB area = ctx.player.getBoundingBox().inflate(radius);
        AbstractBoat best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : ctx.level.getEntities(ctx.player, area, e -> e instanceof AbstractBoat)) {
            if (!(entity instanceof AbstractBoat boat) || !boat.isAlive()) {
                continue;
            }
            double distance = boat.getBoundingBox().distanceToSqr(ctx.player.getEyePosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = boat;
            }
        }
        return best;
    }

    /** The ray {@code BoatItem.use} is about to cast: eyes, view vector, capped at block reach. */
    public static BlockHitResult aimRay(BotContext ctx) {
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 end = eye.add(ctx.player.calculateViewVector(ctx.player.getXRot(), ctx.player.getYRot())
                .scale(ctx.player.blockInteractionRange()));
        // Fluid.ANY, because a boat is put down on the surface of water as readily as on stone.
        return ctx.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.ANY, ctx.player));
    }

    /**
     * Where the hull would appear if the item were used this tick, or {@code null} when the ray
     * reaches nothing - which is also the answer to "is that surface in reach yet".
     */
    public static Vec3 placementTarget(BotContext ctx) {
        BlockHitResult hit = aimRay(ctx);
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : null;
    }

    /** The box a hull placed at {@code where} would occupy. */
    public static AABB hullAt(Vec3 where) {
        return new AABB(where.x - HULL_HALF_WIDTH, where.y, where.z - HULL_HALF_WIDTH,
                where.x + HULL_HALF_WIDTH, where.y + HULL_HEIGHT, where.z + HULL_HALF_WIDTH);
    }

    /**
     * Whether a hull would actually fit there. Asking first is worth a ray cast: a refused
     * placement is silent, and the caller may only get one attempt before it matters.
     */
    public static boolean fits(BotContext ctx, Vec3 where) {
        AABB hull = hullAt(where);
        // The player counts too. Vanilla tests the new hull against everything it could be pushed
        // by, and a player is pushable, so standing (or falling) in the space is a refusal.
        return ctx.level.noCollision(null, hull)
                && !hull.intersects(ctx.player.getBoundingBox());
    }

    /** Sends the one interaction a boat item answers to. */
    public static void place(BotContext ctx) {
        ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
        Hands.swing(ctx.player, InteractionHand.MAIN_HAND);
    }

    public static boolean withinBoardingRange(BotContext ctx, AbstractBoat boat) {
        return ctx.player.isWithinEntityInteractionRange(boat, BOARDING_SLACK);
    }

    /**
     * The furthest the server might still accept a boarding from - it checks the same distance with
     * three whole blocks of its own slack, against a position that is a packet behind.
     *
     * <p>Worth having as a separate question from {@link #withinBoardingRange}. Walking up to a
     * moored boat can afford to be tidy about it; a clutch cannot, because the hull is only in
     * front of it for two or three ticks and a refused packet costs nothing while a skipped tick
     * can cost the fall.
     */
    public static boolean mightReachToBoard(BotContext ctx, AbstractBoat boat) {
        return ctx.player.isWithinEntityInteractionRange(boat, SERVER_BOARDING_SLACK);
    }

    /**
     * Sends the interaction that seats the player.
     *
     * <p>Crouching cancels it - the packet carries the shift state and the boat reads it as "the
     * player meant to do something else" - so callers must have let go of sneak first.
     */
    public static void board(BotContext ctx, AbstractBoat boat) {
        Vec3 seat = boat.getBoundingBox().getCenter();
        ctx.gameMode.interact(ctx.player, boat, new EntityHitResult(boat, seat),
                InteractionHand.MAIN_HAND);
    }

    /** One hit on the hull. Boats are picked back up by breaking them, not by clicking them. */
    public static void strike(BotContext ctx, AbstractBoat boat) {
        ctx.gameMode.attack(ctx.player, boat);
        Hands.swing(ctx.player, InteractionHand.MAIN_HAND);
    }
}
