package com.etka.lune.training;

import com.etka.lune.util.Lang;
import com.etka.lune.task.TaskConnectionAudit;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.TaskWiring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One rung of the course: a task with a hole in it, the card that fills the hole, and two wrong
 * answers worth considering.
 *
 * <p>A lesson owns its own marking. There is no separate answer key and no puzzle-only notion of
 * "correct", because {@link TaskConnectionAudit} already answers "is this graph whole?" for the
 * editor and {@link TaskWiring#poweredNodes} already answers "does this card actually run?" for the
 * safety check. A second opinion on either would drift from the one the player sees on the canvas,
 * and then a puzzle would pass a task the editor calls broken.</p>
 *
 * <h2>The decoys are the lesson</h2>
 *
 * <p>Two wrong cards are offered beside the right one, and they are chosen to be <em>tempting</em>.
 * Eat next to Self Preservation is a real decision - both keep the bot alive, only one of them
 * watches for lava. Chop Wood next to Self Preservation is not a decision at all, and a puzzle made
 * of those teaches nothing but reading speed.</p>
 *
 * @param answerCommandId the command id of the missing card; general nodes like
 *                        {@link TaskNode#START_COMMAND} are named the same way commands are
 * @param decoyCommandIds two plausible wrong answers
 * @param orderBefore     a command the answer must reach, or null when position does not matter;
 *                        see {@link #runsBefore}
 * @param starter         builds a fresh broken task; never returns the same instance twice,
 *                        because the player edits what they are given
 */
public record TrainingLesson(String id, String answerCommandId,
                             List<String> decoyCommandIds, String orderBefore,
                             Supplier<TaskGraph> starter) {

    public TrainingLesson {
        decoyCommandIds = List.copyOf(decoyCommandIds);
    }

    /** A lesson whose answer is right wherever in the task it ends up. */
    public TrainingLesson(String id, String answerCommandId,
                          List<String> decoyCommandIds, Supplier<TaskGraph> starter) {
        this(id, answerCommandId, decoyCommandIds, null, starter);
    }

    /** The lesson's heading. */
    public String title() {
        return Lang.get("lune.training." + id + ".title");
    }

    /** What is wrong with the task the player is handed. */
    public String about() {
        return Lang.get("lune.training." + id + ".about");
    }

    /** The nudge shown once they have been stuck for a while. */
    public String hint() {
        return Lang.get("lune.training." + id + ".hint");
    }

    /** A fresh copy of the broken task, named so the store knows it is scratch. */
    public TaskGraph newAttempt() {
        TaskGraph attempt = starter.get();
        attempt.name = com.etka.lune.task.TaskStore.TRAINING_PREFIX + title();
        return attempt;
    }

    /**
     * True when the graph is whole <em>and</em> the missing card is the one doing the work.
     *
     * <p>Both halves are needed. A player who deletes the broken half of the task has a graph
     * the audit is perfectly happy with and has learned nothing, and a player who drops the right
     * card on the canvas without wiring it has the card but not the task.</p>
     *
     * <p>Sources are exempt from the powered test rather than special-cased around it: a START or a
     * Pulse supplies power instead of receiving it, so it is never in the powered set. The audit
     * covers them instead - it already reports a START with nothing connected and a clock with no
     * targets.</p>
     */
    public boolean isSolvedBy(TaskGraph attempt) {
        if (attempt == null || attempt.nodes == null
                || TaskConnectionAudit.firstIssue(attempt).isPresent()) {
            return false;
        }
        // Keep the actual exercise: deleting or replacing its original work is not a repair.
        for (TaskNode original : starter.get().nodes) {
            TaskNode retained = attempt.nodeById(original.id);
            if (retained == null || !Objects.equals(original.commandId, retained.commandId)) return false;
        }
        Set<TaskNode> powered = TaskWiring.poweredNodes(attempt);
        return attempt.nodes.stream()
                .filter(Objects::nonNull)
                .filter(node -> answerCommandId.equals(node.commandId))
                .anyMatch(node -> (node.isSourceNode() || powered.contains(node))
                        && (node.isSourceNode() || TaskWiring.hasIncomingConnection(attempt, node))
                        && runsBefore(attempt, node) && meetsObjective(attempt, node));
    }

    /** Lesson-specific outcomes beyond having a structurally valid graph. */
    private boolean meetsObjective(TaskGraph task, TaskNode answer) {
        return switch (id) {
            case "tool" -> answer.id.equals(task.nodeById("start").onSuccess)
                    && "mine".equals(answer.onSuccess);
            case "guard" -> task.nodeById("safety_clock").alwaysTargets.contains(answer.id);
            case "scout" -> answer.id.equals(task.nodeById("loot").onSuccess)
                    && answer.id.equals(task.nodeById("loot").onFailure)
                    && "chop".equals(answer.onSuccess)
                    && number(answer, "radius", 32) < number(task.nodeById("chop"), "radius", 48);
            case "collect" -> answer.id.equals(task.nodeById("chop").onSuccess)
                    && answer.id.equals(task.nodeById("chop").onFailure)
                    && "end".equals(answer.onSuccess) && "end".equals(answer.onFailure);
            case "fanout" -> task.nodeById("pulse").alwaysTargets.contains(answer.id)
                    && linked(answer, "sweep") && linked(answer, "top_up");
            case "delay" -> linked(task.nodeById("button"), answer.id)
                    && !linked(task.nodeById("button"), "loot") && linked(answer, "loot")
                    && number(answer, "seconds", 1) > 0;
            case "manual" -> linked(answer, "loot");
            case "count" -> task.nodeById("pulse").alwaysTargets.contains(answer.id)
                    && !task.nodeById("pulse").alwaysTargets.contains("loot")
                    && linked(answer, "loot") && number(answer, "count", 3) == 3;
            case "store" -> answer.id.equals(task.nodeById("loot").onSuccess)
                    && answer.id.equals(task.nodeById("loot").onFailure)
                    && "end".equals(answer.onSuccess) && "end".equals(answer.onFailure)
                    && Set.of("Crops", "All").contains(answer.params.getOrDefault("filter", "Ores"));
            default -> true;
        };
    }

    private static boolean linked(TaskNode source, String target) {
        return source.signalLinks != null && source.signalLinks.stream()
                .anyMatch(link -> link != null && target.equals(link.targetNodeId));
    }

    private static double number(TaskNode node, String key, double fallback) {
        try {
            return Double.parseDouble(node.params.getOrDefault(key, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    /**
     * True when the answer card can still reach the card it is supposed to run in front of.
     *
     * <p>Only the ordering lessons set {@code orderBefore}, and they need it because a card that
     * merely exists somewhere in the task is not the same as a card that runs at the right
     * moment. Get Tools wired in <em>after</em> Mine is a graph the audit is perfectly happy with,
     * every card powered and every pin connected - and it is precisely the mistake that lesson
     * exists to correct, so marking it right would be worse than not asking.</p>
     *
     * <p>Reachability rather than list position: the task's order is its wiring, and the list is
     * only a fallback for the pins nobody connected.</p>
     */
    private boolean runsBefore(TaskGraph attempt, TaskNode answer) {
        if (orderBefore == null) {
            return true;
        }
        Set<TaskNode> seen = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<TaskNode> pending = new java.util.ArrayDeque<>(
                TaskWiring.outgoing(attempt, answer));
        while (!pending.isEmpty()) {
            TaskNode node = pending.removeFirst();
            if (node == null || !seen.add(node)) {
                continue;
            }
            if (orderBefore.equals(node.commandId)) {
                return true;
            }
            pending.addAll(TaskWiring.outgoing(attempt, node));
        }
        return false;
    }

    /**
     * What is still wrong, in the words the editor would use, or an empty string once it is solved.
     *
     * <p>The audit's own message comes first when there is one. It names the specific card and the
     * specific pin, which is a better teacher than any sentence written here could be - and it is
     * the same sentence the player will meet later on their own tasks.</p>
     */
    public String critique(TaskGraph attempt) {
        if (isSolvedBy(attempt)) {
            return "";
        }
        if (attempt == null) {
            return hint();
        }
        return TaskConnectionAudit.firstIssue(attempt)
                .map(TaskConnectionAudit.Issue::message)
                .orElse(hint());
    }

    /**
     * The three tiles the puzzle palette offers, in a stable but unguessable order.
     *
     * <p>Ordered by a hash of the lesson and the card rather than shuffled, so the answer is not
     * always first and the tiles do not rearrange themselves under the cursor between frames.</p>
     */
    public List<String> choices() {
        List<String> choices = new ArrayList<>(decoyCommandIds);
        choices.add(answerCommandId);
        choices.sort(Comparator.comparingInt(choice -> Objects.hash(id, choice)));
        return List.copyOf(choices);
    }
}
