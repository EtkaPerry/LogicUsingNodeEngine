package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.command.Param;
import com.etka.lune.bot.knowledge.OreKnowledge;
import com.etka.lune.bot.knowledge.OreProfile;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.WorldDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;

import java.util.Optional;
import java.util.Set;

/**
 * Digs a main shaft and side branches off it: the classic ore-hunting pattern.
 * <p>
 * A small state machine over the tasks that already exist - descend to the ore band, dig a stretch
 * of shaft, dig one branch, walk back to the junction, repeat. Composing {@link TunnelTask} and
 * {@link GotoTask} rather than re-implementing digging means this inherits their reach handling,
 * re-pathing and failure behaviour for free.
 */
public final class StripmineTask implements Task {

    private static final int CORRIDOR_HEIGHT = 2;
    /**
     * Steps of staircase cut before the descent looks at where it has got to.
     * <p>
     * One step is one block of depth. Short segments cost nothing - the phase simply starts another
     * one - and they give the run a chance to notice a cave, a change of heading or an emergency
     * between them rather than at the bottom.
     */
    private static final int DESCEND_SEGMENT_STEPS = 8;
    /** Headings the descent may be refused on before the shaft is called impossible from here. */
    private static final int MAX_DESCEND_FAILURES = 4;

    private enum Phase {
        DESCEND,
        SHAFT,
        BRANCH,
        RETURN
    }

    private final Set<Block> targets;
    private final int requestedY;
    private final int branchLength;
    private final int spacing;
    private final int branches;

    private int effectiveY;
    /** Set when the target only generates in another world, and reported on the first tick. */
    private WorldDimension elsewhere;

    private Phase phase = Phase.DESCEND;
    private Direction mainDirection;
    /** The heading the descent staircase is cutting along, rotated when the terrain refuses one. */
    private Direction descendDirection;
    private int descendFailures;
    private BlockPos junction;
    private Task current;
    private int branchesDone;
    private final StatusText status = new StatusText();

    public StripmineTask(int yLevel, int branchLength, int spacing, int branches) {
        this(Set.of(), yLevel, branchLength, spacing, branches);
    }

    public StripmineTask(Set<Block> targets, int yLevel, int branchLength, int spacing, int branches) {
        this.targets = Set.copyOf(targets);
        this.requestedY = yLevel;
        this.branchLength = branchLength;
        this.spacing = Math.max(2, spacing);
        this.branches = Math.max(1, branches);
    }

    @Override
    public void onStart(BotContext ctx) {
        // If a target ore is provided, let ore-layer knowledge pick the best level. The requested
        // y-level still works as a fallback for stones or modded ores the bot doesn't know.
        effectiveY = requestedY;
        if (!targets.isEmpty()) {
            java.util.OptionalInt knowledge = OreKnowledge.bestYFor(ctx, targets);
            if (knowledge.isPresent()) {
                effectiveY = knowledge.getAsInt();
            } else {
                elsewhere = onlyGeneratesElsewhere(ctx);
            }
        }
        // A y-level from a card is whatever somebody typed, and the default is -59 - which is the
        // diamond band in the Overworld and thirty blocks under the Nether's bedrock. Clamping it
        // turns a descent that could never arrive into one that stops at the floor.
        effectiveY = Mth.clamp(effectiveY, ctx.level.getMinY() + 1, ctx.level.getMaxY());
    }

    /**
     * The world every target is known to need, when that is not this one - otherwise null.
     *
     * <p>Ancient Debris is the case this exists for: it is the one thing in the ore table that
     * names a dimension, and a Stripmine asked for it in the Overworld would otherwise dig a
     * perfectly good diamond-level mine and report that it found nothing. Saying which world it is
     * in costs one line and answers the question the run would otherwise leave open.</p>
     */
    private WorldDimension onlyGeneratesElsewhere(BotContext ctx) {
        WorldDimension needed = null;
        for (Block target : targets) {
            Optional<OreProfile> profile = OreKnowledge.forBlock(target);
            if (profile.isEmpty() || profile.get().dimension().isEmpty()) {
                // Something here generates where the bot is standing, or is not known at all.
                return null;
            }
            WorldDimension where = WorldDimension.byId(profile.get().dimension().get());
            if (where == null || (needed != null && needed != where)) {
                return null;
            }
            needed = where;
        }
        return needed != null && !needed.matches(ctx.level.dimension()) ? needed : null;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.stripmine.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Stripmine");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (elsewhere != null) {
            // Said once, before a single block is cut: the shaft would be perfectly good and the
            // ore would simply not be in it.
            status.set("lune.status.stripmine.only_in_dimension",
                    targets.iterator().next().getName().getString(),
                    Param.Choice.optionLabel(elsewhere.label()));
            return TaskStatus.FAILED;
        }
        if (current == null && !beginPhase(ctx)) {
            return TaskStatus.FAILED;
        }

        TaskStatus result = current.tick(ctx);
        status.set("lune.status.detail", describe(), current.statusLine());

        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        current.stop(ctx);
        current = null;

        if (result == TaskStatus.FAILED) {
            // A refused staircase is a refused heading, not a refused shaft: gravel overhead or
            // lava behind one wall says nothing about the other three. Turn and cut again.
            if (phase == Phase.DESCEND && ++descendFailures < MAX_DESCEND_FAILURES) {
                descendDirection = descendDirection == null
                        ? ctx.player.getDirection() : descendDirection.getClockWise();
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.hunt_mob.failed", describe());
            return TaskStatus.FAILED;
        }
        return finishPhase(ctx);
    }

    /** Creates the task for the current phase. Returns false if the phase can't be started. */
    private boolean beginPhase(BotContext ctx) {
        current = switch (phase) {
            // Cut a staircase down rather than asking the router to plan one.
            //
            // A y-level goal fifty-five blocks below the grass is not a route, it is a dig, and the
            // search cannot see the end of it: every one of the 4381 descent searches in a measured
            // run expanded the full ten-thousand-node budget and none of them reached the goal. What
            // comes back each time is the best partial path, which is a walk along the surface - so
            // the bot spent fifteen minutes wandering at y64 with "descending to y 16" on the
            // screen, and the client paid ten thousand node expansions a tick for it.
            //
            // StaircaseProspectTask is what already digs downward for the prospector, safety rules
            // and all: it refuses lava, it refuses to drop gravel on itself, and it stops on the
            // step rather than at the bottom.
            case DESCEND -> {
                if (descendDirection == null) {
                    descendDirection = ctx.player.getDirection();
                }
                int drop = ctx.player.blockPosition().getY() - effectiveY;
                yield new StaircaseProspectTask(descendDirection,
                        Math.min(DESCEND_SEGMENT_STEPS, Math.max(1, drop)), targets);
            }
            // Direction is taken once, when the shaft starts, so later branches stay square to it.
            //
            // Both carry the targets, which is what makes this a mine rather than a hole: the
            // corridor stops for the ore it opens in its own walls, the same way the descent
            // already did. Without that, everything a strip mine exposes is walked past.
            case SHAFT -> new TunnelTask(mainDirection, ctx.player.blockPosition(), spacing,
                    CORRIDOR_HEIGHT, targets);
            case BRANCH -> new TunnelTask(mainDirection.getClockWise(), junction, branchLength,
                    CORRIDOR_HEIGHT, targets);
            case RETURN -> new GotoTask(new Goals.Block(junction), true, true);
        };
        current.start(ctx);
        return true;
    }

    private TaskStatus finishPhase(BotContext ctx) {
        switch (phase) {
            case DESCEND -> {
                descendFailures = 0;
                // One segment is eight blocks of depth, and the band is usually further than that.
                // Staying in this phase starts the next segment on the following tick.
                if (ctx.player.blockPosition().getY() > effectiveY) {
                    return TaskStatus.RUNNING;
                }
                mainDirection = ctx.player.getDirection();
                phase = Phase.SHAFT;
            }
            case SHAFT -> {
                // Remember where the branch leaves the shaft so we can come back to it.
                junction = ctx.player.blockPosition();
                phase = Phase.BRANCH;
            }
            case BRANCH -> phase = Phase.RETURN;
            case RETURN -> {
                branchesDone++;
                if (branchesDone >= branches) {
                    status.set("lune.status.stripmine.dug_branches", branchesDone);
                    return TaskStatus.SUCCESS;
                }
                phase = Phase.SHAFT;
            }
        }
        return TaskStatus.RUNNING;
    }

    private String describe() {
        return switch (phase) {
            case DESCEND -> Lang.get("lune.status.stripmine.descending_to_y", effectiveY);
            case SHAFT -> Lang.get("lune.status.stripmine.shaft_branch",
                    branchesDone + 1, branches);
            case BRANCH -> Lang.get("lune.status.stripmine.branch",
                    branchesDone + 1, branches);
            case RETURN -> Lang.get("lune.status.stripmine.returning_to_shaft");
        };
    }

    @Override
    public void onStop(BotContext ctx) {
        if (current != null) {
            current.stop(ctx);
            current = null;
        }
        ctx.input.reset();
    }
}
