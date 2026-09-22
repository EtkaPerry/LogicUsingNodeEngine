package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

import java.util.List;

/**
 * What a piece of armour is worth, and the one way Lune puts one on.
 *
 * <p>Lune could always <em>carry</em> armour and never wore any: {@link InventoryHelper} moves
 * things into the hand and the offhand, and the armour slots have no swap button, so nothing in
 * the tree could reach them. Two runs of the wood benchmark ended with a full iron kit in the bag
 * and the player beaten to death in a shirt.</p>
 *
 * <p>Putting one on is the player's own gesture rather than a click in a menu: hold the piece and
 * right-click it, which is {@code Equippable.swapWithEquipmentSlot} on both sides of the
 * connection. That is the only route that also <em>replaces</em> a worse piece - a shift-click
 * moves armour into an empty slot and shuffles it around the inventory when the slot is full - and
 * it is refused by the same rules that refuse the player, so a Curse of Binding helmet stays where
 * it is instead of Lune quietly cheating it off.</p>
 *
 * <p>Which piece is better is asked of the item, never of a table of tiers: the armour and
 * toughness a stack grants in its own slot come off its attribute modifiers, so a modded
 * chestplate is ranked beside a diamond one without Lune knowing that mod exists.</p>
 */
public final class EquipHelper {

    /** The four slots a player wears armour in, head down, as the inventory screen draws them. */
    public static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private EquipHelper() {}

    /**
     * Which slot using a carried stack would put it in, or {@code null} for anything that would
     * not go anywhere.
     *
     * <p>Both halves of the question are the item's own: a stack with no {@code EQUIPPABLE}
     * component is not worn at all, and one that is not {@code swappable} - a saddle, a wolf's
     * body armour - is worn by something that is not the player, so right-clicking it does nothing
     * and asking again next tick would do nothing for ever.</p>
     */
    public static EquipmentSlot slotFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null || !equippable.swappable()) {
            return null;
        }
        EquipmentSlot slot = equippable.slot();
        return ARMOR_SLOTS.contains(slot) ? slot : null;
    }

    /** What the stack is worth in the slot it belongs to; an empty slot is worth nothing. */
    public static double protection(ItemStack stack, EquipmentSlot slot) {
        if (stack == null || stack.isEmpty() || slot == null) {
            return 0;
        }
        double[] armor = {0};
        double[] toughness = {0};
        // forEachModifier, not the ATTRIBUTE_MODIFIERS component: the component is what the item
        // was given, and this is what it grants here, enchantments and all.
        stack.forEachModifier(slot, (attribute, modifier) -> {
            if (modifier.operation() != AttributeModifier.Operation.ADD_VALUE) {
                return;
            }
            if (attribute.value() == Attributes.ARMOR.value()) {
                armor[0] += modifier.amount();
            } else if (attribute.value() == Attributes.ARMOR_TOUGHNESS.value()) {
                toughness[0] += modifier.amount();
            }
        });
        return score(armor[0], toughness[0], stack.isEnchanted());
    }

    /**
     * The comparison itself, with the stack taken out of it so it can be read and tested.
     *
     * <p>Armour points decide it. Toughness only separates pieces that shield the same amount -
     * netherite and diamond are both three points on the head, and the netherite is the better
     * helmet - and being enchanted only separates two that are otherwise the same piece, because
     * Protection is not an attribute and no amount of arithmetic here will find it.</p>
     */
    public static double score(double armor, double toughness, boolean enchanted) {
        return armor * 100 + toughness * 10 + (enchanted ? 1 : 0);
    }

    /** What the player is wearing in a slot is worth, for comparing a carried piece against. */
    public static double wornProtection(Player player, EquipmentSlot slot) {
        return protection(player.getItemBySlot(slot), slot);
    }

    /**
     * The best carried piece for one slot, or {@code null} when nothing carried fits it.
     *
     * <p>Only a piece that beats what is already worn is returned. Returning an equal one would
     * ask the game to swap a helmet for the same helmet, which {@code swapWithEquipmentSlot}
     * refuses outright ({@code isSameItemSameComponents}), so the card would spend for ever
     * putting on a hat it is already wearing.</p>
     */
    public static ItemStack upgradeFor(Player player, EquipmentSlot slot) {
        double best = wornProtection(player, slot);
        ItemStack found = null;
        Inventory inventory = player.getInventory();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index);
            if (slotFor(stack) != slot) {
                continue;
            }
            double candidate = protection(stack, slot);
            if (candidate > best) {
                best = candidate;
                found = stack;
            }
        }
        return found;
    }

    /** Whether this exact stack - not merely one like it - is in the main hand. */
    public static boolean holding(Player player, ItemStack stack) {
        Inventory inventory = player.getInventory();
        return inventory.getItem(inventory.getSelectedSlot()) == stack;
    }

    /**
     * Right-clicks whatever is in the hand, which is how a held piece of armour goes on.
     *
     * <p>{@code useItem} rather than {@code useItemOn}: what the bot happens to be looking at is
     * not part of this, and a crafting table in front of it must not swallow the gesture.</p>
     */
    public static void putOnHeld(BotContext ctx) {
        ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
    }
}
