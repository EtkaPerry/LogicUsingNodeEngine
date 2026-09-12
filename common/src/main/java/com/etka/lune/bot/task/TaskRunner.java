package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.LoopWatch;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.WhileMonitor;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskDataLink;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.TaskWiring;
import com.etka.lune.task.TaskPower;
import com.etka.lune.task.TaskSignalLink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

/**
 * Runs a {@link TaskGraph} as a set of independently powered circuits. START sends one signal into
 * the primary circuit, while Always periodically sends a new signal into each of its independent
 * target circuits beside it.
 * <p>
 * Each circuit builds a real task and is ticked normally; when it finishes, that circuit's SUCCESS
 * or FAILED edge decides what runs next. Nodes with no edge fall through to the next in the list,
 * which makes the common "do these in order" circuit need no wiring at all, while an edge pointing
 * backwards gives a loop. Circuits share the player's input and world, but no circuit is selected
 * over another by the task runner.
 * <p>
 * At most one node transition happens per circuit per client tick, so even a cycle of
 * instantly-failing nodes costs one step per circuit tick rather than spinning.
 */
public final class TaskRunner implements Task {

    private final TaskGraph graph;

    /** Static so the blueprint view can still colour the failed card after the task stops. */
    private static TaskGraph lastFailedTask;
    private static String lastFailedNodeId;

    private TaskNode node;
    private Task task;
    private WhileMonitor monitor;
    private TaskNode monitorNode;
    private boolean monitorRunning;
    /** Independent circuits powered by Always (or the legacy task-level While source). */
    private final List<ParallelCircuit> parallelCircuits = new ArrayList<>();
    /** Signals emitted while a circuit is being ticked; applied after the iterator finishes. */
    private final List<ParallelCircuit> pendingParallelCircuits = new ArrayList<>();
    /** Independent clocks owned by Always sources; each pulse creates its own circuit. */
    private final List<AlwaysPulseSource> alwaysPulseSources = new ArrayList<>();
    private final List<ObserverPulseSource> observerPulseSources = new ArrayList<>();
    private final List<ButtonPulseSource> buttonPulseSources = new ArrayList<>();
    private final Map<String, Integer> counterCounts = new HashMap<>();
    private static final Set<TaskNode> PRESSED_BUTTONS = ConcurrentHashMap.newKeySet();
    private int iteration;
    private final StatusText status = new StatusText();
    private final StatusText startupError = new StatusText();
    /** Delay state for a Timer used by the primary START circuit. */
    private boolean timerStarted;
    private int timerWaitTicks;
    private int timerPulseCount;
    /** Ticks to wait before restarting a forever step that produced no progress. */
    private int cooldown;
    /** Recent emissions expire independently of the receiving task's lifetime. */
    private final Map<String, TaskPower.Pulse> wirePulses = new HashMap<>();
    private final Map<String, Long> sourcePulses = new HashMap<>();
    /** This tick's power picture, sampled once so every Observer reads the same frame. */
    private TaskPower powerSample = TaskPower.NONE;
    /** Last completed values from each node, used by downstream exposed parameters. */
    private final Map<String, Map<String, String>> nodeOutputs = new HashMap<>();

    public TaskRunner(TaskGraph graph) {
        this.graph = graph;
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
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return task == null ? null : task.progress();
    }

    public TaskGraph currentTask() {
        return graph;
    }

    public TaskNode currentNode() {
        return node;
    }

    /**
     * Node currently receiving control. While self-preservation has interrupted a normal node,
     * this is the monitor node rather than the paused work node.
     */
    @Override
    public boolean automaticSkillLearning() {
        // A task is a route/container. Every child task is instrumented independently.
        return false;
    }

    public TaskNode activeNode() {
        return node;
    }

    /**
     * Everything currently carrying a signal, across every circuit at once.
     *
     * <p>{@link #activeNode()} answers for the primary lane only, which is why an Always branch
     * that was running perfectly well never lit up: it has no bearing on {@code node}. The canvas
     * needs the whole picture - where each pulse started and where it has got to - so it reads
     * this instead.</p>
     */
    public TaskPower livePower() {
        Set<String> nodes = new LinkedHashSet<>();
        long now = System.nanoTime();
        wirePulses.values().removeIf(pulse -> now - pulse.latest() >= TaskPower.PULSE_NANOS);
        sourcePulses.values().removeIf(start -> now - start >= TaskPower.PULSE_NANOS);
        nodes.addAll(sourcePulses.keySet());
        Set<String> wires = new LinkedHashSet<>();
        if (node != null && node.id != null) {
            nodes.add(node.id);
            addMonitorPower(nodes, wires, node, monitorNode, monitorRunning);
        }
        for (ParallelCircuit circuit : parallelCircuits) {
            circuit.collectPower(nodes, wires);
        }
        for (ParallelCircuit circuit : pendingParallelCircuits) {
            circuit.collectPower(nodes, wires);
        }
        return new TaskPower(nodes, wires, wirePulses);
    }

    private void addMonitorPower(Set<String> nodes, Set<String> wires, TaskNode guarded,
                                 TaskNode companion, boolean running) {
        if (!running || companion == null || companion.id == null || guarded == null
                || guarded.id == null) {
            return;
        }
        nodes.add(companion.id);
        wires.add(TaskPower.wire(guarded.id, TaskPower.WHILE, 0, companion.id));
    }

    /** Names the edge just followed. Fall-through draws no wire, so it lights none either. */
    private String wireFrom(TaskNode from, String targetId, int kind, TaskNode to) {
        String wire = targetId == null || from == null || from.id == null || to == null || to.id == null
                ? null : TaskPower.wire(from.id, kind, 0, to.id);
        recordPulse(from, wire);
        return wire;
    }

    private void recordPulse(TaskNode source, String wire) {
        if (wire == null) return;
        long now = System.nanoTime();
        wirePulses.compute(wire, (key, pulse) -> pulse == null
                ? new TaskPower.Pulse(now, now) : pulse.refresh(now));
        // Work and processing cards light only while they are executing. Emitting a pulse
        // must not keep a completed Timer, Counter or Relay powered for its child's lifetime.
        if (source != null && source.isSourceNode() && source.id != null) {
            sourcePulses.put(source.id, now);
        }
    }

    /** A short "prev -> [current] -> next" line for the status panel. */
    public String describeFlow() {
        if (graph == null || node == null) {
            return Lang.get("lune.task.runner.no_active_task");
        }
        int index = graph.indexOf(node);
        if (index < 0) {
            return nodeName(node);
        }
        StringBuilder sb = new StringBuilder();
        if (index > 0) {
            sb.append(nodeName(graph.nodes.get(index - 1))).append(" -> ");
        }
        sb.append("[").append(nodeName(node)).append("]");
        if (index + 1 < graph.nodes.size()) {
            sb.append(" -> ").append(nodeName(graph.nodes.get(index + 1)));
        }
        return sb.toString();
    }

    @Override
    public void onStart(BotContext ctx) {
        startupError.clear();
        wirePulses.clear();
        sourcePulses.clear();
        // A verdict about the previous run says nothing about this one, and carrying one over
        // would let a task be blamed for a stall it inherited.
        LoopWatch.get().clear();
        TaskNode explicitStart = TaskWiring.explicitStart(graph);
        if (explicitStart != null) {
            long startCount = graph.nodes.stream()
                    .filter(candidate -> candidate != null && candidate.isStartNode())
                    .count();
            TaskNode target = explicitStart.onSuccess == null
                    ? null : graph.nodeById(explicitStart.onSuccess);
            if (startCount > 1) {
                startupError.set("lune.status.task_runner.multiple_start");
            } else if (target == null) {
                startupError.set("lune.status.task_runner.start_not_connected");
            } else if (target.isSourceNode()) {
                startupError.set("lune.status.task_runner.start_needs_action");
            }
            node = startupError.isBlank() ? target : explicitStart;
            wireFrom(explicitStart, explicitStart.onSuccess,
                    TaskPower.SUCCESS, node);
        } else {
            node = nextSequentialNode(-1);
        }
        iteration = 0;
        timerStarted = false;
        timerWaitTicks = 0;
        timerPulseCount = 0;
        counterCounts.clear();
        lastFailedTask = null;
        lastFailedNodeId = null;
        nodeOutputs.clear();
        prepareParallelCircuits(ctx);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (!startupError.isBlank()) {
            status.set(startupError);
            markFailed();
            return TaskStatus.FAILED;
        }
        // Sampled before anything moves, so an Observer compares like with like: this is the
        // picture as the previous tick left it, and a change in it is a real transition.
        powerSample = livePower();
        tickAlwaysSources(ctx);
        if (node == null) {
            TaskStatus parallelResult = tickParallelCircuits(ctx);
            if (parallelResult == TaskStatus.FAILED) {
                return TaskStatus.FAILED;
            }
            if (completionStatus() == TaskStatus.SUCCESS) {
                status.set("lune.status.task_runner.finished");
            } else {
                status.set("lune.status.task_runner.running_independent_circuits");
            }
            return completionStatus();
        }

        if (node.isTimerNode()) {
            if (!timerStarted) {
                timerStarted = true;
                timerPulseCount = 0;
                timerWaitTicks = timerTicks(node);
            }
            if (timerWaitTicks > 0) {
                timerWaitTicks--;
                status.set("lune.status.task_runner.timer_waiting_s", timerSeconds(node));
                return tickParallelOrRunning(ctx);
            }
            emitRelay(node, null, ctx);
            timerPulseCount++;
            if (node.repeat == 0 || timerPulseCount < node.repeat) {
                timerWaitTicks = timerTicks(node);
                status.set("lune.status.task_runner.timer_forwarded_pulse_waiting_next_one");
                return tickParallelOrRunning(ctx);
            }
            node = null;
            iteration = 0;
            timerStarted = false;
            status.set("lune.status.task_runner.timer_forwarded_pulse");
            return tickParallelOrFinished(ctx);
        }

        if (node.isCounterNode()) {
            boolean output = countPulse(node);
            if (output) {
                emitRelay(node, null, ctx);
                status.set("lune.status.task_runner.counter_forwarded_pulse");
            } else {
                status.set("lune.status.task_runner.counter_waiting_pulses", counterTarget(node));
            }
            node = null;
            return tickParallelOrFinished(ctx);
        }

        if (node.isEndNode()) {
            node = null;
            iteration = 0;
            status.set("lune.status.task_runner.end_consumed_pulse");
            return tickParallelOrFinished(ctx);
        }

        if (node.isSignalRelayNode()) {
            emitRelay(node, null, ctx);
            node = null;
            iteration = 0;
            status.set("lune.status.task_runner.relay_sent_pulse");
            TaskStatus parallelResult = tickParallelCircuits(ctx);
            if (parallelResult == TaskStatus.FAILED) {
                return TaskStatus.FAILED;
            }
            return completionStatus();
        }

        if (cooldown > 0) {
            cooldown--;
            TaskStatus monitorResult = tickLocalMonitor(ctx);
            if (monitorResult == TaskStatus.FAILED) {
                return failOrBranch(ctx, "lune.status.task_runner.while_failed",
                        monitor.statusLine());
            }
            TaskStatus parallelResult = tickParallelCircuits(ctx);
            if (parallelResult == TaskStatus.FAILED) {
                return TaskStatus.FAILED;
            }
            status.set("lune.status.task_runner.pausing_2", describeStep());
            return TaskStatus.RUNNING;
        }

        if (task == null) {
            task = buildTask(node);
            if (task == null) {
                status.set("lune.status.task_runner.unknown_command", node.commandId);
                markFailed();
                return TaskStatus.FAILED;
            }
            task.start(ctx);
            // Every build of a step is that step starting. Counting them here rather than at the
            // several places a step can end covers a loop that comes back round just as well as a
            // 'forever' card restarting in place - both are "this keeps starting" to the player.
            LoopWatch.get().recordRestart(node.id);
            if (!prepareMonitor(ctx)) {
                task.stop(ctx);
                task = null;
                return failOrBranch(ctx, "lune.status.task_runner.while_not_created");
            }
        }

        TaskStatus result = timedTick(ctx);
        status.set(describeStep());
        TaskStatus monitorResult = tickLocalMonitor(ctx);
        if (monitorResult == TaskStatus.FAILED) {
            return failOrBranch(ctx, "lune.status.task_runner.while_failed",
                    monitor.statusLine());
        }

        if (result == TaskStatus.RUNNING) {
            TaskStatus parallelResult = tickParallelCircuits(ctx);
            if (parallelResult == TaskStatus.FAILED) {
                return TaskStatus.FAILED;
            }
            return TaskStatus.RUNNING;
        }

        Task finished = task;
        recordNodeOutputs(node);
        StatusText childStatus = new StatusText().set(task.statusLine());
        task.stop(ctx);
        task = null;

        if (result == TaskStatus.SUCCESS) {
            iteration++;
            if (node.repeat == 0) {
                handleForeverSuccess(ctx, finished);
                return tickParallelOrFinished(ctx);
            }
            if (iteration < node.repeat) {
                return tickParallelOrRunning(ctx);
            }
            stopMonitor(ctx);
            advance(node.onSuccess, TaskPower.SUCCESS);
        } else {
            if (node.onFailure == null) {
                stopMonitor(ctx);
                if (childStatus.isBlank()) {
                    status.set("lune.status.task_runner.step_failed", node.commandId);
                } else {
                    status.set("lune.status.task_runner.step_failed_because",
                            node.commandId, childStatus);
                }
                markFailed();
                return TaskStatus.FAILED;
            }
            stopMonitor(ctx);
            advance(node.onFailure, TaskPower.FAILURE);
        }

        TaskStatus parallelResult = tickParallelCircuits(ctx);
        if (parallelResult == TaskStatus.FAILED) {
            return TaskStatus.FAILED;
        }
        return completionStatus();
    }

    private TaskStatus handleForeverSuccess(BotContext ctx, Task finished) {
        boolean progressed = finished.madeProgress();
        boolean last = isLastNode();

        if (progressed && !last) {
            // Step did work and there is more to do, so move on (e.g. Find found something -> Mine).
            stopMonitor(ctx);
            advance(node.onSuccess, TaskPower.SUCCESS);
            return node == null ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
        }

        if (!progressed && !last) {
            // No work done, but there is a later step that may handle it (e.g. Find found 0 -> Mine).
            stopMonitor(ctx);
            advance(node.onSuccess, TaskPower.SUCCESS);
            return node == null ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
        }

        if (progressed && last) {
            // Last step is producing; keep it running immediately.
            return TaskStatus.RUNNING;
        }

        // Last step, no progress: cool down so a 'forever' task doesn't spin tightly.
        cooldown = 20;
        return TaskStatus.RUNNING;
    }

    /**
     * Ticks the current step and charges the time to it, so LoopWatch can name the step that is
     * costing the player their frame rate rather than leaving them to guess which card it was.
     *
     * <p>Two {@code nanoTime} calls per tick and nothing else. This has to be measured whether or
     * not the debug profiler is running, because the whole point is to notice a stall on behalf of
     * somebody who has not turned any diagnostics on.</p>
     */
    private TaskStatus timedTick(BotContext ctx) {
        long began = System.nanoTime();
        try {
            return task.tick(ctx);
        } finally {
            LoopWatch.get().sample(node.id, nodeName(node), graph == null ? "" : graph.name,
                    node.repeat == 0, System.nanoTime() - began,
                    task != null && task.madeProgress());
        }
    }

    private TaskStatus tickParallelOrRunning(BotContext ctx) {
        TaskStatus parallelResult = tickParallelCircuits(ctx);
        return parallelResult == TaskStatus.FAILED ? TaskStatus.FAILED : TaskStatus.RUNNING;
    }

    private TaskStatus tickParallelOrFinished(BotContext ctx) {
        TaskStatus parallelResult = tickParallelCircuits(ctx);
        if (parallelResult == TaskStatus.FAILED) {
            return TaskStatus.FAILED;
        }
        return completionStatus();
    }

    /** Sources remain armed between emissions, even when no child is running. */
    private TaskStatus completionStatus() {
        return node != null || !parallelCircuits.isEmpty() || !pendingParallelCircuits.isEmpty()
                || !alwaysPulseSources.isEmpty() || !observerPulseSources.isEmpty()
                || !buttonPulseSources.isEmpty() ? TaskStatus.RUNNING : TaskStatus.SUCCESS;
    }

    private boolean hasSignalTarget(TaskNode source) {
        return source.signalLinks != null && source.signalLinks.stream().anyMatch(link -> {
            if (link == null || link.outputPort < 0 || link.outputPort >= source.signalOutputCount) return false;
            TaskNode target = graph.nodeById(link.targetNodeId);
            return target != null && !target.isSourceNode();
        });
    }

    private boolean isLastNode() {
        return !TaskWiring.hasSuccessDestination(graph, node);
    }

    @Override
    public void onStop(BotContext ctx) {
        if (task != null) {
            task.stop(ctx);
            task = null;
        }
        stopMonitor(ctx);
        stopParallelCircuits(ctx);
        node = null;
        wirePulses.clear();
        sourcePulses.clear();
    }

    /** Follows an explicit edge, or falls through to the next node in the list when there is none. */
    private void advance(String targetId, int kind) {
        iteration = 0;
        TaskNode from = node;
        if (targetId != null) {
            node = graph.nodeById(targetId);
        } else {
            int index = graph.indexOf(node);
            node = index < 0 ? null : nextSequentialNode(index);
        }
        wireFrom(from, targetId, kind, node);
    }

    private TaskNode nextSequentialNode(int afterIndex) {
        return TaskWiring.nextSequentialNode(graph, afterIndex);
    }

    private boolean prepareMonitor(BotContext ctx) {
        if (node.onWhile == null) {
            return true;
        }
        if (monitor != null && monitorNode != null && node.onWhile.equals(monitorNode.id)) {
            return true;
        }
        TaskNode linked = graph.nodeById(node.onWhile);
        if (linked == null) {
            return false;
        }
        WhileMonitor whileMonitor = asWhileMonitor(buildTask(linked));
        if (whileMonitor == null) {
            return false;
        }
        monitorNode = linked;
        monitor = whileMonitor;
        monitorRunning = false;
        monitor.start(ctx);
        return true;
    }

    private void prepareParallelCircuits(BotContext ctx) {
        parallelCircuits.clear();
        pendingParallelCircuits.clear();
        alwaysPulseSources.clear();
        observerPulseSources.clear();
        buttonPulseSources.clear();
        counterCounts.clear();
        if (graph.onWhile != null) {
            addParallelCircuit(ctx, graph.nodeById(graph.onWhile), null);
        }
        for (TaskNode source : graph.nodes) {
            if (source.isClockNode() && source.alwaysTargets != null) {
                for (String targetId : source.alwaysTargets) {
                    addAlwaysPulseSource(source, graph.nodeById(targetId));
                }
            }
            if (source.isObserverNode() && graph.nodeById(source.observedNodeId) != null
                    && hasSignalTarget(source)) {
                observerPulseSources.add(new ObserverPulseSource(source));
            }
            if (source.isButtonNode() && hasSignalTarget(source)) {
                buttonPulseSources.add(new ButtonPulseSource(source));
            }
        }
    }

    private void addAlwaysPulseSource(TaskNode source, TaskNode target) {
        if (source != null && target != null && !target.isSourceNode()) {
            alwaysPulseSources.add(new AlwaysPulseSource(source, target));
        }
    }

    private void tickAlwaysSources(BotContext ctx) {
        for (AlwaysPulseSource pulseSource : alwaysPulseSources) {
            pulseSource.tick(ctx);
        }
        for (ObserverPulseSource pulseSource : observerPulseSources) {
            pulseSource.tick(ctx);
        }
        for (ButtonPulseSource pulseSource : buttonPulseSources) {
            pulseSource.tick(ctx);
        }
    }

    private void addParallelCircuit(BotContext ctx, TaskNode linked, TaskNode source) {
        if (linked == null || linked.isSourceNode()) {
            return;
        }
        // The legacy task-level While has no card of its own, so this branch has no visible
        // origin to light.
        parallelCircuits.add(new ParallelCircuit(linked, source, null, null));
    }

    private void queueParallelCircuit(BotContext ctx, TaskNode linked, TaskNode source,
                                      TaskNode origin, String originWire) {
        if (linked == null || linked.isSourceNode()) {
            return;
        }
        // An absorbed pulse is still a real emission. Refresh its indicator without
        // allocating another worker or restarting a spark that is already travelling.
        recordPulse(origin, originWire);
        // A clock card keeps pulsing - that is what Always/Pulse is for - but a branch already
        // carrying that clock's direct signal absorbs the next pulse instead of forking. A power
        // source held on energises one wire; it does not create a new independent wire twenty times
        // a second.
        //
        // This is load-bearing, not tidiness. A branch that ends on a card set to x∞ never
        // finishes, so without absorbing, every pulse would leave another live circuit and another
        // live child task behind, and the client would stall within seconds of starting the task.
        //
        // Two different sources aimed at the same card still get a circuit each: what is compared
        // is the pair of source and entry card, so independent sources stay independent.
        // A Timer output is different: it is a one-shot pulse emitted after the Timer receives its
        // input. A Timer commonly points back through a few command cards to itself; coalescing that
        // pulse with the circuit that just emitted it makes the first cycle work and silently
        // discards every later cycle. Keep the existing coalescing for other pulse origins so a
        // long-running Always or relay branch does not fork another circuit on every tick.
        if ((origin == null || !origin.isTimerNode())
                && (hasCircuit(linked, origin, parallelCircuits)
                || hasCircuit(linked, origin, pendingParallelCircuits))) {
            return;
        }
        pendingParallelCircuits.add(new ParallelCircuit(linked, source, origin, originWire));
    }

    /** A branch is "already carrying this signal" when the same source drives the same entry card. */
    private boolean hasCircuit(TaskNode entry, TaskNode origin, List<ParallelCircuit> circuits) {
        return circuits.stream().anyMatch(circuit -> circuit.entryNode == entry
                && circuit.origin == origin && !circuit.finished);
    }

    private void emitRelay(TaskNode relay, TaskNode pulseSource, BotContext ctx) {
        if (relay == null || relay.signalLinks == null) {
            return;
        }
        for (TaskSignalLink link : relay.signalLinks) {
            if (link == null || link.outputPort < 0 || link.outputPort >= relay.signalOutputCount) {
                continue;
            }
            TaskNode target = graph.nodeById(link.targetNodeId);
            if (target == null || target.isSourceNode()) {
                continue;
            }
            queueParallelCircuit(ctx, target, pulseSource, relay,
                    TaskPower.wire(relay.id, TaskPower.SIGNAL, link.outputPort, target.id));
        }
    }

    /** Queues one pulse from the editor's Button control for the next task tick. */
    public static void pressButton(TaskNode button) {
        if (button != null && button.isButtonNode()) {
            PRESSED_BUTTONS.add(button);
        }
    }

    private static boolean consumeButtonPress(TaskNode button) {
        return PRESSED_BUTTONS.remove(button);
    }

    private int counterTarget(TaskNode counter) {
        if (counter == null || counter.params == null) {
            return 3;
        }
        try {
            return Math.clamp(Integer.parseInt(counter.params.getOrDefault("count", "3")), 1, 1_000_000);
        } catch (NumberFormatException ignored) {
            return 3;
        }
    }

    private boolean countPulse(TaskNode counter) {
        int count = counterCounts.getOrDefault(counter.id, 0) + 1;
        if (count >= counterTarget(counter)) {
            counterCounts.remove(counter.id);
            return true;
        }
        counterCounts.put(counter.id, count);
        return false;
    }

    private int timerSeconds(TaskNode timer) {
        if (timer == null || timer.params == null) {
            return 5;
        }
        try {
            return Math.clamp(Integer.parseInt(timer.params.getOrDefault("seconds", "5")), 0, 3600);
        } catch (NumberFormatException ignored) {
            return 5;
        }
    }

    private int timerTicks(TaskNode timer) {
        return timerSeconds(timer) * 20;
    }

    /** Ticks the node attached to the main circuit's While pin without pausing the main task. */
    private TaskStatus tickLocalMonitor(BotContext ctx) {
        if (monitor == null) {
            return null;
        }
        if (!monitor.shouldTakeControl(ctx)) {
            if (monitorRunning) {
                monitor.onControlReleased(ctx);
                monitorRunning = false;
            }
            return null;
        }
        monitorRunning = true;
        TaskStatus result = monitor.tick(ctx);
        if (result == TaskStatus.FAILED) {
            return TaskStatus.FAILED;
        }
        if (result != TaskStatus.RUNNING) {
            monitor.onControlReleased(ctx);
            monitorRunning = false;
        }
        return null;
    }

    /** Ticks every Always/legacy-While circuit; none of them is selected over another. */
    private TaskStatus tickParallelCircuits(BotContext ctx) {
        String lastStatus = "";
        var iterator = parallelCircuits.iterator();
        while (iterator.hasNext()) {
            ParallelCircuit circuit = iterator.next();
            TaskStatus result = circuit.onTick(ctx);
            if (result == TaskStatus.FAILED) {
                status.set("lune.status.task_runner.circuit_failed", nodeName(circuit.node), (circuit.status.isBlank() ? "" : ": " + circuit.status));
                markFailed();
                return TaskStatus.FAILED;
            }
            if (circuit.finished) {
                iterator.remove();
            } else if (!circuit.status.isBlank()) {
                lastStatus = nodeName(circuit.node) + " - " + circuit.status;
            }
        }
        if (node == null && !lastStatus.isBlank()) {
            status.set("lune.status.task_runner.circuit", lastStatus);
        }
        if (!pendingParallelCircuits.isEmpty()) {
            parallelCircuits.addAll(pendingParallelCircuits);
            pendingParallelCircuits.clear();
        }
        return null;
    }

    private TaskStatus failOrBranch(BotContext ctx, String key, Object... args) {
        if (task != null) {
            task.stop(ctx);
            task = null;
        }
        stopMonitor(ctx);
        if (node.onFailure == null) {
            status.set(key, args);
            markFailed();
            return TaskStatus.FAILED;
        }
        advance(node.onFailure, TaskPower.FAILURE);
        status.set(key, args);
        return tickParallelOrFinished(ctx);
    }

    private void stopMonitor(BotContext ctx) {
        if (monitor != null) {
            monitor.stop(ctx);
        }
        monitor = null;
        monitorNode = null;
        monitorRunning = false;
    }

    private void stopParallelCircuits(BotContext ctx) {
        for (ParallelCircuit circuit : parallelCircuits) {
            circuit.onStop(ctx);
        }
        parallelCircuits.clear();
        pendingParallelCircuits.clear();
        alwaysPulseSources.clear();
        observerPulseSources.clear();
        buttonPulseSources.clear();
        counterCounts.clear();
    }

    private Task buildTask(TaskNode current) {
        CommandDef def = CommandRegistry.byId(current.commandId);
        if (def == null) {
            return null;
        }
        return def.buildWith(resolveParams(current));
    }

    /** One independent flow powered by a single electrical pulse. */
    private final class ParallelCircuit {
        private final TaskNode entryNode;
        private final TaskNode source;
        /** The card that emitted the pulse: where this branch's electricity started. */
        private final TaskNode origin;
        /** Original edge, retained only for a periodic circuit restarting. */
        private final String originWire;
        private TaskNode node;
        private Task task;
        private WhileMonitor monitor;
        private TaskNode monitorNode;
        private boolean monitorRunning;
        private int iteration;
        private int cooldown;
        private int pulseWaitTicks;
        private boolean timerStarted;
        private int timerWaitTicks;
        private int timerPulseCount;
        private boolean finished;
        private final StatusText status = new StatusText();

        private ParallelCircuit(TaskNode start, TaskNode source, TaskNode origin,
                                String originWire) {
            this.entryNode = start;
            this.source = source;
            this.origin = origin;
            this.originWire = originWire;
            this.node = start;
        }

        private boolean periodic() {
            return source != null;
        }

        /** Adds this branch's share of the picture: its source, its wire, and where it has got to. */
        private void collectPower(Set<String> nodes, Set<String> wires) {
            if (finished) {
                return;
            }
            if (node == null || node.id == null) {
                return;
            }
            nodes.add(node.id);
            addMonitorPower(nodes, wires, node, monitorNode, monitorRunning);
        }

        private TaskStatus onTick(BotContext ctx) {
            if (finished) {
                return TaskStatus.SUCCESS;
            }
            if (periodic() && node == null) {
                if (pulseWaitTicks > 0) {
                    pulseWaitTicks--;
                    status.set("lune.status.task_runner.waiting_next_signal", source.describeAlwaysInterval());
                    return TaskStatus.RUNNING;
                }
                node = entryNode;
                iteration = 0;
                recordPulse(origin, originWire);
                status.set("lune.status.task_runner.new_signal");
            }
            if (cooldown > 0) {
                cooldown--;
                tickOwnMonitor(ctx);
                status.set("lune.status.stop_game.pausing");
                return TaskStatus.RUNNING;
            }
            if (node == null) {
                finished = true;
                return TaskStatus.SUCCESS;
            }
            if (node.isEndNode()) {
                node = null;
                finishCycle();
                return TaskStatus.RUNNING;
            }
            if (node.isCounterNode()) {
                boolean output = countPulse(node);
                if (output) {
                    emitRelay(node, source, ctx);
                    status.set("lune.status.task_runner.forwarded_pulse");
                } else {
                    status.set("lune.status.task_runner.counting_incoming_pulse");
                }
                node = null;
                finishCycle();
                return TaskStatus.RUNNING;
            }
            if (node.isTimerNode()) {
                if (!timerStarted) {
                    timerStarted = true;
                    timerPulseCount = 0;
                    timerWaitTicks = timerTicks(node);
                }
                if (timerWaitTicks > 0) {
                    timerWaitTicks--;
                    status.set("lune.status.task_runner.waiting_s_before_forwarding", timerSeconds(node));
                    return TaskStatus.RUNNING;
                }
                emitRelay(node, source, ctx);
                timerPulseCount++;
                if (node.repeat == 0 || timerPulseCount < node.repeat) {
                    timerWaitTicks = timerTicks(node);
                    status.set("lune.status.task_runner.forwarded_pulse_waiting_next_one");
                    return TaskStatus.RUNNING;
                }
                node = null;
                timerStarted = false;
                finishCycle();
                return TaskStatus.RUNNING;
            }
            if (node.isSignalRelayNode()) {
                emitRelay(node, source, ctx);
                node = null;
                finishCycle();
                return TaskStatus.RUNNING;
            }
            if (task == null) {
                task = buildTask(node);
                if (task == null) {
                    status.set("lune.status.task_runner.unknown_command", node.commandId);
                    finished = true;
                    return TaskStatus.FAILED;
                }
                task.start(ctx);
                if (!prepareOwnMonitor(ctx)) {
                    task.stop(ctx);
                    task = null;
                    status.set("lune.status.task_runner.while_not_created");
                    finished = true;
                    return TaskStatus.FAILED;
                }
            }

            // A monitor used as an Always target, such as Self Preservation or Stay Near, is
            // itself a waiting circuit. It receives ticks only while its own condition is true;
            // it never pauses or replaces another circuit.
            if (task instanceof WhileMonitor guarded) {
                if (!guarded.shouldTakeControl(ctx)) {
                    if (monitorRunning) {
                        guarded.onControlReleased(ctx);
                        monitorRunning = false;
                    }
                    status.set("lune.status.stay_near.watching");
                    return finishMonitorCheck(ctx, guarded);
                }
                monitorRunning = true;
                TaskStatus result = guarded.tick(ctx);
                status.set(guarded.statusLine());
                if (result == TaskStatus.FAILED) {
                    return failMonitor(ctx, guarded);
                }
                if (result != TaskStatus.RUNNING) {
                    guarded.onControlReleased(ctx);
                    monitorRunning = false;
                    if (result == TaskStatus.SUCCESS) {
                        return finishMonitorCheck(ctx, guarded);
                    }
                }
                return TaskStatus.RUNNING;
            }

            TaskStatus result = task.tick(ctx);
            status.set(task.statusLine());
            TaskStatus monitorResult = tickOwnMonitor(ctx);
            if (monitorResult == TaskStatus.FAILED) {
                if (monitor == null) {
                    status.set("lune.status.task_runner.while_action_failed");
                } else {
                    status.set(monitor.statusLine());
                }
                return TaskStatus.FAILED;
            }
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }

            Task finishedTask = task;
            recordNodeOutputs(node);
            StatusText childStatus = new StatusText().set(task.statusLine());
            task.stop(ctx);
            task = null;

            if (result == TaskStatus.SUCCESS) {
                iteration++;
                if (node.repeat == 0) {
                    if (TaskWiring.hasSuccessDestination(graph, node)) {
                        stopOwnMonitor(ctx);
                        // Follows the Success wire, exactly as the primary lane does. Advancing
                        // by list order here is what left a pulse stuck on the first card of its
                        // branch while the wire out of it never lit.
                        advance(node.onSuccess, TaskPower.SUCCESS);
                    } else if (!finishedTask.madeProgress()) {
                        cooldown = 20;
                    }
                    return TaskStatus.RUNNING;
                }
                if (iteration < node.repeat) {
                    return TaskStatus.RUNNING;
                }
                stopOwnMonitor(ctx);
                advance(node.onSuccess, TaskPower.SUCCESS);
            } else {
                if (node.onFailure == null) {
                    stopOwnMonitor(ctx);
                    if (childStatus.isBlank()) {
                        status.set("lune.status.task_runner.failed");
                    } else {
                        status.set(childStatus);
                    }
                    finished = true;
                    return TaskStatus.FAILED;
                }
                stopOwnMonitor(ctx);
                advance(node.onFailure, TaskPower.FAILURE);
            }
            if (node == null) {
                finishCycle();
            }
            return TaskStatus.RUNNING;
        }

        private void finishCycle() {
            if (periodic()) {
                // The completion tick is the pulse that just ran. Subtract it from the next
                // delay so "every tick" really means the next scheduler tick, not every other.
                pulseWaitTicks = Math.max(0, source.alwaysIntervalTicks() - 1);
                status.set("lune.status.task_runner.waiting_next_signal", source.describeAlwaysInterval());
                return;
            }
            finished = true;
        }

        /** A finite monitor is a successful check; x∞ deliberately keeps watching forever. */
        private TaskStatus finishMonitorCheck(BotContext ctx, WhileMonitor guarded) {
            if (node.repeat == 0) {
                return TaskStatus.RUNNING;
            }
            iteration++;
            if (iteration < node.repeat) {
                return TaskStatus.RUNNING;
            }
            guarded.stop(ctx);
            task = null;
            stopOwnMonitor(ctx);
            advance(node.onSuccess, TaskPower.SUCCESS);
            if (node == null) {
                finishCycle();
                return periodic() ? TaskStatus.RUNNING : TaskStatus.SUCCESS;
            }
            return TaskStatus.RUNNING;
        }

        /** A failed monitor follows its Fail edge when one exists, just like every other node. */
        private TaskStatus failMonitor(BotContext ctx, WhileMonitor guarded) {
            StatusText failure = new StatusText().set(guarded.statusLine());
            guarded.onControlReleased(ctx);
            guarded.stop(ctx);
            task = null;
            stopOwnMonitor(ctx);
            if (node.onFailure != null) {
                advance(node.onFailure, TaskPower.FAILURE);
                return TaskStatus.RUNNING;
            }
            if (failure.isBlank()) {
                status.set("lune.status.task_runner.failed");
            } else {
                status.set(failure);
            }
            finished = true;
            return TaskStatus.FAILED;
        }

        private boolean prepareOwnMonitor(BotContext ctx) {
            if (node.onWhile == null) {
                return true;
            }
            TaskNode linked = graph.nodeById(node.onWhile);
            if (linked == null) {
                return false;
            }
            monitor = asWhileMonitor(buildTask(linked));
            if (monitor == null) {
                return false;
            }
            monitorNode = linked;
            monitorRunning = false;
            monitor.start(ctx);
            return true;
        }

        private TaskStatus tickOwnMonitor(BotContext ctx) {
            if (monitor == null) {
                return null;
            }
            if (!monitor.shouldTakeControl(ctx)) {
                if (monitorRunning) {
                    monitor.onControlReleased(ctx);
                    monitorRunning = false;
                }
                return null;
            }
            monitorRunning = true;
            TaskStatus result = monitor.tick(ctx);
            if (result == TaskStatus.FAILED) {
                return TaskStatus.FAILED;
            }
            if (result != TaskStatus.RUNNING) {
                monitor.onControlReleased(ctx);
                monitorRunning = false;
            }
            return null;
        }

        private void stopOwnMonitor(BotContext ctx) {
            if (monitor != null) {
                monitor.stop(ctx);
            }
            monitor = null;
            monitorNode = null;
            monitorRunning = false;
        }

        private void advance() {
            advance(null, TaskPower.SUCCESS);
        }

        private void advance(String targetId, int kind) {
            iteration = 0;
            timerStarted = false;
            timerWaitTicks = 0;
            timerPulseCount = 0;
            TaskNode from = node;
            if (targetId != null) {
                node = graph.nodeById(targetId);
            } else {
                int index = graph.indexOf(node);
                node = index < 0 ? null : nextSequentialNode(index);
            }
            wireFrom(from, targetId, kind, node);
        }

        private void onStop(BotContext ctx) {
            if (task != null) {
                task.stop(ctx);
                task = null;
            }
            stopOwnMonitor(ctx);
        }
    }

    /** A source-side clock. Its pulses are deliberately independent of target completion. */
    private final class AlwaysPulseSource {
        private final TaskNode source;
        private final TaskNode target;
        private int waitTicks;

        private AlwaysPulseSource(TaskNode source, TaskNode target) {
            this.source = source;
            this.target = target;
        }

        private void tick(BotContext ctx) {
            if (waitTicks > 0) {
                waitTicks--;
                return;
            }
            // This pulse is a one-shot circuit. A running target is not reused or ranked above it;
            // the next pulse gets a new circuit, exactly like a separate electrical branch.
            queueParallelCircuit(ctx, target, null, source,
                    TaskPower.wire(source.id, TaskPower.ALWAYS, 0, target.id));
            waitTicks = Math.max(0, source.alwaysIntervalTicks() - 1);
        }
    }

    /**
     * Watches one card and pulses whenever its power changes.
     *
     * <p>An Observer is not wired into the flow: nothing travels down the wire on its left. That
     * wire only says <em>which card to look at</em>. When that card lights up, and again when it
     * goes dark, the Observer sends one pulse of its own to the right.</p>
     *
     * <p>The first sample only establishes a baseline. Without that, every Observer would fire once
     * the moment the task started, purely because it had nothing to compare against yet.</p>
     */
    private final class ObserverPulseSource {
        private final TaskNode source;
        private boolean initialized;
        private boolean wasLive;

        private ObserverPulseSource(TaskNode source) {
            this.source = source;
        }

        private void tick(BotContext ctx) {
            TaskNode watched = source.observedNodeId == null
                    ? null : graph.nodeById(source.observedNodeId);
            if (watched == null) {
                return;
            }
            boolean live = powerSample.isLive(watched);
            if (!initialized) {
                initialized = true;
                wasLive = live;
                return;
            }
            if (live == wasLive) {
                return;
            }
            wasLive = live;
            emitRelay(source, null, ctx);
        }
    }

    /** A manually triggered source. Pressing the editor control queues one pulse. */
    private final class ButtonPulseSource {
        private final TaskNode source;

        private ButtonPulseSource(TaskNode source) {
            this.source = source;
        }

        private void tick(BotContext ctx) {
            if (consumeButtonPress(source)) {
                emitRelay(source, null, ctx);
            }
        }
    }

    /**
     * While edges may point at any command. Dedicated monitors can decide when to take control;
     * ordinary commands are wrapped so they can run as an independent circuit beside the guarded
     * task step.
     */
    private WhileMonitor asWhileMonitor(Task built) {
        if (built == null) {
            return null;
        }
        return built instanceof WhileMonitor monitor ? monitor : new TaskWhileMonitor(built);
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

    /** Resolves all incoming data wires before the command factory reads its parameters. */
    private Map<String, String> resolveParams(TaskNode current) {
        Map<String, String> resolved = new LinkedHashMap<>(current.params);
        if (current.inputLinks == null) {
            return resolved;
        }
        for (Map.Entry<String, TaskDataLink> entry : current.inputLinks.entrySet()) {
            TaskDataLink link = entry.getValue();
            if (link == null || link.sourceNodeId == null || link.sourcePort == null) {
                continue;
            }
            Map<String, String> sourceValues = nodeOutputs.get(link.sourceNodeId);
            if (sourceValues != null && sourceValues.get(link.sourcePort) != null) {
                resolved.put(entry.getKey(), sourceValues.get(link.sourcePort));
            }
        }
        return resolved;
    }

    /** Publishes only parameters whose right-side output box the user enabled. */
    private void recordNodeOutputs(TaskNode current) {
        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, String> resolved = resolveParams(current);
        if (current.exposedOutputs != null) {
            for (String parameterId : current.exposedOutputs) {
                String value = resolved.get(parameterId);
                if (value != null) {
                    outputs.put(parameterId, value);
                }
            }
        }
        nodeOutputs.put(current.id, outputs);
    }

    private StatusText describeStep() {
        int step = graph.indexOf(node) + 1;
        StatusText detail = task == null ? null : task.statusLine();
        return detail == null || detail.isBlank()
                ? new StatusText().set("lune.status.task_runner.step", step,
                        graph.nodes.size(), nodeName(node))
                : new StatusText().set("lune.status.task_runner.step_detail", step,
                        graph.nodes.size(), nodeName(node), detail);
    }

    private static String nodeName(TaskNode node) {
        if (node == null) {
            return "-";
        }
        CommandDef def = CommandRegistry.byId(node.commandId);
        return def == null ? node.commandId : def.name();
    }

    private void markFailed() {
        lastFailedTask = graph;
        lastFailedNodeId = node != null ? node.id : null;
    }

    public static boolean isLastFailed(TaskGraph r, TaskNode n) {
        return r == lastFailedTask && n != null && n.id.equals(lastFailedNodeId);
    }
}
