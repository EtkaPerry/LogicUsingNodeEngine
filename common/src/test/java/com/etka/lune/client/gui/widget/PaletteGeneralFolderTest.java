package com.etka.lune.client.gui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The General folder is the one the palette writes out by hand, and that is a trap.
 *
 * <p>It used to take the registry's General cards out of the grouping and throw them away, on the
 * grounds that they "are the same three cards" it had just written. The first card filed under
 * General that was not one of those three - Notify - registered correctly, translated correctly,
 * and then could not be found in the palette or by searching for it, because the only list the
 * search runs over never contained it.</p>
 */
class PaletteGeneralFolderTest {

    private static final List<PalettePanel.Item> HAND_WRITTEN = List.of(
            new PalettePanel.General("start", "START", "entry"),
            new PalettePanel.General("observer", "Observer", "watch"),
            new PalettePanel.General("end", "End", "finish"));

    @Test
    void aRegistryCardThatIsNotOneOfTheHandWrittenOnesIsKept() {
        List<PalettePanel.Item> registry = List.of(
                new PalettePanel.Command("observer", "Observer", "watch a card"),
                new PalettePanel.Command("end", "End", "consume a pulse"),
                new PalettePanel.Command("notify", "Notify", "play a sound"));

        List<PalettePanel.Item> folder = PalettePanel.withRegistryExtras(HAND_WRITTEN, registry);

        assertEquals(4, folder.size(), "exactly one card should have been added: " + folder);
        assertTrue(folder.containsAll(HAND_WRITTEN), "the hand-written nodes must survive");
        assertEquals("notify", PalettePanel.itemId(folder.get(folder.size() - 1)),
                "the extra card goes after the pulse nodes");
    }

    @Test
    void aCardTheFolderAlreadyWritesIsNotShownTwice() {
        List<PalettePanel.Item> registry = List.of(
                new PalettePanel.Command("observer", "Observer", "watch a card"),
                new PalettePanel.Command("end", "End", "consume a pulse"));

        List<PalettePanel.Item> folder = PalettePanel.withRegistryExtras(HAND_WRITTEN, registry);

        assertEquals(HAND_WRITTEN, folder, "matching ids must not be appended a second time");
    }

    @Test
    void aFolderWithNothingToAddIsLeftAlone() {
        assertEquals(HAND_WRITTEN, PalettePanel.withRegistryExtras(HAND_WRITTEN, null));
        assertEquals(HAND_WRITTEN, PalettePanel.withRegistryExtras(HAND_WRITTEN, List.of()));
    }
}
