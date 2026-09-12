package com.etka.lune.task;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered routing points for one task cable.
 *
 * <p>Points stay in cable traversal order. Pulling a segment inserts a new point into that
 * segment, so untouched points and line sections do not jump when the player shapes another part
 * of the cable. The list is part of the task JSON, so it is also copied by undo/redo and preserved
 * by task sharing.</p>
 */
public final class TaskCableRoute {

    public List<TaskCableAnchor> points = new ArrayList<>();

    public TaskCableRoute() {}

    public TaskCableRoute(TaskCableAnchor firstPoint) {
        if (firstPoint != null) {
            points.add(firstPoint);
        }
    }

    /** A return wire leaves the right pin, passes below the cards, then enters from the left. */
    public static TaskCableRoute returning(int x1, int y1, int x2, int y2, int bottom, int lane) {
        return returning(x1, y1, x2, y2, bottom, lane, 0);
    }

    /**
     * The last approach can be offset above or below the input. This keeps several return wires
     * readable even though they share the same logical In pin.
     */
    public static TaskCableRoute returning(int x1, int y1, int x2, int y2, int bottom,
                                           int lane, int entryOffset) {
        int clearance = 28 + lane;
        int gutter = Math.max(bottom, Math.max(y1, y2)) + clearance;
        TaskCableRoute route = new TaskCableRoute();
        route.points.add(new TaskCableAnchor(x1 + clearance, y1));
        route.points.add(new TaskCableAnchor(x1 + clearance + 12, gutter));
        route.points.add(new TaskCableAnchor(x2 - clearance - 12, gutter));
        route.points.add(new TaskCableAnchor(x2 - clearance, y2 + entryOffset));
        return route;
    }

    /** Inserts a point into the segment that was pulled, clamping stale indices safely. */
    public void insertPoint(int segmentIndex, TaskCableAnchor point) {
        if (point == null) {
            return;
        }
        if (points == null) {
            points = new ArrayList<>();
        }
        points.add(Math.clamp(segmentIndex, 0, points.size()), point);
    }

    public TaskCableRoute copy() {
        TaskCableRoute copy = new TaskCableRoute();
        if (points != null) {
            for (TaskCableAnchor point : points) {
                if (point != null) {
                    copy.points.add(point.copy());
                }
            }
        }
        return copy;
    }
}
