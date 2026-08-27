package com.etka.lune.bot.task;

import java.util.List;

/** Safe placement windows for jump-pillaring; all still rely on vanilla placement validation. */
public final class PillarPolicy {

    public static final String EARLY = "early-placement-window";
    public static final String BALANCED = "balanced-placement-window";
    public static final String APEX = "apex-placement-window";
    public static final List<String> ACTIONS = List.of(EARLY, BALANCED, APEX);
    public static final String DEFAULT = BALANCED;

    private PillarPolicy() {}

    public static double clearance(String action) {
        return switch (action) {
            case EARLY -> 0.35;
            case APEX -> 0.70;
            default -> 0.50;
        };
    }

    public static String heightBucket(int height) {
        if (height <= 1) return "one";
        if (height <= 3) return "short";
        return "tall";
    }
}
