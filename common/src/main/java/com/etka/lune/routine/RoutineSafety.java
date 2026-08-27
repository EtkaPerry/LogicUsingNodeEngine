package com.etka.lune.routine;

import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Shared routine-graph operations for the Self Preservation monitor.
 *
 * <p>The editor, mascot and any future routine import assistant must agree on what "protected"
 * means. Keeping that decision here prevents one UI from merely spotting a command with the right
 * name while another checks whether it is actually wired into the routine.</p>
 */
public final class RoutineSafety {

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

    private RoutineSafety() {}

    /** True only when a Self Preservation node is connected as a While/Always monitor. */
    public static boolean hasMonitor(Routine routine) {
        if (routine == null || routine.nodes == null) {
            return false;
        }
        if (isMonitorNode(routine, routine.nodeById(routine.onWhile))) {
            return true;
        }
        for (RoutineNode source : routine.nodes) {
            if (source == null) {
                continue;
            }
            if (isMonitorNode(routine, routine.nodeById(source.onWhile))) {
                return true;
            }
            if (source.isAlwaysNode() && source.alwaysTargets != null) {
                for (String targetId : source.alwaysTargets) {
                    if (isMonitorNode(routine, routine.nodeById(targetId))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Adds one default global monitor without disturbing any existing flow or While wires.
     *
     * <p>An unconnected Self Preservation command is deliberately not reused: it may be an
     * ordinary step the player placed intentionally. The new node is metadata-only because the
     * Always source points at it, so {@link com.etka.lune.bot.task.RoutineTask} skips it in the
     * sequential flow and ticks it beside every real step.</p>
     *
     * @return true when the routine was changed
     */
    public static boolean installDefaultMonitor(Routine routine) {
        if (routine == null || routine.nodes == null || hasMonitor(routine)) {
            return false;
        }

        RoutineNode always = routine.nodes.stream()
                .filter(node -> node != null && node.isAlwaysNode())
                .findFirst()
                .orElse(null);
        if (always == null) {
            int monitorY = routine.nodes.stream()
                    .filter(node -> node != null && node.editorY != null)
                    .mapToInt(node -> node.editorY)
                    .max()
                    .orElse(0) + 120;
            always = new RoutineNode(RoutineNode.ALWAYS_COMMAND);
            always.editorX = 24;
            always.editorY = monitorY;
            routine.nodes.add(0, always);
        }
        if (always.alwaysTargets == null) {
            always.alwaysTargets = new LinkedHashSet<>();
        }

        RoutineNode guard = new RoutineNode(COMMAND_ID);
        guard.params.putAll(DEFAULT_PARAMS);
        guard.repeat = 0;
        guard.editorX = always.editorX == null ? 184 : always.editorX + 160;
        guard.editorY = always.editorY == null ? 24 : always.editorY;

        int alwaysIndex = routine.nodes.indexOf(always);
        routine.nodes.add(Math.max(0, alwaysIndex + 1), guard);
        always.alwaysTargets.add(guard.id);
        return true;
    }

    /** True when the node is actually attached as a protection monitor, not merely present. */
    public static boolean isMonitorNode(Routine routine, RoutineNode node) {
        if (routine == null || node == null || !COMMAND_ID.equals(node.commandId)) {
            return false;
        }
        if (node.id != null && node.id.equals(routine.onWhile)) {
            return true;
        }
        for (RoutineNode source : routine.nodes) {
            if (source == null) {
                continue;
            }
            if (node.id != null && node.id.equals(source.onWhile)) {
                return true;
            }
            if (source.isAlwaysNode() && source.alwaysTargets != null
                    && source.alwaysTargets.contains(node.id)) {
                return true;
            }
        }
        return false;
    }

}
