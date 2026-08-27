package com.etka.lune.bot.learning;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.platform.BuildFeatures;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Instruments every direct and nested task as a bounded skill episode.
 *
 * <p>A task with one action still establishes a best-time baseline. Tasks that expose alternatives
 * additionally learn which safe tactic works best in their context. Composite routes opt out so
 * their children receive credit instead of the route that happened to contain them.</p>
 */
public final class TaskLearning {

    private static final Map<Task, Episode> EPISODES = new IdentityHashMap<>();

    private TaskLearning() {}

    public static synchronized void start(Task task, BotContext ctx) {
        if (task == null || ctx == null || !task.automaticSkillLearning()
                || (!BuildFeatures.releaseBuild() && !ctx.config.learningEnabled)) {
            return;
        }
        LearningContext context = task.learningContext(ctx);
        List<String> actions = task.learningActions(ctx);
        if (actions == null || actions.isEmpty()) {
            actions = List.of("default");
        }
        LearningStore.SkillChoice choice = ctx.learning.chooseSkill(context, actions, actions.get(0));
        task.onLearningAction(ctx, choice.action());
        TaskProgress progress = task.learningProgress();
        Episode episode = new Episode(choice, choice.action(), completed(progress), expected(progress));
        EPISODES.put(task, episode);
        if (ctx.learningSession.active() && choice.actions().size() > 1) {
            ctx.learningSession.decision(context, choice.action());
        }
        publish(ctx, choice, "started");
    }

    public static synchronized void beforeTick(Task task, BotContext ctx) {
        Episode episode = EPISODES.get(task);
        if (episode == null) {
            return;
        }
        ctx.learning.activateSkill(episode.choice);
        if (!episode.observedAction.equals(episode.choice.action())) {
            TaskProgress progress = task.learningProgress();
            int work = Math.max(0, completed(progress) - episode.startedCompleted);
            episode.expectedUnits = Math.max(1, episode.expectedUnits - work);
            episode.startedCompleted = completed(progress);
            episode.ticks = 0;
            episode.observedAction = episode.choice.action();
            task.onLearningAction(ctx, episode.observedAction);
            if (ctx.learningSession.active()) {
                ctx.learningSession.decision(episode.choice.context(), episode.observedAction);
            }
            publish(ctx, episode.choice, "switched");
        }
        episode.ticks++;
    }

    public static synchronized void afterTick(Task task, BotContext ctx, TaskStatus result) {
        Episode episode = EPISODES.get(task);
        if (episode == null) {
            return;
        }
        if (result != null && result != TaskStatus.RUNNING) {
            episode.result = result;
            return;
        }
        TaskProgress progress = task.learningProgress();
        if (task.learningCheckpointOnProgress() && progress != null
                && progress.completed() > episode.startedCompleted) {
            record(task, ctx, episode, true,
                    progress.completed() - episode.startedCompleted,
                    progress.completed() - episode.startedCompleted);
            startContinuation(task, ctx);
        }
    }

    public static synchronized void stop(Task task, BotContext ctx) {
        Episode episode = EPISODES.remove(task);
        if (episode == null) {
            return;
        }
        TaskProgress progress = task.learningProgress();
        if (episode.result == null) {
            // The job never reached a verdict of its own: something above it stopped it. That is a
            // cancellation, not a failure, and the two are constantly confused because callers
            // rebuild a sub-task whenever its target drifts - a Kill chasing a chicken builds a
            // fresh route every few blocks, the harness ends every run on a tick budget, and the
            // player's stop button lands wherever it lands. Scored as failures, those handoffs
            // were the profile: of 1,763 movement episodes in one batch, 1,638 were "-10, no work
            // done", which put all three search tactics at -8.5 and left nothing to choose
            // between. Bank whatever the episode actually produced, at the rate it was producing
            // it, and let one that produced nothing say nothing at all.
            int banked = progress == null
                    ? 0 : Math.max(0, progress.completed() - episode.startedCompleted);
            if (banked > 0) {
                record(task, ctx, episode, true, banked, banked);
            } else {
                ctx.learning.abandonSkill(episode.choice);
            }
            return;
        }
        boolean completed = episode.result == TaskStatus.SUCCESS;
        int workUnits;
        int expectedUnits;
        if (progress != null) {
            workUnits = Math.max(0, progress.completed() - episode.startedCompleted);
            expectedUnits = Math.max(1, episode.expectedUnits);
        } else {
            // madeProgress() defaults true for routine-loop compatibility, so it cannot prove a
            // failed unmeasured job did useful work. Only terminal success earns its one unit.
            workUnits = completed ? 1 : 0;
            expectedUnits = 1;
        }
        record(task, ctx, episode, completed, workUnits, expectedUnits);
    }

    private static void record(Task task, BotContext ctx, Episode episode, boolean completed,
                               int workUnits, int expectedUnits) {
        // A single-tick episode is dropped by the store rather than here, so the tree-chopping
        // measurements MineTask keeps by hand get the same rule.
        SkillOutcome outcome = ctx.learning.recordSkillOutcome(episode.choice, completed,
                workUnits, expectedUnits, Math.max(1, episode.ticks));
        ctx.debug.learningBestTicksPerUnit =
                ctx.learning.bestSkillTicksPerUnit(episode.choice.context());
        if (ctx.learningSession.active()) {
            ctx.learningSession.skillOutcome(episode.choice.context().task(),
                    episode.choice.action(), outcome, episode.choice.actions().size() > 1);
        }
        ctx.debug.learningReward = ctx.learningSession.reward();
        ctx.debug.learningMemory = ctx.learningSession.summary(BuildFeatures.approvalFeedback())
                + "; " + ctx.learning.summary(BuildFeatures.approvalFeedback());
        ctx.debug.decide(episode.choice.context().task() + " " + episode.choice.action()
                + ": " + outcome.summary());
    }

    private static void startContinuation(Task task, BotContext ctx) {
        LearningContext context = task.learningContext(ctx);
        List<String> actions = task.learningActions(ctx);
        if (actions == null || actions.isEmpty()) {
            actions = List.of("default");
        }
        LearningStore.SkillChoice choice = ctx.learning.chooseSkill(context, actions, actions.get(0));
        task.onLearningAction(ctx, choice.action());
        TaskProgress progress = task.learningProgress();
        Episode continuation = new Episode(choice, choice.action(), completed(progress), 1);
        EPISODES.put(task, continuation);
        if (ctx.learningSession.active() && choice.actions().size() > 1) {
            ctx.learningSession.decision(context, choice.action());
        }
        publish(ctx, choice, "continued");
    }

    /** Test support and run-boundary cleanup. */
    public static synchronized void clear() {
        EPISODES.clear();
    }

    private static void publish(BotContext ctx, LearningStore.SkillChoice choice, String verb) {
        ctx.debug.learningContext = choice.context().key();
        ctx.debug.learningAction = choice.action();
        ctx.debug.learningBestTicksPerUnit = ctx.learning.bestSkillTicksPerUnit(choice.context());
        ctx.debug.decide(choice.context().task() + " skill " + verb + " with " + choice.action());
    }

    private static int completed(TaskProgress progress) {
        return progress == null ? 0 : progress.completed();
    }

    private static int expected(TaskProgress progress) {
        return progress == null ? 1 : Math.max(1, progress.target() - progress.completed());
    }

    private static final class Episode {
        private final LearningStore.SkillChoice choice;
        private String observedAction;
        private int startedCompleted;
        private int expectedUnits;
        private int ticks;
        private TaskStatus result;

        private Episode(LearningStore.SkillChoice choice, String observedAction,
                        int startedCompleted, int expectedUnits) {
            this.choice = choice;
            this.observedAction = observedAction;
            this.startedCompleted = startedCompleted;
            this.expectedUnits = expectedUnits;
        }
    }
}
