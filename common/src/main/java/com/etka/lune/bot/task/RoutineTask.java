package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.WhileMonitor;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.routine.Routine;
import com.etka.lune.routine.RoutineDataLink;
import com.etka.lune.routine.RoutineNode;
import com.etka.lune.routine.RoutineGraph;
import com.etka.lune.routine.RoutineSignalLink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

/**
 * Runs a {@link Routine} as a set of independently powered circuits. START sends one signal into
 * the primary circuit, while Always periodically sends a new signal into each of its independent
 * target circuits beside it.
 * <p>
 * Each circuit builds a real task and is ticked normally; when it finishes, that circuit's SUCCESS
 * or FAILED edge decides what runs next. Nodes with no edge fall through to the next in the list,
 * which makes the common "do these in order" circuit need no wiring at all, while an edge pointing
 * backwards gives a loop. Circuits share the player's input and world, but no circuit is selected
 * over another by the routine runner.
 * <p>
 * At most one node transition happens per circuit per client tick, so even a cycle of
 * instantly-failing nodes costs one step per circuit tick rather than spinning.
 */
public final class RoutineTask implements Task {

    private final Routine routine;

    /** Static so the blueprint view can still colour the failed card after the routine stops. */
    private static Routine lastFailedRoutine;
    private static String lastFailedNodeId;

    private RoutineNode node;
    private Task task;
    private WhileMonitor monitor;
    private RoutineNode monitorNode;
    private boolean monitorRunning;
    /** Independent circuits powered by Always (or the legacy routine-level While source). */
    private final List<ParallelCircuit> parallelCircuits = new ArrayList<>();
    /** Signals emitted while a circuit is being ticked; applied after the iterator finishes. */
    private final List<ParallelCircuit> pendingParallelCircuits = new ArrayList<>();
    /** Independent clocks owned by Always sources; each pulse creates its own circuit. */
    private final List<AlwaysPulseSource> alwaysPulseSources = new ArrayList<>();
    private final List<ObserverPulseSource> observerPulseSources = new ArrayList<>();
    private final List<ButtonPulseSource> buttonPulseSources = new ArrayList<>();
    private final Map<String, Integer> counterCounts = new HashMap<>();
    private static final Set<RoutineNode> PRESSED_BUTTONS = ConcurrentHashMap.newKeySet();
    private int iteration;
    private String status = "";
    private String startupError = "";
    /** Delay state for a Timer used by the primary START circuit. */
    private boolean timerStarted;
    private int timerWaitTicks;
    private int timerPulseCount;
    /** Ticks to wait before restarting a forever step that produced no progress. */
    private int cooldown;
    /** Last completed values from each node, used by downstream exposed parameters. */
    private final Map<String, Map<String, String>> nodeOutputs = new HashMap<>();

    public RoutineTask(Routine routine) {
        this.routine = routine;
    }

    @Override
    public String name() {
        return "Routine: " + routine.name;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return task == null ? null : task.progress();
    }

    public Routine currentRoutine() {
        return routine;
    }

    public RoutineNode currentNode() {
        return node;
    }

    /**
     * Node currently receiving control. While self-preservation has interrupted a normal node,
     * this is the monitor node rather than the paused work node.
     */
    @Override
    public boolean automaticSkillLearning() {
        // A routine is a route/container. Every child task is instrumented independently.
        return false;
    }

    public RoutineNode activeNode() {
        return node;
    }

    @Override
    public boolean canRecoverFromLowFood(com.etka.lune.bot.BotContext ctx) {
        return task != null && task.canRecoverFromLowFood(ctx);
    }

    /** A short "prev -> [current] -> next" line for the status panel. */
    public String describeFlow() {
        if (routine == null || node == null) {
            return "no active routine";
        }
        int index = routine.indexOf(node);
        if (index < 0) {
            return nodeName(node);
        }
        StringBuilder sb = new StringBuilder();
        if (index > 0) {
            sb.append(nodeName(routine.nodes.get(index - 1))).append(" -> ");
        }
        sb.append("[").append(nodeName(node)).append("]");
        if (index + 1 < routine.nodes.size()) {
            sb.append(" -> ").append(nodeName(routine.nodes.get(index + 1)));
        }
        return sb.toString();
    }

    @Override
    public void onStart(BotContext ctx) {
        startupError = "";
        RoutineNode explicitStart = RoutineGraph.explicitStart(routine);
        if (explicitStart != null) {
            long startCount = routine.nodes.stream()
                    .filter(candidate -> candidate != null && candidate.isStartNode())
                    .count();
            RoutineNode target = explicitStart.onSuccess == null
                    ? null : routine.nodeById(explicitStart.onSuccess);
            if (startCount > 1) {
                startupError = "routine has more than one START node";
            } else if (target == null) {
                startupError = "START has no action connected";
            } else if (target.isSourceNode()) {
                startupError = "START must connect directly to a runnable action";
            }
            node = startupError.isBlank() ? target : explicitStart;
        } else {
            node = nextSequentialNode(-1);
        }
        iteration = 0;
        timerStarted = false;
        timerWaitTicks = 0;
        timerPulseCount = 0;
        counterCounts.clear();
        lastFailedRoutine = null;
        lastFailedNodeId = null;
        nodeOutputs.clear();
        prepareParallelCircuits(ctx);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (!startupError.isBlank()) {
            status = startupError;
            markFailed();
            return TaskStatus.FAILED;
        }
        tickAlwaysSources(ctx);
        if (node == null) {
            TaskStatus parallelResult = tickParallelCircuits(ctx);
            if (parallelResult == TaskStatus.FAILED) {
                return TaskStatus.FAILED;
            }
            status = parallelCircuits.isEmpty() ? "finished" : "running independent circuits";
            return parallelCircuits.isEmpty() ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
        }

        if (node.isTimerNode()) {
            if (!timerStarted) {
                timerStarted = true;
                timerPulseCount = 0;
                timerWaitTicks = timerTicks(node);
            }
            if (timerWaitTicks > 0) {
                timerWaitTicks--;
                status = "Timer waiting (" + timerSeconds(node) + "s)";
                return tickParallelOrRunning(ctx);
            }
            emitRelay(node, null, ctx);
            timerPulseCount++;
            if (node.repeat == 0 || timerPulseCount < node.repeat) {
                timerWaitTicks = timerTicks(node);
                status = "Timer forwarded pulse; waiting for the next one";
                return tickParallelOrRunning(ctx);
            }
            node = null;
            iteration = 0;
            timerStarted = false;
            status = "Timer forwarded pulse";
            return tickParallelOrFinished(ctx);
        }

        if (node.isCounterNode()) {
            boolean output = countPulse(node);
            if (output) {
                emitRelay(node, null, ctx);
                status = "Counter forwarded a pulse";
            } else {
                status = "Counter waiting for " + counterTarget(node) + " pulses";
            }
            node = null;
            return tickParallelOrFinished(ctx);
        }

        if (node.isEndNode()) {
            node = null;
            iteration = 0;
            status = "End consumed pulse";
            return tickParallelOrFinished(ctx);
        }

        if (node.isSignalRelayNode()) {
            emitRelay(node, null, ctx);
            node = null;
            iteration = 0;
            status = "relay sent pulse";
            TaskStatus parallelResult = tickParallelCircuits(ctx);
            if (parallelResult == TaskStatus.FAILED) {
                return TaskStatus.FAILED;
            }
            return parallelCircuits.isEmpty() ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
        }

        if (cooldown > 0) {
            cooldown--;
            TaskStatus monitorResult = tickLocalMonitor(ctx);
            if (monitorResult == TaskStatus.FAILED) {
                return failOrBranch(ctx, "While action failed: " + monitor.status());
            }
            TaskStatus parallelResult = tickParallelCircuits(ctx);
            if (parallelResult == TaskStatus.FAILED) {
                return TaskStatus.FAILED;
            }
            status = describeStep() + " - pausing";
            return TaskStatus.RUNNING;
        }

        if (task == null) {
            task = buildTask(node);
            if (task == null) {
                status = "unknown command '" + node.commandId + "'";
                markFailed();
                return TaskStatus.FAILED;
            }
            task.start(ctx);
            if (!prepareMonitor(ctx)) {
                task.stop(ctx);
                task = null;
                return failOrBranch(ctx, "While action could not be created");
            }
        }

        TaskStatus result = task.tick(ctx);
        status = describeStep();
        TaskStatus monitorResult = tickLocalMonitor(ctx);
        if (monitorResult == TaskStatus.FAILED) {
            return failOrBranch(ctx, "While action failed: " + monitor.status());
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
        String childStatus = task.status();
        task.stop(ctx);
        task = null;

        if (result == TaskStatus.SUCCESS) {
            iteration++;
            if (node.repeat == 0) {
                return handleForeverSuccess(ctx, finished);
            }
            if (iteration < node.repeat) {
                return TaskStatus.RUNNING;
            }
            stopMonitor(ctx);
            advance(node.onSuccess);
        } else {
            if (node.onFailure == null) {
                stopMonitor(ctx);
                status = "step '" + node.commandId + "' failed"
                        + (childStatus == null || childStatus.isBlank() ? "" : ": " + childStatus);
                markFailed();
                return TaskStatus.FAILED;
            }
            stopMonitor(ctx);
            advance(node.onFailure);
        }

        TaskStatus parallelResult = tickParallelCircuits(ctx);
        if (parallelResult == TaskStatus.FAILED) {
            return TaskStatus.FAILED;
        }
        if (node == null && !parallelCircuits.isEmpty()) {
            return TaskStatus.RUNNING;
        }
        return node == null ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }

    private TaskStatus handleForeverSuccess(BotContext ctx, Task finished) {
        boolean progressed = finished.madeProgress();
        boolean last = isLastNode();

        if (progressed && !last) {
            // Step did work and there is more to do, so move on (e.g. Find found something -> Mine).
            stopMonitor(ctx);
            advance(node.onSuccess);
            return node == null ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
        }

        if (!progressed && !last) {
            // No work done, but there is a later step that may handle it (e.g. Find found 0 -> Mine).
            stopMonitor(ctx);
            advance(node.onSuccess);
            return node == null ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
        }

        if (progressed && last) {
            // Last step is producing; keep it running immediately.
            return TaskStatus.RUNNING;
        }

        // Last step, no progress: cool down so a 'forever' routine doesn't spin tightly.
        cooldown = 20;
        return TaskStatus.RUNNING;
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
        return parallelCircuits.isEmpty() ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }

    private boolean isLastNode() {
        int index = routine.indexOf(node);
        return index < 0 || nextSequentialNode(index) == null;
    }

    @Override
    public void onStop(BotContext ctx) {
        if (task != null) {
            task.stop(ctx);
            task = null;
        }
        stopMonitor(ctx);
        stopParallelCircuits(ctx);
    }

    /** Follows an explicit edge, or falls through to the next node in the list when there is none. */
    private void advance(String targetId) {
        iteration = 0;
        if (targetId != null) {
            node = routine.nodeById(targetId);
            return;
        }
        int index = routine.indexOf(node);
        node = index < 0 ? null : nextSequentialNode(index);
    }

    private RoutineNode nextSequentialNode(int afterIndex) {
        return RoutineGraph.nextSequentialNode(routine, afterIndex);
    }

    private boolean prepareMonitor(BotContext ctx) {
        if (node.onWhile == null) {
            return true;
        }
        if (monitor != null && monitorNode != null && node.onWhile.equals(monitorNode.id)) {
            return true;
        }
        RoutineNode linked = routine.nodeById(node.onWhile);
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
        if (routine.onWhile != null) {
            addParallelCircuit(ctx, routine.nodeById(routine.onWhile), null);
        }
        for (RoutineNode source : routine.nodes) {
            if (source.isAlwaysNode() && source.alwaysTargets != null) {
                for (String targetId : source.alwaysTargets) {
                    addAlwaysPulseSource(source, routine.nodeById(targetId));
                }
            }
            if (source.isObserverNode()) {
                observerPulseSources.add(new ObserverPulseSource(source));
            }
            if (source.isButtonNode()) {
                buttonPulseSources.add(new ButtonPulseSource(source));
            }
        }
    }

    private void addAlwaysPulseSource(RoutineNode source, RoutineNode target) {
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

    private void addParallelCircuit(BotContext ctx, RoutineNode linked, RoutineNode source) {
        if (linked == null || linked.isSourceNode()) {
            return;
        }
        parallelCircuits.add(new ParallelCircuit(linked, source));
    }

    private void queueParallelCircuit(BotContext ctx, RoutineNode linked, RoutineNode source) {
        if (linked == null || linked.isSourceNode()) {
            return;
        }
        // A fresh electrical pulse gets a fresh circuit. Only the legacy periodic-circuit path
        // carries a non-null source and uses this guard; Always and pulse-node links intentionally
        // remain independent even when an earlier pulse is still running.
        if (source != null && (hasCircuit(linked, source, parallelCircuits)
                || hasCircuit(linked, source, pendingParallelCircuits))) {
            return;
        }
        pendingParallelCircuits.add(new ParallelCircuit(linked, source));
    }

    private boolean hasCircuit(RoutineNode entry, RoutineNode source, List<ParallelCircuit> circuits) {
        return circuits.stream().anyMatch(circuit -> circuit.entryNode == entry
                && circuit.source == source && !circuit.finished);
    }

    private void emitRelay(RoutineNode relay, RoutineNode pulseSource, BotContext ctx) {
        if (relay == null || relay.signalLinks == null) {
            return;
        }
        for (RoutineSignalLink link : relay.signalLinks) {
            if (link == null || link.outputPort < 0 || link.outputPort >= relay.signalOutputCount) {
                continue;
            }
            RoutineNode target = routine.nodeById(link.targetNodeId);
            if (target == null || target.isSourceNode()) {
                continue;
            }
            queueParallelCircuit(ctx, target, pulseSource);
        }
    }

    /** Queues one pulse from the editor's Button control for the next routine tick. */
    public static void pressButton(RoutineNode button) {
        if (button != null && button.isButtonNode()) {
            PRESSED_BUTTONS.add(button);
        }
    }

    private static boolean consumeButtonPress(RoutineNode button) {
        return PRESSED_BUTTONS.remove(button);
    }

    private int counterTarget(RoutineNode counter) {
        if (counter == null || counter.params == null) {
            return 3;
        }
        try {
            return Math.clamp(Integer.parseInt(counter.params.getOrDefault("count", "3")), 1, 1_000_000);
        } catch (NumberFormatException ignored) {
            return 3;
        }
    }

    private boolean countPulse(RoutineNode counter) {
        int count = counterCounts.getOrDefault(counter.id, 0) + 1;
        if (count >= counterTarget(counter)) {
            counterCounts.remove(counter.id);
            return true;
        }
        counterCounts.put(counter.id, count);
        return false;
    }

    private int timerSeconds(RoutineNode timer) {
        if (timer == null || timer.params == null) {
            return 5;
        }
        try {
            return Math.clamp(Integer.parseInt(timer.params.getOrDefault("seconds", "5")), 0, 3600);
        } catch (NumberFormatException ignored) {
            return 5;
        }
    }

    private int timerTicks(RoutineNode timer) {
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
                status = "circuit '" + nodeName(circuit.node) + "' failed"
                        + (circuit.status.isBlank() ? "" : ": " + circuit.status);
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
            status = "circuit: " + lastStatus;
        }
        if (!pendingParallelCircuits.isEmpty()) {
            parallelCircuits.addAll(pendingParallelCircuits);
            pendingParallelCircuits.clear();
        }
        return null;
    }

    private TaskStatus failOrBranch(BotContext ctx, String reason) {
        if (task != null) {
            task.stop(ctx);
            task = null;
        }
        stopMonitor(ctx);
        if (node.onFailure == null) {
            status = reason;
            markFailed();
            return TaskStatus.FAILED;
        }
        advance(node.onFailure);
        status = reason;
        return node == null ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
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

    private Task buildTask(RoutineNode current) {
        CommandDef def = CommandRegistry.byId(current.commandId);
        if (def == null) {
            return null;
        }
        return def.buildWith(resolveParams(current));
    }

    /** One independent flow powered by a single electrical pulse. */
    private final class ParallelCircuit {
        private final RoutineNode entryNode;
        private final RoutineNode source;
        private RoutineNode node;
        private Task task;
        private WhileMonitor monitor;
        private RoutineNode monitorNode;
        private boolean monitorRunning;
        private int iteration;
        private int cooldown;
        private int pulseWaitTicks;
        private boolean timerStarted;
        private int timerWaitTicks;
        private int timerPulseCount;
        private boolean finished;
        private String status = "";

        private ParallelCircuit(RoutineNode start, RoutineNode source) {
            this.entryNode = start;
            this.source = source;
            this.node = start;
        }

        private boolean periodic() {
            return source != null;
        }

        private TaskStatus onTick(BotContext ctx) {
            if (finished) {
                return TaskStatus.SUCCESS;
            }
            if (periodic() && node == null) {
                if (pulseWaitTicks > 0) {
                    pulseWaitTicks--;
                    status = "waiting for next signal (" + source.describeAlwaysInterval() + ")";
                    return TaskStatus.RUNNING;
                }
                node = entryNode;
                iteration = 0;
                status = "new signal";
            }
            if (cooldown > 0) {
                cooldown--;
                tickOwnMonitor(ctx);
                status = "pausing";
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
                    status = "forwarded pulse";
                } else {
                    status = "counting incoming pulse";
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
                    status = "waiting " + timerSeconds(node) + "s before forwarding";
                    return TaskStatus.RUNNING;
                }
                emitRelay(node, source, ctx);
                timerPulseCount++;
                if (node.repeat == 0 || timerPulseCount < node.repeat) {
                    timerWaitTicks = timerTicks(node);
                    status = "forwarded pulse; waiting for the next one";
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
                    status = "unknown command '" + node.commandId + "'";
                    finished = true;
                    return TaskStatus.FAILED;
                }
                task.start(ctx);
                if (!prepareOwnMonitor(ctx)) {
                    task.stop(ctx);
                    task = null;
                    status = "While action could not be created";
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
                    status = "watching";
                    return finishMonitorCheck(ctx, guarded);
                }
                monitorRunning = true;
                TaskStatus result = guarded.tick(ctx);
                status = guarded.status();
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
            status = task.status();
            TaskStatus monitorResult = tickOwnMonitor(ctx);
            if (monitorResult == TaskStatus.FAILED) {
                status = monitor == null ? "While action failed" : monitor.status();
                return TaskStatus.FAILED;
            }
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }

            Task finishedTask = task;
            recordNodeOutputs(node);
            String childStatus = task.status();
            task.stop(ctx);
            task = null;

            if (result == TaskStatus.SUCCESS) {
                iteration++;
                if (node.repeat == 0) {
                    boolean hasNext = node.onSuccess != null
                            || nextSequentialNode(routine.indexOf(node)) != null;
                    if (hasNext) {
                        stopOwnMonitor(ctx);
                        advance();
                    } else if (!finishedTask.madeProgress()) {
                        cooldown = 20;
                    }
                    return TaskStatus.RUNNING;
                }
                if (iteration < node.repeat) {
                    return TaskStatus.RUNNING;
                }
                stopOwnMonitor(ctx);
                advance(node.onSuccess);
            } else {
                if (node.onFailure == null) {
                    stopOwnMonitor(ctx);
                    status = childStatus == null || childStatus.isBlank()
                            ? "failed" : childStatus;
                    finished = true;
                    return TaskStatus.FAILED;
                }
                stopOwnMonitor(ctx);
                advance(node.onFailure);
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
                status = "waiting for next signal (" + source.describeAlwaysInterval() + ")";
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
            advance(node.onSuccess);
            if (node == null) {
                finishCycle();
                return periodic() ? TaskStatus.RUNNING : TaskStatus.SUCCESS;
            }
            return TaskStatus.RUNNING;
        }

        /** A failed monitor follows its Fail edge when one exists, just like every other node. */
        private TaskStatus failMonitor(BotContext ctx, WhileMonitor guarded) {
            String failure = guarded.status();
            guarded.onControlReleased(ctx);
            guarded.stop(ctx);
            task = null;
            stopOwnMonitor(ctx);
            if (node.onFailure != null) {
                advance(node.onFailure);
                return TaskStatus.RUNNING;
            }
            status = failure == null || failure.isBlank() ? "failed" : failure;
            finished = true;
            return TaskStatus.FAILED;
        }

        private boolean prepareOwnMonitor(BotContext ctx) {
            if (node.onWhile == null) {
                return true;
            }
            RoutineNode linked = routine.nodeById(node.onWhile);
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
            advance(null);
        }

        private void advance(String targetId) {
            iteration = 0;
            timerStarted = false;
            timerWaitTicks = 0;
            timerPulseCount = 0;
            if (targetId != null) {
                node = routine.nodeById(targetId);
                return;
            }
            int index = routine.indexOf(node);
            node = index < 0 ? null : nextSequentialNode(index);
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
        private final RoutineNode source;
        private final RoutineNode target;
        private int waitTicks;

        private AlwaysPulseSource(RoutineNode source, RoutineNode target) {
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
            queueParallelCircuit(ctx, target, null);
            waitTicks = Math.max(0, source.alwaysIntervalTicks() - 1);
        }
    }

    /** An event-driven source. The first sample establishes a baseline and never fires by itself. */
    private final class ObserverPulseSource {
        private final RoutineNode source;
        private final String watch;
        private final int threshold;
        private final Item item;
        private final Set<EntityType<?>> entities;
        private final int radius;
        private boolean initialized;
        private float previousHealth;
        private int previousHunger;
        private int previousAir;
        private int previousItemCount;
        private Set<Integer> previousMobs = Set.of();

        private ObserverPulseSource(RoutineNode source) {
            this.source = source;
            CommandDef def = CommandRegistry.byId(RoutineNode.OBSERVER_COMMAND);
            Map<String, String> saved = def == null ? Map.of() : def.snapshot();
            try {
                if (def != null) {
                    def.apply(source.params == null ? Map.of() : source.params);
                    watch = def.choiceValue("watch");
                    threshold = def.intValue("threshold");
                    item = def.itemValue("item");
                    entities = def.entityValue("entities");
                    radius = def.intValue("radius");
                } else {
                    watch = "Health drops";
                    threshold = 4;
                    item = null;
                    entities = Set.of();
                    radius = 16;
                }
            } finally {
                if (def != null) {
                    def.apply(saved);
                }
            }
        }

        private void tick(BotContext ctx) {
            float health = ctx.player.getHealth();
            int hunger = ctx.player.getFoodData().getFoodLevel();
            int air = ctx.player.getAirSupply();
            int itemCount = item == null ? 0 : InventoryHelper.count(ctx.player, item);
            Set<Integer> mobs = mobIds(ctx);
            if (!initialized) {
                initialized = true;
                previousHealth = health;
                previousHunger = hunger;
                previousAir = air;
                previousItemCount = itemCount;
                previousMobs = mobs;
                return;
            }

            boolean event = switch (watch) {
                case "Health drops" -> health < previousHealth - 0.001F;
                case "Health crosses below" -> previousHealth >= threshold && health < threshold;
                case "Health changes" -> Math.abs(health - previousHealth) > 0.001F;
                case "Hunger changes" -> hunger != previousHunger;
                case "Air changes" -> air != previousAir;
                case "Item count changes" -> itemCount != previousItemCount;
                case "Mob enters range" -> mobs.stream().anyMatch(id -> !previousMobs.contains(id));
                default -> false;
            };
            previousHealth = health;
            previousHunger = hunger;
            previousAir = air;
            previousItemCount = itemCount;
            previousMobs = mobs;
            if (event) {
                emitRelay(source, null, ctx);
            }
        }

        private Set<Integer> mobIds(BotContext ctx) {
            if (entities == null || entities.isEmpty()) {
                return Set.of();
            }
            AABB area = ctx.player.getBoundingBox().inflate(radius);
            Set<Integer> ids = new HashSet<>();
            for (var entity : ctx.level.getEntities(ctx.player, area,
                    candidate -> candidate.isAlive() && entities.contains(candidate.getType()))) {
                ids.add(entity.getId());
            }
            return ids;
        }
    }

    /** A manually triggered source. Pressing the editor control queues one pulse. */
    private final class ButtonPulseSource {
        private final RoutineNode source;

        private ButtonPulseSource(RoutineNode source) {
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
     * routine step.
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
    private Map<String, String> resolveParams(RoutineNode current) {
        Map<String, String> resolved = new LinkedHashMap<>(current.params);
        if (current.inputLinks == null) {
            return resolved;
        }
        for (Map.Entry<String, RoutineDataLink> entry : current.inputLinks.entrySet()) {
            RoutineDataLink link = entry.getValue();
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
    private void recordNodeOutputs(RoutineNode current) {
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

    private String describeStep() {
        int step = routine.indexOf(node) + 1;
        String detail = task == null ? "" : task.status();
        return "step " + step + "/" + routine.nodes.size() + " " + nodeName(node)
                + (detail.isEmpty() ? "" : " - " + detail);
    }

    private static String nodeName(RoutineNode node) {
        if (node == null) {
            return "-";
        }
        CommandDef def = CommandRegistry.byId(node.commandId);
        return def == null ? node.commandId : def.name();
    }

    private void markFailed() {
        lastFailedRoutine = routine;
        lastFailedNodeId = node != null ? node.id : null;
    }

    public static boolean isLastFailed(Routine r, RoutineNode n) {
        return r == lastFailedRoutine && n != null && n.id.equals(lastFailedNodeId);
    }
}
