package com.etka.lune.waypoint.external;

import com.etka.lune.waypoint.WaypointNames;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The mods Lune can read waypoints from, and the arithmetic the Waypoints tab does on what they
 * return. The arithmetic lives here rather than in the tab so it can be tested without a screen.
 */
public final class ExternalWaypointSources {

    /** As long as a name may be, matching the name box on the Waypoints tab. */
    public static final int NAME_LIMIT = 32;

    private static final List<ExternalWaypointSource> ALL = List.of(
            new WaystonesSource(), new JourneyMapSource(), new XaeroMinimapSource(), new DiscoveriesSource(),
            new DeathsSource());

    private ExternalWaypointSources() {}

    /** Every source Lune knows how to read, installed or not, in the order the tab shows them. */
    public static List<ExternalWaypointSource> all() {
        return ALL;
    }

    /** The ones whose mod is installed and readable right now. */
    public static List<ExternalWaypointSource> available() {
        return ALL.stream().filter(ExternalWaypointSource::available).toList();
    }

    /**
     * Nearest first. Entries in other dimensions have no distance worth sorting on, so they go
     * after everything in this one, grouped by dimension and then by name.
     */
    public static List<ExternalWaypoint> nearestFirst(Collection<ExternalWaypoint> entries, Vec3 from,
                                                      String dimension) {
        Comparator<ExternalWaypoint> order = Comparator
                .comparing((ExternalWaypoint w) -> !w.isIn(dimension))
                .thenComparingDouble(w -> w.isIn(dimension) ? w.distanceFrom(from) : 0)
                .thenComparing(ExternalWaypoint::dimension)
                .thenComparing(ExternalWaypoint::name, String.CASE_INSENSITIVE_ORDER);
        List<ExternalWaypoint> sorted = new ArrayList<>(entries);
        sorted.sort(order);
        return List.copyOf(sorted);
    }

    /**
     * A position for a column recorded without a height: the ground there if that chunk is
     * loaded, otherwise {@code fallbackY}. Compasses and some map waypoints only know columns.
     */
    public static BlockPos groundPos(int x, int z, int fallbackY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(
                    SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
            if (chunk != null) {
                int ground = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
                return new BlockPos(x, ground, z);
            }
        }
        return new BlockPos(x, fallbackY, z);
    }

    /** Trimmed, single-spaced and cut to {@link #NAME_LIMIT}: what the name box would accept. */
    public static String fitName(String name) {
        if (name == null) {
            return "";
        }
        String fitted = name.strip().replaceAll("\\s+", " ");
        if (fitted.length() > NAME_LIMIT) {
            fitted = fitted.substring(0, NAME_LIMIT).strip();
        }
        return fitted;
    }

    /**
     * {@code wanted} if nothing is using it, otherwise the first of {@code wanted 2},
     * {@code wanted 3}... that is free, shortened so the number still fits. Names are compared the
     * way {@link com.etka.lune.waypoint.WaypointStore} compares them, so a name this returns is a
     * name the store will not silently replace another with. A blank request gets a flower.
     */
    public static String uniqueName(String wanted, Collection<String> taken) {
        String base = fitName(wanted);
        if (base.isEmpty()) {
            return WaypointNames.suggest(taken);
        }
        if (isFree(base, taken)) {
            return base;
        }
        for (int round = 2; round <= 1000; round++) {
            String suffix = " " + round;
            String stem = base.length() + suffix.length() > NAME_LIMIT
                    ? base.substring(0, NAME_LIMIT - suffix.length()).strip()
                    : base;
            String candidate = stem + suffix;
            if (isFree(candidate, taken)) {
                return candidate;
            }
        }
        return WaypointNames.suggest(taken);
    }

    private static boolean isFree(String candidate, Collection<String> taken) {
        for (String name : taken) {
            if (name != null && name.equalsIgnoreCase(candidate)) {
                return false;
            }
        }
        return true;
    }
}
