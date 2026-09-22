package com.etka.lune.waypoint.external;

import com.etka.lune.util.Lang;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;

import java.util.ArrayList;
import java.util.List;

/**
 * The half of the JourneyMap source that speaks JourneyMap's types.
 *
 * <p>Kept apart from {@link JourneyMapSource} so that class can be loaded on a game without
 * JourneyMap: nothing in its bytecode names an API type, so the verifier never goes looking for
 * one, and this class is only resolved when its method is actually called - which happens after
 * JourneyMap has handed over its API and therefore exists.</p>
 */
final class JourneyMapAccess {

    private JourneyMapAccess() {}

    static List<ExternalWaypoint> list(Object api, String sourceId, String fallbackDimension) {
        List<ExternalWaypoint> out = new ArrayList<>();
        for (Waypoint waypoint : ((IClientAPI) api).getAllWaypoints()) {
            String shown = waypoint.getName() == null ? "" : waypoint.getName().strip();
            if (shown.isEmpty()) {
                shown = Lang.get("lune.gui.waypoints.unnamed");
            }
            out.add(new ExternalWaypoint(sourceId, shown, waypoint.getX(), waypoint.getY(),
                    waypoint.getZ(), dimensionOf(waypoint, fallbackDimension), true));
        }
        return out;
    }

    /**
     * A JourneyMap waypoint may belong to several dimensions; the primary one is the one it was
     * made in. Written without a namespace it means the vanilla one, as everywhere else.
     */
    private static String dimensionOf(Waypoint waypoint, String fallback) {
        String primary = waypoint.getPrimaryDimension();
        if ((primary == null || primary.isBlank()) && waypoint.getDimensions() != null
                && !waypoint.getDimensions().isEmpty()) {
            primary = waypoint.getDimensions().first();
        }
        if (primary == null || primary.isBlank()) {
            return fallback;
        }
        return primary.contains(":") ? primary : "minecraft:" + primary;
    }
}
