package com.etka.lune.training;

import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.TaskSignalLink;

/**
 * The answer key used by Lune's five-request reveal.
 *
 * <p>Keeping this beside the lessons means the visible answer follows the same graph rules as the
 * tests and the hand-written exercise. The first phase only places the missing card; the second
 * phase wires and configures it.</p>
 */
public final class TrainingAnswer {

    private TrainingAnswer() {}

    /** Place the lesson's missing card without connecting it yet. */
    public static TaskNode applyCard(TrainingLesson lesson, TaskGraph task) {
        TaskNode existing = task.nodeById("answer");
        if (existing != null) {
            return existing;
        }
        TaskNode answer = new TaskNode(lesson.answerCommandId());
        answer.id = "answer";
        CommandDef definition = CommandRegistry.byId(lesson.answerCommandId());
        if (definition != null) {
            answer.params.putAll(definition.snapshot());
        }
        position(lesson.id(), answer);
        task.nodes.add(answer);
        return answer;
    }

    /** Complete a lesson's graph after its card has been shown. */
    public static void solve(TrainingLesson lesson, TaskGraph task) {
        TaskNode answer = applyCard(lesson, task);
        switch (lesson.id()) {
            case "start" -> {
                answer.onSuccess = "chop";
            }
            case "tool" -> {
                task.nodeById("start").onSuccess = answer.id;
                answer.onSuccess = "mine";
                answer.onFailure = "mine";
            }
            case "guard" -> {
                answer.repeat = 0;
                task.nodeById("safety_clock").alwaysTargets.add(answer.id);
            }
            case "scout" -> {
                TaskNode loot = task.nodeById("loot");
                loot.onSuccess = answer.id;
                loot.onFailure = answer.id;
                answer.onSuccess = "chop";
                answer.onFailure = "chop";
                answer.params.put("radius", "16");
            }
            case "collect" -> {
                TaskNode chop = task.nodeById("chop");
                chop.onSuccess = answer.id;
                chop.onFailure = answer.id;
                answer.onSuccess = "end";
                answer.onFailure = "end";
            }
            case "fanout" -> {
                answer.signalInputCount = 1;
                answer.signalOutputCount = 2;
                task.nodeById("pulse").alwaysTargets.add(answer.id);
                answer.signalLinks.clear();
                answer.signalLinks.add(new TaskSignalLink(0, "sweep", -1));
                answer.signalLinks.add(new TaskSignalLink(1, "top_up", -1));
            }
            case "delay" -> {
                answer.params.put("seconds", "2");
                TaskNode button = task.nodeById("button");
                button.signalLinks.clear();
                button.signalLinks.add(new TaskSignalLink(0, answer.id, -1));
                answer.signalLinks.clear();
                answer.signalLinks.add(new TaskSignalLink(0, "loot", -1));
            }
            case "manual" -> {
                answer.signalLinks.clear();
                answer.signalLinks.add(new TaskSignalLink(0, "loot", -1));
            }
            case "count" -> {
                answer.params.put("count", "3");
                TaskNode pulse = task.nodeById("pulse");
                pulse.alwaysTargets.clear();
                pulse.alwaysTargets.add(answer.id);
                answer.signalLinks.clear();
                answer.signalLinks.add(new TaskSignalLink(0, "loot", -1));
            }
            case "store" -> {
                answer.params.put("filter", "Crops");
                TaskNode loot = task.nodeById("loot");
                loot.onSuccess = answer.id;
                loot.onFailure = answer.id;
                answer.onSuccess = "end";
                answer.onFailure = "end";
            }
            default -> throw new IllegalArgumentException("No answer for lesson " + lesson.id());
        }
    }

    private static void position(String lessonId, TaskNode node) {
        int x;
        int y;
        switch (lessonId) {
            case "start" -> { x = 24; y = 26; }
            case "tool", "delay", "count" -> { x = 232; y = 26; }
            case "guard" -> { x = 232; y = 158; }
            case "scout" -> { x = 648; y = 26; }
            case "collect" -> { x = 440; y = 26; }
            case "fanout" -> { x = 232; y = 26; }
            case "manual" -> { x = 24; y = 26; }
            case "store" -> { x = 648; y = 26; }
            default -> { x = 232; y = 26; }
        }
        node.editorX = x;
        node.editorY = y;
    }
}
