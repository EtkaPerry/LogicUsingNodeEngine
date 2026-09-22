package com.etka.lune.waypoint.external;

import com.etka.lune.Constants;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;

/**
 * The plugin JourneyMap discovers and hands its client API to.
 *
 * <p>This is the one class in Lune that names a JourneyMap type, and JourneyMap is the only thing
 * that ever loads it: Forge and NeoForge find it through the annotation, Fabric through the
 * {@code journeymap} entrypoint in {@code fabric.mod.json}. Without JourneyMap installed nothing
 * references it and it stays an unread file in the jar, which is what lets the API be a
 * compile-time dependency and nothing more.</p>
 *
 * <p>It does one thing, which is to put the API where {@link JourneyMapSource} can find it. It
 * registers no overlays and no waypoints of its own: Lune reads JourneyMap's list and never adds
 * to it.</p>
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class LuneJourneyMapPlugin implements IClientPlugin {

    public LuneJourneyMapPlugin() {}

    @Override
    public void initialize(IClientAPI api) {
        JourneyMapBridge.attach(api);
        Constants.LOG.info("JourneyMap's waypoints are available on the Waypoints tab");
    }

    public String getModId() {
        return Constants.MOD_ID;
    }
}
