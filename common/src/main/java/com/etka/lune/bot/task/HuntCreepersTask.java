package com.etka.lune.bot.task;

import com.etka.lune.compat.Mobs;
import net.minecraft.world.item.Items;

/** Dedicated Creeper hunter; the general Kill command remains available separately. */
public final class HuntCreepersTask extends HuntMobTask {

    public HuntCreepersTask(int wanted) {
        this(wanted, new KillOptions(false, true, false,
                KillOptions.EndermanSafety.DIRECT,
                KillOptions.WeaponPreference.SWORD, false));
    }

    public HuntCreepersTask(int wanted, KillOptions options) {
        super(Mobs.CREEPER, Items.GUNPOWDER, "Creepers", wanted,
                options);
    }
}
