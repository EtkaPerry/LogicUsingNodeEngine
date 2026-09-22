package com.etka.lune.waypoint.external;

import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The waypoints Xaero's Minimap holds for the world the player is standing in.
 *
 * <p>Xaero's files waypoints per world and per dimension, with any number of named sets inside
 * each; the current world is the one its own Waypoints screen opens on, and every set of it is
 * read, not only the one currently shown on the map. Death points and temporary markers are in
 * there too, which is the point: the place you died is the place you most want to walk back to.</p>
 *
 * <p>The mod comes in two editions with two ids, and the fair-play one is the same code.</p>
 */
final class XaeroMinimapSource extends ReflectiveSource {

    private Object minimapModule;
    private Method currentSession;
    private Method worldManager;
    private Method currentWorld;
    private Method waypointSets;
    private Method worldDimension;
    private Method waypointsOfSet;
    private Method x;
    private Method y;
    private Method z;
    private Method name;
    private Method hasY;

    @Override
    public String id() {
        return "xaero";
    }

    @Override
    public String label() {
        return Lang.get("lune.gui.waypoints.source.xaero");
    }

    @Override
    protected List<String> modIds() {
        return List.of("xaerominimap", "xaerominimapfair");
    }

    @Override
    protected void resolve() throws ReflectiveOperationException {
        minimapModule = type("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
        currentSession = type("xaero.hud.module.HudModule").getMethod("getCurrentSession");
        worldManager = type("xaero.hud.minimap.module.MinimapSession").getMethod("getWorldManager");
        currentWorld = type("xaero.hud.minimap.world.MinimapWorldManager").getMethod("getCurrentWorld");
        Class<?> world = type("xaero.hud.minimap.world.MinimapWorld");
        waypointSets = world.getMethod("getIterableWaypointSets");
        worldDimension = world.getMethod("getDimId");
        waypointsOfSet = type("xaero.hud.minimap.waypoint.set.WaypointSet").getMethod("getWaypoints");
        // Still in its pre-24 package: the sets moved, the waypoint itself did not.
        Class<?> waypoint = type("xaero.common.minimap.waypoints.Waypoint");
        x = waypoint.getMethod("getX");
        y = waypoint.getMethod("getY");
        z = waypoint.getMethod("getZ");
        // Death points carry a translation key for a name; this is the one that resolves it.
        name = waypoint.getMethod("getLocalizedName");
        hasY = waypoint.getMethod("isYIncluded");
    }

    @Override
    protected List<ExternalWaypoint> read(Minecraft mc) throws ReflectiveOperationException {
        // Null between worlds, and for a moment after joining one.
        Object session = currentSession.invoke(minimapModule);
        if (session == null) {
            return List.of();
        }
        Object world = currentWorld.invoke(worldManager.invoke(session));
        if (world == null) {
            return List.of();
        }
        ResourceKey<?> level = (ResourceKey<?>) worldDimension.invoke(world);
        String in = level == null ? WaypointStore.currentDimension() : level.identifier().toString();
        List<ExternalWaypoint> out = new ArrayList<>();
        Iterable<?> sets = (Iterable<?>) waypointSets.invoke(world);
        if (sets == null) {
            return List.of();
        }
        for (Object set : sets) {
            Iterable<?> waypoints = (Iterable<?>) waypointsOfSet.invoke(set);
            if (waypoints == null) {
                continue;
            }
            for (Object waypoint : waypoints) {
                String shown = (String) name.invoke(waypoint);
                shown = shown == null ? "" : shown.strip();
                if (shown.isEmpty()) {
                    shown = Lang.get("lune.gui.waypoints.unnamed");
                }
                out.add(new ExternalWaypoint(id(), shown, (Integer) x.invoke(waypoint),
                        (Integer) y.invoke(waypoint), (Integer) z.invoke(waypoint), in,
                        (Boolean) hasY.invoke(waypoint)));
            }
        }
        return out;
    }
}
