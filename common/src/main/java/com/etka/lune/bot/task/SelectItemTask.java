package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Puts a chosen item in the player's hand and keeps an eye on whether it is still fit to use.
 *
 * <p>Picking "a diamond pickaxe" is rarely what someone means. They mean <em>the enchanted one</em>,
 * because the point of the next step is Silk Touch, or they mean the worn one because the point is
 * to use it up before it is lost. An item id alone cannot say that, so this filters on enchantment
 * and on remaining durability, and chooses between the survivors deliberately.</p>
 *
 * <p>Success means the hand now holds something that passes every filter. Fail means it does not,
 * and the two reasons are worth separating in the status line: the item ran out or broke, or it is
 * still there but worn past the floor. Both are the same signal to the graph - take the Fail branch
 * and go fetch a replacement - which is why the durability floor fails rather than succeeds. A
 * Success that means "I am holding something too broken for the job" would send the run onwards
 * into exactly the work that is about to lose the tool.</p>
 *
 * <p>Every run re-checks from scratch, so setting the card to x∞ turns it into a watch: it keeps
 * the item in hand and takes the Fail branch the moment that stops being possible.</p>
 */
public final class SelectItemTask implements Task {

    /** Which copies of the item count, when several are carried. */
    public enum Enchanting {
        ANY("Any"),
        ENCHANTED("Enchanted only"),
        PLAIN("Unenchanted only");

        private final String label;

        Enchanting(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Enchanting fromLabel(String label) {
            for (Enchanting value : values()) {
                if (value.label.equalsIgnoreCase(label)) {
                    return value;
                }
            }
            return ANY;
        }

        private boolean accepts(ItemStack stack) {
            return switch (this) {
                case ENCHANTED -> stack.isEnchanted();
                case PLAIN -> !stack.isEnchanted();
                case ANY -> true;
            };
        }
    }

    /** Which survivor to hold, when several pass the filters. */
    public enum Preference {
        MOST_DURABLE("Most durability"),
        LEAST_DURABLE("Least durability");

        private final String label;

        Preference(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Preference fromLabel(String label) {
            for (Preference value : values()) {
                if (value.label.equalsIgnoreCase(label)) {
                    return value;
                }
            }
            return MOST_DURABLE;
        }
    }

    /** Which hand to put it in. */
    public enum Hand {
        MAIN("Main hand"),
        OFF("Off hand");

        private final String label;

        Hand(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Hand fromLabel(String label) {
            return OFF.label.equalsIgnoreCase(label) ? OFF : MAIN;
        }
    }

    private final Item item;
    private final Enchanting enchanting;
    private final Hand hand;
    private final int minDurabilityPercent;
    private final Preference preference;

    private String status = "";
    private boolean moved;

    public SelectItemTask(Item item, Enchanting enchanting, Hand hand, int minDurabilityPercent,
                          Preference preference) {
        this.item = item;
        this.enchanting = enchanting == null ? Enchanting.ANY : enchanting;
        this.hand = hand == null ? Hand.MAIN : hand;
        this.minDurabilityPercent = Math.clamp(minDurabilityPercent, 0, 100);
        this.preference = preference == null ? Preference.MOST_DURABLE : preference;
    }

    @Override
    public String name() {
        return "Select " + (item == null ? "Item" : InventoryHelper.itemName(item));
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public boolean madeProgress() {
        // Re-selecting something already in hand is not work. Saying so is what stops an x∞ card
        // from spinning at twenty checks a second instead of polling once a second.
        return moved;
    }

    @Override
    public void onStart(BotContext ctx) {
        moved = false;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (item == null) {
            status = "no item chosen";
            return TaskStatus.FAILED;
        }
        Inventory inventory = ctx.player.getInventory();

        int chosen = -1;
        int chosenPercent = -1;
        int bestRejectedPercent = -1;
        boolean sawItem = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(item) || !enchanting.accepts(stack)) {
                continue;
            }
            sawItem = true;
            int percent = durabilityPercent(stack);
            if (percent < minDurabilityPercent) {
                bestRejectedPercent = Math.max(bestRejectedPercent, percent);
                continue;
            }
            if (chosen < 0 || prefers(percent, chosenPercent)) {
                chosen = slot;
                chosenPercent = percent;
            }
        }

        if (chosen < 0) {
            status = describeMiss(sawItem, bestRejectedPercent);
            return TaskStatus.FAILED;
        }

        // Matched by identity rather than by another predicate: two stacks of the same item can
        // both pass the filters, and re-searching by description could equip the other one.
        ItemStack wanted = inventory.getItem(chosen);
        boolean alreadyHeld = wanted == heldStack(inventory);
        if (!alreadyHeld && !hold(ctx, wanted)) {
            status = "could not move " + InventoryHelper.itemName(item) + " into the "
                    + hand.label().toLowerCase();
            return TaskStatus.FAILED;
        }
        moved = !alreadyHeld;
        status = "holding " + InventoryHelper.itemName(item) + durabilityNote(wanted, chosenPercent);
        if (moved) {
            ctx.debug.decide("selected " + InventoryHelper.itemName(item) + " for the "
                    + hand.label().toLowerCase());
        }
        return TaskStatus.SUCCESS;
    }

    private boolean hold(BotContext ctx, ItemStack wanted) {
        return hand == Hand.OFF
                ? InventoryHelper.equipOffhand(ctx, stack -> stack == wanted)
                : InventoryHelper.equip(ctx, stack -> stack == wanted) >= 0;
    }

    private ItemStack heldStack(Inventory inventory) {
        return hand == Hand.OFF
                ? inventory.getItem(Inventory.SLOT_OFFHAND)
                : inventory.getItem(inventory.getSelectedSlot());
    }

    private boolean prefers(int candidate, int current) {
        return preference == Preference.LEAST_DURABLE ? candidate < current : candidate > current;
    }

    /** Percent of durability left; anything that cannot be damaged, such as food, counts as full. */
    private static int durabilityPercent(ItemStack stack) {
        if (!stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
            return 100;
        }
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue()) * 100 / stack.getMaxDamage();
    }

    private String describeMiss(boolean sawItem, int bestRejectedPercent) {
        String name = InventoryHelper.itemName(item);
        if (!sawItem) {
            return switch (enchanting) {
                case ENCHANTED -> "no enchanted " + name + " left";
                case PLAIN -> "no unenchanted " + name + " left";
                case ANY -> "no " + name + " left";
            };
        }
        return name + " is down to " + bestRejectedPercent + "%, below the "
                + minDurabilityPercent + "% needed";
    }

    private String durabilityNote(ItemStack stack, int percent) {
        return stack.isDamageableItem() ? " (" + percent + "% durability)" : "";
    }
}
