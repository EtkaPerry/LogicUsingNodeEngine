package com.etka.lune.bot.catalog;

import com.etka.lune.bot.Task;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.task.ReplantTask;
import com.etka.lune.bot.task.UsePortalTask;
import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which sapling grows a felled tree back, and the two cards that came with the lumber camp.
 *
 * <p>The sapling is found by name, so the cases worth pinning are the ones that break the pattern -
 * a propagule, a fungus, a stripped trunk - and the ones with no sapling at all.</p>
 */
class SaplingsTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void restoreLanguage() {
        Lang.select(LuneLanguages.GAME_DEFAULT);
    }

    @Test
    void everyVanillaTreeFindsTheSaplingItGrewFrom() {
        assertEquals(Items.OAK_SAPLING, Saplings.forLog(Blocks.OAK_LOG));
        assertEquals(Items.SPRUCE_SAPLING, Saplings.forLog(Blocks.SPRUCE_LOG));
        assertEquals(Items.BIRCH_SAPLING, Saplings.forLog(Blocks.BIRCH_LOG));
        assertEquals(Items.JUNGLE_SAPLING, Saplings.forLog(Blocks.JUNGLE_LOG));
        assertEquals(Items.ACACIA_SAPLING, Saplings.forLog(Blocks.ACACIA_LOG));
        assertEquals(Items.DARK_OAK_SAPLING, Saplings.forLog(Blocks.DARK_OAK_LOG));
        assertEquals(Items.CHERRY_SAPLING, Saplings.forLog(Blocks.CHERRY_LOG));
        assertEquals(Items.PALE_OAK_SAPLING, Saplings.forLog(Blocks.PALE_OAK_LOG));
    }

    @Test
    void theTreesThatBreakThePatternAreStillFound() {
        assertEquals(Items.MANGROVE_PROPAGULE, Saplings.forLog(Blocks.MANGROVE_LOG));
        assertEquals(Items.CRIMSON_FUNGUS, Saplings.forLog(Blocks.CRIMSON_STEM));
        assertEquals(Items.WARPED_FUNGUS, Saplings.forLog(Blocks.WARPED_STEM));
        assertEquals(Items.BIRCH_SAPLING, Saplings.forLog(Blocks.STRIPPED_BIRCH_LOG),
                "a stripped trunk grew from the same sapling");
        assertEquals(Items.OAK_SAPLING, Saplings.forLog(Blocks.OAK_WOOD));
    }

    @Test
    void somethingThatIsNotATreeHasNoSapling() {
        assertEquals(Items.AIR, Saplings.forLog(Blocks.BAMBOO_BLOCK));
        assertEquals(Items.AIR, Saplings.forLog(Blocks.STONE));
        assertEquals(Items.AIR, Saplings.forLog(null));
    }

    @Test
    void theWoodIsReadOffTheTrunkName() {
        assertEquals("oak", Saplings.woodOf("oak_log"));
        assertEquals("birch", Saplings.woodOf("stripped_birch_wood"));
        assertEquals("crimson", Saplings.woodOf("crimson_hyphae"));
        assertNull(Saplings.woodOf("_log"), "a suffix with nothing in front of it names no wood");
        assertNull(Saplings.woodOf("planks"));
    }

    /** The planting half of Chop Wood is its own card, gathered with the other gathering cards. */
    @Test
    void replantTreesIsACardOfItsOwn() {
        CommandDef def = CommandRegistry.byId("replant");
        assertNotNull(def);
        assertEquals("Gathering", CommandRegistry.categoryFor("replant"));
        Task built = def.buildWith(Map.of("radius", "24"));
        assertInstanceOf(ReplantTask.class, built);
        // The definition is also the palette's live editing state, so put it back afterwards.
        Map<String, String> saved = def.snapshot();
        try {
            def.apply(Map.of("radius", "24"));
            Lang.select("en_us");
            assertTrue(def.logicDescription().contains("24"),
                    "the card should say how far it goes back for the trees it replants");
        } finally {
            def.apply(saved);
        }
    }

    /** Crossing over is a card now, not something only the speedrun could do. */
    @Test
    void useNetherPortalIsACardOfItsOwn() {
        CommandDef def = CommandRegistry.byId("use_portal");
        assertNotNull(def);
        assertEquals("Movement", CommandRegistry.categoryFor("use_portal"));
        assertInstanceOf(UsePortalTask.class, def.buildWith(Map.of("radius", "16")));
    }
}
