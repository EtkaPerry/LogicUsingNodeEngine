package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.LoopWatch;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.WhileMonitor;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.task.BesideOptions;
import com.etka.lune.task.TaskDebug;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.runner.GraphRun;
import com.etka.lune.task.runner.Guard;
import com.etka.lune.task.runner.Step;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's run of a {@link TaskGraph}: the graph's meaning, which is {@link GraphRun}'s, with
 * the player's cards, the canvas's breakpoints and Button, and the run's place in the engine.
 *
 * <p>Every card it builds is one of the player's {@link Task}s, built from its card definition in
 * {@link CommandRegistry}. What a breakpoint, a stall or a failure means here is the panel's:
 * a hold drops the movement keys, a stall is charged to the card on the Main tab, and the failed
 * card stays coloured on the canvas after the run has stopped.</p>
 */
public final class TaskRunner extends GraphRun<BotContext> implements Task {

    /** Static so the blueprint view can still colour the failed card after the task stops. */
    private static TaskGraph lastFailedTask;
    private static String lastFailedNodeId;
    private static final Set<TaskNode> PRESSED_BUTTONS = ConcurrentHashMap.newKeySet();

    /**
     * Whether this run is beside the player. Set before the run starts, by the engine for a task
     * started on its own and by the parent runner for a Run Task card - never read off this graph's
     * own switch, so a task run from inside another follows the run it is part of.
     */
    private boolean beside;

    public TaskRunner(TaskGraph graph) {
        super(graph);
    }

    /** Whether this task, started on its own, asks to run beside the player. */
    @Override
    public boolean wantsBesidePlayer() {
        return graph != null && graph.beside;
    }

    /** The graph's own choice of who comes first and what she may take; see {@link TaskGraph#besideOptions}. */
    @Override
    public BesideOptions besideOptions() {
        return graph == null ? null : graph.besideOptions;
    }

    @Override
    public void runBesidePlayer() {
        beside = true;
    }

    /** Whether this run is beside the player; see {@link TaskGraph#beside}. */
    public boolean besidePlayer() {
        return beside;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.runner.name", graph.displayName());
    }

    /**
     * English on purpose: this is the learner's row key.
     *
     * <p>Derived from {@link Task#name()} by default, which now reads the graph's title - and a
     * starter job's title is translated. A table keyed on that would start empty for every player
     * who changed language, and would gain a second set of rows for the same six jobs. The graph's
     * stored name is the same string whatever the language, so it is the one to key on. The wording
     * matches the English line above so rows learned before this still answer to their old key.</p>
     */
    @Override
    public String learningId() {
        return Task.learningName("Task: " + (graph == null ? "" : graph.name));
    }

    @Override
    public boolean automaticSkillLearning() {
        // A task is a route/container. Every child task is instrumented independently.
        return false;
    }

    /** A short "prev -> [current] -> next" line for the status panel. */
    public String describeFlow() {
        TaskNode node = currentNode();
        if (graph == null || node == null) {
            return Lang.get("lune.task.runner.no_active_task");
        }
        int index = graph.indexOf(node);
        if (index < 0) {
            return displayName(node);
        }
        StringBuilder sb = new StringBuilder();
        if (index > 0) {
            sb.append(displayName(graph.nodes.get(index - 1))).append(" -> ");
        }
        sb.append("[").append(displayName(node)).append("]");
        if (index + 1 < graph.nodes.size()) {
            sb.append(" -> ").append(displayName(graph.nodes.get(index + 1)));
        }
        return sb.toString();
    }

    /** Queues one pulse from the editor's Button control for the next task tick. */
    public static void pressButton(TaskNode button) {
        if (button != null && button.isButtonNode()) {
            PRESSED_BUTTONS.add(button);
        }
    }

    public static boolean isLastFailed(TaskGraph r, TaskNode n) {
        return r == lastFailedTask && n != null && n.id.equals(lastFailedNodeId);
    }

    // --- What the player's run says for itself ----------------------------------------------------

    @Override
    protected Step<BotContext> buildStep(TaskNode node) {
        return buildTask(node);
    }

    private Task buildTask(TaskNode current) {
        CommandDef def = CommandRegistry.byId(current.commandId);
        if (def == null) {
            return null;
        }
        Task built = def.buildWith(resolveParams(current));
        // Every card this run builds - in the lane, on an Always branch, on a While pin - is told
        // before it starts. The sub-tasks a card builds for itself are not, which is the point:
        // Mine's own sweep still ends when the drops are picked up.
        if (beside && built != null) {
            built.runBesidePlayer();
        }
        return built;
    }

    @Override
    protected Object nodeName(TaskNode node) {
        return displayName(node);
    }

    /**
     * While edges may point at any command. Dedicated monitors can decide when to take control;
     * ordinary commands are wrapped so they can run as an independent circuit beside the guarded
     * task step.
     */
    @Override
    protected Guard<BotContext> wrapGuard(Step<BotContext> built) {
        if (built == null) {
            return null;
        }
        return built instanceof WhileMonitor monitor ? monitor : new TaskWhileMonitor((Task) built);
    }

    @Override
    protected void beginWatch() {
        LoopWatch.get().clear();
    }

    @Override
    protected void restarted(TaskNode node) {
        LoopWatch.get().recordRestart(node.id);
    }

    /**
     * Charges the tick to the card, so LoopWatch can name the step that is costing the player their
     * frame rate rather than leaving them to guess which card it was.
     */
    @Override
    protected void sampled(TaskNode node, long nanos, boolean progressed) {
        LoopWatch.get().sample(node.id, displayName(node), graph == null ? "" : graph.name,
                node.repeat == 0, nanos, progressed);
    }

    @Override
    protected void clearHold() {
        TaskDebug.clear();
    }

    @Override
    protected void arrived(TaskNode node) {
        TaskDebug.arrive(node);
    }

    @Override
    protected boolean holding() {
        return TaskDebug.holding();
    }

    @Override
    protected String heldNodeId() {
        return TaskDebug.haltedNodeId();
    }

    @Override
    protected void whileHeld(BotContext ctx) {
        ctx.input.reset();
    }

    @Override
    protected void failedAt(TaskNode node) {
        lastFailedTask = graph;
        lastFailedNodeId = node != null ? node.id : null;
    }

    @Override
    protected void clearFailure() {
        lastFailedTask = null;
        lastFailedNodeId = null;
    }

    @Override
    protected boolean consumePress(TaskNode button) {
        return PRESSED_BUTTONS.remove(button);
    }

    private static String displayName(TaskNode node) {
        if (node == null) {
            return "-";
        }
        CommandDef def = CommandRegistry.byId(node.commandId);
        return def == null ? node.commandId : def.name();
    }

    private static final class TaskWhileMonitor implements WhileMonitor {
        private final Task delegate;
        private boolean started;

        private TaskWhileMonitor(Task delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String status() {
            return delegate.status();
        }

        @Override
        public boolean automaticSkillLearning() {
            // The wrapped command owns the episode. This adapter only lets it occupy a While pin.
            return false;
        }

        @Override
        public void onStart(BotContext ctx) {
            startDelegate(ctx);
        }

        @Override
        public boolean shouldTakeControl(BotContext ctx) {
            return true;
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            if (!started) {
                startDelegate(ctx);
            }
            TaskStatus result = delegate.tick(ctx);
            if (result != TaskStatus.RUNNING) {
                stopDelegate(ctx);
            }
            return result;
        }

        @Override
        public void onControlReleased(BotContext ctx) {
            stopDelegate(ctx);
        }

        @Override
        public boolean holdsControls() {
            return started && delegate.holdsControls();
        }

        @Override
        public void onStop(BotContext ctx) {
            stopDelegate(ctx);
        }

        private void startDelegate(BotContext ctx) {
            if (!started) {
                delegate.start(ctx);
                started = true;
            }
        }

        private void stopDelegate(BotContext ctx) {
            if (started) {
                delegate.stop(ctx);
                started = false;
            }
        }
    }
}
