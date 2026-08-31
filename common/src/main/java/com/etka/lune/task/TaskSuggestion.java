package com.etka.lune.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Safe, deterministic task improvements Lune may offer instead of applying silently. */
public final class TaskSuggestion {

    public enum Kind {
        CONNECTION,
        START,
        SAFETY,
        QUANTITY,
        AUTO_TOOL,
        LOCATION,
        INVENTORY_SPACE,
        TOOL_DURABILITY,
        FOOD,
        TORCHES,
        SLEEP,
        /** Measured while running: this step is what is costing the frame rate. */
        PERFORMANCE
    }

    /** Live facts kept outside the task model so this class remains deterministic in tests. */
    public record Context(int freeSlots, int foodLevel, int torches, int pickaxeDurability,
                          int axeDurability, boolean night, String dimension, String biomeName,
                          boolean barrenForWood) {
        public static Context unknown() {
            return new Context(-1, -1, -1, -1, -1, false, "", "", false);
        }
    }

    private final Kind kind;
    private final TaskGraph task;
    private final TaskNode node;
    private final int suggestedAmount;
    private final int amountStep;
    private final String unit;
    private final String prompt;
    private final String contextToken;
    private final String acceptedReply;
    private final boolean changesTask;
    private final String previewBefore;
    private final String previewAfter;

    private TaskSuggestion(Kind kind, TaskGraph task, TaskNode node, int suggestedAmount,
                              int amountStep, String unit, String prompt) {
        this(kind, task, node, suggestedAmount, amountStep, unit, prompt, "", "",
                kind == Kind.QUANTITY || kind == Kind.AUTO_TOOL, "", "");
    }

    private TaskSuggestion(Kind kind, TaskGraph task, TaskNode node, int suggestedAmount,
                              int amountStep, String unit, String prompt, String contextToken,
                              String acceptedReply) {
        this(kind, task, node, suggestedAmount, amountStep, unit, prompt, contextToken,
                acceptedReply, false, "", "");
    }

    private TaskSuggestion(Kind kind, TaskGraph task, TaskNode node, int suggestedAmount,
                              int amountStep, String unit, String prompt, String contextToken,
                              String acceptedReply, boolean changesTask, String previewBefore,
                              String previewAfter) {
        this.kind = kind;
        this.task = task;
        this.node = node;
        this.suggestedAmount = suggestedAmount;
        this.amountStep = amountStep;
        this.unit = unit;
        this.prompt = prompt;
        this.contextToken = contextToken;
        this.acceptedReply = acceptedReply;
        this.changesTask = changesTask;
        this.previewBefore = previewBefore;
        this.previewAfter = previewAfter;
    }

    public Kind kind() {
        return kind;
    }

    public String key() {
        String taskName = task == null || task.name == null
                ? "" : task.name.trim().toLowerCase(Locale.ROOT);
        return taskName + ":" + kind.name().toLowerCase(Locale.ROOT) + ":" + node.id
                + ":" + Integer.toHexString(nodeFingerprint(node).hashCode()) + ":" + contextToken;
    }

    public boolean hasAmountChoice() {
        return kind == Kind.QUANTITY;
    }

    public int suggestedAmount() {
        return suggestedAmount;
    }

    public int amountStep() {
        return amountStep;
    }

    public String unit() {
        return unit;
    }

    public String prompt() {
        return prompt;
    }

    public String acceptLabel() {
        if (kind == Kind.START) {
            return "Add START";
        }
        if (kind == Kind.SAFETY) {
            return "Make permanent";
        }
        if (kind == Kind.QUANTITY) {
            return "Set goal";
        }
        if (kind == Kind.AUTO_TOOL) {
            return "Turn on";
        }
        return changesTask ? "Add step" : "Got it";
    }

    public boolean changesTask() {
        return changesTask;
    }

    public boolean hasPreview() {
        return changesTask && (!previewBefore.isBlank() || kind == Kind.QUANTITY
                || kind == Kind.AUTO_TOOL);
    }

    public String previewBefore() {
        if (kind == Kind.SAFETY) {
            return "Self Preservation: " + node.describeRepeat();
        }
        if (kind == Kind.QUANTITY) {
            int current = intParam(node, "limit", 0);
            return "Stop after: " + (current <= 0 ? "No limit" : current + " " + unit);
        }
        if (kind == Kind.AUTO_TOOL) {
            return "Get tools first: Off";
        }
        return previewBefore;
    }

    public String previewAfter(int amount) {
        if (kind == Kind.SAFETY) {
            return "Self Preservation: x∞";
        }
        if (kind == Kind.QUANTITY) {
            return "Stop after: " + Math.clamp(amount, Math.max(1, amountStep), 512) + " " + unit;
        }
        if (kind == Kind.AUTO_TOOL) {
            return "Get tools first: On";
        }
        return previewAfter;
    }

    public String acceptedReply() {
        return acceptedReply.isBlank() ? "All right. I'll keep that in mind." : acceptedReply;
    }

    /** Revalidates the editable node before changing it, since the prompt may have been open awhile. */
    public boolean apply(int amount) {
        if (task == null || task.nodes == null) {
            return false;
        }
        if (!changesTask()) {
            return true;
        }
        if (kind == Kind.START) {
            return TaskWiring.addExplicitStart(task) != null;
        }
        if (!task.nodes.contains(node)) {
            return false;
        }
        if (kind == Kind.QUANTITY) {
            if (!Set.of("chop", "mine", "harvest").contains(node.commandId)
                    || isWired(node, "limit") || isLooped(task, node)) {
                return false;
            }
            int bounded = Math.clamp(amount, Math.max(1, amountStep), 512);
            String previous = node.params.put("limit", String.valueOf(bounded));
            return !String.valueOf(bounded).equals(previous);
        }
        if (kind == Kind.SAFETY) {
            if (!task.nodes.contains(node) || !TaskSafety.isConnectedGuard(task, node)
                    || node.repeat == 0) {
                return false;
            }
            node.repeat = 0;
            return true;
        }
        if (kind == Kind.AUTO_TOOL) {
            if (!"mine".equals(node.commandId) || isWired(node, "auto_tool")) {
                return false;
            }
            String previous = node.params.put("auto_tool", "true");
            return !"true".equalsIgnoreCase(previous);
        }
        JobProfile current = profile(node);
        if (kind == Kind.INVENTORY_SPACE) {
            String filter = depositFilter(node);
            return filter != null && current != null && current.producesItems
                    && TaskWiring.insertAfter(task, node, "deposit", Map.of(
                    "filter", filter, "radius", "16", "optional", "true")) != null;
        }
        if (kind == Kind.TOOL_DURABILITY) {
            return current != null && current.tool != ToolNeed.NONE
                    && TaskWiring.insertBefore(task, node, "gettool", Map.of(
                    "tool", toolName(current), "material", toolMaterial(node),
                    "check_around", "true")) != null;
        }
        if (kind == Kind.FOOD) {
            return current != null && current.workload >= 64
                    && TaskWiring.insertBefore(task, node, "eat",
                    Map.of("minimum_food", "18")) != null;
        }
        if (kind == Kind.SLEEP) {
            return current != null && current.surface && current.workload >= 32
                    && TaskWiring.insertBefore(task, node, "sleep", Map.of(
                    "wait_for_night", "false", "reclaim", "true", "radius", "32")) != null;
        }
        return false;
    }

    public static Optional<TaskSuggestion> firstFor(TaskGraph task, TaskNode preferred,
                                                        Set<String> dismissed) {
        return firstFor(task, preferred, dismissed, Context.unknown());
    }

    public static Optional<TaskSuggestion> firstFor(TaskGraph task, TaskNode preferred,
                                                        Set<String> dismissed, Context context) {
        if (task == null || task.nodes == null || task.nodes.isEmpty()) {
            return Optional.empty();
        }
        Set<String> ignored = dismissed == null ? Set.of() : dismissed;

        Optional<TaskConnectionAudit.Issue> issue = TaskConnectionAudit.firstIssue(task);
        if (issue.isPresent()) {
            TaskConnectionAudit.Issue found = issue.get();
            TaskNode issueNode = found.node() == null ? task.nodes.get(0) : found.node();
            TaskSuggestion connection = notice(Kind.CONNECTION, task, issueNode,
                    found.message(), found.key(),
                    "I'll leave that connection for you to repair in Tasks.");
            if (!ignored.contains(connection.key())) {
                return Optional.of(connection);
            }
        }

        List<TaskNode> ordered = new ArrayList<>();
        if (preferred != null && task.nodes.contains(preferred)) {
            ordered.add(preferred);
        }
        for (TaskNode node : task.nodes) {
            if (node != preferred) {
                ordered.add(node);
            }
        }

        for (TaskNode node : ordered) {
            TaskSuggestion quantity = quantitySuggestion(task, node);
            if (quantity != null && !ignored.contains(quantity.key())) {
                return Optional.of(quantity);
            }
        }
        for (TaskNode node : ordered) {
            TaskSuggestion tool = toolSuggestion(task, node);
            if (tool != null && !ignored.contains(tool.key())) {
                return Optional.of(tool);
            }
        }
        Context facts = context == null ? Context.unknown() : context;
        for (TaskNode node : ordered) {
            if (isMonitorNode(task, node)) {
                continue;
            }
            TaskSuggestion[] preparation = {
                    locationSuggestion(task, node, facts),
                    inventorySuggestion(task, node, facts),
                    durabilitySuggestion(task, node, facts),
                    foodSuggestion(task, node, facts),
                    torchSuggestion(task, node, facts),
                    sleepSuggestion(task, node, facts)
            };
            for (TaskSuggestion suggestion : preparation) {
                if (suggestion != null && !ignored.contains(suggestion.key())) {
                    return Optional.of(suggestion);
                }
            }
        }
        return Optional.empty();
    }

    /** Structural entry-point advice kept separate so Lune can prioritize it over job advice. */
    public static Optional<TaskSuggestion> startFor(TaskGraph task) {
        return Optional.ofNullable(startSuggestion(task));
    }

    /** Advice for a monitor whose editor repeat label is finite instead of permanent. */
    public static Optional<TaskSuggestion> finiteMonitorFor(TaskGraph task) {
        return Optional.ofNullable(finiteMonitorSuggestion(task));
    }

    /**
     * In-flight advice: this step is measurably why the game is stuttering.
     *
     * <p>Unlike everything else here this is not derived from the graph - it is fed from what was
     * actually measured while the task ran, which is why the numbers arrive as arguments. It never
     * changes the task. A step being expensive is not automatically wrong: the player may well have
     * meant to run a heavy search forever. The point is to make an invisible cost visible and let
     * them answer that.</p>
     *
     * @param microsPerTick      the step's own runtime, averaged over the measuring window
     * @param restartsPerSecond  how often it is starting over; the other shape the same fault takes
     */
    public static Optional<TaskSuggestion> slowStepFor(TaskGraph task, TaskNode node,
                                                       long microsPerTick, double restartsPerSecond,
                                                       boolean permanent) {
        if (task == null || node == null) {
            return Optional.empty();
        }
        StringBuilder prompt = new StringBuilder(nodeName(node));
        long millis = microsPerTick / 1000;
        if (millis >= 1) {
            // Against the 50 ms a client tick has, so the number means something to a reader who
            // has never thought about tick budgets.
            prompt.append(" is using about ").append(millis)
                    .append(" ms of every 50 ms tick");
        } else {
            prompt.append(" is restarting constantly");
        }
        if (restartsPerSecond >= 1.0) {
            prompt.append(" and starting over about ").append(Math.round(restartsPerSecond))
                    .append(" times a second");
        }
        prompt.append(". That is why the world is stuttering, and it has not produced anything"
                + " while I have been watching.");
        if (permanent) {
            prompt.append(" It is set to repeat forever, so it begins again the moment it finishes.");
        }
        prompt.append(" Was that intended?");
        return Optional.of(notice(Kind.PERFORMANCE, task, node, prompt.toString(),
                "slow-" + millis + "-" + Math.round(restartsPerSecond),
                "Understood. I'll leave it running and stop mentioning it."));
    }

    private static TaskSuggestion locationSuggestion(TaskGraph task, TaskNode node,
                                                        Context context) {
        JobProfile job = profile(node);
        if (job == null || job.workload < 16) {
            return null;
        }
        String dimension = normal(context.dimension);
        String targets = node.params == null ? "" : node.params.getOrDefault("targets",
                node.params.getOrDefault("target", ""));
        String locationProblem = incompatibleTargetGroup(dimension, targets);
        if (locationProblem != null) {
            return notice(Kind.LOCATION, task, node,
                    "This job only targets " + locationProblem + " blocks, but you're in "
                            + dimensionLabel(dimension) + ". It may search forever here.",
                    dimension, "Good catch. I'll leave the route unchanged for you.");
        }
        if ("chop".equals(node.commandId) && context.barrenForWood) {
            String biome = context.biomeName == null || context.biomeName.isBlank()
                    ? "this biome" : context.biomeName;
            return notice(Kind.LOCATION, task, node,
                    "This is a " + job.workload + "-log job, but " + biome
                            + " has almost no trees. A travel step may be needed first.",
                    biome, "Mm-hm. I'll let you choose where the wood run should begin.");
        }
        return null;
    }

    private static TaskSuggestion inventorySuggestion(TaskGraph task, TaskNode node,
                                                         Context context) {
        JobProfile job = profile(node);
        if (job == null || !job.producesItems || job.workload < 32 || context.freeSlots < 0
                || hasDownstreamCommand(task, node, "deposit")) {
            return null;
        }
        int needed = Math.max(2, (job.workload + 63) / 64 + job.extraSlots);
        if (context.freeSlots >= needed) {
            return null;
        }
        String ending = " Extra drops may be left behind; add a Deposit step if needed.";
        String prompt =
                "This " + job.workload + "-" + job.unit + " job may need about " + needed
                        + " free slots; you have " + context.freeSlots
                        + " and no Deposit step after it." + ending;
        String filter = depositFilter(node);
        if (filter != null && TaskWiring.canInsertAfter(task, node)) {
            return change(Kind.INVENTORY_SPACE, task, node,
                    prompt + " Shall I add an optional " + filter.toLowerCase(Locale.ROOT)
                            + " deposit?",
                    context.freeSlots + "-" + needed,
                    "Done. I'll put away the gathered items when a container is nearby.",
                    nodeName(node), nodeName(node) + "  →  Deposit");
        }
        return notice(Kind.INVENTORY_SPACE, task, node, prompt,
                context.freeSlots + "-" + needed,
                "Okay. I won't pretend that bag has more room than it does.");
    }

    private static TaskSuggestion durabilitySuggestion(TaskGraph task, TaskNode node,
                                                          Context context) {
        JobProfile job = profile(node);
        if (job == null || job.tool == ToolNeed.NONE || job.workload < 24
                || hasCommand(task, "gettool")
                || "mine".equals(node.commandId) && boolParam(node, "auto_tool", true)) {
            return null;
        }
        int remaining = job.tool == ToolNeed.AXE ? context.axeDurability : context.pickaxeDurability;
        if (remaining < 0 || remaining >= job.toolUses) {
            return null;
        }
        String tool = job.tool == ToolNeed.AXE ? "axe" : "pickaxe";
        String prompt = remaining == 0
                        ? "This " + job.workload + "-" + job.unit + " job has no usable " + tool
                                + " prepared. A Get Tools step would make it much smoother."
                        : "Your best " + tool + " has about " + remaining + " uses left, but this job"
                                + " is roughly " + job.toolUses + " uses. It may break partway through.";
        if (TaskWiring.canInsertBefore(task, node)) {
            return change(Kind.TOOL_DURABILITY, task, node,
                    prompt + " Shall I add Get Tools before it?",
                    tool + "-" + remaining + "-" + job.toolUses,
                    "Done. I'll prepare the " + tool + " before that job.",
                    nodeName(node), "Get Tools  →  " + nodeName(node));
        }
        return notice(Kind.TOOL_DURABILITY, task, node, prompt,
                tool + "-" + remaining + "-" + job.toolUses,
                "Got it. I won't count a nearly broken tool as ready.");
    }

    private static TaskSuggestion foodSuggestion(TaskGraph task, TaskNode node,
                                                    Context context) {
        JobProfile job = profile(node);
        if (job == null || job.workload < 64 || context.foodLevel < 0 || context.foodLevel >= 14
                || hasCommand(task, "eat")) {
            return null;
        }
        String prompt = "This is a long " + job.label + ", but your food bar is only "
                + context.foodLevel + "/20 and the task has no Eat step. Hunger may interrupt it.";
        if (TaskWiring.canInsertBefore(task, node)) {
            return change(Kind.FOOD, task, node, prompt + " Shall I add a snack break?",
                    String.valueOf(context.foodLevel),
                    "Done. I'll eat before settling into that long job.",
                    nodeName(node), "Eat  →  " + nodeName(node));
        }
        return notice(Kind.FOOD, task, node, prompt,
                String.valueOf(context.foodLevel),
                "All right. A proper snack break belongs before a long job.");
    }

    private static TaskSuggestion torchSuggestion(TaskGraph task, TaskNode node,
                                                     Context context) {
        JobProfile job = profile(node);
        if (job == null || !job.underground || job.workload < 32 || context.torches < 0) {
            return null;
        }
        int wanted = Math.min(32, Math.max(8, (job.workload + 15) / 16));
        if (context.torches >= wanted) {
            return null;
        }
        return notice(Kind.TORCHES, task, node,
                "This underground job is about " + job.workload + " " + job.unit + ", but you carry "
                        + context.torches + " torch" + (context.torches == 1 ? "" : "es")
                        + ". I'd bring around " + wanted + " before starting.",
                context.torches + "-" + wanted,
                "Mhm. Long tunnels deserve a little light.");
    }

    private static TaskSuggestion sleepSuggestion(TaskGraph task, TaskNode node,
                                                     Context context) {
        JobProfile job = profile(node);
        if (job == null || !job.surface || job.workload < 32 || !context.night
                || !"minecraft:overworld".equals(normal(context.dimension))
                || hasCommand(task, "sleep")) {
            return null;
        }
        String prompt = "It's already night, and this is a long " + job.label
                + " with no Sleep step.";
        if (TaskWiring.canInsertBefore(task, node)) {
            return change(Kind.SLEEP, task, node,
                    prompt + " Shall I add Sleep before it?",
                    "night", "Done. I'll try to sleep before that outdoor job.",
                    nodeName(node), "Sleep  →  " + nodeName(node));
        }
        return notice(Kind.SLEEP, task, node,
                prompt + " You may want to place Sleep manually around this loop.",
                "night", "Okay. I'll leave the bedtime decision with you.");
    }

    private static TaskSuggestion notice(Kind kind, TaskGraph task, TaskNode node,
                                             String prompt, String token, String acceptedReply) {
        return new TaskSuggestion(kind, task, node, 0, 0, "", prompt, token, acceptedReply);
    }

    private static TaskSuggestion startSuggestion(TaskGraph task) {
        if (task == null || task.nodes == null
                || TaskWiring.explicitStart(task) != null
                || TaskWiring.hasAlwaysNode(task)) {
            return null;
        }
        TaskNode first = TaskWiring.nextSequentialNode(task, -1);
        TaskNode keyNode = first != null ? first
                : task.nodes.isEmpty() ? new TaskNode(TaskNode.START_COMMAND)
                : task.nodes.get(0);
        return change(Kind.START, task, keyNode,
                "This task has no explicit START node. Shall I add one before its current entry point?",
                "missing-start", "Done. The task now has an explicit START point.",
                "List order chooses the entry point", "START  →  "
                        + (first == null ? "first action" : nodeName(first)));
    }

    /**
     * The only thing left to say about a guard that is already wired in: whether it lasts.
     *
     * <p>A connected guard set to run a fixed number of times is worth one offer. A connected
     * guard already on x∞ is finished, and so is Lune's advice about it.</p>
     */
    private static TaskSuggestion finiteMonitorSuggestion(TaskGraph task) {
        for (TaskNode node : TaskSafety.connectedGuards(task)) {
            if (node.repeat > 0) {
                return change(Kind.SAFETY, task, node,
                        "Self Preservation is set to " + node.describeRepeat()
                                + ". Shall I make it permanent for the whole task?",
                        "monitor-" + node.id, "Done. Self Preservation will remain available for the whole task.",
                        "Self Preservation: " + node.describeRepeat(), "Self Preservation: x∞");
            }
        }
        return null;
    }

    private static TaskSuggestion change(Kind kind, TaskGraph task, TaskNode node,
                                             String prompt, String token, String acceptedReply,
                                             String previewBefore, String previewAfter) {
        return new TaskSuggestion(kind, task, node, 0, 0, "", prompt, token, acceptedReply,
                true, previewBefore, previewAfter);
    }

    /**
     * Functional identity used to invalidate an offer when the selected or running task changes.
     * Editor coordinates are deliberately omitted: moving a card on the canvas does not change the job.
     */
    public static String fingerprint(TaskGraph task) {
        if (task == null) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        append(value, task.name);
        append(value, task.onWhile);
        if (task.nodes != null) {
            for (TaskNode node : task.nodes) {
                value.append('|').append(nodeFingerprint(node));
            }
        }
        return value.toString();
    }

    private static String nodeFingerprint(TaskNode node) {
        if (node == null) {
            return "null";
        }
        StringBuilder value = new StringBuilder();
        append(value, node.id);
        append(value, node.commandId);
        value.append(node.repeat).append(';');
        append(value, node.onSuccess);
        append(value, node.onFailure);
        append(value, node.onWhile);
        value.append(node.alwaysIntervalSeconds).append(';');
        value.append(node.signalInputCount).append(';');
        value.append(node.signalOutputCount).append(';');
        value.append(node.successInputPort).append(';');
        value.append(node.failureInputPort).append(';');
        value.append(node.whileInputPort).append(';');
        if (node.params != null) {
            new TreeMap<>(node.params).forEach((key, entry) -> {
                append(value, key);
                append(value, entry);
            });
        }
        if (node.inputLinks != null) {
            new TreeMap<>(node.inputLinks).forEach((key, link) -> {
                append(value, key);
                if (link != null) {
                    append(value, link.sourceNodeId);
                    append(value, link.sourcePort);
                }
            });
        }
        if (node.alwaysTargets != null) {
            for (String target : new TreeSet<>(node.alwaysTargets)) {
                append(value, target);
            }
        }
        if (node.alwaysTargetInputPorts != null) {
            new TreeMap<>(node.alwaysTargetInputPorts).forEach((target, port) -> {
                append(value, target);
                value.append(port == null ? -1 : port).append(';');
            });
        }
        if (node.signalLinks != null) {
            node.signalLinks.stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted(java.util.Comparator.comparingInt((TaskSignalLink link) -> link.outputPort)
                            .thenComparing(link -> link.targetNodeId == null ? "" : link.targetNodeId)
                            .thenComparingInt(link -> link.targetPort))
                    .forEach(link -> {
                        value.append(link.outputPort).append(';');
                        append(value, link.targetNodeId);
                        value.append(link.targetPort).append(';');
                    });
        }
        return value.toString();
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe).append(';');
    }

    private static TaskSuggestion quantitySuggestion(TaskGraph task, TaskNode node) {
        if (node == null || node.commandId == null || isMonitorNode(task, node)
                || isWired(node, "limit") || isLooped(task, node)) {
            return null;
        }
        int current = intParam(node, "limit", "chop".equals(node.commandId) ? 8 : 0);
        return switch (node.commandId) {
            case "chop" -> current <= 8
                    ? new TaskSuggestion(Kind.QUANTITY, task, node, 32, 8, "logs",
                    current <= 0
                            ? "This wood job has no finish line. Shall we choose how many logs to gather?"
                            : "Eight logs may be a little tiny. Want to choose a bigger wood goal?")
                    : null;
            case "mine" -> current <= 0
                    ? mineQuantity(task, node)
                    : null;
            case "harvest" -> current <= 0
                    ? new TaskSuggestion(Kind.QUANTITY, task, node, 64, 16, "crops",
                    "This harvest has no stopping point. Shall we give it a crop goal?")
                    : null;
            default -> null;
        };
    }

    private static TaskSuggestion mineQuantity(TaskGraph task, TaskNode node) {
        String targets = node.params == null ? "" : node.params.getOrDefault("targets", "");
        boolean bulkStone = containsAny(targets, "stone", "cobblestone", "deepslate", "netherrack");
        int amount = bulkStone ? 128 : 32;
        int step = bulkStone ? 32 : 8;
        String noun = bulkStone ? "stone blocks" : "blocks";
        String runs = node.repeat == 0 ? "repeat forever" : node.repeat == 1
                ? "run once" : "run " + node.repeat + " times";
        return new TaskSuggestion(Kind.QUANTITY, task, node, amount, step, "blocks",
                "This Mine node is set to " + runs + ", but each run has no block goal and can "
                        + "still continue forever. Shall we set a goal for the next run—maybe "
                        + amount + " " + noun + "?");
    }

    private static TaskSuggestion toolSuggestion(TaskGraph task, TaskNode node) {
        if (node == null || !"mine".equals(node.commandId) || isMonitorNode(task, node)
                || isWired(node, "auto_tool") || boolParam(node, "auto_tool", true)) {
            return null;
        }
        String targets = node.params == null ? "" : node.params.getOrDefault("targets", "");
        if (!containsAny(targets, "ore", "stone", "deepslate", "obsidian", "ancient_debris",
                "mineable/pickaxe", "mineable/axe", "mineable/shovel")) {
            return null;
        }
        return new TaskSuggestion(Kind.AUTO_TOOL, task, node, 0, 0, "",
                "This mining step may need a proper tool, but tool preparation is off. Shall I turn it on?");
    }

    private enum ToolNeed {
        NONE, PICKAXE, AXE
    }

    private record JobProfile(String label, int workload, int toolUses, String unit,
                              boolean producesItems, int extraSlots, boolean underground,
                              boolean surface, ToolNeed tool) {}

    /** One shared estimate drives storage, tool, food, light and night advice. */
    private static JobProfile profile(TaskNode node) {
        if (node == null || node.commandId == null) {
            return null;
        }
        return switch (node.commandId) {
            case "chop" -> {
                int amount = positiveOr(intParam(node, "limit", 8), 32);
                yield new JobProfile("wood run", amount, amount, "log", true, 2,
                        false, true, ToolNeed.AXE);
            }
            case "mine" -> {
                int amount = positiveOr(intParam(node, "limit", 0),
                        containsAny(param(node, "targets"), "stone", "deepslate", "netherrack")
                                ? 128 : 32);
                yield new JobProfile("mining run", amount, amount, "block", true, 2,
                        isUndergroundMine(node), false, mineTool(node));
            }
            case "harvest" -> {
                int amount = positiveOr(intParam(node, "limit", 0), 64);
                yield new JobProfile("harvest", amount, amount, "crop", true, 3,
                        false, true, ToolNeed.NONE);
            }
            case "tunnel" -> {
                int length = intParam(node, "length", 64);
                int height = intParam(node, "height", 2);
                yield new JobProfile("tunnel", length, safeMultiply(length, height), "block",
                        false, 0, true, false, ToolNeed.PICKAXE);
            }
            case "stripmine" -> {
                int branches = intParam(node, "branches", 8);
                int branchLength = intParam(node, "branch_length", 32);
                int amount = safeMultiply(branches, branchLength);
                yield new JobProfile("stripmine", amount, safeMultiply(amount, 2), "block",
                        true, 3, true, false, ToolNeed.PICKAXE);
            }
            case "bridge" -> {
                int length = intParam(node, "length", 16);
                yield new JobProfile("bridge build", length, 0, "block", false, 0,
                        false, true, ToolNeed.NONE);
            }
            case "explore" -> {
                int distance = safeMultiply(intParam(node, "attempts", 6),
                        intParam(node, "step", 12));
                yield new JobProfile("exploration route", distance, 0, "block", false, 0,
                        false, true, ToolNeed.NONE);
            }
            case "walk", "run" -> {
                int distance = intParam(node, "distance", 32);
                yield new JobProfile("travel route", distance, 0, "block", false, 0,
                        false, true, ToolNeed.NONE);
            }
            case "huntendermen" -> hunt(node, "count", 12, "enderman hunt", "pearl");
            case "huntblazes" -> hunt(node, "count", 8, "blaze hunt", "rod");
            case "huntcreepers" -> hunt(node, "count", 16, "creeper hunt", "drop");
            case "huntskeletons" -> hunt(node, "count", 16, "skeleton hunt", "drop");
            case "huntsheep" -> hunt(node, "count", 16, "sheep hunt", "wool");
            default -> null;
        };
    }

    private static JobProfile hunt(TaskNode node, String countParam, int fallback, String label,
                                   String unit) {
        int amount = intParam(node, countParam, fallback);
        return new JobProfile(label, amount, 0, unit, true, 2,
                false, true, ToolNeed.NONE);
    }

    private static ToolNeed mineTool(TaskNode node) {
        String targets = param(node, "targets");
        if (containsAny(targets, "mineable/axe", "logs", "wood", "stem")) {
            return ToolNeed.AXE;
        }
        return ToolNeed.PICKAXE;
    }

    /** Only filters that cannot sweep tools, food, or unrelated inventory are auto-inserted. */
    private static String depositFilter(TaskNode node) {
        if (node == null) {
            return null;
        }
        return switch (node.commandId) {
            case "chop" -> "Logs";
            case "harvest" -> "Crops";
            case "mine" -> containsAny(param(node, "targets"),
                    "stone", "cobblestone", "deepslate", "netherrack") ? "Stone" : "Ores";
            case "stripmine" -> containsAny(param(node, "target"),
                    "stone", "cobblestone", "deepslate", "netherrack") ? "Stone" : "Ores";
            default -> null;
        };
    }

    private static String toolName(JobProfile job) {
        return job != null && job.tool == ToolNeed.AXE ? "Axe" : "Pickaxe";
    }

    private static String toolMaterial(TaskNode node) {
        String targets = param(node, "targets") + "," + param(node, "target");
        if (containsAny(targets, "obsidian", "ancient_debris")) {
            return "Diamond";
        }
        if (containsAny(targets, "diamond", "emerald", "gold", "redstone")) {
            return "Iron";
        }
        return "Stone";
    }

    private static String nodeName(TaskNode node) {
        if (node == null || node.commandId == null || node.commandId.isBlank()) {
            return "Task";
        }
        return switch (node.commandId) {
            case "chop" -> "Chop Wood";
            case "gettool" -> "Get Tools";
            case "stripmine" -> "Stripmine";
            default -> Character.toUpperCase(node.commandId.charAt(0))
                    + node.commandId.substring(1).replace('_', ' ');
        };
    }

    private static boolean isUndergroundMine(TaskNode node) {
        int yMax = intParam(node, "y_max", 320);
        String targets = param(node, "targets");
        return yMax <= 64 || containsAny(targets, "ore", "stone", "deepslate", "netherrack",
                "ancient_debris", "mineable/pickaxe");
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int safeMultiply(int left, int right) {
        long value = (long) Math.max(0, left) * Math.max(0, right);
        return (int) Math.min(4096L, value);
    }

    private static String param(TaskNode node, String name) {
        return node.params == null ? "" : node.params.getOrDefault(name, "");
    }

    private static boolean hasCommand(TaskGraph task, String commandId) {
        if (task == null || task.nodes == null) {
            return false;
        }
        for (TaskNode node : task.nodes) {
            if (node != null && commandId.equals(node.commandId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDownstreamCommand(TaskGraph task, TaskNode start,
                                                String commandId) {
        Set<TaskNode> visited = new HashSet<>();
        List<TaskNode> pending = new ArrayList<>(TaskWiring.outgoing(task, start));
        while (!pending.isEmpty()) {
            TaskNode current = pending.remove(pending.size() - 1);
            if (current == null || !visited.add(current)) {
                continue;
            }
            if (commandId.equals(current.commandId)) {
                return true;
            }
            pending.addAll(TaskWiring.outgoing(task, current));
        }
        return false;
    }

    private static String incompatibleTargetGroup(String dimension, String targets) {
        List<String> selected = splitTargets(targets);
        if (selected.isEmpty()) {
            return null;
        }
        if ("minecraft:overworld".equals(dimension)
                && selected.stream().allMatch(TaskSuggestion::isNetherTarget)) {
            return "Nether";
        }
        if (!"minecraft:the_end".equals(dimension)
                && selected.stream().allMatch(TaskSuggestion::isEndTarget)) {
            return "End";
        }
        if (("minecraft:the_nether".equals(dimension) || "minecraft:the_end".equals(dimension))
                && selected.stream().allMatch(TaskSuggestion::isOverworldTarget)) {
            return "Overworld";
        }
        return null;
    }

    private static List<String> splitTargets(String targets) {
        List<String> result = new ArrayList<>();
        if (targets == null) {
            return result;
        }
        for (String value : targets.toLowerCase(Locale.ROOT).split(",")) {
            if (!value.isBlank()) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private static boolean isNetherTarget(String target) {
        return containsAny(target, "netherrack", "nether_", "ancient_debris", "blackstone",
                "basalt", "soul_sand", "soul_soil", "crimson", "warped");
    }

    private static boolean isEndTarget(String target) {
        return containsAny(target, "end_stone", "chorus", "purpur");
    }

    private static boolean isOverworldTarget(String target) {
        return containsAny(target, "deepslate", "overworld", "stone_ore_replaceables",
                "coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore",
                "lapis_ore", "diamond_ore", "emerald_ore")
                && !containsAny(target, "nether_gold_ore");
    }

    private static String normal(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String dimensionLabel(String dimension) {
        return switch (dimension) {
            case "minecraft:the_nether" -> "the Nether";
            case "minecraft:the_end" -> "the End";
            case "minecraft:overworld" -> "the Overworld";
            default -> "this dimension";
        };
    }

    private static boolean isWired(TaskNode node, String param) {
        return node.inputLinks != null && node.inputLinks.get(param) != null;
    }

    private static boolean isMonitorNode(TaskGraph task, TaskNode candidate) {
        return candidate == null || candidate.isClockNode()
                || TaskWiring.isMonitorOnly(task, candidate);
    }

    private static boolean isLooped(TaskGraph task, TaskNode candidate) {
        return TaskWiring.isInFlowCycle(task, candidate);
    }

    private static int intParam(TaskNode node, String name, int fallback) {
        if (node.params == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(node.params.getOrDefault(name, String.valueOf(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean boolParam(TaskNode node, String name, boolean fallback) {
        if (node.params == null || !node.params.containsKey(name)) {
            return fallback;
        }
        return Boolean.parseBoolean(node.params.get(name));
    }

    private static boolean containsAny(String value, String... needles) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private TaskSuggestion() {
        this(null, null, null, 0, 0, "", "");
    }
}
