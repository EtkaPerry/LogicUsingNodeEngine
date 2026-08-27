package com.etka.lune.bot.task;

import java.util.List;

/** Safe A* search-effort tactics shared by every job that delegates movement to GotoTask. */
public final class MovementPolicy {

    public static final String BALANCED = "balanced-search";
    public static final String QUICK = "quick-search";
    public static final String THOROUGH = "thorough-search";
    public static final List<String> ACTIONS = List.of(BALANCED, QUICK, THOROUGH);
    public static final String DEFAULT = BALANCED;

    private MovementPolicy() {}

    public static double heuristicWeight(String action, double configuredWeight) {
        double base = Math.max(1.0, configuredWeight);
        return switch (action) {
            case QUICK -> base * 1.35;
            case THOROUGH -> Math.max(1.0, base * 0.75);
            default -> base;
        };
    }

    public static String distanceBucket(double heuristicDistance) {
        if (heuristicDistance <= 6.0) {
            return "near";
        }
        if (heuristicDistance <= 32.0) {
            return "medium";
        }
        return "far";
    }
}
