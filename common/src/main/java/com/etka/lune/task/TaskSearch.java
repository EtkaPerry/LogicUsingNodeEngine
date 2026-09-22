package com.etka.lune.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Finding one card on a canvas that has stopped fitting on the screen.
 *
 * <p>Matching is deliberately generous: a card answers to its title, to the command id underneath
 * that title, and to anything the player typed into its parameters. The id matters because a title
 * is translated and a search that only read titles would quietly answer differently in Turkish;
 * the parameters matter because "which card was the one looking for deepslate?" is the question
 * actually being asked, and the answer is written in a box the search would otherwise not open.</p>
 *
 * <p>Results come back in reading order rather than in graph order. The player is looking at a
 * picture, and stepping through matches should walk the picture - left to right, top to bottom -
 * not follow a list order they have never seen.</p>
 */
public final class TaskSearch {

    public enum Kind { CARD, NOTE, GROUP }

    /**
     * One thing worth looking at.
     *
     * @param id     the card, note or group id, so the caller can find the live object again
     * @param label  what to say about it in the result line
     * @param matched which part of it answered: a title, an id, or a parameter
     */
    public record Hit(Kind kind, String id, String label, String matched, int centerX,
                      int centerY) {}

    private TaskSearch() {}

    public static List<Hit> find(TaskGraph task, String query, Function<TaskNode, String> nameOf,
                                 ToIntFunction<TaskNode> heightOf) {
        List<Hit> hits = new ArrayList<>();
        if (task == null || query == null) {
            return hits;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return hits;
        }
        if (task.nodes != null) {
            for (TaskNode node : task.nodes) {
                Hit hit = match(node, needle, nameOf, heightOf);
                if (hit != null) {
                    hits.add(hit);
                }
            }
        }
        if (task.notes != null) {
            for (TaskNote note : task.notes) {
                if (note != null && contains(note.text, needle)) {
                    hits.add(new Hit(Kind.NOTE, note.id, firstLine(note.text), note.text,
                            note.x + Math.max(TaskNote.MIN_WIDTH, note.width) / 2,
                            note.y + Math.max(TaskNote.MIN_HEIGHT, note.height) / 2));
                }
            }
        }
        if (task.groups != null) {
            for (TaskGroup group : task.groups) {
                if (group != null && contains(group.title, needle)) {
                    hits.add(new Hit(Kind.GROUP, group.id, group.title, group.title,
                            group.x + Math.max(TaskGroup.MIN_WIDTH, group.width) / 2,
                            group.y + Math.max(TaskGroup.HEADER_HEIGHT, group.height) / 2));
                }
            }
        }
        hits.sort(Comparator.comparingInt(Hit::centerY).thenComparingInt(Hit::centerX));
        return hits;
    }

    private static Hit match(TaskNode node, String needle, Function<TaskNode, String> nameOf,
                             ToIntFunction<TaskNode> heightOf) {
        if (node == null || node.id == null) {
            return null;
        }
        String name = nameOf == null ? node.commandId : nameOf.apply(node);
        String matched = null;
        if (contains(name, needle)) {
            matched = name;
        } else if (contains(node.commandId, needle)) {
            matched = node.commandId;
        } else if (node.params != null) {
            for (Map.Entry<String, String> entry : node.params.entrySet()) {
                if (contains(entry.getValue(), needle) || contains(entry.getKey(), needle)) {
                    matched = entry.getKey() + " = " + entry.getValue();
                    break;
                }
            }
        }
        if (matched == null) {
            return null;
        }
        int height = heightOf == null ? TaskCanvas.CARD_HEIGHT
                : Math.max(1, heightOf.applyAsInt(node));
        return new Hit(Kind.CARD, node.id, name == null ? node.commandId : name, matched,
                TaskCanvas.left(node) + TaskCanvas.CARD_WIDTH / 2, TaskCanvas.top(node) + height / 2);
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    /** A note is a paragraph; a result line is one line, so it gets the first one. */
    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() <= 40 ? line : line.substring(0, 39) + "…";
    }
}
