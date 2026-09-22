package com.etka.lune.waypoint.external;

import java.util.List;

/**
 * Another mod's list of places, read but never written.
 *
 * <p>Waystones, JourneyMap and Xaero's Minimap each keep their own waypoints, and a player who has
 * been naming places in one of them for a month should not have to type them all into Lune. So
 * the Waypoints tab shows each mod's list beside Lune's own, walks to an entry, or copies it
 * across - and that is the whole relationship. Lune never writes into another mod's list, and it
 * keeps no copy of one: whatever is not copied across is read fresh every time it is shown.</p>
 *
 * <p>None of these mods is a dependency. A source answers {@link #available()} false when its mod
 * is not installed, and also when it is installed but has changed shape underneath Lune's hooks;
 * that second case is logged once, so a mod update shows up as a line in the log rather than as a
 * mysteriously empty list.</p>
 */
public interface ExternalWaypointSource {

    /** A stable identifier for the panel's state and the log. Never shown to the player. */
    String id();

    /** The mod's name as the player knows it. */
    String label();

    /** Whether the mod is installed and Lune's hooks into it resolved. Cheap enough to ask every frame. */
    boolean available();

    /**
     * Everything the mod currently lists, in no particular order. Never throws, and empty rather
     * than null outside a world.
     */
    List<ExternalWaypoint> list();
}
