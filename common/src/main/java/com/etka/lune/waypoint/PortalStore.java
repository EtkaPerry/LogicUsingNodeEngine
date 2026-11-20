package com.etka.lune.waypoint;

import com.etka.lune.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The portals this world has shown the bot, newest last.
 *
 * <p>Written by the cards that stand in front of one: Use Nether Portal writes down what it saw
 * and what it learned by crossing, and Build Nether Portal writes down the frame it lit. Read by
 * the travel a Go to Waypoint plans when the place is on the other side - a remembered portal
 * beats building a new one, and the nearest useful one beats the rest.</p>
 *
 * <p>Two records within {@link #SAME_PORTAL} blocks of each other and going the same way are the
 * same portal: its cells get scanned at different heights and its centre lands a block or two off
 * each time. Re-recording refreshes the spot, which is also how a shifted portal keeps one
 * line.</p>
 *
 * <p>Scoped exactly like {@link WaypointStore}, {@link DiscoveryStore} and {@link DeathStore}:
 * with the world in single-player, per server address online. Saved through a temporary file
 * swapped into place, so a crash mid-write cannot leave half a file.</p>
 */
public final class PortalStore {

    /** How many portals are worth keeping; a base with more than that does not need the bot's help. */
    public static final int LIMIT = 32;
    /** Two records this close, leading the same way, are one portal. */
    public static final double SAME_PORTAL = 8.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT_VERSION = 1;
    private static final String LOCAL_FILE = "portals.json";
    private static final String REMOTE_SUFFIX = ".portals.json";

    private static PortalStore instance;

    private final List<PortalLink> portals = new ArrayList<>();
    private WorldScope scope;
    private String scopeKey;

    public static PortalStore get() {
        if (instance == null) {
            instance = new PortalStore();
        }
        instance.refreshScope();
        return instance;
    }

    public List<PortalLink> all() {
        refreshScope();
        return List.copyOf(portals);
    }

    /** Every portal in {@code dimension} that leads to {@code leadsTo}. */
    public List<PortalLink> linking(String dimension, String leadsTo) {
        refreshScope();
        return portals.stream()
                .filter(link -> link.dimension().equals(dimension) && link.leadsTo().equals(leadsTo))
                .toList();
    }

    /** The closest portal in {@code dimension} to {@code from}, whichever way it leads. */
    public Optional<PortalLink> nearestIn(String dimension, BlockPos from) {
        refreshScope();
        return portals.stream()
                .filter(link -> link.dimension().equals(dimension))
                .min(Comparator.comparingDouble(link -> link.pos().distSqr(from)));
    }

    /**
     * Writes down a portal, refreshing the record already standing within {@link #SAME_PORTAL} of
     * it rather than keeping a second line for the same frame.
     */
    public void record(PortalLink link) {
        refreshScope();
        if (scope == null) {
            Constants.LOG.warn("Ignoring a portal at {} because no world is active", link.pos());
            return;
        }
        for (int i = 0; i < portals.size(); i++) {
            PortalLink known = portals.get(i);
            if (known.dimension().equals(link.dimension()) && known.leadsTo().equals(link.leadsTo())
                    && horizontal(link.pos(), known.pos()) <= SAME_PORTAL) {
                // The same portal seen again. Memory takes the fresher spot - a portal cell
                // gets scanned at different heights - but what is on disk already says there
                // is a portal here, and a disk write for every tick one stands in sight is
                // the part that is not worth it.
                portals.set(i, link);
                return;
            }
        }
        portals.add(link);
        while (portals.size() > LIMIT) {
            portals.remove(0);
        }
        save();
    }

    /**
     * Forgets one, which is what a trip that found nothing there does - so the next route is not
     * planned through a portal that is gone.
     */
    public void forget(PortalLink link) {
        refreshScope();
        if (portals.removeIf(known -> known.dimension().equals(link.dimension())
                && known.leadsTo().equals(link.leadsTo())
                && horizontal(link.pos(), known.pos()) <= SAME_PORTAL)) {
            save();
        }
    }

    /** Horizontal distance between two columns, the distance a portal choice is made on. */
    public static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void save() {
        Path path = scope.file(LOCAL_FILE, REMOTE_SUFFIX);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Document document = new Document(FORMAT_VERSION, scope.persistedKey(), new ArrayList<>(portals));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(document, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Could not write portals to {}", path, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The next save can safely replace the temporary file.
            }
        }
    }

    private void refreshScope() {
        WorldScope next = WorldScope.current();
        String nextKey = next == null ? "<none>" : next.cacheKey();
        if (Objects.equals(scopeKey, nextKey)) {
            return;
        }
        scope = next;
        scopeKey = nextKey;
        portals.clear();
        if (next != null) {
            load(next);
        }
    }

    private void load(WorldScope scope) {
        Path path = scope.file(LOCAL_FILE, REMOTE_SUFFIX);
        if (!Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                return;
            }
            Document document = GSON.fromJson(root, Document.class);
            if (document == null || document.portals() == null) {
                return;
            }
            // A remote file records which server it belongs to; a copied one fails closed. Local
            // files are not checked against their path, which changes when a world is copied.
            if (!scope.local() && !scope.persistedKey().equals(document.scopeKey())) {
                Constants.LOG.warn("Ignoring portal file {} because it belongs to another server", path);
                return;
            }
            for (PortalLink link : document.portals()) {
                if (link != null && link.dimension() != null && link.leadsTo() != null
                        && portals.size() < LIMIT) {
                    portals.add(link);
                }
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read portals from {}, keeping the file untouched", path, e);
        }
    }

    private record Document(int formatVersion, String scopeKey, List<PortalLink> portals) {}
}
