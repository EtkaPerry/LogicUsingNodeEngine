package com.etka.lune.training;

import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The course: ten routines with one card missing from each, ordered easiest first.
 *
 * <p>Every puzzle is a routine that <em>runs</em> once it is fixed, in the shapes the seeded jobs in
 * {@link com.etka.lune.task.DefaultTasks} already use. That is deliberate. A puzzle invented purely
 * to have an answer teaches a shape nobody will meet again; these teach the four things a player has
 * to know before their own graphs stop surprising them - where power comes from, that order is
 * meaningful, that nothing is implicit, and that pulses are not outcomes.</p>
 *
 * <h2>Steps 3 and 4 are the two that cost real runs</h2>
 *
 * <p>Step 3 is the failure the benchmark notes record: two of six wood-benchmark runs ended
 * with the player dead, one drowned and one beaten to nothing, because the routine had no Self
 * Preservation card. Step 4 is the other one: four of fifteen measured runs froze for five to eight
 * minutes because a loop had no card in it that moved the bot. Both are taught here as puzzles
 * rather than as paragraphs because both look completely fine on the canvas until they are not.</p>
 */
public final class TrainingCourse {

    /** Editor spacing, matched to the seeded jobs so a solved puzzle looks like a shipped routine. */
    private static final int STEP_X = 208;
    private static final int STEP_Y = 132;

    /**
     * Cards the palette offers itself rather than the command registry.
     *
     * <p>Held here so the lessons can name them the same way they name commands, and so a test can
     * tell a deliberate general-node answer from a typo in a command id.</p>
     */
    public static final Set<String> GENERAL_NODES = Set.of(
            TaskNode.START_COMMAND, TaskNode.ALWAYS_COMMAND, TaskNode.PULSE_COMMAND,
            TaskNode.SIGNAL_RELAY_COMMAND, TaskNode.OBSERVER_COMMAND, TaskNode.BUTTON_COMMAND,
            TaskNode.END_COMMAND, TaskNode.TIMER_COMMAND, TaskNode.COUNTER_COMMAND);

    private static final List<TrainingLesson> LESSONS = List.of(
            new TrainingLesson("start", TaskNode.START_COMMAND,
                    List.of(TaskNode.ALWAYS_COMMAND, TaskNode.BUTTON_COMMAND),
                    TrainingCourse::missingStart),

            new TrainingLesson("tool", "gettool",
                    List.of("select_item", "loot"),
                    // Position is the lesson. A Get Tools wired in after Mine is a graph the audit
                    // has no complaint about and a routine that still swings bare hands at stone.
                    "mine",
                    TrainingCourse::missingTool),

            new TrainingLesson("guard", "self_preservation",
                    List.of("eat", "sleep"),
                    TrainingCourse::missingGuard),

            new TrainingLesson("scout", "explore",
                    List.of("find", "waypoint"),
                    TrainingCourse::missingScout),

            new TrainingLesson("collect", "loot", List.of("deposit", "select_item"),
                    TrainingCourse::missingCollection),

            new TrainingLesson("fanout", TaskNode.SIGNAL_RELAY_COMMAND,
                    List.of(TaskNode.COUNTER_COMMAND, TaskNode.TIMER_COMMAND),
                    TrainingCourse::missingFanout),

            new TrainingLesson("delay", TaskNode.TIMER_COMMAND, List.of(TaskNode.COUNTER_COMMAND, TaskNode.SIGNAL_RELAY_COMMAND),
                    "loot", TrainingCourse::missingDelay),

            new TrainingLesson("manual", TaskNode.BUTTON_COMMAND, List.of(TaskNode.START_COMMAND, TaskNode.PULSE_COMMAND),
                    TrainingCourse::missingButton),

            new TrainingLesson("count", TaskNode.COUNTER_COMMAND, List.of(TaskNode.TIMER_COMMAND, TaskNode.SIGNAL_RELAY_COMMAND),
                    "loot", TrainingCourse::missingCounter),

            new TrainingLesson("store", "deposit", List.of("loot", "select_item"),
                    TaskNode.END_COMMAND, TrainingCourse::missingStorage));

    private TrainingCourse() {}

    /** The lessons in course order; the index is the step number the map draws. */
    public static List<TrainingLesson> lessons() {
        return LESSONS;
    }

    public static Optional<TrainingLesson> byId(String id) {
        return LESSONS.stream().filter(lesson -> lesson.id().equals(id)).findFirst();
    }

    // --- the broken routines -------------------------------------------------

    /** Chop and Loot, correctly wired to each other, with no power source above them. */
    private static TaskGraph missingStart() {
        TaskGraph task = new TaskGraph();
        TaskNode chop = chop("chop");
        TaskNode loot = loot("loot");
        chop.onSuccess = loot.id;
        chop.onFailure = loot.id;
        // Column 1, not 0: the gap on the left is where the answer goes, and an empty column is a
        // clearer prompt than any arrow drawn into it.
        add(task, chop, 1, 0);
        add(task, loot, 2, 0);
        return task;
    }

    /** START straight into Mine, with auto-tool off so the missing pickaxe is the routine's problem. */
    private static TaskGraph missingTool() {
        TaskGraph task = new TaskGraph();
        TaskNode mine = node("mine", "mine", Map.of(
                "targets", "minecraft:stone",
                "radius", "32", "y_min", "-64", "y_max", "320", "limit", "20",
                "auto_tool", "false", "prospect", "false"));
        TaskNode loot = loot("loot");
        TaskNode end = node("end", TaskNode.END_COMMAND, Map.of());
        mine.onSuccess = loot.id;
        mine.onFailure = loot.id;
        // Closed off deliberately. With Loot's Success pin left open, a card merely dropped on the
        // canvas is picked up by list-order fallthrough and joins the routine at the end - which is
        // the one place this particular card is no use, and the puzzle would have accepted it.
        loot.onSuccess = end.id;
        loot.onFailure = end.id;
        add(task, start(mine.id), 0, 0);
        // Two columns of clearance, because the answer belongs between START and Mine.
        add(task, mine, 2, 0);
        add(task, loot, 3, 0);
        add(task, end, 4, 0);
        return task;
    }

    /** A chopping loop plus the empty safety circuit the seeded jobs all carry. */
    private static TaskGraph missingGuard() {
        TaskGraph task = new TaskGraph();
        TaskNode chop = chop("chop");
        TaskNode loot = loot("loot");
        chop.onSuccess = loot.id;
        chop.onFailure = loot.id;
        loot.onSuccess = chop.id;
        loot.onFailure = chop.id;
        add(task, start(chop.id), 0, 0);
        add(task, chop, 1, 0);
        add(task, loot, 2, 0);

        // The clock is wired to nothing, which the audit reports in its own words the moment the
        // puzzle opens. The player is being asked to finish a circuit somebody started, not to
        // guess that a circuit was wanted.
        add(task, node("safety_clock", TaskNode.ALWAYS_COMMAND, Map.of()), 0, 1);
        return task;
    }

    /** A closed Chop/Loot loop with nothing in it that travels. */
    private static TaskGraph missingScout() {
        TaskGraph task = new TaskGraph();
        TaskNode chop = chop("chop");
        TaskNode loot = loot("loot");
        chop.onSuccess = loot.id;
        chop.onFailure = loot.id;
        // The outgoing loop wires are the exercise: the answer card belongs between Loot and Chop.
        loot.onSuccess = null;
        loot.onFailure = null;
        add(task, start(chop.id), 0, 0);
        add(task, chop, 1, 0);
        add(task, loot, 2, 0);
        return task;
    }

    private static TaskGraph missingCollection() {
        TaskGraph task = new TaskGraph();
        TaskNode chop = chop("chop");
        TaskNode end = node("end", TaskNode.END_COMMAND, Map.of());
        chop.onSuccess = end.id;
        chop.onFailure = end.id;
        add(task, start(chop.id), 0, 0);
        add(task, chop, 1, 0);
        add(task, end, 3, 0);
        return task;
    }

    private static TaskGraph collectingBranch() {
        TaskGraph task = new TaskGraph();
        TaskNode loot = loot("loot");
        TaskNode end = node("end", TaskNode.END_COMMAND, Map.of());
        loot.onSuccess = end.id;
        loot.onFailure = end.id;
        add(task, loot, 2, 0);
        add(task, end, 3, 0);
        return task;
    }

    private static TaskGraph missingDelay() {
        TaskGraph task = collectingBranch();
        TaskNode button = node("button", TaskNode.BUTTON_COMMAND, Map.of());
        button.signalLinks.add(new com.etka.lune.task.TaskSignalLink(0, "loot", -1));
        add(task, button, 0, 0);
        return task;
    }

    private static TaskGraph missingButton() {
        return collectingBranch();
    }

    private static TaskGraph missingCounter() {
        TaskGraph task = collectingBranch();
        TaskNode pulse = node("pulse", TaskNode.PULSE_COMMAND, Map.of());
        pulse.alwaysIntervalSeconds = 5;
        pulse.alwaysTargets.add("loot");
        add(task, pulse, 0, 0);
        return task;
    }

    private static TaskGraph missingStorage() {
        TaskGraph task = collectingBranch();
        TaskNode harvest = node("harvest", "harvest", Map.of());
        harvest.onSuccess = "loot";
        harvest.onFailure = "loot";
        add(task, start(harvest.id), 0, 0);
        add(task, harvest, 1, 0);
        task.nodeById("end").editorX = 24 + 4 * STEP_X;
        return task;
    }

    /** One clock and two finished circuits with no way to reach either of them. */
    private static TaskGraph missingFanout() {
        TaskGraph task = new TaskGraph();
        TaskNode pulse = node("pulse", TaskNode.PULSE_COMMAND, Map.of());
        pulse.alwaysIntervalSeconds = 30;

        TaskNode loot = loot("sweep");
        TaskNode sweepEnd = node("sweep_end", TaskNode.END_COMMAND, Map.of());
        loot.onSuccess = sweepEnd.id;
        loot.onFailure = sweepEnd.id;

        TaskNode chop = chop("top_up");
        TaskNode chopEnd = node("top_up_end", TaskNode.END_COMMAND, Map.of());
        chop.onSuccess = chopEnd.id;
        chop.onFailure = chopEnd.id;

        // The clock leads the list so the audit reports the empty source first; the orphaned
        // branches below it are a consequence of that, not a second unrelated fault.
        add(task, pulse, 0, 0);
        add(task, loot, 2, 0);
        add(task, sweepEnd, 3, 0);
        add(task, chop, 2, 1);
        add(task, chopEnd, 3, 1);
        return task;
    }

    // --- card helpers --------------------------------------------------------

    private static TaskNode chop(String id) {
        return node(id, "chop", Map.of("radius", "48", "limit", "8"));
    }

    private static TaskNode loot(String id) {
        return node(id, "loot", Map.of("radius", "16"));
    }

    private static TaskNode start(String firstCard) {
        TaskNode start = node("start", TaskNode.START_COMMAND, Map.of());
        start.onSuccess = firstCard;
        return start;
    }

    private static TaskNode node(String id, String commandId, Map<String, String> params) {
        TaskNode node = new TaskNode(commandId);
        node.id = id;
        node.params.putAll(params);
        return node;
    }

    private static void add(TaskGraph task, TaskNode node, int column, int row) {
        node.editorX = 24 + column * STEP_X;
        node.editorY = 26 + row * STEP_Y;
        task.nodes.add(node);
    }
}
