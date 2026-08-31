package com.etka.lune.bot;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live telemetry for the on-screen debug overlay.
 * <p>
 * Plain mutable fields written by the engine, tasks and the path executor as they run. Nothing here
 * affects behaviour - it exists so that "the bot is stuck" can be diagnosed from the game rather
 * than guessed at from the outside.
 */
public final class DebugInfo {

    // --- engine ---
    public String state = "Idle";
    public String taskName = "-";
    public String taskStatus = "";
    public String nextTask = "";
    public int queueSize;
    public String lastEvent = "";
    public final Deque<String> thoughts = new ArrayDeque<>();
    public int thoughtRepeat;
    public int taskTicks;
    /** Runtime counters for the Main dashboard; these survive individual task transitions. */
    public long workedTicks;
    public long blocksBroken;
    public long blocksPlaced;
    public long foodEaten;
    public long tasksCompleted;
    public long tasksFailed;
    /** Re-paths counted across the active top-level run, unlike {@link #repaths} per Goto task. */
    public long runRepaths;
    /**
     * Named run counters raised where the work happens - a search finishing, an ore breaking, a
     * mob dying - and read back by the dashboard through {@link BotStatistics}.
     *
     * <p>Keys starting with {@link BotStatistics#PEAK_PREFIX} hold a high-water mark rather than a
     * total; write those through {@link #peak}.</p>
     */
    public final Map<String, Long> counters = new LinkedHashMap<>();
    /** Relative path of the persistent run journal currently being written. */
    public String runTraceFile = "";

    // --- learning ----------------------------------------------------------
    /** Current discrete state supplied to the local policy learner. */
    public String learningContext = "";
    /** Safe strategy variant selected for the active top-level task. */
    public String learningAction = "";
    /** Cumulative reward in the current session. */
    public double learningReward;
    /** Fastest completed rate for the active job context; plain jobs use one completion unit. */
    public double learningBestTicksPerUnit;
    /** Number of persisted policy updates, useful when checking that learning is alive. */
    public int learningUpdates;
    /** Compact session/profile summary for the overlay and run journal. */
    public String learningMemory = "";
    /** Latest automatic mission verdict; retained while the next mission starts or the bot idles. */
    public String automaticVerdict = "";
    public String automaticReason = "";
    public long automaticTicks;
    public long automaticUsualTicks;
    /** Personal best for the top-level task/category. It is telemetry, never a route reward. */
    public long automaticBestTicks;

    // --- decision trace ----------------------------------------------------
    /** Plain-language action currently being attempted, independent of the task name. */
    public String intent = "";
    /** The remembered or discovered thing the bot is considering. */
    public String targetLabel = "";
    public BlockPos targetPos;
    /** E.g. visible, outside view (turn toward it), occluded by stone, or unloaded. */
    public String targetVerdict = "";
    /** Search anchor and candidate count make an apparently idle scan auditable. */
    public BlockPos searchAnchor;
    public int searchCandidates;
    public int searchAttempt;
    public int searchLimit;
    public int searchView;
    public int searchViewCount;
    public int scanTicks;
    public String searchHeading = "";
    /** What the next fallback is, before it happens. */
    public String nextDecision = "";
    /** Remembered blocks/sites and blacklists that affect the current choice. */
    public String memory = "";
    /** Mission-level inventory and phase progress; child tasks may use {@link #memory} freely. */
    public String missionProgress = "";
    /** Mission-level remembered waypoints and blacklists. */
    public String missionMemory = "";
    /** Explicit warning when the mission has not changed for a suspiciously long time. */
    public String missionLoop = "";
    /** A compact statement of the current give-up thresholds. */
    public String giveUp = "";
    /** Block the movement layer is trying to clear, if any. */
    public String obstruction = "";
    public BlockPos obstructionPos;
    /** World-space markers for the three actions a screenshot needs to explain. */
    public BlockPos movementTarget;
    public String movementLabel = "";
    public BlockPos placementTarget;
    public String placementBlock = "";
    public String placementVerdict = "";
    public BlockPos breakTarget;
    public String breakBlock = "";
    public String breakVerdict = "";
    /** Recent non-repeating decisions; unlike thought history this survives status churn. */
    public final Deque<String> decisions = new ArrayDeque<>();
    private String lastDecision = "";
    private int decisionRepeat;
    /**
     * How often opportunistic side-work has taken the route away from the mission, and how it
     * ended. A diversion that fires once is a feature; one that fires forty-four times and fails
     * every time is the run, and nothing else in this class would show that.
     */
    public String diversions = "";
    /**
     * Why child tasks failed. A parent that handles a failed sub-task overwrites its status on the
     * same tick, so the actual reason - "food not consumed", "no stable ground" - never reaches the
     * journal. These survive that.
     */
    public final Deque<String> failures = new ArrayDeque<>();

    // --- last path search ---
    public int nodesExpanded;
    /** Budget this search was actually given; scaled to the distance, not the config maximum. */
    public int nodeBudget;
    public double searchMillis;
    public boolean reachedGoal;
    public int pathLength;
    public String goal = "-";
    public int repaths;

    // --- path following ---
    public int pathIndex;
    public BlockPos currentNode;
    public int noProgressTicks;
    /** Goal-level movement watchdog; unlike noProgressTicks this survives route rebuilds. */
    public int goalNoProgressTicks;
    public double relativeAngle;

    // --- input actually being pressed this tick ---
    public String keys = "";

    /** Clears the per-path fields so a stale search can't be mistaken for a current one. */
    public void clearPath() {
        nodesExpanded = 0;
        nodeBudget = 0;
        searchMillis = 0.0;
        reachedGoal = false;
        pathLength = 0;
        pathIndex = 0;
        currentNode = null;
        noProgressTicks = 0;
        goalNoProgressTicks = 0;
        repaths = 0;
    }

    /** Clears task-specific context when the engine starts a different top-level task. */
    public void clearDecisionTrace() {
        intent = "";
        targetLabel = "";
        targetPos = null;
        targetVerdict = "";
        searchAnchor = null;
        searchCandidates = 0;
        searchAttempt = 0;
        searchLimit = 0;
        searchView = 0;
        searchViewCount = 0;
        scanTicks = 0;
        searchHeading = "";
        nextDecision = "";
        memory = "";
        missionProgress = "";
        missionMemory = "";
        missionLoop = "";
        giveUp = "";
        obstruction = "";
        obstructionPos = null;
        movementTarget = null;
        movementLabel = "";
        placementTarget = null;
        placementBlock = "";
        placementVerdict = "";
        breakTarget = null;
        breakBlock = "";
        breakVerdict = "";
        decisions.clear();
        lastDecision = "";
        decisionRepeat = 0;
        diversions = "";
        failures.clear();
    }

    /** Clears task-level learning labels while retaining the last profile summary. */
    public void clearLearningAction() {
        learningContext = "";
        learningAction = "";
        learningReward = 0.0;
        learningBestTicksPerUnit = 0.0;
    }

    /** Clears one-tick action markers so a completed placement/break is not shown as still active. */
    public void clearActionMarkers() {
        movementTarget = null;
        movementLabel = "";
        placementTarget = null;
        placementBlock = "";
        placementVerdict = "";
        breakTarget = null;
        breakBlock = "";
        breakVerdict = "";
    }

    /** Clears counters that belong to the active top-level run. */
    public void clearRunStatistics() {
        workedTicks = 0L;
        blocksBroken = 0L;
        blocksPlaced = 0L;
        foodEaten = 0L;
        tasksCompleted = 0L;
        tasksFailed = 0L;
        runRepaths = 0L;
        counters.clear();
    }

    /** Raises a named run counter by one. */
    public void count(String key) {
        count(key, 1L);
    }

    /** Raises a named run counter, ignoring the no-op and nonsense cases callers would have to guard. */
    public void count(String key, long amount) {
        if (key == null || key.isBlank() || amount <= 0L) {
            return;
        }
        counters.merge(key, amount, Long::sum);
    }

    /** Records the largest value seen this run for {@code key}, such as the longest task. */
    public void peak(String key, long value) {
        if (key == null || key.isBlank() || value <= 0L) {
            return;
        }
        counters.merge(BotStatistics.PEAK_PREFIX + key, value, Math::max);
    }

    /** Clears the candidate from the previous tick before the active task publishes its own. */
    public void clearTarget() {
        targetLabel = "";
        targetPos = null;
        targetVerdict = "";
    }

    /** Publishes a candidate and the reason it is or is not actionable. */
    public void target(String label, BlockPos pos, String verdict) {
        targetLabel = label == null ? "" : label;
        targetPos = pos == null ? null : pos.immutable();
        targetVerdict = verdict == null ? "" : verdict;
    }

    /** Publishes the current route waypoint for the HUD and optional world marker. */
    public void movement(BlockPos pos, String label) {
        movementTarget = pos == null ? null : pos.immutable();
        movementLabel = label == null ? "" : label;
    }

    /** Publishes an intended placement, including why the click is waiting or impossible. */
    public void placement(BlockPos pos, String block, String verdict) {
        placementTarget = pos == null ? null : pos.immutable();
        placementBlock = block == null ? "" : block;
        placementVerdict = verdict == null ? "" : verdict;
    }

    /** Publishes the actual block the breaker resolved, not just the caller's requested target. */
    public void breaking(BlockPos pos, String block, String verdict) {
        breakTarget = pos == null ? null : pos.immutable();
        breakBlock = block == null ? "" : block;
        breakVerdict = verdict == null ? "" : verdict;
    }

    /** Records the next choice and retains a small, deduplicated decision history. */
    public void decide(String decision) {
        if (decision == null || decision.isBlank()) {
            return;
        }
        nextDecision = decision;
        if (decision.equals(lastDecision)) {
            decisionRepeat++;
            return;
        }
        decisions.addLast(decision);
        if (decisions.size() > 8) {
            decisions.removeFirst();
        }
        lastDecision = decision;
        decisionRepeat = 1;
        lastEvent = decision;
    }

    /** Adds a visible repeat suffix without making the overlay grow one line per tick. */
    public String decisionRepeatSuffix() {
        return decisionRepeat > 1 ? " x" + decisionRepeat : "";
    }

    /** Records why a child task gave up, before the parent's own status replaces it. */
    public void recordFailure(String task, String reason) {
        if (task == null || task.isBlank()) {
            return;
        }
        String entry = task + ": " + (reason == null || reason.isBlank() ? "no reason given" : reason);
        if (entry.equals(failures.peekLast())) {
            return;
        }
        failures.addLast(entry);
        if (failures.size() > 6) {
            failures.removeFirst();
        }
    }

    /** Snapshot for renderers and the journal; callers cannot mutate the live deque. */
    public List<String> failureSnapshot() {
        return List.copyOf(failures);
    }

    /** Snapshot for renderers and tests; callers cannot mutate the live deque. */
    public List<String> decisionSnapshot() {
        return List.copyOf(decisions);
    }
}
