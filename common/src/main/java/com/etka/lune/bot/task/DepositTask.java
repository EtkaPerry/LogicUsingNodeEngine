package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.catalog.BlockCatalog;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BlockScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds a nearby chest or barrel, walks to it, and shift-clicks matching items out of the player's
 * inventory.
 * <p>
 * The filter is deliberately broad (ores, logs, crops, stone, or everything) so a long mining or
 * harvesting run can be followed by a single deposit step.
 */
public final class DepositTask implements Task {

    private static final int SEARCH_RADIUS = 16;
    private static final int ACTION_COOLDOWN = 5;
    private static final int MAX_OPEN_ATTEMPTS = 8;

    private static final Set<Block> CONTAINERS = Set.of(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);

    private final String filter;
    private final int radius;
    private final boolean optional;

    private BlockPos target;
    private GotoTask approach;
    private int cooldown;
    private int openAttempts;
    private String status = "";

    public DepositTask(String filter, int radius) {
        this(filter, radius, false);
    }

    public DepositTask(String filter, int radius, boolean optional) {
        this.filter = filter == null || filter.isBlank() ? "all" : filter;
        this.radius = Math.max(1, radius);
        this.optional = optional;
    }

    @Override
    public String name() {
        return "Deposit";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        Predicate<ItemStack> matcher = makeMatcher(ctx);

        if (target != null && !CONTAINERS.contains(ctx.level.getBlockState(target).getBlock())) {
            target = null;
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
        }

        if (target == null) {
            target = BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                    CONTAINERS, radius, ctx.level.getMinY(), ctx.level.getMaxY());
            if (target == null) {
                return unavailable("no chest or barrel within " + radius + " blocks");
            }
        }

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        if (!isContainerOpen(ctx)) {
            if (openAttempts >= MAX_OPEN_ATTEMPTS) {
                return unavailable("can't open the container");
            }
            return openContainer(ctx);
        }

        // Deposit one matching stack per tick, then wait for the server to move it.
        AbstractContainerMenu menu = ctx.player.containerMenu;
        for (Slot slot : menu.slots) {
            if (slot.container != ctx.player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            ctx.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, ctx.player);
            cooldown = ACTION_COOLDOWN;
            status = "depositing " + stack.getCount() + " " + stack.getHoverName().getString();
            return TaskStatus.RUNNING;
        }

        closeMenu(ctx);
        status = "deposited " + filter;
        return TaskStatus.SUCCESS;
    }

    private TaskStatus openContainer(BotContext ctx) {
        if (inReach(ctx, target)) {
            if (BlockPlacer.use(ctx, target)) {
                openAttempts++;
                status = "opening the container";
                cooldown = ACTION_COOLDOWN + 2;
                return TaskStatus.RUNNING;
            }
            status = "aiming at the container";
            return TaskStatus.RUNNING;
        }

        if (approach == null) {
            approach = new GotoTask(new Goals.Adjacent(target, 3.5), false, true);
            approach.start(ctx);
        }
        TaskStatus walk = approach.tick(ctx);
        if (walk == TaskStatus.SUCCESS) {
            approach.stop(ctx);
            approach = null;
            return TaskStatus.RUNNING;
        }
        if (walk == TaskStatus.FAILED) {
            approach.stop(ctx);
            approach = null;
            return unavailable("can't reach the container");
        }
        status = "walking to the container";
        return TaskStatus.RUNNING;
    }

    private TaskStatus unavailable(String reason) {
        status = optional ? reason + "; skipping optional deposit" : reason;
        return optional ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    private boolean isContainerOpen(BotContext ctx) {
        return ctx.player.containerMenu != null && ctx.player.containerMenu != ctx.player.inventoryMenu;
    }

    private static boolean inReach(BotContext ctx, BlockPos pos) {
        return ctx.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= 20.0;
    }

    private void closeMenu(BotContext ctx) {
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            ctx.mc.setScreen(null);
        }
    }

    private Predicate<ItemStack> makeMatcher(BotContext ctx) {
        return switch (filter.toLowerCase()) {
            case "ores" -> stack -> matchesOres(ctx, stack);
            case "logs" -> stack -> matchesLogs(ctx, stack);
            case "crops" -> stack -> matchesCrops(ctx, stack);
            case "stone" -> stack -> matchesStone(ctx, stack);
            case "all" -> stack -> !stack.isEmpty();
            default -> stack -> false;
        };
    }

    private static boolean matchesOres(BotContext ctx, ItemStack stack) {
        return matchesBlockSet(ctx, stack, BlockCatalog.ores())
                || itemPath(stack).endsWith("_ore");
    }

    private static boolean matchesLogs(BotContext ctx, ItemStack stack) {
        return matchesBlockTag(ctx, stack, BlockTags.LOGS)
                || itemPath(stack).endsWith("_log")
                || itemPath(stack).endsWith("_stem")
                || itemPath(stack).endsWith("_wood");
    }

    private static boolean matchesCrops(BotContext ctx, ItemStack stack) {
        return matchesBlockSet(ctx, stack, BlockCatalog.crops())
                || itemPath(stack).matches("^(wheat|carrot|potato|beetroot|melon|pumpkin|cocoa|sugar_cane|cactus|nether_wart).*");
    }

    private static boolean matchesStone(BotContext ctx, ItemStack stack) {
        return itemPath(stack).matches(".*(stone|cobble|deepslate|granite|andesite|diorite|tuff|calcite|netherrack|blackstone|end_stone|sandstone).*");
    }

    private static boolean matchesBlockSet(BotContext ctx, ItemStack stack, java.util.List<Block> blocks) {
        Block block = Block.byItem(stack.getItem());
        return block != Blocks.AIR && blocks.contains(block);
    }

    private static boolean matchesBlockTag(BotContext ctx, ItemStack stack, net.minecraft.tags.TagKey<Block> tag) {
        Block block = Block.byItem(stack.getItem());
        return block != Blocks.AIR && block.defaultBlockState().is(tag);
    }

    private static String itemPath(ItemStack stack) {
        Item item = stack.getItem();
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "" : id.getPath();
    }

    @Override
    public void onStop(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        closeMenu(ctx);
        ctx.input.reset();
    }
}
