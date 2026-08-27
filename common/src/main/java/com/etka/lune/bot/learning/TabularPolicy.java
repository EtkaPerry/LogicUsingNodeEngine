package com.etka.lune.bot.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A bounded contextual bandit/Q-learning core for safe, named strategy variants.
 *
 * <p>It only learns among actions supplied by a task. It never invents a block target, disables
 * vision, changes safety limits, or performs an action by itself. The first encounter uses the
 * caller's safe default; exploration starts only after there is evidence for that state.</p>
 */
public final class TabularPolicy {

    public static final int MAX_STATES = 512;
    public static final int MAX_ACTIONS = 8;
    private static final double ALPHA = 0.35;
    private static final double GAMMA = 0.80;
    private static final double EPSILON = 0.15;
    private static final double MIN_VALUE = -100.0;
    private static final double MAX_VALUE = 100.0;

    private final Map<String, Map<String, Cell>> table;
    private final Random random;
    private final Exploration exploration;

    /**
     * How a run picks among a task's safe variants.
     *
     * <p>A player's bot runs {@link #OFF}: the safe default until there is evidence, then the best
     * known tactic with a little exploration. That rule is right for playing and wrong for building
     * the shipped profile - a job that gets one episode per run never leaves its default, so the
     * bundled table would ship an opinion about one action per state and no comparison at all.
     * {@link #BALANCED} is the data-collection mode used by the unattended harness.</p>
     */
    public enum Exploration {
        /** Ship behaviour: safe default first, then greedy with epsilon exploration. */
        OFF,
        /** Harness behaviour: always take the least-sampled variant, so every action gets tried. */
        BALANCED;

        /** Reads {@code -Dlune.learning.explore=balanced}; anything else keeps ship behaviour. */
        public static Exploration configured() {
            String raw = System.getProperty("lune.learning.explore", "").trim();
            return "balanced".equalsIgnoreCase(raw) || "true".equalsIgnoreCase(raw)
                    ? BALANCED : OFF;
        }
    }

    public TabularPolicy() {
        this(new LinkedHashMap<>(), new Random());
    }

    public TabularPolicy(Map<String, Map<String, Cell>> table, Random random) {
        this(table, random, Exploration.configured());
    }

    public TabularPolicy(Map<String, Map<String, Cell>> table, Random random,
                         Exploration exploration) {
        this.table = table == null ? new LinkedHashMap<>() : table;
        this.random = random == null ? new Random() : random;
        this.exploration = exploration == null ? Exploration.OFF : exploration;
    }

    public Exploration exploration() {
        return exploration;
    }

    /** Picks a safe supplied action. No-data states always use the caller's default. */
    public synchronized String choose(String state, List<String> actions, String fallback) {
        List<String> choices = choices(actions, fallback);
        if (choices.isEmpty()) {
            return fallback == null || fallback.isBlank() ? "default" : fallback;
        }
        if (exploration == Exploration.BALANCED) {
            return leastSampled(state, choices);
        }

        Map<String, Cell> values = table.get(state);
        boolean hasEvidence = values != null && values.values().stream().anyMatch(cell -> cell != null && cell.visits > 0);
        if (!hasEvidence) {
            return choices.contains(fallback) ? fallback : choices.get(0);
        }

        if (random.nextDouble() < EPSILON) {
            return choices.get(random.nextInt(choices.size()));
        }

        String best = choices.get(0);
        double bestValue = value(state, best);
        for (int i = 1; i < choices.size(); i++) {
            String candidate = choices.get(i);
            double candidateValue = value(state, candidate);
            if (candidateValue > bestValue) {
                best = candidate;
                bestValue = candidateValue;
            }
        }
        return best;
    }

    /** Applies a clipped temporal-difference update at a task/decision boundary. */
    public synchronized void observe(String state, String action, double reward,
                                     String nextState, List<String> nextActions, boolean terminal) {
        if (state == null || state.isBlank() || action == null || action.isBlank()) {
            return;
        }
        Map<String, Cell> values = stateValues(state, true);
        Cell cell = values.computeIfAbsent(action, ignored -> new Cell());
        double next = terminal ? 0.0 : maxValue(nextState, nextActions);
        double target = clamp(reward + GAMMA * next, MIN_VALUE, MAX_VALUE);
        cell.value = clamp(cell.value + ALPHA * (target - cell.value), MIN_VALUE, MAX_VALUE);
        cell.visits++;
        trimStates();
    }

    public synchronized double value(String state, String action) {
        Map<String, Cell> values = table.get(state);
        Cell cell = values == null ? null : values.get(action);
        return cell == null ? 0.0 : cell.value;
    }

    public synchronized int visits(String state, String action) {
        Map<String, Cell> values = table.get(state);
        Cell cell = values == null ? null : values.get(action);
        return cell == null ? 0 : cell.visits;
    }

    public synchronized int stateCount() {
        return table.size();
    }

    public synchronized int updateCount() {
        int total = 0;
        for (Map<String, Cell> values : table.values()) {
            for (Cell cell : values.values()) {
                if (cell != null) {
                    total += cell.visits;
                }
            }
        }
        return total;
    }

    public synchronized Map<String, Map<String, Cell>> table() {
        return table;
    }

    /**
     * The variant this state has the least evidence for, ties broken at random.
     *
     * <p>Round-robin rather than uniform random: a run only affords a handful of episodes per job,
     * and uniform sampling regularly leaves one of three tactics with nothing at all after a whole
     * night. Sampling the thinnest cell each time spends those episodes where they buy the most.</p>
     */
    private String leastSampled(String state, List<String> choices) {
        List<String> thinnest = new ArrayList<>();
        int fewest = Integer.MAX_VALUE;
        for (String candidate : choices) {
            int visits = visits(state, candidate);
            if (visits < fewest) {
                fewest = visits;
                thinnest.clear();
                thinnest.add(candidate);
            } else if (visits == fewest) {
                thinnest.add(candidate);
            }
        }
        return thinnest.get(random.nextInt(thinnest.size()));
    }

    private double maxValue(String state, List<String> actions) {
        if (state == null || actions == null || actions.isEmpty()) {
            return 0.0;
        }
        double best = 0.0;
        for (String action : actions) {
            best = Math.max(best, value(state, action));
        }
        return best;
    }

    private Map<String, Cell> stateValues(String state, boolean create) {
        Map<String, Cell> values = table.get(state);
        if (values != null || !create) {
            return values;
        }
        if (table.size() >= MAX_STATES) {
            trimStates();
        }
        if (table.size() >= MAX_STATES) {
            return new LinkedHashMap<>();
        }
        values = new LinkedHashMap<>();
        table.put(state, values);
        return values;
    }

    private void trimStates() {
        while (table.size() > MAX_STATES) {
            String leastUseful = ProfileBudget.victim(table, values -> values.values().stream()
                    .filter(cell -> cell != null)
                    .mapToInt(cell -> cell.visits)
                    .sum());
            if (leastUseful == null) {
                break;
            }
            table.remove(leastUseful);
        }
    }

    private static List<String> choices(List<String> actions, String fallback) {
        List<String> result = new ArrayList<>();
        if (actions != null) {
            for (String action : actions) {
                if (action != null && !action.isBlank() && !result.contains(action)) {
                    result.add(action);
                }
                if (result.size() >= MAX_ACTIONS) {
                    break;
                }
            }
        }
        if (result.isEmpty() && fallback != null && !fallback.isBlank()) {
            result.add(fallback);
        }
        return result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Gson-friendly persisted cell. */
    public static final class Cell {
        public double value;
        public int visits;
    }
}
