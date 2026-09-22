package com.etka.lune.bot.task;

import com.etka.lune.bot.path.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window of inventory slots a Deposit card is allowed to believe in.
 *
 * <p>The card refuses the trip to a chest when nothing inside that window matches its filter, so
 * a window one slot too wide is no longer cosmetic: it buys a block scan, a path and a walk in
 * order to shift-click something the chest menu cannot see.</p>
 *
 * <p>These are all compile-time constants in {@code Inventory}, so the assertions hold without
 * booting the game - and without building an {@code ItemStack}, which a headless test cannot
 * do.</p>
 */
class DepositPolicyTest {

    @Test
    void onlyTheSlotsAChestMenuPutsOnScreenAreCounted() {
        assertEquals(36, DepositPolicy.STORAGE_SLOTS);
        assertEquals(Inventory.INVENTORY_SIZE, DepositPolicy.STORAGE_SLOTS,
                "the storage window is the game's own number, not one of ours that can drift");
    }

    @Test
    void theHotbarAndTheMainInventoryAreBothInside() {
        assertTrue(DepositPolicy.STORAGE_SLOTS > Inventory.SELECTION_SIZE,
                "a deposit that stopped at the hotbar would leave the mining haul behind");
    }

    @Test
    void wornAndOffhandItemsAreOutside() {
        // A chest menu has no armour, offhand, body or saddle slot. Counting them would send the
        // card off to a container for an item it could never move there.
        assertTrue(Inventory.SLOT_OFFHAND >= DepositPolicy.STORAGE_SLOTS);
        assertTrue(Inventory.SLOT_BODY_ARMOR >= DepositPolicy.STORAGE_SLOTS);
        assertTrue(Inventory.SLOT_SADDLE >= DepositPolicy.STORAGE_SLOTS);
    }

    private static final BlockPos CHEST = new BlockPos(100, 64, 100);

    /**
     * The position the loose goal is happy with and the container cannot be opened from. It is
     * the reason the tightening exists, so a test that did not name it would not be testing
     * anything.
     */
    private static final BlockPos ONE_BLOCK_LOW = CHEST.below().north();

    @Test
    void theLooseGoalIsSatisfiedFromAPositionThatCannotClickTheContainer() {
        Goal loose = DepositPolicy.approachGoal(CHEST, false);
        assertTrue(loose.isReached(CHEST.north()));
        assertTrue(loose.isReached(ONE_BLOCK_LOW),
                "arm's length counts a block below, which is how the card ends up beside a "
                        + "container it cannot see");
    }

    @Test
    void theTightenedGoalOnlyAcceptsTheBlocksBorderingTheContainer() {
        Goal tight = DepositPolicy.approachGoal(CHEST, true);
        for (BlockPos side : new BlockPos[]{
                CHEST.north(), CHEST.south(), CHEST.east(), CHEST.west(),
                CHEST.north().east(), CHEST.north().west(),
                CHEST.south().east(), CHEST.south().west()}) {
            assertTrue(tight.isReached(side), "every side of the container is a place to stand: " + side);
        }
        assertFalse(tight.isReached(ONE_BLOCK_LOW),
                "the tightened goal exists to refuse exactly this position");
        assertFalse(tight.isReached(CHEST), "inside the container is not a place to stand");
        assertFalse(tight.isReached(CHEST.north(2)), "two blocks out is not in reach of a face");
    }

    @Test
    void tighteningActuallyNarrowsTheGoal() {
        // Whatever the tightened goal accepts, the loose one accepted too - otherwise tightening
        // would be sending the bot somewhere new rather than somewhere better.
        Goal loose = DepositPolicy.approachGoal(CHEST, false);
        Goal tight = DepositPolicy.approachGoal(CHEST, true);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = CHEST.offset(dx, dy, dz);
                    if (tight.isReached(pos)) {
                        assertTrue(loose.isReached(pos), "tightened past what the first goal allowed: " + pos);
                    }
                }
            }
        }
    }

    @Test
    void theAimCeilingOutlastsTheSlowestTurnTheConfigTabAllows() {
        // BotConfig.turnSpeed is degrees per tick and the Config tab's lowest value is 1, so a
        // full circle is 360 ticks. A ceiling below that would end a turn that was still working.
        assertTrue(DepositPolicy.MAX_AIM_TICKS >= 360,
                "the ceiling must be a backstop for a head that has stopped converging, "
                        + "not a deadline on a slow one: " + DepositPolicy.MAX_AIM_TICKS);
    }
}
