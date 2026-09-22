package com.etka.lune.bot.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of a learning key a card can claim without a world.
 *
 * <p>A card owns the rows its job would write, and the only way to know that from the editor is
 * to compare the parts of the key that come from parameters. Anything looser attributes iron to
 * stone; anything stricter - the whole phase, say - leaves a tree-chopping card with nothing to
 * show, because size, tool and approach are only known under the tree.</p>
 */
class LearningScopeTest {

    private static final String HAND = "skill|tree-chopping|minecraft:overworld|size=small;tool=hand;approach=walk";
    private static final String AXE = "skill|tree-chopping|minecraft:the_nether|size=giant;tool=axe;approach=near";
    private static final String STONE = "skill|block-mining|minecraft:overworld|targets=minecraft:stone;amount=few;radius=local;prospect=true";
    private static final String IRON = "skill|block-mining|minecraft:overworld|targets=minecraft:iron_ore;amount=few;radius=wide;prospect=false";

    @Test
    void aSkillAloneCoversEveryRowOfThatSkill() {
        LearningScope scope = LearningScope.of("tree-chopping");

        assertTrue(scope.matches(HAND));
        assertTrue(scope.matches(AXE));
        assertFalse(scope.matches(STONE));
    }

    @Test
    void fixedEntriesMustAllBePresentInAnyOrder() {
        LearningScope stone = LearningScope.of("block-mining", "lune.unit.blocks",
                "prospect=true", "targets=minecraft:stone");
        LearningScope fewAnywhere = LearningScope.of("block-mining", null, "amount=few");

        assertTrue(stone.matches(STONE));
        assertFalse(stone.matches(IRON), "iron is not stone, whatever else they share");
        assertTrue(fewAnywhere.matches(STONE));
        assertTrue(fewAnywhere.matches(IRON));
        assertFalse(LearningScope.of("block-mining", null, "radius=area").matches(STONE));
    }

    @Test
    void anEntryIsMatchedWholeRatherThanAsText() {
        // "prospect=true" must not be satisfied by a key that merely contains those characters.
        String tricky = "skill|block-mining|minecraft:overworld|targets=minecraft:stone;note=prospect=true";
        assertFalse(LearningScope.of("block-mining", null, "prospect=true").matches(tricky));
    }

    @Test
    void anotherSkillOrSomethingThatIsNotAKeyNeverMatches() {
        LearningScope scope = LearningScope.of("tree-chopping");

        assertFalse(scope.matches("skill|tree-chopping-2|minecraft:overworld|job"));
        assertFalse(scope.matches("tree-chopping"));
        assertFalse(scope.matches((String) null));
        assertNull(LearningContext.parse("a|b|c"));
    }

    @Test
    void theSkillIsCleanedTheWayAKeyIs() {
        // A learner id with a separator in it is written with a slash; the scope must ask for it
        // the same way, or a job could never find its own rows.
        assertEquals("Go to /here", LearningScope.of("Go to |here").skill());
        assertTrue(LearningScope.of("Go to |here").matches("skill|Go to /here|minecraft:overworld|job"));
        assertNull(LearningScope.of("x", " ").unitKey(), "a blank unit is no unit");
    }

    /**
     * What a row is keyed on for its unit: the stem of the language key, which is the English
     * word. That makes it the same row in every language, and the rows written before units were
     * identifiers are still the rows a job writes to.
     */
    @Test
    void theUnitIdentifierIsTheStemOfItsKey() {
        assertEquals("logs", LearningScope.of("tree-chopping", "lune.unit.logs").unitId());
        assertEquals("blocks", LearningScope.unitId("lune.unit.blocks"));
        assertEquals("frame_blocks", LearningScope.unitId("lune.unit.frame_blocks"));
        assertEquals("work", LearningScope.of("tree-chopping").unitId(), "a job that names no unit");
        assertEquals("work", LearningScope.unitId(" "));
    }
}
