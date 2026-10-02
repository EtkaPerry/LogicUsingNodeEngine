package com.etka.lune.config;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which key starts which task, tested apart from the config file it is kept in and the keyboard it
 * is pressed on.
 */
class TaskShortcutsTest {

    private static final String NUM_1 = "key.keyboard.keypad.1";
    private static final String NUM_2 = "key.keyboard.keypad.2";

    private final Map<String, String> stored = new LinkedHashMap<>();
    private final TaskShortcuts shortcuts = new TaskShortcuts(stored);

    @Test
    void aKeyStartsTheTaskItWasGivenTo() {
        assertNull(shortcuts.assign("Chop Wood", NUM_1));
        assertEquals(NUM_1, shortcuts.keyOf("Chop Wood"));
        assertEquals("Chop Wood", shortcuts.taskOf(NUM_1));
        assertNull(shortcuts.taskOf(NUM_2));
        assertNull(shortcuts.keyOf("Dig a Tunnel"));
    }

    /**
     * One key, one task. A press can only start one of them, so a key given to a second task moves
     * there - and the caller is told where it came from, so the player can be told too.
     */
    @Test
    void givingATakenKeyMovesItAndSaysFromWhere() {
        shortcuts.assign("Chop Wood", NUM_1);
        assertEquals("Chop Wood", shortcuts.assign("Guard Me", NUM_1));
        assertEquals("Guard Me", shortcuts.taskOf(NUM_1));
        assertNull(shortcuts.keyOf("Chop Wood"));
        assertEquals(1, stored.size());
    }

    /** Pressing the key a task already has is not a move from anywhere. */
    @Test
    void givingATaskItsOwnKeyAgainMovesNothing() {
        shortcuts.assign("Chop Wood", NUM_1);
        assertNull(shortcuts.assign("Chop Wood", NUM_1));
        assertEquals(NUM_1, shortcuts.keyOf("Chop Wood"));
    }

    /** A task has one key; a new one replaces the old, which is then free for any other task. */
    @Test
    void aNewKeyReplacesTheTasksOldOne() {
        shortcuts.assign("Chop Wood", NUM_1);
        shortcuts.assign("Chop Wood", NUM_2);
        assertEquals(NUM_2, shortcuts.keyOf("Chop Wood"));
        assertNull(shortcuts.taskOf(NUM_1));
    }

    @Test
    void clearingTakesTheKeyAwayAndSaysWhetherThereWasOne() {
        shortcuts.assign("Chop Wood", NUM_1);
        assertTrue(shortcuts.clear("Chop Wood"));
        assertFalse(shortcuts.clear("Chop Wood"));
        assertNull(shortcuts.taskOf(NUM_1));
    }

    /** The key is the task's, not the name's: renaming carries it along. */
    @Test
    void renamingATaskTakesItsKeyAlong() {
        shortcuts.assign("Chop Wood", NUM_1);
        shortcuts.rename("Chop Wood", "Morning Logs");
        assertEquals(NUM_1, shortcuts.keyOf("Morning Logs"));
        assertNull(shortcuts.keyOf("Chop Wood"));
        // A task with no key renames without inventing one.
        shortcuts.rename("Dig a Tunnel", "Dig Deeper");
        assertNull(shortcuts.keyOf("Dig Deeper"));
    }

    /** Deleted tasks let go of their keys, and so do tasks an undo or a hand edit took away. */
    @Test
    void goneTasksLetGoOfTheirKeys() {
        shortcuts.assign("Chop Wood", NUM_1);
        shortcuts.assign("Guard Me", NUM_2);
        shortcuts.assign("Go Fishing", "key.keyboard.f9");
        shortcuts.forget(List.of("Guard Me"));
        assertNull(shortcuts.taskOf(NUM_2));
        assertTrue(shortcuts.retainOnly(List.of("Chop Wood", "Stone Tools")));
        assertEquals(Map.of("Chop Wood", NUM_1), stored);
        assertFalse(shortcuts.retainOnly(List.of("Chop Wood")));
    }

    /** The number pad wears its printed symbol on a badge; no other key is labelled by Lune. */
    @Test
    void theNumberPadIsLabelledByWhatIsPrintedOnIt() {
        assertEquals("1", TaskShortcuts.keypadSymbol(NUM_1));
        assertEquals("0", TaskShortcuts.keypadSymbol("key.keyboard.keypad.0"));
        assertEquals("+", TaskShortcuts.keypadSymbol("key.keyboard.keypad.add"));
        assertEquals("-", TaskShortcuts.keypadSymbol("key.keyboard.keypad.subtract"));
        assertEquals("*", TaskShortcuts.keypadSymbol("key.keyboard.keypad.multiply"));
        assertEquals("/", TaskShortcuts.keypadSymbol("key.keyboard.keypad.divide"));
        assertEquals(".", TaskShortcuts.keypadSymbol("key.keyboard.keypad.decimal"));
        assertEquals("=", TaskShortcuts.keypadSymbol("key.keyboard.keypad.equal"));
        // Keypad Enter is left to the game's own name for it; so is every key off the pad.
        assertNull(TaskShortcuts.keypadSymbol("key.keyboard.keypad.enter"));
        assertNull(TaskShortcuts.keypadSymbol("key.keyboard.1"));
        assertNull(TaskShortcuts.keypadSymbol("key.keyboard.f9"));
        assertNull(TaskShortcuts.keypadSymbol(null));
    }

    @Test
    void modifiersAreWaitedPast() {
        assertTrue(TaskShortcuts.isModifier("key.keyboard.left.shift"));
        assertTrue(TaskShortcuts.isModifier("key.keyboard.right.control"));
        assertTrue(TaskShortcuts.isModifier("key.keyboard.left.alt"));
        assertTrue(TaskShortcuts.isModifier("key.keyboard.right.win"));
        assertFalse(TaskShortcuts.isModifier(NUM_1));
        assertFalse(TaskShortcuts.isModifier(null));
    }

    /**
     * Every key name the shortcut code names for itself is a real key on the Minecraft version
     * being built.
     *
     * <p>Keys are stored by name because 26.3 renumbered them all; this is what makes sure the names
     * did not move as well. An unknown name does not fail loudly in the game - the number pad would
     * simply lose its short label, or Shift would be taken as a task's key - so it fails here.</p>
     */
    @Test
    void everyNamedKeyIsARealKeyOnThisVersion() {
        List<String> named = new java.util.ArrayList<>(List.of(
                "key.keyboard.backspace", "key.keyboard.delete",
                "key.keyboard.left.shift", "key.keyboard.right.shift",
                "key.keyboard.left.control", "key.keyboard.right.control",
                "key.keyboard.left.alt", "key.keyboard.right.alt",
                "key.keyboard.left.win", "key.keyboard.right.win",
                "key.keyboard.keypad.add", "key.keyboard.keypad.subtract",
                "key.keyboard.keypad.multiply", "key.keyboard.keypad.divide",
                "key.keyboard.keypad.decimal", "key.keyboard.keypad.equal"));
        for (int digit = 0; digit <= 9; digit++) {
            named.add("key.keyboard.keypad." + digit);
        }
        for (String name : named) {
            InputConstants.Key key = InputConstants.getKey(name);
            assertNotEquals(InputConstants.UNKNOWN, key, name);
            // The name the key reports is the one it was found by, so it comes back the same way.
            assertEquals(name, key.getName());
        }
    }
}
