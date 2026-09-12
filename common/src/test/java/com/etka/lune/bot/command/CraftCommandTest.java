package com.etka.lune.bot.command;

import com.etka.lune.bot.Task;
import com.etka.lune.bot.catalog.CraftPattern;
import com.etka.lune.bot.catalog.CraftRecipe;
import com.etka.lune.bot.task.FailTask;
import com.etka.lune.bot.task.GridCraftTask;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Craft card, checked at the point where a stored card becomes work: one answer decides which
 * of two very different tasks is built, and a card that cannot do the job must say so rather than
 * quietly building something that does nothing.
 */
class CraftCommandTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CommandDef craft() {
        CommandDef def = CommandRegistry.byId("craft");
        assertNotNull(def, "the Craft card is not registered");
        return def;
    }

    private static String drawn(int size, Item... cells) {
        return CraftRecipe.ofPattern(CraftPattern.of(size, Arrays.asList(cells))).serialize();
    }

    /**
     * Three rows, because a card nobody can read is a card nobody uses.
     *
     * <p>Naming an item is not built here on purpose: {@code CraftTask} names itself after the
     * item, and reading an item's name needs the component binding a headless bootstrap does not
     * do. What that half does is unchanged and already covered by the jobs that use it; what is new
     * is the drawing, and that is what the rest of this file exercises.</p>
     */
    @Test
    void theCardAsksThreeQuestions() {
        assertEquals(List.of("recipe", "count", "table"),
                craft().params().stream().map(Param::id).toList());
    }

    /** The one count covers both halves: how many to end up with, or how many times to craft. */
    @Test
    void oneCountServesBothHalves() {
        Task task = craft().buildWith(Map.of(
                "recipe", drawn(CraftPattern.LARGE,
                        Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
                        null, Items.STICK, null,
                        null, Items.STICK, null),
                "count", "3"));
        GridCraftTask hand = assertInstanceOf(GridCraftTask.class, task,
                "a drawn recipe must not fall back to the recipe book");
        assertTrue(hand.needsTable(), "a three-wide shape does not fit the player's own grid");
        assertEquals(3, hand.progress().target());
    }

    /** A shape that fits the 2×2 must not send the bot off to build a crafting table for it. */
    @Test
    void aSmallShapeIsCraftedWithoutATable() {
        Task task = craft().buildWith(Map.of(
                "recipe", drawn(CraftPattern.LARGE,
                        null, null, null,
                        null, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        null, Items.OAK_PLANKS, Items.OAK_PLANKS),
                "count", "1"));
        assertFalse(assertInstanceOf(GridCraftTask.class, task).needsTable());
    }

    /** An unanswered recipe is a card that was never finished, and it says so instead of running. */
    @Test
    void anUnfinishedRecipeRefusesToRun() {
        assertInstanceOf(FailTask.class, craft().buildWith(Map.of(
                "recipe", CraftRecipe.empty().serialize(), "count", "1")));
        assertInstanceOf(FailTask.class, craft().buildWith(Map.of(
                "recipe", drawn(CraftPattern.LARGE), "count", "1")));
    }

    /**
     * A card is stored as strings and rebuilt from them, so a drawing that does not survive that
     * trip is a drawing the saved task does not have. The card also has to explain itself from it.
     */
    @Test
    void theDrawingSurvivesBeingStoredAndIsExplained() {
        CommandDef def = craft();
        Map<String, String> saved = def.snapshot();
        try {
            String recipe = drawn(CraftPattern.LARGE,
                    Items.COAL, null, null,
                    Items.STICK, null, null,
                    null, null, null);
            def.apply(Map.of("recipe", recipe, "count", "4"));
            assertEquals(recipe, def.snapshot().get("recipe"));
            assertEquals(Items.COAL, def.recipeValue("recipe").pattern().cell(0, 0));
            assertTrue(def.logicDescription().contains("2 drawn ingredients"),
                    "the card explains itself from the drawing: " + def.logicDescription());
            assertTrue(def.logicDescription().contains("4 times"),
                    "the count is how many times a drawing is crafted: " + def.logicDescription());
        } finally {
            def.apply(saved);
        }
    }

    /**
     * The table row is only asked about when it can be answered. A drawing is three cells wide or
     * it is not, so offering the choice there would invite an answer and then ignore it.
     */
    @Test
    void theTableRowIsHiddenForADrawing() {
        CommandDef def = craft();
        Map<String, String> saved = def.snapshot();
        try {
            def.apply(Map.of("recipe", drawn(CraftPattern.SMALL, Items.OAK_PLANKS, null, null, null)));
            assertFalse(def.isRelevant("table"), "a drawing decides its own grid size");
            assertTrue(def.isRelevant("count"));

            def.apply(Map.of("recipe", CraftRecipe.ofItem(Items.TORCH).serialize()));
            assertTrue(def.isRelevant("table"), "a named recipe still needs the table question");
        } finally {
            def.apply(saved);
        }
    }

    @Test
    void theCardIsOfferedInThePalette() {
        assertEquals("Items & Storage", CommandRegistry.categoryFor("craft"));
        assertTrue(CommandRegistry.search("craft").stream()
                        .anyMatch(def -> def.id().equals("craft")),
                "searching for craft should offer the Craft card");
    }
}
