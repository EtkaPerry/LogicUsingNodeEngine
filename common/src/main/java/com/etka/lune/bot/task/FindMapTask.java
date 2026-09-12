package com.etka.lune.bot.task;

import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.List;
import java.util.Set;

/**
 * Reads a map the player is carrying and walks to what it points at.
 *
 * <p>A treasure map out of a shipwreck is a coordinate the player already owns and cannot use: the
 * cross is drawn on a picture, and turning that picture into somewhere to stand is arithmetic
 * nobody wants to do by hand. This does it - hold the map, read the marker, walk there.</p>
 *
 * <p>The map must be held before it can be read, and that is not a quirk of this code. A client only
 * receives a map's contents while the player is holding it, so a map sitting in a backpack is, from
 * here, a blank item with an id on it. So the first thing this does is put it in hand and wait for
 * the server to send the picture.</p>
 *
 * <p>What it walks to is a square, not a point. Markers are stored coarsely and the game rounds them
 * unevenly, so a scale-3 cross is only good to about eight blocks and a scale-4 one to sixteen - see
 * {@link MapTargetPolicy#accuracy}. Arriving means standing in the right square; finding the chest
 * inside it is digging, and that is a different job.</p>
 */
public final class FindMapTask implements Task {

    /** Ticks to wait for the server to send the map's contents once it is in hand. */
    private static final int SYNC_DEADLINE_TICKS = 100;

    /** Which marker to walk to, when a map carries more than one. */
    public enum Aim {
        MARKER("Where the map points"),
        CENTRE("Middle of the map");

        private final String label;

        Aim(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Aim fromLabel(String raw) {
            for (Aim aim : values()) {
                if (aim.label.equalsIgnoreCase(raw)) {
                    return aim;
                }
            }
            return MARKER;
        }
    }

    /**
     * Markers worth walking to, best first, by registry name.
     *
     * <p>Compared by name and not by {@link net.minecraft.core.Holder} identity, which is what the
     * first version did and why it looked straight at a treasure map's red cross and reported the
     * map blank. A decoration that arrived over the network carries its own holder instance; the
     * static fields here are the registry's. They describe the same decoration and are not the same
     * object, so {@code indexOf} - which falls back to reference equality - never matched one.</p>
     */
    private static final List<String> PREFERRED = List.of(
            MapDecorationTypes.TARGET_X.getRegisteredName(),
            MapDecorationTypes.TARGET_POINT.getRegisteredName(),
            MapDecorationTypes.WOODLAND_MANSION.getRegisteredName(),
            MapDecorationTypes.OCEAN_MONUMENT.getRegisteredName(),
            MapDecorationTypes.RED_MARKER.getRegisteredName(),
            MapDecorationTypes.BLUE_MARKER.getRegisteredName());

    /**
     * Markers that are not destinations: where somebody is standing, and the frame a map hangs in.
     * <p>
     * Everything else is fair game, ranked below {@link #PREFERRED}. An allowlist would quietly
     * refuse any marker this code has not heard of - a banner the player placed as a waypoint, or a
     * decoration from a newer version - and refusing to walk to a mark that is plainly drawn on the
     * map is exactly the failure this class already had once.
     */
    private static final Set<String> NOT_A_PLACE = Set.of(
            MapDecorationTypes.PLAYER.getRegisteredName(),
            MapDecorationTypes.FRAME.getRegisteredName(),
            MapDecorationTypes.PLAYER_OFF_MAP.getRegisteredName(),
            MapDecorationTypes.PLAYER_OFF_LIMITS.getRegisteredName());

    private final Item mapItem;
    private final Aim aim;
    private final int arriveWithin;

    private GotoTask travel;
    private BlockPos destination;
    private final StatusText status = new StatusText();
    private int syncTicks;
    private boolean edgeMarker;

    public FindMapTask(Item mapItem, Aim aim, int arriveWithin) {
        this.mapItem = mapItem;
        this.aim = aim;
        this.arriveWithin = Math.max(1, arriveWithin);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.find_map.task_find_map_target");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Task: Find Map Target");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        travel = null;
        destination = null;
        syncTicks = 0;
        edgeMarker = false;
        status.set("lune.status.find_map.looking_map_read");
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (destination != null) {
            return travelToDestination(ctx);
        }

        // Hold it. A map that is not in hand has no contents on the client.
        int slot = InventoryHelper.equip(ctx, stack -> stack.is(mapItem));
        if (slot < 0) {
            status.set("lune.status.find_map.no_backpack_read", InventoryHelper.itemName(mapItem));
            ctx.debug.decide("nothing to read; this job needs a map in the inventory");
            return TaskStatus.FAILED;
        }

        ItemStack held = ctx.player.getInventory().getItem(slot);
        MapItemSavedData data = MapItem.getSavedData(held, ctx.level);
        if (data == null) {
            // Normal for the first few ticks: the picture arrives from the server after the map is
            // held. Only a map that never arrives is a problem.
            if (++syncTicks > SYNC_DEADLINE_TICKS) {
                status.set("lune.status.find_map.held_map_s_contents_never_arrived", (SYNC_DEADLINE_TICKS / 20));
                ctx.debug.decide("map data never synced; cannot read where it points");
                return TaskStatus.FAILED;
            }
            status.set("lune.status.find_map.holding_map_waiting_fill");
            return TaskStatus.RUNNING;
        }

        if (!ctx.level.dimension().equals(data.dimension)) {
            status.set("lune.status.find_map.map_drawn_another_dimension_means");
            ctx.debug.decide("map belongs to " + data.dimension.identifier()
                    + " but we are in " + ctx.level.dimension().identifier());
            return TaskStatus.FAILED;
        }

        destination = readDestination(ctx, data);
        if (destination == null) {
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    /** Decodes the marker, or the centre, into a place to stand. */
    private BlockPos readDestination(BotContext ctx, MapItemSavedData data) {
        int scale = data.scale;
        MapDecoration marker = aim == Aim.CENTRE ? null : bestMarker(data);

        int x;
        int z;
        String what;
        if (marker != null) {
            x = MapTargetPolicy.worldCoordinate(data.centerX, scale, marker.x());
            z = MapTargetPolicy.worldCoordinate(data.centerZ, scale, marker.y());
            edgeMarker = !MapTargetPolicy.isOnTheMap(marker.x())
                    || !MapTargetPolicy.isOnTheMap(marker.y());
            what = marker.type().getRegisteredName();
        } else {
            if (aim == Aim.MARKER) {
                // Name what was actually drawn. "Nothing marked" was reported once at a map with a
                // red cross plainly on it, and the message gave no way to tell that from the truth.
                String drawn = describeMarkers(data);
                status.set("lune.status.find_map.map_has_nothing_walk_draws", drawn);
                ctx.debug.decide("no usable marker on the map; decorations present: " + drawn);
                return null;
            }
            x = data.centerX;
            z = data.centerZ;
            what = Lang.get("lune.status.find_map.middle_of_map");
        }

        // Say the number out loud. A coordinate the player can read off the screen and check
        // against the map in their own hands is worth more than a bot that silently walks off.
        int slack = MapTargetPolicy.accuracy(scale);
        StatusText reading = new StatusText().set("lune.status.find_map.reading",
                what, x, z, data.centerX, data.centerZ, scale, slack);
        ctx.debug.decide("read " + reading.text());
        status.set(edgeMarker ? "lune.status.find_map.heading_for_edge_marker"
                : "lune.status.find_map.heading_for", reading);
        return new BlockPos(x, ctx.player.blockPosition().getY(), z);
    }

    /** The most destination-like marker on the map, or null when it only draws people and frames. */
    private static MapDecoration bestMarker(MapItemSavedData data) {
        MapDecoration best = null;
        int bestRank = Integer.MAX_VALUE;
        for (MapDecoration decoration : data.getDecorations()) {
            String type = decoration.type().getRegisteredName();
            if (NOT_A_PLACE.contains(type)) {
                continue;
            }
            int preferred = PREFERRED.indexOf(type);
            int rank = preferred >= 0 ? preferred : PREFERRED.size();
            if (rank < bestRank) {
                best = decoration;
                bestRank = rank;
            }
        }
        return best;
    }

    /** Everything the map draws, for the journal, so "nothing marked" can be checked rather than believed. */
    private static String describeMarkers(MapItemSavedData data) {
        StringBuilder found = new StringBuilder();
        for (MapDecoration decoration : data.getDecorations()) {
            if (!found.isEmpty()) {
                found.append(", ");
            }
            found.append(decoration.type().getRegisteredName())
                    .append('@').append(decoration.x()).append(',').append(decoration.y());
        }
        return found.isEmpty() ? "none at all" : found.toString();
    }

    private TaskStatus travelToDestination(BotContext ctx) {
        if (travel == null) {
            travel = new GotoTask(
                    new Goals.NearXZ(destination.getX(), destination.getZ(), arriveWithin),
                    true, false);
            travel.start(ctx);
        }
        TaskStatus result = travel.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            status.set("lune.status.find_map.walking_x_z", destination.getX(), destination.getZ(), travel.statusLine());
            return TaskStatus.RUNNING;
        }
        travel.stop(ctx);
        travel = null;
        if (result == TaskStatus.FAILED) {
            status.set("lune.status.find_map.could_not_find_route_x_z", destination.getX(), destination.getZ());
            return TaskStatus.FAILED;
        }
        status.set("lune.status.find_map.arrived_what_map_points_x_z", destination.getX(), destination.getZ());
        ctx.debug.decide("standing on the map's mark; anything buried here still has to be dug for");
        return TaskStatus.SUCCESS;
    }

    @Override
    public void onPause(BotContext ctx) {
        if (travel != null) {
            travel.stop(ctx);
            travel = null;
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        onPause(ctx);
    }
}
