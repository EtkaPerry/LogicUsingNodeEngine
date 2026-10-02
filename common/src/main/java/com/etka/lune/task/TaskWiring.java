package com.etka.lune.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared execution-graph rules and safe structural edits for tasks. */
public final class TaskWiring {

    private static final int EDITOR_STEP_X = 160;

    private TaskWiring() {}

    /** The next command reached by normal list fallthrough, skipping independently powered cards. */
    public static TaskNode nextSequentialNode(TaskGraph task, int afterIndex) {
        if (task == null || task.nodes == null) {
            return null;
        }
        if (afterIndex < 0) {
            TaskNode start = explicitStart(task);
            if (start != null) {
                // An explicit START deliberately disables list-order guessing. An unconnected
                // START therefore produces no runnable node until the user wires it.
                return start.onSuccess == null ? null : task.nodeById(start.onSuccess);
            }
        }
        for (int i = afterIndex + 1; i < task.nodes.size(); i++) {
            TaskNode candidate = task.nodes.get(i);
            if (candidate != null && !candidate.isSourceNode() && !candidate.isPulseNode()
                    && !isMonitorOnly(task, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The first explicit entry marker, if the task has one. */
    public static TaskNode explicitStart(TaskGraph task) {
        if (task == null || task.nodes == null) {
            return null;
        }
        return task.nodes.stream()
                .filter(node -> node != null && node.isStartNode())
                .findFirst()
                .orElse(null);
    }

    /** True when an Always source can serve as this task's implicit entry marker. */
    public static boolean hasAlwaysNode(TaskGraph task) {
        if (task == null || task.nodes == null) {
            return false;
        }
        return task.nodes.stream().anyMatch(node -> node != null && node.isClockNode());
    }

    /** Adds the explicit entry marker and preserves the task's current first runnable step. */
    public static TaskNode addExplicitStart(TaskGraph task) {
        if (task == null || task.nodes == null
                || explicitStart(task) != null || hasAlwaysNode(task)) {
            return null;
        }
        TaskNode first = nextSequentialNode(task, -1);
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        start.editorX = first != null && first.editorX != null
                ? Math.max(24, first.editorX - EDITOR_STEP_X) : 24;
        start.editorY = first != null && first.editorY != null ? first.editorY : 24;
        if (first != null) {
            start.onSuccess = first.id;
        }
        task.nodes.add(0, start);
        return start;
    }

    /** Matches the runner's rule: While/Always-only cards stay out of normal list fallthrough. */
    public static boolean isMonitorOnly(TaskGraph task, TaskNode candidate) {
        if (task == null || candidate == null || candidate.id == null) {
            return false;
        }
        boolean whileReference = candidate.isClockNode() || candidate.id.equals(task.onWhile);
        boolean flowReference = false;
        for (TaskNode source : task.nodes) {
            if (source == null) {
                continue;
            }
            whileReference |= candidate.id.equals(source.onWhile);
            if (source.isClockNode() && source.alwaysTargets != null) {
                whileReference |= source.alwaysTargets.contains(candidate.id);
            }
            flowReference |= candidate.id.equals(source.onSuccess)
                    || candidate.id.equals(source.onFailure);
        }
        return whileReference && !flowReference;
    }

    /**
     * True when a card set to run forever has somewhere to go once its work succeeds.
     *
     * <p>An explicit Success wire counts first, and list fall-through only when there is no wire.
     * Both lanes of the runner asked this question and answered it differently: the parallel one
     * checked for a Success wire and then advanced by list order anyway, so a branch whose card
     * was set to x&#8734; sat on that card forever and the wire the player drew never fired.</p>
     */
    public static boolean hasSuccessDestination(TaskGraph task, TaskNode node) {
        if (task == null || node == null) {
            return false;
        }
        if (node.onSuccess != null) {
            return task.nodeById(node.onSuccess) != null;
        }
        int index = task.indexOf(node);
        return index >= 0 && nextSequentialNode(task, index) != null;
    }

    /** True when a node is the live companion selected by a While pin. */
    public static boolean isWhileTarget(TaskGraph task, TaskNode candidate) {
        if (task == null || candidate == null || candidate.id == null) {
            return false;
        }
        if (candidate.id.equals(task.onWhile)) {
            return true;
        }
        return task.nodes.stream().filter(java.util.Objects::nonNull)
                .anyMatch(source -> candidate.id.equals(source.onWhile));
    }

    /** Success/failure destinations, including implicit success fallthrough. */
    public static List<TaskNode> outgoing(TaskGraph task, TaskNode node) {
        List<TaskNode> result = new ArrayList<>(2);
        if (task == null || node == null) {
            return result;
        }
        if (node.isStartNode()) {
            addIfPresent(result, task.nodeById(node.onSuccess));
            return result;
        }
        if (node.isPulseNode()) {
            if (node.signalLinks != null) {
                for (TaskSignalLink link : node.signalLinks) {
                    if (link != null) {
                        addIfPresent(result, task.nodeById(link.targetNodeId));
                    }
                }
            }
            return result;
        }
        if (node.onSuccess != null) {
            addIfPresent(result, task.nodeById(node.onSuccess));
        } else {
            addIfPresent(result, nextSequentialNode(task, task.indexOf(node)));
        }
        if (node.onFailure != null) {
            addIfPresent(result, task.nodeById(node.onFailure));
        }
        return result;
    }

    /**
     * Every node a run of this task can actually reach, from every power source it has.
     *
     * <p>"Is this card connected?" is one question with one answer. The connection audit asks it
     * to find orphans and the safety check asks it to find out whether protection will really
     * run; two walks would drift, and then Lune would offer to add a guard to a task whose
     * canvas plainly shows one wired in.</p>
     *
     * <p>Source cards are deliberately left out of the result: they are what supplies power, not
     * what receives it.</p>
     */
    public static Set<TaskNode> poweredNodes(TaskGraph task) {
        Set<TaskNode> powered = new LinkedHashSet<>();
        if (task == null || task.nodes == null) {
            return powered;
        }
        ArrayDeque<TaskNode> pending = new ArrayDeque<>();
        // START, Always, Observer and Button are separate power sources. Seeding each of them
        // means an event-only task is not judged by whether START reaches anything.
        seed(task, task.nodeById(task.onWhile), pending);
        for (TaskNode source : task.nodes) {
            if (source == null) {
                continue;
            }
            if (source.isClockNode() && source.alwaysTargets != null) {
                for (String targetId : source.alwaysTargets) {
                    seed(task, task.nodeById(targetId), pending);
                }
            } else if ((source.isObserverNode() || source.isButtonNode())
                    && source.signalLinks != null) {
                for (TaskSignalLink link : source.signalLinks) {
                    if (link != null) {
                        seed(task, task.nodeById(link.targetNodeId), pending);
                    }
                }
            }
        }
        seed(task, nextSequentialNode(task, -1), pending);

        while (!pending.isEmpty()) {
            TaskNode current = pending.removeFirst();
            if (!powered.add(current)) {
                continue;
            }
            for (TaskNode next : outgoing(task, current)) {
                if (next != null) {
                    pending.addLast(next);
                }
            }
            // A While companion runs beside its node rather than after it, so a powered node
            // powers its companion too.
            seed(task, task.nodeById(current.onWhile), pending);
        }
        return powered;
    }

    /** True when something in the task wires into this node, rather than merely sitting near it. */
    public static boolean hasIncomingConnection(TaskGraph task, TaskNode candidate) {
        if (task == null || task.nodes == null || candidate == null || candidate.id == null) {
            return false;
        }
        if (candidate.id.equals(task.onWhile)) {
            return true;
        }
        for (TaskNode source : task.nodes) {
            if (source == null || source == candidate) {
                continue;
            }
            if (candidate.id.equals(source.onSuccess) || candidate.id.equals(source.onFailure)
                    || candidate.id.equals(source.onWhile)) {
                return true;
            }
            if (source.alwaysTargets != null && source.alwaysTargets.contains(candidate.id)) {
                return true;
            }
            if (source.signalLinks != null) {
                for (TaskSignalLink link : source.signalLinks) {
                    if (link != null && candidate.id.equals(link.targetNodeId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void seed(TaskGraph task, TaskNode target, ArrayDeque<TaskNode> pending) {
        if (target != null && !target.isSourceNode() && task.nodes.contains(target)) {
            pending.addLast(target);
        }
    }

    public static boolean isInFlowCycle(TaskGraph task, TaskNode candidate) {
        if (candidate == null || candidate.repeat == 0) {
            return candidate != null;
        }
        for (TaskNode next : outgoing(task, candidate)) {
            if (reaches(task, next, candidate, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    public static boolean canInsertBefore(TaskGraph task, TaskNode target) {
        return editableTarget(task, target) && !isInFlowCycle(task, target);
    }

    public static boolean canInsertAfter(TaskGraph task, TaskNode target) {
        return editableTarget(task, target) && !isInFlowCycle(task, target);
    }

    /** Inserts a non-blocking preparation step and redirects every explicit flow entry to it. */
    public static TaskNode insertBefore(TaskGraph task, TaskNode target, String commandId,
                                           Map<String, String> params) {
        if (!canInsertBefore(task, target) || commandId == null || commandId.isBlank()) {
            return null;
        }
        TaskNode inserted = new TaskNode(commandId);
        inserted.params.putAll(params == null ? Map.of() : params);
        inserted.onSuccess = target.id;
        inserted.onFailure = target.id;

        for (TaskNode source : task.nodes) {
            if (source == null || source == target) {
                continue;
            }
            if (target.id.equals(source.onSuccess)) {
                removeCableAnchor(task, TaskCableAnchor.key("success", source.id, target.id));
                source.onSuccess = inserted.id;
            }
            if (target.id.equals(source.onFailure)) {
                removeCableAnchor(task, TaskCableAnchor.key("failure", source.id, target.id));
                source.onFailure = inserted.id;
            }
        }

        int targetIndex = task.indexOf(target);
        placeBefore(task, target, inserted);
        task.nodes.add(targetIndex, inserted);
        return inserted;
    }

    /** Inserts an optional follow-up while preserving the target's former success destination. */
    public static TaskNode insertAfter(TaskGraph task, TaskNode target, String commandId,
                                          Map<String, String> params) {
        if (!canInsertAfter(task, target) || commandId == null || commandId.isBlank()) {
            return null;
        }
        TaskNode formerSuccess = target.onSuccess == null
                ? nextSequentialNode(task, task.indexOf(target))
                : task.nodeById(target.onSuccess);
        TaskNode inserted = new TaskNode(commandId);
        inserted.params.putAll(params == null ? Map.of() : params);
        inserted.onSuccess = formerSuccess == null ? null : formerSuccess.id;
        inserted.onFailure = formerSuccess == null ? null : formerSuccess.id;
        if (target.onSuccess != null && formerSuccess != null) {
            removeCableAnchor(task, TaskCableAnchor.key("success", target.id, formerSuccess.id));
        }
        if (target.showsDone()) {
            // A card that carries on either way has one wire out of it, and the new step goes on
            // that wire. Moving only Success would leave Fail behind on the old card, split the
            // pin the player joined, and send a failure somewhere the canvas no longer showed.
            if (target.onFailure != null) {
                removeCableAnchor(task,
                        TaskCableAnchor.key("failure", target.id, target.onFailure));
            }
            target.onFailure = inserted.id;
            target.failureInputPort = target.successInputPort;
        }
        target.onSuccess = inserted.id;

        int targetIndex = task.indexOf(target);
        placeAfter(task, target, inserted);
        task.nodes.add(targetIndex + 1, inserted);
        return inserted;
    }

    private static void removeCableAnchor(TaskGraph task, String key) {
        if (task != null && task.cableAnchors != null) {
            task.cableAnchors.remove(key);
        }
    }

    // --- Success and Fail as one Done pin ------------------------------------------------------
    //
    // A card that carries on the same way however it ends is wired with both edges pointing at the
    // same card; that is what "either way" has always looked like in a saved task. Joining only
    // changes how the canvas draws it - one Done pin and one cable - so these rules exist to keep
    // the two edges together while the pin is joined. The cable is the Success cable: same key,
    // same routing points, so a joined card keeps the shape its Success cable was given.

    /** What asking to join a card's Success and Fail came to. */
    public enum JoinResult {
        /** The card now shows one Done pin. */
        JOINED,
        /** Success and Fail lead to different cards, so nothing changed. */
        LEAD_APART,
        /** A START, a clock or a pulse card: there is no Success and Fail to join. */
        NO_OUTCOMES
    }

    /**
     * Draws a card's Success and Fail as one Done pin.
     *
     * <p>Only a join that loses nothing is made. Two unwired pins join as they are, and one wired
     * pin lends its wire to the other, because "carry on to that card whichever way it ends" is
     * what the player asked for. Two pins wired to different cards are refused rather than settled
     * by dropping one: that would delete a wire the player drew, as a side effect of a tick in a
     * menu.</p>
     */
    public static JoinResult joinOutcomes(TaskGraph task, TaskNode node) {
        if (node == null || !node.hasOutcomes()) {
            return JoinResult.NO_OUTCOMES;
        }
        if (node.onSuccess == null && node.onFailure != null) {
            // The Done cable is the Success cable, so the shape the Fail cable was given goes
            // with its wire.
            moveCableRoute(task, TaskCableAnchor.key("failure", node.id, node.onFailure),
                    TaskCableAnchor.key("success", node.id, node.onFailure));
            node.onSuccess = node.onFailure;
            node.successInputPort = node.failureInputPort;
        } else if (node.onFailure == null && node.onSuccess != null) {
            node.onFailure = node.onSuccess;
        } else if (!java.util.Objects.equals(node.onSuccess, node.onFailure)) {
            // Also clears a switch left on by hand, so it cannot join the card later, on its own,
            // the moment the two wires happen to meet.
            node.outcomesJoined = false;
            return JoinResult.LEAD_APART;
        }
        // Relay inputs are all alike, so the one the Success wire uses serves both.
        node.failureInputPort = node.successInputPort;
        node.outcomesJoined = true;
        return JoinResult.JOINED;
    }

    /**
     * Gives the card its Success and Fail pins back. Both keep the card the Done pin led to, so
     * nothing runs differently until one of them is rewired.
     *
     * @return whether the card had been joined
     */
    public static boolean splitOutcomes(TaskNode node) {
        if (node == null || !node.outcomesJoined) {
            return false;
        }
        node.outcomesJoined = false;
        return true;
    }

    /**
     * Wires a joined card's Done pin to a card: both edges, and both ports when it lands on a
     * relay, so the card carries on there whichever way it ends.
     *
     * @param relayPort the input the cable landed on, used only when {@code target} is a relay
     * @return whether anything changed
     */
    public static boolean wireDone(TaskGraph task, TaskNode node, TaskNode target, int relayPort) {
        if (node == null || target == null || target.id == null) {
            return false;
        }
        boolean changed = !target.id.equals(node.onSuccess) || !target.id.equals(node.onFailure);
        if (node.onSuccess != null && !node.onSuccess.equals(target.id)) {
            removeCableAnchor(task, TaskCableAnchor.key("success", node.id, node.onSuccess));
        }
        if (node.onFailure != null && !node.onFailure.equals(target.id)) {
            removeCableAnchor(task, TaskCableAnchor.key("failure", node.id, node.onFailure));
        }
        node.onSuccess = target.id;
        node.onFailure = target.id;
        if (target.isSignalRelayNode()) {
            changed |= node.successInputPort != relayPort;
            node.successInputPort = relayPort;
        }
        node.failureInputPort = node.successInputPort;
        return changed;
    }

    /**
     * Cuts a joined card's Done cable, which is both of its edges.
     *
     * @return whether there was a cable to cut
     */
    public static boolean cutDone(TaskGraph task, TaskNode node) {
        if (node == null || node.onSuccess == null && node.onFailure == null) {
            return false;
        }
        if (node.onSuccess != null) {
            removeCableAnchor(task, TaskCableAnchor.key("success", node.id, node.onSuccess));
        }
        if (node.onFailure != null) {
            removeCableAnchor(task, TaskCableAnchor.key("failure", node.id, node.onFailure));
        }
        node.onSuccess = null;
        node.onFailure = null;
        return true;
    }

    /**
     * Makes a loaded card's Done pin agree with its wiring.
     *
     * <p>Only reachable by hand - a shared file edited in a text editor, or a card that has since
     * become a pulse card. A joined card whose edges disagree is split rather than mended by
     * dropping one of them, because the edges are what runs and the pin is only how they look.</p>
     */
    public static void normalizeOutcomes(TaskNode node) {
        if (node == null || !node.outcomesJoined) {
            return;
        }
        if (node.showsDone()) {
            node.failureInputPort = node.successInputPort;
        } else {
            node.outcomesJoined = false;
        }
    }

    private static void moveCableRoute(TaskGraph task, String fromKey, String toKey) {
        if (task == null || task.cableAnchors == null) {
            return;
        }
        TaskCableRoute route = task.cableAnchors.remove(fromKey);
        if (route != null) {
            task.cableAnchors.putIfAbsent(toKey, route);
        }
    }

    /** Deep functional snapshot used by Lune's one-step undo while preserving the TaskGraph object. */
    public static TaskGraph copy(TaskGraph source) {
        if (source == null) {
            return null;
        }
        TaskGraph copy = new TaskGraph(source.name);
        copy.onWhile = source.onWhile;
        if (source.cableAnchors != null) {
            source.cableAnchors.forEach((key, route) -> {
                if (route != null) {
                    copy.cableAnchors.put(key, route.copy());
                }
            });
        }
        if (source.nodes != null) {
            for (TaskNode node : source.nodes) {
                copy.nodes.add(copyNode(node));
            }
        }
        copyAnnotations(source, copy);
        return copy;
    }

    /** Notes and frames are part of the task, so a snapshot that dropped them would lose work. */
    private static void copyAnnotations(TaskGraph source, TaskGraph target) {
        target.notes = new ArrayList<>();
        target.groups = new ArrayList<>();
        if (source.notes != null) {
            for (TaskNote note : source.notes) {
                if (note != null) {
                    TaskNote copied = note.copy();
                    copied.id = note.id;
                    target.notes.add(copied);
                }
            }
        }
        if (source.groups != null) {
            for (TaskGroup group : source.groups) {
                if (group != null) {
                    TaskGroup copied = group.copy();
                    copied.id = group.id;
                    target.groups.add(copied);
                }
            }
        }
    }

    /** Restores matching node objects in place so a running TaskRunner keeps valid references. */
    public static boolean restore(TaskGraph target, TaskGraph snapshot) {
        if (target == null || snapshot == null || target.nodes == null || snapshot.nodes == null) {
            return false;
        }
        Map<String, TaskNode> liveById = new LinkedHashMap<>();
        for (TaskNode node : target.nodes) {
            if (node != null && node.id != null) {
                liveById.put(node.id, node);
            }
        }
        List<TaskNode> restored = new ArrayList<>();
        for (TaskNode saved : snapshot.nodes) {
            if (saved == null) {
                continue;
            }
            TaskNode live = liveById.get(saved.id);
            if (live == null) {
                live = copyNode(saved);
            } else {
                copyNodeFields(saved, live);
            }
            restored.add(live);
        }
        target.name = snapshot.name;
        target.onWhile = snapshot.onWhile;
        target.cableAnchors = new LinkedHashMap<>();
        if (snapshot.cableAnchors != null) {
            snapshot.cableAnchors.forEach((key, route) -> {
                if (route != null) {
                    target.cableAnchors.put(key, route.copy());
                }
            });
        }
        target.nodes.clear();
        target.nodes.addAll(restored);
        copyAnnotations(snapshot, target);
        return true;
    }

    private static boolean editableTarget(TaskGraph task, TaskNode target) {
        return task != null && task.nodes != null && target != null
                && target.id != null && task.nodes.contains(target)
                && !target.isSourceNode() && !isMonitorOnly(task, target);
    }

    private static boolean reaches(TaskGraph task, TaskNode current, TaskNode target,
                                   Set<TaskNode> visited) {
        if (current == null) {
            return false;
        }
        if (current == target || target.id != null && target.id.equals(current.id)) {
            return true;
        }
        if (!visited.add(current)) {
            return false;
        }
        for (TaskNode next : outgoing(task, current)) {
            if (reaches(task, next, target, visited)) {
                return true;
            }
        }
        return false;
    }

    private static void addIfPresent(List<TaskNode> nodes, TaskNode node) {
        if (node != null && !nodes.contains(node)) {
            nodes.add(node);
        }
    }

    private static void placeBefore(TaskGraph task, TaskNode target, TaskNode inserted) {
        if (target.editorX == null || target.editorY == null) {
            return;
        }
        int oldX = target.editorX;
        int laneY = target.editorY;
        shiftLane(task, oldX, laneY);
        inserted.editorX = oldX;
        inserted.editorY = laneY;
    }

    private static void placeAfter(TaskGraph task, TaskNode target, TaskNode inserted) {
        if (target.editorX == null || target.editorY == null) {
            return;
        }
        int newX = target.editorX + EDITOR_STEP_X;
        int laneY = target.editorY;
        shiftLane(task, newX, laneY);
        inserted.editorX = newX;
        inserted.editorY = laneY;
    }

    private static void shiftLane(TaskGraph task, int fromX, int laneY) {
        for (TaskNode node : task.nodes) {
            if (node != null && node.editorX != null && node.editorY != null
                    && node.editorX >= fromX && Math.abs(node.editorY - laneY) < 60) {
                node.editorX += EDITOR_STEP_X;
            }
        }
    }

    private static TaskNode copyNode(TaskNode source) {
        if (source == null) {
            return null;
        }
        TaskNode copy = new TaskNode();
        copyNodeFields(source, copy);
        return copy;
    }

    private static void copyNodeFields(TaskNode source, TaskNode target) {
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
                            new TaskDataLink(link.sourceNodeId, link.sourcePort));
                }
            });
        }
        target.observedNodeId = source.observedNodeId;
        target.repeat = source.repeat;
        target.onSuccess = source.onSuccess;
        target.onFailure = source.onFailure;
        target.onWhile = source.onWhile;
        target.whileVisible = source.whileVisible;
        target.outcomesJoined = source.outcomesJoined;
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
            for (TaskSignalLink link : source.signalLinks) {
                if (link != null) {
                    target.signalLinks.add(new TaskSignalLink(link.outputPort,
                            link.targetNodeId, link.targetPort));
                }
            }
        }
        target.alwaysIntervalSeconds = source.alwaysIntervalSeconds;
        target.editorX = source.editorX;
        target.editorY = source.editorY;
        // Carried here, unlike in TaskNode.copy: this is the same card restored, and an undo that
        // silently cleared every breakpoint would be an undo of something nobody did.
        target.breakpoint = source.breakpoint;
    }
}
