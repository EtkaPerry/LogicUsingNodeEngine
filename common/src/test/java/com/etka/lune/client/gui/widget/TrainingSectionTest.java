package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.widget.LuneMenu.Segment;
import com.etka.lune.training.TrainingCourse;
import com.etka.lune.training.TrainingLesson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingSectionTest {

    private static List<Segment> segmentsFor(Set<String> cleared) {
        return TrainingSection.segments(TrainingCourse.lessons(), lesson -> cleared.contains(lesson.id()));
    }

    private static List<Segment> expected(Segment... head) {
        List<Segment> segments = new ArrayList<>(List.of(head));
        segments.addAll(Collections.nCopies(TrainingCourse.lessons().size() - head.length, Segment.AHEAD));
        return segments;
    }

    @Test
    void oneSegmentPerStepWithTheFirstUnclearedOneCurrent() {
        List<TrainingLesson> lessons = TrainingCourse.lessons();
        assertEquals(expected(Segment.DONE, Segment.DONE, Segment.CURRENT),
                segmentsFor(Set.of(lessons.get(0).id(), lessons.get(1).id())));
    }

    @Test
    void aNewPlayerIsOnTheFirstStep() {
        assertEquals(expected(Segment.CURRENT), segmentsFor(Set.of()));
    }

    /** An old save can hold a clear beyond a step that is open again; the gap is what is next. */
    @Test
    void aClearBeyondAGapStaysDoneAndTheGapIsCurrent() {
        List<TrainingLesson> lessons = TrainingCourse.lessons();
        assertEquals(expected(Segment.DONE, Segment.CURRENT, Segment.DONE),
                segmentsFor(Set.of(lessons.get(0).id(), lessons.get(2).id())));
    }

    @Test
    void aFinishedCourseHasNoCurrentStep() {
        Set<String> all = new HashSet<>();
        TrainingCourse.lessons().forEach(lesson -> all.add(lesson.id()));
        assertEquals(Collections.nCopies(TrainingCourse.lessons().size(), Segment.DONE), segmentsFor(all));
    }

    /** The track opens with the chosen step in view, as near the middle as the ends of the course allow. */
    @Test
    void theTrackOpensWithTheFocusInViewAndCentredWherePossible() {
        assertEquals(0, TrainingSection.windowStart(0, 5, 10));
        assertEquals(0, TrainingSection.windowStart(2, 5, 10));
        assertEquals(3, TrainingSection.windowStart(5, 5, 10));
        assertEquals(5, TrainingSection.windowStart(9, 5, 10));
        assertEquals(6, TrainingSection.windowStart(9, 4, 10));
        for (int focus = 0; focus < 10; focus++) {
            for (int visible = 1; visible <= 10; visible++) {
                int start = TrainingSection.windowStart(focus, visible, 10);
                assertTrue(focus >= start && focus < start + visible,
                        "step " + focus + " out of view with " + visible + " cards from " + start);
            }
        }
    }

    @Test
    void aTrackWiderThanTheCourseStartsAtTheFirstStep() {
        assertEquals(0, TrainingSection.windowStart(9, 12, 10));
    }
}
