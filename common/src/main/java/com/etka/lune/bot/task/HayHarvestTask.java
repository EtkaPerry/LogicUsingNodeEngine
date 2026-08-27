package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

/**
 * Strips a village's hay into bread.
 *
 * <p>Hay is the best food a speedrun can find and the easiest to miss. A single bale unpacks into
 * nine wheat and three loaves, so a dozen bales off one farm is thirty-six bread - more than a run
 * to the End can eat, gathered in under a minute from blocks standing in the open at ground level.
 * Hunting a cow gives two steaks and costs a chase.</p>
 *
 * <p>The hoe is not decoration. Hay is in {@code mineable/hoe} and a hoe roughly halves the time per
 * bale, which is worth two planks and two sticks over a real haul and not worth it over three bales
 * - {@link VillagePolicy#hoeWorthCrafting} draws that line. Once the field is stripped the hoe is
 * just a stack slot, so it goes when space runs short.</p>
 */
public final class HayHarvestTask implements Task {

    /** How far a bale can be and still be part of "this village's farm". */
    private static final int DEFAULT_RADIUS = 32;
    /** Steps that may fail before the farm is written off. */
    private static final int MAX_STEP_FAILURES = 3;

    private final int radius;

    private Task current;
    private String currentLabel = "";
    private int startingHay;
    private int stepFailures;
    private boolean hoeConsidered;
    private String status = "";

    public HayHarvestTask() {
        this(DEFAULT_RADIUS);
    }

    public HayHarvestTask(int radius) {
        this.radius = Math.max(8, radius);
    }

    @Override
    public String name() {
        return "Harvest hay";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        current = null;
        currentLabel = "";
        stepFailures = 0;
        hoeConsidered = false;
        startingHay = hay(ctx);
        status = "looking for hay";
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (current != null) {
            TaskStatus result = current.tick(ctx);
            status = currentLabel + " - " + current.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            String reason = current.status();
            current.stop(ctx);
            current = null;
            if (result == TaskStatus.FAILED) {
                ctx.debug.recordFailure(name(), reason);
                if (++stepFailures >= MAX_STEP_FAILURES) {
                    status = "gave up on the hay: " + reason;
                    return TaskStatus.FAILED;
                }
            }
            return TaskStatus.RUNNING;
        }

        int carriedHay = hay(ctx);
        int wheat = InventoryHelper.count(ctx.player, Items.WHEAT);

        // Unpack and bake before deciding there is nothing left to do, so a run that is interrupted
        // mid-farm still ends up with bread rather than a stack of bales.
        if (carriedHay > 0) {
            return start(ctx, CraftTask.of(Items.WHEAT,
                            wheat + carriedHay * VillagePolicy.WHEAT_PER_HAY, false),
                    "unpacking " + carriedHay + " hay into wheat");
        }
        if (wheat >= VillagePolicy.WHEAT_PER_BREAD) {
            int loaves = InventoryHelper.count(ctx.player, Items.BREAD)
                    + wheat / VillagePolicy.WHEAT_PER_BREAD;
            return start(ctx, CraftTask.of(Items.BREAD, loaves, true), "baking " + loaves + " bread");
        }

        int visible = visibleHay(ctx);
        int wanted = VillagePolicy.hayWorthTaking(visible, foodCount(ctx));
        if (wanted <= 0) {
            dropHoeIfCrowded(ctx);
            int taken = Math.max(0, startingHay - hay(ctx));
            status = visible <= 0 ? "no hay in sight" : "took what the farm was worth";
            return taken > 0 || InventoryHelper.count(ctx.player, Items.BREAD) > 0
                    ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }

        if (!hoeConsidered) {
            hoeConsidered = true;
            if (VillagePolicy.hoeWorthCrafting(wanted, hasHoe(ctx),
                    InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.PLANKS)),
                    InventoryHelper.count(ctx.player, Items.STICK))) {
                ctx.debug.decide("make a hoe first: " + wanted + " bales is worth the two planks");
                return start(ctx, new EnsureToolTask(Items.WOODEN_HOE, true), "making a hoe");
            }
        }

        ctx.debug.decide("strip " + wanted + " hay bales - " + VillagePolicy.breadFrom(wanted)
                + " bread once baked");
        return start(ctx, new MineTask(Set.of(Blocks.HAY_BLOCK), radius,
                        ctx.level.getMinY(), ctx.level.getMaxY(), wanted, true, false),
                "taking " + wanted + " hay bales");
    }

    @Override
    public void onPause(BotContext ctx) {
        if (current != null) {
            current.onPause(ctx);
        }
        ctx.input.reset();
    }

    @Override
    public void onStop(BotContext ctx) {
        if (current != null) {
            current.stop(ctx);
            current = null;
        }
        ctx.input.reset();
    }

    private TaskStatus start(BotContext ctx, Task task, String label) {
        current = task;
        currentLabel = label;
        current.start(ctx);
        status = label;
        return TaskStatus.RUNNING;
    }

    /** The hoe has done its job; a full bag is worth more than a tool with nothing left to cut. */
    private void dropHoeIfCrowded(BotContext ctx) {
        if (!VillagePolicy.shouldDropHoe(hasHoe(ctx), 0, InventoryHelper.isFull(ctx.player))) {
            return;
        }
        if (InventoryHelper.equip(ctx, HayHarvestTask::isHoe) < 0) {
            return;
        }
        ctx.player.drop(true);
        ctx.debug.decide("dropped the hoe; the farm is stripped and the bag is full");
    }

    private static boolean isHoe(net.minecraft.world.item.ItemStack stack) {
        return stack.is(Items.WOODEN_HOE) || stack.is(Items.STONE_HOE) || stack.is(Items.IRON_HOE);
    }

    private static boolean hasHoe(BotContext ctx) {
        return InventoryHelper.anyMatch(ctx.player, HayHarvestTask::isHoe);
    }

    private static int hay(BotContext ctx) {
        return InventoryHelper.count(ctx.player, Items.HAY_BLOCK);
    }

    private static int foodCount(BotContext ctx) {
        return InventoryHelper.count(ctx.player,
                stack -> stack.has(net.minecraft.core.component.DataComponents.FOOD));
    }

    /** Bales the bot can actually see, so a farm behind a hill is not counted as found. */
    static int visibleHay(BotContext ctx, int radius) {
        BlockPos feet = ctx.player.blockPosition();
        int found = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -5; dy <= 5; dy++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!ctx.level.isLoaded(cursor)) {
                        continue;
                    }
                    if (!ctx.level.getBlockState(cursor).is(Blocks.HAY_BLOCK)) {
                        continue;
                    }
                    if (Vision.isVisible(ctx, cursor) && ++found >= VillagePolicy.HAY_WANTED * 2) {
                        return found;
                    }
                }
            }
        }
        return found;
    }

    private int visibleHay(BotContext ctx) {
        return visibleHay(ctx, radius);
    }
}
