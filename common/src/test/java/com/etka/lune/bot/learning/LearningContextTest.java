package com.etka.lune.bot.learning;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of a learning key that must never be a sentence.
 *
 * <p>{@code performance} rows are keyed on the running task. Keyed on {@link Task#name()} - which
 * is drawn from the language file - the same bot doing the same job writes one set of rows in
 * English and a second in Turkish, and every row already on disk becomes unreachable the moment
 * the English wording changes. That is what happened: twenty-eight rows shipped keyed
 * {@code Routine: Cov ...} and nothing could reach them again once the line read {@code Task: %s}.
 * These tests pin the rule that replaced it.</p>
 */
class LearningContextTest {

    private static final Path SHIPPED_PROFILE = Path.of("src/main/resources/lune-learning.json");

    @AfterEach
    void restoreLanguage() {
        Lang.select(LuneLanguages.GAME_DEFAULT);
    }

    /** A task whose displayed name and learner key deliberately disagree, as a real one's do. */
    private record NamedTask(String displayName, String key) implements Task {
        @Override
        public String name() {
            return displayName;
        }

        @Override
        public String learningId() {
            return key;
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            return TaskStatus.RUNNING;
        }
    }

    @Test
    void theMissionKeyIsTheLearnerIdRatherThanTheDisplayedName() {
        LearningContext context = LearningContext.mission(
                new NamedTask("Görev: Ghast", "Task: Ghast"), "minecraft:overworld");
        assertEquals("Task: Ghast|Task: Ghast|minecraft:overworld|default", context.key());
    }

    /**
     * The same task in two languages is one row, not two. This is the whole point of the rule, and
     * a plain rendering of the display name would fail it.
     */
    @Test
    void thesameJobInTwoLanguagesSharesOneRow() {
        String english = LearningContext.mission(
                new NamedTask("Task: Chop 12 Logs", "Task: Chop N Logs"), "minecraft:overworld").key();
        String turkish = LearningContext.mission(
                new NamedTask("Görev: 12 Kütük Kes", "Task: Chop N Logs"), "minecraft:overworld").key();
        assertEquals(english, turkish);
    }

    /** A missing task is "unknown", not a crash and not an empty segment that eats the next one. */
    @Test
    void aMissingTaskIsNamedRatherThanBlank() {
        assertEquals("unknown|unknown|minecraft:overworld|default",
                LearningContext.mission(null, "minecraft:overworld").key());
    }

    /**
     * The separator cannot appear inside a segment, or a task named with a pipe would forge a key
     * that reads as a different dimension or phase.
     */
    @Test
    void aPipeInATaskNameCannotSplitTheKey() {
        String key = LearningContext.mission(
                new NamedTask("x", "Task: a|b"), "minecraft:overworld").key();
        assertEquals("Task: a/b|Task: a/b|minecraft:overworld|default", key);
        assertEquals(4, key.split("\\|", -1).length);
    }

    /**
     * A job that counts something, the way Bridge and Tunnel do: a scope that names the unit, and
     * a progress bar that renders it in the player's language.
     */
    private record CountedTask(String key, String unitKey, int done, int wanted) implements Task {
        @Override
        public String name() {
            return key;
        }

        @Override
        public String learningId() {
            return key;
        }

        @Override
        public LearningScope learningScope() {
            return LearningScope.of(key, unitKey);
        }

        @Override
        public TaskProgress progress() {
            return new TaskProgress(done, wanted, Lang.get(unitKey));
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            return TaskStatus.RUNNING;
        }
    }

    /**
     * The other half of the same rule. The default key carries the unit, and a progress bar's
     * unit is a word in the player's language: keyed on that, the same job wrote {@code unit=logs}
     * in English and {@code unit=kütük} in Turkish - one row per language - and a reworded unit
     * orphaned every row before it. The unit in a key is the scope's identifier instead.
     */
    @Test
    void theUnitInADefaultKeyIsTheSameRowInEveryLanguage() {
        CountedTask task = new CountedTask("Chop N Logs", "lune.unit.logs", 3, 12);
        String english = task.learningContext(null).key();
        String englishWord = task.progress().unit();

        Lang.select("tr_tr");
        String turkish = task.learningContext(null).key();

        assertNotEquals(englishWord, task.progress().unit(),
                "the bar is translated, which is exactly why the key must not follow it");
        assertEquals("skill|Chop N Logs|unknown|size=batch;unit=logs", english);
        assertEquals(english, turkish);
    }

    /** A job that counts but names no unit is still keyed on an identifier, never on its bar. */
    @Test
    void aScopeWithoutAUnitIsKeyedOnWork() {
        CountedTask task = new CountedTask("Odd Job", null, 1, 1);
        assertEquals("skill|Odd Job|unknown|size=single;unit=work", task.learningContext(null).key());
    }

    /**
     * The rows that ship are keyed on the identifiers the code writes today. The identifiers are
     * the English words on purpose, so the rows measured before units were identifiers - Bridge,
     * Tunnel, Smelt Iron Ingot, Mine N stone - stayed reachable without being touched; this is
     * what keeps the next rename of a unit key from orphaning them quietly.
     */
    @Test
    void theShippedProfileIsKeyedOnUnitIdentifiers() throws Exception {
        JsonObject profile = JsonParser.parseString(
                Files.readString(SHIPPED_PROFILE, StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> ids = new HashSet<>();
        for (String key : LuneLanguages.load(LuneLanguages.ENGLISH).keySet()) {
            if (key.startsWith("lune.unit.")) {
                ids.add(LearningScope.unitId(key));
            }
        }
        assertTrue(ids.contains("logs"), "the unit keys were not read: " + ids);

        List<String> worded = new ArrayList<>();
        for (String row : profile.getAsJsonObject("skillPerformance").keySet()) {
            LearningContext context = LearningContext.parse(row);
            for (String entry : context.phase().split(";")) {
                if (entry.startsWith("unit=") && !ids.contains(entry.substring("unit=".length()))) {
                    worded.add(row);
                }
            }
        }
        assertEquals(List.of(), worded, "rows keyed on a unit no task writes any more");
    }

    /**
     * The shipped profile must not carry a mission row again. They are named after whichever tasks
     * the developer ran, they are stripped from the public mirror for that reason, and the last set
     * sat unreachable in the jar for long enough to be mistaken for training.
     */
    @Test
    void theShippedProfileShipsNoMissionRows() throws Exception {
        JsonObject profile = JsonParser.parseString(
                Files.readString(SHIPPED_PROFILE, StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(profile.has("performance"), "lune-learning.json has no performance map at all");
        Set<String> rows = profile.getAsJsonObject("performance").keySet();
        assertEquals(Set.of(), rows,
                "lune-learning.json ships mission rows; they are named after the developer's own "
                        + "tasks, are stripped from the public mirror for that reason, and the last "
                        + "set sat unreachable in the jar long enough to be mistaken for training");
    }
}
