package com.etka.lune.bot.task;

import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

/** Dedicated Blaze hunter; the general Kill command remains available separately. */
public final class HuntBlazesTask extends HuntMobTask {

    private static final int MAX_BLAZE_ROAMS = 32;

    public HuntBlazesTask(int wanted) {
        this(wanted, new KillOptions(true, true, false,
                KillOptions.EndermanSafety.DIRECT,
                KillOptions.WeaponPreference.AUTO, false));
    }

    public HuntBlazesTask(int wanted, KillOptions options) {
        super(EntityType.BLAZE, Items.BLAZE_ROD, "Blazes", wanted,
                options);
    }

    public HuntBlazesTask(int wanted, KillOptions options, java.util.Set<Integer> unreachableMobs) {
        super(EntityType.BLAZE, stack -> stack.is(Items.BLAZE_ROD),
                InventoryHelper.itemName(Items.BLAZE_ROD), "Blazes", wanted,
                options, unreachableMobs);
    }

    @Override
    protected int maxRoams() {
        return MAX_BLAZE_ROAMS;
    }
}
