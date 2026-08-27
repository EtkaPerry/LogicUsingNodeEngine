package com.etka.lune.bot;

import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.learning.TaskLearning;

import java.util.List;
import java.util.Map;

/**
 * One unit of work the bot performs. Tasks are ticked once per client tick and drive the player
 * only through {@link com.etka.lune.bot.input.BotInput} and the vanilla interaction calls.
 */
public interface Task {

    /**
     * Shared lifecycle entry point. All callers, including parent tasks, use this rather than
     * invoking {@link #onStart(BotContext)} directly so nested jobs receive the same learner.
     */
    default void start(BotContext ctx) {
        // A caller that rebuilds/restarts an instance must close what the previous run learned
        // before onStart resets its counters.
        TaskLearning.stop(this, ctx);
        onStart(ctx);
        TaskLearning.start(this, ctx);
    }

    /** Shared tick entry point that measures active game ticks and captures the terminal result. */
    default TaskStatus tick(BotContext ctx) {
        TaskLearning.beforeTick(this, ctx);
        TaskStatus result = onTick(ctx);
        TaskLearning.afterTick(this, ctx, result);
        return result;
    }

    /** Shared stop entry point; cancellation becomes an incomplete skill episode. */
    default void stop(BotContext ctx) {
        TaskLearning.stop(this, ctx);
        onStop(ctx);
    }

    /** Name shown in the queue and on the Main tab. */
    String name();

    /** Called once before the first {@link #onTick}. */
    default void onStart(BotContext ctx) {}

    /** Called every client tick while this task is the active one. */
    TaskStatus onTick(BotContext ctx);

    /** Temporarily yields control to a routine's While monitor without discarding task progress. */
    default void onPause(BotContext ctx) {
        ctx.gameMode.stopDestroyBlock();
        if (ctx.player.isUsingItem()) {
            ctx.gameMode.releaseUsingItem(ctx.player);
        }
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            ctx.mc.setScreen(null);
        }
        ctx.input.reset();
    }

    /** Called when a While monitor has recovered and this task may continue. */
    default void onResume(BotContext ctx) {}

    /** Called when the task finishes, fails, or is cancelled. Must release any held inputs. */
    default void onStop(BotContext ctx) {}

    /** One-line live detail for the Main tab, e.g. "walking, 42 blocks left". */
    default String status() {
        return "";
    }

    /** A bounded amount Lune can render as a progress bar, or {@code null} for open-ended work. */
    default TaskProgress progress() {
        return null;
    }

    /**
     * Work units used to compare differently sized runs. Unlike {@link #progress()}, this may
     * describe open-ended work and does not have to make a useful UI progress bar.
     */
    default TaskProgress learningProgress() {
        return progress();
    }

    /**
     * Long-running producers can close one successful learning episode whenever their work-unit
     * counter advances, instead of waiting for a task that intentionally never ends to be stopped.
     */
    default boolean learningCheckpointOnProgress() {
        return false;
    }

    /** Discrete state used for choosing among this task's safe strategy variants. */
    default LearningContext learningContext(BotContext ctx) {
        String dimension = ctx == null || ctx.level == null
                ? "unknown"
                : ctx.level.dimension().identifier().toString();
        TaskProgress progress = learningProgress();
        String phase = progress == null
                ? "job"
                : "size=" + sizeBucket(progress.target()) + ";unit="
                        + (progress.unit().isBlank() ? "work" : progress.unit());
        return new LearningContext("skill", learningName(name()), dimension, phase);
    }

    /**
     * The job a task is, with the numbers taken out of its display name.
     *
     * <p>Names are written for the Main tab, so they carry the request: "Walk East 64 blocks",
     * "Mine 3 stone". Persisting those verbatim gives every distance and every quota its own row in
     * the learned profile, none of which shares what the last one measured, and the bounded table
     * fills up with near-duplicates of one job. What a run should carry forward is how fast this
     * kind of work goes, and that is the same job at 32 blocks and at 64.</p>
     */
    static String learningName(String name) {
        if (name == null || name.isBlank()) {
            return "job";
        }
        return name.replaceAll("-?\\d+", "N").replaceAll("\\s+", " ").trim();
    }

    /**
     * How much work was asked for, in the coarse steps the rest of the learner already uses.
     * A baseline is only useful if later runs land in the same bucket, and it is genuinely
     * different work to clear four blocks and to clear four hundred.
     */
    static String sizeBucket(int target) {
        if (target <= 1) {
            return "single";
        }
        if (target <= 8) {
            return "few";
        }
        if (target <= 32) {
            return "batch";
        }
        return "large";
    }

    /**
     * Safe alternatives this task is willing to let the local learner compare. A task with no
     * variants still gets session/reward telemetry through the default action.
     */
    default List<String> learningActions(BotContext ctx) {
        return List.of("default");
    }

    /** Receives the selected safe variant after {@link #onStart(BotContext)}. */
    default void onLearningAction(BotContext ctx, String action) {}

    /**
     * Composite routes and control nodes opt out. Their children learn independently, while the
     * engine may still retain mission timing as telemetry.
     */
    default boolean automaticSkillLearning() {
        return true;
    }

    /**
     * Whether this task is an active food-recovery route and must get a tick to find food before
     * the global low-hunger safety stop is applied. Ordinary tasks keep the safety stop.
     */
    default boolean canRecoverFromLowFood(BotContext ctx) {
        return false;
    }

    /** Values this task exposes to downstream routine data ports after it has been evaluated. */
    default Map<String, String> dataOutputs() {
        return Map.of();
    }

    /**
     * For routine looping: did the last run of this task actually do useful work? A step that
     * immediately finds nothing should not advance through the routine when set to "forever".
     */
    default boolean madeProgress() {
        return true;
    }
}
