package com.etka.lune.training;

import com.etka.lune.util.Lang;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.TaskPower;
import com.etka.lune.task.TaskSignalLink;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** A finite illustration of explicit wiring, never a world simulation or a task execution.
 * Each source sends once; each card forwards once, so loops visibly close and then stop.
 * Timers use a short, labelled wait; counters retain pulse counts in the preview session.
 */
public record TrainingPreview(Map<String, Integer> nodes, Map<String, Integer> wires,
                              Map<String, Integer> nodeEnds, Map<String, String> notes, int stages) {
    public static final long STAGE_MILLIS = 850;

    public static TrainingPreview trace(TaskGraph graph, boolean failure) {
        return trace(graph, failure, new LinkedHashMap<>());
    }

    public static TrainingPreview trace(TaskGraph graph, boolean failure, Map<String, Integer> counters) {
        Map<String, Integer> nodeEnds = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        Map<String, Integer> nodes = new LinkedHashMap<>();
        Map<String, Integer> wires = new LinkedHashMap<>();
        ArrayDeque<TaskNode> pending = new ArrayDeque<>();
        if (graph != null) {
            for (TaskNode node : graph.nodes) {
                if (node != null && node.isSourceNode()) {
                    nodes.put(node.id, 0);
                    pending.add(node);
                }
            }
        }
        int stages = nodes.isEmpty() ? 1 : 2;
        while (!pending.isEmpty()) {
            TaskNode node = pending.removeFirst();
            int stage = nodes.get(node.id);
            boolean forward = true;
            if (node.isTimerNode()) {
                int seconds = parameter(node, "seconds", 5, 0, 3600);
                if (seconds > 0) stage += 2;
                notes.put(node.id, Lang.get("lune.gui.blueprint.preview_timer", seconds));
            } else if (node.isCounterNode()) {
                int target = parameter(node, "count", 3, 1, 1_000_000);
                int received = counters.getOrDefault(node.id, 0) + 1;
                forward = received >= target;
                notes.put(node.id, Lang.get("lune.gui.blueprint.preview_counter", received, target,
                        Lang.get(forward ? "lune.gui.blueprint.preview_forwarded"
                                : "lune.gui.blueprint.preview_send_another")));
                counters.put(node.id, forward ? 0 : received);
            }
            nodeEnds.put(node.id, stage + 1);
            stages = Math.max(stages, stage + 2);
            if (!forward) continue;
            if (node.isClockNode()) {
                if (node.alwaysTargets != null) for (String target : node.alwaysTargets) {
                    visit(graph, node, target, TaskPower.ALWAYS, 0, stage, nodes, wires, pending);
                }
            } else if (node.isPulseNode()) {
                if (node.signalLinks != null) for (TaskSignalLink link : node.signalLinks) {
                    if (link != null) visit(graph, node, link.targetNodeId, TaskPower.SIGNAL,
                            link.outputPort, stage, nodes, wires, pending);
                }
            } else {
                int kind = failure && !node.isStartNode() ? TaskPower.FAILURE : TaskPower.SUCCESS;
                visit(graph, node, kind == TaskPower.FAILURE ? node.onFailure : node.onSuccess,
                        kind, 0, stage, nodes, wires, pending);
                if (!node.isStartNode()) visit(graph, node, node.onWhile, TaskPower.WHILE,
                        0, stage, nodes, wires, pending);
            }
            stages = Math.max(stages, stage + 2);
        }
        return new TrainingPreview(Map.copyOf(nodes), Map.copyOf(wires),
                Map.copyOf(nodeEnds), Map.copyOf(notes), stages);
    }

    private static int parameter(TaskNode node, String key, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(node.params.getOrDefault(key, "" + fallback)), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void visit(TaskGraph graph, TaskNode from, String targetId, int kind, int port,
                              int stage, Map<String, Integer> nodes, Map<String, Integer> wires,
                              ArrayDeque<TaskNode> pending) {
        TaskNode target = graph.nodeById(targetId);
        if (target == null) return;
        wires.put(TaskPower.wire(from.id, kind, port, target.id), stage);
        if (nodes.putIfAbsent(target.id, stage + 1) == null) pending.add(target);
    }

    public double wireProgress(TaskNode from, int kind, int port, TaskNode to, long elapsed) {
        Integer stage = wires.get(TaskPower.wire(from.id, kind, port, to.id));
        if (stage == null || elapsed < 0) return -1;
        double progress = elapsed / (double) STAGE_MILLIS - stage;
        return progress >= 0 && progress < 1 ? progress : -1;
    }

    public boolean nodeLit(TaskNode node, long elapsed) {
        Integer stage = nodes.get(node.id);
        return stage != null && elapsed >= stage * STAGE_MILLIS
                && elapsed < nodeEnds.getOrDefault(node.id, stage + 1) * STAGE_MILLIS;
    }

    public boolean finished(long elapsed) {
        return elapsed >= stages * STAGE_MILLIS;
    }
}
