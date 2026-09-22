package com.etka.lune.waypoint.external;

import com.etka.lune.Constants;
import com.etka.lune.platform.Services;
import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Every waypoint JourneyMap lists, read through its own client API.
 *
 * <p>JourneyMap is the one of the three that publishes an API, so it is the one Lune does not
 * reach into by name. The API is handed to {@link LuneJourneyMapPlugin} during JourneyMap's
 * start-up and parked in {@link JourneyMapBridge}; until that has happened - JourneyMap absent,
 * or still loading - this source simply is not available.</p>
 */
final class JourneyMapSource implements ExternalWaypointSource {

    private boolean broken;

    @Override
    public String id() {
        return "journeymap";
    }

    @Override
    public String label() {
        return Lang.get("lune.gui.waypoints.source.journeymap");
    }

    @Override
    public boolean available() {
        return !broken && Services.PLATFORM.isModLoaded("journeymap") && JourneyMapBridge.attached();
    }

    @Override
    public List<ExternalWaypoint> list() {
        if (!available()) {
            return List.of();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return List.of();
        }
        try {
            return JourneyMapAccess.list(JourneyMapBridge.api(), id(), WaypointStore.currentDimension());
        } catch (LinkageError | ClassCastException e) {
            // The API Lune was built against and the one running disagree. Once is enough.
            broken = true;
            Constants.LOG.warn("JourneyMap's API no longer looks the way Lune expects, so its "
                    + "waypoints stay off the Waypoints tab for this session: {}", e.toString());
            return List.of();
        } catch (RuntimeException e) {
            Constants.LOG.debug("Could not read JourneyMap waypoints this time", e);
            return List.of();
        }
    }
}
