package com.etka.lune.bot.knowledge;

import com.etka.lune.bot.catalog.BlockCatalog;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a prospect aims, asked without a world: the three facts {@code bestYFor} rests on.
 *
 * <p>Deepslate is what this was written for. It had no depth, so a Mine or Find card asked for it
 * with nothing in sight prospected along the level the bot stood on - on the surface, digging
 * sideways and never nearer the layer it was after. A prospect's aim is {@code bestYFor}, and the
 * bot's own Y when that is empty.</p>
 */
class OreDepthTest {

    private static final Supplier<String> PLAINS = () -> "minecraft:plains";

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // The built-in table on its own, as OreDimensionTest reads it.
        OreKnowledge.loadConfig(Map.of());
    }

    // The worlds' ids are read once a test runs, never in a static field: touching Level before
    // the bootstrap above fails the registries for every suite after this one in the same JVM.
    private static String overworld() {
        return Level.OVERWORLD.identifier().toString();
    }

    private static String nether() {
        return Level.NETHER.identifier().toString();
    }

    private static String end() {
        return Level.END.identifier().toString();
    }

    private static OptionalInt aim(Set<Block> targets, String world, int y) {
        return OreKnowledge.bestYFor(targets, world, PLAINS, y);
    }

    /**
     * Just under Y 0: below the stretch from Y 8 where deepslate thins in, so a branch dug there is
     * deepslate all round, and far above the lava lakes near the floor.
     */
    @Test
    void deepslateIsAimedForJustUnderWhereItBegins() {
        assertEquals(OptionalInt.of(-4), aim(Set.of(Blocks.DEEPSLATE), overworld(), 70));
        OreProfile profile = OreKnowledge.forBlock(Blocks.DEEPSLATE).orElseThrow();
        assertTrue(profile.inBand(8) && profile.inBand(0) && profile.inBand(-64));
        assertFalse(profile.inBand(9), "no deepslate generates above Y 8");
        assertTrue(profile.bestY() < 0 && profile.bestY() > -54,
                "the aim belongs in the solid layer and above the lava lakes");
    }

    /** No other world has deepslate, and the Nether's floor is under its lava sea. */
    @Test
    void onlyTheOverworldHasIt() {
        assertEquals(overworld(), OreKnowledge.forBlock(Blocks.DEEPSLATE).orElseThrow()
                .dimension().orElseThrow());
        assertTrue(aim(Set.of(Blocks.DEEPSLATE), nether(), 70).isEmpty(),
                "a prospect in the Nether would aim at its floor through the lava sea");
        assertTrue(aim(Set.of(Blocks.DEEPSLATE), end(), 70).isEmpty());
    }

    /** A card asked for deepslate and iron aims for whichever depth is nearer the bot. */
    @Test
    void ofSeveralTargetsTheNearestDepthWins() {
        Set<Block> both = Set.of(Blocks.DEEPSLATE, Blocks.IRON_ORE);
        assertEquals(OptionalInt.of(16), aim(both, overworld(), 70));
        assertEquals(OptionalInt.of(-4), aim(both, overworld(), -20));
    }

    /** No depth known is still the honest answer to stay level, for anything else. */
    @Test
    void aBlockTheTableNeverHeardOfStaysLevel() {
        assertTrue(aim(Set.of(Blocks.STONE), overworld(), 70).isEmpty());
        assertTrue(aim(Set.of(Blocks.COBBLED_DEEPSLATE), overworld(), 70).isEmpty(),
                "cobbled deepslate is placed, not generated");
        assertTrue(aim(Set.of(), overworld(), 70).isEmpty());
    }

    /**
     * Having a depth is not being an ore. The run's ore count asked the depth table once, and with
     * deepslate in it every block of a staircase through the layer would have counted.
     */
    @Test
    void aDepthDoesNotMakeAnOre() {
        assertFalse(BlockCatalog.isOre(Blocks.DEEPSLATE));
        assertFalse(BlockCatalog.isOre(Blocks.STONE));
        assertFalse(BlockCatalog.isOre(null));
        assertTrue(BlockCatalog.isOre(Blocks.DEEPSLATE_IRON_ORE));
        assertTrue(BlockCatalog.isOre(Blocks.ANCIENT_DEBRIS));
        assertTrue(BlockCatalog.isOre(Blocks.NETHER_QUARTZ_ORE), "an ore with no depth is still an ore");
    }
}
