package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * A number a Check Player card can read off the bot and compare against a threshold.
 *
 * <p>The card asked three questions for a long time - health, hunger, air - and the conditionals
 * players actually reach for were the ones it could not ask: "have I room for what I am about to
 * mine?", "is this pickaxe about to break?", "is it dark enough here for something to spawn?".
 * Each of those is one reading, so they are one list rather than a card apiece.</p>
 *
 * <p>The reading lives beside the label on purpose. The dropdown is built from {@link #labels()}
 * and the card looks the value back up with {@link #fromLabel}, so an option that nothing can read
 * cannot be offered, which is what a separate list and a separate switch used to allow.</p>
 *
 * <p>The labels are identifiers written into saved tasks, so they stay English and only their
 * rendering is translated - the same rule every dropdown value here follows.</p>
 */
public enum PlayerMetric {

    HEALTH("Health", ctx -> ctx.player.getHealth()),
    HUNGER("Hunger", ctx -> ctx.player.getFoodData().getFoodLevel()),
    AIR("Air", ctx -> ctx.player.getAirSupply()),
    XP_LEVEL("XP level", ctx -> ctx.player.experienceLevel),
    TOOL_DURABILITY("Tool durability", ctx -> heldDurabilityPercent(ctx.player)),
    FREE_SLOTS("Free slots", ctx -> InventoryHelper.freeSlots(ctx.player)),
    LIGHT_LEVEL("Light level",
            ctx -> ctx.level.getMaxLocalRawBrightness(ctx.player.blockPosition())),
    NEAREST_PLAYER("Nearest player", PlayerMetric::nearestOtherPlayer);

    /**
     * What {@link #NEAREST_PLAYER} reads when the client knows of no other player.
     *
     * <p>Deliberately further away than any threshold the card can hold, so both halves of the
     * question answer the way somebody alone in a world would expect: "nearest player at most 32"
     * is false, "nearest player at least 64" is true. A distance of zero would have said a player
     * was standing on the bot, and skipping the comparison would have left the card with no
     * branch to take.</p>
     */
    public static final double NOBODY = 99_999.0;

    private final String label;
    private final ToDoubleFunction<BotContext> reading;

    PlayerMetric(String label, ToDoubleFunction<BotContext> reading) {
        this.label = label;
        this.reading = reading;
    }

    public String label() {
        return label;
    }

    /** Names for a picker, in the order they are offered. */
    public static List<String> labels() {
        return Arrays.stream(values()).map(PlayerMetric::label).toList();
    }

    /** Reads back a name from {@link #labels()}; null when a stored task names something else. */
    public static PlayerMetric fromLabel(String label) {
        for (PlayerMetric metric : values()) {
            if (metric.label.equalsIgnoreCase(label)) {
                return metric;
            }
        }
        return null;
    }

    /** What this metric reads right now. */
    public double read(BotContext ctx) {
        return reading.applyAsDouble(ctx);
    }

    /**
     * Percent of durability left, worked out from an item's own two numbers.
     *
     * <p>Split from the stack it came off so the arithmetic can be checked without a live item:
     * a headless test has no item components to build one with.</p>
     */
    public static int durabilityPercent(int maxDamage, int damage) {
        return maxDamage <= 0 ? 100 : Math.max(0, maxDamage - damage) * 100 / maxDamage;
    }

    /**
     * The held item's remaining durability as a percent.
     *
     * <p>A percent rather than a count of hits, because "at most 20" then means the same thing
     * whether the bot is holding wood or netherite, and because it is what Select Item already
     * filters on. Anything that cannot be damaged - a torch, a full stack of cobble, an empty
     * hand - reads as full, by that card's rule: what has no durability bar is not about to
     * break.</p>
     */
    private static double heldDurabilityPercent(Player player) {
        ItemStack held = player.getMainHandItem();
        return held.isDamageableItem()
                ? durabilityPercent(held.getMaxDamage(), held.getDamageValue()) : 100;
    }

    /**
     * Blocks to the closest other player, or {@link #NOBODY} when there is none.
     *
     * <p>Only players the client has been told about, which is every player it could draw. A
     * server does not send players beyond the view distance, so this answers "is somebody near
     * me?" and never "is somebody online?".</p>
     */
    private static double nearestOtherPlayer(BotContext ctx) {
        double nearest = NOBODY;
        for (Player other : ctx.level.players()) {
            if (other == ctx.player || !other.isAlive()) {
                continue;
            }
            nearest = Math.min(nearest, Math.sqrt(ctx.player.distanceToSqr(other)));
        }
        return nearest;
    }
}
