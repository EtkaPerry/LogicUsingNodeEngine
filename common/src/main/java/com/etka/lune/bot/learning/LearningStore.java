package com.etka.lune.bot.learning;

import com.etka.lune.Constants;
import com.etka.lune.platform.BuildFeatures;
import com.etka.lune.platform.Services;
import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.BinaryOperator;

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
    /** Longest the profile may sit unwritten while a run keeps updating it. */
    private static final long SAVE_INTERVAL_NANOS = 10_000_000_000L;
    private boolean dirty;
    private long lastSaveNanos;
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
        // A profile read under old keys is written back under the new ones at the next flush.
        this.dirty = !readOnly && data.renamedOnLoad;
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
     * the task or speedrun route that happened to request the wood.</p>
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
        if (elapsedTicks < MIN_MEASURABLE_TICKS) {
            // One tick is not a duration. A job that opened and closed inside a single tick did not
            // do its work quickly - it found nothing to do, or could not start - and the callers
            // that produce these produce them in bulk: one recorded run closed a hundred and
            // ninety-five thousand of them. Kept, they become the profile, and they carry a
            // best-ever "one unit in one tick" that no honest episode can ever beat.
            //
            // Dropping it here is only half the job: the outcome handed back is what the run's
            // reward and the debug HUD are built from, so scoring it normally on the way out paid
            // the caller full completion for the episode this branch just refused to believe.
            abandonSkill(choice);
            return SkillOutcome.unmeasured(workUnits, expectedUnits, elapsedTicks);
        }
        if (choice == null) {
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
                performance.lastUnits = outcome.workUnits();
                performance.lastTicks = outcome.elapsedTicks();
                performance.lastOutcome = data.totalSkillOutcomes;
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

    /**
     * Everything measured for the rows a scope covers, for the card that owns that scope.
     *
     * <p>Read-only, and cheap in the sense that the table is bounded at
     * {@link #MAX_PERFORMANCE_STATES} rows - but it is still a scan of all of them, so a panel
     * drawing sixty times a second keeps the answer for a moment rather than asking every frame.</p>
     */
    public synchronized SkillStats skillStats(LearningScope scope) {
        if (scope == null) {
            return SkillStats.EMPTY;
        }
        List<SkillStats.Row> rows = new ArrayList<>();
        for (Map.Entry<String, SkillPerformance> entry : data.skillPerformance.entrySet()) {
            LearningContext context = LearningContext.parse(entry.getKey());
            SkillPerformance performance = entry.getValue();
            if (performance == null || !scope.matches(context)) {
                continue;
            }
            rows.add(new SkillStats.Row(context, performance.runs, performance.completedRuns,
                    performance.totalUnits, performance.totalTicks,
                    performance.bestTicksPerUnit(), performance.lastUnits,
                    performance.lastTicks, performance.lastOutcome,
                    leadingTactic(entry.getKey())));
        }
        return SkillStats.of(rows);
    }

    /** The tactic the policy rates highest in a state, or "" where the job never had a choice. */
    private String leadingTactic(String state) {
        Map<String, TabularPolicy.Cell> cells = data.qValues.get(state);
        if (cells == null || cells.size() < 2) {
            return "";
        }
        String leading = "";
        double best = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, TabularPolicy.Cell> entry : cells.entrySet()) {
            TabularPolicy.Cell cell = entry.getValue();
            if (cell != null && cell.visits > 0 && cell.value > best) {
                best = cell.value;
                leading = entry.getKey();
            }
        }
        return leading;
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

    /** Localized dashboard summary; summary(boolean) remains machine-readable diagnostics. */
    public synchronized String displaySummary() {
        return com.etka.lune.util.Lang.get("lune.gui.main.learning_summary", data.sessions,
                data.successfulSessions, data.failedSessions, stateCount(), updateCount(),
                data.totalSkillOutcomes, data.totalFeedback);
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

    /**
     * Records that the profile changed, and writes it out at most once every
     * {@link #SAVE_INTERVAL_NANOS}.
     *
     * <p>Every method that updates the profile used to write the whole thing to disk immediately -
     * serialise the lot with Gson, create the file, write it, close it - on the client thread. A
     * task whose step finishes in a single tick closes a learning episode every tick, so a profile
     * that had grown to seventy-odd kilobytes was being written twenty times a second. The game
     * advanced one frame at a time and chunks stopped arriving, because the client thread was
     * sitting in file I/O rather than running the game.</p>
     *
     * <p>Coalescing is safe here in a way it would not be for player data: this file is a cache of
     * measured averages. The worst a lost window costs is a few timings, which the next run
     * measures again. {@link #flush()} still forces a write at the points where the value of
     * keeping it is highest - a task ending, a session closing, leaving the world.</p>
     */
    public synchronized void save() {
        if (readOnly) {
            return;
        }
        dirty = true;
        if (System.nanoTime() - lastSaveNanos >= SAVE_INTERVAL_NANOS) {
            flush();
        }
    }

    /** Writes the profile now if anything is pending. Safe to call when nothing has changed. */
    public synchronized void flush() {
        if (readOnly || !dirty) {
            return;
        }
        dirty = false;
        lastSaveNanos = System.nanoTime();
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
        renameWordedUnits(loaded);
        return loaded;
    }

    /** Where the language file names every unit a job can be counted in. */
    private static final String UNIT_KEYS = "lune.unit.";
    private static final String UNIT_ENTRY = "unit=";
    private static final String PREFERENCE_SEPARATOR = "::";

    /**
     * Rows keyed while the unit was a word rather than an identifier.
     *
     * <p>Until a row's unit became {@link LearningScope#unitId(String)}, the phase carried the
     * progress bar's unit as rendered: {@code unit=blocks} in English, {@code unit=blok} in
     * Turkish. The same job then had a row per language, and none of them could be reached once
     * the wording moved. The identifiers are the English words, so a row written in English is
     * already right. One written under another bundled language's word is renamed here, and where
     * the identifier's row already exists the two are folded together by the rules
     * {@code the run harness} merges snapshots with: counts add up, the best pace is
     * the lower one, and "last time" is the newer of the two. A word Lune has no unit for - a
     * hunt once named its drop - is left as it is rather than guessed at.</p>
     */
    private static void renameWordedUnits(Data loaded) {
        Map<String, String> words = unitWords();
        if (words.isEmpty()) {
            return;
        }
        Map<String, SkillPerformance> skills =
                renamed(loaded.skillPerformance, words, LearningStore::mergeSkill);
        Map<String, Map<String, TabularPolicy.Cell>> values =
                renamed(loaded.qValues, words, LearningStore::mergeCells);
        Map<String, Preference> preferences = new LinkedHashMap<>();
        for (Map.Entry<String, Preference> entry : loaded.preferences.entrySet()) {
            String key = entry.getKey();
            int separator = key == null ? -1 : key.lastIndexOf(PREFERENCE_SEPARATOR);
            if (separator > 0) {
                key = renamedKey(key.substring(0, separator), words) + key.substring(separator);
            }
            put(preferences, key, entry.getValue(), LearningStore::mergePreference);
        }
        String lastContext = renamedKey(loaded.lastContext, words);
        loaded.renamedOnLoad = !skills.keySet().equals(loaded.skillPerformance.keySet())
                || !values.keySet().equals(loaded.qValues.keySet())
                || !preferences.keySet().equals(loaded.preferences.keySet())
                || !Objects.equals(lastContext, loaded.lastContext);
        loaded.skillPerformance = skills;
        loaded.qValues = values;
        loaded.preferences = preferences;
        loaded.lastContext = lastContext;
    }

    /**
     * Every word a bundled language has for a unit of work, mapped to that unit's identifier.
     * English first, so an identifier maps to itself before any translation can claim it.
     */
    private static Map<String, String> unitWords() {
        Map<String, String> english = LuneLanguages.load(LuneLanguages.ENGLISH);
        List<String> keys = english.keySet().stream()
                .filter(key -> key.startsWith(UNIT_KEYS)).sorted().toList();
        Set<String> codes = new LinkedHashSet<>();
        codes.add(LuneLanguages.ENGLISH);
        codes.add(Lang.selected());
        codes.addAll(LuneLanguages.available());
        codes.remove(LuneLanguages.GAME_DEFAULT);
        Map<String, String> words = new LinkedHashMap<>();
        for (String code : codes) {
            Map<String, String> lines = LuneLanguages.ENGLISH.equals(code)
                    ? english : LuneLanguages.load(code);
            for (String key : keys) {
                String word = lines.get(key);
                if (word != null && !word.isBlank()) {
                    words.putIfAbsent(word.trim(), LearningScope.unitId(key));
                }
            }
        }
        return words;
    }

    /** {@code key} with a worded unit replaced by its identifier, or {@code key} as it was. */
    static String renamedKey(String key, Map<String, String> words) {
        LearningContext context = LearningContext.parse(key);
        if (context == null || !context.phase().contains(UNIT_ENTRY)) {
            return key;
        }
        String[] entries = context.phase().split(";", -1);
        boolean changed = false;
        for (int i = 0; i < entries.length; i++) {
            if (!entries[i].startsWith(UNIT_ENTRY)) {
                continue;
            }
            String id = words.get(entries[i].substring(UNIT_ENTRY.length()));
            if (id != null && !entries[i].equals(UNIT_ENTRY + id)) {
                entries[i] = UNIT_ENTRY + id;
                changed = true;
            }
        }
        return changed
                ? new LearningContext(context.mission(), context.task(), context.dimension(),
                        String.join(";", entries)).key()
                : key;
    }

    private static <V> Map<String, V> renamed(Map<String, V> rows, Map<String, String> words,
                                              BinaryOperator<V> merge) {
        Map<String, V> result = new LinkedHashMap<>();
        for (Map.Entry<String, V> entry : rows.entrySet()) {
            put(result, renamedKey(entry.getKey(), words), entry.getValue(), merge);
        }
        return result;
    }

    private static <V> void put(Map<String, V> into, String key, V value, BinaryOperator<V> merge) {
        V existing = into.get(key);
        into.put(key, existing == null ? value : value == null ? existing : merge.apply(existing, value));
    }

    private static SkillPerformance mergeSkill(SkillPerformance into, SkillPerformance from) {
        into.runs += from.runs;
        into.completedRuns += from.completedRuns;
        into.totalUnits += from.totalUnits;
        into.totalTicks += from.totalTicks;
        // Zero means "never measured", not "instant"; only a real pace can become the best one.
        if (from.bestTicksPerUnit > 0.0
                && (into.bestTicksPerUnit <= 0.0 || from.bestTicksPerUnit < into.bestTicksPerUnit)) {
            into.bestTicksPerUnit = from.bestTicksPerUnit;
        }
        if (from.lastTicks > 0 && (into.lastTicks <= 0 || from.lastOutcome > into.lastOutcome)) {
            into.lastUnits = from.lastUnits;
            into.lastTicks = from.lastTicks;
            into.lastOutcome = from.lastOutcome;
        }
        return into;
    }

    private static Map<String, TabularPolicy.Cell> mergeCells(Map<String, TabularPolicy.Cell> into,
                                                              Map<String, TabularPolicy.Cell> from) {
        for (Map.Entry<String, TabularPolicy.Cell> entry : from.entrySet()) {
            TabularPolicy.Cell added = entry.getValue();
            if (added == null) {
                continue;
            }
            TabularPolicy.Cell cell = into.computeIfAbsent(entry.getKey(),
                    ignored -> new TabularPolicy.Cell());
            int visits = cell.visits + added.visits;
            // A cell with no visits carries no evidence, so it must not drag the mean toward zero.
            if (visits > 0) {
                cell.value = (cell.value * cell.visits + added.value * added.visits) / visits;
                cell.visits = visits;
            }
        }
        return into;
    }

    private static Preference mergePreference(Preference into, Preference from) {
        into.positive += from.positive;
        into.negative += from.negative;
        if (from.lastFeedback != null && !from.lastFeedback.isEmpty()) {
            into.lastFeedback = from.lastFeedback;
        }
        return into;
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
        /** Set while reading when rows were renamed, so the file is written back under the new keys. */
        public transient boolean renamedOnLoad;
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
        /** The most recent finished episode that produced something, so a card can say "last time". */
        public long lastUnits;
        public long lastTicks;
        /**
         * Which of the profile's outcomes wrote the two above: the running count of skill
         * outcomes at the time. Rows are compared on it to find the newest across a whole job. A
         * clock would do the same, except that a count is exact, survives a wrong system clock
         * and can be asserted on. Zero on every row written before this was kept.
         */
        public long lastOutcome;

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
