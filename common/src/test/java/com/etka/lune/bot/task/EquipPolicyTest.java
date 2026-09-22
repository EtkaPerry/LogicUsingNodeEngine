package com.etka.lune.bot.task;

import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.util.EquipHelper;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which piece of armour is the better one, and when the card asks which item it is putting on.
 *
 * <p>Everything here is the half of the decision that needs no world. The ranking is checked
 * through {@link EquipHelper#score} rather than through a stack, because a headless test has no
 * item components to read attributes off - see the note on {@code InventoryHelper.itemName}. What
 * the card does with the answer is measured in a run, not here.</p>
 */
class EquipPolicyTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Diamond boots over an enchanted leather pair: shielding beats everything else about it. */
    @Test
    void armorPointsDecideBeforeAnythingElse() {
        double diamond = EquipHelper.score(3, 2, false);
        double enchantedLeather = EquipHelper.score(1, 0, true);
        assertTrue(diamond > enchantedLeather, diamond + " vs " + enchantedLeather);
    }

    /** Netherite and diamond shield the same on the head; the tougher one is the better helmet. */
    @Test
    void toughnessOnlySeparatesPiecesThatShieldTheSame() {
        assertTrue(EquipHelper.score(3, 3, false) > EquipHelper.score(3, 2, false));
        // ...and never outweighs a point of armour, however much of it there is.
        assertTrue(EquipHelper.score(4, 0, false) > EquipHelper.score(3, 9, true));
    }

    /** Two of the same piece, one enchanted: the enchanted one, and only as the last word. */
    @Test
    void beingEnchantedIsTheLastTieBreaker() {
        assertTrue(EquipHelper.score(2, 0, true) > EquipHelper.score(2, 0, false));
        assertTrue(EquipHelper.score(2, 1, false) > EquipHelper.score(2, 0, true));
    }

    /**
     * The same piece must never rank above itself.
     *
     * <p>{@code upgradeFor} only reaches for something that scores strictly higher than what is
     * worn, and this is why: {@code swapWithEquipmentSlot} refuses a swap between two stacks that
     * are the same item with the same components, so a card that thought its own helmet was an
     * upgrade would spend every tick asking to put it on and every tick being told no.</p>
     */
    @Test
    void aPieceIsNeverAnUpgradeOverItself() {
        assertEquals(EquipHelper.score(3, 2, true), EquipHelper.score(3, 2, true));
        assertFalse(EquipHelper.score(3, 2, true) > EquipHelper.score(3, 2, true));
    }

    /** Only the four slots a person wears armour in - never the body slot, which is a horse's. */
    @Test
    void onlyTheFourHumanoidSlotsAreDressed() {
        assertEquals(List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET), EquipHelper.ARMOR_SLOTS);
    }

    @Test
    void everyModeRoundTripsThroughItsSavedValue() {
        for (EquipTask.What what : EquipTask.What.values()) {
            assertEquals(what, EquipTask.What.fromLabel(what.label()));
        }
        // A task saved before a value was renamed still opens, on the mode that dresses everything.
        assertEquals(EquipTask.What.BEST_ARMOR, EquipTask.What.fromLabel("Whatever this was"));
        assertEquals(EquipTask.What.BEST_ARMOR, EquipTask.What.fromLabel(null));
    }

    /** The item row is a question only in the mode that puts on one named thing. */
    @Test
    void theItemRowIsOnlyAskedForWhenOneItemIsBeingWorn() {
        CommandDef equip = CommandRegistry.byId("equip");
        Map<String, String> saved = equip.snapshot();
        try {
            equip.apply(Map.of("what", EquipTask.What.BEST_ARMOR.label()));
            assertFalse(equip.isRelevant("item"));
            assertTrue(equip.isRelevant("what"));

            equip.apply(Map.of("what", EquipTask.What.CHOSEN.label()));
            assertTrue(equip.isRelevant("item"));
        } finally {
            equip.apply(saved);
        }
    }

    /**
     * Accessories are the half of the card that needs Curios or Trinkets; armour is not, so the
     * card itself is never gated. A palette that hid it without an accessory mod would be hiding
     * the answer to "why does she keep dying in a shirt".
     */
    @Test
    void theCardIsOfferedWithoutAnyAccessoryMod() {
        assertTrue(CommandRegistry.isAvailable("equip"));
        assertFalse(CommandRegistry.modCards().contains("equip"));
    }
}
