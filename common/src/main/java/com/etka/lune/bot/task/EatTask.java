package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Eats available food until the player's hunger is above the threshold.
 *
 * <p><b>Eating is the use key held down, not a packet.</b> Sending the interaction directly looks
 * like it works - the client accepts it and the animation can even start - and then nothing is ever
 * consumed, because vanilla ends it again on the very next tick:</p>
 *
 * <pre>
 * if (this.player.isUsingItem() &amp;&amp; !this.options.keyUse.isDown()) {
 *     this.gameMode.releaseUsingItem(this.player);   // Minecraft.handleKeybinds
 * }
 * </pre>
 *
 * <p>A consume takes 32 ticks of <em>continuously held</em> use; a bot that never holds a key gets
 * released after one. The failure is silent and expensive: the food stays in the bag, hunger keeps
 * falling, health stops regenerating, and the only symptom is an animation that "goes missing". One
 * recorded run made forty-four attempts, consumed nothing, dropped from twenty hunger to four, and
 * interrupted its own route every ninety-five ticks doing it.</p>
 *
 * <p>So this holds {@code options.keyUse} instead, and lets vanilla run the interaction it already
 * knows how to run - the same reasoning as the movement layer driving {@code player.input} rather
 * than assigning key presses. Vanilla starts the use itself while the key is down, repeats it for
 * the next item, and applies the server's result. Releasing the key on every exit path is
 * load-bearing: a use key left held is a bot right-clicking the world forever.</p>
 */
public final class EatTask implements Task {

    private static final int HUNGRY_THRESHOLD = 14;
    /**
     * Ticks between selecting the food and holding use. {@link InventoryHelper#equip} changes the
     * slot locally; the server only learns about it from the carried-item packet the game sends on
     * its own tick. Using the item in the same tick is answered with whatever the server still
     * thinks is in hand.
     */
    private static final int SLOT_SYNC_TICKS = 2;
    /** Ticks the key may be held without vanilla starting a use before this is called broken. */
    private static final int START_TIMEOUT = 20;
    /** Ticks of holding without the hunger bar moving; a little over two full consumes. */
    private static final int NO_PROGRESS_TIMEOUT = 80;

    private final Predicate<ItemStack> filter;
    private final int minimumFood;

    private int settleTicks;
    private int startTicks;
    private int noProgressTicks;
    private int lastFoodLevel = -1;
    private boolean holdingUse;
    private final StatusText status = new StatusText();

    public EatTask() {
        this(stack -> true, HUNGRY_THRESHOLD);
    }

    public EatTask(Predicate<ItemStack> filter, int minimumFood) {
        this.filter = filter;
        this.minimumFood = minimumFood;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.eat.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Eat");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        settleTicks = 0;
        startTicks = 0;
        noProgressTicks = 0;
        holdingUse = false;
        lastFoodLevel = ctx.player.getFoodData().getFoodLevel();
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        Player player = ctx.player;
        int food = player.getFoodData().getFoodLevel();

        if (food >= minimumFood) {
            releaseUse(ctx);
            status.set("lune.status.eat.food_level_fine", food);
            return TaskStatus.SUCCESS;
        }

        if (food > lastFoodLevel) {
            // Something was eaten. Keep going if still hungry, but restart the patience budget so a
            // slow second item is not judged by the first one's clock.
            lastFoodLevel = food;
            noProgressTicks = 0;
            ctx.debug.foodEaten++;
        }

        // Vanilla cancels a use the moment the player is submerged, so holding the key underwater
        // burns the whole budget. Let the route reach dry ground and try there.
        if (player.isInWater()) {
            releaseUse(ctx);
            status.set("lune.status.eat.cannot_eat_while_swimming_continue_dry");
            return TaskStatus.FAILED;
        }

        // A container screen suppresses handleKeybinds entirely, so no held key would be read.
        if (ctx.mc.screen != null) {
            releaseUse(ctx);
            status.set("lune.status.eat.cannot_eat_with_screen_open");
            return TaskStatus.FAILED;
        }

        if (!holdingFood(player)) {
            releaseUse(ctx);
            if (InventoryHelper.equip(ctx, this::edible) < 0) {
                status.set("lune.status.eat.no_food");
                return TaskStatus.FAILED;
            }
            settleTicks = 0;
            startTicks = 0;
            status.set("lune.status.eat.taking_out", heldName(player));
            return TaskStatus.RUNNING;
        }

        if (settleTicks < SLOT_SYNC_TICKS) {
            settleTicks++;
            status.set("lune.status.eat.waiting_server_see_food");
            return TaskStatus.RUNNING;
        }

        holdUse(ctx);

        if (!player.isUsingItem()) {
            if (++startTicks > START_TIMEOUT) {
                releaseUse(ctx);
                status.set("lune.status.eat.could_not_start_eating", heldName(player));
                return TaskStatus.FAILED;
            }
            status.set("lune.status.eat.starting_eat", heldName(player));
            return TaskStatus.RUNNING;
        }

        startTicks = 0;
        if (++noProgressTicks > NO_PROGRESS_TIMEOUT) {
            releaseUse(ctx);
            status.set("lune.status.eat.held_use_key_but_hunger_never_moved");
            return TaskStatus.FAILED;
        }
        status.set("lune.status.eat.eating", heldName(player), food, minimumFood);
        return TaskStatus.RUNNING;
    }

    @Override
    public void onPause(BotContext ctx) {
        releaseUse(ctx);
    }

    @Override
    public void onStop(BotContext ctx) {
        releaseUse(ctx);
        ctx.input.reset();
    }

    private boolean edible(ItemStack stack) {
        return stack.has(DataComponents.FOOD) && filter.test(stack);
    }

    private boolean holdingFood(Player player) {
        return edible(player.getItemInHand(InteractionHand.MAIN_HAND));
    }

    private static String heldName(Player player) {
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        return stack.isEmpty() ? "nothing" : stack.getItem().getName(stack).getString();
    }

    private void holdUse(BotContext ctx) {
        if (!holdingUse) {
            holdingUse = true;
        }
        // Set every tick: the key is a shared global that anything else may have cleared.
        ctx.mc.options.keyUse.setDown(true);
    }

    private void releaseUse(BotContext ctx) {
        if (!holdingUse) {
            return;
        }
        holdingUse = false;
        ctx.mc.options.keyUse.setDown(false);
        if (ctx.player.isUsingItem()) {
            ctx.gameMode.releaseUsingItem(ctx.player);
        }
    }
}
