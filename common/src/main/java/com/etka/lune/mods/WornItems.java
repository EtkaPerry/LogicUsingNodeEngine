package com.etka.lune.mods;

import com.etka.lune.Constants;
import com.etka.lune.bot.BotContext;
import com.etka.lune.util.Lang;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * What the player is wearing, including the slots vanilla does not have.
 *
 * <p>Curios on NeoForge and Forge, and Trinkets on Fabric, add rings, charms, belts and a back
 * slot that the player's inventory knows nothing about. An elytra in a Curios back slot flies
 * exactly like one in the chest slot, and a backpack worn there is still a backpack; a check that
 * only reads the vanilla slots would call both absent. This asks the armour slots and then
 * whichever accessory mod is installed.</p>
 *
 * <p>Reading is still all this class does. Putting something <em>into</em> an accessory slot is
 * the Equip card's job and goes through {@link AccessoryHook}, which drives the mod the way a
 * player drives it - the mod's own screen, opened by the mod's own request, and vanilla's
 * shift-click into whichever slot the mod says will take it. Nothing here writes a slot directly,
 * and nothing removes an accessory.</p>
 */
public final class WornItems {

    private static final List<AccessoryHook> HOOKS = List.of(new CuriosHook(), new TrinketsHook());

    private WornItems() {}

    /** The accessory mod that is installed and answering, or null when there is none. */
    public static AccessoryHook hook() {
        for (AccessoryHook hook : HOOKS) {
            if (hook.ready()) {
                return hook;
            }
        }
        return null;
    }

    /** Every accessory-slot stack, from whichever mod holds them. Empty without such a mod. */
    public static List<ItemStack> accessories(Player player) {
        AccessoryHook hook = hook();
        return hook == null ? List.of() : hook.stacks(player);
    }

    /** The four armour pieces plus every accessory, skipping empty slots. */
    public static List<ItemStack> worn(Player player) {
        List<ItemStack> out = new ArrayList<>();
        for (EquipmentSlot slot : ARMOUR) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        out.addAll(accessories(player));
        return out;
    }

    private static final EquipmentSlot[] ARMOUR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Accessory slots only: the ones the player's inventory does not already count. */
    public static int countAccessories(Player player, Predicate<ItemStack> match) {
        int total = 0;
        for (ItemStack stack : accessories(player)) {
            if (match.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static int count(Player player, Predicate<ItemStack> match) {
        int total = 0;
        for (ItemStack stack : worn(player)) {
            if (match.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean anyMatch(Player player, Predicate<ItemStack> match) {
        for (ItemStack stack : worn(player)) {
            if (match.test(stack)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isWearing(Player player, Item item) {
        return anyMatch(player, stack -> stack.is(item));
    }

    /**
     * One accessory mod: what is in its slots, and how something gets into one.
     *
     * <p>The two mods are put on in genuinely different ways, and the card asks {@link #usesMenu()}
     * which one it is looking at rather than guessing. Curios keeps its slots in a screen of its
     * own, so the card opens that screen with the mod's own request and shift-clicks into it, the
     * same bargain the backpack cards keep. Trinkets is driven by using the item instead: its
     * accessories equip from the hand, which needs no class of its own and so needs no name Lune
     * has not read out of a jar.</p>
     */
    public abstract static class AccessoryHook extends ModHook {

        /** Key of the mod's name in the language file. */
        protected abstract String labelKey();

        public final String label() {
            return Lang.get(labelKey());
        }

        /** What is in its slots right now. */
        public abstract List<ItemStack> stacks(Player player);

        /**
         * Whether the mod has a slot shaped for this stack. A mod that cannot be asked answers
         * yes and lets the attempt decide, which is the same answer it would have given by
         * failing a moment later.
         */
        public boolean fits(Player player, ItemStack stack) {
            return true;
        }

        /** Whether its slots live in a screen that has to be opened before anything can move. */
        public boolean usesMenu() {
            return false;
        }

        /** Asks the game to open that screen, the way the player's own key would. */
        public boolean open(BotContext ctx) {
            return false;
        }

        /** Whether the menu that is open is that screen. */
        public boolean isMenu(AbstractContainerMenu menu) {
            return false;
        }

        /** Whether one of its slots is an accessory slot rather than the player's own inventory. */
        public boolean isAccessorySlot(Slot slot) {
            return false;
        }
    }

    /**
     * Curios: {@code CuriosApi.getCuriosInventory(entity)} then {@code findCurios(predicate)} to
     * read, and {@code CPacketOpenCurios} - what the button on the inventory screen sends - to
     * open the slots.
     *
     * <p>The screen and the packet are Curios' own internals rather than its API, so they are
     * looked up separately from the names that answer "what are you wearing?". A Curios that has
     * moved them loses the Equip card's accessory half with one line in the log; counting what is
     * worn, which several cards depend on, goes on working.</p>
     */
    static final class CuriosHook extends AccessoryHook {
        private Method inventory;
        private Method findCurios;
        private Method stackOf;
        private Method slotsFor;
        private Constructor<?> openPayload;
        private Class<?> menu;
        private Class<?> curioSlot;

        @Override
        protected String hookName() {
            return "Curios";
        }

        @Override
        protected String labelKey() {
            return "lune.mods.curios";
        }

        @Override
        protected List<String> modIds() {
            return List.of("curios");
        }

        @Override
        protected void resolve() throws ReflectiveOperationException {
            Class<?> api = type("top.theillusivec4.curios.api.CuriosApi");
            inventory = api.getMethod("getCuriosInventory", LivingEntity.class);
            findCurios = type("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler")
                    .getMethod("findCurios", Predicate.class);
            stackOf = type("top.theillusivec4.curios.api.SlotResult").getMethod("stack");
            try {
                slotsFor = api.getMethod("getItemStackSlots", ItemStack.class, LivingEntity.class);
                openPayload = type("top.theillusivec4.curios.common.network.client.CPacketOpenCurios")
                        .getConstructor(ItemStack.class);
                menu = type("top.theillusivec4.curios.common.inventory.container.CuriosMenu");
                curioSlot = type("top.theillusivec4.curios.common.inventory.CurioSlot");
            } catch (ReflectiveOperationException | LinkageError e) {
                openPayload = null;
                Constants.LOG.warn("Curios no longer has the slots screen Lune knows how to open, "
                        + "so Lune will not put accessories on this session: {}", e.toString());
            }
        }

        @Override
        public List<ItemStack> stacks(Player player) {
            return call(() -> {
                Optional<?> handler = (Optional<?>) inventory.invoke(null, player);
                if (handler == null || handler.isEmpty()) {
                    return List.of();
                }
                Predicate<ItemStack> any = stack -> !stack.isEmpty();
                List<?> results = (List<?>) findCurios.invoke(handler.get(), any);
                List<ItemStack> out = new ArrayList<>();
                for (Object result : results) {
                    if (stackOf.invoke(result) instanceof ItemStack stack && !stack.isEmpty()) {
                        out.add(stack);
                    }
                }
                return out;
            }, List.of());
        }

        /** Curios answers which of its slot types would take the stack; no answer means none. */
        @Override
        public boolean fits(Player player, ItemStack stack) {
            if (slotsFor == null) {
                // The half of Curios that answers this is not where it was. Saying "it does not
                // fit" would blame the item for a hook that has gone stale, so the attempt goes
                // ahead and the mod gets to refuse it itself.
                return true;
            }
            return call(() -> slotsFor.invoke(null, stack, player) instanceof Map<?, ?> slots
                    && !slots.isEmpty(), false);
        }

        @Override
        public boolean usesMenu() {
            return openPayload != null;
        }

        @Override
        public boolean open(BotContext ctx) {
            if (openPayload == null) {
                return false;
            }
            return call(() -> {
                if (ctx.mc.getConnection() == null) {
                    return false;
                }
                // The carried stack is what the screen would open holding: nothing, here.
                CustomPacketPayload payload =
                        (CustomPacketPayload) openPayload.newInstance(ItemStack.EMPTY);
                ctx.mc.getConnection().send(new ServerboundCustomPayloadPacket(payload));
                return true;
            }, false);
        }

        @Override
        public boolean isMenu(AbstractContainerMenu open) {
            return menu != null && open != null && menu.isInstance(open);
        }

        @Override
        public boolean isAccessorySlot(Slot slot) {
            return curioSlot != null && slot != null && curioSlot.isInstance(slot);
        }
    }

    /**
     * Trinkets, in either of its two shapes: the fork that carries it past 26.1 lives in
     * {@code eu.pb4} and hands out an attachment, the original in {@code dev.emi} hands out an
     * optional component. Both iterate the same way, with a two-argument {@code forEach}.
     *
     * <p>Its accessories go on by being used, the way they do in the player's own hand, so there
     * is no screen to open and nothing of the mod to name for it. A trinket that does not equip
     * that way does not equip for the player either, and the card says so rather than reaching
     * into the mod for a slot the player could not have reached themselves.</p>
     */
    static final class TrinketsHook extends AccessoryHook {
        private Method holder;
        private boolean optionalHolder;
        private Method forEach;

        @Override
        protected String hookName() {
            return "Trinkets";
        }

        @Override
        protected String labelKey() {
            return "lune.mods.trinkets";
        }

        @Override
        protected List<String> modIds() {
            return List.of("trinkets", "trinkets_updated");
        }

        @Override
        protected void resolve() throws ReflectiveOperationException {
            try {
                holder = type("eu.pb4.trinkets.api.TrinketsApi").getMethod("getAttachment", LivingEntity.class);
                forEach = type("eu.pb4.trinkets.api.TrinketAttachment").getMethod("forEach", BiConsumer.class);
                optionalHolder = false;
            } catch (ClassNotFoundException fork) {
                holder = type("dev.emi.trinkets.api.TrinketsApi").getMethod("getTrinketComponent", LivingEntity.class);
                forEach = type("dev.emi.trinkets.api.TrinketComponent").getMethod("forEach", BiConsumer.class);
                optionalHolder = true;
            }
        }

        @Override
        public List<ItemStack> stacks(Player player) {
            return call(() -> {
                Object component = holder.invoke(null, player);
                if (optionalHolder) {
                    component = component instanceof Optional<?> optional ? optional.orElse(null) : null;
                }
                if (component == null) {
                    return List.of();
                }
                List<ItemStack> out = new ArrayList<>();
                BiConsumer<Object, Object> collect = (slot, stack) -> {
                    if (stack instanceof ItemStack found && !found.isEmpty()) {
                        out.add(found);
                    }
                };
                forEach.invoke(component, collect);
                return out;
            }, List.of());
        }
    }
}
