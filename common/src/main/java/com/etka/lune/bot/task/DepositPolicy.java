package com.etka.lune.bot.task;

import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.Goals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;

/**
 * What the Deposit card can reach, and whether it is carrying any of it.
 *
 * <p>Kept beside {@link DepositTask} rather than folded into {@code InventoryHelper} because the
 * answer is only true of a chest. The shared helpers count the whole inventory; this card can
 * only ever empty the part of it a container menu puts on screen.</p>
 */
final class DepositPolicy {

    /**
     * How many of the player's slots a deposit can reach.
     *
     * <p>A chest menu is built by {@code addStandardInventorySlots}, which adds inventory indices
     * 9..35 and then the hotbar's 0..8 - the 36 storage slots and nothing else. Armour, the
     * offhand, the body and the saddle slot are not in that menu, so nothing sitting in them can
     * be shift-clicked into a chest however well it matches the filter.</p>
     *
     * <p>Which is why this is not {@code Inventory.getContainerSize()}, the obvious thing to ask:
     * that is 36 plus the equipment slots, so a Blocks deposit would have counted the carved
     * pumpkin the player is wearing, walked to the chest and moved nothing.</p>
     */
    static final int STORAGE_SLOTS = Inventory.INVENTORY_SIZE;

    private DepositPolicy() {}

    /**
     * Ticks the head is given to land on a container already in reach and in view.
     *
     * <p>A backstop for the unforeseen rather than the mechanism: line of sight is part of the
     * card's reach test, so anything that reaches this ceiling is a turn that has stopped
     * converging. It is deliberately far larger than a turn costs - the Config tab allows a turn
     * speed as low as one degree a tick, where a full circle is 360 of them, while the default
     * twelve lands a half turn in well under forty - so it never fires on a head that is merely
     * slow.</p>
     */
    static final int MAX_AIM_TICKS = 400;

    /** Whether any reachable slot holds something this filter accepts. */
    static boolean holdsMatching(Player player, Predicate<ItemStack> matcher) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where to stand to open {@code container}.
     *
     * <p>The first attempt is the cheap one - anywhere within arm's length - because that is
     * usually a clear shot and costs the pathfinder least. It is also satisfied from a block
     * below the container, which is the position that can sit inside the vanilla reach radius
     * with a lip across every face.</p>
     *
     * <p>So when the loose goal reports success and the container still cannot be clicked, the
     * answer is to tighten it rather than to rebuild it: {@code tightened} names the eight blocks
     * bordering the container at its own height, which is where a player walks when the easy spot
     * turns out to face the back of a wall.</p>
     */
    static Goal approachGoal(BlockPos container, boolean tightened) {
        if (!tightened) {
            return new Goals.Adjacent(container, 3.5);
        }
        return new Goals.Any(List.of(
                new Goals.Block(container.north()),
                new Goals.Block(container.south()),
                new Goals.Block(container.east()),
                new Goals.Block(container.west()),
                new Goals.Block(container.north().east()),
                new Goals.Block(container.north().west()),
                new Goals.Block(container.south().east()),
                new Goals.Block(container.south().west())));
    }
}
