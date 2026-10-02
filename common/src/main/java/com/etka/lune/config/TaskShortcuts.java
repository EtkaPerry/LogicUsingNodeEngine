package com.etka.lune.config;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which key starts which task: the rules for {@link BotConfig#taskShortcuts}.
 *
 * <p>Kept by task name, the way {@link BotConfig#taskViews} is, because the name is a task's
 * identity on disk; the editor moves an entry when its task is renamed and drops it when the task
 * is deleted. The key is kept under the name the game itself writes into {@code options.txt} -
 * {@code key.keyboard.keypad.1} - and never as a number. 26.3 moved every key code when it swapped
 * GLFW for SDL, and the names are the half that stayed put, so a key given on one version still
 * starts the same task on the next.</p>
 *
 * <p>A key starts one task at most. Giving it to a second task moves it there, rather than leaving
 * one press to start two tasks when the bot can only run one of them.</p>
 *
 * <p>Nothing here touches the game or the file, so the rules can be tested headless: the map is
 * whatever the caller hands over, which in the game is the live one in {@link BotConfig}.</p>
 */
public final class TaskShortcuts {

    /** Where every key on the number pad is named, on every version Lune builds for. */
    private static final String KEYPAD = "key.keyboard.keypad.";

    /** Keys that are held while another is pressed, and never pressed for their own sake. */
    private static final Set<String> MODIFIERS = Set.of(
            "key.keyboard.left.shift", "key.keyboard.right.shift",
            "key.keyboard.left.control", "key.keyboard.right.control",
            "key.keyboard.left.alt", "key.keyboard.right.alt",
            "key.keyboard.left.win", "key.keyboard.right.win");

    private final Map<String, String> byTask;

    public TaskShortcuts(Map<String, String> byTask) {
        this.byTask = byTask;
    }

    /** The key that starts this task, or null when it has none. */
    public String keyOf(String task) {
        return task == null ? null : byTask.get(task);
    }

    /** The task this key starts, or null when it starts none. */
    public String taskOf(String key) {
        if (key == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : byTask.entrySet()) {
            if (key.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Gives {@code key} to {@code task}, taking it from whichever task had it before.
     *
     * @return the task the key used to start, so the player can be told it moved; null when the
     *         key was free or already this task's
     */
    public String assign(String task, String key) {
        String previous = taskOf(key);
        if (previous != null && !previous.equals(task)) {
            byTask.remove(previous);
        } else {
            previous = null;
        }
        byTask.put(task, key);
        return previous;
    }

    /** Takes the task's key away; true when it had one. */
    public boolean clear(String task) {
        return task != null && byTask.remove(task) != null;
    }

    /** Moves a task's key with it when the task is renamed. */
    public void rename(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        String key = byTask.remove(from);
        if (key != null) {
            byTask.put(to, key);
        }
    }

    /** Drops the keys of tasks that are being deleted. */
    public void forget(Collection<String> tasks) {
        byTask.keySet().removeAll(new HashSet<>(tasks));
    }

    /**
     * Drops the key of every task that is no longer there - taken by an undo, or by editing the
     * file by hand. True when anything went.
     */
    public boolean retainOnly(Collection<String> existing) {
        return byTask.keySet().retainAll(new HashSet<>(existing));
    }

    /**
     * What a number-pad key has printed on it - {@code 1} for Keypad 1, {@code +} for Keypad + -
     * or null for every other key.
     *
     * <p>For the short label a badge wears. The game's own name for these keys is too wide for a
     * list row, and the name the system gives them on some platforms is a bare {@code 1} that
     * cannot be told apart from the 1 above the letters. That is why the number pad is the one set
     * of keys Lune writes a label for rather than asking the game.</p>
     */
    public static String keypadSymbol(String key) {
        if (key == null || !key.startsWith(KEYPAD)) {
            return null;
        }
        String rest = key.substring(KEYPAD.length());
        return switch (rest) {
            case "add" -> "+";
            case "subtract" -> "-";
            case "multiply" -> "*";
            case "divide" -> "/";
            case "decimal" -> ".";
            case "equal" -> "=";
            default -> !rest.isEmpty() && rest.chars().allMatch(c -> c >= '0' && c <= '9')
                    ? rest : null;
        };
    }

    /**
     * True for Shift, Control, Alt and the system key.
     *
     * <p>They are held while another key is pressed, so the editor waits past them for the key
     * that comes next: a player reaching for Ctrl+1 means the 1.</p>
     */
    public static boolean isModifier(String key) {
        return key != null && MODIFIERS.contains(key);
    }
}
