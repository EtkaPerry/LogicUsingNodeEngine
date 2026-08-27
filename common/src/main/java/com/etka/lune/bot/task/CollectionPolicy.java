package com.etka.lune.bot.task;

import java.util.List;

/** Safe target-order tactics shared by crop harvesting and dropped-item sweeps. */
final class CollectionPolicy {

    static final String LOCAL_THEN_SITE = "local-then-worksite";
    static final String WORKSITE_FIRST = "worksite-cluster-first";
    static final String NEAREST_FIRST = "nearest-next-target";
    static final List<String> ACTIONS = List.of(LOCAL_THEN_SITE, WORKSITE_FIRST, NEAREST_FIRST);
    static final String DEFAULT = LOCAL_THEN_SITE;

    private CollectionPolicy() {}

    static boolean checksImmediatePocket(String action) {
        return !WORKSITE_FIRST.equals(action);
    }

    static boolean ranksFromPlayer(String action) {
        return NEAREST_FIRST.equals(action);
    }

    static String radiusBucket(int radius) {
        if (radius <= 8) {
            return "small";
        }
        if (radius <= 24) {
            return "medium";
        }
        return "wide";
    }
}
