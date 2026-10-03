package com.etka.lune.bot;

import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.learning.LearningScope;
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
        // Measured here rather than at each call site: every task, monitor and nested child goes
        // through this one door, so one line instruments the whole tree and the profiler's nesting
        // shows which level the time is actually spent at.
        LuneProfiler.push(name());
        TaskStatus result;
        try {
            result = onTick(ctx);
        } finally {
            LuneProfiler.pop();
        }
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

    /** Temporarily yields control to a task's While monitor without discarding task progress. */
    default void onPause(BotContext ctx) {
        ctx.gameMode.stopDestroyBlock();
        if (ctx.player.isUsingItem()) {
            ctx.gameMode.releaseUsingItem(ctx.player);
        }
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.gameMode.closeContainer();
        }
        ctx.input.reset();
    }

    /** Called when a While monitor has recovered and this task may continue. */
    default void onResume(BotContext ctx) {}

    /** Called when the task finishes, fails, or is cancelled. Must release any held inputs. */
    default void onStop(BotContext ctx) {}

    /**
     * One-line live detail for the Main tab, e.g. "walking, 42 blocks left".
     *
     * <p>Held as a key and its arguments rather than as a finished sentence, so the Main tab can
     * render it in the player's language and the mascot can read what it means without either of
     * them going through the other's answer. Tasks override this one; {@link #status()} and
     * {@link #statusSignal()} follow from it.</p>
     */
    default StatusText statusLine() {
        return StatusText.EMPTY;
    }

    /** The live detail as words, in whatever language is loaded. */
    default String status() {
        return statusLine().text();
    }

    /**
     * What the live detail means - danger, a blockage, a full inventory - independent of language.
     * This is what the mascot reads.
     */
    default StatusSignal statusSignal() {
        return statusLine().signal();
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

    /**
     * Discrete state used for choosing among this task's safe strategy variants.
     *
     * <p>The unit is the one {@link #learningScope()} names, written as an identifier:
     * {@code unit=logs} for {@code lune.unit.logs}. Not {@link TaskProgress#unit()}. That is the
     * progress bar's word in the player's language, and keyed on it the same job wrote
     * {@code unit=logs} in English and {@code unit=kütük} in Turkish - one row per language, each
     * unreachable the moment the wording changed. Taking the unit from the scope also makes the
     * card and the key agree by construction.</p>
     */
    default LearningContext learningContext(BotContext ctx) {
        String dimension = ctx == null || ctx.level == null
                ? "unknown"
                : ctx.level.dimension().identifier().toString();
        TaskProgress progress = learningProgress();
        String phase = progress == null
                ? "job"
                : "size=" + sizeBucket(progress.target()) + ";unit=" + learningScope().unitId();
        return new LearningContext("skill", learningId(), dimension, phase);
    }

    /**
     * The job this task is, as a name the learner can store.
     *
     * <p>Deliberately not {@link #name()}. That one is shown to the player and is therefore
     * translated, and a policy table keyed on it would start a fresh, empty set of rows the first
     * time somebody played in Turkish - the same bot, relearning the same jobs, once per language.
     * A task whose displayed name is translated overrides this with the English it used to be, so
     * the rows already on disk keep being the rows it writes to.</p>
     */
    default String learningId() {
        return learningName(name());
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
     * The rows of the learned profile this task measures into, as far as its parameters can say.
     *
     * <p>{@link #learningContext(BotContext)} names the exact row, and only once the bot is in a
     * world. The editor has no world and still puts on a card what its job has measured, so this
     * is the half of the key the card fixes by itself. A task that builds its phase from
     * parameters says so here with the same entries - built by the same helper, so the two
     * cannot drift apart - and a task whose whole phase is decided at run time gives the skill
     * alone. Whatever is named here must be what the context writes, or the card reports on rows
     * its job never touches. The unit is shared by construction: the default context writes this
     * scope's {@link LearningScope#unitId()}, and the card renders the same key.</p>
     */
    default LearningScope learningScope() {
        return LearningScope.of(learningId());
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

    /** Values this task exposes to downstream task data ports after it has been evaluated. */
    default Map<String, String> dataOutputs() {
        return Map.of();
    }

    /**
     * For task looping: did the last run of this task actually do useful work? A step that
     * immediately finds nothing should not advance through the task when set to "forever".
     */
    default boolean madeProgress() {
        return true;
    }

    /**
     * Tells this card that its task runs beside the player rather than in their place.
     *
     * <p>Called once, before the card starts, by the runner that built it - never on the sub-tasks
     * a card builds for itself, so Mine's own sweep still finishes when the drops are gone. A card
     * that looks for its work answers by waiting until the work is in the player's view, instead of
     * turning the head or walking off to search: see {@link Beside}. A card with nothing to wait
     * for ignores this and acts the moment power reaches it, as it always has.</p>
     */
    default void runBesidePlayer() {}

    /**
     * Whether this task, when it is started on its own, asks to run beside the player - a task
     * whose {@link com.etka.lune.task.TaskGraph#beside} switch is on. The engine asks once, as the
     * run begins, and answers with {@link #runBesidePlayer()}; a task started from inside another
     * follows the run it is part of instead.
     */
    default boolean wantsBesidePlayer() {
        return false;
    }

    /**
     * How a run of this task beside the player shares the controls - who comes first, and what Lune
     * may take - when it is started on its own; read once, as the run begins, with
     * {@link #wantsBesidePlayer()}. Null means Lune first with everything, as every beside run was
     * before there was a choice.
     */
    default com.etka.lune.task.BesideOptions besideOptions() {
        return null;
    }

    /**
     * Whether this task needs the player's controls after the tick it has just taken.
     *
     * <p>Asked only of a run beside the player, where it decides who holds the keys: the player
     * while every powered card is waiting, Lune from the tick a card has something to do. True by
     * default, because a card that moves the player is the ordinary case. A card that never touches
     * the controls - a check, a timer, a sound - says false, so its tick does not freeze somebody
     * who is walking; a card waiting for its work to come into view says false while it waits.</p>
     */
    default boolean holdsControls() {
        return true;
    }
}
