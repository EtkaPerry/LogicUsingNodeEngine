package com.etka.lune.task;

import com.etka.lune.util.Lang;
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
                    Lang.get("lune.audit.tasks_while_connection_points_something")));
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
                        Lang.get("lune.audit.task_has_more_than_one_start_node_keep")));
        }

        TaskNode explicitStart = TaskWiring.explicitStart(task);
        if (explicitStart != null && explicitStart.onSuccess == null) {
            return Optional.of(new Issue(explicitStart, "start-empty-" + safe(explicitStart.id),
                        Lang.get("lune.audit.start_has_action_connected_task_has")));
        }

        Set<TaskNode> reachable = TaskWiring.poweredNodes(task);
        for (TaskNode node : task.nodes) {
            if (node != null && !node.isSourceNode()
                    && !reachable.contains(node)
                    && !TaskWiring.isMonitorOnly(task, node)) {
                return Optional.of(new Issue(node, "unreachable-" + safe(node.id),
                        Lang.get("lune.audit.isnt_connected_start_always_or_another", nodeName(node))));
            }
        }
        return Optional.empty();
    }

    private static Optional<Issue> structuralIssue(TaskGraph task, TaskNode node) {
        if (node.isStartNode()) {
            if (node.onFailure != null || node.onWhile != null) {
                return Optional.of(new Issue(node, "start-extra-edge-" + safe(node.id),
                        Lang.get("lune.audit.start_only_accepts_one_success")));
            }
            if (node.onSuccess != null) {
                TaskNode target = task.nodeById(node.onSuccess);
                if (target == null) {
                    return Optional.of(new Issue(node, "start-missing-" + safe(node.id),
                            Lang.get("lune.audit.start_has_success_connection_longer")));
                }
                if (target != null && target.isSourceNode()) {
                    return Optional.of(new Issue(node, "start-source-target-" + safe(node.id),
                            Lang.get("lune.audit.start_must_connect_directly_runnable")));
                }
            }
            return Optional.empty();
        }
        if (node.isClockNode()) {
            if (node.alwaysTargets == null || node.alwaysTargets.isEmpty()) {
                return Optional.of(new Issue(node, "always-empty-" + safe(node.id),
                        Lang.get("lune.audit.has_action_connected_cannot_send_any", nodeName(node))));
            }
            for (String target : node.alwaysTargets) {
                if (target == null || target.isBlank() || task.nodeById(target) == null) {
                    return Optional.of(new Issue(node, "always-missing-" + safe(node.id),
                            Lang.get("lune.audit.one_s_connections_points_something", nodeName(node))));
                }
                TaskNode targetNode = task.nodeById(target);
                int targetPort = node.alwaysTargetInputPorts == null
                        ? 0 : node.alwaysTargetInputPorts.getOrDefault(target, 0);
                if (targetNode.isSignalRelayNode()
                        && (targetPort < 0 || targetPort >= targetNode.signalInputCount)) {
                    return Optional.of(new Issue(node, "always-relay-input-" + safe(node.id),
                            Lang.get("lune.audit.points_signal_relay_input_longer", nodeName(node))));
                }
            }
        }
        if (node.isObserverNode() && (node.observedNodeId == null
                || task.nodeById(node.observedNodeId) == null)) {
            return Optional.of(new Issue(node, "observer-unwatched-" + safe(node.id),
                    Lang.get("lune.audit.observer_has_card_wired_into_watch_pin")));
        }
        if (node.isPulseNode()) {
            if (node.isEndNode()) {
                if (node.signalLinks != null && !node.signalLinks.isEmpty()) {
                    return Optional.of(new Issue(node, "end-output-" + safe(node.id),
                            Lang.get("lune.audit.end_pulse_sink_cannot_have_output")));
                }
            } else if (node.signalLinks == null || node.signalLinks.isEmpty()) {
                String label = pulseName(node);
                return Optional.of(new Issue(node, "pulse-empty-" + safe(node.id),
                        Lang.get("lune.audit.has_output_connected_pulses_go_nowhere", label)));
            }
            for (TaskSignalLink link : node.signalLinks == null
                    ? java.util.List.<TaskSignalLink>of() : node.signalLinks) {
                if (link == null || link.targetNodeId == null
                        || task.nodeById(link.targetNodeId) == null) {
                    return Optional.of(new Issue(node, "relay-target-missing-" + safe(node.id),
                            Lang.get("lune.audit.one_s_outputs_points_something_longer", pulseName(node))));
                }
                TaskNode target = task.nodeById(link.targetNodeId);
                if (link.outputPort < 0 || link.outputPort >= node.signalOutputCount
                        || !node.isSignalRelayNode() && link.outputPort != 0
                        || target.isSourceNode()
                        || !target.isSignalRelayNode() && link.targetPort != -1
                        || target.isSignalRelayNode()
                        && (link.targetPort < 0 || link.targetPort >= target.signalInputCount)) {
                    return Optional.of(new Issue(node, "pulse-port-invalid-" + safe(node.id),
                            Lang.get("lune.audit.has_output_wire_connected_invalid_port", pulseName(node))));
                }
            }
        }
        if (node.onSuccess != null && task.nodeById(node.onSuccess) == null) {
            return Optional.of(new Issue(node, "success-missing-" + safe(node.id),
                    Lang.get("lune.audit.has_success_connection_longer_reaches", nodeName(node))));
        }
        if (node.onSuccess != null && task.nodeById(node.onSuccess).isStartNode()) {
            return Optional.of(new Issue(node, "success-start-" + safe(node.id),
                    Lang.get("lune.audit.cannot_send_success_back_into_start", nodeName(node))));
        }
        if (node.onSuccess != null && task.nodeById(node.onSuccess).isSignalRelayNode()
                && (node.successInputPort < 0
                || node.successInputPort >= task.nodeById(node.onSuccess).signalInputCount)) {
            return Optional.of(new Issue(node, "success-relay-input-" + safe(node.id),
                    Lang.get("lune.audit.sends_success_into_signal_relay_input", nodeName(node))));
        }
        if (node.onFailure != null && task.nodeById(node.onFailure) == null) {
            return Optional.of(new Issue(node, "failure-missing-" + safe(node.id),
                    Lang.get("lune.audit.has_failure_connection_longer_reaches", nodeName(node))));
        }
        if (node.onFailure != null && task.nodeById(node.onFailure).isStartNode()) {
            return Optional.of(new Issue(node, "failure-start-" + safe(node.id),
                    Lang.get("lune.audit.cannot_send_fail_back_into_start", nodeName(node))));
        }
        if (node.onFailure != null && task.nodeById(node.onFailure).isSignalRelayNode()
                && (node.failureInputPort < 0
                || node.failureInputPort >= task.nodeById(node.onFailure).signalInputCount)) {
            return Optional.of(new Issue(node, "failure-relay-input-" + safe(node.id),
                    Lang.get("lune.audit.sends_fail_into_signal_relay_input", nodeName(node))));
        }
        if (node.onWhile != null && task.nodeById(node.onWhile) == null) {
            return Optional.of(new Issue(node, "while-missing-" + safe(node.id),
                    Lang.get("lune.audit.has_while_connection_longer_reaches", nodeName(node))));
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
                        Lang.get("lune.audit.has_data_input_with_source_connected", nodeName(destination))));
            }
            if (parameter == null || parameter.isBlank()
                    || destination.exposedInputs == null
                    || !destination.exposedInputs.contains(parameter)) {
                return Optional.of(new Issue(destination,
                        "data-input-missing-" + safe(destination.id) + "-" + safe(parameter),
                        Lang.get("lune.audit.has_data_wire_into_input_longer_has", nodeName(destination))));
            }
            TaskNode source = task.nodeById(link.sourceNodeId);
            if (source == null) {
                return Optional.of(new Issue(destination,
                        "data-node-missing-" + safe(destination.id) + "-" + safe(parameter),
                        Lang.get("lune.audit.reads_data_from_card_longer_exists", nodeName(destination))));
            }
            if (link.sourcePort == null || link.sourcePort.isBlank()
                    || source.exposedOutputs == null
                    || !source.exposedOutputs.contains(link.sourcePort)
                    || !hasOutputValue(source, link.sourcePort)) {
                return Optional.of(new Issue(destination,
                        "data-output-missing-" + safe(destination.id) + "-" + safe(parameter),
                        Lang.get("lune.audit.needs_data_from_but_output_connected", nodeName(destination), nodeName(source))));
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
            return Lang.get("lune.audit.card");
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
            return Lang.get("lune.audit.card");
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
