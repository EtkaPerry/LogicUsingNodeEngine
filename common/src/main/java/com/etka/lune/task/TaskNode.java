package com.etka.lune.task;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One node in a task's flow graph: a command to run, and where to go depending on how it ends.
 * <p>
 * Every {@link com.etka.lune.bot.Task} already reports SUCCESS or FAILED, so a task needs no
 * separate condition language - the branch <em>is</em> the task's outcome. A loop is simply an edge
 * pointing back at an earlier node.
 * <p>
 * Plain mutable fields with a no-arg constructor, because these are serialised straight to JSON for
 * saving and for share/import.
 */
public final class TaskNode {

    /** Editor-only source node that periodically pulses one or more independent command circuits. */
    public static final String ALWAYS_COMMAND = "always";

    /** Editor-only entry source that makes the first runnable node explicit. */
    public static final String START_COMMAND = "start";

    /** A neutral execution-pulse junction with configurable numbered inputs and outputs. */
    public static final String SIGNAL_RELAY_COMMAND = "signal_relay";

    /** A one-input/one-output pulse delay. */
    public static final String TIMER_COMMAND = "timer";

    /** A one-input pulse sink that deliberately ends a circuit. */
    public static final String END_COMMAND = "end";

    /** A one-input/one-output pulse counter. */
    public static final String COUNTER_COMMAND = "counter";

    /**
     * A clock: a source that emits one pulse every N seconds.
     *
     * <p>Split out from Always because they are different jobs wearing one name. Always is a power
     * source held on - it energises its branch and keeps it energised. Pulse is a metronome, and
     * "every ten seconds" is the whole reason to reach for it, so it says so on the card instead of
     * hiding behind a property of something called Always.</p>
     */
    public static final String PULSE_COMMAND = "pulse";

    /** An independent event-driven pulse source. */
    public static final String OBSERVER_COMMAND = "observer";

    /** A manually pressed pulse source. */
    public static final String BUTTON_COMMAND = "button";

    /** Stable within a task; edges refer to nodes by this. */
    public String id = UUID.randomUUID().toString().substring(0, 8);

    /** A {@link com.etka.lune.bot.command.CommandRegistry} command id, e.g. {@code "mine"}. */
    public String commandId = "";

    /** Parameter id to serialised value. Missing entries fall back to the command's defaults. */
    public Map<String, String> params = new LinkedHashMap<>();

    /** Parameter ids that are visible as data input pins on the left side of this node. */
    public Set<String> exposedInputs = new LinkedHashSet<>();

    /** Parameter ids that are visible as data output pins on the right side of this node. */
    public Set<String> exposedOutputs = new LinkedHashSet<>();

    /** Data wires keyed by the destination parameter id. */
    public Map<String, TaskDataLink> inputLinks = new LinkedHashMap<>();

    /** How many times to run this node before moving on. 0 means forever; While companions stay
     * live for their main node instead of using this as their lifetime. */
    public int repeat = 1;

    /** Node id to run after success. Null falls through to the next node in the list. */
    public String onSuccess;

    /** Node id to run after failure. Null stops the task. */
    public String onFailure;

    /** Optional companion circuit ticked while this node is active, e.g. Self Preservation. */
    public String onWhile;

    /** Whether the editor displays this node's optional While output. Execution is unaffected. */
    public boolean whileVisible;

    /** Target node ids for the special Always source node; one pulse may fan out to many. */
    public Set<String> alwaysTargets = new LinkedHashSet<>();

    /**
     * The card an Observer watches, wired into its left pin.
     *
     * <p>Not an execution edge: no signal travels along it. The Observer only reads whether that
     * card is carrying power, and sends its own pulse when that changes.</p>
     */
    public String observedNodeId;

    /** Numbered execution ports shown on a Signal Relay. */
    public static final int DEFAULT_SIGNAL_PORTS = 1;
    public static final int MIN_SIGNAL_PORTS = 1;
    public static final int MAX_SIGNAL_PORTS = 16;
    public int signalInputCount = DEFAULT_SIGNAL_PORTS;
    public int signalOutputCount = DEFAULT_SIGNAL_PORTS;

    /** Pulse wires leaving a Signal Relay. */
    public List<TaskSignalLink> signalLinks = new ArrayList<>();

    /** Input port used when a normal execution edge lands on a Signal Relay. */
    public int successInputPort;
    public int failureInputPort;
    public int whileInputPort;

    /** Input port used by an Always fan-out edge when it lands on a Signal Relay. */
    public Map<String, Integer> alwaysTargetInputPorts = new LinkedHashMap<>();

    /** Seconds between pulses from an Always source. Zero means pulse continuously. */
    public static final int DEFAULT_ALWAYS_INTERVAL_SECONDS = 0;
    public static final int MIN_ALWAYS_INTERVAL_SECONDS = 0;
    public static final int MAX_ALWAYS_INTERVAL_SECONDS = 3600;
    public int alwaysIntervalSeconds = DEFAULT_ALWAYS_INTERVAL_SECONDS;

    public int alwaysIntervalTicks() {
        int seconds = Math.clamp(alwaysIntervalSeconds, MIN_ALWAYS_INTERVAL_SECONDS,
                MAX_ALWAYS_INTERVAL_SECONDS);
        return seconds == 0 ? 1 : seconds * 20;
    }

    public String describeAlwaysInterval() {
        int seconds = Math.clamp(alwaysIntervalSeconds, MIN_ALWAYS_INTERVAL_SECONDS,
                MAX_ALWAYS_INTERVAL_SECONDS);
        return seconds == 0 ? "continuously" : seconds == 1
                ? "every 1 second" : "every " + seconds + " seconds";
    }

    /** A Pulse with no rate set yet is a Pulse that does nothing, so it starts at a usable one. */
    public static final int DEFAULT_PULSE_INTERVAL_SECONDS = 5;

    /**
     * Optional editor-only position used by the Blueprint canvas. Null keeps tasks written by
     * older Lune versions valid and lets the editor choose a sensible automatic position.
     * Execution never reads these fields.
     */
    public Integer editorX;
    public Integer editorY;

    /**
     * Whether a run stops when it reaches this card.
     *
     * <p>Read by {@link TaskDebug} on arrival, and by nothing else: the card still does exactly its
     * own job, and a breakpoint on it changes when that job starts rather than what it is.</p>
     */
    public boolean breakpoint;

    public TaskNode() {}

    public TaskNode(String commandId) {
        this.commandId = commandId;
    }

    /** Returns an independent copy with a fresh id. */
    public TaskNode copy() {
        TaskNode copy = new TaskNode(commandId);
        copy.params.putAll(params);
        if (exposedInputs != null) {
            copy.exposedInputs.addAll(exposedInputs);
        }
        if (exposedOutputs != null) {
            copy.exposedOutputs.addAll(exposedOutputs);
        }
        if (inputLinks != null) {
            inputLinks.forEach((param, link) -> {
                if (link != null) {
                    copy.inputLinks.put(param, new TaskDataLink(link.sourceNodeId, link.sourcePort));
                }
            });
        }
        copy.observedNodeId = observedNodeId;
        copy.repeat = repeat;
        copy.onSuccess = onSuccess;
        copy.onFailure = onFailure;
        copy.onWhile = onWhile;
        copy.whileVisible = whileVisible;
        if (alwaysTargets != null) {
            copy.alwaysTargets.addAll(alwaysTargets);
        }
        copy.signalInputCount = signalInputCount;
        copy.signalOutputCount = signalOutputCount;
        copy.successInputPort = successInputPort;
        copy.failureInputPort = failureInputPort;
        copy.whileInputPort = whileInputPort;
        if (alwaysTargetInputPorts != null) {
            copy.alwaysTargetInputPorts.putAll(alwaysTargetInputPorts);
        }
        if (signalLinks != null) {
            for (TaskSignalLink link : signalLinks) {
                if (link != null) {
                    copy.signalLinks.add(new TaskSignalLink(link.outputPort,
                            link.targetNodeId, link.targetPort));
                }
            }
        }
        copy.alwaysIntervalSeconds = alwaysIntervalSeconds;
        copy.editorX = editorX;
        copy.editorY = editorY;
        // Not the breakpoint. A fresh id means a card the player has not looked at yet, and a
        // stop they never asked for is the worst kind: it is on a card they did not place it on.
        return copy;
    }

    public boolean isAlwaysNode() {
        return ALWAYS_COMMAND.equals(commandId);
    }

    public boolean isStartNode() {
        return START_COMMAND.equals(commandId);
    }

    public boolean isSignalRelayNode() {
        return SIGNAL_RELAY_COMMAND.equals(commandId);
    }

    public boolean isTimerNode() {
        return TIMER_COMMAND.equals(commandId);
    }

    public boolean isEndNode() {
        return END_COMMAND.equals(commandId);
    }

    public boolean isCounterNode() {
        return COUNTER_COMMAND.equals(commandId);
    }

    public boolean isPulseSourceNode() {
        return PULSE_COMMAND.equals(commandId);
    }

    public boolean isObserverNode() {
        return OBSERVER_COMMAND.equals(commandId);
    }

    public boolean isButtonNode() {
        return BUTTON_COMMAND.equals(commandId);
    }

    /** Nodes that carry electrical pulses rather than Success/Fail/While task outcomes. */
    public boolean isPulseNode() {
        return isSignalRelayNode() || isTimerNode() || isEndNode() || isCounterNode()
                || isObserverNode() || isButtonNode();
    }

    /** Source nodes are drawn as graph entry points rather than runnable commands. */
    public boolean isSourceNode() {
        return isAlwaysNode() || isPulseSourceNode() || isStartNode() || isObserverNode()
                || isButtonNode();
    }

    /** True for the two sources that own a clock and fan out to targets. */
    public boolean isClockNode() {
        return isAlwaysNode() || isPulseSourceNode();
    }

    public String describeRepeat() {
        if (repeat == 0) {
            return "x∞";
        }
        return "x" + repeat;
    }
}
