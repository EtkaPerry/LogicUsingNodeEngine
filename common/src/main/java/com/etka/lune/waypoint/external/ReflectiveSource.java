package com.etka.lune.waypoint.external;

import com.etka.lune.mods.ModHook;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * A source read through reflection, for a mod Lune does not compile against.
 *
 * <p>Waystones and Xaero's Minimap publish no API artifact Lune could build on for this Minecraft
 * version, so their public classes are looked up by name instead. The lookup, the one-time
 * warning when a name is gone and the per-call handling all come from {@link ModHook}; what is
 * left here is the reading.</p>
 */
abstract class ReflectiveSource extends ModHook implements ExternalWaypointSource {

    /** Reads the list, with a level and a player guaranteed to exist. */
    protected abstract List<ExternalWaypoint> read(Minecraft mc) throws ReflectiveOperationException;

    @Override
    protected String hookName() {
        return id();
    }

    @Override
    public final boolean available() {
        return ready();
    }

    @Override
    public final List<ExternalWaypoint> list() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return List.of();
        }
        return call(() -> read(mc), List.of());
    }
}
