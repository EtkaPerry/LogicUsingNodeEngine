package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.LuneProfiler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Batting a Ghast's fireball back at the Ghast that fired it.
 *
 * <p>A melee swing at a large fireball is not an attack at all. {@code Player.attack} checks
 * {@code deflectProjectile} first, before damage, criticals or the sweep are even considered: if the
 * target carries the {@code redirectable_projectile} tag the fireball simply takes the player's look
 * angle as its new velocity and the player as its new owner. A fireball owned by a player then hits
 * its shooter for 1000 damage <em>bypassing invulnerability</em>, so one that arrives back at the
 * Ghast kills it outright - whatever the bot is holding, at any range, on the first hit.
 *
 * <p>That makes this the single highest-value reflex the bot has in the Nether, and it is decided
 * entirely by the packet rather than by the animation. Four details matter:
 *
 * <ul>
 *   <li><b>The look angle is the aim.</b> The deflection does not send the fireball back the way it
 *       came; it sends it exactly where the player is looking. The head has to be on the Ghast
 *       <em>before</em> the swing, because the rotation the server uses is the one it was last told
 *       about, not the one set in the same tick as the attack.</li>
 *   <li><b>The cooldown is irrelevant.</b> The deflect branch runs ahead of the attack-strength
 *       scale, so there is no reason to wait for a charged swing - and waiting is how the fireball
 *       arrives instead. The swing does still spend the charge.</li>
 *   <li><b>Reach is measured to the hitbox, not the centre.</b> The server allows the eye to be
 *       {@code entityInteractionRange + 3} from the fireball's box, which is generous; see
 *       {@link #REACH_SLACK} for why this asks for much less than that.</li>
 *   <li><b>Some items have the packet dropped.</b> A piercing weapon, or one with a minimum attack
 *       charge that has not been met, makes the server ignore the attack entirely - silently. The
 *       hand has to be checked rather than assumed, see {@link #canSwing}.</li>
 * </ul>
 *
 * <p>Shared rather than private to Self Preservation because the geometry question - is that thing
 * going to hit me, and can I reach it yet - is the same one a fight with a Ghast asks.
 */
public final class FireballDeflect {

    /**
     * How far out a shot is worth watching, in blocks.
     *
     * <p>A fireball leaves the Ghast at a tenth of a block per tick and accelerates to a ceiling of
     * 1.9, so it covers its first fifteen blocks in about twenty ticks. Twenty-four blocks is
     * therefore roughly a second and a half of warning - several times what the head needs to come
     * round - while keeping the query small enough to run every tick.
     */
    public static final double WATCH_RADIUS = 24.0;

    /**
     * How far off the body a shot may pass and still count as coming at us, in blocks.
     *
     * <p>Not half the player's width, because a fireball that misses still explodes. A Ghast's is
     * explosion power 1, which throws the player and takes hearts out to several blocks, so a shot
     * lined up to pass just overhead is worth batting rather than watching.
     */
    private static final double BLAST_CLEARANCE = 3.0;

    /**
     * Slack left under the server's own reach test, in blocks.
     *
     * <p>The server would accept a swing from {@code entityInteractionRange + 3}. Asking for a
     * single block of margin instead is deliberate: the client predicts the deflection locally the
     * moment the attack is sent, so a swing the server <em>rejects</em> still turns the local copy
     * of the fireball around. With a position update only every tenth tick, that leaves the bot
     * believing it is safe while the real fireball keeps coming. Swinging only when it is comfortably
     * inside the allowance costs a tick or two of patience and never lies about the outcome.
     */
    private static final double REACH_SLACK = 1.0;

    /** Below this a velocity is noise rather than a heading, in blocks per tick, squared. */
    private static final double MIN_HEADING = 1.0E-6;

    /**
     * Vanilla's own numbers for a fireball in flight: a tenth of a block of acceleration a tick, then
     * five percent off for drag, which together give the 1.9 ceiling it never quite reaches.
     *
     * <p>Both are read back from the game rather than guessed: {@code onDeflection} resets the
     * acceleration power to {@code 0.1} for a shot batted by an attack, and
     * {@code AbstractHurtingProjectile.getInertia} is {@code 0.95}.
     */
    private static final double FLIGHT_ACCELERATION = 0.1;
    private static final double FLIGHT_DRAG = 0.95;
    /** The speed {@code AIM_DEFLECT} starts a batted shot at: a unit look vector, so exactly one. */
    private static final double DEFLECTED_START_SPEED = 1.0;
    /** A ceiling on the flight estimate, so a shooter across the world cannot spin the loop. */
    private static final int MAX_FLIGHT_TICKS = 200;
    /**
     * The fastest drift worth leading, in blocks per tick.
     *
     * <p>A Ghast's flying speed attribute is 0.06 and its move control adds a tenth of a block a tick
     * toward where it is heading, so its real drift is a fraction of this. Anything above it is not
     * the Ghast moving - it is a position resync arriving as one tick of apparent motion, and
     * multiplying that by a twenty-tick flight aims at open sky.
     */
    private static final double MAX_TRACKED_DRIFT = 0.75;

    private FireballDeflect() {}

    /**
     * Every redirectable projectile on course for the player, nearest first.
     *
     * <p>All of them, not just the nearest, because how many there are changes the answer. One shot is
     * worth standing still for; three arriving together are 18 points of contact damage before the
     * splash, and a bot that bats one of them has chosen to eat the other two.
     *
     * <p>Deliberately not cached. The ordinary hostile scan can afford to run every fifth tick because
     * a zombie needs a second to cross a block; the window in which a fireball is both in reach and
     * not yet touching the player is two or three ticks wide, and a cache is how it is missed. The
     * profiler puts this scan under a tenth of a millisecond, so the cost is not the problem a cache
     * would solve.
     */
    public static List<Projectile> inbound(BotContext ctx) {
        AABB box = ctx.player.getBoundingBox().inflate(WATCH_RADIUS);
        LuneProfiler.push("fireball scan");
        try {
            List<Entity> found = ctx.level.getEntities(ctx.player, box, entity ->
                    entity instanceof Projectile shot
                            && shot.isAlive()
                            && shot.is(EntityTypeTags.REDIRECTABLE_PROJECTILE)
                            && !alreadyOurs(ctx.player, shot)
                            && threatens(ctx.player, shot));
            return found.stream()
                    .map(Projectile.class::cast)
                    .sorted(Comparator.comparingDouble(shot -> shot.distanceToSqr(ctx.player)))
                    .toList();
        } finally {
            LuneProfiler.pop();
        }
    }

    /**
     * Whether this shot is still closing on the player and lined up to hurt.
     *
     * <p>Tested against the flight path rather than the current distance, which is what separates a
     * fireball aimed at the bot from one crossing the valley behind it. A shot that has already gone
     * past is no longer a threat however close it still is.
     */
    public static boolean threatens(Player player, Projectile shot) {
        return onCourse(player.getBoundingBox().getCenter(), shot.position(),
                shot.getDeltaMovement());
    }

    /**
     * The geometry behind {@link #threatens}, on plain vectors so it can be checked without a world.
     *
     * <p>Two questions, in order. Is the shot still coming - the component of the offset along the
     * heading has to be positive, or the fireball is already behind us and accelerating away. And
     * will it come close enough to matter - the remaining perpendicular component is how far off the
     * body it will pass, which is what {@link #BLAST_CLEARANCE} is measured against.
     */
    public static boolean onCourse(Vec3 bodyCentre, Vec3 shotPosition, Vec3 velocity) {
        if (velocity.lengthSqr() < MIN_HEADING) {
            return false;
        }
        Vec3 heading = velocity.normalize();
        Vec3 toBody = bodyCentre.subtract(shotPosition);
        double closing = toBody.dot(heading);
        if (closing <= 0.0) {
            return false;
        }
        return toBody.subtract(heading.scale(closing)).length() <= BLAST_CLEARANCE;
    }

    /**
     * Roughly how many ticks until the shot arrives.
     *
     * <p>An over-estimate on purpose, and known to be one: the fireball accelerates every tick it
     * flies, so it gets here sooner than its current speed says. Anything deciding whether there is
     * still time should treat this as the optimistic number.
     */
    public static double ticksAway(Player player, Projectile shot) {
        return ticksToArrive(Math.sqrt(shot.getBoundingBox().distanceToSqr(player.getEyePosition())),
                shot.getDeltaMovement().length());
    }

    /** The arithmetic behind {@link #ticksAway}; a stopped shot never arrives. */
    public static double ticksToArrive(double gap, double speed) {
        return speed * speed < MIN_HEADING ? Double.POSITIVE_INFINITY : gap / speed;
    }

    /** Whether the server will accept a swing at this shot from where the player is standing. */
    public static boolean inReach(Player player, Entity shot) {
        double reach = player.entityInteractionRange() + REACH_SLACK;
        return shot.getBoundingBox().distanceToSqr(player.getEyePosition()) <= reach * reach;
    }

    /**
     * The point to look at so that the deflection flies back into the shooter.
     *
     * <p>The Ghast - look at the thing you want to hit, which is what a player does and what anyone
     * watching expects to see the bot do. A Ghast is four blocks wide and four tall, which is what
     * makes that work: the deflection leaves along the player's look angle rather than from the
     * player, so the fireball sets off parallel to the line being sighted down, offset by however far
     * off that line the shot happened to be - and by the time it is in reach that offset is well
     * inside the radius of what it is being sent at.
     *
     * <p>Where the Ghast <em>will be</em>, though, not where it is. See {@link #leadShooter}.
     *
     * <p>With no shooter on record the aim is back up the flight path, which is where it was fired
     * from whether or not the client still knows what fired it.
     */
    public static Vec3 returnAim(BotContext ctx, Projectile shot) {
        LivingEntity shooter = shooter(shot);
        if (shooter != null) {
            return leadShooter(shot.position(), shooter.getBoundingBox().getCenter(),
                    shooter.getKnownSpeed());
        }
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 back = backAlongFlight(eye, shot.getDeltaMovement());
        return back == null ? eye.add(ctx.player.getLookAngle()) : back;
    }

    /**
     * Where the shooter will be by the time the deflection gets there.
     *
     * <p>Aiming at a Ghast's current position throws the shot away. It floats the whole time - its
     * move control pushes it a tenth of a block a tick toward wherever it has decided to go - and the
     * batted fireball needs the better part of twenty ticks to cross twenty-five blocks. That is
     * several blocks of drift against a four-block target, so the deflection sails past something
     * the bot was looking straight at. Leading it is the difference between a reflex that looks right
     * and one that kills.
     *
     * <p>Measured from the shot rather than from the player because that is where the deflection
     * actually begins; over this range the two differ by a few blocks, which is a tick or two of lead.
     */
    public static Vec3 leadShooter(Vec3 shotPosition, Vec3 shooterCentre, Vec3 shooterDrift) {
        Vec3 drift = shooterDrift.lengthSqr() > MAX_TRACKED_DRIFT * MAX_TRACKED_DRIFT
                ? Vec3.ZERO : shooterDrift;
        if (drift.lengthSqr() < MIN_HEADING) {
            return shooterCentre;
        }
        int flight = deflectedFlightTicks(shooterCentre.distanceTo(shotPosition));
        // One correction pass. Leading moves the target, which moves the range, which set the lead -
        // and a Ghast drifts slowly enough that a second pass shifts the answer by less than its own
        // width, so iterating further buys nothing.
        Vec3 led = shooterCentre.add(drift.scale(flight));
        return shooterCentre.add(drift.scale(deflectedFlightTicks(led.distanceTo(shotPosition))));
    }

    /**
     * How many ticks a batted shot needs to cover {@code distance}.
     *
     * <p>Stepped rather than solved, because the flight is a recurrence: the deflection leaves at
     * exactly one block a tick - {@code AIM_DEFLECT} hands it a unit look vector - and every tick
     * after that it gains a tenth and then loses five percent, climbing toward a ceiling of 1.9. Over
     * a twenty-five block shot that is the difference between eighteen ticks and twenty-five, and the
     * lead is a multiple of it.
     */
    public static int deflectedFlightTicks(double distance) {
        double speed = DEFLECTED_START_SPEED;
        double left = distance;
        int ticks = 0;
        while (left > 0.0 && ticks < MAX_FLIGHT_TICKS) {
            speed = (speed + FLIGHT_ACCELERATION) * FLIGHT_DRAG;
            left -= speed;
            ticks++;
        }
        return ticks;
    }

    /**
     * A point one block back up the flight path, or null when the shot is not going anywhere and the
     * current aim may as well be kept. Only the direction is used; the distance is thrown away.
     */
    public static Vec3 backAlongFlight(Vec3 eye, Vec3 velocity) {
        Vec3 back = velocity.scale(-1.0);
        return back.lengthSqr() < MIN_HEADING ? null : eye.add(back.normalize());
    }

    /**
     * Whatever fired this, when the client knows it.
     *
     * <p>The owner does reach the client: a projectile sends its shooter's entity id in the
     * add-entity packet, so this is the game's own answer rather than a guess at which nearby Ghast
     * it must have been.
     */
    public static LivingEntity shooter(Projectile shot) {
        return shot.getOwner() instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    /** True once this shot belongs to the player - it has been batted and is on its way out. */
    public static boolean alreadyOurs(Player player, Projectile shot) {
        return shot.getOwner() == player;
    }

    /**
     * Whether a swing would reach the server at all.
     *
     * <p>Both of these make {@code handleAttack} discard the packet without a word: a piercing
     * weapon is routed through a different interaction entirely, and an item with a minimum attack
     * charge is refused until the charge is met. Either one turns the deflect into a bot that stands
     * there swinging at a fireball until it explodes, so the hand is checked, not assumed.
     */
    public static boolean canSwing(Player player) {
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        return !held.has(DataComponents.PIERCING_WEAPON) && !player.cannotAttackWithItem(held, 5);
    }

    /**
     * Whether a swing is available now or after one hotbar slot change.
     *
     * <p>Asked before the episode commits to batting rather than stepping, so it must not move
     * anything itself - {@link #freeTheHand} is the half that acts. The two share
     * {@link #swingable} so they cannot disagree about whether the swap was worth trying.
     */
    public static boolean swingPossible(Player player) {
        if (canSwing(player)) {
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            if (swingable(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts something in hand that a swing will not be thrown away for.
     *
     * <p>Any item will do, including none: the deflection ignores damage completely, so an empty
     * hotbar slot bats a fireball exactly as well as a netherite sword.
     */
    public static boolean freeTheHand(BotContext ctx) {
        return InventoryHelper.equip(ctx, FireballDeflect::swingable) >= 0;
    }

    private static boolean swingable(ItemStack stack) {
        return !stack.has(DataComponents.PIERCING_WEAPON)
                && !stack.has(DataComponents.MINIMUM_ATTACK_CHARGE);
    }

    /** Swings at the shot, which is what actually sends it back. */
    public static void hit(BotContext ctx, Entity shot) {
        ctx.gameMode.attack(ctx.player, shot);
        ctx.player.swing(InteractionHand.MAIN_HAND);
    }
}
