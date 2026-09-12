package com.etka.lune.task;

import java.util.ArrayList;
import java.util.List;

/**
 * The drawn shape of one task cable: the two pins, plus whatever routing points the player added.
 *
 * <p>Every segment used to be its own cubic with horizontal control arms at <em>both</em> ends.
 * That is exactly right for a bare cable - it leaves the output pin sideways and enters the input
 * pin sideways - but applying it to each segment of a routed cable gave every segment its own
 * flourish. A point placed directly above another still bowed out sideways, and a segment running
 * leftwards looped back past its own start. Shaping a cable by hand produced handwriting instead
 * of the line the points described.</p>
 *
 * <p>The points are knots of one spline now. Each knot takes its direction from its neighbours and
 * every arm is scaled to the segment it belongs to, so a short segment cannot overshoot and
 * collinear points draw as a dead straight line. A cable with no routing points is left exactly as
 * it was - that horizontal sweep out of the pin is the whole look of an unrouted wire - but once a
 * point exists the cable aims at it, because second-guessing the angle somebody took the trouble to
 * place is how it ends up cursive again.</p>
 *
 * <p>Geometry rather than rendering, so it lives beside the route it draws and can be tested
 * without a screen.</p>
 */
public final class TaskCablePath {

    /** A point on the cable, in canvas coordinates. */
    public record Point(double x, double y) {}

    /** Half the segment: the tangent length that makes a spline hug its knots without bulging. */
    private static final double ARM_FRACTION = 0.5;

    /** Shortest cable the sampler will treat as having any length at all. */
    private static final double MINIMUM_LENGTH = 12;

    private final double[] xs;
    private final double[] ys;
    private final double[] dirX;
    private final double[] dirY;
    private final double[] segmentLength;
    private final double[] lengthBefore;
    private final double length;
    /** A bare pin-to-pin cable keeps the original arm, which is not scaled to its own length. */
    private final double pinArm;
    private final boolean bare;

    private TaskCablePath(double[] xs, double[] ys, double pinArm, boolean bare) {
        this.xs = xs;
        this.ys = ys;
        this.pinArm = pinArm;
        this.bare = bare;
        int knots = xs.length;
        this.dirX = new double[knots];
        this.dirY = new double[knots];
        this.segmentLength = new double[knots - 1];
        this.lengthBefore = new double[knots];

        for (int i = 0; i < knots; i++) {
            // Interior knots follow the line through their two neighbours, which is what makes
            // collinear points draw straight. The ends of a *bare* cable leave horizontally,
            // because that sweep out of the pin is the whole look of an unrouted wire; the ends of
            // a routed one aim at the point the player put there. Once somebody has taken the
            // trouble to place a point, second-guessing the angle they wanted is how a cable ends
            // up cursive.
            double dx;
            double dy;
            if (i > 0 && i < knots - 1) {
                dx = xs[i + 1] - xs[i - 1];
                dy = ys[i + 1] - ys[i - 1];
            } else if (bare) {
                dx = 1;
                dy = 0;
            } else {
                int towards = i == 0 ? 1 : knots - 2;
                dx = i == 0 ? xs[towards] - xs[i] : xs[i] - xs[towards];
                dy = i == 0 ? ys[towards] - ys[i] : ys[i] - ys[towards];
            }
            double magnitude = Math.hypot(dx, dy);
            dirX[i] = magnitude == 0 ? 1 : dx / magnitude;
            dirY[i] = magnitude == 0 ? 0 : dy / magnitude;
        }
        double total = 0;
        for (int i = 0; i < knots - 1; i++) {
            lengthBefore[i] = total;
            segmentLength[i] = Math.hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i]);
            total += segmentLength[i];
        }
        lengthBefore[knots - 1] = total;
        this.length = Math.max(MINIMUM_LENGTH, total);
    }

    /** The horizontal arm a bare cable leaves its pin with. */
    public static double pinTangent(int x1, int x2) {
        return Math.max(28, Math.abs(x2 - x1) * 0.45);
    }

    public static TaskCablePath of(int x1, int y1, int x2, int y2, TaskCableRoute route) {
        List<TaskCableAnchor> points = new ArrayList<>();
        if (route != null && route.points != null) {
            for (TaskCableAnchor point : route.points) {
                if (point != null) {
                    points.add(point);
                }
            }
        }
        double[] xs = new double[points.size() + 2];
        double[] ys = new double[points.size() + 2];
        xs[0] = x1;
        ys[0] = y1;
        for (int i = 0; i < points.size(); i++) {
            xs[i + 1] = points.get(i).x;
            ys[i + 1] = points.get(i).y;
        }
        xs[xs.length - 1] = x2;
        ys[ys.length - 1] = y2;
        // Three times the arm, because a cubic Bezier's control point is a third of the way along
        // its Hermite tangent - that equivalence is what keeps a bare cable looking unchanged.
        return new TaskCablePath(xs, ys, pinTangent(x1, x2) * 3, points.isEmpty());
    }

    public double length() {
        return length;
    }

    public int segments() {
        return segmentLength.length;
    }

    /** The point at 0..1 along the cable, measured by arc length rather than by segment. */
    public Point at(double t) {
        int segment = segmentAt(t);
        double span = segmentLength[segment];
        double along = Math.clamp(t, 0.0, 1.0) * length - lengthBefore[segment];
        return pointOn(segment, span <= 0 ? 0 : Math.clamp(along / span, 0.0, 1.0));
    }

    /** Which segment a position along the cable falls in - the insertion index for a new point. */
    public int segmentAt(double t) {
        double along = Math.clamp(t, 0.0, 1.0) * length;
        for (int i = 0; i < segmentLength.length - 1; i++) {
            if (along <= lengthBefore[i] + segmentLength[i]) {
                return i;
            }
        }
        return segmentLength.length - 1;
    }

    private Point pointOn(int segment, double u) {
        // A bare cable's arm is deliberately not scaled to its own length: that constant is what
        // gives an unrouted wire the familiar node-editor sweep.
        double arm = bare ? pinArm : segmentLength[segment] * ARM_FRACTION;
        double u2 = u * u;
        double u3 = u2 * u;
        double h00 = 2 * u3 - 3 * u2 + 1;
        double h10 = u3 - 2 * u2 + u;
        double h01 = -2 * u3 + 3 * u2;
        double h11 = u3 - u2;
        return new Point(
                h00 * xs[segment] + h10 * dirX[segment] * arm
                        + h01 * xs[segment + 1] + h11 * dirX[segment + 1] * arm,
                h00 * ys[segment] + h10 * dirY[segment] * arm
                        + h01 * ys[segment + 1] + h11 * dirY[segment + 1] * arm);
    }
}
