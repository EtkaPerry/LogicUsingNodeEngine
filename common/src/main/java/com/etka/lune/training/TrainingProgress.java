package com.etka.lune.training;

import com.etka.lune.config.BotConfig;

import java.util.LinkedHashSet;

/**
 * Which rungs the player has cleared, stored in {@code config/lune.json} beside the rest of the
 * editor's state.
 *
 * <p>Lessons unlock in order. Old saves may contain gaps, so a later completion never bypasses
 * an unfinished prerequisite.</p>
 */
public final class TrainingProgress {

    private TrainingProgress() {}

    public static boolean isComplete(TrainingLesson lesson) {
        return lesson != null && completed().contains(lesson.id());
    }

    public static boolean isUnlocked(TrainingLesson lesson) {
        return isUnlocked(lesson, completed());
    }

    static boolean isUnlocked(TrainingLesson lesson, java.util.Set<String> cleared) {
        for (TrainingLesson step : TrainingCourse.lessons()) {
            if (step.equals(lesson)) {
                return true;
            }
            if (!cleared.contains(step.id())) {
                return false;
            }
        }
        return false;
    }

    /** Records a clear and writes it out; a lesson solved and then forgotten is worse than no map. */
    public static void markComplete(TrainingLesson lesson) {
        if (lesson == null || !isUnlocked(lesson) || isComplete(lesson)) {
            return;
        }
        completed().add(lesson.id());
        BotConfig.get().save();
    }

    public static int completedCount() {
        return (int) TrainingCourse.lessons().stream().filter(TrainingProgress::isComplete).count();
    }

    /** The first lesson not yet cleared, or null once the course is finished. */
    public static TrainingLesson next() {
        return TrainingCourse.lessons().stream()
                .filter(lesson -> !isComplete(lesson))
                .findFirst()
                .orElse(null);
    }

    public static void reset() {
        completed().clear();
        BotConfig.get().save();
    }

    /** Tolerates a config written before this field existed, or hand-edited to null. */
    private static java.util.Set<String> completed() {
        BotConfig config = BotConfig.get();
        if (config.trainingCompleted == null) {
            config.trainingCompleted = new LinkedHashSet<>();
        }
        return config.trainingCompleted;
    }
}
