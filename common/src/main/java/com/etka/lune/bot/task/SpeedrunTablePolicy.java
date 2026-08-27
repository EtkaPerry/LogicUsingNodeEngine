package com.etka.lune.bot.task;

/**
 * Pure configuration for crafts that happen after the speedrun has moved away from its table.
 * Keeping this value separate makes the long-route recovery rule testable without loading the
 * Minecraft client classes used by {@link CraftTask}.
 */
public final class SpeedrunTablePolicy {

    public static final int DEFAULT_TABLE_SEARCH_RADIUS = 4;
    public static final int LONG_ROUTE_TABLE_SEARCH_RADIUS = 256;

    private SpeedrunTablePolicy() {}

    public static int searchRadius(boolean longRoute) {
        return longRoute ? LONG_ROUTE_TABLE_SEARCH_RADIUS : DEFAULT_TABLE_SEARCH_RADIUS;
    }
}
