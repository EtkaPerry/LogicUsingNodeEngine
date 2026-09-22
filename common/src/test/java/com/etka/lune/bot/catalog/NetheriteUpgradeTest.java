package com.etka.lune.bot.catalog;

import com.etka.lune.bot.Task;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.command.Param;
import com.etka.lune.bot.task.NetheriteUpgradeTask;
import com.etka.lune.bot.task.SmeltTask;
import com.etka.lune.util.Lang;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last rung of the ladder: ancient debris out of the Nether, and a smithing table at the end
 * of it.
 *
 * <p>The pairing is read off the registry rather than listed, so what is worth checking is that
 * the reading keeps the right things and drops the wrong ones - a diamond block pairs with a
 * netherite block by name and is not a smithing recipe at all.</p>
 */
class NetheriteUpgradeTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void gearPairsWithItsNetheriteTwin() {
        assertEquals(Items.NETHERITE_PICKAXE, NetheriteUpgrades.resultOf(Items.DIAMOND_PICKAXE));
        assertEquals(Items.NETHERITE_HELMET, NetheriteUpgrades.resultOf(Items.DIAMOND_HELMET));
        assertEquals(Items.NETHERITE_SWORD, NetheriteUpgrades.resultOf(Items.DIAMOND_SWORD));
    }

    /**
     * The three ways the name rule would be wrong if nothing else were asked: a block that pairs
     * by name, a diamond thing with no netherite twin at all, and something already upgraded.
     */
    @Test
    void whatIsNotGearIsNotOffered() {
        assertNull(NetheriteUpgrades.resultOf(Items.DIAMOND_BLOCK),
                "a diamond block pairs with a netherite block by name, and is not smithing");
        assertNull(NetheriteUpgrades.resultOf(Items.DIAMOND_ORE));
        assertNull(NetheriteUpgrades.resultOf(Items.DIAMOND));
        assertNull(NetheriteUpgrades.resultOf(Items.NETHERITE_PICKAXE));
        assertNull(NetheriteUpgrades.resultOf(null));
    }

    @Test
    void thePickerHoldsTheGearAndNothingElse() {
        List<Item> bases = NetheriteUpgrades.bases();
        assertTrue(bases.contains(Items.DIAMOND_PICKAXE));
        assertTrue(bases.contains(Items.DIAMOND_CHESTPLATE));
        assertFalse(bases.contains(Items.DIAMOND_BLOCK));
        assertFalse(bases.contains(Items.NETHERITE_PICKAXE));
        for (Item base : bases) {
            assertNotNull(NetheriteUpgrades.resultOf(base), base + " is offered with nothing to become");
        }
    }

    @Test
    void theCardIsInThePaletteAndBuildsTheUpgrade() {
        CommandDef card = CommandRegistry.byId("upgrade_netherite");
        assertNotNull(card, "Upgrade to Netherite should be registered");
        assertEquals("Items & Storage", CommandRegistry.categoryFor("upgrade_netherite"));
        assertTrue(CommandRegistry.isAvailable("upgrade_netherite"),
                "nothing about this card waits on another mod");

        Task task = card.buildWith(Map.of("gear", "minecraft:diamond_pickaxe"));
        assertInstanceOf(NetheriteUpgradeTask.class, task);
    }

    /**
     * The step before it: a furnace turns debris into scrap, and four of those and four gold make
     * the ingot the table asks for. Without this entry the chain has no way to start.
     */
    @Test
    void theFurnaceKnowsAncientDebris() {
        CommandDef smelt = CommandRegistry.byId("smelt");
        Param.Choice input = (Param.Choice) smelt.params().get(0);
        assertTrue(input.options().contains("Ancient Debris"),
                "the smelt dropdown should offer ancient debris: " + input.options());

        // The label is the game's own word for the item, which is what the option means; asking
        // for the name through an ItemStack is what headless tests cannot do.
        assertEquals(Lang.get(Items.ANCIENT_DEBRIS.getDescriptionId()), input.label("Ancient Debris"));
        assertInstanceOf(SmeltTask.class,
                smelt.buildWith(Map.of("input", "Ancient Debris", "count", "4")));
    }
}
