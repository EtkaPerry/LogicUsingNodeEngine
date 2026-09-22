package com.etka.lune.bot;

import com.etka.lune.Constants;
import com.etka.lune.bot.input.BotClientInput;
import com.etka.lune.bot.input.BotInput;
import com.etka.lune.bot.input.LookController;
import com.etka.lune.bot.learning.AutomaticApproval;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.learning.LearningSession;
import com.etka.lune.bot.learning.LearningStore;
import com.etka.lune.bot.learning.TaskLearning;
import com.etka.lune.bot.util.Leash;
import com.etka.lune.bot.util.Cheats;
import com.etka.lune.config.BotConfig;
import com.etka.lune.config.Terms;
import com.etka.lune.util.Alerts;
import com.etka.lune.util.Lang;
import com.etka.lune.platform.BuildFeatures;
import com.etka.lune.waypoint.Death;
import com.etka.lune.waypoint.DeathStore;
import com.etka.lune.waypoint.WaypointStore;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The bot's heart: owns the task queue, ticks the active task, and holds the virtual input the
 * whole movement layer writes into.
 * <p>
 * A client-side singleton. There is exactly one local player, so there is exactly one bot.
 */
public final class BotEngine {

    private static final BotEngine INSTANCE = new BotEngine();

    private final Deque<Task> queue = new ArrayDeque<>();
    private final BotInput input = new BotInput();
    private final LookController look = new LookController();
    private final DebugInfo debug = new DebugInfo();
    private final BotStatisticsStore statistics = BotStatisticsStore.get();
    /** The learned policy is part of the shipped bot and is shared by dev and release installs. */
    private final LearningStore learning = LearningStore.get();
    private RunTrace runTrace;
    private int botTicks;
    private boolean runStatisticsActive;
    private final RunSampler sampler = new RunSampler();

    private LearningSession learningSession = LearningSession.idle();
    private LearningContext currentLearningContext = LearningContext.of("idle", "idle", "unknown");

    private Task current;
    /**
     * Where the bot stood when the current run began, for anything that measures "home". Taken once
     * per run and not per task, so a task of twenty nodes cannot inch away from it.
     */
    private BlockPos runAnchor;
    private boolean paused;
    private String lastMessage = "";
    /**
     * What the last message meant.
     *
     * <p>Carried beside the sentence instead of being read back out of it. The mascot used
     * to decide whether a run had ended well by looking for the word "finished", which is a
     * question about English rather than about the run.</p>
     */
    private StatusSignal lastMessageSignal = StatusSignal.NONE;
    private String lastThought = "";
    private int thoughtRepeat;
    private static final int MAX_THOUGHTS = 7;
    /**
     * Whether the death on screen right now has already been written down.
     *
     * <p>The death screen leaves the player object in place, so the branch that notices it runs
     * every tick until they respawn. Without this the store would fill with the same death several
     * hundred times and push every earlier one out of it.</p>
     */
    private boolean deathRecorded;

    private BotEngine() {}

    public static BotEngine get() {
        return INSTANCE;
    }

    // --- state ---------------------------------------------------------------

    /** True when the bot should be driving the player instead of the keyboard. */
    public boolean isDriving() {
        return current != null && !paused;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Nothing running and nothing waiting: the queue has genuinely finished. */
    public boolean isIdle() {
        return current == null && queue.isEmpty();
    }

    public Task getCurrent() {
        return current;
    }

    public List<Task> getQueue() {
        return new ArrayList<>(queue);
    }

    public BotInput getInput() {
        return input;
    }

    public DebugInfo getDebug() {
        return debug;
    }

    /** Returns a snapshot of the currently active run, or zeroes while the bot is idle. */
    public BotStatistics currentRunStatistics() {
        return runStatisticsActive ? BotStatistics.from(debug) : new BotStatistics();
    }

    public BotStatistics lastRunStatistics() {
        return statistics.lastRun();
    }

    public BotStatistics allTimeStatistics() {
        return statistics.allTime();
    }

    public String getLastMessage() {
        return lastMessage;
    }

    /** Whether the last message was a finish, a failure, or neither. */
    public StatusSignal getLastMessageSignal() {
        return lastMessageSignal;
    }

    public LearningStore getLearning() {
        return learning;
    }

    /** Records explicit feedback against the last concrete skill tactic, never its parent route. */
    public String recordLearningFeedback(boolean positive) {
        BotConfig config = BotConfig.get();
        if (!BuildFeatures.approvalFeedback() || !config.userLearningEnabled || !config.learningEnabled) {
            return Lang.get("lune.engine.user_feedback_unavailable_release_builds");
        }
        LearningStore.SkillChoice choice = learning.feedbackSkillChoice();
        if (choice == null) {
            return Lang.get("lune.engine.changeable_skill_tactic_yet_fixed_jobs");
        }
        String skill = choice.context().task();
        String judgedAction = choice.action();
        learning.recordUserFeedback(choice.context(), judgedAction, positive);
        String label = positive ? "approved" : "rejected";
        if (learningSession.active()) {
            learningSession.remember("user " + label + " " + skill + " " + judgedAction);
        }
        debug.learningMemory = learning.summary(BuildFeatures.approvalFeedback());
        debug.decide("user feedback: " + label + " " + skill + " " + judgedAction);
        String switched = positive ? null : learning.switchRejectedSkill(choice);
        if (switched != null) {
            if (learningSession.active()) {
                learningSession.decision(choice.context(), switched);
            }
            debug.learningAction = switched;
            return Lang.get("lune.engine.rejected_tactic_switching", skill, judgedAction, switched);
        }
        return "Skill " + skill + " " + label + ": " + judgedAction;
    }

    /** "Idle", "Paused", or the running task's name. */
    public String describeState() {
        if (current == null) {
            return "Idle";
        }
        return paused ? "Paused" : "Running";
    }

    // --- queue control -------------------------------------------------------

    public void enqueue(Task task) {
        if (refusedByTerms()) {
            return;
        }
        queue.addLast(task);
    }

    /** Cancels whatever is running and starts this task immediately, keeping the rest of the queue. */
    public void runNow(Task task) {
        if (refusedByTerms()) {
            return;
        }
        cancelCurrent();
        queue.addFirst(task);
        paused = false;
    }

    /**
     * Refuses to take work while Lune's terms are unaccepted, and says so.
     *
     * <p>The control panel is the only route a player has to this method and it does not open
     * unaccepted, so in practice this never fires. It is here anyway because "the bot will not run
     * until you accept" is a promise made to the player on the screen, and a promise kept in one
     * place is a promise the next caller cannot walk around. It is not a behaviour: nothing is
     * decided here, the queue simply stays shut.</p>
     */
    private boolean refusedByTerms() {
        if (Terms.mayRun()) {
            return false;
        }
        lastMessage = Lang.get("lune.engine.accept_terms_first");
        lastMessageSignal = StatusSignal.BLOCKED;
        Constants.LOG.warn("Refused to start a task: Lune's terms have not been accepted");
        return true;
    }

    /**
     * Pauses or resumes, and says so in the journal.
     * <p>
     * A run where the player took over mid-way is otherwise unreadable afterwards: the trace shows
     * the bot standing still and then inexplicably somewhere else with different items, and there
     * is no way to tell a frozen bot from a paused one. The two need different fixes, so the file
     * has to distinguish them.
     */
    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        this.paused = paused;
        // Whatever happened while the player had the controls is theirs, not the run's. Without
        // this the first tick back credits the bot with every block the player walked meanwhile.
        sampler.reset();
        if (!paused) {
            releaseUseKey();
        }
        Minecraft mc = Minecraft.getInstance();
        if (runTrace != null && mc != null && mc.player != null && mc.level != null) {
            runTrace.event(botTicks, paused ? Lang.get("lune.engine.paused_by_user") : Lang.get("lune.engine.resumed_by_user"),
                    debug, mc.player, mc.level);
        }
    }

    public void clearQueue() {
        queue.clear();
    }

    /** Panic button: drop the current task and the whole queue, and release every held key. */
    public void stopAll() {
        stopAll(Lang.get("lune.engine.stopped_by_user"));
    }

    /**
     * Stops everything and says why.
     * <p>
     * The reason is not decoration: it is what the journal and the learning profile record. An
     * unattended harness that stops on its own budget is not the player giving up on the run, and
     * scoring it as one is how a profile ends up with fifty-eight failures and no successes.
     */
    public void stopAll(String reason) {
        finishRunStatistics();
        cancelCurrent();
        queue.clear();
        paused = false;
        input.reset();
        releaseUseKey();
        lastMessage = Lang.get("lune.engine.stopped");
        lastMessageSignal = StatusSignal.NONE;
        runAnchor = null;
        Leash.get().release();
        finishLearningSession(reason, false);
        closeRunTrace(reason);
    }

    /**
     * The use key is a global that {@link com.etka.lune.bot.task.EatTask} holds down, so the stop
     * button has to clear it too. A task that is cancelled between holding and releasing would
     * otherwise leave the player right-clicking the world for as long as the client runs.
     */
    private void releaseUseKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) {
            mc.options.keyUse.setDown(false);
        }
    }

    private void cancelCurrent() {
        if (current == null) {
            return;
        }
        BotContext ctx = tryBuildContext();
        if (ctx != null) {
            // Nothing below here chose to end. TaskLearning reads that from the episode having no
            // verdict of its own and banks its progress rather than filing it as a failed tactic.
            current.stop(ctx);
            recordLearningAbort(current, "cancelled");
        }
        current = null;
        input.reset();
    }

    /**
     * Writes down where the player died, once, and says so out loud.
     *
     * <p>This lives in the engine rather than on the canvas, and it is the one thing that has to:
     * by the time the player is dead there is no task left to ask - the queue is being torn down in
     * the same breath - so no card could ever catch it. What it does is take a note. Nothing moves,
     * nothing is collected, and a Recover Death Drop card still has to be on the canvas for
     * anything to come of it.</p>
     *
     * <p>It is not conditional on a task having been running. A death in a session where nobody
     * pressed Run is still a death the player would like the coordinates of, and a note that is
     * only taken when a setting was switched on beforehand is a note nobody has when they need
     * it.</p>
     */
    private void recordDeath(LocalPlayer player) {
        if (deathRecorded) {
            return;
        }
        deathRecorded = true;
        BlockPos pos = player.blockPosition().immutable();
        DeathStore.get().record(Death.at(pos, WaypointStore.currentDimension(), System.currentTimeMillis()));
        Alerts.died(Lang.get("lune.alert.died_at", pos.getX(), pos.getY(), pos.getZ()));
    }

    // --- ticking -------------------------------------------------------------

    public void tick(Minecraft mc) {
        LuneProfiler.beginTick();
        try {
            tickProfiled(mc);
        } finally {
            LuneProfiler.endTick();
        }
    }

    private void tickProfiled(Minecraft mc) {
        // Followed here rather than only when the Config tab writes it, so the setting survives a
        // restart and can be turned on by hand-editing the config for a session that will not
        // survive long enough to open a menu.
        if (LuneProfiler.isEnabled() != BotConfig.get().debugProfiler) {
            LuneProfiler.setEnabled(BotConfig.get().debugProfiler);
        }
        // Before tickInternal, which returns immediately without a player: leaving a world is
        // exactly when the cheat switches have to be dropped, and there is no player by then.
        Cheats.enforce(mc);
        // Before tickInternal, which returns immediately without a player - and a test run that has
        // to build its own world has no player yet.
        AutoRun.beforeWorld(mc);
        tickInternal(mc);
        // Runs after the task tick so the overlay shows the keys actually pressed this tick.
        updateDebug();
        if (runTrace != null && mc.player != null && mc.level != null) {
            runTrace.tick(botTicks, debug, mc.player, mc.level);
        }
    }

    private void tickInternal(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            // Left the world mid-task; drop everything rather than resuming into a new one.
            if (current != null) {
                recordLearningAbort(current, Lang.get("lune.engine.left_world"));
            }
            finishRunStatistics();
            if (current != null || !queue.isEmpty()) {
                current = null;
                queue.clear();
            }
            finishLearningSession(Lang.get("lune.engine.left_world"), false);
            closeRunTrace(Lang.get("lune.engine.left_world"));
            learning.flush();
            Leash.get().release();
            AutoRun.runInterrupted(mc, Lang.get("lune.engine.world_went_away"));
            return;
        }

        // A death screen leaves the LocalPlayer object around for a while. Do not keep ticking
        // the task against that dead entity; the next world must start with a clean queue and a
        // closed trace.
        if (!player.isAlive()) {
            recordDeath(player);
            if (current != null || !queue.isEmpty()) {
                // Say which one it was. The plain stopAll() closes the journal as "stopped by
                // user", so every death read back afterwards as the player having pressed stop -
                // with the last traced tick still mid-air and at full health, and no clue that
                // the ground had arrived.
                if (runTrace != null) {
                    runTrace.event(botTicks, Lang.get("lune.engine.player_died"), debug, player, mc.level);
                }
                stopAll(Lang.get("lune.engine.player_died"));
            } else {
                input.reset();
            }
            AutoRun.runInterrupted(mc, Lang.get("lune.engine.player_died_3"));
            return;
        }

        deathRecorded = false;
        botTicks++;

        installInputHook(player);

        // Cleared every tick so a task that stops setting a key immediately stops pressing it.
        input.reset();
        debug.clearActionMarkers();
        debug.clearTarget();
        look.setMaxTurnPerTick(BotConfig.get().turnSpeed);
        look.setAcceleration(BotConfig.get().turnSmoothing);

        // Started from the command line rather than by a player; no-op in an ordinary session.
        AutoRun.tick(mc, this);

        // A recorded human session. The journal opens with no task behind it and is then ticked by
        // the caller exactly as it is for a run, so the two come out in the same units and the same
        // analysis reads both. Nothing is driven: isDriving() is false while current is null, so
        // BotClientInput hands the player their own keys back untouched.
        if (AutoRun.isRecordingSession() && runTrace == null && current == null) {
            openRunTrace(player, mc.level, BotConfig.get(), true);
            if (runTrace != null) {
                debug.taskName = "recorded session";
                debug.taskId = "recorded session";
                debug.intent = "human play; nothing is driving";
                runTrace.event(botTicks, "recording-started", debug, player, mc.level);
            }
        }

        if (paused) {
            return;
        }
        if (current == null) {
            current = queue.pollFirst();
            if (current == null) {
                return;
            }
            // First task off an empty queue is the start of a run; everything after it is the same
            // run continuing, so the anchor is taken here and left alone until the queue drains.
            if (runAnchor == null) {
                runAnchor = player.blockPosition().immutable();
                startRunStatistics();
            }
            BotContext startCtx = buildContext(mc, player, mc.level);
            debug.clearDecisionTrace();
            debug.clearPath();
            debug.clearLearningAction();
            debug.taskTicks = 0;
            openRunTrace(player, mc.level, startCtx.config);
            ensureLearningSession(player, mc.level);
            startCtx = buildContext(mc, player, mc.level);
            // mission(), which asks the task for its learning id rather than its displayed name.
            // This used to pass current.name() twice - the sentence from the Main tab, translated -
            // so the table gained a fresh, empty set of rows for every language the player used,
            // and the 28 rows shipped under the old English wording became unreachable.
            currentLearningContext = LearningContext.mission(current,
                    mc.level.dimension().identifier().toString());
            current.start(startCtx);
            debug.taskName = current.name();
            debug.taskId = current.learningId();
            debug.taskStatus = current.status();
            if (runTrace != null) {
                runTrace.event(botTicks, "task-start", debug, player, mc.level);
            }
        }

        BotContext ctx = buildContext(mc, player, mc.level);
        debug.taskTicks++;
        debug.workedTicks++;
        sampler.tick(player, input, debug, runAnchor);

        TaskStatus status;
        try {
            status = current.tick(ctx);
        } catch (RuntimeException e) {
            // A crashing task must not take the game down with it.
            Constants.LOG.error("Task {} threw, stopping bot", current.name(), e);
            if (runTrace != null) {
                runTrace.event(botTicks, "task-error " + e.getClass().getSimpleName() + ":" + e.getMessage(),
                        debug, player, mc.level);
            }
            ctx.chat("Task '" + current.name() + "' errored: " + e);
            stopAll();
            return;
        }

        switch (status) {
            case RUNNING -> {
            }
            case SUCCESS -> {
                Task finished = current;
                debug.tasksCompleted++;
                debug.peak("task_ticks", debug.taskTicks);
                String detail = current.status().isBlank()
                        ? "" : Lang.get("lune.engine.detail_suffix", current.status());
                boolean nextMission = !queue.isEmpty();
                finished.stop(ctx);
                AutomaticApproval.Decision automatic = recordLearningOutcome(finished,
                        TaskStatus.SUCCESS);
                String queued = nextMission
                        ? Lang.get("lune.engine.next_mission_queued") : "";
                if (BuildFeatures.approvalFeedback()) {
                    lastMessage = Lang.get("lune.engine.task_finished_auto", finished.name(),
                            detail, automatic.label(), queued);
                } else {
                    lastMessage = Lang.get("lune.engine.task_finished", finished.name(),
                            detail, queued);
                }
                // A finish that ended in trouble is still trouble; the task keeps the reading.
                lastMessageSignal = finished.statusSignal() == StatusSignal.NONE
                        ? StatusSignal.SUCCESS : finished.statusSignal();
                ctx.chat(lastMessage);
                current = null;
                if (runTrace != null) {
                    runTrace.event(botTicks, "task-success", debug, player, mc.level);
                }
                if (queue.isEmpty()) {
                    // The run is over, so "home" stops meaning anything until the next one starts.
                    runAnchor = null;
                    Leash.get().release();
                    finishLearningSession(Lang.get("lune.engine.queue_complete"), true);
                    closeRunTrace(Lang.get("lune.engine.queue_complete"));
                    finishRunStatistics();
                    // The whole run, not each task in it: a queue of nine would otherwise raise
                    // eight alerts nobody asked for before the one they were waiting for.
                    Alerts.runFinished(finished.name(), false);
                }
            }
            case FAILED -> {
                Task failed = current;
                debug.tasksFailed++;
                debug.peak("task_ticks", debug.taskTicks);
                String detail = failed.status();
                StatusSignal failedSignal = failed.statusSignal();
                failed.stop(ctx);
                AutomaticApproval.Decision automatic = recordLearningOutcome(failed,
                        TaskStatus.FAILED);
                if (BuildFeatures.approvalFeedback()) {
                    lastMessage = Lang.get("lune.engine.task_failed_auto", failed.name(),
                            detail, automatic.label());
                } else {
                    lastMessage = Lang.get("lune.engine.task_failed", failed.name(), detail);
                }
                lastMessageSignal = failedSignal == StatusSignal.NONE
                        ? StatusSignal.BLOCKED : failedSignal;
                ctx.chat(lastMessage);
                current = null;
                if (runTrace != null) {
                    runTrace.event(botTicks, "task-failed", debug, player, mc.level);
                }
                // Halt the rest of the queue: continuing after a failure usually compounds it.
                queue.clear();
                // A failure always ends the run, so this needs no queue check of its own.
                Alerts.runFinished(failed.name(), true);
                finishLearningSession(Lang.get("lune.engine.task_failed"), false);
                closeRunTrace(Lang.get("lune.engine.task_failed"));
                finishRunStatistics();
            }
        }
    }

    /**
     * Swaps the player's keyboard input for {@link BotClientInput} the first time we see a given
     * player instance. Re-checked every tick because respawning and dimension changes replace the
     * {@code LocalPlayer}, and the replacement gets a fresh vanilla input object.
     */
    private void installInputHook(LocalPlayer player) {
        if (!(player.input instanceof BotClientInput)) {
            player.input = new BotClientInput(player.input, this::isDriving, () -> input);
        }
    }

    private BotContext buildContext(Minecraft mc, LocalPlayer player, ClientLevel level) {
        return new BotContext(mc, player, level, mc.gameMode, input, look, BotConfig.get(), debug,
                learning, learningSession, "default", runAnchor);
    }

    /** Mirrors this tick's state into the overlay's telemetry. */
    private void updateDebug() {
        debug.state = describeState();
        boolean recording = current == null && AutoRun.isRecordingSession();
        debug.taskName = current == null
                ? (recording ? "recorded session" : "-")
                : current.name();
        debug.taskId = current == null
                ? (recording ? "recorded session" : "-")
                : current.learningId();
        // A journal with an empty status column summarises to nothing at all. Say plainly that the
        // person is playing, so the closing summary has something to divide the time between.
        debug.taskStatus = current != null ? current.status()
                : recording ? "human play; nothing is driving" : "";
        debug.nextTask = queue.isEmpty() ? "" : queue.peekFirst().name();
        debug.queueSize = queue.size();
        debug.keys = describeKeys();
        LearningStore.SkillChoice skillChoice = learning.lastSkillChoice();
        if (current != null && skillChoice != null && skillChoice.active()) {
            debug.learningAction = skillChoice.action();
            debug.learningContext = skillChoice.context().key();
            debug.learningBestTicksPerUnit = learning.bestSkillTicksPerUnit(skillChoice.context());
        } else {
            debug.learningAction = "";
            debug.learningContext = "";
        }
        if (learningSession.active()) {
            debug.learningReward = learningSession.reward();
        }
        debug.learningUpdates = learning.updateCount();
        if (debug.learningMemory.isEmpty()) {
            debug.learningMemory = learning.summary(BuildFeatures.approvalFeedback());
        }
        if (!BuildFeatures.approvalFeedback()) {
            debug.automaticVerdict = "";
            debug.automaticReason = "";
            debug.automaticTicks = 0L;
            debug.automaticUsualTicks = 0L;
            debug.automaticBestTicks = 0L;
        }

        String thought = current == null ? "" : (debug.taskName + " - " + debug.taskStatus);
        if (!thought.isEmpty()) {
            if (thought.equals(lastThought)) {
                thoughtRepeat++;
            } else {
                debug.thoughts.addLast(thought);
                if (debug.thoughts.size() > MAX_THOUGHTS) {
                    debug.thoughts.removeFirst();
                }
                lastThought = thought;
                thoughtRepeat = 1;
            }
        } else {
            thoughtRepeat = 0;
        }
        debug.thoughtRepeat = thoughtRepeat;
    }

    private void openRunTrace(LocalPlayer player, ClientLevel level, BotConfig config) {
        openRunTrace(player, level, config, false);
    }

    /**
     * @param force open regardless of the debug setting, for a session the caller explicitly asked
     *              to record - a recorded human run is the whole reason the client was started, so
     *              silently declining to write it because a debug toggle is off wastes the session
     */
    private void openRunTrace(LocalPlayer player, ClientLevel level, BotConfig config,
                              boolean force) {
        if (!BuildFeatures.runTracingEnabled()
                || (!config.debugRunLog && !force)
                || runTrace != null) {
            return;
        }
        runTrace = RunTrace.open(player, level, config);
        debug.runTraceFile = runTrace == null ? "" : runTrace.file().toString();
    }

    private void closeRunTrace(String reason) {
        if (runTrace == null) {
            return;
        }
        runTrace.close(reason);
        runTrace = null;
        debug.runTraceFile = "";
    }

    private void startRunStatistics() {
        debug.clearRunStatistics();
        sampler.reset();
        // Counted at the start so an interrupted run still appears in the lifetime totals.
        debug.count("runs");
        runStatisticsActive = true;
    }

    private void finishRunStatistics() {
        if (!runStatisticsActive) {
            return;
        }
        statistics.recordRun(BotStatistics.from(debug));
        debug.clearRunStatistics();
        runStatisticsActive = false;
    }

    private String describeKeys() {
        StringBuilder keys = new StringBuilder();
        if (input.forward) keys.append('W');
        if (input.left) keys.append('A');
        if (input.backward) keys.append('S');
        if (input.right) keys.append('D');
        if (input.jump) keys.append(" JUMP");
        if (input.sprint) keys.append(" SPRINT");
        if (input.sneak) keys.append(" SNEAK");
        return keys.isEmpty() ? "-" : keys.toString();
    }

    private BotContext tryBuildContext() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return null;
        }
        return buildContext(mc, mc.player, mc.level);
    }

    private void ensureLearningSession(LocalPlayer player, ClientLevel level) {
        if (learningSession.active()) {
            return;
        }
        learning.startSkillSession();
        TaskLearning.clear();
        learningSession = LearningSession.start(current == null ? "bot" : current.name());
        debug.learningMemory = "session " + learningSession.id() + " started at "
                + player.blockPosition().toShortString() + " in " + level.dimension();
    }

    private AutomaticApproval.Decision recordLearningOutcome(Task task, TaskStatus result) {
        long elapsedTicks = Math.max(1L, debug.taskTicks);
        boolean success = result == TaskStatus.SUCCESS;
        if (BuildFeatures.approvalFeedback()) {
            AutomaticApproval.Decision automatic = automaticDecision(task, success, elapsedTicks);
            rememberAutomaticOutcome(task, automatic, success);
            return automatic;
        }
        rememberMissionOutcome(task, success, elapsedTicks);
        return null;
    }

    private void recordLearningAbort(Task task, String reason) {
        if (!learningSession.active()) {
            return;
        }
        recordLearningOutcome(task, TaskStatus.FAILED);
        learningSession.remember("abort " + reason + " " + task.name());
    }

    private AutomaticApproval.Decision automaticDecision(Task task, boolean success, long elapsedTicks) {
        BotConfig config = BotConfig.get();
        if (config.learningEnabled) {
            return learning.recordMissionOutcome(currentLearningContext, success, elapsedTicks);
        }
        return AutomaticApproval.evaluate(success, elapsedTicks, 0L);
    }

    private void rememberAutomaticOutcome(Task task, AutomaticApproval.Decision automatic,
                                          boolean missionSucceeded) {
        debug.automaticVerdict = automatic.label();
        debug.automaticReason = automatic.reason();
        debug.automaticTicks = automatic.elapsedTicks();
        debug.automaticUsualTicks = automatic.usualTicks();
        debug.automaticBestTicks = learning.bestMissionTicks(currentLearningContext);
        debug.decide("automatic " + automatic.label() + ": " + automatic.reason());
        if (!learningSession.active()) {
            return;
        }
        learningSession.automaticOutcome(task.name(), automatic);
        learningSession.missionOutcome(task.name(), missionSucceeded, automatic.elapsedTicks());
        debug.learningMemory = learningSession.summary(BuildFeatures.approvalFeedback()) + "; "
                + learning.summary(BuildFeatures.approvalFeedback());
    }

    private void rememberMissionOutcome(Task task, boolean success, long elapsedTicks) {
        if (!learningSession.active()) {
            return;
        }
        learningSession.missionOutcome(task.name(), success, elapsedTicks);
        debug.learningMemory = learningSession.summary(BuildFeatures.approvalFeedback()) + "; "
                + learning.summary(BuildFeatures.approvalFeedback());
    }

    private void finishLearningSession(String reason, boolean success) {
        if (!learningSession.active()) {
            return;
        }
        learning.finishSession(learningSession, reason, success);
        // A session ending is worth a real write: it is the last moment the numbers it measured
        // are guaranteed to still be in memory.
        learning.flush();
        debug.learningReward = learningSession.reward();
        debug.learningMemory = learningSession.summary(BuildFeatures.approvalFeedback()) + "; "
                + learning.summary(BuildFeatures.approvalFeedback());
        learningSession = LearningSession.idle();
        currentLearningContext = LearningContext.of("idle", "idle", "unknown");
    }

}
