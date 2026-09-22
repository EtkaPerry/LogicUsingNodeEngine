package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.learning.LearningScope;
import com.etka.lune.bot.path.Goals;
import net.minecraft.core.BlockPos;

/** Walks a chosen distance in a cardinal or player-relative direction. */
public final class DirectionalGotoTask implements Task {

    /** Which card this is, so the title is drawn from that card's own name. */
    private final String commandId;
    /** The English the title used to be. Never drawn; it is the learner's row key. */
    private final String label;
    private final String direction;
    private final int distance;
    private final int tolerance;
    private final boolean sprint;

    /**
     * How short the walk may be cut before the heading itself is called impassable.
     * <p>
     * Six blocks is a step off whatever the bot is standing on, which is the whole point of
     * retrying: any movement at all changes the terrain the next search sees.
     */
    private static final int MIN_RETRY_DISTANCE = 6;

    private GotoTask delegate;
    private BlockPos target;
    /** How far this attempt is asking for; halved each time the route comes back refused. */
    private int reach;
    private final StatusText status = new StatusText();
    /** The heading half of the line, kept apart so the walk's own detail can nest beside it. */
    private final StatusText heading = new StatusText();

    public DirectionalGotoTask(String commandId, String label, String direction, int distance,
                               int tolerance, boolean sprint) {
        this.commandId = commandId;
        this.label = label;
        this.direction = direction;
        this.distance = Math.max(1, distance);
        // A tolerance equal to the requested distance would make the task succeed without taking
        // a step. Keep directional travel meaningful even if an old coordinate node stored 32.
        this.tolerance = tolerance >= distance ? 1 : Math.max(0, tolerance);
        this.sprint = sprint;
        updateStatus();
    }

    /**
     * The card's own name, where it was pointed, and how far.
     *
     * <p>Drawn from the language file rather than assembled out of English words. This is the
     * line the queue and the Main tab show, and the one {@code lune.engine.task_finished} says
     * in chat, so a player who is not reading English should not be reading English here.
     * {@code direction} is a dropdown value - an identifier written into saved tasks and
     * switched on by {@link #resolveTarget} - so it goes through the same {@code lune.choice.*}
     * label its own dropdown uses, which translates what is shown without touching what is
     * stored. The verb is the card's, which is why the caller hands over the card's id rather
     * than the word "Walk"; the unit is the one every other card already counts blocks in.</p>
     */
    @Override
    public String name() {
        return Lang.get("lune.task.directional_goto.name",
                Lang.get("lune.command." + commandId + ".name"),
                com.etka.lune.bot.command.Param.Choice.optionLabel(direction), distance,
                Lang.get(distance == 1 ? "lune.card.block_unit" : "lune.unit.blocks"));
    }

    /**
     * English on purpose: this is the learner's row key, and is never shown.
     *
     * <p>Still reached, even though {@link #learningScope()} and
     * {@link #learningContext(BotContext)} are both written out below. Those two name the skill
     * rows; the mission row is {@link com.etka.lune.bot.learning.LearningContext#mission}, which
     * asks this, and the run journal's {@code taskid=} and the debug overlay ask it too. So it
     * is pinned to the English {@link #name()} used to be - "Walk North N blocks", once
     * {@link Task#learningName} has taken the number out - and the rows already on disk stay
     * the rows this card writes to, rather than a second, empty set appearing the first time
     * somebody plays in Turkish. The plural stays wrong at one block for the same reason: it is
     * how every row measured so far was named.</p>
     */
    @Override
    public String learningId() {
        return Task.learningName(label + " " + direction + " " + distance + " blocks");
    }

    /**
     * Directional travel is one job, not one job per compass point.
     *
     * <p>The default context is built from {@link #learningId()}, which names the request rather
     * than the work: "Walk East N blocks" and "Walk North N blocks" would keep separate timing
     * baselines for the same walking. What actually differs between them is how far and whether
     * the bot may sprint, and the route inside is already learned by {@link GotoTask} under
     * "movement" - this entry only times the wrapper.</p>
     */
    @Override
    public LearningScope learningScope() {
        return LearningScope.of("directional-travel", null, travelPhase());
    }

    @Override
    public com.etka.lune.bot.learning.LearningContext learningContext(BotContext ctx) {
        String dimension = ctx == null || ctx.level == null
                ? "unknown" : ctx.level.dimension().identifier().toString();
        return new com.etka.lune.bot.learning.LearningContext("skill", "directional-travel",
                dimension, String.join(";", travelPhase()));
    }

    /** The situation a walk is keyed on; both parts of it are the card's parameters. */
    private String[] travelPhase() {
        return new String[] {
                "distance=" + MovementPolicy.distanceBucket(distance),
                "sprint=" + sprint};
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    /**
     * Where the bot is heading, and what the walk underneath it is doing.
     *
     * <p>{@code direction} is a dropdown value: an identifier written into saved tasks and
     * switched on by {@link #resolveTarget}, so it is never looked up as a line of text. It is
     * drawn through the same {@code lune.choice.*} label the card's own dropdown uses, which
     * translates what is shown without touching what is stored.</p>
     *
     * <p>The walk's own status is nested rather than rendered into words here, so its meaning -
     * bridging, walled in, swimming for air - comes up with it, and the mascot reads the
     * trouble through the wrapper.</p>
     */
    private void updateStatus() {
        if (target == null) {
            status.set("lune.status.directional_goto.choosing_destination");
            return;
        }
        if (delegate == null) {
            status.set("lune.status.directional_goto.heading_to", describe(target));
            return;
        }
        heading.set("lune.status.directional_goto.heading_direction_to",
                com.etka.lune.bot.command.Param.Choice.optionLabel(direction), describe(target));
        StatusText detail = delegate.statusLine();
        if (detail.isBlank()) {
            status.set(heading);
        } else {
            status.set("lune.status.detail", heading, detail);
        }
    }

    @Override
    public void onStart(BotContext ctx) {
        reach = distance;
        startLeg(ctx);
    }

    private void startLeg(BotContext ctx) {
        target = resolveTarget(ctx);
        // Directional travel follows terrain; only X/Z distance matters, unlike exact coordinates.
        delegate = new GotoTask(new Goals.NearXZ(target.getX(), target.getZ(), tolerance), sprint, false);
        delegate.start(ctx);
        updateStatus();
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (delegate == null) {
            onStart(ctx);
        }
        TaskStatus result = delegate.tick(ctx);
        // A refused route is not a refused heading. Forty-eight blocks that way may be across a
        // ravine while six blocks that way is a step onto grass, and the difference matters far
        // more than it sounds: this card sits in loops that rebuild it, so failing immediately
        // hands the same impossible request straight back. A measured run spent 89 seconds on one
        // block cycling Kill, Loot, Eat, Run - the Run giving up after two ticks every time, about
        // three hundred times, while the search burned six thousand nodes on the same unreachable
        // point. Halving is the something a retry has to change; the direction the player asked
        // for is never changed, only how far along it this attempt commits to.
        if (result == TaskStatus.FAILED && reach > MIN_RETRY_DISTANCE) {
            reach = Math.max(MIN_RETRY_DISTANCE, reach / 2);
            delegate.stop(ctx);
            ctx.debug.decide("route refused; trying the same heading " + reach + " blocks instead");
            startLeg(ctx);
            return TaskStatus.RUNNING;
        }
        updateStatus();
        return result;
    }

    @Override
    public void onStop(BotContext ctx) {
        if (delegate != null) {
            delegate.stop(ctx);
            delegate = null;
        }
        ctx.input.reset();
        // The walk is over, so its detail no longer describes anything; drop back to the
        // destination alone rather than leaving a stale "42 blocks left" behind.
        updateStatus();
    }

    /** What this attempt is asking for: the card's distance, or what is left of it after a refusal. */
    private int legDistance() {
        return reach > 0 ? reach : distance;
    }

    private BlockPos resolveTarget(BotContext ctx) {
        double dx;
        double dz;
        switch (direction) {
            case "North" -> { dx = 0; dz = -1; }
            case "South" -> { dx = 0; dz = 1; }
            case "East" -> { dx = 1; dz = 0; }
            case "West" -> { dx = -1; dz = 0; }
            default -> {
                double yaw = Math.toRadians(ctx.player.getYRot());
                double offset = switch (direction) {
                    case "Back" -> Math.PI;
                    case "Left" -> -Math.PI / 2.0;
                    case "Right" -> Math.PI / 2.0;
                    default -> 0.0;
                };
                dx = -Math.sin(yaw + offset);
                dz = Math.cos(yaw + offset);
            }
        }
        return new BlockPos(
                (int) Math.floor(ctx.player.getX() + dx * legDistance()),
                ctx.player.blockPosition().getY(),
                (int) Math.floor(ctx.player.getZ() + dz * legDistance()));
    }

    private static String describe(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
