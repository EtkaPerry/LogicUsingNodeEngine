package com.etka.lune.waypoint.external;

import com.etka.lune.util.Durations;
import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.Death;
import com.etka.lune.waypoint.DeathStore;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Where this world has killed the player, shown beside the other mods' lists.
 *
 * <p>The Recover Death Drop card is what walks back there, but the list is what makes the card
 * worth trusting: a memory nobody can inspect is a memory nobody trusts, and "it says it knows
 * where I died" is a claim a player should be able to check against the coordinates they saw on
 * the death screen. Copying one across to the waypoints is how a death outlives the few that are
 * kept.</p>
 */
final class DeathsSource implements ExternalWaypointSource {

    @Override
    public String id() {
        return "deaths";
    }

    @Override
    public String label() {
        return Lang.get("lune.gui.waypoints.source.deaths");
    }

    @Override
    public boolean available() {
        return Minecraft.getInstance().level != null && !DeathStore.get().all().isEmpty();
    }

    @Override
    public List<ExternalWaypoint> list() {
        long now = System.currentTimeMillis();
        List<ExternalWaypoint> out = new ArrayList<>();
        for (Death death : DeathStore.get().all()) {
            String name = Lang.get("lune.gui.waypoints.death_entry",
                    Durations.describe(death.ageMillis(now) / 1000L));
            out.add(new ExternalWaypoint(id(), name, death.x(), death.y(), death.z(),
                    death.dimension(), true));
        }
        return out;
    }
}
