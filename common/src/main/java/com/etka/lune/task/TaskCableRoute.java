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
