package com.etka.lune.client;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.config.BotConfig;
import com.etka.lune.config.TaskShortcuts;
import com.etka.lune.config.Terms;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskStore;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The keys players give their tasks on the Tasks tab, heard in game.
 *
 * <p>Pressing one does what the Run button beside that task does: it starts the task, or stops
 * it when it is the one running. It is the player's own gesture made from somewhere else, not a
 * behaviour of the bot, which is why it lives with the other keys and not in the engine.</p>
 *
 * <p>Each key is heard through a {@link KeyMapping} the game is never told about. The game's own
 * input path then decides when a press counts - only in play, never while a screen or the chat
 * box has the keyboard - and it counts a tap made between two ticks, which reading the key once
 * a tick would miss. Never registering the mapping with a loader is what keeps it out of the
 * Controls screen and out of {@code options.txt}: the key belongs to the task, is kept beside the
 * task in {@code config/lune.json}, and is set where the task is.</p>
 */
public final class TaskShortcutKeys {

    /** What the unregistered mappings are called in the game's table of every mapping. */
    private static final String SLOT_NAME = "key.lune.task_shortcut.";
    private static final String BACKSPACE = "key.keyboard.backspace";
    private static final String DELETE = "key.keyboard.delete";

    private static final List<Slot> SLOTS = new ArrayList<>();
    /** What {@link #SLOTS} were last set up from, to notice the config changing under them. */
    private static Map<String, String> synced = Map.of();

    private TaskShortcutKeys() {}

    /**
     * One mapping and the task it starts.
     *
     * <p>Reused rather than replaced when the keys change, because the game has no way to forget a
     * mapping once one is made; a spare one simply answers to no key.</p>
     */
    private static final class Slot {
        private final KeyMapping mapping;
        private String task;
        /** Whether the key was down last tick, so the keyboard's auto-repeat is not more presses. */
        private boolean held;

        private Slot(int number) {
            mapping = new KeyMapping(SLOT_NAME + number, InputConstants.UNKNOWN.getValue(),
                    LuneKeybinds.CATEGORY);
        }
    }

    /** The shortcut rules over the live config. */
    public static TaskShortcuts shortcuts() {
        return new TaskShortcuts(live());
    }

    /** The config's map, made safe for a config written before there were any. */
    private static Map<String, String> live() {
        BotConfig config = BotConfig.get();
        if (config.taskShortcuts == null) {
            config.taskShortcuts = new LinkedHashMap<>();
        }
        return config.taskShortcuts;
    }

    /** Called every client tick while there is a player: keeps the keys set up, and acts on them. */
    public static void tick(Minecraft mc) {
        sync();
        for (Slot slot : SLOTS) {
            boolean pressed = false;
            while (slot.mapping.consumeClick()) {
                pressed = true;
            }
            // A held key repeats, and every repeat counts as a click. Only a press that found the
            // key up is the player asking again; otherwise holding it would flick the task on and
            // off for as long as the finger stayed there.
            boolean fresh = pressed && !slot.held;
            slot.held = slot.mapping.isDown();
            if (fresh && slot.task != null) {
                press(mc, slot.task);
            }
        }
    }

    /** Points the mappings at the keys the config names, when those have changed. */
    private static void sync() {
        Map<String, String> wanted = live();
        if (wanted.equals(synced)) {
            return;
        }
        synced = new LinkedHashMap<>(wanted);
        int used = 0;
        Set<String> taken = new HashSet<>();
        for (Map.Entry<String, String> entry : synced.entrySet()) {
            InputConstants.Key key = key(entry.getValue());
            // A key this version does not have, or one a hand-edited file gave to a second task.
            if (key == null || !taken.add(key.getName())) {
                continue;
            }
            while (SLOTS.size() <= used) {
                SLOTS.add(new Slot(SLOTS.size() + 1));
            }
            Slot slot = SLOTS.get(used++);
            slot.task = entry.getKey();
            slot.held = false;
            slot.mapping.setKey(key);
        }
        for (int index = used; index < SLOTS.size(); index++) {
            Slot slot = SLOTS.get(index);
            slot.task = null;
            slot.held = false;
            slot.mapping.setKey(InputConstants.UNKNOWN);
        }
        // What the Controls screen calls after a change: the game looks a pressed key up in this
        // index, not in the mappings themselves.
        KeyMapping.resetMapping();
    }

    /** A task's key was pressed in game. The Run button's answer, from anywhere. */
    private static void press(Minecraft mc, String taskName) {
        TaskGraph task = TaskStore.get().byName(taskName).orElse(null);
        if (task == null) {
            // Gone while the editor was not looking - undone, or taken out of the file by hand.
            // The key is let go, so pressing it again is not the same puzzle twice.
            if (shortcuts().clear(taskName)) {
                BotConfig.get().save();
            }
            say(mc, Lang.get("lune.shortcut.gone", taskName));
            return;
        }
        BotEngine engine = BotEngine.get();
        // By name, not by object: an undo in the editor swaps every task for an equal copy, and the
        // key should still stop the run it started.
        if (engine.getCurrent() instanceof TaskRunner running && running.currentTask() != null
                && running.currentTask().name.equalsIgnoreCase(task.name)) {
            engine.stopAll();
            say(mc, Lang.get("lune.shortcut.stopped", task.displayName()));
            return;
        }
        if (task.nodes.isEmpty()) {
            say(mc, Lang.get("lune.gui.tasks.nothing_run"));
            return;
        }
        if (!Terms.mayRun()) {
            say(mc, Lang.get("lune.engine.accept_terms_first"));
            return;
        }
        if (!engine.runNow(new TaskRunner(task))) {
            // This server's rules, most likely; the engine has the sentence.
            say(mc, engine.getLastMessage());
            return;
        }
        say(mc, Lang.get("lune.shortcut.started", task.displayName()));
    }

    /** Above the hotbar, where the pause key already reports: seen, and gone in a moment. */
    private static void say(Minecraft mc, String line) {
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal(Lang.get("lune.gui.bot_context.lune", line)));
        }
    }

    // --- naming keys ---------------------------------------------------------

    /** The game's key for a stored name, or null when this version has no such key. */
    public static InputConstants.Key key(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            InputConstants.Key key = InputConstants.getKey(name);
            return InputConstants.UNKNOWN.equals(key) ? null : key;
        } catch (RuntimeException unknown) {
            // A key only a later version has, or a name from a hand-edited file.
            return null;
        }
    }

    /** True for the two keys that take a task's key away while the editor is listening. */
    public static boolean clears(String name) {
        return BACKSPACE.equals(name) || DELETE.equals(name);
    }

    /**
     * What the game calls a key, as its Controls screen would show it.
     *
     * <p>Except the number pad, whose system name on some platforms is a bare {@code 1} that reads
     * exactly like the 1 above the letters. The game's own line for those says which one.</p>
     */
    public static String label(String name) {
        InputConstants.Key key = key(name);
        if (key == null) {
            return name == null ? "" : name;
        }
        if (TaskShortcuts.keypadSymbol(name) != null && Lang.has(name)) {
            return Lang.get(name);
        }
        return key.getDisplayName().getString();
    }

    /** The label a key wears on a badge: {@code Num 1} for the number pad, the game's name otherwise. */
    public static String shortLabel(String name) {
        String symbol = TaskShortcuts.keypadSymbol(name);
        return symbol != null ? Lang.get("lune.shortcut.keypad", symbol) : label(name);
    }

    /**
     * The mapping in Controls that already answers to this key, or null when none does.
     *
     * <p>Every registered mapping - the game's, every mod's, Lune's own open and pause keys. A task
     * given one of those keys would start whenever the player jumped or opened a menu, so the
     * editor refuses the key and says what has it.</p>
     */
    public static KeyMapping takenBy(InputConstants.Key key) {
        Options options = Minecraft.getInstance().options;
        if (key == null || options == null) {
            return null;
        }
        String name = key.getName();
        for (KeyMapping mapping : options.keyMappings) {
            if (!mapping.isUnbound() && name.equals(mapping.saveString())) {
                return mapping;
            }
        }
        return null;
    }

    /** The badge a task's row wears in a list, or null when the task has no key. */
    public static String badge(TaskGraph task) {
        String key = task == null ? null : shortcuts().keyOf(task.name);
        return key == null ? null : shortLabel(key);
    }

    /** What hovering that badge says. */
    public static String badgeTip(TaskGraph task) {
        String key = task == null ? null : shortcuts().keyOf(task.name);
        return key == null ? null : Lang.get("lune.shortcut.badge_tip", label(key));
    }
}
