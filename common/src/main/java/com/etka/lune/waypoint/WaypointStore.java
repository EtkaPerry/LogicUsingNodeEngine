package com.etka.lune.waypoint;

import com.etka.lune.Constants;
import com.etka.lune.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Named locations, scoped to the world in which they were created.
 *
 * <p>Single-player waypoints live below the world root, next to the world data. That is deliberate:
 * copying or moving a world copies its waypoints too, while opening another world cannot expose
 * the old world's coordinates. Multiplayer has no client-side world folder, so it gets a separate
 * file per server address as the safest identity available to a client-only mod.</p>
 */
public final class WaypointStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TypeToken<List<Waypoint>> WAYPOINT_LIST = new TypeToken<>() {};
    private static final int FORMAT_VERSION = 2;
    private static final String WAYPOINT_FILE = "waypoints.json";
    private static final String LEGACY_FILE = Constants.MOD_ID + "-waypoints.json";
    private static final String LEGACY_BACKUP = Constants.MOD_ID + "-waypoints.legacy.json";

    private static WaypointStore instance;

    private final List<Waypoint> waypoints = new ArrayList<>();
    private Scope activeScope;
    private String activeScopeKey;
    private String documentId;

    public static WaypointStore get() {
        if (instance == null) {
            instance = new WaypointStore();
        }
        instance.refreshScope();
        return instance;
    }

    public List<Waypoint> all() {
        refreshScope();
        return List.copyOf(waypoints);
    }

    public List<String> names() {
        refreshScope();
        return waypoints.stream().map(Waypoint::name).toList();
    }

    public Optional<Waypoint> byName(String name) {
        refreshScope();
        return waypoints.stream().filter(w -> w.name().equalsIgnoreCase(name)).findFirst();
    }

    /** Adds or replaces by name, then saves in the currently active world scope. */
    public void put(Waypoint waypoint) {
        refreshScope();
        if (activeScope == null) {
            Constants.LOG.warn("Ignoring waypoint '{}' because no world is active", waypoint.name());
            return;
        }
        waypoints.removeIf(w -> w.name().equalsIgnoreCase(waypoint.name()));
        waypoints.add(waypoint);
        save();
    }

    public void remove(String name) {
        refreshScope();
        if (waypoints.removeIf(w -> w.name().equalsIgnoreCase(name))) {
            save();
        }
    }

    /** The dimension id the player is currently in, in the form stored on a waypoint. */
    public static String currentDimension() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return "";
        }
        return mc.level.dimension().identifier().toString();
    }

    /** Whether a waypoint can be acted on without crossing a dimension boundary first. */
    public static boolean isInCurrentDimension(Waypoint waypoint) {
        return waypoint != null && currentDimension().equals(waypoint.dimension());
    }

    /** Saves the player's current position under {@code name}. */
    public void captureHere(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        BlockPos pos = mc.player.blockPosition();
        put(Waypoint.of(name, pos, currentDimension()));
    }

    /**
     * Forces the current document to disk. Saves are written to a temporary file and swapped into
     * place, so a crash during serialization cannot leave a half-written waypoint file. The prior
     * document is also retained as {@code waypoints.json.bak}.
     */
    public void save() {
        refreshScope();
        if (activeScope == null) {
            Constants.LOG.warn("Ignoring waypoint save because no world is active");
            return;
        }

        Path path = activeScope.path();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        WaypointDocument document = new WaypointDocument(FORMAT_VERSION, activeScope.persistedKey(),
                documentId, new ArrayList<>(waypoints));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(document, writer);
            }

            if (Files.exists(path)) {
                try {
                    Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException backupError) {
                    // The atomic replacement below is still safer than refusing to save a new
                    // waypoint because an old backup could not be refreshed.
                    Constants.LOG.warn("Could not refresh waypoint backup {}", backup, backupError);
                }
            }

            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Could not write waypoints to {}", path, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The next save can safely replace the temporary file.
            }
        }
    }

    private void refreshScope() {
        Scope next = currentScope();
        String nextKey = next == null ? "<none>" : next.cacheKey();
        if (Objects.equals(activeScopeKey, nextKey)) {
            return;
        }

        activeScope = next;
        activeScopeKey = nextKey;
        documentId = null;
        waypoints.clear();
        if (next != null) {
            load(next);
        }
    }

    private void load(Scope scope) {
        Path path = scope.path();
        if (!Files.exists(path)) {
            migrateLegacyFile(scope);
            if (!Files.exists(path)) {
                documentId = UUID.randomUUID().toString();
                return;
            }
        }

        Loaded loaded = read(path, scope);
        if (loaded == null) {
            Path backup = path.resolveSibling(path.getFileName() + ".bak");
            loaded = read(backup, scope);
            if (loaded != null) {
                Constants.LOG.warn("Recovered waypoints from {}", backup);
                restoreBackup(path, backup);
            }
        }
        if (loaded == null) {
            // Keep the unreadable file in place for manual recovery; do not overwrite it with an
            // empty list. A backup from the last successful save is available beside it.
            documentId = UUID.randomUUID().toString();
            return;
        }
        documentId = loaded.documentId();
        waypoints.addAll(loaded.waypoints());
    }

    /**
     * Imports the old global file exactly once, into the first active scope that needs data. It is
     * then renamed rather than deleted, so existing users do not silently lose their waypoints.
     */
    private void migrateLegacyFile(Scope scope) {
        Path legacy = legacyFile();
        if (!Files.exists(legacy)) {
            return;
        }

        Loaded loaded = read(legacy, null);
        if (loaded == null) {
            Constants.LOG.warn("Leaving unreadable legacy waypoint file at {}", legacy);
            return;
        }

        documentId = UUID.randomUUID().toString();
        waypoints.addAll(loaded.waypoints());
        save();
        if (!Files.exists(scope.path())) {
            Constants.LOG.warn("Could not write migrated waypoints to {}; leaving {} untouched",
                    scope.path(), legacy);
            return;
        }
        try {
            Files.move(legacy, legacy.resolveSibling(LEGACY_BACKUP),
                    StandardCopyOption.REPLACE_EXISTING);
            Constants.LOG.info("Migrated {} waypoint(s) into {}", waypoints.size(), scope.path());
        } catch (IOException e) {
            Constants.LOG.warn("Waypoints were migrated, but could not archive {}", legacy, e);
        }
    }

    private static Loaded read(Path path, Scope expectedScope) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                return null;
            }

            if (root.isJsonArray()) {
                return new Loaded(UUID.randomUUID().toString(), readWaypointList(root));
            }
            if (!root.isJsonObject()) {
                return null;
            }

            WaypointDocument document = GSON.fromJson(root, WaypointDocument.class);
            if (document == null || document.waypoints() == null) {
                return null;
            }
            // A remote file is addressed by a hash, but still records the full scope key inside
            // the document. This makes a damaged/copied config file fail closed. Local files are
            // intentionally not checked against their path: the path is expected to change when
            // the entire world folder is copied.
            if (expectedScope != null && !expectedScope.local()
                    && !expectedScope.persistedKey().equals(document.scopeKey())) {
                Constants.LOG.warn("Ignoring waypoint file {} because it belongs to another server", path);
                return null;
            }

            String id = document.documentId();
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            return new Loaded(id, sanitise(document.waypoints()));
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read waypoints from {}, keeping the file untouched", path, e);
            return null;
        }
    }

    private static List<Waypoint> readWaypointList(JsonElement root) {
        List<Waypoint> loaded = GSON.fromJson(root, WAYPOINT_LIST.getType());
        return sanitise(loaded);
    }

    private static void restoreBackup(Path path, Path backup) {
        Path temporary = path.resolveSibling(path.getFileName() + ".recovery.tmp");
        try {
            Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Could not restore waypoint backup {}", backup, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The next load can try the backup again.
            }
        }
    }

    private static List<Waypoint> sanitise(List<Waypoint> loaded) {
        if (loaded == null) {
            return List.of();
        }
        return loaded.stream()
                .filter(w -> w != null && w.name() != null && !w.name().isBlank())
                .toList();
    }

    private static Scope currentScope() {
        WorldScope scope = WorldScope.current();
        if (scope == null) {
            return null;
        }
        return new Scope(scope.cacheKey(), scope.file(WAYPOINT_FILE, ".json"), scope.local(),
                scope.persistedKey());
    }

    private static Path legacyFile() {
        return Services.PLATFORM.getConfigDir().resolve(LEGACY_FILE);
    }

    private record Scope(String cacheKey, Path path, boolean local, String persistedKey) {}

    private record Loaded(String documentId, List<Waypoint> waypoints) {}

    private record WaypointDocument(int formatVersion, String scopeKey, String documentId,
                                    List<Waypoint> waypoints) {}
}
