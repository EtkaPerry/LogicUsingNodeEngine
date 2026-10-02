package com.etka.lune.bot.task;

import com.etka.lune.bot.knowledge.StrongholdKnowledge;
import com.etka.lune.bot.util.SightMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Where to walk next inside a stronghold, chosen only from places the bot has seen.
 *
 * <p>Every spot the eyes have passed through that a player could stand on, on a stronghold floor,
 * is a candidate until the bot has stood near it and looked round. Picking the next one is the
 * whole of the exploring: the far end of the corridor it can see, or the doorway at the side of
 * it, before anything it would have to turn back for.</p>
 *
 * <p>The corridors the eyes have not reached yet are not on this list, and that is the point. The
 * route to a chosen spot is still planned the way every route is, but where to go is never
 * decided by stone the bot has not looked at.</p>
 */
final class PortalRoomSearch {

    /** Stood here and turned the head round: nothing within this is worth coming back for. */
    static final double LOOKED_ROUND = 5.0;
    /** Walked past, looking ahead with a glance to either side. */
    static final double WALKED_PAST = 3.5;
    /**
     * A look round covers the floor the bot stands on, not the one under it. A staircase seen
     * through the hole just dug, or a room down a flight of steps, is close by and not yet visited.
     */
    private static final int SAME_FLOOR = 2;

    /** How much a spot far from anywhere already looked at is worth, per block of that distance. */
    private static final double NEWNESS = 1.2;
    /** Past this, further from anything seen is not more interesting, only further. */
    private static final double NEWNESS_CAP = 24.0;
    /** What keeping on the way the bot was already going is worth, in blocks of walking. */
    private static final double KEEP_GOING = 3.0;
    /**
     * A small pull away from the start. The portal room is always several pieces deep, never one
     * of the first rooms off the staircase, so between two otherwise equal leads the deeper one
     * is the better bet.
     */
    private static final double DEPTH = 0.05;
    /** Climbing and dropping cost more than walking the same distance flat. */
    private static final double HEIGHT = 1.0;

    /** Whether a player could stand with their feet in a cell, once any door there is out of the way. */
    interface Ground {
        boolean standable(BlockPos feet);
    }

    /** What the eyes made of a cell: {@link SightMap#UNSEEN}, {@link SightMap#OPEN} or {@link SightMap#STOPPED}. */
    interface Seen {
        byte state(long cell);
    }

    /** The four ways out of a cell on the level. */
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private record Mark(BlockPos at, double radius) {}

    private final int centreX;
    private final int centreZ;
    private final int ceiling;
    /** Candidates, each with its distance to the nearest place already looked at. */
    private final Long2DoubleOpenHashMap frontier = new Long2DoubleOpenHashMap();
    private final LongOpenHashSet refused = new LongOpenHashSet();
    private final List<Mark> marks = new ArrayList<>();

    /**
     * @param ceiling the highest a stronghold floor can be; the start staircase is its top
     */
    PortalRoomSearch(int centreX, int centreZ, int ceiling) {
        this.centreX = centreX;
        this.centreZ = centreZ;
        this.ceiling = ceiling;
    }

    boolean hasFrontier() {
        return !frontier.isEmpty();
    }

    int frontierSize() {
        return frontier.size();
    }

    /** A cell the eyes just saw into: it, and the cell under it, may be somewhere to stand. */
    void seen(long cell, Ground ground) {
        BlockPos pos = BlockPos.of(cell);
        offer(pos, ground);
        offer(pos.below(), ground);
    }

    private void offer(BlockPos pos, Ground ground) {
        long key = pos.asLong();
        if (frontier.containsKey(key) || refused.contains(key) || !inReach(pos)) {
            return;
        }
        double nearest = Double.POSITIVE_INFINITY;
        for (Mark mark : marks) {
            nearest = Math.min(nearest, distance(mark.at(), pos));
        }
        if (!ground.standable(pos)) {
            return;
        }
        frontier.put(key, nearest);
    }

    private boolean inReach(BlockPos pos) {
        int reach = StrongholdKnowledge.REACH + 16;
        return Math.abs(pos.getX() - centreX) <= reach && Math.abs(pos.getZ() - centreZ) <= reach
                && pos.getY() <= ceiling;
    }

    /**
     * The bot has been at {@code at} and looked round everything within {@code radius} of it.
     *
     * <p>A spot near it is only done with once everything round it has been seen too - on its
     * level, at head height, and a step down. Standing at the top of a spiral staircase puts the
     * whole stairwell within a few blocks, and the steps going down out of sight are exactly what
     * is left to explore; clearing them because they were near was how the first run to get
     * inside a stronghold looked round once and gave up.</p>
     */
    void looked(BlockPos at, double radius, Seen seen) {
        Mark mark = new Mark(at.immutable(), radius);
        marks.add(mark);
        var entries = frontier.long2DoubleEntrySet().iterator();
        while (entries.hasNext()) {
            Long2DoubleMap.Entry entry = entries.next();
            BlockPos cell = BlockPos.of(entry.getLongKey());
            if (covers(mark, cell) && surroundingsSeen(cell, seen)) {
                entries.remove();
                continue;
            }
            double distance = distance(at, cell);
            if (distance < entry.getDoubleValue()) {
                entry.setValue(distance);
            }
        }
    }

    /**
     * Whether every way out of a cell has been seen for what it is. A wall beside it is an answer;
     * an opening needs its head room and the ground under it seen too, because a step down out of
     * sight is where a stairwell goes. What is inside a wall is never asked about - no look ever
     * reaches it, and a rule that waited for it looped for two thousand ticks on the spot beside
     * the bot.
     */
    private static boolean surroundingsSeen(BlockPos cell, Seen seen) {
        for (int[] side : SIDES) {
            BlockPos next = cell.offset(side[0], 0, side[1]);
            byte level = seen.state(next.asLong());
            if (level == SightMap.UNSEEN) {
                return false;
            }
            if (level == SightMap.OPEN && (seen.state(next.above().asLong()) == SightMap.UNSEEN
                    || seen.state(next.below().asLong()) == SightMap.UNSEEN)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The bot has stood at {@code target} and looked round from it: whatever is still unanswered
     * right there cannot be answered from there, so it is done with. This is what guarantees each
     * walk makes progress - without it a spot one block away is "reached" at once and chosen again.
     */
    void visited(BlockPos target) {
        frontier.remove(target.asLong());
        for (int[] side : SIDES) {
            frontier.remove(target.offset(side[0], 0, side[1]).asLong());
        }
        frontier.remove(target.above().asLong());
        frontier.remove(target.below().asLong());
    }

    /** A cell that is not somewhere to go - the bot's own way in - and never will be. */
    void exclude(BlockPos cell) {
        refused.add(cell.asLong());
        frontier.remove(cell.asLong());
    }

    private static boolean covers(Mark mark, BlockPos cell) {
        if (Math.abs(cell.getY() - mark.at().getY()) > SAME_FLOOR) {
            return false;
        }
        double dx = cell.getX() - mark.at().getX();
        double dz = cell.getZ() - mark.at().getZ();
        return dx * dx + dz * dz <= mark.radius() * mark.radius();
    }

    /**
     * A spot the bot could not get to, which will not be tried again.
     *
     * <p>That spot only. Crossing out the block round it as well emptied the whole list inside
     * the start staircase, which is five blocks across: two failed walks on its tight turns and
     * there was nowhere left, with most of the stairwell still to see.</p>
     */
    void refuse(BlockPos target) {
        refused.add(target.asLong());
        frontier.remove(target.asLong());
    }

    /**
     * The best place to walk to next, or null when everything seen has been looked round.
     *
     * @param headingX which way the bot has been going, as a unit vector, or zero for no preference
     */
    BlockPos next(BlockPos from, double headingX, double headingZ) {
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Long2DoubleMap.Entry entry : frontier.long2DoubleEntrySet()) {
            BlockPos cell = BlockPos.of(entry.getLongKey());
            double score = score(from, cell, entry.getDoubleValue(), headingX, headingZ);
            if (score < bestScore) {
                bestScore = score;
                best = cell;
            }
        }
        return best;
    }

    double score(BlockPos from, BlockPos cell, double newness, double headingX, double headingZ) {
        double dx = cell.getX() - from.getX();
        double dz = cell.getZ() - from.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        double walk = flat + HEIGHT * Math.abs(cell.getY() - from.getY());
        double ahead = flat < 1.0 ? 0.0 : (dx * headingX + dz * headingZ) / flat;
        double deep = Math.hypot(cell.getX() - centreX, cell.getZ() - centreZ);
        double fresh = Math.min(newness, NEWNESS_CAP);
        return walk - NEWNESS * fresh - KEEP_GOING * ahead - DEPTH * deep;
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
