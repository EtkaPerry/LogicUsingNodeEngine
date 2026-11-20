package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.command.Param;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.WorldDimension;
import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.PortalLink;
import com.etka.lune.waypoint.PortalStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Gets the bot to a place somebody named, even when the place is on the other side of a portal.
 *
 * <p>In the dimension the place is in, this is the walk every named place gets -
 * {@link GotoTask#toPlace} - and nothing more. Somewhere else it is a trip of legs: to a portal,
 * through it, and the rest of the way on the far side.</p>
 *
 * <p>The portal leg is chosen, not assumed. Every crossing the bot has ever made or seen is in
 * {@link PortalStore}, and each remembered portal is priced by what the whole trip costs - the
 * walk to it plus the walk from where it comes out to the place - rather than by how near it is.
 * A lit portal standing in front of the bot is one more candidate. With nothing remembered and
 * nothing seen, a portal is built for the trip, the way a player builds one when the road is
 * longer than the pickaxe is patient.</p>
 *
 * <p>What this cannot do is guess. Vanilla pairs the Overworld and the Nether through Nether
 * portals and nothing else - a trip to the End, or to a dimension some mod added, fails with
 * which side of that it is stuck on, rather than walking to coordinates that mean nothing there.
 * A memory that turns out wrong is treated the same way: a remembered portal that is not there
 * when the bot arrives is forgotten and the next one is tried, not argued with.</p>
 */
public final class TravelTask implements Task {

    /** How far around the bot a lit portal is worth noticing before one gets built instead. */
    private static final int SEEN_RADIUS = 48;
    /** Close enough to a remembered portal that the block it names is loaded and checkable. */
    private static final int ANCHOR_NEAR = 4;
    /**
     * How far from a portal's scaled twin a remembered far end may sit and still be counted as
     * its other side. Vanilla links a portal anywhere in a 128-block radius of the scaled point
     * on the Nether side - and on a server, a neighbour's portal answers where none was built -
     * so a remembered end a little further out is still the likelier exit than the estimate.
     */
    private static final double LINK_RADIUS = 192.0;

    private enum Phase { PLAN, TO_PORTAL, CROSS, BUILD, WALK }

    private final Goal goal;
    /** The place's column, for pricing portal exits; the goal keeps the real shape of done. */
    private final BlockPos target;
    /** The dimension id the place is in, in the form a waypoint stores; null means "here". */
    private final String targetDimension;
    private final boolean sprint;

    private Phase phase = Phase.PLAN;
    private ResourceKey<Level> targetKey;
    private PortalLink candidate;
    private GotoTask walk;
    private UsePortalTask cross;
    private BuildPortalTask build;
    /** Portal columns this run already tried and lost, so the next plan picks another way. */
    private final List<BlockPos> tried = new ArrayList<>();
    private boolean built;
    private final StatusText status = new StatusText();
    /** Why the last leg failed, kept so a plan with nowhere left to go can say it. */
    private final StatusText lastFailure = new StatusText();

    public TravelTask(Goal goal, BlockPos target, String targetDimension, boolean sprint) {
        this.goal = goal;
        this.target = target;
        this.targetDimension = targetDimension;
        this.sprint = sprint;
    }

    /** The trip to a waypoint, as the Go to Waypoint card and the Waypoints tab both ask for it. */
    public static TravelTask toWaypoint(com.etka.lune.waypoint.Waypoint waypoint, int tolerance) {
        return new TravelTask(new Goals.Near(waypoint.pos(), tolerance), waypoint.pos(),
                waypoint.dimension(), true);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.goto.name", goal.describe());
    }

    /** The English this is, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Go to " + goal.describe());
    }

    /** A route of legs; the walk, the crossing and the build each learn for themselves. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return switch (phase) {
            case TO_PORTAL, WALK -> walk == null ? null : walk.progress();
            case BUILD -> build == null ? null : build.progress();
            default -> null;
        };
    }

    @Override
    public void onStart(BotContext ctx) {
        phase = Phase.PLAN;
        candidate = null;
        targetKey = null;
        tried.clear();
        built = false;
        lastFailure.clear();
        status.clear();
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        return switch (phase) {
            case PLAN -> plan(ctx);
            case TO_PORTAL -> toPortal(ctx);
            case CROSS -> crossing(ctx);
            case BUILD -> building(ctx);
            case WALK -> walking(ctx);
        };
    }

    /**
     * Which leg comes next, asked again every time one ends - because a leg that crossed over has
     * changed which dimension the rest of the question is in.
     */
    private TaskStatus plan(BotContext ctx) {
        String here = ctx.level.dimension().identifier().toString();
        if (targetDimension == null || targetDimension.isBlank() || here.equals(targetDimension)) {
            startWalk(ctx);
            return TaskStatus.RUNNING;
        }
        String nether = "minecraft:the_nether";
        String overworld = "minecraft:overworld";
        String legTarget = targetDimension;
        boolean ridable = nether.equals(targetDimension)
                || (nether.equals(here) && overworld.equals(targetDimension));
        if (!ridable) {
            // Vanilla's pairing is hardcoded: a Nether portal answers the Nether from anywhere
            // but the Nether, and home from inside it. Two hops still reach a place one never
            // could - a modded world's portal goes to the Nether, and the Nether's goes home -
            // so aim at the Nether first whenever the far side pairs with it. The End, and the
            // way out of a modded world to anywhere but home, have no ride vanilla can promise.
            if (!nether.equals(here) && overworld.equals(targetDimension)) {
                legTarget = nether;
            } else {
                status.set("lune.status.travel.no_way",
                        dimensionName(ctx.level.dimension()), displayName(targetDimension));
                return TaskStatus.FAILED;
            }
        }
        targetKey = dimensionKey(legTarget);
        if (targetKey == null) {
            status.set("lune.status.travel.no_way",
                    dimensionName(ctx.level.dimension()), legTarget);
            return TaskStatus.FAILED;
        }

        candidate = choosePortal(ctx, legTarget);
        if (candidate != null) {
            walk = GotoTask.toPlace(new Goals.Near(candidate.pos(), ANCHOR_NEAR), sprint);
            walk.start(ctx);
            phase = Phase.TO_PORTAL;
            return TaskStatus.RUNNING;
        }
        if (!built) {
            built = true;
            build = new BuildPortalTask(BuildPortalTask.FrameMode.RESOURCE_SAVING,
                    Set.of(Blocks.DIRT, Blocks.COBBLESTONE), false);
            build.start(ctx);
            phase = Phase.BUILD;
            return TaskStatus.RUNNING;
        }
        if (lastFailure.isBlank()) {
            status.set("lune.status.travel.no_way",
                    dimensionName(ctx.level.dimension()), displayName(targetDimension));
        } else {
            status.set(lastFailure);
        }
        return TaskStatus.FAILED;
    }

    /**
     * The cheapest portal the trip can start through: memory's candidates plus any lit portal
     * standing in front of the bot, each priced by the whole trip it makes - the walk to it plus
     * the walk from where it comes out - and each already lost this run skipped.
     */
    private PortalLink choosePortal(BotContext ctx, String legTarget) {
        String here = ctx.level.dimension().identifier().toString();
        BlockPos feet = ctx.player.blockPosition();
        List<PortalLink> candidates = new ArrayList<>(
                PortalStore.get().linking(here, legTarget));
        candidates.removeIf(link -> tried(link.pos()));
        BlockPos seen = UsePortalTask.findPortal(ctx, feet, SEEN_RADIUS);
        if (seen != null && !tried(seen)
                && candidates.stream().noneMatch(link -> samePortal(link.pos(), seen))) {
            candidates.add(PortalLink.at(seen, here, legTarget, 0L));
        }

        // On a hop leg the far side is only a step toward the place, not the place - what the
        // rest of the trip costs is unknowable until the hop is made, so the nearest wins.
        boolean hop = !legTarget.equals(targetDimension);
        PortalLink best = null;
        double bestCost = Double.MAX_VALUE;
        for (PortalLink link : candidates) {
            double cost = hop
                    ? PortalStore.horizontal(feet, link.pos())
                    : routeCost(feet, link.pos(), exitEstimate(link, legTarget), target);
            if (cost < bestCost) {
                bestCost = cost;
                best = link;
            }
        }
        return best;
    }

    /**
     * Where this portal comes out, as well as memory can say: the far end it was ridden to, when
     * a crossing wrote one down near where vanilla's own link lands it, else the scaled point
     * vanilla computes the other side from.
     */
    private BlockPos exitEstimate(PortalLink link, String legTarget) {
        BlockPos scaled = scaledExit(link.dimension(), link.pos());
        return PortalStore.get().nearestIn(legTarget, scaled)
                .filter(far -> PortalStore.horizontal(far.pos(), scaled) <= LINK_RADIUS)
                .map(PortalLink::pos)
                .orElse(scaled);
    }

    private TaskStatus toPortal(BotContext ctx) {
        TaskStatus result = walk.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            status.set("lune.status.travel.to_portal", walk.statusLine());
            return TaskStatus.RUNNING;
        }
        if (result == TaskStatus.FAILED) {
            lastFailure.set(walk.statusLine());
            stopWalk(ctx);
            // The portal may still stand - a walk says nothing about that - but this run is done
            // walking to it.
            miss(false);
            return plan(ctx);
        }
        stopWalk(ctx);
        cross = new UsePortalTask(targetKey, candidate.pos(), 32);
        cross.start(ctx);
        phase = Phase.CROSS;
        return TaskStatus.RUNNING;
    }

    private TaskStatus crossing(BotContext ctx) {
        TaskStatus result = cross.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            status.set("lune.status.travel.crossing", dimensionName(targetKey), cross.statusLine());
            return TaskStatus.RUNNING;
        }
        boolean gone = "lune.status.use_portal.none_in_sight".equals(cross.statusLine().key());
        lastFailure.set(cross.statusLine());
        cross.stop(ctx);
        cross = null;
        if (result == TaskStatus.FAILED) {
            miss(gone);
            return plan(ctx);
        }
        // Through - which dimension the rest of the trip is in is a fresh question now.
        candidate = null;
        return plan(ctx);
    }

    private TaskStatus building(BotContext ctx) {
        TaskStatus result = build.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            status.set("lune.status.travel.building_portal",
                    dimensionName(targetKey), build.statusLine());
            return TaskStatus.RUNNING;
        }
        lastFailure.set(build.statusLine());
        build.stop(ctx);
        build = null;
        if (result == TaskStatus.FAILED) {
            status.set(lastFailure);
            return TaskStatus.FAILED;
        }
        // The frame it just lit is in sight and in memory; ride it like any other.
        cross = new UsePortalTask(targetKey, null, SEEN_RADIUS);
        cross.start(ctx);
        phase = Phase.CROSS;
        return TaskStatus.RUNNING;
    }

    private TaskStatus walking(BotContext ctx) {
        TaskStatus result = walk.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            status.set(walk.statusLine());
            return TaskStatus.RUNNING;
        }
        stopWalk(ctx);
        return result;
    }

    private void startWalk(BotContext ctx) {
        walk = GotoTask.toPlace(goal, sprint);
        walk.start(ctx);
        phase = Phase.WALK;
    }

    private void stopWalk(BotContext ctx) {
        if (walk != null) {
            walk.stop(ctx);
            walk = null;
        }
    }

    /**
     * Writes off the portal just lost: this run never picks it again, and a portal that was not
     * there when the bot arrived stops being one memory offers anybody.
     */
    private void miss(boolean forget) {
        if (candidate != null) {
            tried.add(candidate.pos());
            if (forget) {
                PortalStore.get().forget(candidate);
            }
            candidate = null;
        }
    }

    /** Whether a portal at {@code pos} is one this run already tried. */
    private boolean tried(BlockPos pos) {
        for (BlockPos lost : tried) {
            if (samePortal(lost, pos)) {
                return true;
            }
        }
        return false;
    }

    /** Two portal blocks the same frame could be seen or remembered at. */
    static boolean samePortal(BlockPos a, BlockPos b) {
        return PortalStore.horizontal(a, b) <= PortalStore.SAME_PORTAL
                && Math.abs(a.getY() - b.getY()) <= 4;
    }

    /**
     * The column a portal in {@code dimension} at {@code pos} links to on the other side, by
     * vanilla's own arithmetic: the Nether compresses eight to one. Compared as ids, not
     * {@link Level} keys - the keys cannot be named without a bootstrapped registry.
     */
    static BlockPos scaledExit(String dimension, BlockPos pos) {
        boolean fromNether = "minecraft:the_nether".equals(dimension);
        int x = fromNether ? pos.getX() * 8 : Math.floorDiv(pos.getX(), 8);
        int z = fromNether ? pos.getZ() * 8 : Math.floorDiv(pos.getZ(), 8);
        return new BlockPos(x, pos.getY(), z);
    }

    /** What a trip through a portal costs, on the only scale that matters: blocks walked. */
    static double routeCost(BlockPos feet, BlockPos portal, BlockPos exit, BlockPos target) {
        return PortalStore.horizontal(feet, portal) + PortalStore.horizontal(exit, target);
    }

    private static ResourceKey<Level> dimensionKey(String id) {
        try {
            return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** One of the three by name, or a pack's own world by its id - which is all anybody has. */
    private static String dimensionName(ResourceKey<Level> dimension) {
        WorldDimension known = WorldDimension.of(dimension);
        return known == null ? dimension.identifier().toString()
                : Param.Choice.optionLabel(known.label());
    }

    private static String displayName(String dimensionId) {
        ResourceKey<Level> key = dimensionKey(dimensionId);
        return key == null ? dimensionId : dimensionName(key);
    }

    @Override
    public void onStop(BotContext ctx) {
        stopWalk(ctx);
        if (cross != null) {
            cross.stop(ctx);
            cross = null;
        }
        if (build != null) {
            build.stop(ctx);
            build = null;
        }
        ctx.input.reset();
    }
}
