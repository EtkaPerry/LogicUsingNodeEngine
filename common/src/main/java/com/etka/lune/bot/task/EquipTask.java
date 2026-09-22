package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.util.EquipHelper;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.compat.Screens;
import com.etka.lune.mods.WornItems;
import com.etka.lune.util.Lang;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Puts on what the bot is carrying: every armour slot it can improve, or one chosen piece.
 *
 * <p>Wearing was the hole in the middle of the kit. Lune has always mined the iron, smelted it and
 * crafted the set, and then fought in a shirt, because nothing in the tree could reach the armour
 * slots. The card is the missing half of that chain, and it is a card rather than something the
 * engine does when it notices bare shoulders: a task does its job and nothing else, so a run that
 * should be armoured says so on the canvas.</p>
 *
 * <p>Two modes, because they are two questions. <em>Best armour</em> asks "be wearing the best of
 * what you carry", walks the four slots and swaps in anything that beats what is on - the answer
 * after a loot run. <em>A chosen item</em> asks for one named thing: a helmet, an elytra, or a
 * ring, an amulet, a belt when Curios or Trinkets have added somewhere to put it. Success means
 * the thing is on. Fail is the branch for "go and get one", which is why carrying nothing fails
 * rather than quietly succeeding at wearing nothing.</p>
 *
 * <p>Nothing here is a shortcut the player does not have. Armour goes on by being held and
 * right-clicked, so a Curse of Binding helmet refuses Lune exactly as it refuses everyone;
 * accessories go through the accessory mod's own screen with the same shift-click, so a slot that
 * would not take the item does not take it from Lune either.</p>
 */
public final class EquipTask implements Task {

    /** What the card is being asked to put on. */
    public enum What {
        BEST_ARMOR("Best armor"),
        CHOSEN("A chosen item");

        private final String label;

        What(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static What fromLabel(String label) {
            return CHOSEN.label.equalsIgnoreCase(label) ? CHOSEN : BEST_ARMOR;
        }
    }

    /** Ticks a swap is given to reach the server and come back before it is judged. */
    private static final int SETTLE_TICKS = 3;
    /** How long an open request is given before the menu is expected. */
    private static final int OPEN_COOLDOWN = 10;
    /** How many times one piece is offered to a slot before the slot is taken at its word. */
    private static final int MAX_TRIES = 3;
    private static final int MAX_OPEN_ATTEMPTS = 6;

    private final What what;
    private final Item item;

    private final StatusText status = new StatusText();

    private int cooldown;
    private boolean moved;
    private int tries;
    private int openAttempts;
    private Item offered;

    public EquipTask(What what, Item item) {
        this.what = what == null ? What.BEST_ARMOR : what;
        this.item = item;
    }

    @Override
    public String name() {
        return what == What.BEST_ARMOR
                ? Lang.get("lune.task.equip.armor.name")
                : Lang.get("lune.task.equip.item.name", item == null
                        ? Lang.get("lune.param.item.label") : InventoryHelper.itemName(item));
    }

    /**
     * English on purpose: this is the learner's row key and is never shown. It also does not name
     * the item, which is drawn from the language file and would key a row on whatever language
     * the run happened to be played in.
     */
    @Override
    public String learningId() {
        return Task.learningName(what == What.BEST_ARMOR ? "Equip Armor" : "Equip Item");
    }

    /** Dressing is bookkeeping, not a paced job; there is nothing here for the learner to rank. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    /** Checking what is already on is not work. An x∞ card must not spin on a full set. */
    @Override
    public boolean madeProgress() {
        return moved;
    }

    @Override
    public void onStart(BotContext ctx) {
        cooldown = 0;
        moved = false;
        tries = 0;
        openAttempts = 0;
        offered = null;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }
        return what == What.BEST_ARMOR ? bestArmor(ctx) : chosenItem(ctx);
    }

    /** Every slot that can be improved, best piece first, one swap at a time. */
    private TaskStatus bestArmor(BotContext ctx) {
        for (EquipmentSlot slot : EquipHelper.ARMOR_SLOTS) {
            ItemStack upgrade = EquipHelper.upgradeFor(ctx.player, slot);
            if (upgrade == null) {
                continue;
            }
            if (giveUpOn(upgrade.getItem())) {
                status.set("lune.status.equip.would_not_go_on",
                        InventoryHelper.itemName(upgrade.getItem()));
                return TaskStatus.FAILED;
            }
            return putOn(ctx, slot, upgrade);
        }
        if (!anyArmorWorn(ctx)) {
            status.set("lune.status.equip.no_armor_carried");
            return TaskStatus.FAILED;
        }
        status.set("lune.status.equip.wearing_best");
        return TaskStatus.SUCCESS;
    }

    /** One named thing: armour and elytra through the vanilla slots, everything else through a mod. */
    private TaskStatus chosenItem(BotContext ctx) {
        if (item == null) {
            status.set("lune.status.equip.nothing_chosen_to_wear");
            return TaskStatus.FAILED;
        }
        String name = InventoryHelper.itemName(item);
        if (WornItems.isWearing(ctx.player, item)) {
            closeMenu(ctx);
            status.set(moved ? "lune.status.equip.now_wearing" : "lune.status.equip.already_wearing",
                    name);
            return TaskStatus.SUCCESS;
        }

        int slotIndex = InventoryHelper.findSlot(ctx.player, stack -> stack.is(item));
        if (slotIndex < 0) {
            closeMenu(ctx);
            // Place Block's line, because it is the same sentence: one line, one key.
            status.set("lune.status.place_block.not_carrying", name);
            return TaskStatus.FAILED;
        }
        ItemStack carried = ctx.player.getInventory().getItem(slotIndex);

        EquipmentSlot slot = EquipHelper.slotFor(carried);
        if (slot != null) {
            if (giveUpOn(item)) {
                status.set("lune.status.equip.would_not_go_on", name);
                return TaskStatus.FAILED;
            }
            return putOn(ctx, slot, carried);
        }

        WornItems.AccessoryHook hook = WornItems.hook();
        if (hook == null) {
            // Nothing in the game has a slot for it: not armour, and no mod that adds slots.
            status.set("lune.status.equip.cannot_be_worn", name);
            return TaskStatus.FAILED;
        }
        if (!hook.fits(ctx.player, carried)) {
            status.set("lune.status.equip.no_slot_for_it", name, hook.label());
            return TaskStatus.FAILED;
        }
        return hook.usesMenu() ? throughMenu(ctx, hook, name) : byUsingIt(ctx, name);
    }

    /**
     * Holds the piece, then right-clicks it, one tick apart.
     *
     * <p>The two halves are separate ticks because the hand change is a packet of its own: the
     * server has to have moved the piece into the hand before it is told the hand was used. The
     * copy being reached for is matched on what it is worth rather than on its slot, so a second
     * hand-off cannot pick up the plain helmet when the enchanted one was chosen.</p>
     */
    private TaskStatus putOn(BotContext ctx, EquipmentSlot slot, ItemStack wanted) {
        Item target = wanted.getItem();
        double worth = EquipHelper.protection(wanted, slot);
        Predicate<ItemStack> asGood = stack ->
                stack.is(target) && EquipHelper.protection(stack, slot) >= worth;

        String name = InventoryHelper.itemName(target);
        status.set("lune.status.equip.putting_on", name);

        if (!asGood.test(ctx.player.getMainHandItem())) {
            if (InventoryHelper.equip(ctx, asGood) < 0) {
                status.set("lune.status.equip.could_not_hold", name);
                return TaskStatus.FAILED;
            }
            cooldown = SETTLE_TICKS;
            return TaskStatus.RUNNING;
        }

        count(target);
        moved = true;
        EquipHelper.putOnHeld(ctx);
        ctx.debug.decide("putting on " + target);
        cooldown = SETTLE_TICKS;
        return TaskStatus.RUNNING;
    }

    /** Curios: open the slots the way its own button does, then shift-click the piece across. */
    private TaskStatus throughMenu(BotContext ctx, WornItems.AccessoryHook hook, String name) {
        AbstractContainerMenu menu = ctx.player.containerMenu;
        if (!hook.isMenu(menu)) {
            if (openAttempts >= MAX_OPEN_ATTEMPTS) {
                status.set("lune.status.equip.could_not_open_slots", hook.label());
                return TaskStatus.FAILED;
            }
            if (menu != null && menu != ctx.player.inventoryMenu) {
                // Something else is open and the game will not open a second menu over it.
                closeMenu(ctx);
                cooldown = SETTLE_TICKS;
                return TaskStatus.RUNNING;
            }
            openAttempts++;
            if (!hook.open(ctx)) {
                status.set("lune.status.equip.could_not_open_slots", hook.label());
                return TaskStatus.FAILED;
            }
            status.set("lune.status.equip.opening_slots", hook.label());
            cooldown = OPEN_COOLDOWN;
            return TaskStatus.RUNNING;
        }

        Slot source = null;
        for (Slot slot : menu.slots) {
            if (slot.container == ctx.player.getInventory() && slot.getItem().is(item)) {
                source = slot;
                break;
            }
        }
        if (source == null) {
            closeMenu(ctx);
            // Place Block's line, because it is the same sentence: one line, one key.
            status.set("lune.status.place_block.not_carrying", name);
            return TaskStatus.FAILED;
        }
        if (giveUpOn(item)) {
            // The click keeps landing back where it came from: every slot shaped for it is taken.
            closeMenu(ctx);
            status.set("lune.status.equip.no_free_slot", name, hook.label());
            return TaskStatus.FAILED;
        }

        count(item);
        moved = true;
        status.set("lune.status.equip.putting_on", name);
        ctx.gameMode.handleContainerInput(menu.containerId, source.index, 0,
                ContainerInput.QUICK_MOVE, ctx.player);
        cooldown = SETTLE_TICKS;
        return TaskStatus.RUNNING;
    }

    /** Trinkets: its accessories go on from the hand, the same gesture as a helmet. */
    private TaskStatus byUsingIt(BotContext ctx, String name) {
        status.set("lune.status.equip.putting_on", name);
        if (!ctx.player.getMainHandItem().is(item)) {
            if (InventoryHelper.equip(ctx, stack -> stack.is(item)) < 0) {
                status.set("lune.status.equip.could_not_hold", name);
                return TaskStatus.FAILED;
            }
            cooldown = SETTLE_TICKS;
            return TaskStatus.RUNNING;
        }
        if (giveUpOn(item)) {
            status.set("lune.status.equip.would_not_go_on", name);
            return TaskStatus.FAILED;
        }

        count(item);
        moved = true;
        EquipHelper.putOnHeld(ctx);
        cooldown = SETTLE_TICKS;
        return TaskStatus.RUNNING;
    }

    /**
     * Whether this piece has been offered to its slot as often as it is going to be.
     *
     * <p>The count is kept per piece rather than per card: putting on four pieces in a row is four
     * attempts at four different slots, and a shared counter would call the boots a failure
     * because the helmet, the chestplate and the leggings went on first.</p>
     */
    private boolean giveUpOn(Item candidate) {
        return candidate.equals(offered) && tries >= MAX_TRIES;
    }

    private void count(Item candidate) {
        if (!candidate.equals(offered)) {
            offered = candidate;
            tries = 0;
        }
        tries++;
    }

    private boolean anyArmorWorn(BotContext ctx) {
        for (EquipmentSlot slot : EquipHelper.ARMOR_SLOTS) {
            if (!ctx.player.getItemBySlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void closeMenu(BotContext ctx) {
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            Screens.open(ctx.mc, null);
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        closeMenu(ctx);
    }
}
