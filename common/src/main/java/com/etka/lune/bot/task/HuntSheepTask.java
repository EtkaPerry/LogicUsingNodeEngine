package com.etka.lune.bot.task;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;

/** Dedicated sheep hunter that counts every wool colour, not just white wool. */
public final class HuntSheepTask extends HuntMobTask {

    public HuntSheepTask(int wanted, KillOptions options) {
        super(EntityType.SHEEP, stack -> stack.is(ItemTags.WOOL), "wool", "Sheep", wanted, options);
    }
}
