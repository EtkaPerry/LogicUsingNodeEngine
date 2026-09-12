package com.etka.lune.bot.path;

import com.etka.lune.bot.util.ToolSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over block positions, where a node is the block the player's feet occupy.
 * <p>
 * The search runs synchronously on the client thread under a strict node budget rather than on a
 * worker thread, because reading chunks off-thread is not safe. The budget is what makes that
 * viable: when it runs out the search returns the best partial path found so far instead of
 * failing, and {@link com.etka.lune.bot.task.GotoTask} simply re-paths on arrival. Long journeys
 * therefore come out as a series of cheap hops rather than one expensive search.
 */
public final class AStarPathfinder {

    private static final double JUMP_COST = 0.7;
    private static final double CLIMB_COST = 1.4;
    /**
     * Falls are priced by the square of the drop, so a long descent is only chosen when the
     * alternative is much longer - a bot that free-falls at every opportunity takes chip damage all
     * session and eventually lands somewhere it cannot climb out of.
     */
    private static final double FALL_COST = 0.35;
    /**
     * Getting into water is what costs; a route only takes the plunge when going around would be
     * roughly this many blocks longer. Swimming is slow, drops you off the path, and getting out
     * again is the part that strands bots.
     */
    private static final double WATER_ENTRY_COST = 12.0;
    /** Per-block cost of staying in water once in it, so it always looks for the nearest shore. */
    private static final double WATER_SWIM_COST = 3.0;
    /** Shallow water is waded, not swum, at roughly half the speed of a submerged sprint. */
    private static final double WATER_WADE_COST = 6.0;
    /**
     * Nodes a search may spend per block of straight-line distance to its goal.
     *
     * <p>Generous - a route that has to climb, detour or tunnel expands far more nodes than the
     * distance alone suggests, and cutting a legitimate long path short is worse than a slow one.
     * The point is only to stop a two-block goal from being handed a region-sized search.
     */
    private static final double NODES_PER_BLOCK = 120.0;
    /**
     * Floor for a walking search, so a short goal still gets room to route right round an
     * obstacle. Set well above the point where a confined space exhausts naturally: too low and
     * "budget spent" becomes indistinguishable from "no route exists", which is the difference the
     * caller uses to decide whether to try swimming or digging next.
     */
    private static final int MIN_NODE_BUDGET = 2_500;
    /**
     * A returned route this short is not a leg of a journey, it is a bot standing still.
     *
     * <p>Healthy partial paths in a measured run were 43 to 94 nodes; the ones the frozen bot kept
     * getting back were a handful, and it reported "no movable path yet" five hundred times over.
     * The gap between those two is wide enough that this does not need to be a close call.</p>
     */
    private static final int STUCK_PATH_NODES = 8;
    /** Effectively "go round" unless the detour is enormous. */
    private static final double LAVA_PROXIMITY_COST = 40.0;
    /** Digging a riverbed/bank is a last resort after open swimming has failed. */
    private static final double WATER_ADJACENT_BREAK_COST = 80.0;
    /** Escape movement while already in lava; safe ground is always dramatically cheaper. */
    private static final double LAVA_ESCAPE_COST = 100.0;
    /**
     * Surcharge for sinking a shaft straight down, on top of the cost of the block removed.
     * <p>
     * By block cost alone a vertical shaft is always the cheapest way down - one block broken per
     * level instead of the two a staircase costs - so the search dug a hole every time it needed to
     * lose height. That is not how anyone plays: a shaft is a place you cannot climb back out of
     * without spending blocks to pillar, and it is the single most reliable way to drop into a cave
     * or onto lava you never saw. Priced just above the extra block a staircase costs, so a
     * staircase wins whenever both are available, and a shaft is still chosen when the target is
     * genuinely straight down.
     */
    private static final double DIG_DOWN_COST = 4.0;
    /**
     * Blocks of empty gap the search will plan to jump across, and how far a landing may drop.
     *
     * <p>Shared with {@link PathExecutor} through {@link GapJumpPolicy} rather than restated here.
     * The two used to hold their own copies of this, and disagreed about the drop for as long as the
     * move existed - see that class for what the disagreement cost.
     */
    private static final int MAX_GAP_JUMP = GapJumpPolicy.MAX_GAP;
    private static final int MAX_GAP_DROP = GapJumpPolicy.MAX_DROP;

    /** Horizontal neighbour offsets: 4 cardinals then 4 diagonals. */
    private static final int[][] HORIZONTAL = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private AStarPathfinder() {}

    /**
     * Tuning for a single search.
     *
     * @param maxFall          how far the bot will drop voluntarily, in blocks
     * @param allowBreak       whether it may mine through obstacles (Mine and Tunnel; not Goto)
     * @param allowSwim        whether it may enter water
     * @param allowJump        whether it may change elevation or jump a gap
     * @param nodeBudget       how many nodes to expand before giving up and returning a partial path
     * @param heuristicWeight  multiplier on the remaining-distance estimate; see below
     */
    public record Settings(int maxFall, boolean allowBreak, boolean allowSwim, boolean allowJump,
                           int nodeBudget, double heuristicWeight, Player miner) {

        /** Ordinary travel: never breaks blocks, and only steps down one block at a time. */
        public static Settings walking() {
            return new Settings(1, false, true, true, 10_000, 1.6, null);
        }

        /** Travel that may tunnel through terrain. */
        public static Settings mining() {
            return new Settings(3, true, true, true, 10_000, 3.0, null);
        }

        /**
         * Attaches the player whose tools decide what can be dug. Without this the search happily
         * plans routes through blocks the bot has no way to break.
         */
        public Settings withMiner(Player miner) {
            return new Settings(maxFall, allowBreak, allowSwim, allowJump, nodeBudget,
                    heuristicWeight, miner);
        }

        /** Returns these settings with water traversal explicitly enabled or disabled. */
        public Settings withAllowSwim(boolean allowSwim) {
            return new Settings(maxFall, allowBreak, allowSwim, allowJump, nodeBudget,
                    heuristicWeight, miner);
        }

        /** Returns these settings with a learned, still-safe search greediness. */
        public Settings withHeuristicWeight(double heuristicWeight) {
            return new Settings(maxFall, allowBreak, allowSwim, allowJump, nodeBudget,
                    Math.max(1.0, heuristicWeight), miner);
        }

        /** Returns these settings with voluntary elevation changes and jumping enabled or disabled. */
        public Settings withAllowJump(boolean allowJump) {
            return new Settings(maxFall, allowBreak, allowSwim, allowJump, nodeBudget,
                    heuristicWeight, miner);
        }
    }

    /**
     * @param path          waypoints from the start block to the end, inclusive
     * @param reachedGoal   false when this is a partial path toward the goal
     * @param nodesExpanded how much of the budget the search used, for the debug overlay
     * @param searchMillis  wall-clock cost of the search, for the debug overlay
     */
    public record Result(List<BlockPos> path, boolean reachedGoal, int nodesExpanded,
                         double searchMillis, int nodeBudget) {
        public boolean isEmpty() {
            return path.size() <= 1;
        }

        /**
         * Whether the search stopped because it ran out of nodes rather than out of world.
         *
         * <p>Callers need the distinction to tell a partial path worth walking from one that means
         * this movement tier is cut off. The budget is scaled per request, so it has to travel with
         * the result - comparing against the configured maximum would call every scaled-down search
         * "open set exhausted" and disable the partial-path handling everywhere.
         */
        public boolean budgetExhausted() {
            return nodesExpanded >= nodeBudget;
        }
    }

    private static final class Node {
        final BlockPos pos;
        /** Cost from the start. Infinite until the node is first reached. */
        double g = Double.POSITIVE_INFINITY;
        double f = Double.POSITIVE_INFINITY;
        Node parent;
        boolean closed;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    public static Result find(BlockGetter level, BlockPos start, Goal goal, Settings settings) {
        com.etka.lune.bot.LuneProfiler.push("A* pathfind");
        try {
            return search(level, start, goal, settings);
        } finally {
            com.etka.lune.bot.LuneProfiler.pop();
        }
    }

    /**
     * Runs the search, and runs it again at full budget if the scaled one came up short.
     *
     * <p>The scaling below sizes a walking search by how far away the goal <em>looks</em>. Search
     * cost is not a function of that: a tree ten blocks off across a ravine needs the long way
     * round, and the way round is not ten blocks of search. So exhausting a budget that was scaled
     * down is not the evidence the caller takes it for - it says the route is longer than the
     * search was allowed to look, not that no route exists.</p>
     *
     * <p>That distinction is what a snowy-taiga run spent 175 seconds on, motionless at one block:
     * a spruce in plain sight, "no movable path yet" and "route search budget exhausted" five
     * hundred times over, on a 2500-node allowance it never had a chance of finishing inside. The
     * retry costs a second search only in the case that has already failed - and that case
     * currently costs minutes.</p>
     */
    private static Result search(BlockGetter level, BlockPos start, Goal goal, Settings settings) {
        int allowance = scaledBudget(goal, start, settings);
        Result scaled = search(level, start, goal, settings, allowance);
        // Only a search that cannot move is worth paying for twice.
        //
        // Retrying every partial path doubled the cost of the common case for nothing: a partial
        // that still hands back forty blocks of route is a perfectly good next leg, and the bot
        // re-plans when it gets there anyway. Doing it unconditionally took the median search from
        // about 1 ms to 13, roughly a seventh of the run spent in A*, and cost more wood than the
        // freeze it was fixing. What actually freezes is the search that comes back with nowhere
        // to go, and that is the only one retried now.
        if (scaled.reachedGoal() || scaled.path().size() > STUCK_PATH_NODES
                || allowance >= settings.nodeBudget()) {
            return scaled;
        }
        Result full = search(level, start, goal, settings, settings.nodeBudget());
        // Keep whichever got further. A second miss still returns the better partial rather than
        // discarding the work, and a search that was genuinely bounded by terrain returns the same
        // answer both times.
        return full.reachedGoal() || full.path().size() > scaled.path().size() ? full : scaled;
    }

    /**
     * How far this node may drop, which depends on how near the goal is.
     *
     * <p>{@code maxFall} is the largest drop the bot survives, and near the goal it needs all of it:
     * a tree stands on ground the bot has to get down onto, and refusing the drop leaves it circling
     * a trunk it can see. Far from the goal the same allowance is a liability. A planned drop is not
     * where the bot lands - it leaves the block with walking momentum and comes down further along
     * and further below - so a three-block plan arrives as four or five, and a chain of them is a
     * staircase down a ravine face.</p>
     *
     * <p>Both halves were measured, one run each. Clamping every walking drop to one fixed travel -
     * fall damage over a whole run went from twelve to nothing, the route from 1.6x the straight
     * line to 1.3x, and the seed that had killed the bot three times out of three came home. It
     * also cost a snowy-taiga chop run two thirds of its harvest, 81 logs down to 34, four minutes
     * of it frozen on "arrived at that Spruce Log 8 times without moving": with no drop available
     * the approach goal was satisfied while the bot was never beside the tree.</p>
     *
     * <p>So it is not one number. Distance to the goal is what tells the two cases apart, because
     * that is the actual difference between them: a chop approach is a few blocks and a travel leg
     * is dozens.</p>
     */
    private static int dropLimit(Goal goal, BlockPos from, Settings settings) {
        if (settings.allowBreak() || goal.heuristic(from) <= CLOSING_ON_GOAL) {
            return settings.maxFall();
        }
        return Math.min(settings.maxFall(), TRAVELLING_MAX_FALL);
    }

    /** Within this of the goal, the search is arriving rather than travelling. */
    private static final int CLOSING_ON_GOAL = 24;
    /** What a drop may cost while still on the way. One block down is an ordinary walking step. */
    private static final int TRAVELLING_MAX_FALL = 1;

    /** The walking allowance for this request; a digging search is deliberately left uncapped. */
    private static int scaledBudget(Goal goal, BlockPos start, Settings settings) {
        if (settings.allowBreak()) {
            return settings.nodeBudget();
        }
        return Math.max(MIN_NODE_BUDGET,
                Math.min(settings.nodeBudget(), (int) (goal.heuristic(start) * NODES_PER_BLOCK)));
    }

    private static Result search(BlockGetter level, BlockPos start, Goal goal, Settings settings,
                                 int budget) {
        long startNanos = System.nanoTime();
        Map<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));

        Node startNode = new Node(start);
        startNode.g = 0.0;
        startNode.f = settings.heuristicWeight() * goal.heuristic(start);
        nodes.put(start.asLong(), startNode);
        open.add(startNode);

        // Tracked so an exhausted search still makes forward progress instead of returning nothing.
        // Seeded with the *unweighted* estimate, to match what the loop below compares against.
        Node best = startNode;
        double bestHeuristic = goal.heuristic(start);

        // Scale a *walking* search to the size of the job; leave a digging one alone.
        //
        // One budget for every request let a drop two blocks away expand the same ten thousand
        // nodes as a two-hundred-block march. Stranded in a tree canopy with no way down that is
        // what happened, and since one repath runs the dry, swimming and digging tiers in turn it
        // cost three full searches - well over a hundred milliseconds - every attempt.
        //
        // Only walking is capped. A digging search has no terrain bound - it can tunnel in any
        // direction, so it always reaches its cap, and capping it low made every route report "no
        // dry or swimming route from here" while standing next to the target. That was a straight
        // loss of capability. The allowance itself is chosen by the caller above, which also
        // decides whether a short one that ran out deserves a second look at full size.
        int expanded = 0;
        while (!open.isEmpty() && expanded < budget) {
            Node current = open.poll();
            if (current.closed) {
                continue;
            }
            current.closed = true;
            expanded++;

            if (goal.isReached(current.pos)) {
                return new Result(reconstruct(current), true, expanded, millisSince(startNanos), budget);
            }

            double h = goal.heuristic(current.pos);
            if (h < bestHeuristic) {
                bestHeuristic = h;
                best = current;
            }

            expandNeighbours(level, current, goal, settings, nodes, open);
        }

        return new Result(reconstruct(best), false, expanded, millisSince(startNanos), budget);
    }

    /**
     * Jumps a gap, the way a player crosses a ravine lip or a one-block hole.
     *
     * <p>The search had no such move at all: its only options were to step onto an adjacent block,
     * climb one, or fall. So a two-block gap with perfectly good ground on the far side was simply
     * not a route, and the bot either walked the long way round or reported no route from a spot a
     * person would clear without breaking stride.
     *
     * <p>Deliberately conservative. Cardinal only, because diagonal jumps catch on corners; the
     * take-off needs solid ground and headroom; every block of the gap must be clear at both feet
     * and head height, so this cannot jump through a wall; and the landing must be somewhere the
     * bot can stand, at the same level or one below. Vanilla clears more than this with a sprint,
     * but a failed jump is a fall, so the reach stops short of what is theoretically possible.
     */
    private static void relaxGapJumps(BlockGetter level, Node current, int dx, int dz, Goal goal,
                                      Settings settings, Map<Long, Node> nodes,
                                      PriorityQueue<Node> open) {
        BlockPos pos = current.pos;
        if (!MovementHelper.isSolidFloor(level, pos.below())
                || !MovementHelper.isPassable(level, pos.above(2))) {
            return;
        }
        for (int distance = 2; distance <= MAX_GAP_JUMP + 1; distance++) {
            // Everything between take-off and landing has to be open at body height, and the gap
            // itself has to be a gap: if there were a floor here the ordinary walking move would
            // have taken it, and jumping over solid ground is not a move worth searching.
            boolean clear = true;
            for (int step = 1; step < distance; step++) {
                BlockPos over = pos.offset(dx * step, 0, dz * step);
                if (!MovementHelper.hasBodyClearance(level, over)
                        || MovementHelper.isSolidFloor(level, over.below())
                        || MovementHelper.isHarmful(level.getBlockState(over.below()))) {
                    clear = false;
                    break;
                }
            }
            if (!clear) {
                continue;
            }
            for (int drop = 0; drop <= MAX_GAP_DROP; drop++) {
                BlockPos landing = pos.offset(dx * distance, -drop, dz * distance);
                if (MovementHelper.canStandAt(level, landing, settings.allowSwim())) {
                    relax(level, current, landing,
                            Goals.STEP * distance + JUMP_COST * distance + drop * FALL_COST,
                            goal, settings, nodes, open);
                    break;
                }
            }
        }
    }

    private static double millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static void expandNeighbours(BlockGetter level, Node current, Goal goal, Settings settings,
                                         Map<Long, Node> nodes, PriorityQueue<Node> open) {
        BlockPos pos = current.pos;

        // Ladders and vines are vertical paths, not floors. Jungle trees in particular become
        // impossible to finish if the search only understands walking and jumping between solid
        // platforms, even though a player can simply climb the vines already on the trunk.
        BlockPos climbUp = pos.above();
        if (settings.allowJump()
                && (MovementHelper.isClimbable(level, pos) || MovementHelper.isClimbable(level, climbUp))
                && MovementHelper.hasBodyClearance(level, climbUp)) {
            relax(level, current, climbUp, CLIMB_COST, goal, settings, nodes, open);
        }
        BlockPos climbDown = pos.below();
        // Climbing down only makes sense if both the current and the target blocks can actually
        // be occupied by a climbing player. Solid floors may carry a climbable (e.g. a vine on a
        // wall), but the bot cannot phase through them and should not plan a straight-down climb
        // through the floor it is standing on.
        boolean canClimbDown = settings.allowJump()
                && MovementHelper.isClimbable(level, pos)
                && !MovementHelper.isSolidFloor(level, pos)
                && !MovementHelper.isSolidFloor(level, climbDown)
                && (MovementHelper.hasBodyClearance(level, climbDown)
                        || MovementHelper.isClimbable(level, climbDown));
        if (canClimbDown) {
            relax(level, current, climbDown, CLIMB_COST, goal, settings, nodes, open);
        }

        // Dig straight down. Without this the search can only ever tunnel sideways, so reaching
        // anything buried means hunting the surface for a natural opening - which is exactly how a
        // short trip to an ore 16 blocks down burns the entire node budget and returns a partial
        // path. Requires a floor under the block being removed, so the bot lands rather than falls.
        if (settings.allowBreak()) {
            BlockPos below = pos.below();
            if (MovementHelper.isSolidFloor(level, below.below())) {
                double clear = costToClear(level, below, settings);
                // Zero means it's already open, which the ordinary fall move handles better.
                if (clear > 0.0 && Double.isFinite(clear)) {
                    relax(level, current, below, Goals.STEP + clear + DIG_DOWN_COST,
                            goal, settings, nodes, open);
                }
            }
        }

        for (int[] dir : HORIZONTAL) {
            int dx = dir[0];
            int dz = dir[1];
            boolean diagonal = dx != 0 && dz != 0;
            double baseCost = diagonal ? Goals.DIAGONAL : Goals.STEP;

            // Diagonals must not clip through a corner: both orthogonal columns need to be clear.
            // When we are allowed to break, leaves and other soft blocks can be cleared.
            BlockPos sideX = diagonal ? pos.offset(dx, 0, 0) : null;
            BlockPos sideZ = diagonal ? pos.offset(0, 0, dz) : null;
            double sideCost = 0.0;
            if (diagonal) {
                boolean clearX = MovementHelper.hasBodyClearance(level, sideX);
                boolean clearZ = MovementHelper.hasBodyClearance(level, sideZ);
                if (!clearX || !clearZ) {
                    if (!settings.allowBreak()) {
                        continue;
                    }
                    sideCost = costToClearSide(level, sideX, settings, clearX)
                            + costToClearSide(level, sideZ, settings, clearZ);
                    if (!Double.isFinite(sideCost)) {
                        continue;
                    }
                }
            }

            BlockPos flat = pos.offset(dx, 0, dz);

            // Never enter lava from safety. If the player was dropped into a lava pool, lava nodes
            // may lead to more lava nodes only long enough to reach a non-lava edge and escape.
            if (MovementHelper.isLava(level, flat)) {
                if (MovementHelper.isLava(level, pos)) {
                    relax(level, current, flat, baseCost + LAVA_ESCAPE_COST + sideCost,
                            goal, settings, nodes, open);
                }
                continue;
            }

            // A water surface is only a floor for an explicitly swimming route. Without this
            // guard, the generic canStandAt predicate makes the first water block look like an
            // ordinary step and the player can sink before the route notices the mistake.
            if (MovementHelper.isWater(level, flat) && !settings.allowSwim()) {
                continue;
            }

            if (MovementHelper.canStandAt(level, flat, settings.allowSwim())) {
                relax(level, current, flat, baseCost + sideCost, goal, settings, nodes, open);
                continue;
            }

            // Step up one block. Cardinal only - diagonal jumps catch on corners in practice.
            if (!diagonal && settings.allowJump()) {
                BlockPos up = flat.above();
                if (MovementHelper.canStandAt(level, up, settings.allowSwim())
                        && MovementHelper.isPassable(level, pos.above(2))) {
                    relax(level, current, up, baseCost + JUMP_COST + sideCost, goal, settings, nodes, open);
                }
                relaxGapJumps(level, current, dx, dz, goal, settings, nodes, open);
            }

            // Walk off an edge and fall, up to the limit for this part of the journey.
            if (MovementHelper.hasBodyClearance(level, flat)) {
                if (settings.allowJump()) {
                    for (int drop = 1; drop <= dropLimit(goal, flat, settings); drop++) {
                        BlockPos landing = flat.below(drop);
                        if (MovementHelper.canStandAt(level, landing, settings.allowSwim())) {
                            relax(level, current, landing,
                                    baseCost + drop * drop * FALL_COST + sideCost,
                                    goal, settings, nodes, open);
                            break;
                        }
                        // Must actually fall *through* this block to keep dropping. A snow layer
                        // or carpet is walkable but still stops a fall, so the walking test is too
                        // loose.
                        if (!MovementHelper.canFallThrough(level, landing)) {
                            break;
                        }
                    }
                }

                // Swim across open water at the same level. The water surcharge is applied by
                // relax(), which is also what makes leaving the water look attractive.
                if (settings.allowSwim() && MovementHelper.isSurfaceWater(level, flat)) {
                    relax(level, current, flat, baseCost + sideCost, goal, settings, nodes, open);
                }

                // Swim *below* the surface. Without this the cheap swim rate was unreachable: the
                // only water nodes ever generated were surface ones, and surface water has air
                // above it by definition, so relax() charged every crossing the wade rate and the
                // route hugged the top of the water the whole way. That is the crossing that looks
                // nothing like a person swimming - and no amount of holding sprint fixes it,
                // because vanilla will not start the stroke while the head is out.
                //
                // Depth needs no encouragement beyond this: every submerged node costs the same, so
                // the search takes the shallowest line that works rather than diving to the bed.
                if (settings.allowSwim()
                        && MovementHelper.isWater(level, flat)
                        && MovementHelper.isWater(level, flat.above())) {
                    relax(level, current, flat, baseCost + sideCost, goal, settings, nodes, open);
                }
            } else if (settings.allowBreak()) {
                // Mine through: pay for every block in the way, provided there's a floor to land on.
                if (MovementHelper.isSolidFloor(level, flat.below())) {
                    double cost = baseCost + sideCost;
                    cost += costToClear(level, flat, settings);
                    cost += costToClear(level, flat.above(), settings);
                    if (Double.isFinite(cost)) {
                        relax(level, current, flat, cost, goal, settings, nodes, open);
                    }
                }

                // Cut a step up through solid rock. This is the counterpart to digging down, and
                // without it that move is a one-way trip: the bot sinks a shaft to reach an ore and
                // then cannot climb out, because getting up any other way needs pillaring. Standing
                // on the block ahead means clearing the two above it, plus headroom to jump from
                // here.
                if (!diagonal && MovementHelper.isSolidFloor(level, flat)) {
                    BlockPos up = flat.above();
                    double cost = baseCost + JUMP_COST + sideCost;
                    cost += costToClear(level, up, settings);
                    cost += costToClear(level, up.above(), settings);
                    cost += costToClear(level, pos.above(2), settings);
                    if (Double.isFinite(cost)) {
                        relax(level, current, up, cost, goal, settings, nodes, open);
                    }
                }
            }
        }
    }

    /**
     * Surcharge for where a step ends up, as opposed to how far it goes.
     * <p>
     * Charging water on <em>entry</em> rather than per block is what produces the behaviour you'd
     * want from a person: walk around a pond, but swim a lake rather than trek miles around it. It
     * also means that once the bot is in water, every step that leaves it is cheaper than every step
     * that stays in, so it heads for the nearest shore instead of wandering the surface.
     */
    private static double hazardPenalty(BlockGetter level, BlockPos from, BlockPos to) {
        double penalty = 0.0;
        if (MovementHelper.nearLava(level, to)) {
            penalty += LAVA_PROXIMITY_COST;
        }
        if (MovementHelper.isWater(level, to)) {
            if (!MovementHelper.isWater(level, from)) {
                penalty += WATER_ENTRY_COST;
            } else {
                // Wading and swimming are not the same movement and must not cost the same.
                //
                // The crawl stroke only starts once the eyes are under, so water with air above it
                // is waded, not swum - and wading is the slowest way to travel in the game, about
                // half the speed of a submerged sprint and a third of running on land. Charging one
                // flat rate for both let routes hug a shoreline for a thousand ticks: measured at
                // 0.100 blocks per tick, with the sprint key held the whole time and the head above
                // water 98% of it. Deep water is genuinely quicker, so it stays cheap.
                penalty += MovementHelper.isWater(level, to.above())
                        ? WATER_SWIM_COST : WATER_WADE_COST;
            }
        }
        return penalty;
    }

    /** Cost of clearing one body column (feet and head) for a diagonal corner. */
    private static double costToClearSide(BlockGetter level, BlockPos side, Settings settings,
                                          boolean alreadyClear) {
        if (alreadyClear) {
            return 0.0;
        }
        return costToClear(level, side, settings) + costToClear(level, side.above(), settings);
    }

    /** Cost of making one block passable: free if it already is, infinite if it can't be broken. */
    private static double costToClear(BlockGetter level, BlockPos pos, Settings settings) {
        if (MovementHelper.isPassable(level, pos)) {
            return 0.0;
        }
        if (!MovementHelper.isBreakable(level, pos)) {
            return Double.POSITIVE_INFINITY;
        }
        // A dry route may walk beside water, but it must not tunnel through the wall that keeps
        // that water out. The executor enforces the same rule at action time; keeping it here too
        // prevents A* from selecting a path that is already unsafe in the loaded snapshot.
        if (!settings.allowSwim() && MovementHelper.wouldOpenWater(level, pos)) {
            return Double.POSITIVE_INFINITY;
        }
        // Opening a block beside lava can pour lava onto the player. Only relax this rule when the
        // player is already burning in lava and digging may be the way out.
        if (MovementHelper.nearLava(level, pos)
                && (settings.miner() == null || !settings.miner().isInLava())) {
            return Double.POSITIVE_INFINITY;
        }
        // Unbreakable in practice: the bot has no tool that would drop this, and mining it anyway
        // would destroy it. Treat it as solid so the route goes around.
        if (settings.miner() != null
                && !ToolSelector.canHarvest(settings.miner(), level.getBlockState(pos))) {
            return Double.POSITIVE_INFINITY;
        }
        double cost = MovementHelper.breakCost(level, pos);
        if (MovementHelper.nearWater(level, pos)) {
            cost += WATER_ADJACENT_BREAK_COST;
        }
        return cost;
    }

    private static void relax(BlockGetter level, Node from, BlockPos to, double cost, Goal goal,
                              Settings settings, Map<Long, Node> nodes, PriorityQueue<Node> open) {
        double tentativeG = from.g + cost + hazardPenalty(level, from.pos, to);
        Node node = nodes.computeIfAbsent(to.asLong(), key -> new Node(to));
        // Unvisited nodes start at infinite g, so this also stops the search relaxing back into the
        // start node (g = 0) and building a parent cycle that reconstruct() would loop on forever.
        if (node.closed || tentativeG >= node.g) {
            return;
        }
        node.parent = from;
        node.g = tentativeG;
        node.f = tentativeG + settings.heuristicWeight() * goal.heuristic(to);
        open.add(node);
    }

    private static List<BlockPos> reconstruct(Node end) {
        List<BlockPos> path = new ArrayList<>();
        for (Node node = end; node != null; node = node.parent) {
            path.add(node.pos);
        }
        Collections.reverse(path);
        return path;
    }
}
