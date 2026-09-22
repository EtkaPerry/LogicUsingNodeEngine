package com.etka.lune.bot.task;

import com.etka.lune.compat.Mobs;
import net.minecraft.tags.ItemTags;

/** Dedicated sheep hunter that counts every wool colour, not just white wool. */
public final class HuntSheepTask extends HuntMobTask {

    public HuntSheepTask(int wanted, KillOptions options) {
        super(Mobs.SHEEP, stack -> stack.is(ItemTags.WOOL), "wool", "Sheep", wanted, options);
    }

    /** Sheep graze; grassland is where they are. */
    @Override
    protected com.etka.lune.bot.knowledge.Need roamNeed() {
        return com.etka.lune.bot.knowledge.Need.FOOD;
    }
}
