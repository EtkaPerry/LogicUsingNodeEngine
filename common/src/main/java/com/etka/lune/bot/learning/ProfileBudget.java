package com.etka.lune.bot.learning;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Decides what a full learning table forgets first.
 *
 * <p>Every table here is bounded, and the obvious rule - drop whichever state has the least
 * evidence - quietly destroys the profile. The jobs are not sampled evenly and cannot be: walking
 * is re-entered thousands of times in a single run, because every approach, every step towards a
 * dropped item and every recovery goes through it, while bridging a ravine or sleeping through a
 * night happens a handful of times a night. Rank the whole table by visits and every surviving row
 * is a walking route, and the rare jobs - the ones a shipped profile exists to know something
 * about - are the first things thrown away.</p>
 *
 * <p>So the table is treated as a budget shared between jobs. When room is needed, it comes from
 * whichever job is using the most rows, and within that job from its least-supported row. A job
 * that genuinely has more distinct situations still keeps more of them; no job is evicted out of
 * existence by a job that simply runs more often.</p>
 */
final class ProfileBudget {

    private ProfileBudget() {}

    /**
     * The key to remove, or {@code null} for an empty table.
     *
     * @param evidence how much a row is worth keeping; lower goes first
     */
    static <V> String victim(Map<String, V> table, ToDoubleFunction<V> evidence) {
        if (table == null || table.isEmpty()) {
            return null;
        }
        Map<String, Integer> rowsPerJob = new HashMap<>();
        for (String key : table.keySet()) {
            rowsPerJob.merge(job(key), 1, Integer::sum);
        }
        String worst = null;
        int worstRows = Integer.MIN_VALUE;
        double worstEvidence = Double.MAX_VALUE;
        for (Map.Entry<String, V> entry : table.entrySet()) {
            int rows = rowsPerJob.getOrDefault(job(entry.getKey()), 1);
            double support = evidence.applyAsDouble(entry.getValue());
            if (rows > worstRows || (rows == worstRows && support < worstEvidence)) {
                worst = entry.getKey();
                worstRows = rows;
                worstEvidence = support;
            }
        }
        return worst;
    }

    /** The job part of a {@link LearningContext#key()}: mission|<b>task</b>|dimension|phase. */
    static String job(String stateKey) {
        if (stateKey == null) {
            return "unknown";
        }
        int first = stateKey.indexOf('|');
        if (first < 0) {
            return stateKey;
        }
        int second = stateKey.indexOf('|', first + 1);
        return second < 0 ? stateKey.substring(first + 1) : stateKey.substring(first + 1, second);
    }
}
