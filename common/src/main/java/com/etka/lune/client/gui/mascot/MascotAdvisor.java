package com.etka.lune.client.gui.mascot;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.catalog.ToolCatalog;
import com.etka.lune.bot.knowledge.BiomeKnowledge;
import com.etka.lune.bot.knowledge.Need;
import com.etka.lune.bot.task.RoutineTask;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.routine.Routine;
import com.etka.lune.routine.RoutineGraph;
import com.etka.lune.routine.RoutineNode;
import com.etka.lune.routine.RoutineSafety;
import com.etka.lune.routine.RoutineSuggestion;
import com.etka.lune.routine.RoutineStore;
import com.etka.lune.config.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns real bot state into the mascot's deliberately small amount of speech.
 *
 * <p>This is deterministic and local. It does not invent advice, parse chat, or change a routine
 * until the player presses the affirmative button. Declines are remembered for the client session
 * so reopening the screen does not turn Lune into a nag.</p>
 */
public final class MascotAdvisor {

    private static final int SUGGESTION_DELAY_TICKS = 60;
    /** Six stable seconds plus the normal three-second thought delay = a fresh offer after ~9 s. */
    private static final int ROUTINE_STABLE_TICKS = 120;
    private static final int REPLY_TICKS = 140;
    private static final int NEXT_SUGGESTION_COOLDOWN_TICKS = 240;
    private static final int BLOCKED_CONFIRM_TICKS = 20;
    private static final int WAITING_CONFIRM_TICKS = 8;
    private static final Set<String> DISMISSED_THIS_SESSION = new HashSet<>();
    private static int MEMORY_GENERATION;

    public enum Mood {
        IDLE,
        WORKING,
        WAITING,
        BLOCKED,
        DANGER,
        SUCCESS,
        INVENTORY_FULL,
        MISSING_MATERIALS,
        PAUSED,
        THINKING,
        ASKING,
        SLEEPING
    }

    public enum Surface {
        MAIN,
        ROUTINES,
        WAYPOINTS,
        CONFIG
    }

    private Pending candidate;
    private int candidateTicks;
    private boolean prompting;
    private int chosenAmount;
    private int suggestionCooldownTicks;
    private String reply = "";
    private int replyTicks;
    private Mood replyMood = Mood.IDLE;
    private int napTicks;
    private Routine observedRoutine;
    private String observedFingerprint = "";
    private int stableRoutineTicks;
    private UndoRecord undo;
    private boolean dismissalMenu;
    private final Set<String> hiddenUntilReopen = new HashSet<>();
    private int memoryGeneration = MEMORY_GENERATION;
    private Surface surface = Surface.MAIN;
    private String lastMilestoneSeen = "";
    private String milestone = "";
    private int milestoneTicks;
    private Mood milestoneMood = Mood.IDLE;
    private Mood liveMood = Mood.IDLE;
    private String liveDetail = "";
    private int blockedTicks;
    private int waitingTicks;
    private boolean emergencyWasActive;

    private record Pending(String key, Routine routine, RoutineSuggestion taskSuggestion) {
        boolean isSafety() {
            return taskSuggestion == null;
        }
    }

    private record UndoRecord(Routine routine, Routine snapshot, String changedFingerprint) {}

    public enum Dismissal {
        NOT_NOW("Not now", "Naps and retries shortly."),
        NEXT_TIME("Remind me next time I see you",
                "Appears after closing and reopening the interface."),
        NEVER_TYPE("Don't ask again", "Permanently mutes that suggestion type."),
        THIS_ROUTINE("Not for this task", "Mutes it only for the selected task."),
        LATER("Remind me later", "Retries after three minutes.");

        private final String label;
        private final String description;

        Dismissal(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }
    }

    /** Task ideas belong in Task; the other tabs use calm, contextual ambient dialogue. */
    public void setSurface(Surface surface) {
        this.surface = surface == null ? Surface.MAIN : surface;
    }

    public void tick(BotEngine engine) {
        if (replyTicks > 0) {
            replyTicks--;
            if (replyTicks == 0) {
                reply = "";
                undo = null;
            }
        }
        if (suggestionCooldownTicks > 0) {
            suggestionCooldownTicks--;
        }
        if (napTicks > 0) {
            napTicks--;
        }
        if (milestoneTicks > 0) {
            milestoneTicks--;
        }

        BotConfig config = BotConfig.get();
        if (!config.showLune
                || BotConfig.LUNE_SPEECH_SILENT.equalsIgnoreCase(config.luneSpeech)) {
            suspendSpeech();
            return;
        }
        captureMilestone(engine);
        updateActivityState(engine);

        if (suppressesSuggestions()) {
            // Live state and recent outcomes are more useful than an unrelated routine prompt.
            prompting = false;
            dismissalMenu = false;
            clearCandidate();
            return;
        }

        if (surface != Surface.ROUTINES) {
            // Do not turn the dashboard or utility tabs into an unsolicited suggestion queue.
            // The active task, replies, and milestone narration still remain visible.
            prompting = false;
            dismissalMenu = false;
            stableRoutineTicks = 0;
            clearCandidate();
            return;
        }

        Routine routine = candidateRoutine(engine);
        String fingerprint = RoutineSuggestion.fingerprint(routine);
        if (routine != observedRoutine || !fingerprint.equals(observedFingerprint)) {
            observedRoutine = routine;
            observedFingerprint = fingerprint;
            stableRoutineTicks = 0;
            suggestionCooldownTicks = 0;
            if (napTicks > 0) {
                napTicks = 0;
                reply = "";
                replyTicks = 0;
            }
            prompting = false;
            dismissalMenu = false;
            clearCandidate();
            return;
        }
        if (routine == null || routine.nodes == null || routine.nodes.isEmpty()) {
            clearCandidate();
            return;
        }
        if (stableRoutineTicks < ROUTINE_STABLE_TICKS) {
            stableRoutineTicks++;
            return;
        }
        Pending next = nextSuggestion(engine, routine);
        if (prompting) {
            if (candidate != null && next != null && candidate.key().equals(next.key())) {
                return;
            }
            prompting = false;
            clearCandidate();
        }
        if (replyTicks > 0 || suggestionCooldownTicks > 0) {
            return;
        }
        if (next == null) {
            clearCandidate();
            return;
        }

        if (candidate == null || !next.key().equals(candidate.key())) {
            candidate = next;
            candidateTicks = 1;
            chosenAmount = next.taskSuggestion() == null
                    ? 0 : next.taskSuggestion().suggestedAmount();
            return;
        }

        candidateTicks++;
        if (candidateTicks >= SUGGESTION_DELAY_TICKS) {
            prompting = true;
        }
    }

    public boolean isPrompting() {
        return prompting;
    }

    public boolean isDismissalMenu() {
        return prompting && dismissalMenu;
    }

    public void showDismissalMenu() {
        if (prompting && candidate != null) {
            dismissalMenu = true;
        }
    }

    public void hideDismissalMenu() {
        dismissalMenu = false;
    }

    /** Used by Config's restore button to clear session-only acknowledgements as well. */
    public static void restoreSuggestionMemory() {
        DISMISSED_THIS_SESSION.clear();
        MEMORY_GENERATION++;
    }

    public void chooseDismissal(int ordinal) {
        if (!prompting || candidate == null
                || ordinal < 0 || ordinal >= Dismissal.values().length) {
            return;
        }
        Dismissal choice = Dismissal.values()[ordinal];
        String key = candidate.key();
        String type = candidateType(candidate);
        BotConfig config = BotConfig.get();
        ensurePreferenceCollections(config);
        switch (choice) {
            case NOT_NOW -> {
                reply = "Okay. I'll take a little nap and try again soon.";
                replyMood = Mood.SLEEPING;
                napTicks = NEXT_SUGGESTION_COOLDOWN_TICKS;
            }
            case NEXT_TIME -> {
                hiddenUntilReopen.add(key);
                reply = "Okay. I'll bring this up next time you open me.";
                replyMood = Mood.SUCCESS;
            }
            case NEVER_TYPE -> {
                config.luneDismissedSuggestionTypes.add(type);
                config.save();
                reply = "Got it. I won't ask about this kind again.";
                replyMood = Mood.SUCCESS;
            }
            case THIS_ROUTINE -> {
                config.luneDismissedRoutineSuggestions.add(routineKindKey(
                        candidate.routine(), type));
                config.save();
                reply = "Understood. I'll leave this task alone.";
                replyMood = Mood.SUCCESS;
            }
            case LATER -> {
                config.luneSuggestionReminders.put(key,
                        System.currentTimeMillis() + 3L * 60L * 1000L);
                config.save();
                reply = "I'll circle back in a few minutes, promise.";
                replyMood = Mood.THINKING;
            }
        }
        undo = null;
        finishPrompt();
    }

    public boolean hasAmountChoice() {
        return prompting && candidate != null && candidate.taskSuggestion() != null
                && candidate.taskSuggestion().hasAmountChoice();
    }

    /** True only during the short, deliberate pause before a discovered idea becomes a question. */
    public boolean isThinking() {
        return candidate != null && !prompting && candidateTicks > 0;
    }

    public int chosenAmount() {
        return chosenAmount;
    }

    public String chosenAmountLabel() {
        if (!hasAmountChoice()) {
            return "";
        }
        return chosenAmount + " " + candidate.taskSuggestion().unit();
    }

    public void decreaseAmount() {
        if (!hasAmountChoice()) {
            return;
        }
        int step = candidate.taskSuggestion().amountStep();
        chosenAmount = Math.max(step, chosenAmount - step);
    }

    public void increaseAmount() {
        if (!hasAmountChoice()) {
            return;
        }
        chosenAmount = Math.min(512, chosenAmount + candidate.taskSuggestion().amountStep());
    }

    public String promptTitle() {
        if (candidate != null && candidate.isSafety()) {
            return "LUNE HAS A THOUGHT";
        }
        if (candidate != null && candidate.taskSuggestion() != null
                && !candidate.taskSuggestion().changesRoutine()) {
            return "LUNE NOTICED SOMETHING";
        }
        return "LUNE HAS AN IDEA";
    }

    public String acceptLabel() {
        return candidate == null || candidate.isSafety()
                ? "Add it" : candidate.taskSuggestion().acceptLabel();
    }

    public boolean hasPreview() {
        return prompting && candidate != null && (candidate.isSafety()
                || candidate.taskSuggestion().hasPreview());
    }

    public String previewBefore() {
        if (!hasPreview()) {
            return "";
        }
        return candidate.isSafety() ? "No Always protection"
                : candidate.taskSuggestion().previewBefore();
    }

    public String previewAfter() {
        if (!hasPreview()) {
            return "";
        }
        return candidate.isSafety() ? "Always  →  Self Preservation"
                : candidate.taskSuggestion().previewAfter(chosenAmount);
    }

    public boolean canUndo() {
        if (undo == null || replyTicks <= 0
                || !undo.changedFingerprint().equals(RoutineSuggestion.fingerprint(undo.routine()))) {
            return false;
        }
        Task current = BotEngine.get().getCurrent();
        if (current instanceof RoutineTask running && running.currentRoutine() == undo.routine()) {
            RoutineNode active = running.activeNode();
            return active == null || undo.snapshot().nodeById(active.id) != null;
        }
        return true;
    }

    public void undo() {
        if (!canUndo()) {
            return;
        }
        Routine restored = undo.routine();
        if (RoutineGraph.restore(restored, undo.snapshot())) {
            RoutineStore.get().save();
            observedRoutine = restored;
            observedFingerprint = RoutineSuggestion.fingerprint(restored);
            stableRoutineTicks = 0;
            reply = "Undone. The task is back the way it was.";
            replyMood = Mood.IDLE;
            replyTicks = REPLY_TICKS;
        }
        undo = null;
    }

    /** Ambient dialogue appears on the welcome and utility tabs; Task stays task-focused. */
    public boolean shouldSpeak(BotEngine engine) {
        BotConfig config = BotConfig.get();
        if (!config.showLune
                || BotConfig.LUNE_SPEECH_SILENT.equalsIgnoreCase(config.luneSpeech)) {
            return false;
        }
        Mood mood = mood(engine);
        boolean actionable = mood == Mood.DANGER || mood == Mood.INVENTORY_FULL
                || mood == Mood.MISSING_MATERIALS || mood == Mood.BLOCKED;
        if (BotConfig.LUNE_SPEECH_QUIET.equalsIgnoreCase(config.luneSpeech)) {
            return actionable || prompting || replyTicks > 0;
        }
        if (actionable) {
            return true;
        }
        if (napTicks > 0) {
            return replyTicks > 0;
        }
        return surface != Surface.ROUTINES || prompting || replyTicks > 0 || milestoneTicks > 0
                || liveMood != Mood.IDLE || engine.getCurrent() != null;
    }

    public Mood mood(BotEngine engine) {
        BotConfig config = BotConfig.get();
        if (config.showLune
                && BotConfig.LUNE_SPEECH_SILENT.equalsIgnoreCase(config.luneSpeech)) {
            return Mood.SLEEPING;
        }
        if (isAttentionMood(liveMood)) {
            return liveMood;
        }
        if (prompting) {
            return Mood.ASKING;
        }
        if (replyTicks > 0) {
            return replyMood;
        }
        if (milestoneTicks > 0 && milestoneMood != Mood.IDLE) {
            return milestoneMood;
        }
        if (napTicks > 0) {
            return Mood.SLEEPING;
        }
        if (liveMood == Mood.WAITING || liveMood == Mood.SUCCESS) {
            return liveMood;
        }
        if (isThinking()) {
            return Mood.THINKING;
        }
        return liveMood;
    }

    public String speech(BotEngine engine) {
        if (isAttentionMood(liveMood)) {
            return stateSpeech(liveMood, liveDetail);
        }
        if (prompting) {
            if (candidate == null || candidate.isSafety()) {
                return "No Self Preservation here. Shall I add it for the next run?";
            }
            return candidate.taskSuggestion().prompt();
        }
        if (replyTicks > 0) {
            return reply;
        }
        if (milestoneTicks > 0) {
            return milestone;
        }
        if (liveMood == Mood.WAITING || liveMood == Mood.SUCCESS) {
            return stateSpeech(liveMood, liveDetail);
        }
        Task current = engine.getCurrent();
        if (current == null) {
            return surface == Surface.ROUTINES
                    ? "Nothing running. I'll stay right here."
                    : idleSpeech(surface);
        }
        String status = current.status();
        return status == null || status.isBlank() ? "I'm working on it." : sentence(status);
    }

    /**
     * Stable identity for the current piece of dialogue. The popup can dismiss this conversation
     * without muting Lune; a new prompt, reply, milestone, state, or task opens it again.
     */
    public String conversationKey(BotEngine engine) {
        if (prompting) {
            return "prompt:" + (candidate == null ? "" : candidate.key());
        }
        if (replyTicks > 0) {
            return "reply:" + reply;
        }
        if (milestoneTicks > 0) {
            return "milestone:" + lastMilestoneSeen;
        }
        if (isAttentionMood(liveMood)) {
            return "attention:" + liveMood + ":" + liveDetail;
        }
        Task current = engine == null ? null : engine.getCurrent();
        if (current != null) {
            return "task:" + current.name() + ":" + liveMood;
        }
        return "ambient:" + surface + ":" + liveMood;
    }

    private void captureMilestone(BotEngine engine) {
        String message = engine.getLastMessage();
        if (message == null || message.isBlank() || message.equals(lastMilestoneSeen)) {
            return;
        }
        lastMilestoneSeen = message;
        milestoneMood = moodFor(MascotSignals.classifyMilestone(message));
        milestone = milestoneMood == Mood.IDLE
                ? "Milestone: " + sentence(message)
                : stateSpeech(milestoneMood, message);
        milestoneTicks = 100;
    }

    private void updateActivityState(BotEngine engine) {
        Task current = engine.getCurrent();
        String status = current == null ? "" : current.status();
        liveDetail = status;

        if (engine.isEmergencyProtectionActive()) {
            emergencyWasActive = true;
            liveMood = Mood.DANGER;
            liveDetail = engine.getEmergencyProtectionStatus();
            resetConfirmationTicks();
            return;
        }
        if (emergencyWasActive) {
            emergencyWasActive = false;
            if (current != null) {
                milestoneMood = Mood.SUCCESS;
                milestone = stateSpeech(Mood.SUCCESS,
                        "Emergency protection finished; we're safe again.");
                milestoneTicks = 100;
            }
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            var player = minecraft.player;
            BotConfig config = BotConfig.get();
            float dangerHealth = config.stopBelowHealth > 0 ? config.stopBelowHealth : 4.0F;
            if (player.isInLava()) {
                liveMood = Mood.DANGER;
                liveDetail = status.isBlank() ? "I'm in lava." : status;
                resetConfirmationTicks();
                return;
            }
            if (player.isUnderWater()
                    && player.getAirSupply() <= player.getMaxAirSupply() / 3) {
                liveMood = Mood.DANGER;
                liveDetail = status.isBlank() ? "My air is running low." : status;
                resetConfirmationTicks();
                return;
            }
            if (player.getHealth() <= dangerHealth) {
                liveMood = Mood.DANGER;
                liveDetail = status.isBlank() ? "My health is dangerously low." : status;
                resetConfirmationTicks();
                return;
            }
            if (current != null && player.getInventory().getFreeSlot() < 0
                    && MascotSignals.classify(status) != MascotSignals.Signal.DANGER) {
                liveMood = Mood.INVENTORY_FULL;
                liveDetail = status.isBlank() ? "The inventory is full." : status;
                resetConfirmationTicks();
                return;
            }
        }

        if (engine.isPaused()) {
            liveMood = Mood.PAUSED;
            liveDetail = "";
            resetConfirmationTicks();
            return;
        }

        MascotSignals.Signal signal = MascotSignals.classify(status);
        if (signal == MascotSignals.Signal.BLOCKED) {
            waitingTicks = 0;
            blockedTicks = Math.min(BLOCKED_CONFIRM_TICKS, blockedTicks + 1);
            liveMood = blockedTicks >= BLOCKED_CONFIRM_TICKS ? Mood.BLOCKED
                    : current == null ? Mood.IDLE : Mood.WORKING;
            return;
        }
        if (signal == MascotSignals.Signal.WAITING) {
            blockedTicks = 0;
            waitingTicks = Math.min(WAITING_CONFIRM_TICKS, waitingTicks + 1);
            liveMood = waitingTicks >= WAITING_CONFIRM_TICKS ? Mood.WAITING
                    : current == null ? Mood.IDLE : Mood.WORKING;
            return;
        }

        resetConfirmationTicks();
        liveMood = current == null ? Mood.IDLE : moodFor(signal);
        if (liveMood == Mood.IDLE && current != null) {
            liveMood = Mood.WORKING;
        }
    }

    private boolean suppressesSuggestions() {
        return liveMood != Mood.IDLE && liveMood != Mood.WORKING
                || milestoneTicks > 0 && milestoneMood != Mood.IDLE;
    }

    private static boolean isAttentionMood(Mood mood) {
        return mood == Mood.DANGER || mood == Mood.INVENTORY_FULL
                || mood == Mood.MISSING_MATERIALS || mood == Mood.BLOCKED
                || mood == Mood.PAUSED;
    }

    private static Mood moodFor(MascotSignals.Signal signal) {
        return switch (signal) {
            case WAITING -> Mood.WAITING;
            case BLOCKED -> Mood.BLOCKED;
            case DANGER -> Mood.DANGER;
            case SUCCESS -> Mood.SUCCESS;
            case INVENTORY_FULL -> Mood.INVENTORY_FULL;
            case MISSING_MATERIALS -> Mood.MISSING_MATERIALS;
            case NONE -> Mood.IDLE;
        };
    }

    private void resetConfirmationTicks() {
        blockedTicks = 0;
        waitingTicks = 0;
    }

    private static String stateSpeech(Mood mood, String status) {
        if (mood == Mood.PAUSED) {
            String[] paused = {
                    "You paused me. I'll stay right here.",
                    "Taking a little break? I won't touch anything.",
                    "I'm holding still until you say go.",
                    "Paused. I'll keep our place warm for you."
            };
            int minutes = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
            return paused[minutes / 3 % paused.length];
        }
        String detail = status == null || status.isBlank()
                ? fallbackDetail(mood)
                : sentence(status);
        String[] openings = switch (mood) {
            case WAITING -> new String[] {
                    "I'll wait right here.",
                    "Just a little patience, lovely.",
                    "Nothing to do but give it a moment.",
                    "I'm keeping watch while we wait."
            };
            case BLOCKED -> new String[] {
                    "Hmm... I've run into an obstacle.",
                    "That path is being stubborn.",
                    "I'm a little stuck here.",
                    "Something is in my way, lovely."
            };
            case DANGER -> new String[] {
                    "Careful—something is wrong.",
                    "This is getting dangerous. I'm handling it.",
                    "Stay close, lovely. I need to get us safe.",
                    "Danger first. Everything else can wait."
            };
            case SUCCESS -> new String[] {
                    "Done, lovely!",
                    "There we are—I knew we could do it.",
                    "All finished. That went rather nicely.",
                    "A little victory for us."
            };
            case INVENTORY_FULL -> new String[] {
                    "My pockets are completely full.",
                    "I can't carry another thing, lovely.",
                    "We've gathered so much that there is nowhere to put it.",
                    "A tiny storage problem: every slot is occupied."
            };
            case MISSING_MATERIALS -> new String[] {
                    "I'm missing something I need.",
                    "I can't finish this without the right supplies.",
                    "We're a few materials short, lovely.",
                    "I checked twice—one of the ingredients is missing."
            };
            default -> new String[] {"I'm working on it."};
        };
        int index = Math.floorMod(detail.toLowerCase(Locale.ROOT).hashCode(), openings.length);
        return openings[index] + " " + detail;
    }

    private static String fallbackDetail(Mood mood) {
        return switch (mood) {
            case WAITING -> "We need to wait a moment.";
            case BLOCKED -> "I can't find a way forward yet.";
            case DANGER -> "I'm trying to make things safe.";
            case SUCCESS -> "The work is complete.";
            case INVENTORY_FULL -> "The inventory has no free slot.";
            case MISSING_MATERIALS -> "The required supplies are unavailable.";
            default -> "I'm working on it.";
        };
    }

    private static String idleSpeech(Surface surface) {
        LocalTime now = LocalTime.now();
        int minutes = now.getHour() * 60 + now.getMinute();
        String[] lines;
        if (surface == Surface.CONFIG) {
            lines = new String[] {
                    "A little tuning? I’ll keep the complicated bits tidy.",
                    "Take your time. Good settings make calm tasks.",
                    "I’m watching the details so you don’t have to.",
                    "If something feels too sharp, we can soften it together.",
                    "This is where I learn how you like to play."
            };
            return lines[minutes / 5 % lines.length];
        }
        if (surface == Surface.WAYPOINTS) {
            lines = new String[] {
                    "Where shall we go next? I can keep the important places close.",
                    "A good waypoint is a promise to come back.",
                    "I’ll remember the path, even when you wander.",
                    "Pick a destination and I’ll help you make a route.",
                    "Some places deserve names. This one looks like a good start."
            };
            return lines[minutes / 5 % lines.length];
        }
        if (now.getHour() < 5) {
            lines = new String[] {
                    "Isn't it a little late to play? Let me handle the quiet work for you.",
                    "You should rest soon, lovely. I can keep an eye on things.",
                    "Even Lune needs a quiet hour. Shall we leave the busy work to me?",
                    "Close the world for tonight? I’ll be here when you return."
            };
        } else if (now.getHour() < 10) {
            lines = new String[] {
                    "Good morning. What are we going to do now?",
                    "I'm here with you. Shall we make a little plan?",
                    "New day, new little adventure. I’m ready when you are.",
                    "Take your time waking up. I’ll keep the plans warm."
            };
        } else if (now.getHour() < 18) {
            lines = new String[] {
                    "What are we going to do now?",
                    "How can I help you today?",
                    "Give me a direction and I’ll help turn it into a task.",
                    "Perhaps we gather a little, build a little, and see what happens?"
            };
        } else if (now.getHour() < 23) {
            lines = new String[] {
                    "How can I help you tonight?",
                    "Tell me what you feel like doing, and I'll help with the rest.",
                    "Let’s make tonight’s work gentle and worthwhile.",
                    "The world is quieting down. We can still make progress.",
                    "Point me toward a task and I’ll stay by your side."
            };
        } else {
            lines = new String[] {
                    "It is getting late. Shall I take care of a little work for you?",
                    "If you're tired, I can keep watch while you rest.",
                    "Your eyes must be tired by now. I can keep watch for a while.",
                    "Perhaps you rest, and I handle the quiet parts?",
                    "The moon is up; maybe we should choose something simple."
            };
        }
        return lines[(minutes / 5) % lines.length];
    }

    public void accept() {
        if (!prompting || candidate == null || candidate.routine() == null) {
            return;
        }
        boolean mutating = candidate.isSafety() || candidate.taskSuggestion().changesRoutine();
        Routine snapshot = mutating ? RoutineGraph.copy(candidate.routine()) : null;
        boolean changed;
        if (candidate.isSafety()) {
            changed = RoutineSafety.installDefaultMonitor(candidate.routine());
        } else {
            changed = candidate.taskSuggestion().apply(chosenAmount);
        }
        if (changed) {
            DISMISSED_THIS_SESSION.add(candidate.key());
            if (mutating) {
                RoutineStore.get().save();
                undo = new UndoRecord(candidate.routine(), snapshot,
                        RoutineSuggestion.fingerprint(candidate.routine()));
            }
            if (candidate.isSafety()) {
                reply = "All set. I'll keep a closer eye on its next run.";
            } else if (candidate.taskSuggestion().kind() == RoutineSuggestion.Kind.QUANTITY) {
                reply = "Lovely. I'll stop at " + chosenAmount + " "
                        + candidate.taskSuggestion().unit() + " next time.";
            } else if (candidate.taskSuggestion().kind() == RoutineSuggestion.Kind.AUTO_TOOL) {
                reply = "Done. I'll prepare a proper tool before mining.";
            } else {
                reply = candidate.taskSuggestion().acceptedReply();
            }
            replyMood = Mood.SUCCESS;
        } else {
            undo = null;
            reply = "That task changed while I was asking, so I left it alone.";
            replyMood = Mood.IDLE;
        }
        finishPrompt();
    }

    public void decline() {
        if (!prompting) {
            return;
        }
        showDismissalMenu();
    }

    private void clearCandidate() {
        candidate = null;
        candidateTicks = 0;
        chosenAmount = 0;
    }

    private void suspendSpeech() {
        prompting = false;
        dismissalMenu = false;
        reply = "";
        replyTicks = 0;
        suggestionCooldownTicks = 0;
        napTicks = 0;
        undo = null;
        clearCandidate();
        milestone = "";
        milestoneTicks = 0;
        milestoneMood = Mood.IDLE;
        liveMood = Mood.IDLE;
        liveDetail = "";
        resetConfirmationTicks();
        emergencyWasActive = false;
    }

    private void finishPrompt() {
        prompting = false;
        dismissalMenu = false;
        replyTicks = REPLY_TICKS;
        suggestionCooldownTicks = NEXT_SUGGESTION_COOLDOWN_TICKS;
        clearCandidate();
    }

    private static String routineKey(Routine routine) {
        return routine == null || routine.name == null
                ? ""
                : routine.name.trim().toLowerCase(Locale.ROOT);
    }

    private Pending nextSuggestion(BotEngine engine, Routine routine) {
        String routineKey = routineKey(routine);
        String safetyKey = routineKey + ":safety";

        RoutineNode preferred = null;
        Task current = engine.getCurrent();
        if (current instanceof RoutineTask running && running.currentRoutine() == routine) {
            preferred = running.currentNode();
        }
        Set<String> ignored = new HashSet<>(DISMISSED_THIS_SESSION);

        // Structural repairs come before safety and workload advice. In particular, an imported
        // routine without START must be offered an explicit entry point before Lune discusses
        // mining quotas or protection.
        var leading = RoutineSuggestion.startFor(routine);
        if (leading.isEmpty() || ignored.contains(leading.get().key())) {
            leading = RoutineSuggestion.finiteMonitorFor(routine);
        }
        if (leading.isPresent()) {
            RoutineSuggestion found = leading.get();
            Pending pending = new Pending(found.key(), routine, found);
            if (!isSuppressed(pending)) {
                return pending;
            }
            ignored.add(found.key());
        }

        if (!RoutineSafety.hasMonitor(routine) && !DISMISSED_THIS_SESSION.contains(safetyKey)) {
            Pending safety = new Pending(safetyKey, routine, null);
            if (!isSuppressed(safety)) {
                return safety;
            }
        }

        while (true) {
            var suggestion = RoutineSuggestion.firstFor(routine, preferred, ignored,
                    suggestionContext());
            if (suggestion.isEmpty()) {
                return null;
            }
            RoutineSuggestion found = suggestion.get();
            Pending pending = new Pending(found.key(), routine, found);
            if (!isSuppressed(pending)) {
                return pending;
            }
            ignored.add(found.key());
        }
    }

    private boolean isSuppressed(Pending pending) {
        if (memoryGeneration != MEMORY_GENERATION) {
            hiddenUntilReopen.clear();
            memoryGeneration = MEMORY_GENERATION;
        }
        if (pending == null || pending.routine() == null) {
            return false;
        }
        String type = candidateType(pending);
        String routineKind = routineKindKey(pending.routine(), type);
        BotConfig config = BotConfig.get();
        ensurePreferenceCollections(config);
        if (DISMISSED_THIS_SESSION.contains(pending.key())
                || config.luneDismissedSuggestionTypes.contains(type)
                || config.luneDismissedRoutineSuggestions.contains(routineKind)
                || hiddenUntilReopen.contains(pending.key())) {
            return true;
        }

        Map<String, Long> reminders = config.luneSuggestionReminders;
        Long until = reminders.get(pending.key());
        if (until != null) {
            if (until > System.currentTimeMillis()) {
                return true;
            }
            reminders.remove(pending.key());
            config.save();
        }
        return false;
    }

    private static String candidateType(Pending pending) {
        return pending == null || pending.taskSuggestion() == null
                ? "SAFETY" : pending.taskSuggestion().kind().name();
    }

    private static String routineKindKey(Routine routine, String type) {
        return routineKey(routine) + ":" + type;
    }

    private static void ensurePreferenceCollections(BotConfig config) {
        if (config.luneDismissedSuggestionTypes == null) {
            config.luneDismissedSuggestionTypes = new java.util.LinkedHashSet<>();
        }
        if (config.luneDismissedRoutineSuggestions == null) {
            config.luneDismissedRoutineSuggestions = new java.util.LinkedHashSet<>();
        }
        if (config.luneSuggestionReminders == null) {
            config.luneSuggestionReminders = new java.util.LinkedHashMap<>();
        }
    }

    private static RoutineSuggestion.Context suggestionContext() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return RoutineSuggestion.Context.unknown();
        }
        Inventory inventory = minecraft.player.getInventory();
        int torches = 0;
        int pickaxeDurability = 0;
        int axeDurability = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.TORCH) || stack.is(Items.SOUL_TORCH)) {
                torches += stack.getCount();
            }
            int durability = stack.isDamageableItem()
                    ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue()) : 0;
            ToolCatalog.Kind kind = ToolCatalog.kindOf(stack.getItem());
            if (kind == ToolCatalog.Kind.PICKAXE) {
                pickaxeDurability = Math.max(pickaxeDurability, durability);
            } else if (kind == ToolCatalog.Kind.AXE) {
                axeDurability = Math.max(axeDurability, durability);
            }
        }
        long dayTime = Math.floorMod(minecraft.level.getOverworldClockTime(), 24_000L);
        String dimension = minecraft.level.dimension().identifier().toString();
        var biome = minecraft.level.getBiome(minecraft.player.blockPosition());
        return new RoutineSuggestion.Context(
                InventoryHelper.freeSlots(minecraft.player),
                minecraft.player.getFoodData().getFoodLevel(),
                torches,
                pickaxeDurability,
                axeDurability,
                dayTime >= 12_542L && dayTime <= 23_460L,
                dimension,
                BiomeKnowledge.name(biome),
                BiomeKnowledge.isBarrenFor(biome, Set.of(Need.WOOD)),
                BotConfig.get().stopWhenFull);
    }

    /**
     * Prefer the routine that is actually running. While idle, the last routine selected in the
     * editor remains useful context; this also lets a very short routine finish without making the
     * recommendation disappear before the player can reopen the panel.
     */
    private static Routine candidateRoutine(BotEngine engine) {
        Task current = engine.getCurrent();
        if (current instanceof RoutineTask running) {
            return running.currentRoutine();
        }
        if (current != null) {
            return null;
        }
        String lastOpened = BotConfig.get().lastOpenedRoutine;
        if (lastOpened == null || lastOpened.isBlank()) {
            return null;
        }
        return RoutineStore.get().byName(lastOpened).orElse(null);
    }

    private static String sentence(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "I'm working on it.";
        }
        String capitalised = Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
        char last = capitalised.charAt(capitalised.length() - 1);
        return last == '.' || last == '!' || last == '?' ? capitalised : capitalised + ".";
    }
}
