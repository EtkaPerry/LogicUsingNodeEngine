package com.etka.lune.bot.knowledge;

import com.etka.lune.bot.util.WorldDimension;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ore profile that names a world, and the form that name has to be in.
 *
 * <p>Ancient Debris was the first entry in the table with a dimension, and the filter that reads it
 * compared {@code ResourceKey.toString()} - which prints
 * {@code ResourceKey[minecraft:dimension / minecraft:the_nether]} - against the plain id a profile
 * holds. They never matched, so the profile was skipped everywhere including the Nether, and a
 * Stripmine asked for debris fell back to the card's own y-level. That default is -59: thirty
 * blocks below the Nether's bedrock.</p>
 *
 * <p>There is no world to stand in here, so what is checked is the pair of identifiers that
 * comparison is made of.</p>
 */
class OreDimensionTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // The built-in table on its own. Reading the player's file would go through the platform
        // services, which there is no loader here to answer - and the question is what Lune ships
        // with, not what somebody's lune.json says about it.
        OreKnowledge.loadConfig(Map.of());
    }

    private static String debrisDimension() {
        return OreKnowledge.forBlock(Blocks.ANCIENT_DEBRIS)
                .orElseThrow(() -> new AssertionError("ancient debris has no ore profile"))
                .dimension()
                .orElseThrow(() -> new AssertionError("the debris profile names no world"));
    }

    @Test
    void theProfileNamesTheWorldTheWayTheGameIdentifiesIt() {
        assertEquals(Level.NETHER.identifier().toString(), debrisDimension());
    }

    @Test
    void andNotTheWayAResourceKeyPrintsItself() {
        assertNotEquals(Level.NETHER.toString(), debrisDimension(),
                "the sentence a key prints is not an id, and comparing against it matched nothing");
    }

    @Test
    void aProfileWorldIsReadableAsOneOfTheThree() {
        assertEquals(WorldDimension.NETHER, WorldDimension.byId(debrisDimension()));
        assertTrue(WorldDimension.NETHER.matches(Level.NETHER));
        assertNull(WorldDimension.byId("someothermod:moon"));
    }

    /**
     * The ores generate wherever the bot is standing, and say so by saying nothing. Deepslate, a
     * layer rather than an ore, names the Overworld in the same form; {@link OreDepthTest} says why.
     */
    @Test
    void theOresNameNoWorldAndDeepslateNamesItsOwn() {
        assertTrue(OreKnowledge.forBlock(Blocks.DIAMOND_ORE).orElseThrow().dimension().isEmpty());
        assertTrue(OreKnowledge.forBlock(Blocks.IRON_ORE).orElseThrow().dimension().isEmpty());
        assertEquals(Level.OVERWORLD.identifier().toString(),
                OreKnowledge.forBlock(Blocks.DEEPSLATE).orElseThrow().dimension().orElseThrow());
        assertEquals(WorldDimension.OVERWORLD, WorldDimension.byId(
                OreKnowledge.forBlock(Blocks.DEEPSLATE).orElseThrow().dimension().orElseThrow()));
    }
}
