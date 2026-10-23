package com.etka.lune.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * How far mobs on foot have to walk to reach each block round them: the ground a chaser covers.
 *
 * <p>A run used to score a spot by how far it stands from the chasers as the crow flies. The user
 * showed what that misses (2026-10-18): playing the lava maze against two Zombies and a Creeper
 * they jumped a gap every three seconds and changed island every six, for five minutes untouched,
 * because nothing that walks follows a body across a gap - every hop left the chasers on the far
 * side, to go the long way round or not at all. A spot across a gap is a long walk for them however
 * near it looks. So a run asks this, and the spot its chasers cannot walk to within
 * {@link #REACH} is the best there is.</p>
 *
 * <p>A mob is walked the way vanilla's ground navigation moves one: a block along or across,
 * corners only with both sides open, a block up, and a drop of up to three blocks rather than go
 * round. Never a jump over a gap, never through lava or anything else that hurts, which
 * {@link MovementHelper#canStandAt} already refuses as a floor or a place to stand.</p>
 *
 * <p>The same walk, with the jumps a route plans and a route's prices, is the ground the bot itself
 * covers ({@link #runner}), so a run can tell which of the two gets to a block first.</p>
 */
public final class ChaserReach {

    /** How far the field reaches, in blocks walked; beyond it a spot is as good as out of reach. */
    public static final double REACH = 32.0;
    /** Blocks a mob walks to search no further: the field round a handful of mobs on a crowded maze. */
    private static final int BUDGET = 6000;
    /** The furthest a walking mob drops rather than go round: three, a mob's own limit. */
    private static final int MOB_DROP = 3;
    private static final double DIAGONAL = Math.sqrt(2.0);
    /** A step up costs a mob the jump, half a block of walking. */
    private static final double CLIMB = 0.5;
    /** What a jump costs the bot beyond its length: the run-up and the line-up a take-off takes. */
    private static final double JUMP_EXTRA = 1.0;
    /** How far above or below a spot a chaser can stand and still strike it, in blocks. */
    private static final int STRIKE_HEIGHT = 2;
    private static final int[][] AROUND = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final long NONE = Long.MIN_VALUE;

    private final Map<Long, Cell> cells;

    private ChaserReach(Map<Long, Cell> cells) {
        this.cells = cells;
    }

    /** The field from mobs whose feet are at {@code feet}: where each stands, or just above it. */
    public static ChaserReach from(BlockGetter level, List<BlockPos> feet) {
        return flood(level, feet, 1, false, null);
    }

    /**
     * The ground the bot covers from {@code feet} on a run that keeps clear of {@code avoid}: the
     * same walk, kept out of water as the run's first route is, and the gap jumps a route plans up
     * to {@code maxGap} blocks of gap ({@link GapJumpPolicy#isGap}'s shapes), each costing its length
     * and a block more. Each block is reached the way the run's route would choose, priced as it is
     * priced - the ground round each chaser, lava beside the feet and a gap it would hurt to miss -
     * and {@link #walked} reads how far that way runs.
     *
     * <p>A spot fourteen blocks off can be a sixty-block run round the islands, which the chasers
     * cut across: two hits on the way, on 2026-10-18. A run weighs every spot by this.</p>
     *
     * <p>A body in the air is reckoned from where it comes down, up to four blocks below. With
     * {@code avoid} null the way is an ordinary route's, which keeps clear of nothing and never
     * jumps lava standing level with the take-off.</p>
     */
    public static ChaserReach runner(BlockGetter level, BlockPos feet, int maxGap, AStarPathfinder.Avoid avoid) {
        return flood(level, List.of(feet), Math.max(1, maxGap), true, avoid);
    }

    /**
     * @param maxGap the widest gap a jump crosses; 1, which no jump is, for a mob
     * @param bot    the bot, which jumps and prices its way as its route does, rather than a mob
     * @param run    what the bot's run keeps clear of, or null for an ordinary route
     */
    private static ChaserReach flood(BlockGetter level, List<BlockPos> feet, int maxGap, boolean bot,
                                     AStarPathfinder.Avoid run) {
        boolean swims = !bot;
        Map<Long, Cell> cells = new HashMap<>();
        PriorityQueue<Step> open = new PriorityQueue<>(Comparator.comparingDouble(Step::cost));
        for (BlockPos start : feet) {
            BlockPos stand = settle(level, start, bot ? MOB_DROP + 1 : 1, swims);
            if (stand != null && cells.putIfAbsent(stand.asLong(), new Cell(0.0, 0.0, NONE)) == null) {
                open.add(new Step(0.0, stand));
            }
        }
        int expanded = 0;
        while (!open.isEmpty() && expanded < BUDGET) {
            Step step = open.poll();
            Cell at = cells.get(step.pos().asLong());
            if (at == null || at.cost() < step.cost() - 1.0E-9) {
                continue;
            }
            expanded++;
            for (int[] way : AROUND) {
                boolean diagonal = way[0] != 0 && way[1] != 0;
                if (diagonal && !(MovementHelper.hasBodyClearance(level, step.pos().offset(way[0], 0, 0))
                        && MovementHelper.hasBodyClearance(level, step.pos().offset(0, 0, way[1])))) {
                    continue;
                }
                // The bot's way goes round a hurting corner, as its route does; a mob's cuts it.
                if (diagonal && bot
                        && MovementHelper.cutsAHurtingCorner(level, step.pos(), way[0], way[1])) {
                    continue;
                }
                BlockPos next = walk(level, step.pos(), way[0], way[1], swims);
                if (next == null) {
                    continue;
                }
                double length = (diagonal ? DIAGONAL : 1.0) + (next.getY() > step.pos().getY() ? CLIMB : 0.0);
                offer(level, cells, open, step.pos(), at, next, length, 0.0, bot, run);
            }
            if (bot && maxGap > 1) {
                jumps(level, step.pos(), at, maxGap, cells, open, run);
            }
        }
        return new ChaserReach(cells);
    }

    /**
     * Reaches {@code next} from {@code from} by a move {@code length} blocks long, when that is
     * the cheapest way there yet and still within {@link #REACH}.
     */
    private static void offer(BlockGetter level, Map<Long, Cell> cells, PriorityQueue<Step> open,
                              BlockPos from, Cell at, BlockPos next, double length, double hazard,
                              boolean bot, AStarPathfinder.Avoid run) {
        double walked = at.length() + length;
        if (walked > REACH) {
            return;
        }
        double cost = at.cost() + length + hazard;
        if (bot) {
            cost += run == null ? 0.0 : run.penalty(next);
            if (MovementHelper.nearLava(level, next)) {
                cost += price(run, next, AStarPathfinder.LAVA_PROXIMITY_COST);
            }
        }
        Cell known = cells.get(next.asLong());
        if (known == null || cost < known.cost() - 1.0E-9) {
            cells.put(next.asLong(), new Cell(cost, walked, from.asLong()));
            open.add(new Step(cost, next));
        }
    }

    /** What a hazard any route pays {@code full} for costs this way: a run's share, or all of it. */
    private static double price(AStarPathfinder.Avoid run, BlockPos pos, double full) {
        return run == null ? full : run.hazard(pos, full);
    }

    /**
     * The gap jumps a route would plan from {@code pos}, the way AStarPathfinder's relaxGapJumps
     * does - over lava standing level with the take-off too, on a run.
     */
    private static void jumps(BlockGetter level, BlockPos pos, Cell at, int maxGap, Map<Long, Cell> cells,
                              PriorityQueue<Step> open, AStarPathfinder.Avoid run) {
        if (!MovementHelper.isSolidFloor(level, pos.below()) || !MovementHelper.isPassable(level, pos.above(2))) {
            return;
        }
        for (int[] way : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            boolean missHurts = false;
            double flight = 0.0;
            for (int distance = 2; distance <= maxGap + 1; distance++) {
                BlockPos over = pos.offset(way[0] * (distance - 1), 0, way[1] * (distance - 1));
                if (!MovementHelper.hasBodyClearance(level, over) || MovementHelper.isSolidFloor(level, over.below())
                        || run == null && MovementHelper.isHarmful(level.getBlockState(over.below()))) {
                    break;
                }
                missHurts |= MovementHelper.fallHurts(level, over);
                // What the route prices the air at, as AStarPathfinder's relaxGapJumps does.
                if (run != null) {
                    flight = Math.max(flight, run.penalty(over));
                }
                double length = distance + JUMP_EXTRA;
                double hazard = flight + (missHurts ? price(run, pos, AStarPathfinder.HURTING_GAP_COST) : 0.0);
                BlockPos ahead = pos.offset(way[0] * distance, 0, way[1] * distance);
                if (distance == 2) {
                    BlockPos up = ahead.above(GapJumpPolicy.MAX_RISE);
                    if (MovementHelper.canStandAt(level, up, false)
                            && MovementHelper.isPassable(level, pos.offset(way[0], 2, way[1]))
                            && MovementHelper.isPassable(level, up.above(2))) {
                        offer(level, cells, open, pos, at, up, length + CLIMB, hazard, true, run);
                    }
                }
                int lowest = distance > GapJumpPolicy.MAX_GAP + 1 ? 0 : GapJumpPolicy.MAX_DROP;
                for (int drop = 0; drop <= lowest; drop++) {
                    BlockPos landing = ahead.below(drop);
                    if (MovementHelper.canStandAt(level, landing, false)) {
                        offer(level, cells, open, pos, at, landing, length, hazard, true, run);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Blocks walked to have the feet at {@code feet} - for a chaser field the nearest chaser's, for
     * the runner the length of the way the run would take - or infinity where none gets there within
     * {@link #REACH}.
     */
    public double walked(BlockPos feet) {
        Cell cell = cells.get(feet.asLong());
        return cell == null ? Double.POSITIVE_INFINITY : cell.length();
    }

    /** Whether the field has anywhere at all: false for a body standing nowhere it could start from. */
    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /**
     * The way to {@code feet}, from where the field starts to there, both ends included: every block
     * the feet stand in on the way, a jump being its take-off and its landing. Empty where the field
     * does not reach.
     */
    public List<BlockPos> way(BlockPos feet) {
        Cell cell = cells.get(feet.asLong());
        if (cell == null) {
            return List.of();
        }
        List<BlockPos> way = new ArrayList<>();
        long at = feet.asLong();
        while (cell != null && way.size() <= BUDGET) {
            way.add(BlockPos.of(at));
            at = cell.parent();
            cell = at == NONE ? null : cells.get(at);
        }
        Collections.reverse(way);
        return way;
    }

    /**
     * How far a chaser walks before it stands within {@code strike} blocks of {@code spot}, middle to
     * middle - near enough to hit someone there, or to light a fuse - or infinity when it never does
     * within {@link #REACH}.
     *
     * <p>Not being able to walk onto a spot is not the same as not being able to hurt someone on it.
     * A Zombie at the edge of a one-block gap hits across it, and a Creeper's blast crosses any gap;
     * the four-seed batch of 2026-10-18 died twice of exactly that, hopping a block or two to spots
     * out of the chasers' walk and inside their reach. Two blocks of gap are past a Zombie's arm.</p>
     */
    public double toStrike(BlockPos spot, double strike) {
        int reach = (int) Math.ceil(strike);
        double best = Double.POSITIVE_INFINITY;
        for (int dy = -STRIKE_HEIGHT; dy <= STRIKE_HEIGHT; dy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx * dx + dy * dy + dz * dz > strike * strike) {
                        continue;
                    }
                    Cell cell = cells.get(spot.offset(dx, dy, dz).asLong());
                    if (cell != null && cell.length() < best) {
                        best = cell.length();
                    }
                }
            }
        }
        return best;
    }

    /**
     * Where a body whose feet are at {@code pos} stands: there, or up to {@code below} blocks under
     * it - a mob mid-step, a bot in the air over where it comes down.
     */
    private static BlockPos settle(BlockGetter level, BlockPos pos, int below, boolean swims) {
        for (int down = 0; down <= below; down++) {
            BlockPos stand = pos.below(down);
            if (MovementHelper.canStandAt(level, stand, swims)) {
                return stand.immutable();
            }
            if (down > 0 && !MovementHelper.isPassable(level, stand)) {
                return null;
            }
        }
        return null;
    }

    /** Where a body walking one block along {@code (dx, dz)} from {@code from} stands next, or null. */
    private static BlockPos walk(BlockGetter level, BlockPos from, int dx, int dz, boolean swims) {
        BlockPos flat = from.offset(dx, 0, dz);
        if (MovementHelper.canStandAt(level, flat, swims)) {
            return flat;
        }
        BlockPos up = flat.above();
        if (MovementHelper.canStandAt(level, up, swims) && MovementHelper.isPassable(level, from.above(2))) {
            return up;
        }
        if (!MovementHelper.hasBodyClearance(level, flat)) {
            return null;
        }
        for (int drop = 1; drop <= MOB_DROP; drop++) {
            BlockPos below = flat.below(drop);
            if (MovementHelper.canStandAt(level, below, swims)) {
                return below;
            }
            if (!MovementHelper.isPassable(level, below)) {
                return null;
            }
        }
        return null;
    }

    /**
     * A block reached: what the way there cost - its length, and for the runner what the run's
     * route pays besides - how long it is, and the block it came from.
     */
    private record Cell(double cost, double length, long parent) {}

    private record Step(double cost, BlockPos pos) {}
}
