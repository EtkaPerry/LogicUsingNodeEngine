package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.memory.CraftingTableMemory;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BlockScanner;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Crafts items by driving the recipe book, the same path the "auto-fill" button in the recipe book
 * uses: ask the server to lay the recipe into the grid, then shift-click the result out.
 * <p>
 * Recipes are server-authoritative in modern versions - the client only knows the
 * {@link RecipeDisplayEntry}s it has been told about - so there is no local recipe to inspect and no
 * way to place ingredients slot by slot reliably. Going through {@code handlePlaceRecipe} means the
 * server does the matching, which is also what makes this work unchanged with modded recipes.
 * <p>
 * Anything needing a 3×3 grid places and opens a crafting table first. The bot remembers tables it
 * has placed or used and can walk back to them when no wood is close, while staying willing to build
 * a fresh one when it is cheaper than the hike.
 */
public final class CraftTask implements Task {

    /** The output slot in both the inventory's 2×2 and a table's 3×3. */
    private static final int RESULT_SLOT = 0;
    /** Ticks to wait after a container action, so the server's reply lands before the next one. */
    private static final int ACTION_COOLDOWN = 5;
    /** Ticks of no new items before concluding the ingredients aren't there. */
    private static final int GIVE_UP_TICKS = 80;
    /** Default radius for looking for an existing crafting table before placing one. */
    /**
     * Deliberately short. Since the bot stopped reclaiming its tables there is usually one standing
     * somewhere behind it, and walking back to it is only worth doing if it is genuinely underfoot.
     * A table is four planks; further than this and it is faster to make another one where the work
     * is than to walk there and back - and a table eight blocks away can mean climbing out of a
     * mineshaft, which is not eight blocks of walking at all.
     */
    private static final int TABLE_SEARCH_RADIUS = 4;
    /** If the nearest table is further than this, the bot prefers placing a fresh one. */
    private static final int NEARBY_TABLE_RADIUS_SQR = 3 * 3;
    /**
     * A crafting table is an interaction target, not merely a nearby landmark. A distance goal can
     * accept a position below or behind a table where the ray cast can never hit its face. The
     * approach goal therefore names the eight same-level neighbouring blocks, so arrival is a
     * meaningful interaction position rather than a satisfied radius beside the table.
     */
    /** How many times we will try to open a table before concluding it's unreachable. */
    private static final int MAX_OPEN_ATTEMPTS = 4;
    /** How many ticks we will keep trying to place at one spot before picking another. */
    private static final int MAX_PLACE_TICKS = 40;
    /** Item name endings that identify a per-material family, longest first so "_log" beats "og". */
    private static final List<String> MATERIAL_SUFFIXES = List.of("_planks", "_wool", "_log");

    private final Predicate<ItemStack> matches;
    private final String label;
    private final int wanted;
    private final boolean needsTable;

    private final List<RecipeDisplayId> candidates = new ArrayList<>();
    private final Set<Long> unreachableTables = new HashSet<>();
    private final Set<Long> badPlacementSpots = new HashSet<>();

    private int candidateIndex;
    private int cooldown;
    private int lastCount = -1;
    private int noProgressTicks;
    private int openAttempts;
    private long currentTableKey = -1;
    private BlockPos tablePos;
    private GotoTask approach;
    private int tableSearchRadius = TABLE_SEARCH_RADIUS;
    private int placingTicks;
    private BlockPos placingSpot;
    private BlockPos fallbackTablePos;
    /** One bounded attempt to reconnect to a surface table after a route failed from a pocket. */
    private SurfaceRecoveryTask tableRecovery;
    private BlockPos tableForRecovery;
    private boolean attemptedTableRecovery;
    private String status = "";

    private CraftTask(Predicate<ItemStack> matches, String label, int wanted, boolean needsTable) {
        this.matches = matches;
        this.label = label;
        this.wanted = Math.max(1, wanted);
        this.needsTable = needsTable;
    }

    /** Craft a specific item. */
    public static CraftTask of(Item item, int wanted, boolean needsTable) {
        return new CraftTask(stack -> stack.is(item),
                InventoryHelper.itemName(item), wanted, needsTable);
    }

    /**
     * Craft anything in a tag. Necessary for things like planks, where "do I have enough" and
     * "which recipe applies" both depend on which wood happens to be in the bag - and where a mod's
     * wood should work without being named anywhere.
     */
    public static CraftTask ofTag(TagKey<Item> tag, String label, int wanted, boolean needsTable) {
        return new CraftTask(stack -> stack.is(tag), label, wanted, needsTable);
    }

    /**
     * Craft whatever the caller can recognise. Needed where the wanted item is picked out by its
     * data rather than by its name - a dye is "the item carrying this dye colour", whichever mod
     * registered it, and there is no tag that distinguishes yellow from blue.
     */
    public static CraftTask matching(Predicate<ItemStack> matches, String label, int wanted,
                                     boolean needsTable) {
        return new CraftTask(matches, label, wanted, needsTable);
    }

    /**
     * Whether the player's recipe book currently contains anything matching {@code wanted}.
     * <p>
     * Recipes are server-authoritative and unlocked by doing things, so "can I craft this" is not
     * a question about ingredients. A boat is the sharpest example in the game: its recipe unlocks
     * by <em>standing in water</em>, so a bot with a stack of planks and a table still cannot make
     * one until it has got its feet wet, and the only symptom is a recipe that never appears.
     */
    public static boolean knowsRecipeFor(BotContext ctx, Predicate<ItemStack> wanted) {
        ContextMap context = SlotDisplayContext.fromLevel(ctx.level);
        for (RecipeCollection collection : ctx.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (wanted.test(entry.display().result().resolveForFirstStack(context))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a crafting table is close enough to use, counting the ones already remembered.
     * <p>
     * Callers that need a 3×3 recipe have to know this <em>before</em> they build the CraftTask,
     * because the answer decides whether they first have to spend four planks on a table - and a
     * recipe that needs a table it cannot reach fails with a message about the recipe.
     */
    public static boolean tableInReach(BotContext ctx, int radius) {
        if (CraftingTableMemory.get().findNearest(ctx, ctx.player.blockPosition(), radius) != null) {
            return true;
        }
        return BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                Set.of(Blocks.CRAFTING_TABLE), radius, ctx.level.getMinY(), ctx.level.getMaxY(),
                Set.of()) != null;
    }

    /**
     * Sets how far the bot will look for an existing table. Used by {@link EnsureToolTask} when it
     * would rather hike back to a remembered table than chop a new tree.
     */
    public CraftTask withTableSearchRadius(int radius) {
        this.tableSearchRadius = Math.max(1, radius);
        return this;
    }

    @Override
    public String name() {
        return "Craft " + label;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        int have = InventoryHelper.count(ctx.player, matches);
        if (have >= wanted) {
            closeMenu(ctx);
            status = "have " + have;
            return TaskStatus.SUCCESS;
        }

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        if (needsTable && !(ctx.player.containerMenu instanceof CraftingMenu)) {
            // Time spent finding, placing and opening a table is not the crafting recipe stalling.
            lastCount = have;
            noProgressTicks = 0;
            return prepareTable(ctx);
        }

        // Progress is "more of the thing exists". Ingredients running out looks exactly like the
        // recipe not applying, so both end in the same honest failure.
        if (have == lastCount) {
            noProgressTicks += ACTION_COOLDOWN;
        } else {
            ctx.debug.count("items_crafted", have - lastCount);
            lastCount = have;
            noProgressTicks = 0;
        }
        if (noProgressTicks > GIVE_UP_TICKS) {
            if (advanceCandidate()) {
                // Several recipes can make the same thing (oak vs birch planks). Try the next one
                // before declaring defeat.
                noProgressTicks = 0;
                return TaskStatus.RUNNING;
            }
            closeMenu(ctx);
            status = candidates.isEmpty()
                    ? "no recipe for " + label + " - is the ingredient in the bag?"
                    : "missing materials for " + label;
            return TaskStatus.FAILED;
        }

        if (candidates.isEmpty() && !collectCandidates(ctx)) {
            // Recipes are unlocked by the server when the ingredient is first picked up, and that
            // reply lands a few ticks after the item does. Failing here would lose a race we only
            // have to wait out; the give-up timer still catches a genuinely unknown recipe.
            status = "waiting for the " + label + " recipe";
            cooldown = ACTION_COOLDOWN;
            return TaskStatus.RUNNING;
        }

        int containerId = ctx.player.containerMenu.containerId;
        // false = one batch per action. The "max items" form fills the grid with everything it can,
        // which turns "make 4 sticks" into "convert the entire wood supply into sticks". Looping a
        // batch at a time is slower by a few ticks and actually respects the requested count.
        ctx.gameMode.handlePlaceRecipe(containerId, candidates.get(candidateIndex), false);
        ctx.gameMode.handleContainerInput(containerId, RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, ctx.player);
        cooldown = ACTION_COOLDOWN;

        status = "crafting (" + have + "/" + wanted + ")";
        return TaskStatus.RUNNING;
    }

    /** Ensures a crafting table exists nearby, we're standing next to it, and it's open. */
    private TaskStatus prepareTable(BotContext ctx) {
        if (tableRecovery != null) {
            TaskStatus recovered = tableRecovery.tick(ctx);
            status = "returning to the table - " + tableRecovery.status();
            if (recovered == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }

            tableRecovery.stop(ctx);
            tableRecovery = null;
            if (recovered == TaskStatus.SUCCESS && tableForRecovery != null) {
                // SurfaceRecoveryTask changed the position, so allow the same remembered table to
                // be selected again. The recovery is deliberately one-shot for this CraftTask;
                // if the route still fails, the table is genuinely not usable from this side.
                CraftingTableMemory.get().remember(tableForRecovery);
                unreachableTables.remove(tableForRecovery.asLong());
                tablePos = null;
                fallbackTablePos = null;
                tableForRecovery = null;
                status = "back on dry ground; retrying the remembered table";
                return TaskStatus.RUNNING;
            }

            if (tableForRecovery != null) {
                CraftingTableMemory.get().markUnreachable(tableForRecovery);
            }
            tableForRecovery = null;
            status = "couldn't reconnect to the remembered table";
            return TaskStatus.FAILED;
        }

        // Forget a table that has been broken or removed.
        if (tablePos != null && !ctx.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
            CraftingTableMemory.get().forget(tablePos);
            unreachableTables.add(tablePos.asLong());
            tablePos = null;
        }

        // Track open attempts per table so we don't spin on an unreachable one forever.
        if (tablePos == null) {
            currentTableKey = -1;
            openAttempts = 0;
        } else if (currentTableKey != tablePos.asLong()) {
            currentTableKey = tablePos.asLong();
            openAttempts = 0;
        }

        if (tablePos == null && fallbackTablePos == null && placingSpot == null) {
            // First, try the tables the bot has already seen. If none are available in range, the
            // ordinary scanner still runs.
            tablePos = CraftingTableMemory.get().findNearest(ctx, ctx.player.blockPosition(), tableSearchRadius);

            if (tablePos == null) {
                tablePos = BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                        Set.of(Blocks.CRAFTING_TABLE), tableSearchRadius,
                        ctx.level.getMinY(), ctx.level.getMaxY(), unreachableTables);
                if (tablePos != null) {
                    CraftingTableMemory.get().remember(tablePos);
                }
            }

            // The nearest table is far away. Rather than hiking back, try to place a fresh one where
            // the bot is standing. This keeps the mining site alive and avoids starting a fresh dig.
            if (tablePos != null && ctx.player.blockPosition().distSqr(tablePos) > NEARBY_TABLE_RADIUS_SQR
                    && InventoryHelper.has(ctx.player, Items.CRAFTING_TABLE, 1)) {
                fallbackTablePos = tablePos;
                tablePos = null;
            }
        }

        // No close table in the world; place one if we can.
        if (tablePos == null) {
            if (!InventoryHelper.has(ctx.player, Items.CRAFTING_TABLE, 1)) {
                if (fallbackTablePos != null) {
                    tablePos = fallbackTablePos;
                    fallbackTablePos = null;
                    return TaskStatus.RUNNING;
                }
                status = "need a crafting table";
                return TaskStatus.FAILED;
            }

            if (placingSpot == null) {
                placingSpot = findGoodPlacementSpot(ctx);
                placingTicks = 0;
            }

            if (placingSpot == null) {
                if (fallbackTablePos != null) {
                    tablePos = fallbackTablePos;
                    fallbackTablePos = null;
                    return TaskStatus.RUNNING;
                }
                status = "nowhere to put a crafting table";
                return TaskStatus.FAILED;
            }

            // Only commit the spot once the block is actually there. tryPlace may take several
            // ticks to finish turning and the server to confirm, and acting as though the table
            // already exists makes the next tick try to open empty air.
            BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(
                    ctx, Blocks.CRAFTING_TABLE, placingSpot);
            if (placement == BlockPlacer.PlacementResult.PLACED
                    || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
                tablePos = placingSpot;
                placingSpot = null;
                placingTicks = 0;
                fallbackTablePos = null;
                CraftingTableMemory.get().remember(tablePos);
                status = "placed a crafting table";
                cooldown = ACTION_COOLDOWN;
                return TaskStatus.RUNNING;
            }

            if (!placement.isTransient()) {
                badPlacementSpots.add(placingSpot.asLong());
                placingSpot = null;
                placingTicks = 0;
                status = "can't place there (" + placement.name().toLowerCase()
                        + "), trying another spot";
                return TaskStatus.RUNNING;
            }

            placingTicks++;
            if (placingTicks >= MAX_PLACE_TICKS) {
                badPlacementSpots.add(placingSpot.asLong());
                placingSpot = null;
                placingTicks = 0;
                status = "can't place there, trying another spot";
                return TaskStatus.RUNNING;
            }
            status = "placing a crafting table";
            return TaskStatus.RUNNING;
        }

        // Close enough to interact? Try to open it.
        if (inReach(ctx, tablePos)) {
            if (openAttempts >= MAX_OPEN_ATTEMPTS) {
                unreachableTables.add(tablePos.asLong());
                CraftingTableMemory.get().markUnreachable(tablePos);
                tablePos = null;
                placingSpot = null;
                fallbackTablePos = null;
                status = "that table can't be opened from here";
                return TaskStatus.RUNNING;
            }

            if (BlockPlacer.use(ctx, tablePos)) {
                openAttempts++;
                status = "opening the crafting table";
                cooldown = ACTION_COOLDOWN + 3;
                return TaskStatus.RUNNING;
            }
            status = "aiming at the crafting table";
            return TaskStatus.RUNNING;
        }

        // Need to walk closer. Allow breaking light obstructions (leaves, tall grass) so a table
        // placed or found behind foliage is still usable.
        if (approach == null) {
            approach = new GotoTask(tableApproachGoal(tablePos), false, true);
            approach.start(ctx);
        }
        TaskStatus walk = approach.tick(ctx);
        if (walk == TaskStatus.SUCCESS) {
            approach.stop(ctx);
            approach = null;
            // A path goal can be satisfied on the same tick that terrain or a collision box makes
            // the actual interaction impossible. Do not rebuild that already-satisfied goal every
            // tick: remember the table as unusable from this side and let normal table selection
            // or placement make a different decision.
            if (!inReach(ctx, tablePos)) {
                unreachableTables.add(tablePos.asLong());
                CraftingTableMemory.get().markUnreachable(tablePos);
                tablePos = null;
                placingSpot = null;
                fallbackTablePos = null;
                status = "arrived beside a blocked table; trying another";
                return TaskStatus.RUNNING;
            }
            return TaskStatus.RUNNING; // now in reach, open next tick
        }
        if (walk == TaskStatus.FAILED) {
            approach.stop(ctx);
            approach = null;
            unreachableTables.add(tablePos.asLong());
            // A long-route craft may have started at the bottom of a self-dug staircase. Give the
            // shared route a chance to reconnect with visible dry ground before declaring its
            // remembered table unusable. Ordinary local crafts keep their old bounded failure.
            if (tableSearchRadius > TABLE_SEARCH_RADIUS
                    && ctx.level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                    && SurfaceRecoveryTask.needsDryGroundRecovery(ctx)
                    && tableRecovery == null
                    && !attemptedTableRecovery) {
                tableForRecovery = tablePos;
                tablePos = null;
                fallbackTablePos = null;
                attemptedTableRecovery = true;
                tableRecovery = new SurfaceRecoveryTask();
                tableRecovery.start(ctx);
                status = "can't reach that table; reconnecting to dry ground";
                return TaskStatus.RUNNING;
            }
            CraftingTableMemory.get().markUnreachable(tablePos);
            tablePos = null;
            placingSpot = null;
            fallbackTablePos = null;
            status = "can't reach that table, trying another";
            return TaskStatus.RUNNING;
        }
        status = "walking to the crafting table";
        return TaskStatus.RUNNING;
    }

    private static boolean inReach(BotContext ctx, BlockPos pos) {
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        if (eye.distanceToSqr(centre) > 20.0) {
            return false;
        }
        // Distance alone is not an interaction position. A player below a table can be within the
        // vanilla reach radius while a stone/dirt lip blocks every face; treating that as ready
        // leaves CraftTask in its aiming loop forever. Keep the approach goal alive until the
        // actual table centre is the first block hit by the player's ray.
        return BlockPlacer.hasLineOfSight(ctx, pos);
    }

    /**
     * Stand on a block at the table's own height. Keeping the candidate set explicit prevents a
     * player one block below a table from satisfying a distance heuristic while remaining unable
     * to click any of its faces.
     */
    private static Goals.Any tableApproachGoal(BlockPos table) {
        return new Goals.Any(List.of(
                new Goals.Block(table.north()),
                new Goals.Block(table.south()),
                new Goals.Block(table.east()),
                new Goals.Block(table.west()),
                new Goals.Block(table.north().east()),
                new Goals.Block(table.north().west()),
                new Goals.Block(table.south().east()),
                new Goals.Block(table.south().west())));
    }

    /** Picks a table placement spot, excluding spots that have already failed. */
    private BlockPos findGoodPlacementSpot(BotContext ctx) {
        return BlockPlacer.findPlacementSpot(ctx, badPlacementSpots);
    }

    /**
     * Collects every known recipe producing the wanted item, best guess first.
     * <p>
     * More than one is normal - planks come from any log, a boat from any wood - and which one
     * works depends on what is in the bag. Order matters far more than it looks: a wrong candidate
     * is not rejected, it is <em>waited on</em> for {@link #GIVE_UP_TICKS} before the next is tried,
     * so trying eleven wood types in registry order costs a minute of standing at the table
     * apparently doing nothing. One recorded run took 1241 ticks to make a birch boat it had the
     * planks for the whole time.
     * <p>
     * The material prefix of the carried items is the cheap, reliable hint: holding birch planks
     * puts {@code birch_boat} first, holding white wool puts {@code white_bed} first. It is only a
     * sort, so nothing becomes uncraftable if the guess is wrong.
     */
    private boolean collectCandidates(BotContext ctx) {
        ContextMap context = SlotDisplayContext.fromLevel(ctx.level);
        candidates.clear();
        candidateIndex = 0;
        Set<String> carriedMaterials = carriedMaterialPrefixes(ctx);
        List<RecipeDisplayEntry> matching = new ArrayList<>();
        for (RecipeCollection collection : ctx.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (matches.test(entry.display().result().resolveForFirstStack(context))) {
                    matching.add(entry);
                }
            }
        }
        matching.sort(Comparator.comparingInt(
                entry -> madeFromCarriedMaterial(entry, context, carriedMaterials) ? 0 : 1));
        for (RecipeDisplayEntry entry : matching) {
            candidates.add(entry.id());
        }
        return !candidates.isEmpty();
    }

    /** True when the recipe's result shares a material prefix with something already carried. */
    private static boolean madeFromCarriedMaterial(RecipeDisplayEntry entry, ContextMap context,
                                                   Set<String> carriedMaterials) {
        ItemStack result = entry.display().result().resolveForFirstStack(context);
        if (result.isEmpty()) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(result.getItem()).getPath();
        for (String material : carriedMaterials) {
            if (path.startsWith(material + "_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Material prefixes of the carried building blocks: {@code birch} from birch planks,
     * {@code white} from white wool. Only the families where the same recipe exists once per
     * material, which is exactly where the candidate list gets long.
     */
    private static Set<String> carriedMaterialPrefixes(BotContext ctx) {
        Set<String> prefixes = new HashSet<>();
        Inventory inventory = ctx.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            for (String suffix : MATERIAL_SUFFIXES) {
                if (path.endsWith(suffix) && path.length() > suffix.length()) {
                    prefixes.add(path.substring(0, path.length() - suffix.length()));
                }
            }
        }
        return prefixes;
    }

    private boolean advanceCandidate() {
        if (candidateIndex + 1 >= candidates.size()) {
            return false;
        }
        candidateIndex++;
        return true;
    }

    private void closeMenu(BotContext ctx) {
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            ctx.mc.setScreen(null);
        }
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
