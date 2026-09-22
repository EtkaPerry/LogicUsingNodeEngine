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
 * Where this world has killed the player, newest first.
 *
 * <p>A bot runs while nobody is watching, so a death is the one event the player is guaranteed to
 * miss. The engine writes the position down the moment it sees the player is not alive - no card
 * is running by then, and nothing that needs a card on the canvas could ever catch it - and the
 * Recover Death Drop card reads it back.</p>
 *
 * <p>More than one is kept because dying on the way back to your things is the ordinary case, not
 * the strange one: the second pile does not replace the first, and a bot that collects one and
 * then walks to the other is the whole point. {@link #LIMIT} of them, oldest dropped, because a
 * death older than the last few is a death whose drops are long gone.</p>
 *
 * <p>Scoped exactly like {@link WaypointStore} and {@link DiscoveryStore}: with the world in
 * single-player, per server address online. Saved through a temporary file swapped into place, so
 * a crash mid-write cannot leave half a file.</p>
 */
public final class DeathStore {

    /** How many deaths are worth keeping. Vanilla drops despawn in five minutes; this is generous. */
    public static final int LIMIT = 8;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT_VERSION = 1;
    private static final String LOCAL_FILE = "deaths.json";
    private static final String REMOTE_SUFFIX = ".deaths.json";

    private static DeathStore instance;

    private final List<Death> deaths = new ArrayList<>();
    private WorldScope scope;
    private String scopeKey;

    public static DeathStore get() {
        if (instance == null) {
            instance = new DeathStore();
        }
        instance.refreshScope();
        return instance;
    }

    /** Newest first. */
    public List<Death> all() {
        refreshScope();
        return List.copyOf(deaths);
    }

    /** The most recent death anywhere, or empty in a world that has not killed anybody yet. */
    public Optional<Death> latest() {
        refreshScope();
        return deaths.stream().findFirst();
    }

    /** The most recent death in {@code dimension}, which is the only kind a card can walk to. */
    public Optional<Death> latestIn(String dimension) {
        refreshScope();
        return deaths.stream().filter(death -> death.dimension().equals(dimension)).findFirst();
    }

    /**
     * The closest death in {@code dimension} to {@code from}.
     *
     * <p>Nearest rather than newest is what you want with two piles on the ground: collect the one
     * underfoot before walking to the one across the valley, whichever order they happened in.</p>
     */
    public Optional<Death> nearestIn(String dimension, BlockPos from) {
        refreshScope();
        return deaths.stream()
                .filter(death -> death.dimension().equals(dimension))
                .min(Comparator.comparingDouble(death -> death.pos().distSqr(from)));
    }

    /** Writes down a death, dropping the oldest once there are more than {@link #LIMIT}. */
    public void record(Death death) {
        refreshScope();
        if (scope == null) {
            Constants.LOG.warn("Ignoring a death at {} because no world is active", death.pos());
            return;
        }
        deaths.add(0, death);
        while (deaths.size() > LIMIT) {
            deaths.remove(deaths.size() - 1);
        }
        save();
    }

    /**
     * Forgets one, which is what a finished recovery does.
     *
     * <p>Without this the card walks back to a picked-clean patch of ground every time it runs,
     * and a task that loops does nothing else for the rest of the night.</p>
     */
    public void forget(Death death) {
        refreshScope();
        if (deaths.removeIf(known -> known.equals(death))) {
            save();
        }
    }

    public void forgetAll() {
        refreshScope();
        if (!deaths.isEmpty()) {
            deaths.clear();
            save();
        }
    }

    private void save() {
        Path path = scope.file(LOCAL_FILE, REMOTE_SUFFIX);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Document document = new Document(FORMAT_VERSION, scope.persistedKey(), new ArrayList<>(deaths));
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
            Constants.LOG.warn("Could not write deaths to {}", path, e);
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
        deaths.clear();
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
            if (document == null || document.deaths() == null) {
                return;
            }
            // A remote file records which server it belongs to; a copied one fails closed. Local
            // files are not checked against their path, which changes when a world is copied.
            if (!scope.local() && !scope.persistedKey().equals(document.scopeKey())) {
                Constants.LOG.warn("Ignoring death file {} because it belongs to another server", path);
                return;
            }
            for (Death death : document.deaths()) {
                if (death != null && death.dimension() != null && deaths.size() < LIMIT) {
                    deaths.add(death);
                }
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read deaths from {}, keeping the file untouched", path, e);
        }
    }

    private record Document(int formatVersion, String scopeKey, List<Death> deaths) {}
}
