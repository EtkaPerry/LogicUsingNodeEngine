package com.etka.lune.routine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared execution-graph rules and safe structural edits for routines. */
public final class RoutineGraph {

    private static final int EDITOR_STEP_X = 160;

    private RoutineGraph() {}

    /** The next command reached by normal list fallthrough, skipping independently powered cards. */
    public static RoutineNode nextSequentialNode(Routine routine, int afterIndex) {
        if (routine == null || routine.nodes == null) {
            return null;
        }
        if (afterIndex < 0) {
            RoutineNode start = explicitStart(routine);
            if (start != null) {
                // An explicit START deliberately disables list-order guessing. An unconnected
                // START therefore produces no runnable node until the user wires it.
                return start.onSuccess == null ? null : routine.nodeById(start.onSuccess);
            }
        }
        for (int i = afterIndex + 1; i < routine.nodes.size(); i++) {
            RoutineNode candidate = routine.nodes.get(i);
            if (candidate != null && !candidate.isSourceNode() && !candidate.isPulseNode()
                    && !isMonitorOnly(routine, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The first explicit entry marker, if the routine has one. */
    public static RoutineNode explicitStart(Routine routine) {
        if (routine == null || routine.nodes == null) {
            return null;
        }
        return routine.nodes.stream()
                .filter(node -> node != null && node.isStartNode())
                .findFirst()
                .orElse(null);
    }

    /** True when an Always source can serve as this routine's implicit entry marker. */
    public static boolean hasAlwaysNode(Routine routine) {
        if (routine == null || routine.nodes == null) {
            return false;
        }
        return routine.nodes.stream().anyMatch(node -> node != null && node.isAlwaysNode());
    }

    /** Adds the explicit entry marker and preserves the routine's current first runnable step. */
    public static RoutineNode addExplicitStart(Routine routine) {
        if (routine == null || routine.nodes == null
                || explicitStart(routine) != null || hasAlwaysNode(routine)) {
            return null;
        }
        RoutineNode first = nextSequentialNode(routine, -1);
        RoutineNode start = new RoutineNode(RoutineNode.START_COMMAND);
        start.editorX = first != null && first.editorX != null
                ? Math.max(24, first.editorX - EDITOR_STEP_X) : 24;
        start.editorY = first != null && first.editorY != null ? first.editorY : 24;
        if (first != null) {
            start.onSuccess = first.id;
        }
        routine.nodes.add(0, start);
        return start;
    }

    /** Matches the runner's rule: While/Always-only cards stay out of normal list fallthrough. */
    public static boolean isMonitorOnly(Routine routine, RoutineNode candidate) {
        if (routine == null || candidate == null || candidate.id == null) {
            return false;
        }
        boolean whileReference = candidate.isAlwaysNode() || candidate.id.equals(routine.onWhile);
        boolean flowReference = false;
        for (RoutineNode source : routine.nodes) {
            if (source == null) {
                continue;
            }
            whileReference |= candidate.id.equals(source.onWhile);
            if (source.isAlwaysNode() && source.alwaysTargets != null) {
                whileReference |= source.alwaysTargets.contains(candidate.id);
            }
            flowReference |= candidate.id.equals(source.onSuccess)
                    || candidate.id.equals(source.onFailure);
        }
        return whileReference && !flowReference;
    }

    /** True when a node is the live companion selected by a While pin. */
    public static boolean isWhileTarget(Routine routine, RoutineNode candidate) {
        if (routine == null || candidate == null || candidate.id == null) {
            return false;
        }
        if (candidate.id.equals(routine.onWhile)) {
            return true;
        }
        return routine.nodes.stream().filter(java.util.Objects::nonNull)
                .anyMatch(source -> candidate.id.equals(source.onWhile));
    }

    /** Success/failure destinations, including implicit success fallthrough. */
    public static List<RoutineNode> outgoing(Routine routine, RoutineNode node) {
        List<RoutineNode> result = new ArrayList<>(2);
        if (routine == null || node == null) {
            return result;
        }
        if (node.isStartNode()) {
            addIfPresent(result, routine.nodeById(node.onSuccess));
            return result;
        }
        if (node.isPulseNode()) {
            if (node.signalLinks != null) {
                for (RoutineSignalLink link : node.signalLinks) {
                    if (link != null) {
                        addIfPresent(result, routine.nodeById(link.targetNodeId));
                    }
                }
            }
            return result;
        }
        if (node.onSuccess != null) {
            addIfPresent(result, routine.nodeById(node.onSuccess));
        } else {
            addIfPresent(result, nextSequentialNode(routine, routine.indexOf(node)));
        }
        if (node.onFailure != null) {
            addIfPresent(result, routine.nodeById(node.onFailure));
        }
        return result;
    }

    public static boolean isInFlowCycle(Routine routine, RoutineNode candidate) {
        if (candidate == null || candidate.repeat == 0) {
            return candidate != null;
        }
        for (RoutineNode next : outgoing(routine, candidate)) {
            if (reaches(routine, next, candidate, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    public static boolean canInsertBefore(Routine routine, RoutineNode target) {
        return editableTarget(routine, target) && !isInFlowCycle(routine, target);
    }

    public static boolean canInsertAfter(Routine routine, RoutineNode target) {
        return editableTarget(routine, target) && !isInFlowCycle(routine, target);
    }

    /** Inserts a non-blocking preparation step and redirects every explicit flow entry to it. */
    public static RoutineNode insertBefore(Routine routine, RoutineNode target, String commandId,
                                           Map<String, String> params) {
        if (!canInsertBefore(routine, target) || commandId == null || commandId.isBlank()) {
            return null;
        }
        RoutineNode inserted = new RoutineNode(commandId);
        inserted.params.putAll(params == null ? Map.of() : params);
        inserted.onSuccess = target.id;
        inserted.onFailure = target.id;

        for (RoutineNode source : routine.nodes) {
            if (source == null || source == target) {
                continue;
            }
            if (target.id.equals(source.onSuccess)) {
                source.onSuccess = inserted.id;
            }
            if (target.id.equals(source.onFailure)) {
                source.onFailure = inserted.id;
            }
        }

        int targetIndex = routine.indexOf(target);
        placeBefore(routine, target, inserted);
        routine.nodes.add(targetIndex, inserted);
        return inserted;
    }

    /** Inserts an optional follow-up while preserving the target's former success destination. */
    public static RoutineNode insertAfter(Routine routine, RoutineNode target, String commandId,
                                          Map<String, String> params) {
        if (!canInsertAfter(routine, target) || commandId == null || commandId.isBlank()) {
            return null;
        }
        RoutineNode formerSuccess = target.onSuccess == null
                ? nextSequentialNode(routine, routine.indexOf(target))
                : routine.nodeById(target.onSuccess);
        RoutineNode inserted = new RoutineNode(commandId);
        inserted.params.putAll(params == null ? Map.of() : params);
        inserted.onSuccess = formerSuccess == null ? null : formerSuccess.id;
        inserted.onFailure = formerSuccess == null ? null : formerSuccess.id;
        target.onSuccess = inserted.id;

        int targetIndex = routine.indexOf(target);
        placeAfter(routine, target, inserted);
        routine.nodes.add(targetIndex + 1, inserted);
        return inserted;
    }

    /** Deep functional snapshot used by Lune's one-step undo while preserving the Routine object. */
    public static Routine copy(Routine source) {
        if (source == null) {
            return null;
        }
        Routine copy = new Routine(source.name);
        copy.onWhile = source.onWhile;
        if (source.nodes != null) {
            for (RoutineNode node : source.nodes) {
                copy.nodes.add(copyNode(node));
            }
        }
        return copy;
    }

    /** Restores matching node objects in place so a running RoutineTask keeps valid references. */
    public static boolean restore(Routine target, Routine snapshot) {
        if (target == null || snapshot == null || target.nodes == null || snapshot.nodes == null) {
            return false;
        }
        Map<String, RoutineNode> liveById = new LinkedHashMap<>();
        for (RoutineNode node : target.nodes) {
            if (node != null && node.id != null) {
                liveById.put(node.id, node);
            }
        }
        List<RoutineNode> restored = new ArrayList<>();
        for (RoutineNode saved : snapshot.nodes) {
            if (saved == null) {
                continue;
            }
            RoutineNode live = liveById.get(saved.id);
            if (live == null) {
                live = copyNode(saved);
            } else {
                copyNodeFields(saved, live);
            }
            restored.add(live);
        }
        target.name = snapshot.name;
        target.onWhile = snapshot.onWhile;
        target.nodes.clear();
        target.nodes.addAll(restored);
        return true;
    }

    private static boolean editableTarget(Routine routine, RoutineNode target) {
        return routine != null && routine.nodes != null && target != null
                && target.id != null && routine.nodes.contains(target)
                && !target.isSourceNode() && !isMonitorOnly(routine, target);
    }

    private static boolean reaches(Routine routine, RoutineNode current, RoutineNode target,
                                   Set<RoutineNode> visited) {
        if (current == null) {
            return false;
        }
        if (current == target || target.id != null && target.id.equals(current.id)) {
            return true;
        }
        if (!visited.add(current)) {
            return false;
        }
        for (RoutineNode next : outgoing(routine, current)) {
            if (reaches(routine, next, target, visited)) {
                return true;
            }
        }
        return false;
    }

    private static void addIfPresent(List<RoutineNode> nodes, RoutineNode node) {
        if (node != null && !nodes.contains(node)) {
            nodes.add(node);
        }
    }

    private static void placeBefore(Routine routine, RoutineNode target, RoutineNode inserted) {
        if (target.editorX == null || target.editorY == null) {
            return;
        }
        int oldX = target.editorX;
        int laneY = target.editorY;
        shiftLane(routine, oldX, laneY);
        inserted.editorX = oldX;
        inserted.editorY = laneY;
    }

    private static void placeAfter(Routine routine, RoutineNode target, RoutineNode inserted) {
        if (target.editorX == null || target.editorY == null) {
            return;
        }
        int newX = target.editorX + EDITOR_STEP_X;
        int laneY = target.editorY;
        shiftLane(routine, newX, laneY);
        inserted.editorX = newX;
        inserted.editorY = laneY;
    }

    private static void shiftLane(Routine routine, int fromX, int laneY) {
        for (RoutineNode node : routine.nodes) {
            if (node != null && node.editorX != null && node.editorY != null
                    && node.editorX >= fromX && Math.abs(node.editorY - laneY) < 60) {
                node.editorX += EDITOR_STEP_X;
            }
        }
    }

    private static RoutineNode copyNode(RoutineNode source) {
        if (source == null) {
            return null;
        }
        RoutineNode copy = new RoutineNode();
        copyNodeFields(source, copy);
        return copy;
    }

    private static void copyNodeFields(RoutineNode source, RoutineNode target) {
        target.id = source.id;
        target.commandId = source.commandId;
        target.params = source.params == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(source.params);
        target.exposedInputs = source.exposedInputs == null ? new LinkedHashSet<>()
                : new LinkedHashSet<>(source.exposedInputs);
        target.exposedOutputs = source.exposedOutputs == null ? new LinkedHashSet<>()
                : new LinkedHashSet<>(source.exposedOutputs);
        target.inputLinks = new LinkedHashMap<>();
        if (source.inputLinks != null) {
            source.inputLinks.forEach((parameter, link) -> {
                if (link != null) {
                    target.inputLinks.put(parameter,
                            new RoutineDataLink(link.sourceNodeId, link.sourcePort));
                }
            });
        }
        target.repeat = source.repeat;
        target.onSuccess = source.onSuccess;
        target.onFailure = source.onFailure;
        target.onWhile = source.onWhile;
        target.alwaysTargets = source.alwaysTargets == null ? new LinkedHashSet<>()
                : new LinkedHashSet<>(source.alwaysTargets);
        target.signalInputCount = source.signalInputCount;
        target.signalOutputCount = source.signalOutputCount;
        target.successInputPort = source.successInputPort;
        target.failureInputPort = source.failureInputPort;
        target.whileInputPort = source.whileInputPort;
        target.alwaysTargetInputPorts = source.alwaysTargetInputPorts == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(source.alwaysTargetInputPorts);
        target.signalLinks = new ArrayList<>();
        if (source.signalLinks != null) {
            for (RoutineSignalLink link : source.signalLinks) {
                if (link != null) {
                    target.signalLinks.add(new RoutineSignalLink(link.outputPort,
                            link.targetNodeId, link.targetPort));
                }
            }
        }
        target.alwaysIntervalSeconds = source.alwaysIntervalSeconds;
        target.editorX = source.editorX;
        target.editorY = source.editorY;
    }
}
