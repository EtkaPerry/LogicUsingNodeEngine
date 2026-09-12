package com.etka.lune.client.gui.mascot;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.StatusSignal;
import com.etka.lune.bot.LoopWatch;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.catalog.ToolCatalog;
import com.etka.lune.bot.knowledge.BiomeKnowledge;
import com.etka.lune.bot.knowledge.Need;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.WorldClock;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskWiring;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.TaskSafety;
import com.etka.lune.task.TaskSuggestion;
import com.etka.lune.task.TaskStore;
import com.etka.lune.config.BotConfig;
import com.etka.lune.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
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
 * <p>This is deterministic and local. It does not invent advice, parse chat, or change a task
 * until the player presses the affirmative button. Declines are remembered for the client session
 * so reopening the screen does not turn Lune into a nag.</p>
 */
public final class MascotAdvisor {

    private static final int SUGGESTION_DELAY_TICKS = 60;
    /** Six stable seconds plus the normal three-second thought delay = a fresh offer after ~9 s. */
    private static final int TASK_STABLE_TICKS = 120;
    private static final int REPLY_TICKS = 140;
    private static final int NEXT_SUGGESTION_COOLDOWN_TICKS = 240;
    private static final int BLOCKED_CONFIRM_TICKS = 20;
    private static final int WAITING_CONFIRM_TICKS = 8;
    /** Below this she cannot sprint, which is the point it starts costing the run something. */
    private static final int HUNGRY_FOOD_LEVEL = 6;
    /** Blocks per tick before a swim counts as going somewhere rather than treading water. */
    private static final double WATER_CLIMB_SPEED = 0.02;
    private static final Set<String> DISMISSED_THIS_SESSION = new HashSet<>();
    private static int MEMORY_GENERATION;

    /**
     * Every face Lune has. One row of the soul atlas each, in the order {@link SoulAnimation}
     * fixes.
     *
     * <p>{@link #QUIET} is Lune muted - the speech setting, or a dismissal she has taken to heart.
     * {@link #RESTING} is the bot actually asleep in a bed. They look alike on purpose and mean
     * entirely different things, so they are never merged.</p>
     */
    public enum Mood {
        IDLE,
        WORKING,
        THINKING,
        ASKING,
        QUIET,
        RESTING,
        PAUSED,
        WAITING,
        LOADING,
        SEARCH,
        TRAVEL,
        BRIDGING,
        PILLARING,
        STAIRS,
        FRAMING,
        DIVING,
        SWIMMING,
        ASCENDING,
        LEARNING,
        STALLED,
        BLOCKED,
        DANGER,
        FIGHT,
        FLEE,
        HURT,
        RECOVERING,
        EFFECT,
        HUNGRY,
        DEAD,
        SUCCESS,
        INVENTORY_FULL,
        MISSING_MATERIALS
    }

    public enum Surface {
        MAIN,
        TASKS,
        WAYPOINTS,
        CONFIG,
        /** The terms, the license and the credits. Nothing here is hers to advise on. */
        ABOUT,
        /** A training puzzle is open. Lune watches and offers, but never answers. */
        TRAINING
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
    private TaskGraph observedTask;
    private String observedFingerprint = "";
    private int stableTaskTicks;
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
    /** What Lune says while a puzzle is open; the Task tab owns the timing behind it. */
    private String trainingLine = "";

    private record Pending(String key, TaskGraph task, TaskSuggestion taskSuggestion) {
        boolean isSafety() {
            return taskSuggestion == null;
        }
    }

    private record UndoRecord(TaskGraph task, TaskGraph snapshot, String changedFingerprint) {}

    public enum Dismissal {
        NOT_NOW,
        NEXT_TIME,
        NEVER_TYPE,
        THIS_TASK,
        LATER;

        private String key() {
            return "lune.mascot.dismissal." + name().toLowerCase(Locale.ROOT);
        }

        public String label() {
            return Lang.get(key() + ".label");
        }

        public String description() {
            return Lang.get(key() + ".desc");
        }
    }

    /** Task ideas belong in Task; the other tabs use calm, contextual ambient dialogue. */
    public void setSurface(Surface surface) {
        this.surface = surface == null ? Surface.MAIN : surface;
    }

    /**
     * The line to speak while a training puzzle is open.
     *
     * <p>Pushed in rather than worked out here, because the thing that decides it - how long the
     * player has been stuck on which lesson - is the Task tab's, and Lune has no business owning a
     * second copy of it.</p>
     */
    public void setTrainingLine(String line) {
        this.trainingLine = line == null ? "" : line;
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

        if (surface == Surface.TRAINING) {
            // A puzzle is a question the player is being asked, and every task suggestion Lune has
            // is an answer to it - "this task has no START, shall I add one?" is step one, solved
            // for them and dismissed in a click. She stays out of the graph until they leave.
            prompting = false;
            dismissalMenu = false;
            stableTaskTicks = 0;
            clearCandidate();
            return;
        }

        if (suppressesSuggestions()) {
            // Live state and recent outcomes are more useful than an unrelated task prompt.
            prompting = false;
            dismissalMenu = false;
            clearCandidate();
            return;
        }

        if (surface != Surface.TASKS && !hasRunningStall(engine)) {
            // Do not turn the dashboard or utility tabs into an unsolicited suggestion queue.
            // The active task, replies, and milestone narration still remain visible.
            //
            // A measured stall is the one exception, because it is not an unsolicited idea: it
            // explains something already happening to the player's game, and the Tasks screen is
            // precisely where they are not sitting when they notice the world going to pieces.
            prompting = false;
            dismissalMenu = false;
            stableTaskTicks = 0;
            clearCandidate();
            return;
        }

        TaskGraph task = candidateTask(engine);
        String fingerprint = TaskSuggestion.fingerprint(task);
        if (task != observedTask || !fingerprint.equals(observedFingerprint)) {
            observedTask = task;
            observedFingerprint = fingerprint;
            stableTaskTicks = 0;
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
        if (task == null || task.nodes == null || task.nodes.isEmpty()) {
            clearCandidate();
            return;
        }
        if (stableTaskTicks < TASK_STABLE_TICKS) {
            stableTaskTicks++;
            return;
        }
        Pending next = nextSuggestion(engine, task);
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
                reply = Lang.get("lune.mascot.reply.not_now");
                replyMood = Mood.QUIET;
                napTicks = NEXT_SUGGESTION_COOLDOWN_TICKS;
            }
            case NEXT_TIME -> {
                hiddenUntilReopen.add(key);
                reply = Lang.get("lune.mascot.reply.next_time");
                replyMood = Mood.SUCCESS;
            }
            case NEVER_TYPE -> {
                config.luneDismissedSuggestionTypes.add(type);
                config.save();
                reply = Lang.get("lune.mascot.reply.never_type");
                replyMood = Mood.SUCCESS;
            }
            case THIS_TASK -> {
                config.luneDismissedTaskSuggestions.add(taskKindKey(
                        candidate.task(), type));
                config.save();
                reply = Lang.get("lune.mascot.reply.this_task");
                replyMood = Mood.SUCCESS;
            }
            case LATER -> {
                config.luneSuggestionReminders.put(key,
                        System.currentTimeMillis() + 3L * 60L * 1000L);
                config.save();
                reply = Lang.get("lune.mascot.reply.later");
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
        return Lang.get("lune.mascot.amount", chosenAmount,
                candidate.taskSuggestion().unit());
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
            return Lang.get("lune.mascot.prompt.title.thought");
        }
        if (candidate != null && candidate.taskSuggestion() != null
                && !candidate.taskSuggestion().changesTask()) {
            return Lang.get("lune.mascot.prompt.title.noticed");
        }
        return Lang.get("lune.mascot.prompt.title.idea");
    }

    public String acceptLabel() {
        return candidate == null || candidate.isSafety()
                ? Lang.get("lune.mascot.prompt.accept_default")
                : candidate.taskSuggestion().acceptLabel();
    }

    public boolean hasPreview() {
        return prompting && candidate != null && (candidate.isSafety()
                || candidate.taskSuggestion().hasPreview());
    }

    public String previewBefore() {
        if (!hasPreview()) {
            return "";
        }
        return candidate.isSafety() ? Lang.get("lune.mascot.preview.no_protection")
                : candidate.taskSuggestion().previewBefore();
    }

    public String previewAfter() {
        if (!hasPreview()) {
            return "";
        }
        return candidate.isSafety() ? Lang.get("lune.mascot.preview.with_protection")
                : candidate.taskSuggestion().previewAfter(chosenAmount);
    }

    public boolean canUndo() {
        if (undo == null || replyTicks <= 0
                || !undo.changedFingerprint().equals(TaskSuggestion.fingerprint(undo.task()))) {
            return false;
        }
        Task current = BotEngine.get().getCurrent();
        if (current instanceof TaskRunner running && running.currentTask() == undo.task()) {
            TaskNode active = running.activeNode();
            return active == null || undo.snapshot().nodeById(active.id) != null;
        }
        return true;
    }

    public void undo() {
        if (!canUndo()) {
            return;
        }
        TaskGraph restored = undo.task();
        if (TaskWiring.restore(restored, undo.snapshot())) {
            TaskStore.get().save();
            observedTask = restored;
            observedFingerprint = TaskSuggestion.fingerprint(restored);
            stableTaskTicks = 0;
            reply = Lang.get("lune.mascot.reply.undone");
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
        boolean actionable = isAttentionMood(mood) && mood != Mood.PAUSED;
        if (BotConfig.LUNE_SPEECH_QUIET.equalsIgnoreCase(config.luneSpeech)) {
            return actionable || prompting || replyTicks > 0;
        }
        if (actionable) {
            return true;
        }
        if (napTicks > 0) {
            return replyTicks > 0;
        }
        return surface != Surface.TASKS || prompting || replyTicks > 0 || milestoneTicks > 0
                || liveMood != Mood.IDLE || engine.getCurrent() != null;
    }

    public Mood mood(BotEngine engine) {
        BotConfig config = BotConfig.get();
        if (config.showLune
                && BotConfig.LUNE_SPEECH_SILENT.equalsIgnoreCase(config.luneSpeech)) {
            return Mood.QUIET;
        }
        if (isAttentionMood(liveMood)) {
            return liveMood;
        }
        if (prompting) {
            return Mood.ASKING;
        }
        if (surface == Surface.TRAINING) {
            // She is watching the player work a puzzle out, not working one out herself.
            return Mood.LEARNING;
        }
        if (replyTicks > 0) {
            return replyMood;
        }
        if (milestoneTicks > 0 && milestoneMood != Mood.IDLE) {
            return milestoneMood;
        }
        if (napTicks > 0) {
            return Mood.QUIET;
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
                return Lang.get("lune.mascot.prompt.safety");
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
        if (surface == Surface.TRAINING && !trainingLine.isBlank()) {
            return trainingLine;
        }
        Task current = engine.getCurrent();
        if (current == null) {
            return surface == Surface.TASKS
                    ? Lang.get("lune.mascot.nothing_running")
                    : idleSpeech(surface);
        }
        String status = current.status();
        return status == null || status.isBlank()
                ? Lang.get("lune.mascot.fallback.working") : sentence(status);
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
        milestoneMood = moodFor(engine.getLastMessageSignal());
        milestone = milestoneMood == Mood.IDLE
                ? Lang.get("lune.mascot.milestone", sentence(message))
                : stateSpeech(milestoneMood, message);
        milestoneTicks = 100;
    }

    /**
     * What Lune is, right now, in the order a player would want to be told it.
     *
     * <p>Trouble first - dead, then the world, then whatever the running task is shouting about.
     * Then her body: asleep, under water, poisoned, starving. Only when none of that applies does
     * she show which job is in hand.</p>
     */
    private void updateActivityState(BotEngine engine) {
        Task current = engine.getCurrent();
        String status = current == null ? "" : current.status();
        liveDetail = status;

        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft == null ? null : minecraft.player;
        if (player != null) {
            if (player.isDeadOrDying()) {
                liveMood = Mood.DEAD;
                liveDetail = status.isBlank() ? Lang.get("lune.mascot.fallback.dead") : status;
                resetConfirmationTicks();
                return;
            }
            if (player.isInLava()) {
                liveMood = Mood.DANGER;
                liveDetail = status.isBlank() ? Lang.get("lune.mascot.in_lava") : status;
                resetConfirmationTicks();
                return;
            }
            if (player.isUnderWater()
                    && player.getAirSupply() <= player.getMaxAirSupply() / 3) {
                liveMood = Mood.DANGER;
                liveDetail = status.isBlank() ? Lang.get("lune.mascot.low_air") : status;
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

        StatusSignal signal = current == null ? StatusSignal.NONE : current.statusSignal();
        if (signal == StatusSignal.BLOCKED) {
            waitingTicks = 0;
            blockedTicks = Math.min(BLOCKED_CONFIRM_TICKS, blockedTicks + 1);
            liveMood = blockedTicks >= BLOCKED_CONFIRM_TICKS ? Mood.BLOCKED
                    : current == null ? Mood.IDLE : Mood.WORKING;
            return;
        }
        if (signal == StatusSignal.WAITING) {
            blockedTicks = 0;
            waitingTicks = Math.min(WAITING_CONFIRM_TICKS, waitingTicks + 1);
            liveMood = waitingTicks >= WAITING_CONFIRM_TICKS ? Mood.WAITING
                    : current == null ? Mood.IDLE : Mood.WORKING;
            return;
        }
        resetConfirmationTicks();

        // Trouble the running task is already reporting outranks anything read off the player.
        if (signal.priority() >= StatusSignal.MISSING_MATERIALS.priority()) {
            liveMood = moodFor(signal);
            return;
        }

        Mood body = bodyMood(player);
        if (body != null) {
            liveMood = body;
            if (status.isBlank()) {
                liveDetail = "";
            }
            return;
        }

        if (BotConfig.get().warnAboutSlowSteps && current instanceof TaskRunner
                && LoopWatch.get().worst() != null) {
            liveMood = Mood.STALLED;
            return;
        }

        liveMood = current == null ? Mood.IDLE : moodFor(signal);
        if (liveMood == Mood.IDLE && current != null) {
            liveMood = Mood.WORKING;
        }
    }

    /**
     * What the player's own body says, or {@code null} when it has nothing to add.
     *
     * <p>Read from the client rather than from a status line, because none of it belongs to a task:
     * she is in a bed, or under water, or poisoned, whatever she happens to be doing.</p>
     */
    private static Mood bodyMood(LocalPlayer player) {
        if (player == null) {
            return null;
        }
        if (player.isSleeping()) {
            return Mood.RESTING;
        }
        if (player.isInWater()) {
            double rise = player.getDeltaMovement().y;
            if (rise > WATER_CLIMB_SPEED) {
                return Mood.ASCENDING;
            }
            if (rise < -WATER_CLIMB_SPEED) {
                return Mood.DIVING;
            }
            return Mood.SWIMMING;
        }
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                return Mood.EFFECT;
            }
        }
        if (player.getFoodData().getFoodLevel() <= HUNGRY_FOOD_LEVEL) {
            return Mood.HUNGRY;
        }
        return null;
    }

    private boolean suppressesSuggestions() {
        return !ordinaryWork(liveMood)
                || milestoneTicks > 0 && milestoneMood != Mood.IDLE;
    }

    /** Moods that interrupt: they describe something happening to the player's game right now. */
    private static boolean isAttentionMood(Mood mood) {
        return switch (mood) {
            case DANGER, FIGHT, FLEE, HURT, DEAD, EFFECT, HUNGRY, STALLED, BLOCKED,
                    INVENTORY_FULL, MISSING_MATERIALS, PAUSED -> true;
            default -> false;
        };
    }

    private static Mood moodFor(StatusSignal signal) {
        return switch (signal) {
            case WAITING -> Mood.WAITING;
            case LOADING -> Mood.LOADING;
            case SEARCH -> Mood.SEARCH;
            case TRAVEL -> Mood.TRAVEL;
            case BRIDGING -> Mood.BRIDGING;
            case PILLARING -> Mood.PILLARING;
            case STAIRS -> Mood.STAIRS;
            case FRAMING -> Mood.FRAMING;
            case BLOCKED -> Mood.BLOCKED;
            case DANGER -> Mood.DANGER;
            case FIGHT -> Mood.FIGHT;
            case FLEE -> Mood.FLEE;
            case HURT -> Mood.HURT;
            case RECOVERING -> Mood.RECOVERING;
            case DEAD -> Mood.DEAD;
            case SUCCESS -> Mood.SUCCESS;
            case INVENTORY_FULL -> Mood.INVENTORY_FULL;
            case MISSING_MATERIALS -> Mood.MISSING_MATERIALS;
            case NONE -> Mood.IDLE;
        };
    }

    /**
     * Moods that are simply a job in hand. Lune keeps offering ideas through these; everything else
     * means she has something more useful to say than an unrelated suggestion.
     */
    private static boolean ordinaryWork(Mood mood) {
        return switch (mood) {
            case IDLE, WORKING, TRAVEL, BRIDGING, PILLARING, STAIRS, FRAMING, SEARCH, LOADING,
                    SWIMMING, DIVING, ASCENDING -> true;
            default -> false;
        };
    }

    private void resetConfirmationTicks() {
        blockedTicks = 0;
        waitingTicks = 0;
    }

    /**
     * One of several ways of saying the same thing, plus the detail behind it.
     *
     * <p>The opening is picked from a bank in the language file rather than chosen at random: the
     * same situation should say the same thing while it lasts, or Lune reads as a slot machine.
     * The rotation is derived from what she is talking about, so the line changes when the
     * circumstances do and not on a timer.</p>
     */
    private static String stateSpeech(Mood mood, String status) {
        if (mood == Mood.PAUSED) {
            LocalTime now = LocalTime.now();
            int minutes = now.getHour() * 60 + now.getMinute();
            return Lang.pick("lune.mascot.paused", minutes / 3);
        }
        String detail = status == null || status.isBlank()
                ? fallbackDetail(mood)
                : sentence(status);
        String bank = switch (mood) {
            case WAITING -> "lune.mascot.opening.waiting";
            case BLOCKED -> "lune.mascot.opening.blocked";
            case DANGER -> "lune.mascot.opening.danger";
            case FIGHT -> "lune.mascot.opening.fight";
            case FLEE -> "lune.mascot.opening.flee";
            case HURT -> "lune.mascot.opening.hurt";
            case RECOVERING -> "lune.mascot.opening.recovering";
            case DEAD -> "lune.mascot.opening.dead";
            case EFFECT -> "lune.mascot.opening.effect";
            case HUNGRY -> "lune.mascot.opening.hungry";
            case STALLED -> "lune.mascot.opening.stalled";
            case SUCCESS -> "lune.mascot.opening.success";
            case INVENTORY_FULL -> "lune.mascot.opening.inventory_full";
            case MISSING_MATERIALS -> "lune.mascot.opening.missing_materials";
            default -> "lune.mascot.opening.working";
        };
        String opening = Lang.pick(bank, detail.toLowerCase(Locale.ROOT).hashCode());
        return Lang.get("lune.mascot.amount", opening, detail);
    }

    private static String fallbackDetail(Mood mood) {
        return Lang.get(switch (mood) {
            case WAITING -> "lune.mascot.fallback.waiting";
            case BLOCKED -> "lune.mascot.fallback.blocked";
            case DANGER -> "lune.mascot.fallback.danger";
            case FIGHT -> "lune.mascot.fallback.fight";
            case FLEE -> "lune.mascot.fallback.flee";
            case HURT -> "lune.mascot.fallback.hurt";
            case RECOVERING -> "lune.mascot.fallback.recovering";
            case DEAD -> "lune.mascot.fallback.dead";
            case EFFECT -> "lune.mascot.fallback.effect";
            case HUNGRY -> "lune.mascot.fallback.hungry";
            case STALLED -> "lune.mascot.fallback.stalled";
            case SUCCESS -> "lune.mascot.fallback.success";
            case INVENTORY_FULL -> "lune.mascot.fallback.inventory_full";
            case MISSING_MATERIALS -> "lune.mascot.fallback.missing_materials";
            default -> "lune.mascot.fallback.working";
        });
    }

    /**
     * Ambient chatter, chosen by where the player is and what time it is where they are.
     *
     * <p>Every bank is discovered from the language file, so how many ways Lune has of greeting a
     * morning is a question about that file and not about this method.</p>
     */
    private static String idleSpeech(Surface surface) {
        LocalTime now = LocalTime.now();
        int minutes = now.getHour() * 60 + now.getMinute();
        String bank = switch (surface) {
            case CONFIG -> "lune.mascot.idle.config";
            case ABOUT -> "lune.mascot.idle.about";
            case WAYPOINTS -> "lune.mascot.idle.waypoints";
            default -> timeOfDayBank(now.getHour());
        };
        return Lang.pick(bank, minutes / 5);
    }

    private static String timeOfDayBank(int hour) {
        if (hour < 5) {
            return "lune.mascot.idle.small_hours";
        }
        if (hour < 10) {
            return "lune.mascot.idle.morning";
        }
        if (hour < 18) {
            return "lune.mascot.idle.day";
        }
        if (hour < 23) {
            return "lune.mascot.idle.evening";
        }
        return "lune.mascot.idle.late";
    }

    public void accept() {
        if (!prompting || candidate == null || candidate.task() == null) {
            return;
        }
        boolean mutating = candidate.isSafety() || candidate.taskSuggestion().changesTask();
        TaskGraph snapshot = mutating ? TaskWiring.copy(candidate.task()) : null;
        boolean changed;
        if (candidate.isSafety()) {
            changed = TaskSafety.installDefaultMonitor(candidate.task());
        } else {
            changed = candidate.taskSuggestion().apply(chosenAmount);
        }
        if (changed) {
            DISMISSED_THIS_SESSION.add(candidate.key());
            if (mutating) {
                TaskStore.get().save();
                undo = new UndoRecord(candidate.task(), snapshot,
                        TaskSuggestion.fingerprint(candidate.task()));
            }
            if (candidate.isSafety()) {
                reply = Lang.get("lune.mascot.reply.safety_added");
            } else if (candidate.taskSuggestion().kind() == TaskSuggestion.Kind.QUANTITY) {
                reply = Lang.get("lune.mascot.reply.quantity", chosenAmount,
                        candidate.taskSuggestion().unit());
            } else if (candidate.taskSuggestion().kind() == TaskSuggestion.Kind.AUTO_TOOL) {
                reply = Lang.get("lune.mascot.reply.auto_tool");
            } else {
                reply = candidate.taskSuggestion().acceptedReply();
            }
            replyMood = Mood.SUCCESS;
        } else {
            undo = null;
            reply = Lang.get("lune.mascot.reply.task_changed");
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
    }

    private void finishPrompt() {
        prompting = false;
        dismissalMenu = false;
        replyTicks = REPLY_TICKS;
        suggestionCooldownTicks = NEXT_SUGGESTION_COOLDOWN_TICKS;
        clearCandidate();
    }

    private static String taskKey(TaskGraph task) {
        return task == null || task.name == null
                ? ""
                : task.name.trim().toLowerCase(Locale.ROOT);
    }

    private Pending nextSuggestion(BotEngine engine, TaskGraph task) {
        String taskKey = taskKey(task);
        String safetyKey = taskKey + ":safety";

        TaskNode preferred = null;
        boolean isRunningTask = false;
        Task current = engine.getCurrent();
        if (current instanceof TaskRunner running && running.currentTask() == task) {
            preferred = running.currentNode();
            isRunningTask = true;
        }
        Set<String> ignored = new HashSet<>(DISMISSED_THIS_SESSION);

        // Ahead of everything else, and only for the task actually running. This one is measured
        // rather than derived, and it describes something the player can see happening to their
        // game right now - advice about mining quotas can wait until the stutter is explained.
        Pending slow = slowStepSuggestion(task, isRunningTask, ignored);
        if (slow != null) {
            return slow;
        }

        // Structural repairs come before safety and workload advice. In particular, an imported
        // task without START must be offered an explicit entry point before Lune discusses
        // mining quotas or protection.
        var leading = TaskSuggestion.startFor(task);
        if (leading.isEmpty() || ignored.contains(leading.get().key())) {
            leading = TaskSuggestion.finiteMonitorFor(task);
        }
        if (leading.isPresent()) {
            TaskSuggestion found = leading.get();
            Pending pending = new Pending(found.key(), task, found);
            if (!isSuppressed(pending)) {
                return pending;
            }
            ignored.add(found.key());
        }

        if (!TaskSafety.hasMonitor(task) && !DISMISSED_THIS_SESSION.contains(safetyKey)) {
            Pending safety = new Pending(safetyKey, task, null);
            if (!isSuppressed(safety)) {
                return safety;
            }
        }

        while (true) {
            var suggestion = TaskSuggestion.firstFor(task, preferred, ignored,
                    suggestionContext());
            if (suggestion.isEmpty()) {
                return null;
            }
            TaskSuggestion found = suggestion.get();
            Pending pending = new Pending(found.key(), task, found);
            if (!isSuppressed(pending)) {
                return pending;
            }
            ignored.add(found.key());
        }
    }

    /** Cheap precondition for letting a stall warning past the surface gate. */
    private boolean hasRunningStall(BotEngine engine) {
        return BotConfig.get().warnAboutSlowSteps
                && engine.getCurrent() instanceof TaskRunner
                && LoopWatch.get().worst() != null;
    }

    /**
     * The running task's own measured stall, if it has one the player has not already waved away.
     *
     * <p>Deliberately silent for a task being edited but not run: LoopWatch only ever describes the
     * run in progress, and attaching that verdict to whichever task happens to be open on screen
     * would blame the wrong one.</p>
     */
    private Pending slowStepSuggestion(TaskGraph task, boolean isRunningTask, Set<String> ignored) {
        if (!isRunningTask || task == null || !BotConfig.get().warnAboutSlowSteps) {
            return null;
        }
        LoopWatch.Spin spin = LoopWatch.get().worst();
        if (spin == null) {
            return null;
        }
        TaskNode node = task.nodeById(spin.nodeId());
        if (node == null) {
            return null;
        }
        var suggestion = TaskSuggestion.slowStepFor(task, node, spin.microsPerTick(),
                spin.restartsPerSecond(), spin.permanent());
        if (suggestion.isEmpty() || ignored.contains(suggestion.get().key())) {
            return null;
        }
        Pending pending = new Pending(suggestion.get().key(), task, suggestion.get());
        return isSuppressed(pending) ? null : pending;
    }

    private boolean isSuppressed(Pending pending) {
        if (memoryGeneration != MEMORY_GENERATION) {
            hiddenUntilReopen.clear();
            memoryGeneration = MEMORY_GENERATION;
        }
        if (pending == null || pending.task() == null) {
            return false;
        }
        String type = candidateType(pending);
        String taskKind = taskKindKey(pending.task(), type);
        BotConfig config = BotConfig.get();
        ensurePreferenceCollections(config);
        if (DISMISSED_THIS_SESSION.contains(pending.key())
                || config.luneDismissedSuggestionTypes.contains(type)
                || config.luneDismissedTaskSuggestions.contains(taskKind)
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

    private static String taskKindKey(TaskGraph task, String type) {
        return taskKey(task) + ":" + type;
    }

    private static void ensurePreferenceCollections(BotConfig config) {
        if (config.luneDismissedSuggestionTypes == null) {
            config.luneDismissedSuggestionTypes = new java.util.LinkedHashSet<>();
        }
        if (config.luneDismissedTaskSuggestions == null) {
            config.luneDismissedTaskSuggestions = new java.util.LinkedHashSet<>();
        }
        if (config.luneSuggestionReminders == null) {
            config.luneSuggestionReminders = new java.util.LinkedHashMap<>();
        }
    }

    private static TaskSuggestion.Context suggestionContext() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return TaskSuggestion.Context.unknown();
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
        long dayTime = WorldClock.dayTime(minecraft.level);
        String dimension = minecraft.level.dimension().identifier().toString();
        var biome = minecraft.level.getBiome(minecraft.player.blockPosition());
        return new TaskSuggestion.Context(
                InventoryHelper.freeSlots(minecraft.player),
                minecraft.player.getFoodData().getFoodLevel(),
                torches,
                pickaxeDurability,
                axeDurability,
                WorldClock.isNight(dayTime),
                dimension,
                BiomeKnowledge.displayName(biome),
                BiomeKnowledge.isBarrenFor(biome, Set.of(Need.WOOD)));
    }

    /**
     * Prefer the task that is actually running. While idle, the last task selected in the
     * editor remains useful context; this also lets a very short task finish without making the
     * recommendation disappear before the player can reopen the panel.
     */
    private static TaskGraph candidateTask(BotEngine engine) {
        Task current = engine.getCurrent();
        if (current instanceof TaskRunner running) {
            return running.currentTask();
        }
        if (current != null) {
            return null;
        }
        String lastOpened = BotConfig.get().lastOpenedTask;
        if (lastOpened == null || lastOpened.isBlank()) {
            return null;
        }
        return TaskStore.get().byName(lastOpened).orElse(null);
    }

    private static String sentence(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return Lang.get("lune.mascot.fallback.working");
        }
        String capitalised = Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
        char last = capitalised.charAt(capitalised.length() - 1);
        return last == '.' || last == '!' || last == '?' ? capitalised : capitalised + ".";
    }
}
