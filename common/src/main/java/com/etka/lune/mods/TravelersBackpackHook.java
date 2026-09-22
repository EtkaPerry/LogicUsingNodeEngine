package com.etka.lune.mods;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * Traveler's Backpack.
 *
 * <p>A worn one is not an item in any slot the client can list - it is an attachment - so it is
 * opened the way the mod's own key opens it, with an action-tag payload whose action is
 * {@code OPEN_SCREEN}. One carried in the inventory is opened the way a player opens it: held
 * and used. When the server is configured to allow only worn backpacks, using it wears it
 * instead, and the next attempt then finds it worn.</p>
 *
 * <p>The compartment's slots are {@code BackpackSlotItemHandler}s. The tool slots, the crafting
 * grid, the tank slots and the upgrade slots are other classes, and none of them is touched.</p>
 */
final class TravelersBackpackHook extends BackpackHook {

    /** {@code ServerboundActionTagPacket.OPEN_SCREEN}: open the backpack being worn. */
    private static final int OPEN_SCREEN = 1;

    private Class<?> item;
    private Class<?> menu;
    private Class<?> storageSlot;
    private Constructor<?> actionPacket;

    @Override
    protected String hookName() {
        return "Traveler's Backpack";
    }

    @Override
    protected String labelKey() {
        return "lune.mods.travelers_backpack";
    }

    @Override
    protected List<String> modIds() {
        return List.of("travelersbackpack");
    }

    @Override
    protected void resolve() throws ReflectiveOperationException {
        item = type("com.tiviacz.travelersbackpack.item.TravelersBackpackItem");
        menu = type("com.tiviacz.travelersbackpack.inventory.menu.AbstractBackpackMenu");
        storageSlot = type("com.tiviacz.travelersbackpack.inventory.menu.slot.BackpackSlotItemHandler");
        actionPacket = type("com.tiviacz.travelersbackpack.network.ServerboundActionTagPacket")
                .getConstructor(CompoundTag.class);
    }

    @Override
    public boolean isBackpack(ItemStack stack) {
        return ready() && stack != null && !stack.isEmpty() && item.isInstance(stack.getItem());
    }

    @Override
    public boolean isBackpackMenu(AbstractContainerMenu menu) {
        return ready() && menu != null && this.menu.isInstance(menu);
    }

    @Override
    public boolean isStorageSlot(Slot slot) {
        return ready() && slot != null && storageSlot.isInstance(slot);
    }

    /** A worn one cannot be seen from here, so an installed mod is taken on trust. */
    @Override
    public boolean carried(Player player) {
        return ready();
    }

    @Override
    public Opening open(BotContext ctx) {
        if (isBackpack(ctx.player.getMainHandItem())) {
            ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
            return Opening.SENT;
        }
        if (InventoryHelper.anyMatch(ctx.player, this::isBackpack)) {
            // Into the hand now, used on a later tick: the hand change reaches the server on the
            // next tick, and a use sent before it would use whatever was held before.
            return InventoryHelper.equip(ctx, this::isBackpack) >= 0 ? Opening.EQUIPPING : Opening.NONE;
        }
        return call(() -> {
            if (ctx.mc.getConnection() == null) {
                return Opening.NONE;
            }
            CompoundTag action = new CompoundTag();
            action.putInt("ActionType", OPEN_SCREEN);
            CustomPacketPayload payload = (CustomPacketPayload) actionPacket.newInstance(action);
            ctx.mc.getConnection().send(new ServerboundCustomPayloadPacket(payload));
            return Opening.SENT;
        }, Opening.NONE);
    }
}
