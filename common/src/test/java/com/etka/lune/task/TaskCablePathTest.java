package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shape a cable draws once the player has put routing points on it. */
class TaskCablePathTest {

    private static TaskCableRoute route(int... coordinates) {
        TaskCableRoute route = new TaskCableRoute();
        for (int i = 0; i < coordinates.length; i += 2) {
            route.points.add(new TaskCableAnchor(coordinates[i], coordinates[i + 1]));
        }
        return route;
    }

    /** The furthest any sample strays from the straight line between the two pins. */
    private static double maximumBow(TaskCablePath path, double x1, double y1,
                                     double x2, double y2) {
        double worst = 0;
        for (int i = 0; i <= 200; i++) {
            TaskCablePath.Point point = path.at(i / 200.0);
            double dx = x2 - x1;
            double dy = y2 - y1;
            double t = Math.clamp(((point.x() - x1) * dx + (point.y() - y1) * dy)
                    / (dx * dx + dy * dy), 0.0, 1.0);
            worst = Math.max(worst, Math.hypot(point.x() - (x1 + t * dx),
                    point.y() - (y1 + t * dy)));
        }
        return worst;
    }

    /**
     * The bug this class was written for. Placing points in a straight line and expecting a
     * straight line is the obvious thing to try, and every segment used to bow sideways because it
     * carried a horizontal control arm at both ends - so a hand-shaped cable came out cursive.
     */
    @Test
    void collinearRoutingPointsDrawAStraightLine() {
        TaskCablePath path = TaskCablePath.of(0, 0, 400, 400, route(100, 100, 200, 200, 300, 300));
        assertTrue(maximumBow(path, 0, 0, 400, 400) < 1.0,
                "a cable routed along a straight line should draw as one, not as handwriting");
    }

    /** A point placed directly above another used to bulge sideways by the whole pin arm. */
    @Test
    void aVerticalRunDoesNotBulgeSideways() {
        TaskCablePath path = TaskCablePath.of(200, 0, 200, 300, route(200, 100, 200, 200));
        double widest = 0;
        for (int i = 0; i <= 200; i++) {
            widest = Math.max(widest, Math.abs(path.at(i / 200.0).x() - 200));
        }
        assertTrue(widest < 1.0, "a vertical run bulged sideways by " + widest + " pixels");
    }

    /** A segment that runs backwards used to loop back past its own start. */
    @Test
    void aBackwardSegmentDoesNotLoopPastItsOwnStart() {
        TaskCablePath path = TaskCablePath.of(400, 0, 0, 0, route(300, 120, 100, 120));
        double furthestRight = 0;
        for (int i = 0; i <= 200; i++) {
            furthestRight = Math.max(furthestRight, path.at(i / 200.0).x());
        }
        assertTrue(furthestRight <= 400 + 1e-6,
                "the cable overshot its own start by " + (furthestRight - 400) + " pixels");
    }

    /** Whatever the shape, the cable still starts and ends exactly on its pins. */
    @Test
    void theCableMeetsBothPinsExactly() {
        for (TaskCableRoute shape : new TaskCableRoute[] {
                null, route(), route(150, 90), route(150, 90, 260, 30, 90, 240)}) {
            TaskCablePath path = TaskCablePath.of(10, 20, 330, 240, shape);
            assertEquals(10, path.at(0).x(), 1e-6);
            assertEquals(20, path.at(0).y(), 1e-6);
            assertEquals(330, path.at(1).x(), 1e-6);
            assertEquals(240, path.at(1).y(), 1e-6);
        }
    }

    /** Every routing point is actually passed through, not merely suggested to the curve. */
    @Test
    void theCablePassesThroughEveryPointThePlayerPlaced() {
        TaskCableRoute shape = route(150, 90, 260, 30, 90, 240);
        TaskCablePath path = TaskCablePath.of(10, 20, 330, 240, shape);
        for (TaskCableAnchor point : shape.points) {
            double closest = Double.POSITIVE_INFINITY;
            for (int i = 0; i <= 400; i++) {
                TaskCablePath.Point sample = path.at(i / 400.0);
                closest = Math.min(closest, Math.hypot(sample.x() - point.x, sample.y() - point.y));
            }
            assertTrue(closest < 1.5,
                    "the cable misses its own routing point by " + closest + " pixels");
        }
    }

    /**
     * A bare cable is the one shape that must not change: it is the familiar node-editor sweep,
     * and it is what every unrouted wire in every seeded job is drawn with.
     */
    @Test
    void aBareCableKeepsTheOriginalPinToPinSweep() {
        int x1 = 40;
        int y1 = 60;
        int x2 = 400;
        int y2 = 200;
        TaskCablePath path = TaskCablePath.of(x1, y1, x2, y2, null);
        double tangent = TaskCablePath.pinTangent(x1, x2);
        for (int i = 0; i <= 50; i++) {
            double t = i / 50.0;
            double u = 1 - t;
            double expectedX = u * u * u * x1 + 3 * u * u * t * (x1 + tangent)
                    + 3 * u * t * t * (x2 - tangent) + t * t * t * x2;
            double expectedY = u * u * u * y1 + 3 * u * u * t * y1
                    + 3 * u * t * t * y2 + t * t * t * y2;
            TaskCablePath.Point point = path.at(t);
            // Sampled by arc length rather than by curve parameter, so the two agree on where the
            // line is without agreeing on which t reaches it.
            assertTrue(nearCurve(path, expectedX, expectedY),
                    "a bare cable left the original curve at t=" + t
                            + " (" + point.x() + "," + point.y() + ")");
        }
    }

    private static boolean nearCurve(TaskCablePath path, double x, double y) {
        double closest = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= 400; i++) {
            TaskCablePath.Point sample = path.at(i / 400.0);
            closest = Math.min(closest, Math.hypot(sample.x() - x, sample.y() - y));
        }
        return closest < 1.0;
    }

    /** The segment index is what a pull uses to decide where a new point goes in the list. */
    @Test
    void segmentIndexTracksThePointListAlongTheCable() {
        TaskCablePath path = TaskCablePath.of(0, 0, 400, 0, route(100, 0, 200, 0, 300, 0));
        assertEquals(4, path.segments());
        assertEquals(0, path.segmentAt(0.0));
        assertEquals(0, path.segmentAt(0.1));
        assertEquals(1, path.segmentAt(0.35));
        assertEquals(2, path.segmentAt(0.6));
        assertEquals(3, path.segmentAt(1.0));
    }
}
