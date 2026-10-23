package com.etka.lune.bot.task;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where to stand to watch the next eye fly, when the last one went behind something.
 *
 * <p>An eye is read by watching it, and a flight lost to a tree three blocks out is a direction
 * and nothing more. A player who threw into a tree knows which way the eye went and steps round
 * the tree before throwing again; this picks the step. Spots a few blocks off - the near ones
 * first, straight along the way the eye went before off to either side - are judged by how much
 * of a flight that way could be seen from them: the next eye climbs eight blocks over the twelve
 * it flies, so the sight lines run up along that slope. The nearest spot that sees the whole of
 * it wins; failing that, the one that sees the most; and when nowhere nearby sees more than here,
 * the throw is made from here.</p>
 *
 * <p>Pure: the ground and the sight lines are asked through the two interfaces, so the choice is
 * tested without a world.</p>
 */
final class EnderEyeClearing {

    /** How far to step, nearest first: a few blocks, as a player steps round a tree. */
    static final int[] DISTANCES = {3, 5, 7, 10};
    /** Where round the way the eye went to look for the step, straight ahead first. */
    static final double[] OFFSET_DEGREES = {0.0, -25.0, 25.0, -50.0, 50.0, -80.0, 80.0};
    /** Blocks along the next flight to look at, out to the twelve a far eye flies. */
    static final double[] SAMPLES = {2.0, 4.0, 6.0, 8.0, 10.0, 12.0};
    /** The next eye climbs eight over the twelve it flies. */
    static final double CLIMB = 8.0 / 12.0;
    /** A standing player's eyes above their feet. */
    static final double EYE_HEIGHT = 1.62;

    /** Ground a player could stand on near a column, at about a height; null for none. */
    interface Floor {
        BlockPos near(int x, int y, int z);
    }

    /** Whether a look from one point reaches another with nothing in between. */
    interface Sight {
        boolean clear(Vec3 from, Vec3 to);
    }

    /**
     * A spot to throw from.
     *
     * @param seen     how many of {@link #SAMPLES} are in view from it
     * @param distance how far it is from where the bot stands, in blocks
     */
    record Spot(BlockPos feet, int seen, int distance) {}

    private EnderEyeClearing() {}

    /** How many points of a flight from {@code feet} the way of {@code (dirX, dirZ)} can be seen. */
    static int seenFrom(BlockPos feet, double dirX, double dirZ, Sight sight) {
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
        int seen = 0;
        for (double along : SAMPLES) {
            Vec3 there = new Vec3(eye.x + dirX * along, eye.y + CLIMB * along, eye.z + dirZ * along);
            if (sight.clear(eye, there)) {
                seen++;
            }
        }
        return seen;
    }

    /**
     * The spot to move to before the next throw, or null when standing still is as good.
     *
     * @param feet where the bot stands
     * @param dirX the way the eye went, as a unit vector
     */
    static Spot choose(BlockPos feet, double dirX, double dirZ, Floor floor, Sight sight) {
        int here = seenFrom(feet, dirX, dirZ, sight);
        if (here >= SAMPLES.length) {
            return null;
        }
        double way = Math.atan2(dirZ, dirX);
        Spot best = null;
        for (int distance : DISTANCES) {
            for (double offset : OFFSET_DEGREES) {
                double angle = way + Math.toRadians(offset);
                int x = feet.getX() + (int) Math.round(Math.cos(angle) * distance);
                int z = feet.getZ() + (int) Math.round(Math.sin(angle) * distance);
                BlockPos stand = floor.near(x, feet.getY(), z);
                if (stand == null) {
                    continue;
                }
                int seen = seenFrom(stand, dirX, dirZ, sight);
                if (seen >= SAMPLES.length) {
                    return new Spot(stand, seen, distance);
                }
                if (best == null || seen > best.seen()) {
                    best = new Spot(stand, seen, distance);
                }
            }
        }
        return best != null && best.seen() > here ? best : null;
    }
}
