package com.etka.lune.training;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TrainingProgressTest {
    @Test
    void unlocksOnlyAfterAllEarlierLessonsAreCleared() {
        Set<String> cleared = new HashSet<>();
        var lessons = TrainingCourse.lessons();
        for (int next = 0; next < lessons.size(); next++) {
            for (int i = 0; i < lessons.size(); i++) {
                assertEquals(i <= next, TrainingProgress.isUnlocked(lessons.get(i), cleared));
            }
            cleared.add(lessons.get(next).id());
        }
    }

    @Test
    void oldCompletionBeyondAGapCannotBypassReplacementLessonFive() {
        Set<String> oldSave = Set.of("start", "tool", "guard", "scout", "sink", "fanout");
        assertTrue(TrainingProgress.isUnlocked(TrainingCourse.byId("collect").orElseThrow(), oldSave));
        assertFalse(TrainingProgress.isUnlocked(TrainingCourse.byId("fanout").orElseThrow(), oldSave));
        assertFalse(TrainingProgress.isUnlocked(TrainingCourse.byId("delay").orElseThrow(), oldSave));
    }
}
