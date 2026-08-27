package com.etka.lune.routine;

import com.etka.lune.Constants;
import com.etka.lune.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The player's routines, persisted to {@code config/lune-routines.json}.
 * <p>
 * Also handles share/import. A routine is just JSON, and every parameter serialises to namespaced
 * ids rather than registry indices, so a routine copied to the clipboard and pasted by someone else
 * works - dropping only the entries whose mods they don't have.
 */
public final class RoutineStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static RoutineStore instance;

    private final List<Routine> routines = new ArrayList<>();

    public static RoutineStore get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public List<Routine> all() {
        return List.copyOf(routines);
    }

    public List<String> names() {
        return routines.stream().map(routine -> routine.name).toList();
    }

    public Optional<Routine> byName(String name) {
        return routines.stream().filter(routine -> routine.name.equalsIgnoreCase(name)).findFirst();
    }

    public Routine create(String name) {
        Routine routine = new Routine(uniqueName(name));
        RoutineGraph.addExplicitStart(routine);
        routines.add(routine);
        save();
        return routine;
    }

    public void remove(Routine routine) {
        if (routines.remove(routine)) {
            save();
        }
    }

    /**
     * Restores only the built-in tasks that are missing by name. Existing tasks, including edited
     * copies of a built-in task, are left untouched.
     *
     * @return the number of built-in tasks restored
     */
    public int restoreMissingDefaults() {
        int restored = 0;
        for (Routine defaultRoutine : DefaultRoutines.create()) {
            if (byName(defaultRoutine.name).isEmpty()) {
                routines.add(defaultRoutine);
                restored++;
            }
        }
        if (restored > 0) {
            save();
        }
        return restored;
    }

    /** Ensures a name doesn't collide, since routines are referenced by name from other routines. */
    public String uniqueName(String wanted) {
        String base = wanted == null || wanted.isBlank() ? "Task" : wanted.trim();
        String candidate = base;
        int suffix = 2;
        while (byName(candidate).isPresent()) {
            candidate = base + " " + suffix++;
        }
        return candidate;
    }

    // --- share / import ------------------------------------------------------

    /** The shareable form of a routine. */
    public String export(Routine routine) {
        return GSON.toJson(routine);
    }

    /** A snapshot of the whole routine list, used for undo/redo. */
    public String exportAll() {
        return GSON.toJson(routines);
    }

    /** Restores the whole routine list from a snapshot without writing to disk. */
    public void restoreAll(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            List<Routine> parsed = GSON.fromJson(json, new TypeToken<List<Routine>>() {}.getType());
            if (parsed == null) {
                return;
            }
            routines.clear();
            for (Routine routine : parsed) {
                if (routine != null && routine.nodes != null) {
                    normalize(routine);
                    routines.add(routine);
                }
            }
        } catch (RuntimeException e) {
            Constants.LOG.warn("Could not restore routine snapshot", e);
        }
    }

    /** Copies a routine to the system clipboard. */
    public boolean exportToClipboard(Routine routine) {
        if (routine == null) {
            return false;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(export(routine));
        return true;
    }

    /**
     * Reads a shared routine from the clipboard and adds it under a non-colliding name.
     *
     * @return the imported routine, or empty if the clipboard didn't hold a valid one
     */
    public Optional<Routine> importFromClipboard() {
        String text = Minecraft.getInstance().keyboardHandler.getClipboard();
        return importFrom(text);
    }

    public Optional<Routine> importFrom(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            Routine imported = GSON.fromJson(json, Routine.class);
            if (imported == null || imported.nodes == null) {
                return Optional.empty();
            }
            imported.nodes.removeIf(node -> node == null || node.commandId == null);
            normalize(imported);
            imported.name = uniqueName(imported.name);
            routines.add(imported);
            save();
            return Optional.of(imported);
        } catch (RuntimeException e) {
            // Pasting arbitrary clipboard content is expected to fail often; that's not an error
            // worth a stack trace in the log.
            Constants.LOG.debug("Clipboard did not contain a routine", e);
            return Optional.empty();
        }
    }

    // --- persistence ---------------------------------------------------------

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + "-routines.json");
    }

    private static RoutineStore load() {
        RoutineStore store = new RoutineStore();
        Path path = file();
        if (!Files.exists(path)) {
            // First run on this profile. Seeding here rather than on an empty list means a player
            // who deletes every routine keeps them deleted - the file exists by then.
            store.routines.addAll(DefaultRoutines.create());
            store.routines.forEach(RoutineStore::normalize);
            store.save();
            return store;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            List<Routine> loaded = GSON.fromJson(reader, new TypeToken<List<Routine>>() {}.getType());
            if (loaded != null) {
                loaded.stream()
                        .filter(routine -> routine != null && routine.name != null && routine.nodes != null)
                        .peek(RoutineStore::normalize)
                        .forEach(store.routines::add);
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read {}, starting with no routines", path, e);
        }
        return store;
    }

    public void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(routines, writer);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Could not write {}", path, e);
        }
    }

    /** Adds editor/runtime defaults introduced after older routine JSON was written. */
    private static void normalize(Routine routine) {
        routine.nodes.removeIf(java.util.Objects::isNull);
        migrateLegacyAlways(routine);
        for (RoutineNode node : routine.nodes) {
            if (node == null) {
                continue;
            }
            if (node.params == null) {
                node.params = new java.util.LinkedHashMap<>();
            }
            if (node.exposedInputs == null) {
                node.exposedInputs = new java.util.LinkedHashSet<>();
            }
            if (node.exposedOutputs == null) {
                node.exposedOutputs = new java.util.LinkedHashSet<>();
            }
            if (node.inputLinks == null) {
                node.inputLinks = new java.util.LinkedHashMap<>();
            }
            if (node.alwaysTargets == null) {
                node.alwaysTargets = new java.util.LinkedHashSet<>();
            }
            if (node.alwaysTargetInputPorts == null) {
                node.alwaysTargetInputPorts = new java.util.LinkedHashMap<>();
            }
            if (node.signalLinks == null) {
                node.signalLinks = new java.util.ArrayList<>();
            }
            // Timers used to be ordinary Success-only commands. Preserve an old saved connection
            // when loading it into the new pulse-only shape.
            if (node.isTimerNode() && node.signalLinks.isEmpty() && node.onSuccess != null) {
                node.signalLinks.add(new RoutineSignalLink(0, node.onSuccess, -1));
            }
            if (node.isTimerNode()) {
                node.onSuccess = null;
                node.onFailure = null;
                node.onWhile = null;
            }
            if (node.isPulseNode()) {
                // Pulse cards use signalLinks for their output; normal task branches do not
                // belong on them. Incoming Success/Fail wires from another card remain intact.
                node.onSuccess = null;
                node.onFailure = null;
                node.onWhile = null;
            }
            node.signalInputCount = Math.clamp(node.signalInputCount,
                    RoutineNode.MIN_SIGNAL_PORTS, RoutineNode.MAX_SIGNAL_PORTS);
            node.signalOutputCount = Math.clamp(node.signalOutputCount,
                    RoutineNode.MIN_SIGNAL_PORTS, RoutineNode.MAX_SIGNAL_PORTS);
            node.alwaysTargetInputPorts.keySet().removeIf(target -> !node.alwaysTargets.contains(target));
            node.signalLinks.removeIf(link -> link == null || link.targetNodeId == null
                    || link.targetNodeId.isBlank()
                    || link.outputPort < 0 || link.outputPort >= node.signalOutputCount
                    || link.targetPort < -1);
            if (node.isAlwaysNode()) {
                node.alwaysIntervalSeconds = node.alwaysIntervalSeconds <= 0
                        ? RoutineNode.DEFAULT_ALWAYS_INTERVAL_SECONDS
                        : Math.clamp(node.alwaysIntervalSeconds,
                                RoutineNode.MIN_ALWAYS_INTERVAL_SECONDS,
                                RoutineNode.MAX_ALWAYS_INTERVAL_SECONDS);
            }
            if (RoutineGraph.isWhileTarget(routine, node)) {
                // A While companion is live for its main node; its own repeat box is not its
                // lifetime and must not make the card look like a one-shot watcher.
                node.repeat = 0;
            }
            if (RoutineSafety.isMonitorNode(routine, node)) {
                // Monitor execution is independent of ordinary step repetition. Keep the card's
                // label honest, including for old routines that were saved as x1.
                node.repeat = 0;
            }
            if ("walk".equals(node.commandId) || "run".equals(node.commandId)) {
                String oldTarget = node.params.get("target");
                node.params.putIfAbsent("direction",
                        oldTarget == null || oldTarget.isBlank() ? "Facing" : "Coordinates");
                node.params.putIfAbsent("distance", "32");
            }
            if ("mine".equals(node.commandId) || "gettool".equals(node.commandId)
                    || "find".equals(node.commandId)) {
                // Older routines predate deliberate camera sweeps. Keep their previous single-view
                // behavior until the user explicitly enables the new fallback on that node.
                node.params.putIfAbsent("check_around", "false");
            }
            if ("deposit".equals(node.commandId)) {
                // Missing parameters otherwise inherit the palette's last edited live value.
                node.params.putIfAbsent("optional", "false");
            }
        }
    }

    /** Converts the old single routine-level While pointer into the visible fan-out source node. */
    private static void migrateLegacyAlways(Routine routine) {
        if (routine.onWhile == null || routine.nodes.stream().anyMatch(node -> node.isAlwaysNode())) {
            return;
        }
        RoutineNode target = routine.nodeById(routine.onWhile);
        if (target == null) {
            return;
        }
        RoutineNode always = new RoutineNode(RoutineNode.ALWAYS_COMMAND);
        always.editorX = 24;
        always.editorY = 2;
        always.alwaysTargets.add(target.id);
        routine.nodes.add(0, always);
        routine.onWhile = null;
    }
}
