package com.etka.lune.mods;

import com.etka.lune.bot.BotContext;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * Sophisticated Backpacks.
 *
 * <p>Its open-backpack key sends a payload with no arguments and the server finds the first
 * backpack the player has - the Curios back slot, the chest slot, the offhand, then the
 * inventory - so that one payload covers every way of carrying one. The compartment's slots are
 * {@code StorageInventorySlot}s; the upgrade slots beside them are not, and are left alone.</p>
 */
final class SophisticatedBackpacksHook extends BackpackHook {

    private Class<?> item;
    private Class<?> menu;
    private Class<?> storageSlot;
    private Constructor<?> openPayload;

    @Override
    protected String hookName() {
        return "Sophisticated Backpacks";
    }

    @Override
    protected String labelKey() {
        return "lune.mods.sophisticated_backpacks";
    }

    @Override
    protected List<String> modIds() {
        return List.of("sophisticatedbackpacks");
    }

    @Override
    protected void resolve() throws ReflectiveOperationException {
        item = type("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem");
        menu = type("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer");
        storageSlot = type("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot");
        openPayload = type("net.p3pp3rf1y.sophisticatedbackpacks.network.BackpackOpenPayload")
                .getConstructor();
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

    @Override
    public Opening open(BotContext ctx) {
        return call(() -> {
            if (ctx.mc.getConnection() == null) {
                return Opening.NONE;
            }
            CustomPacketPayload payload = (CustomPacketPayload) openPayload.newInstance();
            ctx.mc.getConnection().send(new ServerboundCustomPayloadPacket(payload));
            return Opening.SENT;
        }, Opening.NONE);
    }
}
