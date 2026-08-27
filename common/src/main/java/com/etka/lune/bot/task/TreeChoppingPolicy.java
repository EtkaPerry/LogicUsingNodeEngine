package com.etka.lune.bot.task;

import java.util.List;

/**
 * Safe candidate-ranking tactics available to the tree-chopping skill learner.
 *
 * <p>Every tactic still receives only logs accepted by MineTask's existing connected-tree and
 * Vision filters. Learning may change the order in which visible logs are attempted; it cannot
 * reveal a hidden log, disable safety, or invent a new game action.</p>
 */
public final class TreeChoppingPolicy {

    public static final String TRUNK_FIRST = "trunk-first";
    public static final String NEAREST_CUT = "nearest-cut";
    public static final String OUTER_FIRST = "outer-first";
    public static final List<String> ACTIONS = List.of(TRUNK_FIRST, NEAREST_CUT, OUTER_FIRST);
    public static final String DEFAULT = TRUNK_FIRST;

    private TreeChoppingPolicy() {}

    /** Lower scores are attempted first. Inputs are block coordinates. */
    public static double candidateScore(String action,
                                        int anchorX, int anchorY, int anchorZ,
                                        int workX, int workY, int workZ,
                                        int candidateX, int candidateY, int candidateZ,
                                        int maxHorizontalRadiusSquared) {
        double workDistance = squared(candidateX - workX)
                + squared(candidateY - workY)
                + squared(candidateZ - workZ);
        int dx = candidateX - anchorX;
        int dz = candidateZ - anchorZ;
        double radial = squared(dx) + squared(dz);
        double height = Math.max(0, candidateY - anchorY);

        return switch (action) {
            case NEAREST_CUT -> workDistance;
            case OUTER_FIRST -> Math.max(0.0, maxHorizontalRadiusSquared - radial) * 8.0
                    + workDistance * 0.5 + height;
            default -> radial * 8.0 + height * 3.0 + workDistance * 0.25;
        };
    }

    public static String sizeBucket(int connectedLogs) {
        if (connectedLogs <= 7) {
            return "small";
        }
        if (connectedLogs <= 18) {
            return "large";
        }
        return "giant";
    }

    public static String distanceBucket(double distance) {
        if (distance <= 4.5) {
            return "near";
        }
        if (distance <= 12.0) {
            return "walk";
        }
        return "far";
    }

    public static String toolBucket(float destroySpeed) {
        if (destroySpeed <= 1.1F) {
            return "hand";
        }
        if (destroySpeed < 6.0F) {
            return "basic-tool";
        }
        return "fast-tool";
    }

    public static String phase(int connectedLogs, float destroySpeed, double distance) {
        return "size=" + sizeBucket(connectedLogs)
                + ";tool=" + toolBucket(destroySpeed)
                + ";approach=" + distanceBucket(distance);
    }

    private static double squared(int value) {
        return (double) value * value;
    }
}
