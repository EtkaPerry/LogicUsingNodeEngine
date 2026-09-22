package com.etka.lune.waypoint;

import com.etka.lune.Constants;
import com.etka.lune.bot.knowledge.BiomeKnowledge;
import com.etka.lune.util.Lang;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a compass has found for this world, so it never has to be asked twice.
 *
 * <p>Nature's Compass and Explorer's Compass cost a search each time and, on some servers, an
 * experience level. A place they have answered with once does not move, so the answer is written
 * down here, next to the waypoints of the same world, and the Find cards read it before they reach
 * for the compass. The compass is how a place is learned; this is what makes it learned.</p>
 *
 * <p>Scoped exactly like {@link WaypointStore}: with the world in single-player, per server
 * address online. Saved through a temporary file swapped into place, so a crash mid-write cannot
 * leave half a file.</p>
 */
public final class DiscoveryStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT_VERSION = 1;
    private static final String LOCAL_FILE = "discoveries.json";
    private static final String REMOTE_SUFFIX = ".discoveries.json";

    private static DiscoveryStore instance;

    private final List<Discovery> discoveries = new ArrayList<>();
    private WorldScope scope;
    private String scopeKey;

    public static DiscoveryStore get() {
        if (instance == null) {
            instance = new DiscoveryStore();
        }
        instance.refreshScope();
        return instance;
    }

    public List<Discovery> all() {
        refreshScope();
        return List.copyOf(discoveries);
    }

    public Optional<Discovery> find(String kind, String id, String dimension) {
        refreshScope();
        String key = Discovery.key(kind, id, dimension);
        return discoveries.stream().filter(d -> d.key().equals(key)).findFirst();
    }

    /** Adds or replaces, by what it is rather than where. */
    public void remember(Discovery discovery) {
        refreshScope();
        if (scope == null) {
            Constants.LOG.warn("Ignoring discovery {} because no world is active", discovery.key());
            return;
        }
        discoveries.removeIf(d -> d.key().equals(discovery.key()));
        discoveries.add(discovery);
        save();
    }

    public void forget(String key) {
        refreshScope();
        if (discoveries.removeIf(d -> d.key().equals(key))) {
            save();
        }
    }

    /**
     * What to call a discovery: a biome by the game's own name for it, a structure by its id read
     * as a title, since the game has no names for those.
     */
    public static String displayName(Discovery discovery) {
        return displayName(discovery.kind(), discovery.id());
    }

    public static String displayName(String kind, String id) {
        if (Discovery.BIOME.equals(kind)) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                try {
                    return mc.level.registryAccess().lookupOrThrow(Registries.BIOME)
                            .get(Identifier.parse(id))
                            .map(BiomeKnowledge::displayName)
                            .orElseGet(() -> Discovery.humanize(id));
                } catch (RuntimeException unreadable) {
                    return Discovery.humanize(id);
                }
            }
            return Discovery.humanize(id);
        }
        String key = "structure." + id.replace(':', '.');
        return Lang.has(key) ? Lang.get(key) : Discovery.humanize(id);
    }

    private void save() {
        Path path = scope.file(LOCAL_FILE, REMOTE_SUFFIX);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Document document = new Document(FORMAT_VERSION, scope.persistedKey(), new ArrayList<>(discoveries));
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
            Constants.LOG.warn("Could not write discoveries to {}", path, e);
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
        discoveries.clear();
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
            if (document == null || document.discoveries() == null) {
                return;
            }
            // A remote file records which server it belongs to; a copied one fails closed. Local
            // files are not checked against their path, which changes when a world is copied.
            if (!scope.local() && !scope.persistedKey().equals(document.scopeKey())) {
                Constants.LOG.warn("Ignoring discovery file {} because it belongs to another server", path);
                return;
            }
            for (Discovery discovery : document.discoveries()) {
                if (discovery != null && discovery.kind() != null && discovery.id() != null
                        && discovery.dimension() != null) {
                    discoveries.add(discovery);
                }
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read discoveries from {}, keeping the file untouched", path, e);
        }
    }

    private record Document(int formatVersion, String scopeKey, List<Discovery> discoveries) {}
}
