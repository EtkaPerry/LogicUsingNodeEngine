package com.etka.lune.bot.learning;

/**
 * Small, deliberately discrete state used by the local policy learner.
 *
 * <p>Keeping the state categorical is important here. The bot learns which safe strategy worked
 * in a situation; it does not attempt to memorise the whole world or infer hidden blocks.</p>
 */
public record LearningContext(String mission, String task, String dimension, String phase) {

    public LearningContext {
        mission = clean(mission);
        task = clean(task);
        dimension = clean(dimension);
        phase = clean(phase);
    }

    public static LearningContext of(String mission, String task, String dimension) {
        return new LearningContext(mission, task, dimension, "default");
    }

    /** Stable, human-readable key used in the persisted policy table. */
    public String key() {
        return mission + "|" + task + "|" + dimension + "|" + phase;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }
}
