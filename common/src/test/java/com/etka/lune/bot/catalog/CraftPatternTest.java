package com.etka.lune.bot.catalog;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-drawn grid, checked where it is easy to get wrong: what a pattern reads back as after a
 * round trip, and whether it still means the same shape when the grid it was drawn on changes.
 */
class CraftPatternTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A torch: coal over a stick, drawn on the large grid. */
    private static CraftPattern torch() {
        return CraftPattern.of(CraftPattern.LARGE, Arrays.asList(
                Items.COAL, null, null,
                Items.STICK, null, null,
                null, null, null));
    }

    @Test
    void aPatternReadsBackAsItWasWritten() {
        CraftPattern original = torch();
        CraftPattern restored = CraftPattern.deserialize(original.serialize());
        assertEquals(original, restored);
        assertEquals(Items.COAL, restored.cell(0, 0));
        assertEquals(Items.STICK, restored.cell(1, 0));
        assertNull(restored.cell(0, 1));
    }

    /** Empty cells are the common case in a drawing, so they have to survive the trip. */
    @Test
    void trailingEmptyCellsSurvive() {
        CraftPattern restored = CraftPattern.deserialize(torch().serialize());
        assertEquals(CraftPattern.LARGE, restored.size());
        assertEquals(2, restored.filledCells());
        assertNull(restored.cell(2, 2));
    }

    /**
     * A pattern is shared and imported, so a cell naming an item this instance does not have has to
     * read back empty rather than refusing the whole card - the same rule every other parameter has.
     */
    @Test
    void unknownItemsAreDroppedRatherThanRefused() {
        CraftPattern restored = CraftPattern.deserialize("2;minecraft:stick,somemod:mythril,,junk");
        assertEquals(CraftPattern.SMALL, restored.size());
        assertEquals(Items.STICK, restored.cell(0, 0));
        assertNull(restored.cell(0, 1));
        assertEquals(1, restored.filledCells());
    }

    @Test
    void junkReadsBackAsAnEmptyLargeGrid() {
        assertTrue(CraftPattern.deserialize("").isEmpty());
        assertTrue(CraftPattern.deserialize(null).isEmpty());
        assertEquals(CraftPattern.LARGE, CraftPattern.deserialize("not a pattern").size());
    }

    /** Growing the grid must not move what is already drawn; shrinking drops what no longer fits. */
    @Test
    void resizingKeepsTheTopLeftCorner() {
        CraftPattern small = CraftPattern.of(CraftPattern.SMALL,
                Arrays.asList(Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS));
        CraftPattern grown = small.withSize(CraftPattern.LARGE);
        assertEquals(4, grown.filledCells());
        assertEquals(Items.OAK_PLANKS, grown.cell(1, 1));
        assertNull(grown.cell(2, 2));

        CraftPattern shrunk = torch().withSize(CraftPattern.SMALL);
        assertEquals(Items.COAL, shrunk.cell(0, 0));
        assertEquals(Items.STICK, shrunk.cell(1, 0));
        assertEquals(2, shrunk.filledCells());
    }

    /**
     * Whether a table is needed is a question about the drawing, not about the paper. Two planks in
     * the middle of the 3×3 are still two planks, and sending the bot to build a table for them
     * would be theatre.
     */
    @Test
    void aSmallShapeDrawnOnTheLargeGridStillFitsTheSmallOne() {
        CraftPattern middle = CraftPattern.of(CraftPattern.LARGE, Arrays.asList(
                null, null, null,
                null, Items.OAK_PLANKS, Items.OAK_PLANKS,
                null, Items.OAK_PLANKS, Items.OAK_PLANKS));
        assertEquals(2, middle.boundingWidth());
        assertEquals(2, middle.boundingHeight());
        assertTrue(middle.fitsIn(CraftPattern.SMALL));

        CraftPattern normalized = middle.normalized();
        assertEquals(Items.OAK_PLANKS, normalized.cell(0, 0));
        assertEquals(Items.OAK_PLANKS, normalized.cell(1, 1));
        assertNull(normalized.cell(2, 2));
        assertEquals(4, normalized.filledCells());
    }

    @Test
    void aWideShapeNeedsTheLargeGrid() {
        CraftPattern pickaxe = CraftPattern.of(CraftPattern.LARGE, Arrays.asList(
                Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
                null, Items.STICK, null,
                null, Items.STICK, null));
        assertEquals(3, pickaxe.boundingWidth());
        assertFalse(pickaxe.fitsIn(CraftPattern.SMALL));
        assertEquals(pickaxe, pickaxe.normalized());
    }

    /** What one craft costs, which is what the card checks the bag against before it starts. */
    @Test
    void ingredientsCountEveryCellThatWantsAnItem() {
        Map<Item, Integer> needed = CraftPattern.of(CraftPattern.LARGE, Arrays.asList(
                Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
                null, Items.STICK, null,
                null, Items.STICK, null)).ingredients();
        assertEquals(Map.of(Items.COBBLESTONE, 3, Items.STICK, 2), needed);
    }

    @Test
    void anEmptyGridHasNothingToCraft() {
        CraftPattern empty = CraftPattern.empty(CraftPattern.LARGE);
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.boundingWidth());
        assertTrue(empty.ingredients().isEmpty());
        assertEquals(empty, empty.normalized());
        assertTrue(torch().cleared().isEmpty());
    }

    /** Air is how an empty cell arrives from a picker that has no concept of "nothing". */
    @Test
    void airIsAnEmptyCell() {
        CraftPattern pattern = CraftPattern.of(CraftPattern.SMALL,
                List.of(Items.AIR, Items.AIR, Items.AIR, Items.AIR));
        assertTrue(pattern.isEmpty());
    }
}
