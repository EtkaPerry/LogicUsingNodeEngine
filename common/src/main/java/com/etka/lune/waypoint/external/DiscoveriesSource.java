package com.etka.lune.waypoint.external;

import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.Discovery;
import com.etka.lune.waypoint.DiscoveryStore;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * The places Lune's own compass cards have found, shown beside the other mods' lists.
 *
 * <p>Not another mod, but the same shape: a list of places with a distance, each of which can be
 * walked to or given a name. Without it the Find cards would remember things the player has no
 * way to see, and a memory nobody can inspect is a memory nobody trusts.</p>
 */
final class DiscoveriesSource implements ExternalWaypointSource {

    @Override
    public String id() {
        return "found";
    }

    @Override
    public String label() {
        return Lang.get("lune.gui.waypoints.source.found");
    }

    @Override
    public boolean available() {
        return Minecraft.getInstance().level != null && !DiscoveryStore.get().all().isEmpty();
    }

    @Override
    public List<ExternalWaypoint> list() {
        List<ExternalWaypoint> out = new ArrayList<>();
        for (Discovery discovery : DiscoveryStore.get().all()) {
            out.add(new ExternalWaypoint(id(), DiscoveryStore.displayName(discovery), discovery.x(), 0,
                    discovery.z(), discovery.dimension(), false));
        }
        return out;
    }
}
