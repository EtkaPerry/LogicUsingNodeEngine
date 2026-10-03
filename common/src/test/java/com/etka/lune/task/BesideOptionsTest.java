package com.etka.lune.task;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a task beside the player shares the controls is saved with it, and a task saved before there
 * was a choice runs the way it always did.
 */
class BesideOptionsTest {

    private static final Gson GSON = new Gson();

    @Test
    void aTaskSavedBeforeTheChoiceRunsAsItAlwaysDid() {
        TaskGraph old = GSON.fromJson("{\"name\":\"Old\",\"beside\":true,\"nodes\":[]}", TaskGraph.class);
        assertNotNull(old.besideOptions);
        assertTrue(old.besideOptions.isDefault(), "Lune first, with the mouse and the keyboard");
    }

    @Test
    void aChoiceSavedInPartKeepsTheRestAsItWas() {
        TaskGraph task = GSON.fromJson("{\"name\":\"T\",\"besideOptions\":{\"mouse\":false}}",
                TaskGraph.class);
        assertFalse(task.besideOptions.playerFirst);
        assertFalse(task.besideOptions.mouse);
        assertTrue(task.besideOptions.keyboard);
    }

    @Test
    void neitherDeviceReadsAsBoth() {
        BesideOptions none = GSON.fromJson("{\"playerFirst\":true,\"mouse\":false,\"keyboard\":false}",
                BesideOptions.class).normalize();
        assertTrue(none.mouse, "a run that may take nothing does nothing");
        assertTrue(none.keyboard);
        assertTrue(none.playerFirst, "who comes first is kept");
        assertTrue(new BesideOptions(false, false, false).mouse, "the constructor refuses it too");
    }

    @Test
    void theChoiceTravelsWithTheTaskThroughAShareCode() {
        TaskGraph task = new TaskGraph("Fireballs");
        task.beside = true;
        task.besideOptions = new BesideOptions(true, true, false);

        TaskGraph back = GSON.fromJson(TaskCode.decode(TaskCode.encode(task)), TaskGraph.class);
        assertEquals(new BesideOptions(true, true, false), back.besideOptions);
        assertTrue(back.beside);
    }

    @Test
    void aCopyIsItsOwn() {
        BesideOptions original = new BesideOptions(true, false, true);
        BesideOptions copy = original.copy();
        copy.playerFirst = false;
        assertTrue(original.playerFirst);
        assertEquals(new BesideOptions(true, false, true), original);
    }
}
