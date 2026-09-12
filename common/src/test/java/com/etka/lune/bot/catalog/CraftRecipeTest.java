package com.etka.lune.bot.catalog;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Craft card's single answer, which can be a name or a picture. What has to hold is that
 * switching between the two does not throw the other away, and that both survive being stored.
 */
class CraftRecipeTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CraftPattern torch() {
        return CraftPattern.of(CraftPattern.LARGE, Arrays.asList(
                Items.COAL, null, null,
                Items.STICK, null, null,
                null, null, null));
    }

    @Test
    void aNamedItemReadsBackAsItself() {
        CraftRecipe named = CraftRecipe.ofItem(Items.TORCH);
        CraftRecipe restored = CraftRecipe.deserialize(named.serialize());
        assertFalse(restored.isDrawn());
        assertEquals(Items.TORCH, restored.item());
        assertTrue(restored.isReady());
    }

    @Test
    void aDrawingReadsBackAsItself() {
        CraftRecipe drawn = CraftRecipe.ofPattern(torch());
        CraftRecipe restored = CraftRecipe.deserialize(drawn.serialize());
        assertTrue(restored.isDrawn());
        assertEquals(torch(), restored.pattern());
        assertTrue(restored.isReady());
    }

    /**
     * Trying the other tab and changing your mind must not cost you the answer you had. Both halves
     * are stored, so a card that names an item, gets drawn on, and is switched back still knows it.
     */
    @Test
    void bothAnswersSurviveSwitchingBetweenThem() {
        CraftRecipe both = CraftRecipe.ofItem(Items.TORCH).withPattern(torch());
        assertTrue(both.isDrawn());
        assertEquals(Items.TORCH, both.item());

        CraftRecipe reloaded = CraftRecipe.deserialize(both.serialize());
        assertEquals(both, reloaded);
        assertEquals(2, reloaded.pattern().filledCells());

        CraftRecipe backToTheItem = reloaded.withItem(reloaded.item());
        assertFalse(backToTheItem.isDrawn());
        assertEquals(2, backToTheItem.pattern().filledCells(), "the drawing is still there");
    }

    @Test
    void anUnfinishedRecipeSaysSo() {
        assertFalse(CraftRecipe.empty().isReady());
        assertFalse(CraftRecipe.ofPattern(CraftPattern.empty(CraftPattern.LARGE)).isReady());
        assertEquals("not set", CraftRecipe.empty().describe());
        assertEquals("empty grid", CraftRecipe.ofPattern(CraftPattern.empty(CraftPattern.SMALL)).describe());
    }

    /** An imported card may name an item this instance does not have; that is not a broken card. */
    @Test
    void anUnknownItemIsDroppedRatherThanRefused() {
        CraftRecipe restored = CraftRecipe.deserialize("item|somemod:mythril|3;");
        assertFalse(restored.isDrawn());
        assertNull(restored.item());
        assertFalse(restored.isReady());
    }

    @Test
    void junkReadsBackAsAnUnsetRecipe() {
        assertFalse(CraftRecipe.deserialize(null).isReady());
        assertFalse(CraftRecipe.deserialize("").isReady());
        assertFalse(CraftRecipe.deserialize("nonsense").isReady());
    }
}
