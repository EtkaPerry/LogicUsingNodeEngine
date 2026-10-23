package com.etka.lune.bot.task;

import com.etka.lune.bot.path.AStarPathfinder;
import com.etka.lune.bot.path.ChaserReach;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Every hostile a run is getting away from, and what that means for where it goes.
 *
 * <p>Self Preservation used to plan an escape round one hostile, the one it was tracking: a spot
 * further from that one than the bot stood, a route priced round that one alone, a step away from
 * that one. With three Zombies on the lava maze's bastion (2026-10-18) that ran the bot from one
 * Zombie into the next - five hits in the open - and then into a dead-end corridor with a Zombie at
 * each end, where every fresh route turned it round toward the other end, and the last jumped it
 * back into reach. The user asked for Creepers and the rest to count the same way. So every hostile
 * the guard has seen lately is a chaser, whatever its kind, with the berth its kind needs, and
 * where to run, which way is away and what a route keeps clear of are answered from all of them.</p>
 *
 * <p>Pure, so those answers are tested without a world.</p>
 */
public final class Chasers {

    /**
     * One hostile as a run knows it: where it was when last seen - a run that cannot see it does not
     * know where it is now - how wide a berth a route gives it, how near it has to get to hurt
     * someone, in blocks (a Zombie's arm, a Creeper's fuse), how many blocks it walks while the bot
     * runs one ({@link #pace}), and how near a route may go only when there is no other way at all
     * (its {@code core}: none for a Zombie, the ground round a Creeper where its fuse lights, or
     * where its blast lands once lit).
     */
    public record Chaser(double x, double y, double z, double berth, double strike, double pace, double core) {

        /** A chaser a route may pass as near as its price allows. */
        public Chaser(double x, double y, double z, double berth, double strike, double pace) {
            this(x, y, z, berth, strike, pace, 0.0);
        }
    }

    /**
     * What a spot no chaser can get near enough to strike is worth, in blocks: further than any walk
     * the field reads ({@link ChaserReach#REACH}), so it beats every spot they can.
     */
    public static final double OUT_OF_REACH = ChaserReach.REACH + 8.0;
    /**
     * How far the bot has run before a way can be cut off, in blocks. A chaser beside the bot is
     * beside it for the first steps whichever way it goes; what a way is judged by is whether it
     * gets clear after them.
     */
    public static final double GRACE = 2.5;
    /**
     * What a chaser's pace is reckoned at, over what it is on open ground: the bot loses time a
     * walker does not, lining a jump up, turning, holding back from an edge.
     */
    private static final double PACE_MARGIN = 1.25;
    /** What a body keeps of its speed each tick on ordinary ground: the block's 0.6 and the air's 0.91. */
    private static final double GROUND_KEPT = 0.546;
    /** A player's movement speed, walking; sprinting is three tenths more. */
    private static final double PLAYER_SPEED = 0.1;
    private static final double SPRINT_SHARE = 1.3;

    private final List<Chaser> all;

    public Chasers(List<Chaser> all) {
        this.all = List.copyOf(all);
    }

    public List<Chaser> all() {
        return all;
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    /** The block each chaser's feet were in, for a walk of the ground it covers. */
    public List<BlockPos> feet() {
        List<BlockPos> feet = new ArrayList<>(all.size());
        for (Chaser chaser : all) {
            feet.add(BlockPos.containing(chaser.x(), chaser.y() + 0.01, chaser.z()));
        }
        return feet;
    }

    /**
     * How many blocks a mob with movement speed {@code speed} walks while the bot runs one, with
     * {@link #PACE_MARGIN} to spare: a Zombie about half a block against a sprint, a baby Zombie
     * more than one.
     *
     * <p>Vanilla moves a mob by the square of its speed and a player by the speed alone: a mob's
     * forward input is its speed, and the push is that input times the speed again, where a
     * player's input is one. Both keep {@link #GROUND_KEPT} of their speed each tick, so on open
     * ground a Zombie (0.23) walks 0.114 blocks a tick, a Creeper (0.25) 0.135, a Spider (0.3)
     * 0.194, and the bot walks 0.216 and sprints 0.281.</p>
     *
     * @param runs whether the bot sprints on this run; see {@code GotoTask.mayRun}
     */
    public static double pace(double speed, boolean runs) {
        double mob = 0.98 * speed * speed / (1.0 - GROUND_KEPT);
        double bot = 0.98 * PLAYER_SPEED * (runs ? SPRINT_SHARE : 1.0) / (1.0 - GROUND_KEPT);
        return PACE_MARGIN * mob / bot;
    }

    /**
     * The flat way away from all of them, as a unit {@code {x, z}}, or null when there are none or
     * they pull exactly against each other. Each pushes along the line from it through the body, by
     * its berth over the square of its distance: the nearest decides once it is near, a wider berth
     * pushes harder, and the far ones only lean on the answer.
     */
    public double[] away(double x, double z) {
        double sumX = 0.0;
        double sumZ = 0.0;
        for (Chaser chaser : all) {
            double dx = x - chaser.x();
            double dz = z - chaser.z();
            double distance = Math.hypot(dx, dz);
            if (distance < 1.0E-6) {
                continue;
            }
            double weight = chaser.berth() / Math.max(distance * distance, 0.25);
            sumX += dx / distance * weight;
            sumZ += dz / distance * weight;
        }
        double length = Math.hypot(sumX, sumZ);
        return length < 1.0E-9 ? null : new double[] {sumX / length, sumZ / length};
    }

    /** How far {@code (x, y, z)} is from the nearest of them; infinity when there are none. */
    public double nearest(double x, double y, double z) {
        double least = Double.POSITIVE_INFINITY;
        for (Chaser chaser : all) {
            least = Math.min(least, Math.sqrt(square(x - chaser.x(), y - chaser.y(), z - chaser.z())));
        }
        return least;
    }

    /**
     * The room a block leaves the bot as its feet get there, in blocks: how much further the soonest
     * chaser still has to walk before it can strike someone on it, once the bot has run {@code run}
     * blocks to it and each chaser has walked its pace times that. Below nothing, a chaser is there
     * first.
     *
     * @param toStrike for each chaser, in order, how far it walks before it is within its strike of
     *                 the block ({@link ChaserReach#toStrike}); infinity - across a gap wider than its
     *                 reach - counts as {@link #OUT_OF_REACH}
     */
    public double lead(double[] toStrike, double run) {
        double lead = OUT_OF_REACH;
        for (int i = 0; i < all.size(); i++) {
            lead = Math.min(lead, Math.min(toStrike[i], OUT_OF_REACH) - all.get(i).pace() * run);
        }
        return lead;
    }

    /**
     * Whether the chasers get to a way first: true when, somewhere along it past the first
     * {@link #GRACE} blocks, a block leaves no {@link #lead}. A way past a chaser, or round a loop
     * it can cut across, is a way it stands in by the time the bot gets there - the sixty-six block
     * route of 2026-10-18 was hit twice on the way to a spot that was fine when the bot got there.
     *
     * @param run      blocks run to each block of the way, in order
     * @param toStrike each block's {@code toStrike}, as {@link #lead} takes it, or null for a block
     *                 already known to be clear ({@link #clearByDistance})
     */
    public boolean cutOff(double[] run, double[][] toStrike) {
        return worstLead(run, toStrike) < 0.0;
    }

    /**
     * The least room a way leaves past its first {@link #GRACE} blocks: the block a chaser comes
     * nearest to striking first, by how much it is still clear of it, below nothing where the chaser
     * gets there first, and infinity for a way with nothing to judge. Among ways that are all cut
     * off, this is what tells the least bad: a body pinned between chasers that takes no way at all
     * stands where it is until it is struck, and twice on 2026-10-18 it shuffled in place for three
     * or four seconds until it was.
     */
    public double worstLead(double[] run, double[][] toStrike) {
        double worst = Double.POSITIVE_INFINITY;
        for (int k = 0; k < run.length; k++) {
            if (run[k] > GRACE && toStrike[k] != null) {
                worst = Math.min(worst, lead(toStrike[k], run[k]));
            }
        }
        return worst;
    }

    /**
     * Whether the block whose middle is at {@code (x, z)}, {@code run} blocks along a way, is
     * certainly not cut off, by distance alone: no chaser could walk within its strike in the time,
     * even straight there. A walk is never shorter than the flat line it covers, and a block more
     * allows for a chaser's walk being reckoned from the middle of the block it stands in, so a
     * block this answers yes for needs no walking field.
     */
    public boolean clearByDistance(double x, double z, double run) {
        for (Chaser chaser : all) {
            double straight = Math.hypot(x - chaser.x(), z - chaser.z()) - chaser.strike() - 1.0;
            if (straight - chaser.pace() * run < 0.0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a chaser stands in the way of a body at {@code (x, y, z)} making for a waypoint at
     * {@code (toX, toZ)}: within {@link #IN_THE_WAY} blocks of it, on its level, and ahead of it - no
     * more than sixty degrees off the line to the waypoint. A body pressed against a mob on its route
     * goes nowhere however long it presses: on the bastion (2026-10-18) a run pushed into a Zombie
     * standing on its way for thirteen ticks while a Creeper's fuse ran out behind it.
     */
    public boolean inTheWay(double x, double y, double z, double toX, double toZ) {
        double wayX = toX - x;
        double wayZ = toZ - z;
        double way = Math.hypot(wayX, wayZ);
        for (Chaser chaser : all) {
            double dx = chaser.x() - x;
            double dz = chaser.z() - z;
            double distance = Math.hypot(dx, dz);
            if (distance > IN_THE_WAY || Math.abs(chaser.y() - y) > ON_ITS_LEVEL) {
                continue;
            }
            if (way < 1.0E-6 || distance < 1.0E-6 || (dx * wayX + dz * wayZ) / (distance * way) >= AHEAD) {
                return true;
            }
        }
        return false;
    }

    /** How near a chaser has to stand to be in a body's way, in blocks: within a step and an arm. */
    static final double IN_THE_WAY = 2.5;
    /** How far above or below a body a chaser can stand and still be in its way, in blocks. */
    private static final double ON_ITS_LEVEL = 1.5;
    /** The least a chaser's bearing may share with the way ahead to be in it: sixty degrees off. */
    private static final double AHEAD = 0.5;

    /**
     * The ground a route keeps clear of: a zone round each chaser as wide as its berth, where a step
     * at the middle costs {@code cost} more blocks of walking, and its core costs a long way round
     * ({@link AStarPathfinder.Avoid#CORE_COST}); outside them, lava and the gaps over it at
     * {@code hazardShare} of their price ({@link AStarPathfinder.Avoid}).
     */
    public AStarPathfinder.Avoid avoid(double cost, double hazardShare) {
        List<AStarPathfinder.Avoid.Zone> zones = new ArrayList<>(all.size());
        for (Chaser chaser : all) {
            zones.add(new AStarPathfinder.Avoid.Zone(chaser.x(), chaser.y(), chaser.z(), chaser.berth(), cost,
                    chaser.core()));
        }
        return new AStarPathfinder.Avoid(zones, hazardShare);
    }

    private static double square(double x, double y, double z) {
        return x * x + y * y + z * z;
    }
}
