package com.etka.lune.routine;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Finds graph links that are present or required but cannot actually be followed at runtime. */
public final class RoutineConnectionAudit {

    public record Issue(RoutineNode node, String key, String message) {}

    private RoutineConnectionAudit() {}

    public static Optional<Issue> firstIssue(Routine routine) {
        if (routine == null || routine.nodes == null || routine.nodes.isEmpty()) {
            return Optional.empty();
        }

        if (routine.onWhile != null && routine.nodeById(routine.onWhile) == null) {
            return Optional.of(new Issue(firstNode(routine), "routine-while-missing",
                    "The task's While connection points to something that no longer exists."));
        }

        for (RoutineNode node : routine.nodes) {
            if (node == null) {
                continue;
            }
            Optional<Issue> structural = structuralIssue(routine, node);
            if (structural.isPresent()) {
                return structural;
            }
            Optional<Issue> data = dataIssue(routine, node);
            if (data.isPresent()) {
                return data;
            }
        }

        long starts = routine.nodes.stream()
                .filter(node -> node != null && node.isStartNode())
                .count();
        if (starts > 1) {
            RoutineNode first = routine.nodes.stream()
                    .filter(node -> node != null && node.isStartNode())
                    .findFirst().orElse(null);
            return Optional.of(new Issue(first, "multiple-starts",
                        "This task has more than one START node; keep one entry point."));
        }

        RoutineNode explicitStart = RoutineGraph.explicitStart(routine);
        if (explicitStart != null && explicitStart.onSuccess == null) {
            return Optional.of(new Issue(explicitStart, "start-empty-" + safe(explicitStart.id),
                        "START has no action connected, so the task has nowhere to begin."));
        }

        Set<RoutineNode> reachable = new HashSet<>();
        // START, Always, Observer, and Button are separate power sources. Seed them before
        // checking ordinary fall-through, so an event-only task is not judged by START reachability.
        addIndependentSources(routine, reachable);
        RoutineNode start = RoutineGraph.nextSequentialNode(routine, -1);
        if (start != null) {
            reachable.addAll(reachableFrom(routine, start));
        }
        for (RoutineNode node : routine.nodes) {
            if (node != null && !node.isSourceNode()
                    && !reachable.contains(node)
                    && !poweredByAlways(routine, node)
                    && !RoutineGraph.isMonitorOnly(routine, node)) {
                return Optional.of(new Issue(node, "unreachable-" + safe(node.id),
                        nodeName(node) + " isn't connected to START, Always, or another runnable step."));
            }
        }
        return Optional.empty();
    }

    private static void addIndependentSources(Routine routine, Set<RoutineNode> reachable) {
        if (routine.onWhile != null) {
            addCircuit(routine, routine.nodeById(routine.onWhile), reachable);
        }
        for (RoutineNode source : routine.nodes) {
            if (source == null) {
                continue;
            }
            if (source.isAlwaysNode()) {
                if (source.alwaysTargets == null) {
                    continue;
                }
                for (String targetId : source.alwaysTargets) {
                    addCircuit(routine, routine.nodeById(targetId), reachable);
                }
            } else if ((source.isObserverNode() || source.isButtonNode())
                    && source.signalLinks != null) {
                for (RoutineSignalLink link : source.signalLinks) {
                    if (link != null) {
                        addCircuit(routine, routine.nodeById(link.targetNodeId), reachable);
                    }
                }
            }
        }
    }

    private static void addCircuit(Routine routine, RoutineNode target, Set<RoutineNode> reachable) {
        if (target != null && !target.isSourceNode()) {
            reachable.addAll(reachableFrom(routine, target));
        }
    }

    private static boolean poweredByAlways(Routine routine, RoutineNode candidate) {
        if (candidate == null || candidate.id == null) {
            return false;
        }
        for (RoutineNode source : routine.nodes) {
            if (source != null && source.isAlwaysNode() && source.alwaysTargets != null
                    && source.alwaysTargets.contains(candidate.id)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Issue> structuralIssue(Routine routine, RoutineNode node) {
        if (node.isStartNode()) {
            if (node.onFailure != null || node.onWhile != null) {
                return Optional.of(new Issue(node, "start-extra-edge-" + safe(node.id),
                        "START only accepts one Success connection to the first action."));
            }
            if (node.onSuccess != null) {
                RoutineNode target = routine.nodeById(node.onSuccess);
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
        if (node.isAlwaysNode()) {
            if (node.alwaysTargets == null || node.alwaysTargets.isEmpty()) {
                return Optional.of(new Issue(node, "always-empty-" + safe(node.id),
                        "Always has no action connected, so it cannot send any signals."));
            }
            for (String target : node.alwaysTargets) {
                if (target == null || target.isBlank() || routine.nodeById(target) == null) {
                    return Optional.of(new Issue(node, "always-missing-" + safe(node.id),
                            "One of Always's connections points to something that no longer exists."));
                }
                RoutineNode targetNode = routine.nodeById(target);
                int targetPort = node.alwaysTargetInputPorts == null
                        ? 0 : node.alwaysTargetInputPorts.getOrDefault(target, 0);
                if (targetNode.isSignalRelayNode()
                        && (targetPort < 0 || targetPort >= targetNode.signalInputCount)) {
                    return Optional.of(new Issue(node, "always-relay-input-" + safe(node.id),
                            "Always points to a Signal Relay input that is no longer available."));
                }
            }
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
            for (RoutineSignalLink link : node.signalLinks == null
                    ? java.util.List.<RoutineSignalLink>of() : node.signalLinks) {
                if (link == null || link.targetNodeId == null
                        || routine.nodeById(link.targetNodeId) == null) {
                    return Optional.of(new Issue(node, "relay-target-missing-" + safe(node.id),
                            "One of " + pulseName(node) + "'s outputs points to something that no longer exists."));
                }
                RoutineNode target = routine.nodeById(link.targetNodeId);
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
        if (node.onSuccess != null && routine.nodeById(node.onSuccess) == null) {
            return Optional.of(new Issue(node, "success-missing-" + safe(node.id),
                    nodeName(node) + " has a success connection that no longer reaches anything."));
        }
        if (node.onSuccess != null && routine.nodeById(node.onSuccess).isStartNode()) {
            return Optional.of(new Issue(node, "success-start-" + safe(node.id),
                    nodeName(node) + " cannot send Success back into START."));
        }
        if (node.onSuccess != null && routine.nodeById(node.onSuccess).isSignalRelayNode()
                && (node.successInputPort < 0
                || node.successInputPort >= routine.nodeById(node.onSuccess).signalInputCount)) {
            return Optional.of(new Issue(node, "success-relay-input-" + safe(node.id),
                    nodeName(node) + " sends Success into a Signal Relay input that is unavailable."));
        }
        if (node.onFailure != null && routine.nodeById(node.onFailure) == null) {
            return Optional.of(new Issue(node, "failure-missing-" + safe(node.id),
                    nodeName(node) + " has a failure connection that no longer reaches anything."));
        }
        if (node.onFailure != null && routine.nodeById(node.onFailure).isStartNode()) {
            return Optional.of(new Issue(node, "failure-start-" + safe(node.id),
                    nodeName(node) + " cannot send Fail back into START."));
        }
        if (node.onFailure != null && routine.nodeById(node.onFailure).isSignalRelayNode()
                && (node.failureInputPort < 0
                || node.failureInputPort >= routine.nodeById(node.onFailure).signalInputCount)) {
            return Optional.of(new Issue(node, "failure-relay-input-" + safe(node.id),
                    nodeName(node) + " sends Fail into a Signal Relay input that is unavailable."));
        }
        if (node.onWhile != null && routine.nodeById(node.onWhile) == null) {
            return Optional.of(new Issue(node, "while-missing-" + safe(node.id),
                    nodeName(node) + " has a While connection that no longer reaches anything."));
        }
        return Optional.empty();
    }

    private static Optional<Issue> dataIssue(Routine routine, RoutineNode destination) {
        if (destination.inputLinks == null || destination.inputLinks.isEmpty()) {
            return Optional.empty();
        }
        for (var entry : destination.inputLinks.entrySet()) {
            String parameter = entry.getKey();
            RoutineDataLink link = entry.getValue();
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
            RoutineNode source = routine.nodeById(link.sourceNodeId);
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

    private static boolean hasOutputValue(RoutineNode source, String port) {
        return source.params != null && source.params.containsKey(port)
                || source.inputLinks != null && source.inputLinks.containsKey(port);
    }

    private static Set<RoutineNode> reachableFrom(Routine routine, RoutineNode start) {
        Set<RoutineNode> visited = new HashSet<>();
        ArrayDeque<RoutineNode> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            RoutineNode current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (RoutineNode next : RoutineGraph.outgoing(routine, current)) {
                if (next != null && !visited.contains(next)) {
                    pending.addLast(next);
                }
            }
        }
        return visited;
    }

    private static RoutineNode firstNode(Routine routine) {
        return routine.nodes.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static String nodeName(RoutineNode node) {
        if (node == null) {
            return "This card";
        }
        if (node.isAlwaysNode()) {
            return "Always";
        }
        return commandName(node.commandId);
    }

    private static String pulseName(RoutineNode node) {
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
            case RoutineNode.START_COMMAND -> "START";
            case RoutineNode.SIGNAL_RELAY_COMMAND -> "Signal Relay";
            case RoutineNode.TIMER_COMMAND -> "Timer";
            case RoutineNode.END_COMMAND -> "End";
            case RoutineNode.COUNTER_COMMAND -> "Counter";
            case RoutineNode.OBSERVER_COMMAND -> "Observer";
            case RoutineNode.BUTTON_COMMAND -> "Button";
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
