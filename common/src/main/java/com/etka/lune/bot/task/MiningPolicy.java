package com.etka.lune.bot.task;

import java.util.List;

/** Safe target-ordering tactics shared by every non-tree MineTask caller. */
public final class MiningPolicy {

    public static final String WORKSITE_FIRST = "worksite-first";
    public static final String NEAREST_VISIBLE = "nearest-visible";
    public static final String LEVEL_FIRST = "level-first";
    public static final List<String> ACTIONS = List.of(WORKSITE_FIRST, NEAREST_VISIBLE, LEVEL_FIRST);
    public static final String DEFAULT = WORKSITE_FIRST;

    private MiningPolicy() {}

    /** Lower scores are attempted first; eligibility remains with MineTask and Vision. */
    public static double levelFirstScore(int workX, int workY, int workZ,
                                         int candidateX, int candidateY, int candidateZ) {
        int dx = candidateX - workX;
        int dy = candidateY - workY;
        int dz = candidateZ - workZ;
        return (double) dy * dy * 16.0 + (double) dx * dx + (double) dz * dz;
    }

    public static String amountBucket(int limit) {
        if (limit <= 0) {
            return "open";
        }
        if (limit <= 4) {
            return "few";
        }
        if (limit <= 16) {
            return "batch";
        }
        return "large";
    }

    public static String radiusBucket(int radius) {
        if (radius <= 12) {
            return "local";
        }
        if (radius <= 48) {
            return "area";
        }
        return "wide";
    }
}
