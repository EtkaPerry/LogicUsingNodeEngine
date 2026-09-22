package com.etka.lune.bot.task;

import com.etka.lune.compat.Mobs;
import net.minecraft.world.item.Items;

/** Dedicated Skeleton hunter with configurable shield and weapon preparation. */
public final class HuntSkeletonsTask extends HuntMobTask {

    public HuntSkeletonsTask(int wanted) {
        this(wanted, new KillOptions(false, true, false,
                KillOptions.EndermanSafety.DIRECT,
                KillOptions.WeaponPreference.SWORD, false));
    }

    public HuntSkeletonsTask(int wanted, KillOptions options) {
        super(Mobs.SKELETON, Items.BONE, "Skeletons", wanted, options);
    }
}
