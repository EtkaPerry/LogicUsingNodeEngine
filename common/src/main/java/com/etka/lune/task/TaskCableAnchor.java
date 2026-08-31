package com.etka.lune.task;

/**
 * An editor-only routing point for one task cable.
 *
 * <p>The coordinates are in the blueprint canvas' logical coordinate system, not screen pixels,
 * so zooming and panning do not change a saved cable route. Gson serialises this small value
 * directly as {@code {"x": ..., "y": ...}} inside the task JSON.</p>
 */
public final class TaskCableAnchor {

    public int x;
    public int y;

    public TaskCableAnchor() {}

    public TaskCableAnchor(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** Stable key for a single-port cable such as Success, Fail, While, Always or Observer. */
    public static String key(String kind, String sourceNodeId, String targetNodeId) {
        return kind + "|" + sourceNodeId + "|" + targetNodeId;
    }

    /** Stable key for a cable whose source and target each have a numbered/named port. */
    public static String key(String kind, String sourceNodeId, String sourcePort,
                             String targetNodeId, String targetPort) {
        return kind + "|" + sourceNodeId + "|" + sourcePort + "|"
                + targetNodeId + "|" + targetPort;
    }

    /** True when a saved cable key contains the id of a node being removed. */
    public static boolean referencesNode(String key, String nodeId) {
        if (key == null || nodeId == null) {
            return false;
        }
        for (String part : key.split("\\|", -1)) {
            if (nodeId.equals(part)) {
                return true;
            }
        }
        return false;
    }

    public TaskCableAnchor copy() {
        return new TaskCableAnchor(x, y);
    }
}
