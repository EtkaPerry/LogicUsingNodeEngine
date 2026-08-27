package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.catalog.BlockCatalog;
import com.etka.lune.bot.catalog.ToolCatalog;
import com.etka.lune.bot.memory.CraftingTableMemory;
import com.etka.lune.bot.util.BlockScanner;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.ToolSelector;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * Makes sure the bot has the tool a job needs, bootstrapping one from nothing if it has to: fell a
 * tree, make planks and sticks, put down a crafting table, cut a wooden pickaxe, mine some stone,
 * and cut the tool that was actually asked for.
 * <p>
 * The tool can be named outright - "a stone axe for the chopping run" - or left to be derived from a
 * block, in which case it is the cheapest pickaxe that would harvest that block for drops. A named
 * request is already answered by anything better of the same kind, so an iron axe in the bag ends it
 * immediately rather than making a second, worse axe.
 * <p>
 * Structured as a dependency resolver rather than a fixed sequence of phases. Every tick it asks
 * "what is the first thing still missing?" and works on that. This matters because supplies get
 * consumed out from under a plan: crafting sticks eats the planks the pickaxe needed, so a one-way
 * phase machine marches past PLANKS and then fails at the end with nothing to make. Re-deriving the
 * answer each tick means it simply goes back and makes more planks - and if the logs are gone too,
 * back to the trees.
 * <p>
 * Targets are computed from what is actually needed, not fixed quantities, so it stops as soon as it
 * has enough instead of converting the whole wood supply into something.
 * <p>
 * The chain ends at diamond. Netherite is upgraded rather than crafted, and gold ore needs an iron
 * pickaxe to reach in the first place, so both fail with a clear message rather than looping.
 */
public final class EnsureToolTask implements Task {

    /** Vanilla costs, used to work out how much of each intermediate is required. */
    private static final int PLANKS_PER_TABLE = 4;
    private static final int PLANKS_PER_STICK_CRAFT = 2;
    private static final int STICKS_PER_STICK_CRAFT = 4;
    private static final int PLANKS_PER_LOG = 4;
    private static final int COBBLE_PER_FURNACE = 8;

    /**
     * A pickaxe is the bootstrap for everything above wood, whatever the requested tool is: cobble
     * cannot be picked up by hand, so a stone axe still starts with a wooden pickaxe. It costs the
     * same three of its own material either way - planks for the wooden one, cobble for the stone.
     */
    private static final int PICKAXE_MATERIAL = ToolCatalog.materialCount(ToolCatalog.Kind.PICKAXE);
    private static final int PICKAXE_STICKS = ToolCatalog.sticks(ToolCatalog.Kind.PICKAXE);

    private static final int TABLE_SEARCH_RADIUS = 8;
    private static final int MAX_TABLE_MEMORY_RADIUS = 256;
    private static final int LOG_SEARCH_RADIUS = 32;
    private static final int RECONSIDER_TICKS = 10;
    private static final int MAX_WOOD_SCOUT_ROUNDS = 2;
    private static final int WOOD_SCOUT_ATTEMPTS = 3;
    private static final int WOOD_SCOUT_STEP = 12;
    private static final int MAX_STONE_ATTEMPTS = 4;

    /** Human-like search radius: scales with the player's render distance, capped at 96 blocks. */
    private static int gatherRadius(BotContext ctx) {
        return (int) Vision.maxRange(ctx);
    }

    /** The block that has to become harvestable; null when a tool was named instead. */
    private final Block goal;
    private final boolean checkAround;
    /** The tool that was asked for outright; null when it is derived from {@link #goal}. */
    private final Item requestedTool;
    private Task current;
    private String stepName = "";
    private String status = "";
    private boolean exhausted;
    private final Set<Long> avoidedWoodTargets = new HashSet<>();
    private StepKind currentKind = StepKind.OTHER;
    private int progressBeforeStep;
    private int reconsiderTicks;
    private int woodScoutRounds;
    private int stoneAttempts;
    private boolean woodScoutPending;
    /** A stone retry must leave the failed physical start, not rebuild the same route. */
    private final Set<Long> avoidedStoneStarts = new HashSet<>();

    private enum StepKind {
        WOOD, WOOD_SCOUT, STONE, IRON, DIAMOND, OTHER
    }

    public EnsureToolTask(Block goal) {
        this(goal, null, false);
    }

    public EnsureToolTask(Block goal, boolean checkAround) {
        this(goal, null, checkAround);
    }

    /**
     * Makes one named tool rather than working out what a block needs. This is the form a caller
     * wants when the job, not the block, decides the tool: an axe for a chopping run, a shovel for
     * digging out sand.
     */
    public EnsureToolTask(Item tool, boolean checkAround) {
        this(null, tool, checkAround);
    }

    private EnsureToolTask(Block goal, Item requestedTool, boolean checkAround) {
        this.goal = goal;
        this.requestedTool = requestedTool;
        this.checkAround = checkAround;
    }

    /**
     * Builds the normal dependency chain, but does not finish at a wooden pickaxe. This is used by
     * the speedrun's stone-kit phase, which needs stone-or-better even though a wooden pickaxe can
     * technically harvest stone.
     */
    public static EnsureToolTask stonePickaxe(boolean checkAround) {
        return new EnsureToolTask(Items.STONE_PICKAXE, checkAround);
    }

    @Override
    public String name() {
        return requestedTool != null
                ? "Get a " + itemName(requestedTool)
                : "Get a tool for " + goal.getName().getString();
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        stopCurrent(ctx);
        status = "figuring out what is missing";
        exhausted = false;
        avoidedWoodTargets.clear();
        currentKind = StepKind.OTHER;
        progressBeforeStep = 0;
        reconsiderTicks = 0;
        woodScoutRounds = 0;
        stoneAttempts = 0;
        woodScoutPending = false;
        avoidedStoneStarts.clear();
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        // Re-checked every tick: the moment a good enough tool exists the rest is pointless, which
        // also makes this a cheap no-op when already equipped.
        if (hasRequiredTool(ctx)) {
            // The table is left standing rather than reclaimed. Making a wooden pickaxe and then
            // needing stone tools happens within a few blocks of each other, so walking back to
            // break the table costs the trip and the ~75 ticks of breaking it, and then it has to
            // be placed again almost immediately. CraftingTableMemory remembers where it is and the
            // next craft walks to it; a table is four planks if one is ever genuinely needed
            // elsewhere.
            stopCurrent(ctx);
            status = "ready";
            return TaskStatus.SUCCESS;
        }
        if (exhausted) {
            status = requestedTool != null
                    ? "can't make a " + itemName(requestedTool)
                            + " - the chain stops at diamond"
                    : "can't craft a good enough tool for " + goal.getName().getString()
                            + " - needs netherite or a modded tool we don't know how to make";
            return TaskStatus.FAILED;
        }

        if (reconsiderTicks > 0) {
            reconsiderTicks--;
            return TaskStatus.RUNNING;
        }

        if (current == null) {
            if (woodScoutPending) {
                woodScoutPending = false;
                currentKind = StepKind.WOOD_SCOUT;
                stepName = "looking for a better tree";
                current = new ExploreTask(woodBlocks(), Math.min(gatherRadius(ctx), LOG_SEARCH_RADIUS),
                        WOOD_SCOUT_ATTEMPTS, WOOD_SCOUT_STEP, true);
            } else {
                current = nextStep(ctx);
                currentKind = classify(current);
            }
            if (current == null) {
                exhausted = true;
                return TaskStatus.RUNNING;
            }
            progressBeforeStep = resourceProgress(ctx, currentKind);
            current.start(ctx);
        }

        TaskStatus result = current.tick(ctx);
        status = stepName + " - " + current.status();
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }

        String reason = current.status();
        Task finished = current;
        StepKind finishedKind = currentKind;
        int resourceGain = resourceProgress(ctx, finishedKind) - progressBeforeStep;
        current.stop(ctx);
        current = null;
        currentKind = StepKind.OTHER;

        if (finished instanceof MineTask mine && finishedKind == StepKind.WOOD) {
            avoidedWoodTargets.addAll(mine.unreachableTargets());
        }

        if (finishedKind == StepKind.WOOD_SCOUT) {
            if (result == TaskStatus.SUCCESS) {
                reconsider("found a better place to try; going back to the tool plan");
                return TaskStatus.RUNNING;
            }
            if (woodScoutRounds < MAX_WOOD_SCOUT_ROUNDS) {
                woodScoutRounds++;
                woodScoutPending = true;
                reconsider("nothing useful that way; trying one more nearby area");
                return TaskStatus.RUNNING;
            }
            status = "couldn't find usable wood nearby; stopping instead of repeating the same route";
            return TaskStatus.FAILED;
        }

        if (isGathering(finishedKind)) {
            if (resourceGain > 0) {
                if (finishedKind == StepKind.WOOD) {
                    woodScoutRounds = 0;
                }
                reconsider(stepName + " made some progress; checking what is still needed");
                return TaskStatus.RUNNING;
            }
            return recoverFromStalledGather(finishedKind, reason);
        }

        if (result == TaskStatus.FAILED) {
            status = stepName + " failed - " + reason;
            return TaskStatus.FAILED;
        }
        if (finished instanceof SurfaceRecoveryTask) {
            // Recovery changes the route state, not the inventory. Re-derive the missing
            // dependency on the next tick instead of treating a successful swim to shore as an
            // exhausted tool-gathering step.
            reconsider("reached dry ground; checking for usable wood");
            return TaskStatus.RUNNING;
        }
        if (!finished.madeProgress()) {
            // A gatherer that exhausted its search without collecting anything did not satisfy a
            // dependency. Recreating it next tick causes an infinite full-radius rescan loop.
            status = stepName + " failed - " + reason;
            return TaskStatus.FAILED;
        }
        // Deliberately no phase advance: the next tick re-derives what's missing now.
        return TaskStatus.RUNNING;
    }

    /**
     * The first unmet requirement, resolved dependency-first.
     *
     * @return the task to run, or null when no craftable tool would do the job
     */
    private Task nextStep(BotContext ctx) {
        Item tool = targetTool();
        if (tool == null || !ToolCatalog.canMake(tool)) {
            return null;
        }

        // If the cheapest pickaxe that works is wooden but a stone one would also work
        // and we already have the cobblestone, skip the wooden step and make stone instead.
        // Only when the tool was derived: a request for a wooden pickaxe is a request, not a guess.
        if (goal != null && tool == Items.WOODEN_PICKAXE
                && correct(Items.STONE_PICKAXE)
                && hasMaterial(ctx, ToolCatalog.Material.STONE, PICKAXE_MATERIAL)) {
            tool = Items.STONE_PICKAXE;
        }

        ToolCatalog.Kind kind = ToolCatalog.kindOf(tool);
        ToolCatalog.Material material = ToolCatalog.materialOf(tool);
        int materialNeeded = ToolCatalog.materialCount(kind);

        BlockPos playerPos = ctx.player.blockPosition();
        BlockPos rememberedTable = CraftingTableMemory.get().findNearest(ctx, playerPos, MAX_TABLE_MEMORY_RADIUS);
        // The log distance is only needed to choose between wood and a remembered table. Avoid a
        // radius-32 world scan when there is no table to compare it with.
        BlockPos nearestLog = rememberedTable == null ? null : nearestLog(ctx, playerPos);

        // If wood is closer than a remembered table, the bot should make a fresh table. If there is
        // no wood in sight (desert, deep cave), the bot hikes back to the remembered table instead.
        boolean useFarTable = rememberedTable != null
                && (nearestLog == null || playerPos.distSqr(rememberedTable) <= playerPos.distSqr(nearestLog));
        int tableSearchRadius = useFarTable ? MAX_TABLE_MEMORY_RADIUS : TABLE_SEARCH_RADIUS;

        // Planks are consumed by several things; count what this run still owes.
        int planksNeeded = 0;

        boolean needTable = !InventoryHelper.has(ctx.player, Items.CRAFTING_TABLE, 1)
                && CraftingTableMemory.get().findNearest(ctx, playerPos, tableSearchRadius) == null;
        if (needTable) {
            planksNeeded += PLANKS_PER_TABLE;
        }
        // How much cobble would let this run skip the bootstrap pickaxe: a stone tool needs only
        // its own material, while everything above it needs enough for the stone pickaxe that goes
        // and mines the ore.
        int cobbleNeeded = material == ToolCatalog.Material.STONE ? materialNeeded : PICKAXE_MATERIAL;
        // Anything above wood needs a pickaxe before it can mine cobble. If the cobblestone is
        // already in the bag the wooden pickaxe is skipped; otherwise it is the bootstrap tool. A
        // wooden tool skips all of this - planks are the material.
        boolean needWoodenFirst = material != ToolCatalog.Material.WOOD
                && !ToolSelector.canHarvest(ctx.player, Blocks.STONE.defaultBlockState())
                && !InventoryHelper.has(ctx.player, Items.WOODEN_PICKAXE, 1)
                && !hasMaterial(ctx, ToolCatalog.Material.STONE, cobbleNeeded);

        int sticksNeeded = ToolCatalog.sticks(kind) + (needWoodenFirst ? PICKAXE_STICKS : 0);
        int sticks = InventoryHelper.count(ctx.player, Items.STICK);
        boolean needSticks = sticks < sticksNeeded;
        if (needSticks) {
            planksNeeded += PLANKS_PER_STICK_CRAFT
                    * ceilDiv(sticksNeeded - sticks, STICKS_PER_STICK_CRAFT);
        }
        if (needWoodenFirst) {
            planksNeeded += PICKAXE_MATERIAL;
        }
        if (material == ToolCatalog.Material.WOOD) {
            planksNeeded += materialNeeded;
        }

        int planks = planks(ctx);
        if (planks < planksNeeded) {
            return planksStep(ctx, planksNeeded);
        }

        if (needTable) {
            stepName = "making a crafting table";
            return CraftTask.of(Items.CRAFTING_TABLE, 1, false);
        }
        if (needSticks) {
            stepName = "making sticks";
            return CraftTask.of(Items.STICK, sticksNeeded, false);
        }
        if (needWoodenFirst) {
            stepName = "making a wooden pickaxe";
            return CraftTask.of(Items.WOODEN_PICKAXE, 1, true)
                    .withTableSearchRadius(tableSearchRadius);
        }
        if (material == ToolCatalog.Material.STONE && !hasMaterial(ctx, material, materialNeeded)) {
            stepName = "mining stone";
            return new QuickStoneTask(materialNeeded, stoneAttempts++, avoidedStoneStarts);
        }

        // Iron/diamond progression also needs a stone pickaxe to mine the materials.
        if (material == ToolCatalog.Material.IRON || material == ToolCatalog.Material.DIAMOND) {
            if (!ToolSelector.canHarvest(ctx.player, Blocks.IRON_ORE.defaultBlockState())) {
                if (!hasMaterial(ctx, ToolCatalog.Material.STONE, PICKAXE_MATERIAL)) {
                    stepName = "mining stone for a pickaxe";
                    return new QuickStoneTask(PICKAXE_MATERIAL, stoneAttempts++, avoidedStoneStarts);
                }
                stepName = "making a stone pickaxe";
                return CraftTask.of(Items.STONE_PICKAXE, 1, true)
                        .withTableSearchRadius(tableSearchRadius);
            }
        }

        if (material == ToolCatalog.Material.IRON) {
            if (!hasFurnace(ctx, playerPos, tableSearchRadius)) {
                if (!hasMaterial(ctx, ToolCatalog.Material.STONE, COBBLE_PER_FURNACE)) {
                    stepName = "mining stone for a furnace";
                    return new QuickStoneTask(COBBLE_PER_FURNACE, stoneAttempts++, avoidedStoneStarts);
                }
                stepName = "making a furnace";
                return CraftTask.of(Items.FURNACE, 1, true)
                        .withTableSearchRadius(tableSearchRadius);
            }

            int ingotsNeeded = materialNeeded - InventoryHelper.count(ctx.player, Items.IRON_INGOT);
            if (ingotsNeeded > 0) {
                int ore = InventoryHelper.count(ctx.player,
                        stack -> stack.is(Items.RAW_IRON)
                                || stack.is(Items.IRON_ORE)
                                || stack.is(Items.DEEPSLATE_IRON_ORE));
                if (ore >= ingotsNeeded) {
                    stepName = "smelting iron";
                    return new SmeltTask(Set.of(Items.RAW_IRON, Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE),
                            Items.IRON_INGOT, ingotsNeeded);
                }
                stepName = "mining iron ore";
                return new MineTask(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), gatherRadius(ctx),
                        ctx.level.getMinY(), ctx.level.getMaxY(), ingotsNeeded, true, true,
                        false, checkAround);
            }
        }

        if (material == ToolCatalog.Material.DIAMOND) {
            int diamondsNeeded = materialNeeded - InventoryHelper.count(ctx.player, Items.DIAMOND);
            if (diamondsNeeded > 0) {
                stepName = "mining diamond";
                // Diamond ore needs an iron pickaxe, which is a whole smelting trip further than
                // the stone one above. Auto-tool is on, so the miner asks for that upgrade itself
                // once it has picked the ore it is standing in front of.
                return new MineTask(Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), gatherRadius(ctx),
                        ctx.level.getMinY(), ctx.level.getMaxY(), diamondsNeeded, true, true,
                        false, checkAround);
            }
        }

        stepName = "making a " + itemName(tool);
        return CraftTask.of(tool, 1, true)
                .withTableSearchRadius(tableSearchRadius);
    }

    /** Makes planks, gathering tree trunks or pre-built plank blocks (e.g. mineshafts, villages). */
    private Task planksStep(BotContext ctx, int planksNeeded) {
        // Mine/Explore are deliberately no-swim. If a tool caller starts in water, reconnect to
        // a visible dry column first so the shared wood search can actually reach a tree.
        if (ctx.level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                && SurfaceRecoveryTask.needsDryGroundRecovery(ctx)) {
            stepName = "returning to dry ground";
            return new SurfaceRecoveryTask();
        }
        int planks = planks(ctx);
        int logs = InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.LOGS));
        int logsNeeded = ceilDiv(planksNeeded - planks, PLANKS_PER_LOG);

        if (logs < logsNeeded) {
            stepName = "gathering wood";
            Set<Block> wood = woodBlocks();
            // Gather only the minimum number of logs. A tool prerequisite is not a request to
            // climb through every branch of the first tree; stopping after the required drops
            // keeps the bot on the ground and lets the caller choose the next safe action.
            int limit = Math.max(logsNeeded, 1);
            return new MineTask(wood, gatherRadius(ctx),
                    ctx.level.getMinY(), ctx.level.getMaxY(), limit,
                    false, false, false, checkAround).avoiding(avoidedWoodTargets);
        }
        stepName = "making planks";
        return CraftTask.ofTag(ItemTags.PLANKS, "planks", planksNeeded, false);
    }

    private BlockPos nearestLog(BotContext ctx, BlockPos centre) {
        return BlockScanner.findNearest(ctx.level, centre,
                new HashSet<>(BlockCatalog.logs()), LOG_SEARCH_RADIUS,
                ctx.level.getMinY(), ctx.level.getMaxY());
    }

    private boolean hasFurnace(BotContext ctx, BlockPos playerPos, int radius) {
        if (InventoryHelper.has(ctx.player, Items.FURNACE, 1)) {
            return true;
        }
        return BlockScanner.findNearest(ctx.level, playerPos,
                Set.of(Blocks.FURNACE), radius,
                ctx.level.getMinY(), ctx.level.getMaxY()) != null;
    }

    /**
     * The tool to end up holding: the one that was asked for, or else the cheapest pickaxe that
     * would actually harvest the goal - asked of the item itself rather than hardcoded against a
     * tier table, so modded blocks and modded tools both work.
     */
    private Item targetTool() {
        if (requestedTool != null) return requestedTool;
        if (correct(Items.WOODEN_PICKAXE)) return Items.WOODEN_PICKAXE;
        if (correct(Items.STONE_PICKAXE)) return Items.STONE_PICKAXE;
        if (correct(Items.IRON_PICKAXE)) return Items.IRON_PICKAXE;
        if (correct(Items.DIAMOND_PICKAXE)) return Items.DIAMOND_PICKAXE;
        if (correct(Items.NETHERITE_PICKAXE)) return Items.NETHERITE_PICKAXE;
        return null;
    }

    private boolean hasRequiredTool(BotContext ctx) {
        if (requestedTool == null) {
            return ToolSelector.canHarvest(ctx.player, goal.defaultBlockState());
        }
        // A better tool of the same kind is still the tool that was asked for, so a carried iron
        // axe answers a request for a stone one instead of making a second, worse axe.
        return InventoryHelper.anyMatch(ctx.player,
                stack -> ToolCatalog.satisfies(stack.getItem(), requestedTool));
    }

    private boolean correct(Item pickaxe) {
        return new ItemStack(pickaxe).isCorrectToolForDrops(goal.defaultBlockState());
    }

    private static boolean hasMaterial(BotContext ctx, ToolCatalog.Material material, int atLeast) {
        return InventoryHelper.count(ctx.player, ToolCatalog.material(material)) >= atLeast;
    }

    private static String itemName(Item item) {
        return InventoryHelper.itemName(item);
    }

    private static int planks(BotContext ctx) {
        return InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.PLANKS));
    }

    private static int ceilDiv(int value, int divisor) {
        return value <= 0 ? 0 : (value + divisor - 1) / divisor;
    }

    private static Set<Block> woodBlocks() {
        Set<Block> wood = new HashSet<>();
        wood.addAll(BlockCatalog.logs());
        wood.addAll(BlockCatalog.planks());
        return wood;
    }

    private StepKind classify(Task task) {
        if (task instanceof QuickStoneTask) {
            return StepKind.STONE;
        }
        if (task instanceof MineTask) {
            if (stepName.equals("gathering wood")) return StepKind.WOOD;
            if (stepName.contains("iron ore")) return StepKind.IRON;
            if (stepName.contains("diamond")) return StepKind.DIAMOND;
        }
        return StepKind.OTHER;
    }

    private static boolean isGathering(StepKind kind) {
        return kind == StepKind.WOOD || kind == StepKind.STONE
                || kind == StepKind.IRON || kind == StepKind.DIAMOND;
    }

    /** Measures useful inventory progress, not blocks broken or item entities disappearing. */
    private int resourceProgress(BotContext ctx, StepKind kind) {
        return switch (kind) {
            case WOOD -> planks(ctx) + PLANKS_PER_LOG
                    * InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.LOGS));
            case STONE -> InventoryHelper.count(ctx.player,
                    ToolCatalog.material(ToolCatalog.Material.STONE));
            case IRON -> InventoryHelper.count(ctx.player, Items.IRON_INGOT)
                    + InventoryHelper.count(ctx.player, stack -> stack.is(Items.RAW_IRON)
                            || stack.is(Items.IRON_ORE) || stack.is(Items.DEEPSLATE_IRON_ORE));
            case DIAMOND -> InventoryHelper.count(ctx.player, Items.DIAMOND);
            default -> 0;
        };
    }

    private TaskStatus recoverFromStalledGather(StepKind kind, String reason) {
        if (kind == StepKind.WOOD && woodScoutRounds < MAX_WOOD_SCOUT_ROUNDS) {
            woodScoutRounds++;
            woodScoutPending = true;
            reconsider("that wood attempt didn't work out; leaving it and scouting nearby");
            return TaskStatus.RUNNING;
        }
        if (kind == StepKind.STONE && stoneAttempts < MAX_STONE_ATTEMPTS) {
            reconsider("that digging direction isn't working; trying a different side");
            return TaskStatus.RUNNING;
        }
        status = stepName + " made no useful progress";
        if (reason != null && !reason.isBlank()) {
            status += " - " + reason;
        }
        return TaskStatus.FAILED;
    }

    private void reconsider(String message) {
        status = message;
        reconsiderTicks = RECONSIDER_TICKS;
    }

    private void stopCurrent(BotContext ctx) {
        if (current != null) {
            current.stop(ctx);
            current = null;
        }
    }

    @Override
    public void onPause(BotContext ctx) {
        if (current != null) {
            current.onPause(ctx);
        } else {
            Task.super.onPause(ctx);
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        stopCurrent(ctx);
        ctx.input.reset();
    }
}
