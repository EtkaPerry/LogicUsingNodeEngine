package com.etka.lune.task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared task-graph operations for the Self Preservation monitor.
 *
 * <p>The editor, mascot and any future task import assistant must agree on what "protected"
 * means. Keeping that decision here prevents one UI from merely spotting a command with the right
 * name while another checks whether it is actually wired into the task.</p>
 */
public final class TaskSafety {

    public static final String COMMAND_ID = "self_preservation";

    private static final Map<String, String> DEFAULT_PARAMS = Map.ofEntries(
            Map.entry("protect_air", "true"),
            Map.entry("air_compare", "At most"),
            Map.entry("air_value", "120"),
            Map.entry("protect_lava", "true"),
            Map.entry("protect_fall", "true"),
            Map.entry("fall_threshold", "10"),
            Map.entry("protect_monsters", "true"),
            Map.entry("monster_compare", "At most"),
            Map.entry("monster_distance", "8"),
            Map.entry("protect_health", "true"),
            Map.entry("health_compare", "At most"),
            Map.entry("health_value", "8")
    );

    private TaskSafety() {}

    /**
     * True when the task already protects itself, however the player wired it.
     *
     * <p>The question is whether a run reaches Self Preservation at all, not whether it hangs off
     * one particular pin. A guard on a While pin, on an Always fan-out, on a Button's pulse or
     * simply on the Success edge of the step before it all do the job, and offering to add a
     * second one because the wire is not the shape Lune would have chosen is just noise.</p>
     */
    public static boolean hasMonitor(TaskGraph task) {
        return !connectedGuards(task).isEmpty();
    }

    /**
     * Every Self Preservation card a run would actually reach, in task order.
     *
     * <p>Being powered is not enough on its own: a card dropped on the canvas with no wires at all
     * can still be first in list order, and treating that as protection would silently stop Lune
     * from offering the guard the player never finished connecting.</p>
     */
    public static List<TaskNode> connectedGuards(TaskGraph task) {
        List<TaskNode> guards = new ArrayList<>();
        if (task == null || task.nodes == null) {
            return guards;
        }
        Set<TaskNode> powered = TaskWiring.poweredNodes(task);
        for (TaskNode node : task.nodes) {
            if (node != null && COMMAND_ID.equals(node.commandId) && powered.contains(node)
                    && TaskWiring.hasIncomingConnection(task, node)) {
                guards.add(node);
            }
        }
        return guards;
    }

    /** True when this particular Self Preservation card is wired in and will really run. */
    public static boolean isConnectedGuard(TaskGraph task, TaskNode node) {
        return node != null && connectedGuards(task).contains(node);
    }

    /**
     * Adds one default global monitor without disturbing any existing flow or While wires.
     *
     * <p>An unconnected Self Preservation command is deliberately not reused: it may be an
     * ordinary step the player placed intentionally. The new node is metadata-only because the
     * Always source points at it, so {@link com.etka.lune.bot.task.TaskRunner} skips it in the
     * sequential flow and ticks it beside every real step.</p>
     *
     * @return true when the task was changed
     */
    public static boolean installDefaultMonitor(TaskGraph task) {
        if (task == null || task.nodes == null || hasMonitor(task)) {
            return false;
        }

        TaskNode always = task.nodes.stream()
                .filter(node -> node != null && node.isClockNode())
                .findFirst()
                .orElse(null);
        if (always == null) {
            int monitorY = task.nodes.stream()
                    .filter(node -> node != null && node.editorY != null)
                    .mapToInt(node -> node.editorY)
                    .max()
                    .orElse(0) + 120;
            always = new TaskNode(TaskNode.ALWAYS_COMMAND);
            always.editorX = 24;
            always.editorY = monitorY;
            task.nodes.add(0, always);
        }
        if (always.alwaysTargets == null) {
            always.alwaysTargets = new LinkedHashSet<>();
        }

        TaskNode guard = new TaskNode(COMMAND_ID);
        guard.params.putAll(DEFAULT_PARAMS);
        guard.repeat = 0;
        guard.editorX = always.editorX == null ? 184 : always.editorX + 160;
        guard.editorY = always.editorY == null ? 24 : always.editorY;

        int alwaysIndex = task.nodes.indexOf(always);
        task.nodes.add(Math.max(0, alwaysIndex + 1), guard);
        always.alwaysTargets.add(guard.id);
        return true;
    }

    /**
     * True when the node is attached as a live-beside-the-work monitor, on a While pin or an
     * Always fan-out.
     *
     * <p>Narrower than {@link #isConnectedGuard}, and deliberately so: this is the shape whose
     * repeat box is not a lifetime, so it is the shape whose card is normalised to x∞. A guard
     * sitting on an ordinary Success edge is an ordinary step, and how many times it runs there
     * is the player's decision.</p>
     */
    public static boolean isMonitorNode(TaskGraph task, TaskNode node) {
        if (task == null || node == null || !COMMAND_ID.equals(node.commandId)) {
            return false;
        }
        if (node.id != null && node.id.equals(task.onWhile)) {
            return true;
        }
        for (TaskNode source : task.nodes) {
            if (source == null) {
                continue;
            }
            if (node.id != null && node.id.equals(source.onWhile)) {
                return true;
            }
            if (source.isClockNode() && source.alwaysTargets != null
                    && source.alwaysTargets.contains(node.id)) {
                return true;
            }
        }
        return false;
    }

}
