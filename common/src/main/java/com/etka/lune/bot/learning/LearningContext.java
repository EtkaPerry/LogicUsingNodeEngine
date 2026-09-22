package com.etka.lune.bot.learning;

import com.etka.lune.bot.Task;

/**
 * Small, deliberately discrete state used by the local policy learner.
 *
 * <p>Keeping the state categorical is important here. The bot learns which safe strategy worked
 * in a situation; it does not attempt to memorise the whole world or infer hidden blocks.</p>
 *
 * <p><b>Every part of a key is an identifier, never a rendered sentence.</b> A key built from
 * {@link Task#name()} is a key in whatever language the client happened to be running, so the same
 * bot doing the same job writes one set of rows in English and another in Turkish, and a row
 * written under one wording is unreachable after the wording changes. That is not hypothetical:
 * twenty-eight rows shipped in {@code lune-learning.json} keyed {@code Routine: Cov ...}, and
 * nothing could reach them once the English line became {@code Task: %s}. Use
 * {@link #mission(Task, String)} and let it ask for the right half.</p>
 */
public record LearningContext(String mission, String task, String dimension, String phase) {

    public LearningContext {
        mission = clean(mission);
        task = clean(task);
        dimension = clean(dimension);
        phase = clean(phase);
    }

    /**
     * The mission context for a running task.
     *
     * <p>Takes the task rather than its name on purpose: the caller cannot then reach for
     * {@link Task#name()}, which is the mistake this exists to prevent.</p>
     */
    public static LearningContext mission(Task task, String dimension) {
        String id = task == null ? null : task.learningId();
        return new LearningContext(id, id, dimension, "default");
    }

    /** For the two fixed contexts that name no task at all - idle, and the skill rows. */
    public static LearningContext of(String mission, String task, String dimension) {
        return new LearningContext(mission, task, dimension, "default");
    }

    /** Stable, human-readable key used in the persisted policy table. */
    public String key() {
        return mission + "|" + task + "|" + dimension + "|" + phase;
    }

    /**
     * The context a persisted key was written from, or null for a key that is not one of ours.
     *
     * <p>Safe to read back because {@link #clean} never lets a separator into a part, so a key
     * has exactly four of them however strange the task name was.</p>
     */
    public static LearningContext parse(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split("\\|", -1);
        if (parts.length != 4) {
            return null;
        }
        return new LearningContext(parts[0], parts[1], parts[2], parts[3]);
    }

    static String clean(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }
}
