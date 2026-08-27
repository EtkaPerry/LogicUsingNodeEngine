package com.etka.lune.bot.task;

/**
 * Pure rules for which clutch a fatal fall gets; kept testable without Minecraft.
 *
 * <p>Water and a boat both cancel a fall and want opposite timing to do it. A bucket is one packet
 * with no answer needed, so it can be poured in the last three blocks and decided by that single
 * tick. A boat cannot: only the server spawns the hull, so the placement has to go out early enough
 * for the entity to come back and be climbed into, and riding it is the only part that cancels
 * anything - landing on the hull is an ordinary landing on the block underneath, at full damage.
 *
 * <p>That makes water the better answer wherever it works at all, and makes the choice worth making
 * once rather than every tick: swapping methods halfway spends the very ticks that decide the
 * outcome. The two places water does not work are the two the boat exists for - nothing to pour,
 * and the Nether, where a bucket emptied into the air evaporates on the spot.
 */
final class ClutchPolicy {

    /** How a fall is being survived. */
    enum Method { NONE, WATER, CUSHION, BOAT }

    /**
     * Ticks a boat needs between going down and being sat in: one for the server to send the hull
     * back, one for the boarding to be accepted, and one spare for a server that is not on this
     * machine. Measured the hard way - a fall at 2.28 blocks a tick put the hull down 1.2 ticks
     * before the ground and never got near it.
     */
    static final int BOAT_TICKS_NEEDED = 3;

    /** Vanilla's downward acceleration and air drag, which together give the 3.92 terminal speed. */
    private static final double FALL_ACCELERATION = 0.08;
    private static final double FALL_DRAG = 0.98;
    /** Blocks of a fall that never hurt, before any landing softens the rest. */
    private static final double FREE_FALL_BLOCKS = 3.0;

    private ClutchPolicy() {}

    /**
     * Which method this fall should use.
     *
     * <p>Water first wherever it works: one packet, nothing to answer, and the bucket comes back.
     * Then the boat, which is the cheapest thing to spend - but only while there is still time to
     * climb into one, because a hull nobody is sitting in is an ordinary block to land on. Past
     * that the cushion is not a luxury, it is the only thing left that lands in a single packet,
     * so a fast fall spends the block rather than a boat it cannot reach.
     *
     * @param current       what was chosen earlier in the same fall, or {@code NONE} at the start
     * @param waterUsable   a water bucket is carried and the dimension will keep the water
     * @param cushionUsable a block that softens a landing is carried
     * @param boatUsable    a boat is carried
     * @param boatHasTime   the fall is still slow enough to place a hull and board it
     * @param boatCommitted a hull has already been paid for on this fall, so the boat remains the
     *                      answer even though the item has left the inventory
     */
    static Method choose(Method current, boolean waterUsable, boolean cushionUsable,
                         boolean boatUsable, boolean boatHasTime, boolean boatCommitted) {
        Method chosen = current;
        if (chosen == Method.WATER && !waterUsable) {
            chosen = Method.NONE;
        }
        if (chosen == Method.CUSHION && !cushionUsable) {
            chosen = Method.NONE;
        }
        if (chosen == Method.BOAT && !boatUsable && !boatCommitted) {
            chosen = Method.NONE;
        }
        if (chosen != Method.NONE) {
            return chosen;
        }
        if (waterUsable) {
            return Method.WATER;
        }
        if (boatUsable && boatHasTime) {
            return Method.BOAT;
        }
        if (cushionUsable) {
            return Method.CUSHION;
        }
        return boatUsable || boatCommitted ? Method.BOAT : Method.NONE;
    }

    /** Honors a learned preference only while it remains a physically valid clutch. */
    static Method choosePreferred(Method current, Method preferred,
                                  boolean waterUsable, boolean cushionUsable,
                                  boolean boatUsable, boolean boatHasTime,
                                  boolean boatCommitted) {
        Method retained = choose(current, waterUsable, cushionUsable,
                boatUsable, boatHasTime, boatCommitted);
        if (current != Method.NONE && retained == current) {
            return current;
        }
        if (preferred == Method.WATER && waterUsable) {
            return Method.WATER;
        }
        if (preferred == Method.CUSHION && cushionUsable) {
            return Method.CUSHION;
        }
        if (preferred == Method.BOAT
                && (boatCommitted || boatUsable && boatHasTime)) {
            return Method.BOAT;
        }
        return choose(Method.NONE, waterUsable, cushionUsable,
                boatUsable, boatHasTime, boatCommitted);
    }

    /**
     * How fast the player will be going when they arrive, in blocks per tick.
     *
     * <p>Stepped rather than solved because vanilla's fall is a recurrence - each tick the speed
     * gains gravity and then loses two percent - and the answer is wanted at the top of the fall,
     * where the current speed is nothing like the impact speed. Twenty blocks of drop is the
     * difference between a boat that works and a boat that is still in the hand at the bottom.
     */
    static double impactSpeed(double dropRemaining, double currentSpeed) {
        double speed = Math.max(0.0, currentSpeed);
        double left = dropRemaining;
        for (int tick = 0; tick < 400 && left > 0.0; tick++) {
            speed = (speed + FALL_ACCELERATION) * FALL_DRAG;
            left -= speed;
        }
        return speed;
    }

    /**
     * Whether a hull can still be put down and climbed into before the ground arrives.
     *
     * @param reachBelowFeet how far under the feet a placement can land, which is the whole window
     */
    static boolean boatHasTime(double reachBelowFeet, double dropRemaining, double currentSpeed) {
        double speed = impactSpeed(dropRemaining, currentSpeed);
        return speed <= 0.0 || reachBelowFeet / speed >= BOAT_TICKS_NEEDED;
    }

    /**
     * Vanilla's own sum: everything past the free three blocks, times whatever share of it the
     * landing leaves. A slime block or a web leaves none of it, hay and honey a fifth.
     */
    static int fallDamage(double distance, double damageShare) {
        return (int) Math.floor(Math.max(0.0, distance - FREE_FALL_BLOCKS) * damageShare);
    }

    /** Whether landing on something that leaves {@code damageShare} of the fall is survivable. */
    static boolean survives(double distance, double damageShare, double health) {
        return fallDamage(distance, damageShare) < health;
    }
}
