package com.etka.lune.bot.learning;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Short-term memory for one bot run. It is intentionally bounded: a session explains what just
 * happened, while the compact policy/profile is what survives into the next run.
 */
public final class LearningSession {

    private static final int MAX_MEMORIES = 32;

    private final String id;
    private final String mission;
    private final String started;
    private final boolean active;
    private final Deque<String> memories = new ArrayDeque<>();
    private double reward;
    private int decisions;
    private int taskOutcomes;
    private int skillOutcomes;
    private int automaticApprovals;
    private int automaticDisapprovals;

    private LearningSession(String id, String mission, boolean active) {
        this.id = id;
        this.mission = mission == null || mission.isBlank() ? "unknown" : mission;
        this.started = Instant.now().toString();
        this.active = active;
    }

    public static LearningSession start(String mission) {
        return new LearningSession(UUID.randomUUID().toString(), mission, true);
    }

    /** An inert context supplied to tasks when the engine is idle. */
    public static LearningSession idle() {
        return new LearningSession("idle", "idle", false);
    }

    public String id() {
        return id;
    }

    public String mission() {
        return mission;
    }

    public String started() {
        return started;
    }

    public boolean active() {
        return active;
    }

    public double reward() {
        return reward;
    }

    public int decisions() {
        return decisions;
    }

    public int taskOutcomes() {
        return taskOutcomes;
    }

    public int automaticApprovals() {
        return automaticApprovals;
    }

    public int automaticDisapprovals() {
        return automaticDisapprovals;
    }

    public int skillOutcomes() {
        return skillOutcomes;
    }

    /** Keeps the automatic mission verdict visible in the bounded session memory. */
    public void automaticOutcome(String task, AutomaticApproval.Decision decision) {
        if (decision == null) {
            return;
        }
        if (decision.approved()) {
            automaticApprovals++;
        } else {
            automaticDisapprovals++;
        }
        remember("auto " + decision.label() + " " + task + " ("
                + decision.elapsedTicks() + "/" + decision.usualTicks() + " ticks): "
                + decision.reason());
    }

    public void decision(LearningContext context, String action) {
        decisions++;
        remember("choose " + context.key() + " -> " + action);
    }

    public void taskOutcome(String task, double outcome) {
        taskOutcomes++;
        reward += outcome;
        remember("outcome " + task + " reward=" + format(outcome));
    }

    /** Adds the normalised result of a concrete learned skill action to this run's reward. */
    public void skillOutcome(String skill, String action, SkillOutcome outcome) {
        skillOutcome(skill, action, outcome, true);
    }

    /** A fixed job contributes timing telemetry but not points for a made-up default action. */
    public void skillOutcome(String skill, String action, SkillOutcome outcome, boolean rewarded) {
        if (outcome == null) {
            return;
        }
        skillOutcomes++;
        if (rewarded) {
            reward += outcome.reward();
        }
        remember((rewarded ? "skill " : "metric ") + skill + " " + action
                + " -> " + outcome.summary());
    }

    /** Records mission completion as telemetry without assigning policy reward. */
    public void missionOutcome(String task, boolean success, long elapsedTicks) {
        taskOutcomes++;
        remember("mission " + task + " " + (success ? "completed" : "failed")
                + " in " + Math.max(0L, elapsedTicks) + " ticks");
    }

    public void reward(double amount, String reason) {
        reward += amount;
        remember("reward " + format(amount) + " " + reason);
    }

    public void remember(String event) {
        if (event == null || event.isBlank()) {
            return;
        }
        memories.addLast(event.replace('\n', ' ').replace('\r', ' '));
        while (memories.size() > MAX_MEMORIES) {
            memories.removeFirst();
        }
    }

    public List<String> memories() {
        return List.copyOf(new ArrayList<>(memories));
    }

    public String summary() {
        return summary(true);
    }

    /** Compact summary, optionally omitting development-only automatic verdict counters. */
    public String summary(boolean includeAutomaticApproval) {
        String automatic = includeAutomaticApproval
                ? ";auto=" + automaticApprovals + "/" + automaticDisapprovals : "";
        return "id=" + id + ";mission=" + mission + ";decisions=" + decisions
                + ";tasks=" + taskOutcomes + ";skills=" + skillOutcomes
                + automatic + ";reward=" + format(reward)
                + ";recent=" + memories();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
