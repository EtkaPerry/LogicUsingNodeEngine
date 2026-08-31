package com.etka.lune.task;

import java.util.Optional;
import java.util.Set;

/** Finds graph links that are present or required but cannot actually be followed at runtime. */
public final class TaskConnectionAudit {

    public record Issue(TaskNode node, String key, String message) {}

    private TaskConnectionAudit() {}

    public static Optional<Issue> firstIssue(TaskGraph task) {
        if (task == null || task.nodes == null || task.nodes.isEmpty()) {
            return Optional.empty();
        }

        if (task.onWhile != null && task.nodeById(task.onWhile) == null) {
            return Optional.of(new Issue(firstNode(task), "task-while-missing",
                    "The task's While connection points to something that no longer exists."));
        }

        for (TaskNode node : task.nodes) {
            if (node == null) {
                continue;
            }
            Optional<Issue> structural = structuralIssue(task, node);
            if (structural.isPresent()) {
                return structural;
            }
            Optional<Issue> data = dataIssue(task, node);
            if (data.isPresent()) {
                return data;
            }
        }

        long starts = task.nodes.stream()
                .filter(node -> node != null && node.isStartNode())
                .count();
        if (starts > 1) {
            TaskNode first = task.nodes.stream()
                    .filter(node -> node != null && node.isStartNode())
                    .findFirst().orElse(null);
            return Optional.of(new Issue(first, "multiple-starts",
                        "This task has more than one START node; keep one entry point."));
        }

        TaskNode explicitStart = TaskWiring.explicitStart(task);
        if (explicitStart != null && explicitStart.onSuccess == null) {
            return Optional.of(new Issue(explicitStart, "start-empty-" + safe(explicitStart.id),
                        "START has no action connected, so the task has nowhere to begin."));
        }

        Set<TaskNode> reachable = TaskWiring.poweredNodes(task);
        for (TaskNode node : task.nodes) {
            if (node != null && !node.isSourceNode()
                    && !reachable.contains(node)
                    && !TaskWiring.isMonitorOnly(task, node)) {
                return Optional.of(new Issue(node, "unreachable-" + safe(node.id),
                        nodeName(node) + " isn't connected to START, Always, or another runnable step."));
            }
        }
        return Optional.empty();
    }

    private static Optional<Issue> structuralIssue(TaskGraph task, TaskNode node) {
        if (node.isStartNode()) {
            if (node.onFailure != null || node.onWhile != null) {
                return Optional.of(new Issue(node, "start-extra-edge-" + safe(node.id),
                        "START only accepts one Success connection to the first action."));
            }
            if (node.onSuccess != null) {
                TaskNode target = task.nodeById(node.onSuccess);
                if (target == null) {
                    return Optional.of(new Issue(node, "start-missing-" + safe(node.id),
                            "START has a Success connection that no longer reaches anything."));
                }
                if (target != null && target.isSourceNode()) {
                    return Optional.of(new Issue(node, "start-source-target-" + safe(node.id),
                            "START must connect directly to a runnable action."));
                }
            }
            return Optional.empty();
        }
        if (node.isClockNode()) {
            if (node.alwaysTargets == null || node.alwaysTargets.isEmpty()) {
                return Optional.of(new Issue(node, "always-empty-" + safe(node.id),
                        nodeName(node) + " has no action connected, so it cannot send any signals."));
            }
            for (String target : node.alwaysTargets) {
                if (target == null || target.isBlank() || task.nodeById(target) == null) {
                    return Optional.of(new Issue(node, "always-missing-" + safe(node.id),
                            "One of " + nodeName(node)
                                    + "'s connections points to something that no longer exists."));
                }
                TaskNode targetNode = task.nodeById(target);
                int targetPort = node.alwaysTargetInputPorts == null
                        ? 0 : node.alwaysTargetInputPorts.getOrDefault(target, 0);
                if (targetNode.isSignalRelayNode()
                        && (targetPort < 0 || targetPort >= targetNode.signalInputCount)) {
                    return Optional.of(new Issue(node, "always-relay-input-" + safe(node.id),
                            nodeName(node)
                                    + " points to a Signal Relay input that is no longer available."));
                }
            }
        }
        if (node.isObserverNode() && (node.observedNodeId == null
                || task.nodeById(node.observedNodeId) == null)) {
            return Optional.of(new Issue(node, "observer-unwatched-" + safe(node.id),
                    "Observer has no card wired into its Watch pin, so it has nothing to react to."));
        }
        if (node.isPulseNode()) {
            if (node.isEndNode()) {
                if (node.signalLinks != null && !node.signalLinks.isEmpty()) {
                    return Optional.of(new Issue(node, "end-output-" + safe(node.id),
                            "End is a pulse sink and cannot have an output connection."));
                }
            } else if (node.signalLinks == null || node.signalLinks.isEmpty()) {
                String label = pulseName(node);
                return Optional.of(new Issue(node, "pulse-empty-" + safe(node.id),
                        label + " has no output connected, so its pulses go nowhere."));
            }
            for (TaskSignalLink link : node.signalLinks == null
                    ? java.util.List.<TaskSignalLink>of() : node.signalLinks) {
                if (link == null || link.targetNodeId == null
                        || task.nodeById(link.targetNodeId) == null) {
                    return Optional.of(new Issue(node, "relay-target-missing-" + safe(node.id),
                            "One of " + pulseName(node) + "'s outputs points to something that no longer exists."));
                }
                TaskNode target = task.nodeById(link.targetNodeId);
                if (link.outputPort < 0 || link.outputPort >= node.signalOutputCount
                        || !node.isSignalRelayNode() && link.outputPort != 0
                        || target.isSourceNode()
                        || !target.isSignalRelayNode() && link.targetPort != -1
                        || target.isSignalRelayNode()
                        && (link.targetPort < 0 || link.targetPort >= target.signalInputCount)) {
                    return Optional.of(new Issue(node, "pulse-port-invalid-" + safe(node.id),
                            pulseName(node) + " has an output wire connected to an invalid port."));
                }
            }
        }
        if (node.onSuccess != null && task.nodeById(node.onSuccess) == null) {
            return Optional.of(new Issue(node, "success-missing-" + safe(node.id),
                    nodeName(node) + " has a success connection that no longer reaches anything."));
        }
        if (node.onSuccess != null && task.nodeById(node.onSuccess).isStartNode()) {
            return Optional.of(new Issue(node, "success-start-" + safe(node.id),
                    nodeName(node) + " cannot send Success back into START."));
        }
        if (node.onSuccess != null && task.nodeById(node.onSuccess).isSignalRelayNode()
                && (node.successInputPort < 0
                || node.successInputPort >= task.nodeById(node.onSuccess).signalInputCount)) {
            return Optional.of(new Issue(node, "success-relay-input-" + safe(node.id),
                    nodeName(node) + " sends Success into a Signal Relay input that is unavailable."));
        }
        if (node.onFailure != null && task.nodeById(node.onFailure) == null) {
            return Optional.of(new Issue(node, "failure-missing-" + safe(node.id),
                    nodeName(node) + " has a failure connection that no longer reaches anything."));
        }
        if (node.onFailure != null && task.nodeById(node.onFailure).isStartNode()) {
            return Optional.of(new Issue(node, "failure-start-" + safe(node.id),
                    nodeName(node) + " cannot send Fail back into START."));
        }
        if (node.onFailure != null && task.nodeById(node.onFailure).isSignalRelayNode()
                && (node.failureInputPort < 0
                || node.failureInputPort >= task.nodeById(node.onFailure).signalInputCount)) {
            return Optional.of(new Issue(node, "failure-relay-input-" + safe(node.id),
                    nodeName(node) + " sends Fail into a Signal Relay input that is unavailable."));
        }
        if (node.onWhile != null && task.nodeById(node.onWhile) == null) {
            return Optional.of(new Issue(node, "while-missing-" + safe(node.id),
                    nodeName(node) + " has a While connection that no longer reaches anything."));
        }
        return Optional.empty();
    }

    private static Optional<Issue> dataIssue(TaskGraph task, TaskNode destination) {
        if (destination.inputLinks == null || destination.inputLinks.isEmpty()) {
            return Optional.empty();
        }
        for (var entry : destination.inputLinks.entrySet()) {
            String parameter = entry.getKey();
            TaskDataLink link = entry.getValue();
            if (link == null || link.sourceNodeId == null || link.sourceNodeId.isBlank()) {
                return Optional.of(new Issue(destination,
                        "data-source-empty-" + safe(destination.id) + "-" + safe(parameter),
                        nodeName(destination) + " has a data input with no source connected."));
            }
            if (parameter == null || parameter.isBlank()
                    || destination.exposedInputs == null
                    || !destination.exposedInputs.contains(parameter)) {
                return Optional.of(new Issue(destination,
                        "data-input-missing-" + safe(destination.id) + "-" + safe(parameter),
                        nodeName(destination) + " has a data wire into an input it no longer has."));
            }
            TaskNode source = task.nodeById(link.sourceNodeId);
            if (source == null) {
                return Optional.of(new Issue(destination,
                        "data-node-missing-" + safe(destination.id) + "-" + safe(parameter),
                        nodeName(destination) + " reads data from a card that no longer exists."));
            }
            if (link.sourcePort == null || link.sourcePort.isBlank()
                    || source.exposedOutputs == null
                    || !source.exposedOutputs.contains(link.sourcePort)
                    || !hasOutputValue(source, link.sourcePort)) {
                return Optional.of(new Issue(destination,
                        "data-output-missing-" + safe(destination.id) + "-" + safe(parameter),
                        nodeName(destination) + " needs data from " + nodeName(source)
                                + ", but that output is not connected."));
            }
        }
        return Optional.empty();
    }

    private static boolean hasOutputValue(TaskNode source, String port) {
        return source.params != null && source.params.containsKey(port)
                || source.inputLinks != null && source.inputLinks.containsKey(port);
    }

    private static TaskNode firstNode(TaskGraph task) {
        return task.nodes.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static String nodeName(TaskNode node) {
        if (node == null) {
            return "This card";
        }
        if (node.isClockNode()) {
            return node.isPulseSourceNode() ? "Pulse" : "Always";
        }
        return commandName(node.commandId);
    }

    private static String pulseName(TaskNode node) {
        return node.isTimerNode() ? "Timer"
                : node.isEndNode() ? "End"
                : node.isCounterNode() ? "Counter"
                : node.isObserverNode() ? "Observer"
                : node.isButtonNode() ? "Button" : "Signal Relay";
    }

    private static String commandName(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return "This card";
        }
        return switch (commandId) {
            case TaskNode.START_COMMAND -> "START";
            case TaskNode.SIGNAL_RELAY_COMMAND -> "Signal Relay";
            case TaskNode.TIMER_COMMAND -> "Timer";
            case TaskNode.END_COMMAND -> "End";
            case TaskNode.COUNTER_COMMAND -> "Counter";
            case TaskNode.OBSERVER_COMMAND -> "Observer";
            case TaskNode.BUTTON_COMMAND -> "Button";
            case "chop" -> "Chop Wood";
            case "gettool" -> "Get Tools";
            case "stripmine" -> "Stripmine";
            case "self_preservation" -> "Self Preservation";
            default -> Character.toUpperCase(commandId.charAt(0))
                    + commandId.substring(1).replace('_', ' ');
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
