package com.etka.lune.task;

import com.etka.lune.Constants;
import com.etka.lune.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
 * The player's tasks, persisted to {@code config/lune-tasks.json}.
 * <p>
 * Also handles share/import. A task is just JSON, and every parameter serialises to namespaced
 * ids rather than registry indices, so a task copied to the clipboard and pasted by someone else
 * works - dropping only the entries whose mods they don't have.
 */
public final class TaskStore {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            // Routes used to be serialised as one {x, y} anchor. Read that shape as a
            // one-point route while writing the new repeatable {points: [...]} shape.
            .registerTypeAdapter(TaskCableRoute.class,
                    (JsonDeserializer<TaskCableRoute>) TaskStore::deserializeCableRoute)
            .create();
    private static TaskStore instance;

    private final List<TaskGraph> tasks = new ArrayList<>();

    public static TaskStore get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Name prefix marking a training attempt.
     *
     * <p>These are scratch. They are removed when the lesson is left and dropped again on load, so
     * a crash mid-puzzle cannot leave one sitting in the task list looking like work the player
     * started and forgot - which is exactly what it would look like.</p>
     */
    public static final String TRAINING_PREFIX = "Training: ";

    public static boolean isTrainingAttempt(TaskGraph task) {
        return task != null && task.name != null && task.name.startsWith(TRAINING_PREFIX);
    }

    public List<TaskGraph> all() {
        return List.copyOf(tasks);
    }

    public List<String> names() {
        return tasks.stream().map(task -> task.name).toList();
    }

    public Optional<TaskGraph> byName(String name) {
        return tasks.stream().filter(task -> task.name.equalsIgnoreCase(name)).findFirst();
    }

    /** The starter job with this id, if the player still has it. */
    public Optional<TaskGraph> bySeededId(String seededId) {
        if (seededId == null) {
            return Optional.empty();
        }
        return tasks.stream().filter(task -> seededId.equals(task.seededId)).findFirst();
    }

    /**
     * What a stored task name is called on screen.
     *
     * <p>For the Run Task card's dropdown, which lists the names tasks are saved under. The value
     * it writes into the node has to stay the saved name - that is how the card finds the task
     * again - so only the label changes.</p>
     */
    public static String displayNameOf(String storedName) {
        if (storedName == null || storedName.isEmpty()) {
            return "";
        }
        return get().byName(storedName).map(TaskGraph::displayName).orElse(storedName);
    }

    public TaskGraph create(String name) {
        TaskGraph task = new TaskGraph(uniqueName(name));
        TaskWiring.addExplicitStart(task);
        tasks.add(task);
        save();
        return task;
    }

    public void remove(TaskGraph task) {
        if (tasks.remove(task)) {
            save();
        }
    }

    /**
     * Takes ownership of a graph built in code, replacing any task already using its name.
     *
     * <p>Replacement rather than {@link #uniqueName}, because this is how a training attempt reaches
     * the editor: reopening a lesson should hand back that lesson, not add it beside four abandoned
     * numbered copies of itself. Everything the player authors arrives through {@link #create} or
     * {@link #importFrom}, and both of those still uniquify.</p>
     */
    public TaskGraph adopt(TaskGraph task) {
        if (task == null || task.nodes == null || task.name == null) {
            return null;
        }
        normalize(task);
        tasks.removeIf(existing -> existing.name.equalsIgnoreCase(task.name));
        tasks.add(task);
        save();
        return task;
    }

    /**
     * Restores only the built-in tasks that are missing. Existing tasks, including edited copies of
     * a built-in task, are left untouched.
     *
     * <p>Matched on the seeded id rather than the name, because the name is what the player is
     * free to change and what used to be the only thing to match on. A renamed starter job is
     * still that job; a save written before ids existed is recognised by the name it shipped
     * under, which {@link #normalize} has already turned back into an id by this point.</p>
     *
     * @return the number of built-in tasks restored
     */
    public int restoreMissingDefaults() {
        int restored = 0;
        for (TaskGraph defaultTask : DefaultTasks.create()) {
            if (!alreadyPresent(tasks, defaultTask)) {
                tasks.add(defaultTask);
                restored++;
            }
        }
        if (restored > 0) {
            save();
        }
        return restored;
    }

    /**
     * Gives a task saved before starter jobs had ids the id it should have had.
     *
     * <p>The name is the only evidence of which job it is, and it is good evidence: an untouched
     * starter job still carries the name it shipped under. A renamed one is not recognised, which
     * is the same answer the name match gave before ids existed.</p>
     */
    static void adoptSeededId(TaskGraph task) {
        if (task != null && task.seededId == null && task.name != null) {
            task.seededId = DefaultTasks.SEEDED_NAMES.get(task.name);
        }
    }

    /**
     * Whether a starter job is already in this list, so a restore leaves it alone.
     *
     * <p>By id first, so a job the player renamed is still recognised as theirs, and by the name it
     * shipped under as well - which catches a save from before ids that {@link #adoptSeededId} has
     * not been over, and is the check this used to do on its own.</p>
     */
    static boolean alreadyPresent(List<TaskGraph> existing, TaskGraph seeded) {
        for (TaskGraph task : existing) {
            if (seeded.seededId != null && seeded.seededId.equals(task.seededId)) {
                return true;
            }
            if (task.name != null && task.name.equalsIgnoreCase(seeded.name)) {
                return true;
            }
        }
        return false;
    }

    /** Called only after the hidden restore-button gesture; normal defaults never include it. */
    public TaskGraph unlockSecretTasks() {
        int before = tasks.size();
        TaskGraph task = SecretTasks.restoreInto(tasks);
        if (tasks.size() != before) save();
        return task;
    }

    /** Ensures a name doesn't collide, since tasks are referenced by name from other tasks. */
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

    /** The shareable form of a task. */
    public String export(TaskGraph task) {
        return GSON.toJson(task);
    }

    /** A snapshot of the whole task list, used for undo/redo. */
    public String exportAll() {
        return GSON.toJson(tasks);
    }

    /** Restores the whole task list from a snapshot without writing to disk. */
    public void restoreAll(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            List<TaskGraph> parsed = GSON.fromJson(json, new TypeToken<List<TaskGraph>>() {}.getType());
            if (parsed == null) {
                return;
            }
            tasks.clear();
            for (TaskGraph task : parsed) {
                if (task != null && task.nodes != null) {
                    normalize(task);
                    tasks.add(task);
                }
            }
        } catch (RuntimeException e) {
            Constants.LOG.warn("Could not restore task snapshot", e);
        }
    }

    /** Copies a task to the system clipboard. */
    public boolean exportToClipboard(TaskGraph task) {
        if (task == null) {
            return false;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(export(task));
        return true;
    }

    /**
     * Reads a shared task from the clipboard and adds it under a non-colliding name.
     *
     * @return the imported task, or empty if the clipboard didn't hold a valid one
     */
    public Optional<TaskGraph> importFromClipboard() {
        String text = Minecraft.getInstance().keyboardHandler.getClipboard();
        return importFrom(text);
    }

    public Optional<TaskGraph> importFrom(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            TaskGraph imported = GSON.fromJson(json, TaskGraph.class);
            if (imported == null || imported.nodes == null) {
                return Optional.empty();
            }
            imported.nodes.removeIf(node -> node == null || node.commandId == null);
            normalize(imported);
            // A copy of a starter job is a task of the player's own, however it was made. Keeping
            // the id would give them two tasks claiming to be job 3, and a restore would then see
            // job 3 as present and decline to bring back the one they actually deleted.
            imported.seededId = null;
            imported.name = uniqueName(imported.name);
            tasks.add(imported);
            save();
            return Optional.of(imported);
        } catch (RuntimeException e) {
            // Pasting arbitrary clipboard content is expected to fail often; that's not an error
            // worth a stack trace in the log.
            Constants.LOG.debug("Clipboard did not contain a task", e);
            return Optional.empty();
        }
    }

    // --- persistence ---------------------------------------------------------

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + "-tasks.json");
    }

    /**
     * Where these were saved before tasks stopped being called routines.
     *
     * <p>Read once, if the new file is not there yet. Renaming the file without this would look
     * exactly like a fresh install to an existing player: every task they had built, silently
     * replaced by the starter set. The old name is a string literal on purpose - it names a file
     * that exists on disk, so it must not follow the code when the code is renamed again.</p>
     */
    private static Path legacyFile() {
        return Services.PLATFORM.getConfigDir().resolve("lune-routines.json");
    }

    private static TaskStore load() {
        TaskStore store = new TaskStore();
        Path path = file();
        if (!Files.exists(path) && Files.exists(legacyFile())) {
            path = legacyFile();
            Constants.LOG.info("Adopting tasks from {}", path);
        }
        if (!Files.exists(path)) {
            // First run on this profile. Seeding here rather than on an empty list means a player
            // who deletes every task keeps them deleted - the file exists by then.
            store.tasks.addAll(DefaultTasks.create());
            store.tasks.forEach(TaskStore::normalize);
            store.save();
            return store;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            List<TaskGraph> loaded = GSON.fromJson(reader, new TypeToken<List<TaskGraph>>() {}.getType());
            if (loaded != null) {
                loaded.stream()
                        .filter(task -> task != null && task.name != null && task.nodes != null)
                        .filter(task -> !isTrainingAttempt(task))
                        .peek(TaskStore::normalize)
                        .forEach(store.tasks::add);
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read {}, starting with no tasks", path, e);
        }
        if (!path.equals(file())) {
            // Adopted from the old name; write it out under the new one so this happens once.
            store.save();
        }
        return store;
    }

    public void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(tasks, writer);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Could not write {}", path, e);
        }
    }

    /** Adds editor/runtime defaults introduced after older task JSON was written. */
    private static void normalize(TaskGraph task) {
        task.nodes.removeIf(java.util.Objects::isNull);
        adoptSeededId(task);
        if (task.cableAnchors == null) {
            task.cableAnchors = new java.util.LinkedHashMap<>();
        }
        task.cableAnchors.entrySet().removeIf(entry -> entry.getKey() == null
                || entry.getValue() == null);
        for (TaskCableRoute route : task.cableAnchors.values()) {
            if (route.points == null) {
                route.points = new ArrayList<>();
            } else {
                route.points.removeIf(java.util.Objects::isNull);
            }
        }
        task.cableAnchors.entrySet().removeIf(entry -> entry.getValue().points.isEmpty());
        migrateLegacyAlways(task);
        for (TaskNode node : task.nodes) {
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
                node.signalLinks.add(new TaskSignalLink(0, node.onSuccess, -1));
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
                    TaskNode.MIN_SIGNAL_PORTS, TaskNode.MAX_SIGNAL_PORTS);
            node.signalOutputCount = Math.clamp(node.signalOutputCount,
                    TaskNode.MIN_SIGNAL_PORTS, TaskNode.MAX_SIGNAL_PORTS);
            node.alwaysTargetInputPorts.keySet().removeIf(target -> !node.alwaysTargets.contains(target));
            node.signalLinks.removeIf(link -> link == null || link.targetNodeId == null
                    || link.targetNodeId.isBlank()
                    || link.outputPort < 0 || link.outputPort >= node.signalOutputCount
                    || link.targetPort < -1);
            if (node.isClockNode()) {
                // An Always that was given an interval is what Pulse now is. Converting keeps the
                // task doing exactly what it did, under the card that says so.
                if (node.isAlwaysNode() && node.alwaysIntervalSeconds > 0) {
                    node.commandId = TaskNode.PULSE_COMMAND;
                }
                if (node.isAlwaysNode()) {
                    node.alwaysIntervalSeconds = 0;
                } else {
                    node.alwaysIntervalSeconds = node.alwaysIntervalSeconds <= 0
                            ? TaskNode.DEFAULT_PULSE_INTERVAL_SECONDS
                            : Math.clamp(node.alwaysIntervalSeconds,
                                    TaskNode.MIN_ALWAYS_INTERVAL_SECONDS,
                                    TaskNode.MAX_ALWAYS_INTERVAL_SECONDS);
                }
            }
            if (TaskWiring.isWhileTarget(task, node)) {
                // A While companion is live for its main node; its own repeat box is not its
                // lifetime and must not make the card look like a one-shot watcher.
                node.repeat = 0;
            }
            if (node.onWhile != null) {
                // Older saved tasks carried the live companion edge but not the editor-only
                // visibility flag. Keep the wire visible after loading so protection is never
                // mistaken for an empty or disconnected Self Preservation card.
                node.whileVisible = true;
            }
            if (TaskSafety.isMonitorNode(task, node)) {
                // Monitor execution is independent of ordinary step repetition. Keep the card's
                // label honest, including for old tasks that were saved as x1.
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
                // Older tasks predate deliberate camera sweeps. Keep their previous single-view
                // behavior until the user explicitly enables the new fallback on that node.
                node.params.putIfAbsent("check_around", "false");
            }
            if ("deposit".equals(node.commandId)) {
                // Missing parameters otherwise inherit the palette's last edited live value.
                node.params.putIfAbsent("optional", "false");
            }
            if ("task".equals(node.commandId)) {
                // The Run Task node's id followed the rename. Saved tasks still say "task",
                // and a node whose command id no longer resolves is a dead card on the canvas.
                node.commandId = "task";
            }
            if ("stop_game".equals(node.commandId)) {
                // Stop the Game used to be a yes/no "close the game too" and is now a three-way
                // ending. Reading the old answer matters: without this a saved task that said
                // "stop at the title screen" would quietly start quitting to desktop instead.
                String closeClient = node.params.remove("close_client");
                if (closeClient != null) {
                    node.params.putIfAbsent("ending",
                            (Boolean.parseBoolean(closeClient)
                                    ? com.etka.lune.bot.task.StopGameTask.Ending.QUIT
                                    : com.etka.lune.bot.task.StopGameTask.Ending.MENU).label());
                }
            }
        }
    }

    private static TaskCableRoute deserializeCableRoute(JsonElement json,
                                                        java.lang.reflect.Type type,
                                                        JsonDeserializationContext context) {
        TaskCableRoute route = new TaskCableRoute();
        if (!json.isJsonObject()) {
            return route;
        }
        JsonObject object = json.getAsJsonObject();
        if (object.has("points") && !object.get("points").isJsonNull()) {
            List<TaskCableAnchor> points = context.deserialize(object.get("points"),
                    new TypeToken<List<TaskCableAnchor>>() {}.getType());
            if (points != null) {
                route.points.addAll(points);
            }
        } else if (object.has("x") && object.has("y")) {
            TaskCableAnchor legacyPoint = context.deserialize(json, TaskCableAnchor.class);
            if (legacyPoint != null) {
                route.points.add(legacyPoint);
            }
        }
        return route;
    }

    /** Converts the old single task-level While pointer into the visible fan-out source node. */
    private static void migrateLegacyAlways(TaskGraph task) {
        if (task.onWhile == null || task.nodes.stream().anyMatch(node -> node.isClockNode())) {
            return;
        }
        TaskNode target = task.nodeById(task.onWhile);
        if (target == null) {
            return;
        }
        TaskNode always = new TaskNode(TaskNode.ALWAYS_COMMAND);
        always.editorX = 24;
        always.editorY = 2;
        always.alwaysTargets.add(target.id);
        task.nodes.add(0, always);
        task.onWhile = null;
    }
}
