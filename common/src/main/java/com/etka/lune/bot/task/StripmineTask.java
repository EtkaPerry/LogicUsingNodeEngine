package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.knowledge.OreKnowledge;
import com.etka.lune.bot.path.Goals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;

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

    private Phase phase = Phase.DESCEND;
    private Direction mainDirection;
    private BlockPos junction;
    private Task current;
    private int branchesDone;
    private String status = "";

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
            }
        }
    }

    @Override
    public String name() {
        return "Stripmine";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (current == null && !beginPhase(ctx)) {
            return TaskStatus.FAILED;
        }

        TaskStatus result = current.tick(ctx);
        status = describe() + " - " + current.status();

        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        current.stop(ctx);
        current = null;

        if (result == TaskStatus.FAILED) {
            status = describe() + " failed";
            return TaskStatus.FAILED;
        }
        return finishPhase(ctx);
    }

    /** Creates the task for the current phase. Returns false if the phase can't be started. */
    private boolean beginPhase(BotContext ctx) {
        current = switch (phase) {
            case DESCEND -> new GotoTask(new Goals.YLevel(effectiveY), true, true);
            // Direction is taken once, when the shaft starts, so later branches stay square to it.
            case SHAFT -> new TunnelTask(mainDirection, ctx.player.blockPosition(), spacing, CORRIDOR_HEIGHT);
            case BRANCH -> new TunnelTask(mainDirection.getClockWise(), junction, branchLength, CORRIDOR_HEIGHT);
            case RETURN -> new GotoTask(new Goals.Block(junction), true, true);
        };
        current.start(ctx);
        return true;
    }

    private TaskStatus finishPhase(BotContext ctx) {
        switch (phase) {
            case DESCEND -> {
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
                    status = "dug " + branchesDone + " branches";
                    return TaskStatus.SUCCESS;
                }
                phase = Phase.SHAFT;
            }
        }
        return TaskStatus.RUNNING;
    }

    private String describe() {
        return switch (phase) {
            case DESCEND -> "descending to y " + effectiveY;
            case SHAFT -> "shaft (branch " + (branchesDone + 1) + "/" + branches + ")";
            case BRANCH -> "branch " + (branchesDone + 1) + "/" + branches;
            case RETURN -> "returning to shaft";
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
