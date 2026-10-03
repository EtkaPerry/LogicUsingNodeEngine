package com.etka.lune.client.gui.widget;

import com.etka.lune.task.BesideOptions;
import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-figure switch opens this popup instead of flipping, so nothing about the task changes
 * until a button says so - and Cancel says nothing at all.
 *
 * <p>The first version flipped the switch on and then asked; Cancel threw the choices away and
 * left the switch on. Driven by keys here, because a click needs the game's font to find the
 * buttons.</p>
 */
class BesidePromptTest {

    private final List<BesidePrompt.Choice> heard = new ArrayList<>();

    @Test
    void cancellingChangesNothingNotEvenTheSwitch() {
        BesidePrompt prompt = new BesidePrompt();
        prompt.open("Mine", false, new BesideOptions(), heard::add);
        assertTrue(prompt.isOpen());

        prompt.handleScreenKeyPressed(InputConstants.KEY_ESCAPE);
        assertFalse(prompt.isOpen());
        assertTrue(heard.isEmpty(), "a task that ran in the player's place still does");
    }

    @Test
    void onATaskThatRunsInThePlayersPlaceTheMainButtonTurnsItOn() {
        BesidePrompt prompt = new BesidePrompt();
        prompt.open("Fireballs", false, new BesideOptions(true, true, false), heard::add);

        prompt.handleScreenKeyPressed(InputConstants.KEY_RETURN);
        assertFalse(prompt.isOpen());
        assertEquals(List.of(new BesidePrompt.Choice(true, new BesideOptions(true, true, false))), heard);
    }

    @Test
    void onATaskThatRunsBesideThemTheMainButtonKeepsItOn() {
        BesidePrompt prompt = new BesidePrompt();
        prompt.open("Mine", true, new BesideOptions(), heard::add);

        prompt.handleScreenKeyPressed(InputConstants.KEY_RETURN);
        assertEquals(List.of(new BesidePrompt.Choice(true, new BesideOptions())), heard);
    }

    @Test
    void theChoicesShownAreACopyUntilTheyAreCommitted() {
        BesideOptions saved = new BesideOptions(false, true, true);
        BesidePrompt prompt = new BesidePrompt();
        prompt.open("Mine", true, saved, heard::add);
        prompt.handleScreenKeyPressed(InputConstants.KEY_RETURN);

        heard.get(0).options().playerFirst = true;
        assertFalse(saved.playerFirst, "what was handed in is never what comes back to be edited");
    }
}
