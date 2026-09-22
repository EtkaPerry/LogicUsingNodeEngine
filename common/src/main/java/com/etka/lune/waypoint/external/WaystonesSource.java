package com.etka.lune.waypoint.external;

import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The waystones this player has activated, as Waystones itself lists them.
 *
 * <p>Waystones keeps the client's copy of that list in {@code PlayerWaystoneManager}, filled from
 * the server every time it changes, and its own selection screen reads the same method - so what
 * shows here is exactly what the player would see on a waystone. Global waystones arrive through
 * the same channel, activated for everyone.</p>
 */
final class WaystonesSource extends ReflectiveSource {

    private Method activatedWaystones;
    private Method name;
    private Method pos;
    private Method dimension;

    @Override
    public String id() {
        return "waystones";
    }

    @Override
    public String label() {
        return Lang.get("lune.gui.waypoints.source.waystones");
    }

    @Override
    protected List<String> modIds() {
        return List.of("waystones");
    }

    @Override
    protected void resolve() throws ReflectiveOperationException {
        Class<?> manager = type("net.blay09.mods.waystones.core.PlayerWaystoneManager");
        activatedWaystones = manager.getMethod("getActivatedWaystones", Player.class);
        Class<?> waystone = type("net.blay09.mods.waystones.api.Waystone");
        name = waystone.getMethod("getName");
        pos = waystone.getMethod("getPos");
        dimension = waystone.getMethod("getDimension");
    }

    @Override
    protected List<ExternalWaypoint> read(Minecraft mc) throws ReflectiveOperationException {
        Collection<?> known = (Collection<?>) activatedWaystones.invoke(null, mc.player);
        if (known == null) {
            return List.of();
        }
        String here = WaypointStore.currentDimension();
        List<ExternalWaypoint> out = new ArrayList<>(known.size());
        for (Object waystone : known) {
            BlockPos at = (BlockPos) pos.invoke(waystone);
            if (at == null) {
                continue;
            }
            Component title = (Component) name.invoke(waystone);
            String shown = title == null ? "" : title.getString().strip();
            if (shown.isEmpty()) {
                shown = Lang.get("lune.gui.waypoints.unnamed");
            }
            ResourceKey<?> level = (ResourceKey<?>) dimension.invoke(waystone);
            String in = level == null ? here : level.identifier().toString();
            out.add(new ExternalWaypoint(id(), shown, at.getX(), at.getY(), at.getZ(), in, true));
        }
        return out;
    }
}
