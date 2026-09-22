package com.etka.lune.bot.task;

import com.etka.lune.compat.Screens;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

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
 * Anything needing a 3×3 grid gets one through {@link CraftingTableAccess}, which finds, places,
 * walks to and opens a table.
 * <p>
 * The other half of crafting is {@link GridCraftTask}: this card can only make what the recipe book
 * already knows, and that one lays the ingredients out by hand for the recipes it does not.
 */
public final class CraftTask implements Task {

    /** The output slot in both the inventory's 2×2 and a table's 3×3. */
    private static final int RESULT_SLOT = 0;
    /** Ticks to wait after a container action, so the server's reply lands before the next one. */
    private static final int ACTION_COOLDOWN = 5;
    /** Ticks of no new items before concluding the ingredients aren't there. */
    private static final int GIVE_UP_TICKS = 80;
    /** Item name endings that identify a per-material family, longest first so "_log" beats "og". */
    private static final List<String> MATERIAL_SUFFIXES = List.of("_planks", "_wool", "_log");

    private final Predicate<ItemStack> matches;
    private final String label;
    private final int wanted;
    private final boolean needsTable;
    /** True when {@link #wanted} counts what this run makes, rather than what the bag holds. */
    private final boolean countsNewItems;

    private final List<RecipeDisplayId> candidates = new ArrayList<>();
    private final CraftingTableAccess table = new CraftingTableAccess();

    private int candidateIndex;
    private int cooldown;
    private int lastCount = -1;
    private int noProgressTicks;
    /** What was already carried when the run began; zero unless counting new items. */
    private int baseline;
    private boolean baselineTaken;
    private final StatusText status = new StatusText();

    private CraftTask(Predicate<ItemStack> matches, String label, int wanted, boolean needsTable,
                      boolean countsNewItems) {
        this.matches = matches;
        this.label = label;
        this.wanted = Math.max(1, wanted);
        this.needsTable = needsTable;
        this.countsNewItems = countsNewItems;
    }

    /** Craft until the bag holds this many, which is what a job needing materials asks for. */
    public static CraftTask of(Item item, int wanted, boolean needsTable) {
        return new CraftTask(stack -> stack.is(item),
                InventoryHelper.itemName(item), wanted, needsTable, false);
    }

    /**
     * Craft this many, whatever is already carried.
     * <p>
     * The difference matters more than it reads. "Have four planks" is the right question for a
     * job that needs four planks to continue, and it is the wrong one for a person who asked for
     * four planks: a card told to craft four while a stack of sixty-four sits in the bag has
     * nothing to do, finishes immediately, and looks broken. A card says what it makes.
     */
    public static CraftTask make(Item item, int amount, boolean needsTable) {
        return new CraftTask(stack -> stack.is(item),
                InventoryHelper.itemName(item), amount, needsTable, true);
    }

    /**
     * Craft anything in a tag. Necessary for things like planks, where "do I have enough" and
     * "which recipe applies" both depend on which wood happens to be in the bag - and where a mod's
     * wood should work without being named anywhere.
     */
    public static CraftTask ofTag(TagKey<Item> tag, String label, int wanted, boolean needsTable) {
        return new CraftTask(stack -> stack.is(tag), label, wanted, needsTable, false);
    }

    /**
     * Craft whatever the caller can recognise. Needed where the wanted item is picked out by its
     * data rather than by its name - a dye is "the item carrying this dye colour", whichever mod
     * registered it, and there is no tag that distinguishes yellow from blue.
     */
    public static CraftTask matching(Predicate<ItemStack> matches, String label, int wanted,
                                     boolean needsTable) {
        return new CraftTask(matches, label, wanted, needsTable, false);
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
        return CraftingTableAccess.tableInReach(ctx, radius);
    }

    /**
     * Sets how far the bot will look for an existing table. Used by {@link EnsureToolTask} when it
     * would rather hike back to a remembered table than chop a new tree.
     */
    public CraftTask withTableSearchRadius(int radius) {
        table.setSearchRadius(radius);
        return this;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.craft.name", label);
    }

    /** English on purpose: this is the learner's row key, and is never shown. */
    @Override
    public String learningId() {
        return Task.learningName("Craft " + label);
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        int have = InventoryHelper.count(ctx.player, matches);
        // Taken on the first tick rather than in onStart, because a caller that ticks a task it
        // built itself is not obliged to have started it.
        if (countsNewItems && !baselineTaken) {
            baseline = have;
            baselineTaken = true;
        }
        int done = have - baseline;
        if (done >= wanted) {
            closeMenu(ctx);
            if (countsNewItems) {
                status.set("lune.status.craft.made", done);
            } else {
                status.set("lune.status.craft.have", have);
            }
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
            TaskStatus table = this.table.ensureOpen(ctx);
            status.set(this.table.statusLine());
            // SUCCESS here only means the grid is open; the craft itself starts next tick.
            return table == TaskStatus.FAILED ? TaskStatus.FAILED : TaskStatus.RUNNING;
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
            if (candidates.isEmpty()) {
                status.set("lune.status.craft.no_recipe_ingredient_bag", label);
            } else {
                status.set("lune.status.craft.missing_materials", label);
            }
            return TaskStatus.FAILED;
        }

        if (candidates.isEmpty() && !collectCandidates(ctx)) {
            // Recipes are unlocked by the server when the ingredient is first picked up, and that
            // reply lands a few ticks after the item does. Failing here would lose a race we only
            // have to wait out; the give-up timer still catches a genuinely unknown recipe.
            status.set("lune.status.craft.waiting_recipe", label);
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

        status.set("lune.status.craft.crafting", done, wanted);
        return TaskStatus.RUNNING;
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
            Screens.open(ctx.mc, null);
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        table.stop(ctx);
        closeMenu(ctx);
        ctx.input.reset();
    }
}
