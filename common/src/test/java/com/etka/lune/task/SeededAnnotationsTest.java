package com.etka.lune.task;

import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The notes and frames the starter jobs explain themselves with.
 *
 * <p>They are the part of a job a player reads, so the checks here are the ones a reader would
 * notice: a note in the wrong language, a note whose last lines are cut off by its own border, a
 * note sitting on top of a card, two frames drawn over each other.</p>
 */
class SeededAnnotationsTest {

    /** The canvas draws note text this far in from the left edge, and wraps it this much short. */
    private static final int TEXT_ROOM = 12;
    /** Lines start this far down and stop this far from the bottom edge, nine pixels apart. */
    private static final int TEXT_TOP = 4;
    private static final int TEXT_BOTTOM = 2;
    private static final int LINE_HEIGHT = 9;
    /**
     * A generous glyph width: most letters are six pixels with their gap, spaces four, and the
     * narrow ones less. Measuring every character as six overstates a line, which is the safe way
     * round - a note that fits here fits on screen.
     */
    private static final int GLYPH = 6;

    @AfterEach
    void restoreLanguage() {
        Lang.select(LuneLanguages.GAME_DEFAULT);
    }

    @Test
    void everyNoteAndFrameIsTranslatedAndItsEnglishIsTheEnglishLine() {
        for (TaskGraph task : DefaultTasks.create()) {
            assertFalse(task.notes.isEmpty(), task.name + " explains nothing");
            assertFalse(task.groups.isEmpty(), task.name + " has no frames");
            for (TaskNote note : task.notes) {
                assertEquals(task.seededId + "." + note.id, note.seededId,
                        task.name + ": note " + note.id + " is not keyed by its job");
                Lang.select("en_us");
                assertEquals(note.text, note.displayText(),
                        task.name + ": note " + note.id + " stores English that is not its English "
                                + "line - fix one of them");
                Lang.select("tr_tr");
                assertNotEquals(note.text, note.displayText(),
                        task.name + ": note " + note.id + " has no Turkish");
            }
            for (TaskGroup group : task.groups) {
                assertNotNull(group.seededId, task.name + ": frame " + group.title + " has no id");
                Lang.select("en_us");
                assertEquals(group.title, group.displayTitle(), task.name + ": frame " + group.id);
                Lang.select("tr_tr");
                assertNotEquals(group.title, group.displayTitle(),
                        task.name + ": frame " + group.title + " has no Turkish");
            }
        }
    }

    /**
     * The canvas stops drawing a note's lines at its bottom edge, silently. A Turkish line runs
     * a third longer than the English one, so both are measured.
     */
    @Test
    void everyNoteFitsInsideItsOwnBorderInEveryShippedLanguage() {
        List<String> overflowing = new ArrayList<>();
        for (String code : List.of("en_us", "tr_tr")) {
            Lang.select(code);
            for (TaskGraph task : DefaultTasks.create()) {
                for (TaskNote note : task.notes) {
                    int perLine = (note.width - TEXT_ROOM) / GLYPH;
                    int lines = (note.height - TEXT_TOP - TEXT_BOTTOM) / LINE_HEIGHT;
                    int needed = wrappedLines(note.displayText(), perLine);
                    if (needed > lines) {
                        overflowing.add(code + " " + task.name + " / " + note.id + ": " + needed
                                + " lines in room for " + lines);
                    }
                }
            }
        }
        assertTrue(overflowing.isEmpty(), "notes cut off by their own border:\n"
                + String.join("\n", overflowing));
    }

    /** A note is read beside the cards, never on top of them; a frame never covers another. */
    @Test
    void notesAndFramesNeverSitOnEachOtherOrOnACard() {
        for (TaskGraph task : DefaultTasks.create()) {
            List<Box> notes = new ArrayList<>();
            for (TaskNote note : task.notes) {
                notes.add(new Box("note " + note.id, note.x, note.y, note.right(), note.bottom()));
            }
            List<Box> frames = new ArrayList<>();
            for (TaskGroup group : task.groups) {
                group.fit(task, node -> TaskCanvas.CARD_HEIGHT);
                frames.add(new Box("frame " + group.id, group.x, group.y - TaskGroup.HEADER_HEIGHT,
                        group.right(), group.bottom()));
            }
            List<Box> cards = new ArrayList<>();
            for (TaskNode node : task.nodes) {
                cards.add(new Box("card " + node.id, node.editorX, node.editorY,
                        node.editorX + TaskCanvas.CARD_WIDTH, node.editorY + TaskCanvas.CARD_HEIGHT));
            }
            for (int i = 0; i < notes.size(); i++) {
                for (int j = i + 1; j < notes.size(); j++) {
                    assertFalse(notes.get(i).overlaps(notes.get(j)),
                            task.name + ": " + notes.get(i) + " overlaps " + notes.get(j));
                }
                for (Box other : cards) {
                    assertFalse(notes.get(i).overlaps(other),
                            task.name + ": " + notes.get(i) + " overlaps " + other);
                }
                for (Box other : frames) {
                    assertFalse(notes.get(i).overlaps(other),
                            task.name + ": " + notes.get(i) + " overlaps " + other);
                }
            }
            for (int i = 0; i < frames.size(); i++) {
                for (int j = i + 1; j < frames.size(); j++) {
                    assertFalse(frames.get(i).overlaps(frames.get(j)),
                            task.name + ": " + frames.get(i) + " overlaps " + frames.get(j));
                }
            }
            for (Box frame : frames) {
                assertTrue(frame.left >= 0 && frame.top >= 0,
                        task.name + ": " + frame + " hangs off the top or left of the canvas");
            }
        }
    }

    /**
     * The canvas opens at its origin, so the first thing a player sees is whatever is there - and
     * it should be the note that says what the job is for.
     */
    @Test
    void everyJobOpensOnItsIntroduction() {
        for (TaskGraph task : DefaultTasks.create()) {
            TaskNote intro = task.notes.stream().filter(note -> note.id.equals("intro"))
                    .findFirst().orElse(null);
            assertNotNull(intro, task.name + " has no introduction");
            for (TaskNote note : task.notes) {
                assertTrue(note.x >= intro.x && (note.y >= intro.y),
                        task.name + ": " + note.id + " sits above or left of the introduction");
            }
            assertEquals(DefaultTasks.CAPTION_TOP, intro.y);
            assertEquals(0, intro.colour, task.name + ": introductions are amber");
        }
    }

    /**
     * Every card that does something is inside a frame that names its section. The only ones left
     * out are the lane's own punctuation: START, End, and the sounds at either end of a demo.
     */
    @Test
    void everyWorkingCardIsInsideANamedFrame() {
        for (TaskGraph task : DefaultTasks.create()) {
            Set<String> framed = new HashSet<>();
            task.groups.forEach(group -> framed.addAll(group.members));
            for (TaskNode node : task.nodes) {
                if (framed.contains(node.id) || node.isStartNode() || node.isEndNode()
                        || "notify".equals(node.commandId)) {
                    continue;
                }
                throw new AssertionError(task.name + ": " + node.id + " is in no frame");
            }
            for (TaskGroup group : task.groups) {
                for (String member : group.members) {
                    assertNotNull(task.nodeById(member),
                            task.name + ": frame " + group.id + " holds a card that is not there");
                }
            }
        }
    }

    /** Two jobs that frame the same kind of section call it the same thing, from one line. */
    @Test
    void aFrameIdMeansTheSameTitleInEveryJob() {
        java.util.Map<String, String> titles = new java.util.HashMap<>();
        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskGroup group : task.groups) {
                String earlier = titles.putIfAbsent(group.seededId, group.title);
                if (earlier != null) {
                    assertEquals(earlier, group.title,
                            task.name + ": frame " + group.seededId + " means two different things");
                }
            }
        }
    }

    /** Greedy word wrap at a fixed character width: how many lines the text takes. */
    private static int wrappedLines(String text, int perLine) {
        int lines = 0;
        for (String paragraph : text.split("\n", -1)) {
            int used = 0;
            lines++;
            for (String word : paragraph.split(" ")) {
                int length = word.length();
                if (used == 0) {
                    used = length;
                } else if (used + 1 + length <= perLine) {
                    used += 1 + length;
                } else {
                    lines++;
                    used = length;
                }
                while (used > perLine) {
                    lines++;
                    used -= perLine;
                }
            }
        }
        return lines;
    }

    private record Box(String name, int left, int top, int right, int bottom) {
        boolean overlaps(Box other) {
            return left < other.right && other.left < right && top < other.bottom
                    && other.top < bottom;
        }

        @Override
        public String toString() {
            return name + " [" + left + "," + top + " - " + right + "," + bottom + "]";
        }
    }
}
