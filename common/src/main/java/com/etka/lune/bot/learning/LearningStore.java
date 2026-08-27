package com.etka.lune.bot.learning;

import com.etka.lune.Constants;
import com.etka.lune.platform.BuildFeatures;
import com.etka.lune.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Local, bounded persistence for policy values and explicit developer feedback.
 *
 * <p>This is general user learning in the useful bot sense: it remembers which named strategy
 * performs well and can incorporate explicit developer feedback. Release jars load the bundled
 * profile and never write it. It does not upload data, inspect chat, or store raw screenshots.</p>
 */
public final class LearningStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Version 2 replaces mission/route rewards with normalised skill outcomes. */
    private static final int SCHEMA_VERSION = 2;
    private static final int MAX_SESSION_HISTORY = 12;
    /** Ticks an episode must last before it is a measurement rather than a non-event. */
    private static final int MIN_MEASURABLE_TICKS = 2;
    private static final int MAX_PERFORMANCE_STATES = 512;
    private static LearningStore instance;

    private final Path file;
    private final boolean readOnly;
    private final Data data;
    private final TabularPolicy policy;
    /** Most recent concrete skill tactic, kept outside the persisted data for live feedback. */
    private SkillChoice lastSkillChoice;
    /** Most recent skill with a real alternative; fixed jobs must not hide it from F7/F8. */
    private SkillChoice lastFeedbackSkillChoice;
    /** Active nested episodes, ordered so feedback follows the leaf job currently being ticked. */
    private final List<SkillChoice> activeSkillChoices = new ArrayList<>();

    public LearningStore(Path file) {
        this(file, new Random());
    }

    public LearningStore(Path file, Random random) {
        this(file, random, false);
    }

    private LearningStore(Path file, Random random, boolean readOnly) {
        this.file = file;
        this.readOnly = readOnly;
        this.data = readOnly ? loadBundled() : load(file);
        this.policy = new TabularPolicy(data.qValues, random);
    }

    public static synchronized LearningStore get() {
        if (instance == null) {
            instance = BuildFeatures.releaseBuild()
                    ? new LearningStore(null, new Random(), true)
                    : new LearningStore(defaultFile());
        }
        return instance;
    }

    public static Path defaultFile() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + "-learning.json");
    }

    /**
     * Chooses one safe tactic for a concrete skill episode.
     *
     * <p>This is deliberately separate from top-level mission selection. F7/F8 feedback follows
     * this token, so feedback given while chopping a tree rewards the chopping tactic rather than
     * the routine or speedrun route that happened to request the wood.</p>
     */
    public synchronized SkillChoice chooseSkill(LearningContext context, List<String> actions,
                                                String fallback) {
        LearningContext safeContext = context == null
                ? LearningContext.of("skill", "unknown", "unknown") : context;
        List<String> choices = safeChoices(actions, fallback);
        String chosen = policy.choose(safeContext.key(), choices, fallback);
        SkillChoice choice = new SkillChoice(safeContext, choices, chosen);
        lastSkillChoice = choice;
        if (choices.size() > 1) {
            lastFeedbackSkillChoice = choice;
        }
        activeSkillChoices.remove(choice);
        activeSkillChoices.add(choice);
        if (!readOnly && choices.size() > 1) {
            data.totalDecisions++;
            data.lastContext = safeContext.key();
            data.lastAction = chosen;
        }
        return choice;
    }

    /** The concrete job currently acting, including metric-only jobs with one fixed action. */
    public synchronized SkillChoice lastSkillChoice() {
        return activeSkillChoices.isEmpty()
                ? lastSkillChoice
                : activeSkillChoices.get(activeSkillChoices.size() - 1);
    }

    /**
     * Latest choice feedback can actually change. A fixed/default job still records best times,
     * but F7/F8 must not pretend that voting on its only action creates an alternative.
     */
    public synchronized SkillChoice feedbackSkillChoice() {
        for (int i = activeSkillChoices.size() - 1; i >= 0; i--) {
            SkillChoice choice = activeSkillChoices.get(i);
            if (choice.actions.size() > 1) {
                return choice;
            }
        }
        return lastFeedbackSkillChoice;
    }

    /** Marks a nested episode as the concrete job acting during this game tick. */
    public synchronized void activateSkill(SkillChoice choice) {
        if (choice == null || !choice.active) {
            return;
        }
        activeSkillChoices.remove(choice);
        activeSkillChoices.add(choice);
        lastSkillChoice = choice;
    }

    /** Ends a measurement token without treating an intentional continuous-task stop as failure. */
    public synchronized void abandonSkill(SkillChoice choice) {
        if (choice == null) {
            return;
        }
        choice.active = false;
        activeSkillChoices.remove(choice);
    }

    /** A new run must not accidentally send feedback to a skill used by the previous run. */
    public synchronized void startSkillSession() {
        lastSkillChoice = null;
        lastFeedbackSkillChoice = null;
        activeSkillChoices.clear();
    }

    /**
     * Applies a rejection immediately when the skill episode is still active. The rejected action
     * is excluded, so this always represents a real change rather than another policy roll that
     * may select the same tactic again.
     */
    public synchronized String switchRejectedSkill(SkillChoice choice) {
        if (choice == null || !choice.active || choice.actions.size() <= 1) {
            return null;
        }
        List<String> alternatives = new ArrayList<>(choice.actions);
        alternatives.remove(choice.action);
        if (alternatives.isEmpty()) {
            return null;
        }
        String replacement = policy.choose(choice.context.key(), alternatives, alternatives.get(0));
        choice.action = replacement;
        lastFeedbackSkillChoice = choice;
        if (!readOnly) {
            data.totalDecisions++;
            data.lastContext = choice.context.key();
            data.lastAction = replacement;
            save();
        }
        return replacement;
    }

    /**
     * Rewards a skill by useful work per tick and completion, not by the raw size of its job.
     * Historical speed is stored per skill context, so small, large and giant trees establish
     * independent baselines and a faster later attempt earns a better score.
     */
    public synchronized SkillOutcome recordSkillOutcome(SkillChoice choice, boolean completed,
                                                        int workUnits, int expectedUnits,
                                                        long elapsedTicks) {
        if (choice == null) {
            return SkillOutcome.evaluate(completed, workUnits, expectedUnits, elapsedTicks, 0.0);
        }
        if (elapsedTicks < MIN_MEASURABLE_TICKS) {
            // One tick is not a duration. A job that opened and closed inside a single tick did not
            // do its work quickly - it found nothing to do, or could not start - and the callers
            // that produce these produce them in bulk: one recorded run closed a hundred and
            // ninety-five thousand of them. Kept, they become the profile, and they carry a
            // best-ever "one unit in one tick" that no honest episode can ever beat.
            abandonSkill(choice);
            return SkillOutcome.evaluate(completed, workUnits, expectedUnits, elapsedTicks, 0.0);
        }
        String state = choice.context.key();
        SkillPerformance performance = skillPerformance(state);
        SkillOutcome outcome = SkillOutcome.evaluate(completed, workUnits, expectedUnits,
                elapsedTicks, performance.usualTicksPerUnit(), performance.bestTicksPerUnit);
        choice.active = false;
        activeSkillChoices.remove(choice);
        lastSkillChoice = choice;
        if (choice.actions.size() > 1) {
            lastFeedbackSkillChoice = choice;
        }
        if (readOnly) {
            return outcome;
        }

        // A sole "default" action is a clock, not a decision. Store its best time without
        // manufacturing policy points for a route/job label that had no alternative.
        if (choice.actions.size() > 1) {
            policy.observe(state, choice.action, outcome.reward(), null, List.of(), true);
            data.totalUpdates++;
        }
        data.totalSkillOutcomes++;
        performance.runs++;
        if (completed) {
            performance.completedRuns++;
            // Only an episode that produced something can describe a rate. Charging the ticks of a
            // job that correctly found nothing to do against zero units drove the "usual" speed of
            // that job up without limit, and every later episode was then judged against a
            // baseline made of idle looking-around.
            if (outcome.workUnits() > 0) {
                performance.totalUnits += outcome.workUnits();
                performance.totalTicks += outcome.elapsedTicks();
                double ticksPerUnit = (double) outcome.elapsedTicks() / outcome.workUnits();
                if (performance.bestTicksPerUnit <= 0.0
                        || ticksPerUnit < performance.bestTicksPerUnit) {
                    performance.bestTicksPerUnit = ticksPerUnit;
                }
            }
        }
        data.lastContext = state;
        data.lastAction = choice.action;
        data.lastReward = outcome.reward();
        save();
        return outcome;
    }

    /**
     * Tracks mission timing for diagnostics without feeding mission/route labels into the policy.
     */
    public synchronized AutomaticApproval.Decision recordMissionOutcome(
            LearningContext context, boolean success, long elapsedTicks) {
        if (readOnly) {
            return AutomaticApproval.evaluate(success, elapsedTicks, 0L);
        }
        String state = context == null ? "unknown" : context.key();
        Performance performance = performance(state);
        AutomaticApproval.Decision decision = AutomaticApproval.evaluate(success, elapsedTicks,
                performance.usualTicks());
        performance.runs++;
        if (success) {
            performance.successfulRuns++;
            performance.totalSuccessfulTicks += Math.max(0L, elapsedTicks);
            long safeTicks = Math.max(1L, elapsedTicks);
            if (performance.bestSuccessfulTicks <= 0L
                    || safeTicks < performance.bestSuccessfulTicks) {
                performance.bestSuccessfulTicks = safeTicks;
            }
        }
        data.totalAutomaticOutcomes++;
        if (decision.approved()) {
            data.totalAutomaticApprovals++;
            performance.approved++;
        } else {
            data.totalAutomaticDisapprovals++;
            performance.disapproved++;
        }
        save();
        return decision;
    }

    /** Records explicit F7/F8 feedback as a stronger policy update and a user preference vote. */
    public synchronized void recordUserFeedback(LearningContext context, String action, boolean positive) {
        if (readOnly) {
            return;
        }
        String state = context == null ? "unknown" : context.key();
        int signal = positive ? 1 : -1;
        policy.observe(state, action, signal * 12.0, null, List.of(), true);
        Preference preference = data.preferences.computeIfAbsent(state + "::" + action,
                ignored -> new Preference());
        if (positive) {
            preference.positive++;
        } else {
            preference.negative++;
        }
        preference.lastFeedback = positive ? "positive" : "negative";
        data.totalFeedback++;
        data.lastContext = state;
        data.lastAction = action;
        data.lastReward = signal * 12.0;
        save();
    }

    public synchronized void finishSession(LearningSession session, String reason, boolean success) {
        if (readOnly || session == null || !session.active()) {
            return;
        }
        data.sessions++;
        if (success) {
            data.successfulSessions++;
        } else {
            data.failedSessions++;
        }
        SessionRecord record = new SessionRecord();
        record.id = session.id();
        record.mission = session.mission();
        record.started = session.started();
        record.ended = java.time.Instant.now().toString();
        record.reason = reason == null ? "unknown" : reason;
        record.success = success;
        record.reward = session.reward();
        record.decisions = session.decisions();
        data.recentSessions.add(record);
        while (data.recentSessions.size() > MAX_SESSION_HISTORY) {
            data.recentSessions.remove(0);
        }
        save();
    }

    public synchronized int stateCount() {
        return policy.stateCount();
    }

    /**
     * How this session picked among variants. Recorded in the run journal because a balanced
     * sampling run and a normal greedy run produce very different-looking journals, and a batch
     * read back a week later has no other way to tell which one it is looking at.
     */
    public synchronized String explorationMode() {
        return policy.exploration().name().toLowerCase(java.util.Locale.ROOT);
    }

    public synchronized int updateCount() {
        return policy.updateCount();
    }

    public synchronized long sessionCount() {
        return data.sessions;
    }

    public synchronized double bestSkillTicksPerUnit(LearningContext context) {
        String state = context == null ? "unknown" : context.key();
        SkillPerformance performance = data.skillPerformance.get(state);
        return performance == null ? 0.0 : performance.bestTicksPerUnit();
    }

    public synchronized long bestMissionTicks(LearningContext context) {
        String state = context == null ? "unknown" : context.key();
        Performance performance = data.performance.get(state);
        return performance == null ? 0L : performance.bestTicks();
    }

    public synchronized String lastContext() {
        return data.lastContext;
    }

    public synchronized String lastAction() {
        return data.lastAction;
    }

    public synchronized String summary() {
        return summary(true);
    }

    /** Compact profile summary, optionally omitting development-only approval counters. */
    public synchronized String summary(boolean includeAutomaticApproval) {
        String automatic = includeAutomaticApproval
                ? ";auto=" + data.totalAutomaticApprovals + "/" + data.totalAutomaticDisapprovals
                : "";
        return "sessions=" + data.sessions + " (ok=" + data.successfulSessions
                + ", failed=" + data.failedSessions + ")"
                + ";states=" + stateCount() + ";updates=" + updateCount()
                + ";skills=" + data.totalSkillOutcomes + ";feedback=" + data.totalFeedback
                + automatic;
    }

    /** Test/UI support for deliberately forgetting the learned profile. */
    public synchronized void clear() {
        if (readOnly) {
            return;
        }
        data.qValues.clear();
        data.preferences.clear();
        data.recentSessions.clear();
        data.performance.clear();
        data.skillPerformance.clear();
        data.sessions = 0;
        data.successfulSessions = 0;
        data.failedSessions = 0;
        data.totalDecisions = 0;
        data.totalUpdates = 0;
        data.totalFeedback = 0;
        data.totalAutomaticOutcomes = 0;
        data.totalAutomaticApprovals = 0;
        data.totalAutomaticDisapprovals = 0;
        data.totalSkillOutcomes = 0;
        data.lastContext = "";
        data.lastAction = "";
        data.lastReward = 0.0;
        lastSkillChoice = null;
        lastFeedbackSkillChoice = null;
        activeSkillChoices.clear();
        save();
    }

    public synchronized void save() {
        if (readOnly) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not write Lune learning profile {}", file, e);
        }
    }

    private static Data load(Path file) {
        if (file == null || !Files.exists(file)) {
            return new Data();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            return normalize(GSON.fromJson(reader, Data.class), file.toString());
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read Lune learning profile {}, starting fresh", file, e);
        }
        return new Data();
    }

    private static Data loadBundled() {
        try (InputStream stream = LearningStore.class.getResourceAsStream("/lune-learning.json")) {
            if (stream == null) {
                Constants.LOG.warn("Bundled Lune learning profile is missing; starting with an empty profile");
                return new Data();
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return normalize(GSON.fromJson(reader, Data.class), "bundled lune-learning.json");
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read bundled Lune learning profile, starting fresh", e);
            return new Data();
        }
    }

    private static Data normalize(Data loaded, String source) {
        if (loaded == null) {
            return new Data();
        }
        if (loaded.schemaVersion != SCHEMA_VERSION) {
            Constants.LOG.warn("Ignoring unsupported Lune learning profile schema {} from {}",
                    loaded.schemaVersion, source);
            return new Data();
        }
        if (loaded.qValues == null) loaded.qValues = new LinkedHashMap<>();
        if (loaded.preferences == null) loaded.preferences = new LinkedHashMap<>();
        if (loaded.recentSessions == null) loaded.recentSessions = new ArrayList<>();
        if (loaded.performance == null) loaded.performance = new LinkedHashMap<>();
        if (loaded.skillPerformance == null) loaded.skillPerformance = new LinkedHashMap<>();
        return loaded;
    }

    private Performance performance(String state) {
        Performance existing = data.performance.get(state);
        if (existing != null) {
            return existing;
        }
        while (data.performance.size() >= MAX_PERFORMANCE_STATES) {
            String crowded = ProfileBudget.victim(data.performance, entry -> entry.runs);
            if (crowded == null) {
                break;
            }
            data.performance.remove(crowded);
        }
        Performance created = new Performance();
        data.performance.put(state, created);
        return created;
    }

    private SkillPerformance skillPerformance(String state) {
        SkillPerformance existing = data.skillPerformance.get(state);
        if (existing != null) {
            return existing;
        }
        while (data.skillPerformance.size() >= MAX_PERFORMANCE_STATES) {
            String crowded = ProfileBudget.victim(data.skillPerformance, entry -> entry.runs);
            if (crowded == null) {
                break;
            }
            data.skillPerformance.remove(crowded);
        }
        SkillPerformance created = new SkillPerformance();
        data.skillPerformance.put(state, created);
        return created;
    }

    private static List<String> safeChoices(List<String> actions, String fallback) {
        List<String> result = new ArrayList<>();
        if (actions != null) {
            for (String action : actions) {
                if (action != null && !action.isBlank() && !result.contains(action)) {
                    result.add(action);
                }
                if (result.size() >= TabularPolicy.MAX_ACTIONS) {
                    break;
                }
            }
        }
        String safeFallback = fallback == null || fallback.isBlank() ? "default" : fallback;
        if (result.isEmpty()) {
            result.add(safeFallback);
        }
        return List.copyOf(result);
    }

    /** Gson data object; fields are intentionally simple and versioned. */
    public static final class Data {
        public int schemaVersion = SCHEMA_VERSION;
        public long sessions;
        public long successfulSessions;
        public long failedSessions;
        public long totalDecisions;
        public long totalUpdates;
        public long totalFeedback;
        public long totalAutomaticOutcomes;
        public long totalAutomaticApprovals;
        public long totalAutomaticDisapprovals;
        public long totalSkillOutcomes;
        public double lastReward;
        public String lastContext = "";
        public String lastAction = "";
        public Map<String, Map<String, TabularPolicy.Cell>> qValues = new LinkedHashMap<>();
        public Map<String, Preference> preferences = new LinkedHashMap<>();
        public List<SessionRecord> recentSessions = new ArrayList<>();
        /** Successful mission durations keyed by LearningContext.key(). */
        public Map<String, Performance> performance = new LinkedHashMap<>();
        /** Normalised work-rate baselines keyed by skill LearningContext.key(). */
        public Map<String, SkillPerformance> skillPerformance = new LinkedHashMap<>();
    }

    public static final class Performance {
        public long runs;
        public long successfulRuns;
        public long totalSuccessfulTicks;
        public long approved;
        public long disapproved;
        public long bestSuccessfulTicks;

        public long usualTicks() {
            if (successfulRuns <= 0 || totalSuccessfulTicks <= 0) {
                return 0L;
            }
            return Math.max(1L, Math.round((double) totalSuccessfulTicks / successfulRuns));
        }

        public long bestTicks() {
            return Math.max(0L, bestSuccessfulTicks);
        }
    }

    public static final class Preference {
        public int positive;
        public int negative;
        public String lastFeedback = "";
    }

    public static final class SkillPerformance {
        public long runs;
        public long completedRuns;
        public long totalUnits;
        public long totalTicks;
        public double bestTicksPerUnit;

        public double usualTicksPerUnit() {
            if (totalUnits <= 0 || totalTicks <= 0) {
                return 0.0;
            }
            return (double) totalTicks / totalUnits;
        }

        public double bestTicksPerUnit() {
            return Math.max(0.0, bestTicksPerUnit);
        }
    }

    /** Runtime token shared with the active skill so rejected tactics can change immediately. */
    public static final class SkillChoice {
        private final LearningContext context;
        private final List<String> actions;
        private volatile String action;
        private volatile boolean active = true;

        private SkillChoice(LearningContext context, List<String> actions, String action) {
            this.context = context;
            this.actions = actions;
            this.action = action;
        }

        public LearningContext context() {
            return context;
        }

        public List<String> actions() {
            return actions;
        }

        public String action() {
            return action;
        }

        public boolean active() {
            return active;
        }
    }

    public static final class SessionRecord {
        public String id = "";
        public String mission = "";
        public String started = "";
        public String ended = "";
        public String reason = "";
        public boolean success;
        public double reward;
        public int decisions;
    }
}
