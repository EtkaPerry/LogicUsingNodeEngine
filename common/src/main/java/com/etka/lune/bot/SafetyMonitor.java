package com.etka.lune.bot;

import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.config.BotConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The conditions that halt the bot regardless of what it is doing.
 * <p>
 * Checked by {@link BotEngine} before every task tick, so every task gets them for free rather than
 * each one re-implementing "stop if I'm about to die". An AFK bot is unattended by definition -
 * these are what stop a long session ending in a lost inventory.
 */
public final class SafetyMonitor {

    /** How recently the player must have been hit for it to still count as "under attack". */
    private static final int ATTACK_MEMORY_TICKS = 100;

    /**
     * How far above the stop limit hunger may fall before the bot eats. Waiting until the limit
     * itself would mean eating on the same tick the stop is decided, which is a race the bot only
     * has to lose once - and eating three points early costs one extra steak.
     */
    private static final int MEAL_MARGIN = 3;

    public enum Trigger {
        NONE(""),
        LOW_HEALTH("health dropped below the limit"),
        NO_FOOD("out of food"),
        ATTACKED_BY_PLAYER("attacked by another player"),
        INVENTORY_FULL("inventory is full");

        private final String reason;

        Trigger(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }

        public boolean isTriggered() {
            return this != NONE;
        }
    }

    private SafetyMonitor() {}

    public static Trigger check(BotContext ctx) {
        return check(ctx, false);
    }

    /**
     * Checks safety while allowing a task that explicitly owns food recovery to make progress.
     * Health, hostile-player, and inventory safeguards are never bypassed.
     */
    public static Trigger check(BotContext ctx, boolean foodRecoveryInProgress) {
        BotConfig config = ctx.config;

        // Ordered by how urgent recovery is: dying beats being robbed beats being hungry.
        if (config.stopBelowHealth > 0 && ctx.player.getHealth() <= config.stopBelowHealth) {
            return Trigger.LOW_HEALTH;
        }
        if (config.stopWhenAttackedByPlayer && attackedByPlayerRecently(ctx)) {
            return Trigger.ATTACKED_BY_PLAYER;
        }
        // "Out of food" has to mean out of food. Hunger alone stopped runs that were carrying a
        // full stack of steak - the bot announced that it was starving, with the answer in its own
        // bag, and a twelve-minute job ended after two. Being hungry is something the bot can fix;
        // the stop is for when it cannot.
        if (!foodRecoveryInProgress && config.stopBelowFood > 0
                && ctx.player.getFoodData().getFoodLevel() <= config.stopBelowFood
                && !carriesFood(ctx)) {
            return Trigger.NO_FOOD;
        }
        if (config.stopWhenFull && ctx.player.getInventory().getFreeSlot() < 0) {
            return Trigger.INVENTORY_FULL;
        }
        return Trigger.NONE;
    }

    /** True when the bot is carrying something it could eat. */
    public static boolean carriesFood(BotContext ctx) {
        return InventoryHelper.count(ctx.player, stack -> stack.has(DataComponents.FOOD)) > 0;
    }

    /**
     * True when the bot should stop what it is doing and have a meal: hungry enough that the
     * safety limit is in sight, and holding something to fix it with.
     */
    public static boolean shouldEatNow(BotContext ctx) {
        BotConfig config = ctx.config;
        int hungerFloor = Math.max(config.stopBelowFood, 0);
        return ctx.player.getFoodData().getFoodLevel() <= hungerFloor + MEAL_MARGIN
                && carriesFood(ctx);
    }

    /**
     * Only counts other players, and only for a short window - otherwise one hit at the start of a
     * session would keep the bot disabled forever.
     */
    private static boolean attackedByPlayerRecently(BotContext ctx) {
        LivingEntity attacker = ctx.player.getLastHurtByMob();
        if (!(attacker instanceof Player) || attacker == ctx.player) {
            return false;
        }
        int ticksAgo = ctx.player.tickCount - ctx.player.getLastHurtByMobTimestamp();
        return ticksAgo >= 0 && ticksAgo < ATTACK_MEMORY_TICKS;
    }
}
