package com.etka.lune.bot.task;

import com.etka.lune.bot.knowledge.StrongholdKnowledge;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads where a stronghold is out of one Eye of Ender's flight.
 *
 * <p>The eye does not wander toward the stronghold. It is launched at the corner of the chunk the
 * stronghold starts in and flies a dead straight line at it: every step it takes is aimed at a
 * point twelve blocks along that line, so it can never leave it. Only the height and the speed
 * change. So the flight is the bearing, exactly, and the server reports the eye's position to a
 * 4096th of a block - a bearing good to a few millionths of a radian.</p>
 *
 * <p>A bearing alone has no distance, but the target is not just anywhere on it. It is a chunk
 * corner, a multiple of sixteen on both axes, and it is on one of the rings
 * {@link StrongholdKnowledge} knows about. A line that fine passes that close to very few corners,
 * and from near spawn it passes close to exactly one about nine times in ten. When it is closer
 * than twelve blocks the eye stops short of its usual twelve and dives, and then the corner is
 * simply where it went.</p>
 */
final class EnderEyePolicy {

    /** How far along its line vanilla sends an eye when the stronghold is further than that. */
    static final double STEER = 12.0;
    /** Position updates are whole 4096ths of a block; this is the spread one rounding leaves. */
    private static final double ROUNDING = 1.0 / 4096.0 / Math.sqrt(6.0);
    /** A flight that strays this far from one line is not an eye flying at a stronghold. */
    private static final double CROOKED = 0.05;
    /** Standard deviations of bearing error a corner may sit off the line and still count. */
    private static final double TOLERANCE = 4.0;
    /** Where an eye that stopped short came to rest, versus the twelve it flies otherwise. */
    private static final double STOPPED_SHORT = STEER - 0.15;
    /** Last positions over which a flight has to have stopped moving to count as settled. */
    private static final int SETTLED_SAMPLES = 4;
    private static final double SETTLED_SPREAD = 0.1;
    private static final int GRID = 16;

    enum Kind {
        /** The stronghold is somewhere further along the line than the eye went. */
        FAR,
        /** The eye stopped short and dove: the stronghold's corner is where it went. */
        NEAR,
        /** Too little flight, or not a straight one - throw again. */
        UNREADABLE
    }

    /**
     * What one flight said.
     *
     * @param angularError one standard deviation of the bearing, in radians
     * @param nearX        where a {@link Kind#NEAR} eye dove, on the x axis
     */
    record Reading(Kind kind, double originX, double originZ, double dirX, double dirZ,
                   double angularError, int nearX, int nearZ) {

        static Reading unreadable(double originX, double originZ) {
            return new Reading(Kind.UNREADABLE, originX, originZ, 0.0, 0.0, 0.0, 0, 0);
        }

        /** A point {@code distance} blocks along the line, for walking on when nothing fits. */
        double[] pointAlong(double distance) {
            return new double[]{originX + dirX * distance, originZ + dirZ * distance};
        }
    }

    /**
     * A chunk corner the line passes close enough to.
     *
     * @param distance how far along the line it is from where the eye was thrown
     * @param error    how far off the line, in standard deviations of the bearing
     */
    record Candidate(int x, int z, double distance, double error) {}

    private EnderEyePolicy() {}

    /**
     * Reads a flight.
     *
     * @param originX where the eye was thrown from, which is where the player stood
     * @param originY the height it was thrown at
     * @param path    the eye's positions as the server reported them, oldest first
     */
    static Reading read(double originX, double originY, double originZ, List<Vec3> path) {
        if (path == null || path.size() < 3) {
            return Reading.unreadable(originX, originZ);
        }
        double sxx = 0.0;
        double sxz = 0.0;
        double szz = 0.0;
        double meanX = 0.0;
        double meanZ = 0.0;
        double furthest = 0.0;
        for (Vec3 point : path) {
            double dx = point.x - originX;
            double dz = point.z - originZ;
            sxx += dx * dx;
            sxz += dx * dz;
            szz += dz * dz;
            meanX += dx;
            meanZ += dz;
            furthest = Math.max(furthest, Math.sqrt(dx * dx + dz * dz));
        }
        if (furthest < 1.0) {
            return Reading.unreadable(originX, originZ);
        }
        // The line through the throw point that the flight sits closest to.
        double angle = 0.5 * Math.atan2(2.0 * sxz, sxx - szz);
        double dirX = Math.cos(angle);
        double dirZ = Math.sin(angle);
        if (dirX * meanX + dirZ * meanZ < 0.0) {
            dirX = -dirX;
            dirZ = -dirZ;
        }

        double squares = 0.0;
        for (Vec3 point : path) {
            double off = (point.x - originX) * dirZ - (point.z - originZ) * dirX;
            squares += off * off;
        }
        double wobble = Math.sqrt(squares / path.size());
        if (wobble > CROOKED) {
            return Reading.unreadable(originX, originZ);
        }
        double angularError = Math.max(ROUNDING, wobble) / Math.sqrt(sxx + szz);

        int tail = Math.min(SETTLED_SAMPLES, path.size());
        double lowest = Double.POSITIVE_INFINITY;
        double highest = 0.0;
        double sum = 0.0;
        for (int index = path.size() - tail; index < path.size(); index++) {
            Vec3 point = path.get(index);
            double reach = Math.hypot(point.x - originX, point.z - originZ);
            lowest = Math.min(lowest, reach);
            highest = Math.max(highest, reach);
            sum += reach;
        }
        boolean settled = highest - lowest < SETTLED_SPREAD;
        double rest = sum / tail;
        // An eye sent twelve blocks on climbs eight; one sent at the corner itself heads for the
        // corner's own height, which is y = 0. Thrown from above that, the two go opposite ways,
        // and the height tells them apart even when the corner happened to be twelve blocks off.
        boolean dove = originY > 2.0 && path.get(path.size() - 1).y < originY - 1.0;

        if ((settled && rest < STOPPED_SHORT) || dove) {
            double x = originX + dirX * rest;
            double z = originZ + dirZ * rest;
            return new Reading(Kind.NEAR, originX, originZ, dirX, dirZ, angularError,
                    snap(x), snap(z));
        }
        if (!settled && furthest < STEER - 0.5) {
            // Still on its way when the watching stopped: the bearing is fine, but whether it was
            // going to stop short is not known yet.
            return Reading.unreadable(originX, originZ);
        }
        return new Reading(Kind.FAR, originX, originZ, dirX, dirZ, angularError, 0, 0);
    }

    /** A dive lands on a corner in vanilla; a modded target keeps the block it dove at. */
    private static int snap(double coordinate) {
        double corner = Math.round(coordinate / GRID) * (double) GRID;
        return Math.abs(coordinate - corner) < 0.75 ? (int) corner : (int) Math.floor(coordinate);
    }

    /**
     * Every chunk corner on a ring that the line passes close enough to, nearest first.
     *
     * <p>Walked one grid line at a time along whichever axis the line runs closer to, so each
     * crossing has exactly one corner worth checking: the one the crossing is nearest.</p>
     *
     * @param maxDistance nothing further along the line than this can be the nearest stronghold
     */
    static List<Candidate> candidates(Reading reading, double maxDistance) {
        List<Candidate> found = new ArrayList<>();
        if (reading.kind() != Kind.FAR) {
            return found;
        }
        double ox = reading.originX();
        double oz = reading.originZ();
        double ux = reading.dirX();
        double uz = reading.dirZ();
        boolean alongX = Math.abs(ux) >= Math.abs(uz);
        double major = alongX ? ux : uz;
        double start = (alongX ? ox : oz) + major * STEER;
        double end = (alongX ? ox : oz) + major * maxDistance;
        int from = (int) Math.floor(Math.min(start, end) / GRID) - 1;
        int to = (int) Math.ceil(Math.max(start, end) / GRID) + 1;
        for (int line = from; line <= to; line++) {
            double fixed = line * (double) GRID;
            double t = (fixed - (alongX ? ox : oz)) / major;
            double other = (alongX ? oz + t * uz : ox + t * ux);
            double nearest = Math.round(other / GRID) * (double) GRID;
            double x = alongX ? fixed : nearest;
            double z = alongX ? nearest : fixed;
            double cx = x - ox;
            double cz = z - oz;
            double distance = cx * ux + cz * uz;
            if (distance <= STEER || distance > maxDistance) {
                continue;
            }
            double off = Math.abs(cx * uz - cz * ux);
            double spread = reading.angularError() * distance;
            if (off > TOLERANCE * spread + 1.0E-3 || !StrongholdKnowledge.onARing(x, z)) {
                continue;
            }
            found.add(new Candidate((int) x, (int) z, distance, off / Math.max(spread, 1.0E-12)));
        }
        found.sort(Comparator.comparingDouble(Candidate::distance));
        return found;
    }

    /** The corner the line passes closest to, measured in how well the bearing is known. */
    static Candidate bestGuess(List<Candidate> candidates) {
        return candidates.stream().min(Comparator.comparingDouble(Candidate::error)).orElse(null);
    }
}
