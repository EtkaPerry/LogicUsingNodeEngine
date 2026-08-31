package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.catalog.ToolCatalog;
import com.etka.lune.bot.knowledge.BiomeScout;
import com.etka.lune.bot.memory.CraftingTableMemory;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BlockScanner;
import com.etka.lune.bot.util.BucketHelper;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.util.HotbarLayout;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.TargetIndex;
import com.etka.lune.bot.util.ToolSelector;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A survival route from a fresh overworld spawn to the End credits.
 *
 * <p>The route deliberately follows the useful part of a 1.16-style speedrun: get a stone kit,
 * make a bucket/flint-and-steel, cast a portal from a visible lava pool, take the first fortress
 * the player can actually see, collect blaze rods, then use endermen and eyes to finish the game.
 * It does not inspect unloaded chunks or jump directly to structure coordinates.</p>
 */
public final class SpeedrunTask implements Task {

    // The portal kit uses one bucket twice: first as the lava source and then, once emptied, as the
    // water bucket for the cast. Four ingots therefore cover one bucket plus flint-and-steel - but
    // only for a run that starts with neither. What is actually needed is worked out per run by
    // SpeedrunIronPolicy.ironNeeded, so a looted flint and steel, or enough obsidian to skip the
    // cast altogether, removes its own cost instead of being mined for a second time.
    private static final int BLAZE_RODS_NEEDED = 7;
    private static final int PEARLS_NEEDED = 14;
    private static final int EYES_NEEDED = 14;
    /** Enough ordinary food for the Nether and the final approach without over-looting. */
    private static final int FOOD_NEEDED = 8;
    /**
     * How much food an opportunistic detour takes, as opposed to the dedicated food phase.
     * <p>
     * Eight is the right stock to leave for the Nether with; it is the wrong amount to stop a
     * mining route and chase. Salmon in particular are slow - they are in water, they flee, and each
     * one is a swim - so a "quick" eight-item detour costs minutes. Three is a meal and a reserve,
     * and the hay farms and the bed cover the rest of the run's hunger.
     */
    private static final int OPPORTUNISTIC_FOOD = 3;
    /**
     * The hunger level below which food stops being opportunistic.
     * <p>
     * Vanilla refuses to sprint at six or less, and sprint is not a luxury: it is thirty percent of
     * walking speed and <em>all</em> of swimming speed, because the crawl stroke only exists while
     * sprinting. A run that drops under this is measurably slower at everything, including at
     * fetching the food that would fix it - one recorded run spent five hundred ticks paddling
     * after salmon at three hunger, holding the sprint key the whole time and being ignored. Seven
     * is one above the cliff, so the search starts before the penalty does.
     */
    private static final int SPRINT_FOOD_FLOOR = 7;
    /** Ticks before a failed urgent food search may be retried, so it cannot loop on a barren area. */
    private static final int HUNGRY_SEARCH_COOLDOWN_TICKS = 1200;
    /** Do not interrupt a Nether objective for food until the carried reserve is genuinely low. */
    private static final int NETHER_EMERGENCY_FOOD = 4;
    private static final int MAX_GATHER_ATTEMPTS = 6;
    /** How far a flock may be and still be worth a stop. */
    private static final int SHEEP_DIVERSION_RADIUS = 32;
    /**
     * Blocks that drop something a stone tool can actually be made from.
     * <p>
     * Deliberately narrow. Vanilla's {@code stone_tool_materials} tag is cobblestone, blackstone and
     * cobbled deepslate - a village's stone bricks and cobblestone stairs drop themselves and are
     * useless for a pickaxe, so mining them would be work that looks like progress.
     */
    private static final Set<Block> PLACED_STONE = Set.of(
            Blocks.COBBLESTONE, Blocks.STONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE,
            Blocks.BLACKSTONE);
    /** Close enough that taking a wall apart beats sinking a staircase. */
    private static final int PLACED_STONE_RADIUS = 24;
    /**
     * How far to notice a structure while walking past: the player's own view distance.
     * <p>
     * Anything else is arbitrary. {@link Vision} already refuses to see past the loaded chunks, so
     * scanning further is wasted work and scanning less is artificial blindness - a hardcoded
     * sixty-four made the bot walk past a village that was plainly on screen. The palette-pruned
     * index is what makes a full view-distance sweep affordable.
     */
    private static int sightingRadius(BotContext ctx) {
        return (int) Math.max(32.0, Vision.maxRange(ctx));
    }
    /** Structures are found by ground-level blocks, so a tall band buys nothing. */
    private static final int SIGHTING_VERTICAL = 16;
    /** How far the bot moves before the awareness scans re-centre and rebuild. */
    private static final int SIGHTING_REANCHOR = 32;
    /** Line-of-sight rays one sweep may spend; the cursor resumes where the last sweep stopped. */
    private static final int SIGHTING_RAY_BUDGET = 24;
    private static final int LAVA_RAY_BUDGET = 8;
    private static final Set<Block> LAVA_ONLY = Set.of(Blocks.LAVA);
    /** Blocks that generate in villages and effectively nowhere else. */
    private static final Set<Block> VILLAGE_MARKERS = Set.of(
            Blocks.BELL, Blocks.COMPOSTER, Blocks.LECTERN, Blocks.SMOKER,
            Blocks.BLAST_FURNACE, Blocks.HAY_BLOCK);
    /** A village this close is close enough to be worth working instead of leaving. */
    private static final int VILLAGE_RADIUS = 48;
    /** How far to look for an opening worth going down instead of walking the surface. */
    private static final int CAVE_DIVE_RADIUS = 32;
    /** How close a spawner has to be to make a structure not worth entering. */
    private static final int SPAWNER_GUARD_RADIUS = 12;
    /** Logs to take once an axe makes it quick; three is a boat, or a bed and a table. */
    private static final int WOOD_TOPUP_LOGS = 3;
    /** Plank-equivalents above which the run is already carrying enough wood to skip the top-up. */
    private static final int WOOD_TOPUP_TARGET = 16;
    /** Ticks between opportunity sweeps; a full block scan every tick is not worth its cost. */
    private static final int SIGHTING_INTERVAL_TICKS = 40;
    /** Enough is enough: a visible-block count only has to answer "are there plenty". */
    private static final int VISIBLE_COUNT_CAP = 32;
    /** How far to look for water worth crossing when the shore has no gravel. */
    private static final int FLINT_CROSSING_RADIUS = 24;
    /** Below this the diamonds cannot become a pickaxe, so they are better spent on a shovel. */
    private static final int DIAMONDS_FOR_PICKAXE = 3;
    /** How far to look for a waterline to search for gravel along. */
    private static final int SHORE_SEARCH_RADIUS = 48;
    private static final int SHORE_SEARCH_HEIGHT = 6;
    /** Standing this close to the water already counts as being on the bank. */
    private static final int ON_SHORE_DISTANCE = 4;
    private static final int SHOVEL_STICKS = 2;
    /** Ticks between hotbar tidy-ups. */
    private static final int HOTBAR_TIDY_TICKS = 100;
    /** Deferrals allowed before the shaft goes ahead regardless. */
    private static final int MAX_DIG_DEFERRALS = 6;
    /** Planks that one stick craft consumes; two planks make four sticks. */
    private static final int PLANKS_PER_STICK_CRAFT = 2;
    /** Cobble a stone pickaxe costs; the point at which upgrading mid-dig becomes possible. */
    private static final int STONE_PICKAXE_COBBLE = 3;
    private static final int WOODEN_PICKAXE_PLANKS = 3;
    private static final int WOODEN_PICKAXE_STICKS = 2;
    /**
     * Gravel worth carrying out of a bank passed on the way to something else.
     *
     * <p>Flint comes off gravel one block in ten, so the flint phase is really a search for a
     * gravel bank followed by a dig. Banks turn up constantly during the earlier phases and cost
     * almost nothing to strip in passing, and four blocks carried is a phase that can start where
     * it stands instead of walking a shoreline first.
     */
    private static final int GRAVEL_RESERVE = 4;
    /**
     * Food items worth carrying before an opportunity in passing stops being worth the stop.
     *
     * <p>Six is roughly two full hunger bars of cooked meat - enough to cross the iron phase and
     * the Nether entry without a detour, and small enough that topping up never becomes the run.
     */
    private static final int FOOD_RESERVE = 6;
    private static final int GRAVEL_DIVERSION_RADIUS = 16;
    /** Ticks before gravel is looked for again, so a bank that cannot be reached is asked once. */
    private static final int GRAVEL_RECHECK_TICKS = 600;
    /** Knapping attempts allowed before the phase accepts it must go and find more gravel. */
    private static final int MAX_GRAVEL_KNAPS = 3;

    /**
     * Raw food and what it becomes. Cooking roughly doubles the hunger and saturation a piece is
     * worth, and the furnace the iron phase has just finished with is standing right there - so
     * the whole upgrade costs the fuel and none of the walking.
     */
    private static final Map<Item, Item> RAW_TO_COOKED = Map.of(
            Items.BEEF, Items.COOKED_BEEF,
            Items.PORKCHOP, Items.COOKED_PORKCHOP,
            Items.CHICKEN, Items.COOKED_CHICKEN,
            Items.MUTTON, Items.COOKED_MUTTON,
            Items.RABBIT, Items.COOKED_RABBIT,
            Items.COD, Items.COOKED_COD,
            Items.SALMON, Items.COOKED_SALMON);
    /** Cook attempts per run, so a furnace that will not cooperate cannot hold up the route. */
    private static final int MAX_COOK_ATTEMPTS = 3;
    /** Two seconds between flock scans; sheep do not appear faster than that. */
    private static final int NIGHT_CHECK_INTERVAL_TICKS = 40;
    /** After a failed camp, leave the route alone for a minute before asking again. */
    private static final int NIGHT_RECHECK_TICKS = 1200;
    /** A run that cannot make a bed should stop trying rather than re-testing every night. */
    private static final int MAX_NIGHT_DIVERSION_FAILURES = 3;
    /**
     * Search reach for the two phases that hunt for something the player can see from where they
     * stand.
     * <p>
     * These were both fixed at 64, which is nowhere near how far a person can see. Standing in an
     * open plain with a treeline on the horizon, the bot would turn a full circle, index nothing,
     * and walk off on a guessed heading - while the trees were in plain view the whole time. The
     * real limit is the render distance, because that is the last chunk the client has loaded, so
     * these are caps on that rather than numbers in their own right.
     */
    private static final int WOOD_SEARCH_CAP = 160;
    /** A badlands spawn can have portal logs but no natural tree in the first few loaded chunks. */
    private static final int WOOD_SCOUT_ATTEMPTS = 12;
    private static final int WOOD_SCOUT_STEP = 24;
    private static final int IRON_SEARCH_CAP = 128;

    /** Take a structure when it is on the way, and carry a compact kit. The safe default. */
    private static final String ROUTE_BALANCED = "balanced";
    /** Raid what it walks past and carry a bigger reserve. Slower per hour, fewer dead runs. */
    private static final String ROUTE_SCAVENGE = "scavenge";
    /** Ignore structures and go underground for the iron immediately. */
    private static final String ROUTE_DIRECT = "direct";
    /** A standing table this close counts as having one; matches CraftTask's own reuse range. */
    private static final int NEARBY_TABLE_RADIUS = 4;
    /**
     * A barren spawn should get several genuinely different cave/ore views before the run is
     * abandoned. Each round either walks a fresh visible area or makes one short physical prospect;
     * the cap is deliberately finite so this never becomes an endless quarry at one location.
     */
    /** A barren area is not a reason to stop a run that is still making physical iron progress. */
    private static final int MAX_IRON_SEARCH_ROUNDS = 24;
    /** Vanilla plank recipe: one log in, four planks out. */
    private static final int PLANKS_PER_LOG = 4;
    /** Vanilla stick recipe: two planks in, four sticks out. */
    private static final int PLANKS_PER_CRAFT = 2;
    private static final int STICKS_PER_CRAFT = 4;
    /** Vanilla stone axe recipe. */
    private static final int STONE_AXE_STICKS = 2;
    private static final int STONE_AXE_COBBLE = 3;
    /**
     * The speedrun deliberately leaves its crafting table standing while it gathers stone, iron
     * and food. Those phases can move hundreds of blocks or descend a staircase, so the normal
     * short CraftTask lookup would report "need a crafting table" even though the bot already has
     * a remembered table it can walk back to.
     */
    private static final int LONG_ROUTE_TABLE_SEARCH_RADIUS =
            SpeedrunTablePolicy.LONG_ROUTE_TABLE_SEARCH_RADIUS;

    private enum Phase {
        START,
        WOOD,
        STONE,
        FOOD,
        IRON,
        FLINT,
        PORTAL,
        FORTRESS_SCAN,
        FORTRESS_APPROACH,
        BLAZE,
        NETHER_EXIT,
        PEARLS,
        BLAZE_POWDER,
        EYES,
        ENDGAME,
        DONE
    }

    private Phase phase = Phase.START;
    private Task current;
    private int phaseAttempts;
    /** An empty wood scan must lead to movement before the next scan, not another spin in place. */
    private boolean woodExplorePending;
    /** Explore remembers a visible tree; mine it on the following task tick. */
    private boolean woodMineAfterExplore;
    private BlockPos fortress;
    private BlockPos netherPortal;
    /** A completed empty iron mine must be followed by a physical scout to a new area. */
    private boolean ironExplorePending;
    private boolean ironScoutActive;
    /** Surface scouts that came back with nothing, which is what justifies a boat or a cave. */
    private int ironScoutFailures;
    /** One sea crossing per run; a second is wandering with extra steps. */
    private boolean ironSailed;
    /** True while walking to a cave mouth, so arriving resumes the ordinary ore search there. */
    private boolean ironDiving;
    private int hotbarTidyCooldown;
    /** Times the phase has put off digging to go looking; capped so it cannot become a loop. */
    private int ironDigDeferrals;
    /** Cave mouths already walked into, so the same opening is not chosen for ever. */
    private final Set<Long> divedCaveMouths = new HashSet<>();
    /** Leave a failed pocket before asking the next scout to search it again. */
    private boolean ironRelocationPending;
    /** True while the bounded inter-area walk is the active child task. */
    private boolean ironRelocationActive;
    private int ironSearchRounds;
    /** Total raw/ore/ingot iron seen so the area budget resets on real progress. */
    private int ironProgressCount;
    /** A hidden iron block seen by the scout; the next physical prospect returns to that pocket. */
    private BlockPos ironOreHint;
    /** Ore pockets that a physical prospect already proved unproductive for this run. */
    private final Set<Long> exhaustedIronPockets = new HashSet<>();
    /** The hint currently being worked, so a failed prospect can blacklist exactly that pocket. */
    private BlockPos activeIronProspectHint;
    /** A gravel search that found no flint must move before it is rebuilt. */
    private boolean flintExplorePending;
    private int flintSearchRounds;
    /**
     * Set once a surface recovery has proved it cannot improve the current position. Without it the
     * dry-ground gate is asked again on every attempt, and a phase whose whole retry budget goes on
     * the same impossible recovery fails the mission without ever looking for what it came for.
     * Cleared on every phase change, because the next phase starts somewhere else.
     */
    private boolean surfaceRecoveryUnavailable;
    /** One bounded visible Nether-container pass before the enderman fallback. */
    private boolean netherChestChecked;
    private final Set<Long> netherUnavailableContainers = new java.util.HashSet<>();
    /** A surface find the iron scout walked past and should work before digging any further. */
    private BlockPos opportunity;
    private SpeedrunOpportunity opportunityKind;
    /** Containers already emptied or proven unreachable, shared across every Overworld detour. */
    private final Set<Long> overworldContainers = new java.util.HashSet<>();
    /** Sites already worked, so the same rail or chest is not walked to twice. */
    private final Set<Long> workedOpportunities = new java.util.HashSet<>();
    /** Hunt retries must not forget which moving targets were already proven unreachable. */
    private final Set<Integer> unreachableBlazes = new java.util.HashSet<>();
    private final Set<Integer> unreachableEndermen = new java.util.HashSet<>();
    /** If no visible Overworld source exists, continue toward the Nether instead of circling. */
    private boolean foodDeferredToNether;
    /** Last Overworld position where the iron route checked for an opportunistic food source. */
    private BlockPos foodOpportunityAnchor;
    /** Location where carried-food use failed; retry only after the route has materially moved. */
    private BlockPos foodEatUnavailableAt;
    /** The active preparation task paused while a visible local food opportunity is handled. */
    private Task suspendedForFood;
    /** The active preparation task paused while the run makes camp or uses its bed. */
    private Task suspendedForNight;
    /** The active preparation task paused while a gravel bank in passing is stripped. */
    private Task suspendedForGravel;
    /** Ticks until the next gravel check, and a cooldown after one that came to nothing. */
    private int gravelCheckCooldown;
    /** Ticks until the next flock check; a full entity sweep every tick is not worth its cost. */
    private int nightCheckCooldown;
    /** Bed attempts that came to nothing, so a run without sheep stops asking every night. */
    private int nightDiversionFailures;
    /**
     * How often opportunistic side-work has taken the route away from the mission.
     * <p>
     * Counted because the cost of a diversion is not the time it spends, it is the time it takes
     * from the thing it interrupted. Forty-four failed eat attempts do not read as a problem one
     * at a time; "food 44/44 failed" does.
     */
    private int foodDiversions;
    private int foodDiversionsFailed;
    private int nightDiversions;
    /** Ticks until another urgent, hunger-driven food search may start. */
    private int hungrySearchCooldown;
    /**
     * A lava pool seen on the way past, so the portal phase does not have to go looking.
     * <p>
     * The portal only starts hunting for lava once it reaches the portal phase, from wherever it
     * happens to be standing - which means a run can walk right past a pool while mining iron and
     * then spend minutes searching for one. Somebody watching knows exactly where it was.
     */
    private BlockPos knownLavaPool;
    /** Times raw food has been put in a furnace, bounded so a failure cannot loop the phase. */
    private int cookAttempts;
    /** One attempt at taking stone from what is already built, before falling back to digging. */
    private boolean placedStoneTried;
    /** Ticks until the next sweep for village landmarks. */
    private int sightingCooldown;
    private final TargetIndex opportunityIndex = new TargetIndex();
    private final TargetIndex lavaIndex = new TargetIndex();
    /** Debounced centre for both awareness scans; never the live position (see scan-anchor rule). */
    private BlockPos sightingAnchor;
    /** Hay already taken from this village's farms, so one field is not stripped twice. */
    private boolean hayTaken;
    /** One water crossing per flint phase, before falling back to digging for gravel. */
    private boolean flintBoatTried;
    /** One walk to the waterline per flint phase, before the crossing or the dig are considered. */
    private boolean flintShoreTried;
    /** One shovel attempt: a craft that cannot finish must not be reopened every tick. */
    private boolean diamondShovelTried;
    /** Knapping attempts made this flint phase; bounded so unlucky gravel cannot become a loop. */
    private int gravelKnapAttempts;
    /** One wood top-up per run, taken while the axe makes it cheap. */
    private boolean toppedUpWood;
    /** Mission telemetry detects a child task that keeps rebuilding without changing the route. */
    private int routeTicks;
    private int routeNoProgressTicks;
    private String lastRouteSignature = "";
    private BlockPos lastRoutePosition;
    private Phase lastRoutePhase;
    private String status = "";
    /** Fixed high-level route style; learning happens inside concrete skills such as chopping. */
    private String routeStyle = ROUTE_BALANCED;

    @Override
    public String name() {
        return "Speedrun: Complete the Game";
    }

    @Override
    public boolean automaticSkillLearning() {
        // The category time is mission telemetry; its concrete child jobs receive policy reward.
        return false;
    }

    /**
     * Whether to stop and deal with the night.
     * <p>
     * Two separate moments, deliberately sharing one entry point. Sheep are opportunistic: they are
     * only worth stopping for while they are in view, whatever the time of day, because a flock is
     * a bed and a bed is every following night. Actually going to sleep is the opposite - it has to
     * wait until the world says a bed will be accepted, and by then the flock is long gone.
     */
    private boolean shouldMakeCamp(BotContext ctx) {
        if (suspendedForNight != null || current instanceof SleepTask) {
            return false;
        }
        if (nightDiversionFailures >= MAX_NIGHT_DIVERSION_FAILURES) {
            return false;
        }
        if (ctx.level.dimension() != Level.OVERWORLD || !isOverworldPreparation()) {
            return false;
        }
        boolean carryingBed = SleepTask.hasBed(ctx);
        int wool = SleepTask.carriedWool(ctx);
        if (SleepTask.canSleepNow(ctx)
                && (carryingBed || wool >= BedPolicy.WOOL_PER_BED)) {
            // Cut wool is a bed one craft away. Without this, a flock taken at noon is carried
            // around all night and the run sleeps through exactly none of it.
            return true;
        }
        // The flock scan walks every entity in a 32-block box, so it is rate-limited rather than
        // run on every tick of a mining route.
        if (nightCheckCooldown > 0) {
            nightCheckCooldown--;
            return false;
        }
        nightCheckCooldown = NIGHT_CHECK_INTERVAL_TICKS;
        return BedPolicy.worthDivertingForSheep(
                SleepTask.visibleSheep(ctx, SHEEP_DIVERSION_RADIUS), wool, carryingBed);
    }

    /**
     * Counts blocks of a kind the bot can actually see, stopping early once the answer is enough.
     * Visibility matters here for the same reason it does everywhere else: a wall of cobblestone
     * buried under a hill is not a shortcut.
     */
    private int countVisible(BotContext ctx, Set<Block> blocks, int radius) {
        BlockPos feet = ctx.player.blockPosition();
        int found = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -6; dy <= 6; dy++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!ctx.level.isLoaded(cursor)) {
                        continue;
                    }
                    if (!blocks.contains(ctx.level.getBlockState(cursor).getBlock())) {
                        continue;
                    }
                    if (!Vision.isVisible(ctx, cursor)) {
                        continue;
                    }
                    if (++found >= VISIBLE_COUNT_CAP) {
                        return found;
                    }
                }
            }
        }
        return found;
    }

    /**
     * Notices a village while walking through it, in every phase rather than only while looking
     * for iron.
     * <p>
     * The opportunity scout used to run inside the iron search alone, which meant a run could take
     * wood from a village's houses, walk past its smithy, and go and dig a mineshaft for the iron
     * that was in the chest it had just squeezed past. The sighting is remembered here and consumed
     * by the iron phase, so passing a smithy at any point books the visit.
     */
    private void sightOpportunities(BotContext ctx) {
        if (opportunity != null || ROUTE_DIRECT.equals(routeStyle)) {
            return;
        }
        if (ctx.level.dimension() != Level.OVERWORLD || !isOverworldPreparation()) {
            return;
        }
        if (sightingCooldown > 0) {
            sightingCooldown--;
            return;
        }
        sightingCooldown = SIGHTING_INTERVAL_TICKS;

        BlockPos feet = ctx.player.blockPosition();
        Set<Block> markers = SpeedrunOpportunity.allMarkers();
        // The radius is the player's view distance and stays that way; what has to be bounded is
        // the cost, not the reach. Three rules keep a 256-block awareness sweep affordable:
        //
        //  1. The index is keyed on a coarse anchor, never the live position. Keyed on feet it
        //     rebuilt the whole region every sweep while walking, which is the lag a person feels
        //     as the game stuttering every two seconds.
        //  2. The scan is palette-pruned, so chunk sections holding no marker blocks cost nothing.
        //  3. Ray casts are budgeted per sweep. The bounded search also skips candidates sealed on
        //     all six faces for free, which is most of an underground index, and its cursor
        //     carries across sweeps so the whole region still gets covered over time.
        if (sightingAnchor == null
                || sightingAnchor.distSqr(feet) > (double) SIGHTING_REANCHOR * SIGHTING_REANCHOR) {
            sightingAnchor = feet.immutable();
        }
        int radius = sightingRadius(ctx);
        int yMin = Math.max(ctx.level.getMinY(), sightingAnchor.getY() - SIGHTING_VERTICAL);
        int yMax = Math.min(ctx.level.getMaxY() - 1, sightingAnchor.getY() + SIGHTING_VERTICAL);
        if (!opportunityIndex.isUsable(sightingAnchor, markers, radius, yMin, yMax)) {
            opportunityIndex.rebuild(ctx.level, sightingAnchor, markers, radius, yMin, yMax);
        }
        // Every candidate must pass the same line-of-sight test as an ore or a tree - without it
        // this is an X-ray that notices mineshaft rails through forty blocks of stone. A smithy
        // still outranks everything, so the single budgeted pass returns on a smithy and records
        // the nearest other visible structure as the fallback.
        BlockPos[] fallback = new BlockPos[1];
        BlockPos best = opportunityIndex.nearestBounded(ctx.level, sightingAnchor, workedOpportunities,
                (pos, state) -> {
                    SpeedrunOpportunity kind = SpeedrunOpportunity.classify(state.getBlock());
                    if (kind == null) {
                        return false;
                    }
                    if (!ctx.omniscientMining() && !Vision.isVisible(ctx, pos)) {
                        return false;
                    }
                    if (kind == SpeedrunOpportunity.BLACKSMITH) {
                        return true;
                    }
                    if (fallback[0] == null) {
                        fallback[0] = pos.immutable();
                    }
                    return false;
                }, SIGHTING_RAY_BUDGET);
        if (best == null) {
            best = fallback[0];
        }
        SpeedrunOpportunity bestKind = best == null
                ? null : SpeedrunOpportunity.classify(ctx.level.getBlockState(best).getBlock());

        rememberLavaPool(ctx, feet);

        if (best == null || bestKind == null) {
            return;
        }
        // A mineshaft is exposed iron and free chests, and it is also where cave spiders live.
        // Widening the sighting radius made them findable and promptly walked a run into one: the
        // journal shows health going 18 to 1 on repeated `dmg=magic`, which is spider venom, and it
        // survived only because poison cannot land the last half heart. A spawner in the room is
        // the thing that makes the difference, and it is cheap to check for.
        if (bestKind == SpeedrunOpportunity.MINESHAFT && spawnerNear(ctx, best)) {
            workedOpportunities.add(best.asLong());
            ctx.debug.decide("mineshaft at " + best.toShortString()
                    + " has a spawner beside it; leaving it alone");
            return;
        }
        opportunity = best;
        opportunityKind = bestKind;
        ctx.debug.decide("noted a " + bestKind.label() + " at " + best.toShortString()
                + "; the iron phase will call there first");
    }

    /**
     * Whether the bot is standing in a village.
     * <p>
     * Bells, composters, lecterns and smokers do not generate anywhere else, so one sighting is
     * evidence rather than a guess - the same standard {@link SpeedrunOpportunity} uses. This is
     * the reason not to sail away from a coast: a village already holds the smithy iron and the
     * hay this phase would be leaving to look for.
     */
    private boolean villageNearby(BotContext ctx) {
        return countVisible(ctx, VILLAGE_MARKERS, VILLAGE_RADIUS) > 0;
    }

    /** Whether a monster spawner sits close enough to make a sighting not worth taking. */
    private static boolean spawnerNear(BotContext ctx, BlockPos site) {
        return BlockScanner.findNearest(ctx.level, site, Set.of(Blocks.SPAWNER), SPAWNER_GUARD_RADIUS,
                ctx.level.getMinY(), ctx.level.getMaxY(), Set.of()) != null;
    }


    /** Notes the nearest visible lava source while passing, for the portal phase to come back to. */
    private void rememberLavaPool(BotContext ctx, BlockPos feet) {
        if (knownLavaPool != null && ctx.level.getBlockState(knownLavaPool).getFluidState().isSource()
                && ctx.level.getBlockState(knownLavaPool).getBlock() == Blocks.LAVA) {
            return;
        }
        knownLavaPool = null;
        // Same cost rules as the structure sweep: the reach is the view distance, the price is
        // bounded. The old BlockScanner call at this radius was the single heaviest thing the bot
        // did, every two seconds, forever. Sealed lava is skipped for free by the exposed-face
        // check, which is most lava; a surface pool has its top face open and passes.
        if (sightingAnchor == null) {
            return;
        }
        int radius = sightingRadius(ctx);
        int yMin = Math.max(ctx.level.getMinY(), sightingAnchor.getY() - SIGHTING_VERTICAL);
        int yMax = Math.min(ctx.level.getMaxY() - 1, sightingAnchor.getY() + SIGHTING_VERTICAL);
        if (!lavaIndex.isUsable(sightingAnchor, LAVA_ONLY, radius, yMin, yMax)) {
            lavaIndex.rebuild(ctx.level, sightingAnchor, LAVA_ONLY, radius, yMin, yMax);
        }
        BlockPos found = lavaIndex.nearestBounded(ctx.level, sightingAnchor, Set.of(),
                (pos, state) -> state.getFluidState().isSource()
                        && (ctx.omniscientMining() || Vision.isVisible(ctx, pos)),
                LAVA_RAY_BUDGET);
        if (found != null) {
            knownLavaPool = found;
            ctx.debug.decide("noted lava at " + found.toShortString()
                    + "; the portal phase will come back here");
        }
    }

    /** The Overworld phases where a night is an interruption rather than the mission itself. */
    private boolean isOverworldPreparation() {
        return phase == Phase.WOOD || phase == Phase.STONE || phase == Phase.FOOD
                || phase == Phase.IRON || phase == Phase.FLINT || phase == Phase.PORTAL;
    }

    private String describeRouteStyle() {
        return switch (routeStyle) {
            case ROUTE_SCAVENGE -> "raid every structure on the way, take more visible food";
            case ROUTE_DIRECT -> "ignore structures, dig for iron straight away";
            default -> "take structures that are on the way, compact supplies";
        };
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        phase = Phase.START;
        current = null;
        phaseAttempts = 0;
        woodExplorePending = false;
        woodMineAfterExplore = false;
        fortress = null;
        netherPortal = null;
        ironExplorePending = false;
        ironScoutActive = false;
        ironScoutFailures = 0;
        ironSailed = false;
        ironDiving = false;
        ironDigDeferrals = 0;
        divedCaveMouths.clear();
        ironRelocationPending = false;
        ironRelocationActive = false;
        ironSearchRounds = 0;
        ironProgressCount = 0;
        ironOreHint = null;
        exhaustedIronPockets.clear();
        activeIronProspectHint = null;
        flintExplorePending = false;
        flintSearchRounds = 0;
        surfaceRecoveryUnavailable = false;
        opportunity = null;
        opportunityKind = null;
        overworldContainers.clear();
        workedOpportunities.clear();
        netherChestChecked = false;
        netherUnavailableContainers.clear();
        unreachableBlazes.clear();
        unreachableEndermen.clear();
        foodDeferredToNether = false;
        foodOpportunityAnchor = null;
        foodEatUnavailableAt = null;
        suspendedForFood = null;
        suspendedForNight = null;
        nightCheckCooldown = 0;
        nightDiversionFailures = 0;
        foodDiversions = 0;
        foodDiversionsFailed = 0;
        nightDiversions = 0;
        hungrySearchCooldown = 0;
        cookAttempts = 0;
        knownLavaPool = null;
        placedStoneTried = false;
        sightingCooldown = 0;
        sightingAnchor = null;
        hayTaken = false;
        flintBoatTried = false;
        flintShoreTried = false;
        diamondShovelTried = false;
        gravelKnapAttempts = 0;
        gravelCheckCooldown = 0;
        suspendedForGravel = null;
        toppedUpWood = false;
        routeTicks = 0;
        routeNoProgressTicks = 0;
        lastRouteSignature = "";
        lastRoutePosition = null;
        lastRoutePhase = null;
        status = "choosing a route";
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (phase == Phase.START) {
            chooseStartingPhase(ctx);
        }

        publishRouteTelemetry(ctx);
        sightOpportunities(ctx);
        tidyHotbar(ctx);

        if (phase == Phase.DONE) {
            status = "ender dragon killed and returned to the overworld";
            return TaskStatus.SUCCESS;
        }

        if (current == null) {
            Task next = createTask(ctx);
            if (next == null) {
                // createTask may advance a phase after discovering that its inventory requirement
                // is already satisfied. Re-enter on the next tick rather than ticking a finished
                // child task, which is an easy source of stuck tasks.
                return TaskStatus.RUNNING;
            }
            current = next;
            current.start(ctx);
        }

        // A long ExploreTask or physical prospect can move far past the last food check. Look
        // again at bounded distance intervals, but only interrupt for a source already visible in
        // the current loaded view. An empty sightline merely re-arms the next local check; it never
        // creates a food expedition and the active work is left untouched.
        // Urgency first, and deliberately not behind the opportunistic distance gate. Below the
        // sprint threshold every later step is slower, including the walk to the food that fixes
        // it - so "have I wandered far enough to look around" is the wrong question to ask here.
        if (suspendedForFood == null && shouldSearchForFoodUrgently(ctx)
                && !(current instanceof FoodTask) && !(current instanceof EatTask)) {
            hungrySearchCooldown = HUNGRY_SEARCH_COOLDOWN_TICKS;
            current.onPause(ctx);
            suspendedForFood = current;
            foodDiversions++;
            // A wreck or a village chest beats chasing animals, and by a wide margin: a chest is a
            // stack of bread or a few cooked fish standing still, while a chicken is two raw meat
            // at the end of a chase and a salmon is that plus a swim. If one has been noticed, go
            // there rather than starting a hunt.
            BlockPos supplies = opportunity != null && opportunityKind == SpeedrunOpportunity.CONTAINER
                    ? opportunity : null;
            if (supplies != null) {
                opportunity = null;
                opportunityKind = null;
                workedOpportunities.add(supplies.asLong());
                current = new OpportunityTask(SpeedrunOpportunity.CONTAINER, supplies, overworldContainers);
                status = describe() + " - hungry; raiding the supplies noticed earlier";
                ctx.debug.decide("hungry, and a chest was noted at " + supplies.toShortString()
                        + "; take that before hunting");
            } else {
                current = new FoodTask(OPPORTUNISTIC_FOOD, false);
                status = describe() + " - too hungry to sprint; going to find food";
                ctx.debug.decide("under the sprint threshold with nothing to eat; searching for food");
            }
            current.start(ctx);
        } else if (suspendedForFood == null && shouldEatCarriedFood(ctx)
                && !(current instanceof FoodTask) && !(current instanceof EatTask)) {
            current.onPause(ctx);
            suspendedForFood = current;
            foodDiversions++;
            current = new EatTask(stack -> stack.has(DataComponents.FOOD), 14);
            current.start(ctx);
            status = describe() + " - eating carried food before the route continues";
        } else if (suspendedForFood == null && suspendedForGravel == null && shouldGrabGravel(ctx)) {
            // Take the gravel while it is here. The flint phase is a search for a bank like this
            // one, so a bank walked past now is a shoreline walked to later.
            current.onPause(ctx);
            suspendedForGravel = current;
            gravelCheckCooldown = GRAVEL_RECHECK_TICKS;
            current = new MineTask(Set.of(Blocks.GRAVEL), GRAVEL_DIVERSION_RADIUS,
                    ctx.level.getMinY(), ctx.level.getMaxY(),
                    GRAVEL_RESERVE - InventoryHelper.count(ctx.player, Items.GRAVEL),
                    false, false, false, true, 0, 0);
            current.start(ctx);
            status = describe() + " - taking gravel in passing for the flint later";
            ctx.debug.decide("gravel in sight; carry a few so the flint phase starts with material");
            return TaskStatus.RUNNING;
        } else if (suspendedForFood == null && shouldDivertToOpportunity(ctx)) {
            // Noting a wreck and then walking past it is the complaint this answers. The sighting
            // was only consumed where the iron phase picks its *next* task, so a structure spotted
            // two thousand ticks into a march to some far-off ore search sat in the field until
            // that march ended - by which time it was usually out of range and dropped. What is in
            // sight now is worth more than where the route was already going, so interrupt it, and
            // let the existing suspend/resume put the march back afterwards.
            BlockPos site = opportunity;
            SpeedrunOpportunity kind = opportunityKind;
            opportunity = null;
            opportunityKind = null;
            workedOpportunities.add(site.asLong());
            current.onPause(ctx);
            suspendedForFood = current;
            current = new OpportunityTask(kind, site, overworldContainers);
            current.start(ctx);
            status = describe() + " - calling at the " + kind.label() + " spotted on the way";
            ctx.debug.decide("a " + kind.label() + " is in sight at " + site.toShortString()
                    + "; call there now rather than after the current march");
            return TaskStatus.RUNNING;
        } else if (suspendedForFood == null && shouldCheckPreparationFood(ctx)
                && !(current instanceof FoodTask) && !(current instanceof EatTask)) {
            foodOpportunityAnchor = ctx.player.blockPosition().immutable();
            // A hay farm beats anything with legs. Twelve bales is thirty-six bread off blocks
            // standing still in the open; the same minute spent hunting buys two steaks and a
            // chase, so the farm is checked first whenever one is actually in sight.
            if (!hayTaken && HayHarvestTask.visibleHay(ctx, SHEEP_DIVERSION_RADIUS) > 0) {
                hayTaken = true;
                current.onPause(ctx);
                suspendedForFood = current;
                foodDiversions++;
                current = new HayHarvestTask(SHEEP_DIVERSION_RADIUS);
                current.start(ctx);
                status = describe() + " - stripping a hay farm for bread";
                return TaskStatus.RUNNING;
            }
            FoodTask opportunityFood = new FoodTask(OPPORTUNISTIC_FOOD, true);
            if (opportunityFood.hasImmediateOpportunity(ctx)) {
                current.onPause(ctx);
                suspendedForFood = current;
                foodDiversions++;
                current = opportunityFood;
                current.start(ctx);
                status = describe() + " - taking a visible food opportunity";
            }
        } else if (suspendedForFood == null && shouldMakeCamp(ctx)) {
            // A bed is cheaper than the night it removes. Mobs that never spawn cost no fighting,
            // no healing and no Self Preservation interruptions, and the hunger clock runs through
            // the night in one tick instead of a walk - which is why this is worth a short stop
            // even in a speedrun, and why the food phase can stay opportunistic.
            current.onPause(ctx);
            suspendedForNight = current;
            nightDiversions++;
            // Never idle waiting for dusk: on a bright afternoon this makes the bed and hands the
            // route straight back, and the same check picks it up again once it is actually dark.
            current = new SleepTask(false, true);
            current.start(ctx);
            status = describe() + " - making camp for the night";
        }

        TaskStatus result = current.tick(ctx);
        status = describe() + " - " + current.status();
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }

        Task finished = current;
        if (result == TaskStatus.FAILED) {
            // Captured before onStop, and before this method's own status overwrites it: the
            // child's reason is the only place that says *why*, and it is gone one line later.
            ctx.debug.recordFailure(finished.name(), finished.status());
            if (finished instanceof EatTask || finished instanceof FoodTask) {
                foodDiversionsFailed++;
            }
        }
        current.stop(ctx);
        current = null;

        if (finished instanceof SurfaceRecoveryTask) {
            // Record the verdict before the retry logic runs, so a recovery that cannot reconnect
            // is asked for once rather than on every remaining attempt of the phase.
            surfaceRecoveryUnavailable = SurfaceRecoveryPolicy.recoveryUnavailable(
                    result == TaskStatus.FAILED,
                    SurfaceRecoveryTask.needsDryGroundRecovery(ctx));
        }

        // Resume the exact long-running task after opportunistic food side-work. Keeping it
        // suspended instead of rebuilding it preserves its scanner, route and bounded progress.
        if ((finished instanceof FoodTask || finished instanceof EatTask
                || finished instanceof HayHarvestTask || finished instanceof OpportunityTask)
                && suspendedForFood != null) {
            if (finished instanceof EatTask && result == TaskStatus.FAILED) {
                // An eat interaction that did not raise the hunger level is not a successful food
                // opportunity. Resume the route once, but do not immediately reopen the same eat
                // task on the next tick and freeze the mission in an eat/retry loop.
                foodEatUnavailableAt = ctx.player.blockPosition().immutable();
                ctx.debug.decide("food was carried but could not be consumed; continue the route");
            }
            current = suspendedForFood;
            suspendedForFood = null;
            current.onResume(ctx);
            if (phase == Phase.IRON && hasFood(ctx)) {
                foodDeferredToNether = false;
            }
            status = describe() + " - food opportunity handled; resuming the route";
            return TaskStatus.RUNNING;
        }

        // Resume the route after a gravel bank, the same way food and camp do. A bank that could
        // not be worked is not worth reporting: the flint phase will look for its own.
        if (suspendedForGravel != null) {
            current = suspendedForGravel;
            suspendedForGravel = null;
            current.onResume(ctx);
            status = describe() + " - gravel taken; resuming the route";
            return TaskStatus.RUNNING;
        }

        // Resume the exact preparation task after the camp side-work, the same way food does.
        if (finished instanceof SleepTask && suspendedForNight != null) {
            if (result == TaskStatus.FAILED) {
                nightDiversionFailures++;
                ctx.debug.decide("no bed available here; carrying on through the night");
            }
            // Whatever happened, do not re-open the same question on the next tick.
            nightCheckCooldown = NIGHT_RECHECK_TICKS;
            current = suspendedForNight;
            suspendedForNight = null;
            current.onResume(ctx);
            status = describe() + " - camp handled; resuming the route";
            return TaskStatus.RUNNING;
        }

        if (phase == Phase.IRON && ironRelocationActive) {
            // Relocation is a recovery child, not a gather attempt. Whether the route reached
            // its waypoint or was blocked, the next tick must scout from the new position
            // instead of rebuilding the failed prospect in the old pocket.
            ironRelocationActive = false;
            ironRelocationPending = false;
            ironExplorePending = true;
            phaseAttempts = 0;
            ctx.debug.decide(result == TaskStatus.SUCCESS
                    ? "relocation reached; scout the new iron area"
                    : "relocation was blocked; scout from the best new position");
            status = describe() + " - relocation ended; scouting a new area";
            return TaskStatus.RUNNING;
        }

        if (result == TaskStatus.FAILED) {
            if (phase == Phase.IRON && finished instanceof MineTask) {
                // A failed ore approach is not made better by recreating it at the same block.
                // The physical prospect may already have mined part of a pocket, so this is a
                // search-area verdict rather than a generic gather-attempt failure. Reset the
                // phase retry counter and send the scout to a genuinely new loaded area; the
                // separate iron-search cap still keeps a barren world bounded.
                ironExplorePending = true;
                ironScoutActive = false;
                ironScoutFailures++;
                ironOreHint = null;
                if (activeIronProspectHint != null) {
                    exhaustedIronPockets.add(activeIronProspectHint.asLong());
                    ctx.debug.decide("blacklist the exhausted iron pocket before the next scout");
                    activeIronProspectHint = null;
                }
                ironRelocationPending = true;
                ironSearchRounds++;
                if (ironSearchRounds >= MAX_IRON_SEARCH_ROUNDS) {
                    status = describe() + " failed - no visible iron after "
                            + ironSearchRounds + " searched areas";
                    return TaskStatus.FAILED;
                }
                phaseAttempts = 0;
                status = describe() + " - physical iron approach failed; scouting a new area";
                return TaskStatus.RUNNING;
            } else if (phase == Phase.IRON && finished instanceof ExploreTask) {
                // A scout that exhausted its walking stops has already delivered its location
                // verdict. Rebuilding the same scout only repeats the same views; hand the phase
                // to MineTask so its bounded physical staircase can expose an ore pocket behind
                // dirt/grass or a cave lip. A later MineTask failure sets ironExplorePending again
                // and sends the bot to a genuinely new area.
                // An exhausted remembered pocket asks for another scout directly; an otherwise
                // empty scout still falls through to the one bounded physical prospect.
                if (!ironExplorePending) {
                    ironExplorePending = false;
                }
                ironScoutActive = false;
                // A failed surface scout is a failed surface scout, and this is the only place one
                // can be observed. Counting only MineTask failures left the strategy's counter at
                // zero however many walks came back with nothing, so SAIL and DIVE were unreachable
                // and the phase re-scouted for ever - the "never thinks of the boat" behaviour.
                ironScoutFailures++;
                // Blocked in every direction with water in reach is not a bad scout, it is a coast.
                // Waiting for a second identical failure just buys another lap of the same shore.
                if (BoatTask.crossingNearby(ctx, FLINT_CROSSING_RADIUS)) {
                    ironScoutFailures = Math.max(ironScoutFailures,
                            IronStrategyPolicy.SCOUTS_BEFORE_SWITCHING);
                    ctx.debug.decide("the search is walled in and there is open water; a boat is the way out");
                }
                if (finished instanceof ExploreTask explore) {
                    BlockPos candidate = explore.getLastCandidate();
                    if (candidate != null) {
                        Block block = ctx.level.getBlockState(candidate).getBlock();
                        if ((block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE)
                                && !exhaustedIronPockets.contains(candidate.asLong())) {
                            ironOreHint = candidate.immutable();
                            ctx.debug.decide("remember the occluded iron pocket for the physical prospect");
                        } else if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
                            ctx.debug.decide("skip the already exhausted iron pocket; keep scouting");
                        }
                    }
                }
                // A matching block that is already on the exhausted list is not a reason to
                // enter the physical fallback again. Keep the scout pending so the next task is
                // forced to leave this pocket and use the larger displacement for the next area.
                if (finished instanceof ExploreTask explore
                        && explore.getLastCandidate() != null
                        && exhaustedIronPockets.contains(explore.getLastCandidate().asLong())) {
                    ironExplorePending = true;
                }
                // A failed scout commonly means the current cave/shoreline has no walkable path
                // to its next point. Do not let retryable rebuild another scout at the same feet;
                // first make a bounded, break-capable relocation so the next scan has new ground.
                ironRelocationPending = true;
                ironSearchRounds++;
                if (ironSearchRounds >= MAX_IRON_SEARCH_ROUNDS) {
                    status = describe() + " failed - no visible iron after "
                            + ironSearchRounds + " searched areas";
                    return TaskStatus.FAILED;
                }
                phaseAttempts = 0;
                status = describe() + " - scout route failed; relocating before the next search";
                return TaskStatus.RUNNING;
            }
            if (retryable(ctx, finished)) {
                status = describe() + " - retrying after " + finished.status();
                return TaskStatus.RUNNING;
            }
            status = describe() + " failed - " + finished.status();
            return TaskStatus.FAILED;
        }

        if (phase == Phase.FOOD && finished instanceof FoodTask food
                && food.exhaustedWithoutFood()) {
            foodDeferredToNether = true;
            phase = Phase.IRON;
            phaseAttempts = 0;
            ironExplorePending = true;
            status = describe() + " - no visible Overworld food; continuing toward Nether fallback";
            return TaskStatus.RUNNING;
        }

        // An opportunistic food pass is side-work for the iron route. It must never advance the
        // mission or rebuild a full food search after taking one nearby source.
        if (phase == Phase.IRON && finished instanceof FoodTask) {
            if (hasFood(ctx)) {
                foodDeferredToNether = false;
            }
            status = describe() + " - nearby food handled; continuing toward iron";
            return TaskStatus.RUNNING;
        }

        if (phase == Phase.IRON && finished instanceof MineTask mine) {
            // A nearHint prospect is deliberately bounded. Breaking a sandstone/dirt blocker is
            // progress for MineTask, but it is not iron; without this separate verdict the same
            // visible pocket can be rediscovered and reopened forever after every short staircase.
            if (activeIronProspectHint != null) {
                exhaustedIronPockets.add(activeIronProspectHint.asLong());
                ctx.debug.decide("finish the bounded iron prospect and blacklist its pocket");
                activeIronProspectHint = null;
                if (!ironSuppliesReady(ctx)) {
                    ironSearchRounds++;
                    if (ironSearchRounds >= MAX_IRON_SEARCH_ROUNDS) {
                        status = describe() + " failed - no visible iron after "
                                + ironSearchRounds + " searched areas";
                        return TaskStatus.FAILED;
                    }
                    ironExplorePending = true;
                    ironRelocationPending = true;
                    status = describe() + " - prospect ended; scouting a new area";
                    return TaskStatus.RUNNING;
                }
            }
        }

        if (phase == Phase.IRON && finished instanceof MineTask mine
                && SpeedrunIronPolicy.shouldScoutAfterMine(mine.minedTargets() > 0,
                ironSuppliesReady(ctx))) {
            if (mine.minedTargets() == 0 && activeIronProspectHint != null) {
                exhaustedIronPockets.add(activeIronProspectHint.asLong());
                ctx.debug.decide("blacklist the exhausted iron pocket before the next scout");
                activeIronProspectHint = null;
            }
            ironSearchRounds++;
            if (ironSearchRounds >= MAX_IRON_SEARCH_ROUNDS) {
                status = describe() + " failed - no visible iron after "
                        + ironSearchRounds + " searched areas";
                return TaskStatus.FAILED;
            }
            ironExplorePending = true;
            ironRelocationPending = true;
            status = describe() + " - no iron in this pocket; scouting a new area";
            return TaskStatus.RUNNING;
        }

        if (phase == Phase.FLINT && finished instanceof MineTask mine
                && !InventoryHelper.has(ctx.player, Items.FLINT, 1)) {
            flintSearchRounds++;
            if (flintSearchRounds >= MAX_GATHER_ATTEMPTS) {
                status = describe() + " failed - no flint after "
                        + flintSearchRounds + " searched areas";
                return TaskStatus.FAILED;
            }
            // MineTask reports an empty visible scan as success. Rebuilding it at the same cave
            // floor would repeat the same head turn forever, so make the next attempt leave the
            // pocket (and, when possible, reach the surface gravel a player would actually use).
            flintExplorePending = true;
            status = describe() + " - no flint in this spot; moving before the next search";
            return TaskStatus.RUNNING;
        }

        if (phase == Phase.WOOD && finished instanceof SurfaceRecoveryTask) {
            // Reaching dry ground is a prerequisite for the wood phase, not completion of the
            // phase itself. Let woodTask re-derive its inventory requirement on the next tick.
            status = describe() + " - reached dry ground; looking for visible wood";
            return TaskStatus.RUNNING;
        }

        // A visible Nether chest or emergency food task can succeed while still leaving the
        // pearl count short. Do not let that useful side-work accidentally send the run back
        // through the portal; the next tick will continue with the bounded enderman fallback.
        if (phase == Phase.PEARLS
                && !InventoryHelper.has(ctx.player, Items.ENDER_PEARL, PEARLS_NEEDED)) {
            status = describe() + " - supplies collected; continuing until pearls are ready";
            return TaskStatus.RUNNING;
        }

        // Food is an interruption, not completion of the blaze objective. Without this guard a
        // successful emergency meal would advance directly to pearls with too few rods, and the
        // eventual powder craft would fail deterministically.
        if (phase == Phase.BLAZE
                && !InventoryHelper.has(ctx.player, Items.BLAZE_ROD, BLAZE_RODS_NEEDED)) {
            status = describe() + " - food handled; continuing until rods are ready";
            return TaskStatus.RUNNING;
        }

        advance(ctx, finished);
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(BotContext ctx) {
        if (current != null) {
            current.stop(ctx);
            current = null;
        }
        if (suspendedForFood != null) {
            suspendedForFood.stop(ctx);
            suspendedForFood = null;
        }
        ctx.input.reset();
    }

    private void chooseStartingPhase(BotContext ctx) {
        phaseAttempts = 0;
        if (ctx.level.dimension() == Level.END) {
            phase = Phase.ENDGAME;
            return;
        }
        if (ctx.level.dimension() == Level.NETHER) {
            if (!InventoryHelper.has(ctx.player, Items.BLAZE_ROD, BLAZE_RODS_NEEDED)) {
                phase = Phase.FORTRESS_SCAN;
            } else if (!InventoryHelper.has(ctx.player, Items.ENDER_PEARL, PEARLS_NEEDED)) {
                phase = Phase.PEARLS;
            } else {
                phase = Phase.NETHER_EXIT;
            }
            return;
        }

        phase = overworldResumePhase(ctx);
    }

    /**
     * Works out where the run actually got to, from what is in the bag.
     * <p>
     * This runs on every start, which includes rejoining the world, so it is what decides whether a
     * resumed run continues or begins again. Anything short of a full check means a bot that walks
     * back out for logs while carrying a stone pickaxe - it has the kit, it just does not know it.
     * <p>
     * Checked from the end backwards, so the furthest evidence wins. Each phase re-tests its own
     * requirements when entered and skips itself if they are already met, so landing one phase early
     * is cheap; landing one phase late means redoing work that was already done.
     */
    private Phase overworldResumePhase(BotContext ctx) {
        if (InventoryHelper.has(ctx.player, Items.ENDER_EYE, 12) && hasWeapon(ctx) && hasFood(ctx)) {
            return Phase.ENDGAME;
        }
        if (portalKitReady(ctx) || InventoryHelper.has(ctx.player, Items.FLINT, 1)) {
            return Phase.PORTAL;
        }
        if (ironSuppliesReady(ctx)) {
            return Phase.FLINT;
        }
        if (stoneKitReady(ctx)) {
            return Phase.IRON;
        }
        if (woodKitReady(ctx)) {
            return Phase.STONE;
        }
        return Phase.WOOD;
    }

    /**
     * The wood phase exists to produce a crafting table and the sticks for a stone kit. Once a stone
     * pickaxe exists those sticks have already been spent on it, so an empty stick slot is proof the
     * phase finished, not proof it never ran. The table is still required either way, because every
     * later phase crafts against one.
     */
    private static boolean woodKitReady(BotContext ctx) {
        if (!hasCraftingTable(ctx)) {
            return false;
        }
        return (hasStonePickaxeOrBetter(ctx) || InventoryHelper.has(ctx.player, Items.STICK, 8))
                && woodProducts(ctx) >= SpeedrunResourcePolicy.woodProductsNeeded(needsShield(ctx));
    }

    private static boolean stoneKitReady(BotContext ctx) {
        return hasStonePickaxeOrBetter(ctx) && hasWeapon(ctx)
                && remainingStoneCost(ctx) == 0;
    }

    private static boolean ironSuppliesReady(BotContext ctx) {
        return SpeedrunIronPolicy.suppliesReady(
                InventoryHelper.count(ctx.player, Items.IRON_INGOT), ironNeeded(ctx));
    }

    /**
     * Iron this run still has to find, charged only for the tools it does not already carry. A
     * flint and steel out of a ruined portal chest pays for itself; enough obsidian to raise a
     * frame removes the bucket too.
     */
    private static int ironNeeded(BotContext ctx) {
        return SpeedrunIronPolicy.ironNeeded(
                InventoryHelper.has(ctx.player, Items.BUCKET, 1),
                hasLighter(ctx),
                obsidianRouteReady(ctx));
    }

    /** How far a look-for-it-and-walk-to-it search may reach: what the client has actually loaded. */
    private static int sightRadius(BotContext ctx, int cap) {
        return Math.max(32, Math.min(cap, (int) Vision.maxRange(ctx)));
    }

    /** Anything that lights a portal. A looted fire charge does the job a flint and steel does. */
    private static boolean hasLighter(BotContext ctx) {
        return InventoryHelper.has(ctx.player, Items.FLINT_AND_STEEL, 1)
                || InventoryHelper.has(ctx.player, Items.FIRE_CHARGE, 1);
    }

    /** True when a frame can be raised from carried obsidian instead of cast out of lava. */
    private static boolean obsidianRouteReady(BotContext ctx) {
        return SpeedrunIronPolicy.obsidianRouteReady(
                InventoryHelper.count(ctx.player, Items.OBSIDIAN), hasLighter(ctx));
    }

    private static boolean portalKitReady(BotContext ctx) {
        if (obsidianRouteReady(ctx)) {
            return true;
        }
        return InventoryHelper.has(ctx.player, Items.BUCKET, 1) && hasLighter(ctx);
    }

    /**
     * A table the bot can use, carried or standing.
     * <p>
     * The bot no longer picks its table back up, so "is one in the bag" stopped being the same
     * question as "can I craft here" - asking only about the inventory made it build a second table
     * while the first was still standing a few blocks away.
     */
    private static boolean hasCraftingTable(BotContext ctx) {
        return InventoryHelper.has(ctx.player, Items.CRAFTING_TABLE, 1)
                || CraftingTableMemory.get()
                        .findNearest(ctx, ctx.player.blockPosition(), NEARBY_TABLE_RADIUS) != null;
    }

    private static boolean hasStonePickaxeOrBetter(BotContext ctx) {
        return InventoryHelper.has(ctx.player, Items.STONE_PICKAXE, 1)
                || InventoryHelper.has(ctx.player, Items.IRON_PICKAXE, 1)
                || InventoryHelper.has(ctx.player, Items.DIAMOND_PICKAXE, 1)
                || InventoryHelper.has(ctx.player, Items.NETHERITE_PICKAXE, 1);
    }

    private Task createTask(BotContext ctx) {
        return switch (phase) {
            case WOOD -> woodTask(ctx);
            case STONE -> stoneTask(ctx);
            case FOOD -> foodTask(ctx);
            case IRON -> ironTask(ctx);
            case FLINT -> flintTask(ctx);
            case PORTAL -> portalTask(ctx);
            case FORTRESS_SCAN -> new ExploreTask(Set.of(Blocks.NETHER_BRICKS), 96, 16, 32, true);
            case FORTRESS_APPROACH -> fortress == null
                    ? null
                    : new GotoTask(new Goals.Adjacent(fortress, 8.0), true, false);
            case BLAZE -> {
                if (needsNetherFood(ctx)) {
                    yield new FoodTask(foodNeeded());
                }
                int rodsNeeded = SpeedrunResourcePolicy.remaining(BLAZE_RODS_NEEDED,
                        InventoryHelper.count(ctx.player, Items.BLAZE_ROD));
                yield new HuntBlazesTask(rodsNeeded,
                        new KillOptions(true, true, false, KillOptions.EndermanSafety.DIRECT,
                                KillOptions.WeaponPreference.SWORD, false), unreachableBlazes);
            }
            case PEARLS -> {
                if (needsNetherFood(ctx)) {
                    yield new FoodTask(foodNeeded());
                }
                if (InventoryHelper.has(ctx.player, Items.ENDER_PEARL, PEARLS_NEEDED)) {
                    phase = Phase.NETHER_EXIT;
                    yield null;
                }
                if (!netherChestChecked) {
                    netherChestChecked = true;
                    yield new FoodContainerTask(48, netherUnavailableContainers,
                            SpeedrunTask::isNetherSupply);
                }
                int pearlsNeeded = SpeedrunResourcePolicy.remaining(PEARLS_NEEDED,
                        InventoryHelper.count(ctx.player, Items.ENDER_PEARL));
                yield new HuntEndermenTask(pearlsNeeded,
                        new KillOptions(false, true, false, KillOptions.EndermanSafety.AUTO,
                                KillOptions.WeaponPreference.SWORD, false), unreachableEndermen);
            }
            case NETHER_EXIT -> new PortalTravelTask(Level.OVERWORLD, netherPortal, 256);
            case BLAZE_POWDER -> {
                if (InventoryHelper.has(ctx.player, Items.BLAZE_POWDER, 14)) {
                    phase = Phase.EYES;
                    yield null;
                }
                yield CraftTask.of(Items.BLAZE_POWDER, 14, false);
            }
            case EYES -> {
                if (InventoryHelper.has(ctx.player, Items.ENDER_EYE, EYES_NEEDED)) {
                    phase = Phase.ENDGAME;
                    yield null;
                }
                yield CraftTask.of(Items.ENDER_EYE, EYES_NEEDED, false);
            }
            case ENDGAME -> new CompleteGameTask();
            case START, DONE -> null;
        };
    }

    private Task woodTask(BotContext ctx) {
        // A fresh spawn can be standing in shallow water. Recover to visible dry ground before
        // starting the no-swim tree search; otherwise ExploreTask correctly refuses water goals
        // but the wood phase burns all of its attempts without ever reaching the shore.
        if (ctx.level.dimension() == Level.OVERWORLD
                && SurfaceRecoveryPolicy.shouldRecover(
                        SurfaceRecoveryTask.needsDryGroundRecovery(ctx),
                        surfaceRecoveryUnavailable)) {
            return new SurfaceRecoveryTask();
        }
        int logs = InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.LOGS));
        int woodProducts = planks(ctx) + logs * 4;
        int woodNeeded = SpeedrunResourcePolicy.woodProductsNeeded(needsShield(ctx));
        if (woodProducts < woodNeeded) {
            // Raw logs already in the inventory are a valid source of the missing planks. Do not
            // send the bot back into the forest for one more tree when converting carried logs
            // would satisfy the budget (the shield branch commonly lands here with 4 planks and
            // 2 logs).
            if (logs > 0 && planks(ctx) + logs * PLANKS_PER_LOG >= woodNeeded) {
                return CraftTask.ofTag(ItemTags.PLANKS, "planks", woodNeeded, false);
            }
            if (woodMineAfterExplore) {
                woodMineAfterExplore = false;
                return new MineTask(woodBlocks(), sightRadius(ctx, WOOD_SEARCH_CAP),
                        ctx.level.getMinY(), ctx.level.getMaxY(),
                        Math.max(1, (woodNeeded - planks(ctx) - logs * PLANKS_PER_LOG + 3) / 4),
                        false, false, true, true);
            }
            if (woodExplorePending) {
                return new ExploreTask(woodBlocks(), sightRadius(ctx, WOOD_SEARCH_CAP),
                        WOOD_SCOUT_ATTEMPTS, WOOD_SCOUT_STEP, true);
            }
            return new MineTask(woodBlocks(), sightRadius(ctx, WOOD_SEARCH_CAP),
                    ctx.level.getMinY(), ctx.level.getMaxY(),
                    Math.max(1, (woodNeeded - planks(ctx) - logs * PLANKS_PER_LOG + 3) / 4),
                    false, false, true, true);
        }
        if (!hasCraftingTable(ctx)) {
            if (planks(ctx) < 8) {
                return CraftTask.ofTag(ItemTags.PLANKS, "planks", 8, false);
            }
            return CraftTask.of(Items.CRAFTING_TABLE, 1, false);
        }
        if (!InventoryHelper.has(ctx.player, Items.STICK, 8)) {
            // Sticks come from planks, and by this point the planks may all have gone into the
            // table. Asking to craft sticks with nothing but logs in the bag fails on missing
            // materials, and because the phase retries that failure, it fails the same way every
            // time until the run gives up. Convert logs to planks first, as the branch above does.
            int missing = 8 - InventoryHelper.count(ctx.player, Items.STICK);
            int planksNeeded = ((missing + STICKS_PER_CRAFT - 1) / STICKS_PER_CRAFT) * PLANKS_PER_CRAFT;
            if (planks(ctx) < planksNeeded) {
                return CraftTask.ofTag(ItemTags.PLANKS, "planks", planksNeeded, false);
            }
            return CraftTask.of(Items.STICK, 8, false);
        }
        // Turn the rest of the wood into planks before moving on. A log is four planks and nothing
        // in the run wants it raw, so carrying logs is a wasted slot now and an extra trip back to
        // a crafting surface later, in the middle of whatever the bot is doing then.
        int spareLogs = InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.LOGS));
        if (spareLogs > 0) {
            return CraftTask.ofTag(ItemTags.PLANKS, "planks",
                    planks(ctx) + spareLogs * PLANKS_PER_LOG, false);
        }
        phase = Phase.STONE;
        phaseAttempts = 0;
        return null;
    }

    private Task stoneTask(BotContext ctx) {
        // Every stone tool costs a stick. Resuming a run can land here with tools already made and
        // the stick slot empty, and a craft with no sticks cannot succeed however many times it is
        // retried - it just burns the phase's attempts and fails the run. Go and make some instead.
        if (!InventoryHelper.has(ctx.player, Items.STICK, 1) && needsStoneCrafting(ctx)) {
            phase = Phase.WOOD;
            phaseAttempts = 0;
            return null;
        }
        // Take the whole stone budget in one trip, before anything is crafted.
        //
        // Otherwise the phase digs twice: EnsureToolTask gathers just enough for the pickaxe and
        // crafts it, then the phase re-derives, finds it still owes cobble for the sword, axe and
        // furnace - and starts a second staircase, from the surface it had just climbed back to.
        // A wooden pickaxe already mines stone, so there is no reason to split the trip; the only
        // thing the stone pickaxe buys is speed, and it buys it after the digging, not before.
        // The one trip needs a pickaxe, and that pickaxe needs no trip at all - three planks and two
        // sticks, from wood already in the bag. Making it here is what actually keeps the phase to
        // one staircase.
        //
        // The guard below asks whether stone can be mined, and on a fresh run the answer at this
        // point is no: the wood phase ends with no pickaxe of any kind. So the whole one-trip branch
        // was skipped every single run, EnsureToolTask went and dug its own three cobble for the
        // stone pickaxe, and the full budget was gathered afterwards from a second staircase. Two
        // holes, every time, and the journal shows the second starting one tick after the first
        // craft finished.
        if (!canMineStone(ctx) && canMakeWoodenPickaxe(ctx)) {
            ctx.debug.decide("make the wooden pickaxe first so the stone budget is one trip");
            return craftAtRememberedTable(CraftTask.of(Items.WOODEN_PICKAXE, 1, true));
        }
        int fullBudget = remainingStoneCost(ctx);
        int carriedCobble = InventoryHelper.count(ctx.player, Items.COBBLESTONE);

        // Upgrade the pickaxe the moment it can be afforded, not at the end of the trip.
        //
        // Gathering all seventeen cobble before crafting anything meant the whole staircase was cut
        // with a wooden pickaxe, at half the speed of the stone one it had the materials for after
        // the first three blocks. The two-staircase problem this replaced was never about waiting -
        // it was about walking back to the surface and starting a *second* hole. Crafting mid-trip
        // costs nothing: the table is carried, the bot is standing in its own staircase, and the
        // rest of the budget is then cut twice as fast.
        if (carriedCobble >= STONE_PICKAXE_COBBLE && !hasStonePickaxeOrBetter(ctx)) {
            ctx.debug.decide("enough cobble for the stone pickaxe; make it before cutting the rest");
            return craftAtRememberedTable(CraftTask.of(Items.STONE_PICKAXE, 1, true));
        }
        if (canMineStone(ctx) && fullBudget > 0 && carriedCobble < fullBudget) {
            ctx.debug.decide("take the whole stone budget in one trip: " + fullBudget + " cobble");
            return stoneGatherTask(ctx, fullBudget);
        }
        if (!InventoryHelper.has(ctx.player, Items.STONE_PICKAXE, 1)
                && !InventoryHelper.has(ctx.player, Items.IRON_PICKAXE, 1)
                && !InventoryHelper.has(ctx.player, Items.DIAMOND_PICKAXE, 1)
                && !InventoryHelper.has(ctx.player, Items.NETHERITE_PICKAXE, 1)) {
            return EnsureToolTask.stonePickaxe(true);
        }
        int stoneNeeded = remainingStoneCost(ctx);
        if (stoneNeeded > 0
                && InventoryHelper.count(ctx.player, Items.COBBLESTONE) < stoneNeeded) {
            // Somebody already quarried the village. Breaking cobblestone out of a house wall costs
            // no staircase, opens no cave and happens at ground level, so it is strictly better than
            // digging whenever there is enough of it standing in the open. Tried once: if none is
            // actually reachable the flag falls through to the staircase and stays there.
            if (!placedStoneTried) {
                int visible = countVisible(ctx, PLACED_STONE, PLACED_STONE_RADIUS);
                if (VillagePolicy.worthTakingPlacedStone(visible, stoneNeeded)) {
                    placedStoneTried = true;
                    ctx.debug.decide("take " + stoneNeeded + " stone from what is already built here");
                    return new MineTask(PLACED_STONE, PLACED_STONE_RADIUS,
                            ctx.level.getMinY(), ctx.level.getMaxY(), stoneNeeded, true, false);
                }
                placedStoneTried = true;
            }
            return new QuickStoneTask(stoneNeeded, phaseAttempts % 4);
        }
        if (!hasWeapon(ctx)) {
            return craftAtRememberedTable(CraftTask.of(Items.STONE_SWORD, 1, true));
        }
        // An axe alongside the sword. It fells the next tree far faster than anything else the bot
        // is carrying - ToolSelector picks it by destroy speed on its own - and it hits harder than
        // the sword if something closes in mid-chop.
        //
        // Only when the materials are already in the bag, though. This is a nice-to-have, and a
        // nice-to-have must never be able to fail the run the way a requirement can.
        // The cobble for this is now part of the stone budget rather than a leftover, so the test
        // is simply "can it be made", not "is there enough spare that nothing else wanted".
        if (!hasStoneAxeOrBetter(ctx)
                && InventoryHelper.has(ctx.player, Items.STICK, STONE_AXE_STICKS)
                && InventoryHelper.has(ctx.player, Items.COBBLESTONE, STONE_AXE_COBBLE)) {
            return craftAtRememberedTable(CraftTask.of(Items.STONE_AXE, 1, true));
        }
        // With an axe in hand, wood is suddenly cheap - so this is the moment to take a few more
        // logs rather than the moment to leave. Everything after this phase wants planks: a boat is
        // five, a bed three, a table four, and each stick for each later tool is another two. The
        // alternative is walking back to a forest at hunger six with a pickaxe, which is what the
        // run kept doing. Once per run, and only while the axe makes it quick.
        if (hasStoneAxeOrBetter(ctx) && !toppedUpWood && woodProducts(ctx) < WOOD_TOPUP_TARGET) {
            toppedUpWood = true;
            ctx.debug.decide("axe in hand; taking " + WOOD_TOPUP_LOGS + " more logs while it is cheap");
            return new MineTask(woodBlocks(), sightRadius(ctx, WOOD_SEARCH_CAP),
                    ctx.level.getMinY(), ctx.level.getMaxY(), WOOD_TOPUP_LOGS,
                    false, false, true, true);
        }

        // Food is deliberately not a mandatory Overworld phase. A player takes visible food that
        // happens to be on the route, but does not leave an exposed cave or iron prospect to roam
        // for a village, animal, or buried source. Nether food has its own bastion/hoglin fallback.
        phase = Phase.IRON;
        phaseAttempts = 0;
        ironExplorePending = true;
        foodDeferredToNether = true;
        foodOpportunityAnchor = null;
        return null;
    }

    /** True while the stone kit still has something left to craft, all of which need sticks. */
    private static boolean needsStoneCrafting(BotContext ctx) {
        return !hasStonePickaxeOrBetter(ctx) || !hasWeapon(ctx);
    }

    /**
     * Counts only cobble that is still needed from the current inventory state. A pickaxe already
     * crafted is not charged again, and a surplus axe never consumes the furnace reserve.
     */
    /** Whether the bot can already break stone and get cobble from it. */
    private static boolean canMineStone(BotContext ctx) {
        return ToolSelector.canHarvest(ctx.player, Blocks.STONE.defaultBlockState());
    }

    /**
     * A visible cave mouth this run has not already walked into.
     *
     * <p>Without the memory the dive is re-decided from scratch every time the phase picks a task,
     * and since the mouth is still visible from beside it the answer is always the same one. A run
     * spent 2,902 decisions on a single opening at y70 and cycled between four of them without ever
     * getting below the surface - the "keeps digging down again and again" behaviour. Each opening
     * is worth one try; after that the phase has to look somewhere else.
     */
    private BlockPos freshCaveMouth(BotContext ctx) {
        BlockPos mouth = MineTask.visibleCaveMouth(ctx, CAVE_DIVE_RADIUS);
        return mouth == null || divedCaveMouths.contains(mouth.asLong()) ? null : mouth;
    }

    /** Whether the wood already carried covers a wooden pickaxe, handle included. */
    private static boolean canMakeWoodenPickaxe(BotContext ctx) {
        int planks = InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.PLANKS))
                + InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.LOGS)) * PLANKS_PER_LOG;
        int forHandle = InventoryHelper.has(ctx.player, Items.STICK, WOODEN_PICKAXE_STICKS)
                ? 0 : PLANKS_PER_STICK_CRAFT;
        return planks >= WOODEN_PICKAXE_PLANKS + forHandle;
    }

    /** One gathering trip for cobble: village walls if they are here, a staircase otherwise. */
    private Task stoneGatherTask(BotContext ctx, int stoneNeeded) {
        if (!placedStoneTried) {
            placedStoneTried = true;
            int visible = countVisible(ctx, PLACED_STONE, PLACED_STONE_RADIUS);
            if (VillagePolicy.worthTakingPlacedStone(visible, stoneNeeded)) {
                ctx.debug.decide("take " + stoneNeeded + " stone from what is already built here");
                return new MineTask(PLACED_STONE, PLACED_STONE_RADIUS,
                        ctx.level.getMinY(), ctx.level.getMaxY(), stoneNeeded, true, false);
            }
        }
        return new QuickStoneTask(stoneNeeded, phaseAttempts % 4);
    }

    private static int remainingStoneCost(BotContext ctx) {
        return SpeedrunResourcePolicy.remainingCobblestone(
                !hasStonePickaxeOrBetter(ctx), !hasWeapon(ctx), !hasUsableFurnace(ctx),
                !hasStoneAxeOrBetter(ctx));
    }

    private static boolean hasUsableFurnace(BotContext ctx) {
        if (InventoryHelper.has(ctx.player, Items.FURNACE, 1)) {
            return true;
        }
        return BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(), Set.of(Blocks.FURNACE),
                8, ctx.level.getMinY(), ctx.level.getMaxY(), Set.of(),
                (pos, state) -> ctx.omniscientMining() || Vision.isVisible(ctx, pos)) != null;
    }

    /**
     * Whether a structure noticed on the way is worth breaking off the current march for.
     *
     * <p>Restricted to the phase that actually wants what these structures hold, and to marches -
     * a mine or a craft is already at the thing it was sent to, so pulling it away mid-job would
     * throw away the progress that {@link OpportunityTask}'s resume is meant to preserve.
     */
    private boolean shouldDivertToOpportunity(BotContext ctx) {
        return phase == Phase.IRON
                && opportunity != null
                && opportunityKind != null
                // Nothing to fight with means the wreck's drowned and the village's zombies get a
                // free run at the bot. The sighting keeps; the trip can wait for a sword.
                && hasWeapon(ctx)
                && !ROUTE_DIRECT.equals(routeStyle)
                && ctx.level.dimension() == Level.OVERWORLD
                && (current instanceof GotoTask || current instanceof ExploreTask);
    }

    private static int carriedFood(BotContext ctx) {
        return InventoryHelper.count(ctx.player, stack -> stack.has(DataComponents.FOOD));
    }

    private static boolean hasStoneAxeOrBetter(BotContext ctx) {
        return InventoryHelper.has(ctx.player, Items.STONE_AXE, 1)
                || InventoryHelper.has(ctx.player, Items.IRON_AXE, 1)
                || InventoryHelper.has(ctx.player, Items.DIAMOND_AXE, 1)
                || InventoryHelper.has(ctx.player, Items.NETHERITE_AXE, 1);
    }

    /**
     * A dry block to stand on at the water's edge, or null if no shore is in sight.
     *
     * <p>Gravel is a beach and river-bed block. Searching for it inland is searching where it does
     * not generate, and the bot's answer to a failed inland search was to start digging - the
     * "still digs for flint" complaint. The waterline is where the deposits actually are and where
     * a person would walk, so the search starts by getting there. Only visible, standable, dry
     * blocks count: the point is to walk the bank, not to end up swimming along it.
     */
    private static BlockPos nearestShoreStand(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = Math.min(sightingRadius(ctx), SHORE_SEARCH_RADIUS);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -SHORE_SEARCH_HEIGHT; dy <= SHORE_SEARCH_HEIGHT; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!ctx.level.isLoaded(candidate)) {
                        continue;
                    }
                    double distance = feet.distSqr(candidate);
                    if (distance >= bestDistance
                            || MovementHelper.isWater(ctx.level, candidate)
                            || !MovementHelper.nearWater(ctx.level, candidate)
                            || !MovementHelper.canStandAt(ctx.level, candidate)
                            || !Vision.isVisible(ctx, candidate)) {
                        continue;
                    }
                    bestDistance = distance;
                    best = candidate.immutable();
                }
            }
        }
        return best;
    }

    /**
     * Whether a gravel bank in sight is worth a short stop.
     *
     * <p>Skipped once the run can already light a portal - at that point flint is not wanted at
     * all and the stop would be pure cost.
     */
    private boolean shouldGrabGravel(BotContext ctx) {
        if (gravelCheckCooldown > 0) {
            gravelCheckCooldown--;
            return false;
        }
        if (ctx.level.dimension() != Level.OVERWORLD || !isOverworldPreparation()
                || phase == Phase.FLINT) {
            return false;
        }
        if (hasLighter(ctx) || InventoryHelper.has(ctx.player, Items.FLINT, 1)
                || InventoryHelper.count(ctx.player, Items.GRAVEL) >= GRAVEL_RESERVE) {
            return false;
        }
        // Pay the cooldown for looking, not for finding.
        //
        // Set only when the diversion fired, it was never set on the common path - no gravel in
        // sight - so the scan below ran on every tick of every preparation phase. With the search
        // box also spanning the whole world height that was on the order of four hundred thousand
        // block lookups a tick, each visible candidate costing a ray cast. The game stopped
        // rendering; the bot did not so much stop moving as stop being given ticks to move in.
        gravelCheckCooldown = GRAVEL_RECHECK_TICKS;
        BlockPos feet = ctx.player.blockPosition();
        return BlockScanner.findNearest(ctx.level, feet, Set.of(Blocks.GRAVEL),
                GRAVEL_DIVERSION_RADIUS,
                feet.getY() - GRAVEL_DIVERSION_RADIUS, feet.getY() + GRAVEL_DIVERSION_RADIUS,
                Set.of(),
                (pos, state) -> ctx.omniscientMining() || Vision.isVisible(ctx, pos)) != null;
    }

    private static boolean hasShovel(BotContext ctx) {
        return InventoryHelper.count(ctx.player,
                stack -> ToolCatalog.kindOf(stack.getItem()) == ToolCatalog.Kind.SHOVEL) > 0;
    }

    /**
     * Whether the odd diamond turned up by the iron dig should become a shovel.
     *
     * <p>A single diamond is otherwise dead weight for this route: the portal is cast from a lava
     * pool rather than mined obsidian, and a diamond pickaxe - the only other thing worth the
     * material - needs three. Meanwhile the flint phase is spent breaking gravel, which a diamond
     * shovel clears in a fraction of the time it takes by hand. So one or two spare diamonds are
     * better in a shovel than in a pocket; at three the pickaxe wins and the diamonds are left
     * alone.
     */
    private boolean shouldCraftDiamondShovel(BotContext ctx) {
        int diamonds = InventoryHelper.count(ctx.player, Items.DIAMOND);
        // Sticks come from planks, and CraftTask gathers its own sub-ingredients, so requiring the
        // finished sticks turned the shovel down over a single stick while carrying thirteen
        // planks. Ask for the handle or the wood to make it.
        boolean handle = InventoryHelper.has(ctx.player, Items.STICK, SHOVEL_STICKS)
                || InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.PLANKS)) >= PLANKS_PER_STICK_CRAFT;
        return !diamondShovelTried
                && diamonds >= 1 && diamonds < DIAMONDS_FOR_PICKAXE
                && !hasShovel(ctx)
                && handle;
    }

    private Task foodTask(BotContext ctx) {
        if (hasFood(ctx)) {
            // Gathering can finish with an empty hunger bar: raw meat counts as a carried food
            // reserve, but it does not keep the next phase alive until the player actually eats.
            // Consume enough before moving on, so the next phase starts with the supplies this
            // phase promised to provide.
            if (ctx.player.getFoodData().getFoodLevel() < 14) {
                return new EatTask(stack -> stack.has(DataComponents.FOOD), 14);
            }
            phase = Phase.IRON;
            phaseAttempts = 0;
            ironExplorePending = true;
            return null;
        }
        if (foodDeferredToNether && ctx.level.dimension() == Level.OVERWORLD) {
            phase = Phase.IRON;
            phaseAttempts = 0;
            ironExplorePending = true;
            return null;
        }
        return new FoodTask(foodNeeded());
    }

    private Task ironTask(BotContext ctx) {
        noteIronProgress(ctx);

        if (ctx.level.dimension() == Level.OVERWORLD && !hasFood(ctx)
                && shouldCheckFoodOpportunity(ctx)) {
            FoodTask food = new FoodTask(foodNeeded(), true);
            boolean sourceVisible = food.hasImmediateOpportunity(ctx);
            boolean alreadyCheckedHere = false;
            foodOpportunityAnchor = ctx.player.blockPosition().immutable();
            if (FoodSearchPolicy.shouldTakeLocalOpportunity(sourceVisible, alreadyCheckedHere)) {
                return food;
            }
        }

        int needed = ironNeeded(ctx);
        if (needed <= 0) {
            phase = Phase.FLINT;
            phaseAttempts = 0;
            return null;
        }

        if (ironRelocationPending) {
            ironRelocationPending = false;
            ironRelocationActive = true;
            opportunity = null;
            opportunityKind = null;
            ironOreHint = null;
            return ironRelocationTask(ctx);
        }

        // Work a find before digging for the same thing. Somebody already mined the iron in a
        // village smithy, a shipwreck hold and a mineshaft wall; a chest is minutes cheaper than a
        // shaft, and a ruined portal's chest can carry the flint and steel this phase is ultimately
        // paying for.
        if (opportunity != null && !ROUTE_DIRECT.equals(routeStyle)) {
            BlockPos site = opportunity;
            SpeedrunOpportunity kind = opportunityKind;
            opportunity = null;
            opportunityKind = null;
            workedOpportunities.add(site.asLong());
            return new OpportunityTask(kind, site, overworldContainers);
        }

        // A bounded iron prospect can outlast the stone pickaxe that opened it. Repair the
        // mining dependency before re-entering that prospect; otherwise MineTask falls back to
        // the wooden pickaxe and spends the next search budget breaking stone without a chance to
        // harvest the iron it was sent to collect.
        if (ironOreHint != null
                && SpeedrunIronPolicy.needsMiningTool(
                        ToolSelector.canHarvest(ctx.player, Blocks.IRON_ORE.defaultBlockState()))) {
            return EnsureToolTask.stonePickaxe(true);
        }

        if (ironOreHint != null) {
            BlockPos hint = ironOreHint;
            ironOreHint = null;
            activeIronProspectHint = hint;
            return new MineTask(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), 64,
                    ctx.level.getMinY(), ctx.level.getMaxY(), needed -
                    InventoryHelper.count(ctx.player, Items.IRON_INGOT),
                    true, true, false, true,
                    SpeedrunIronPolicy.PROSPECT_STAIR_STEPS,
                    SpeedrunIronPolicy.PROSPECT_MAX_ATTEMPTS).nearHint(hint);
        }

        if (ironExplorePending) {
            ironExplorePending = false;
            if (ROUTE_DIRECT.equals(routeStyle)) {
                // The direct route does not walk the surface looking for a shortcut. Drop anything
                // the last scout spotted and fall through to the ore search, which digs its own way
                // down.
                opportunity = null;
                opportunityKind = null;
            } else {
                // Before another surface walk, ask whether there is a better way to look. The
                // surface scout is the fallback, not the plan: it is what the phase does when
                // there is neither an opening to go down nor a sea to cross.
                IronStrategyPolicy.Strategy strategy = IronStrategyPolicy.choose(
                        ironScoutFailures,
                        BoatTask.crossingNearby(ctx, FLINT_CROSSING_RADIUS),
                        IronStrategyPolicy.worthStayingForVillage(
                                villageNearby(ctx), opportunityKind == SpeedrunOpportunity.BLACKSMITH),
                        freshCaveMouth(ctx) != null,
                        ironSailed,
                        opportunity != null,
                        hasWeapon(ctx));
                if (strategy == IronStrategyPolicy.Strategy.STRUCTURE) {
                    BlockPos site = opportunity;
                    SpeedrunOpportunity kind = opportunityKind;
                    opportunity = null;
                    opportunityKind = null;
                    workedOpportunities.add(site.asLong());
                    ironScoutActive = false;
                    ctx.debug.decide("a " + kind.label() + " is known at " + site.toShortString()
                            + "; its iron is already mined and smelted");
                    return new OpportunityTask(kind, site, overworldContainers);
                }
                if (strategy == IronStrategyPolicy.Strategy.DIVE) {
                    BlockPos mouth = freshCaveMouth(ctx);
                    divedCaveMouths.add(mouth.asLong());
                    ironScoutActive = false;
                    ironDiving = true;
                    ctx.debug.decide("open cave at " + mouth.toShortString()
                            + "; going down it instead of walking the surface");
                    return new GotoTask(new Goals.Near(mouth, 2), true, false);
                }
                if (strategy == IronStrategyPolicy.Strategy.SAIL) {
                    ironSailed = true;
                    ironScoutActive = false;
                    ctx.debug.decide("surface search keeps failing on a coast; taking the sea");
                    return new BoatTask(null, true);
                }
                ironScoutActive = true;
                // The scout looks for the ore and for the structures that hold it in the same pass.
                // It walks and scans at once, so adding markers costs one wider block index rather
                // than a second trip over the same ground.
                Set<Block> scoutTargets = new java.util.HashSet<>(SpeedrunOpportunity.allMarkers());
                scoutTargets.add(Blocks.IRON_ORE);
                scoutTargets.add(Blocks.DEEPSLATE_IRON_ORE);
                int scoutStep = Math.min(64, 16 + ironSearchRounds * 8);
                ctx.debug.decide("scout a new iron area with " + scoutStep + " block steps");
                return new ExploreTask(Set.copyOf(scoutTargets),
                        sightRadius(ctx, IRON_SEARCH_CAP), 4, scoutStep, true,
                        HeadScanner.Style.GLANCE, true);
            }
        }

        if (SpeedrunIronPolicy.needsMiningTool(
                ToolSelector.canHarvest(ctx.player, Blocks.IRON_ORE.defaultBlockState()))) {
            return EnsureToolTask.stonePickaxe(true);
        }

        int ingots = InventoryHelper.count(ctx.player, Items.IRON_INGOT);
        if (ingots < needed) {
            int raw = InventoryHelper.count(ctx.player, stack -> stack.is(Items.RAW_IRON)
                    || stack.is(Items.IRON_ORE) || stack.is(Items.DEEPSLATE_IRON_ORE));
            if (raw < needed - ingots) {
                // Digging is the last resort and now has to prove it. Until the run has actually
                // gone looking for something built, send it looking instead: a village smithy or a
                // wreck hold is iron nobody has to mine, and this branch used to fire on any tick
                // with no structure in mind and no ore in view - which put the bot down a shaft
                // before it had ever searched for either.
                // The counter has to be one the search itself moves, or this cannot be satisfied.
                // ironSearchRounds only advances when a MineTask finishes, so gating the MineTask
                // on it would be a deadlock; ironScoutFailures advances when a surface search comes
                // back empty, which is exactly the evidence being asked for. The direct route is
                // exempt because digging its own way down is what that route is.
                // Bounded by its own count of deferrals, not only by the search counter. Sending
                // the phase back to explore is only useful if something changes; when it does not,
                // this is a loop with no exit, and it was one - the scout kept succeeding, so the
                // failure counter never moved and the gate never opened. Deferring is capped so
                // the worst case is a delayed shaft rather than a frozen run.
                if (!ROUTE_DIRECT.equals(routeStyle)
                        && ironDigDeferrals < MAX_DIG_DEFERRALS
                        && !IronStrategyPolicy.mayDigForIron(ironScoutFailures,
                                opportunity != null, freshCaveMouth(ctx) != null)) {
                    ironDigDeferrals++;
                    ironExplorePending = true;
                    ctx.debug.decide("not digging yet after " + ironScoutFailures
                            + " empty searches; look for a village or a wreck first ("
                            + ironDigDeferrals + "/" + MAX_DIG_DEFERRALS + ")");
                    status = describe() + " - looking for a village or wreck before digging";
                    return null;
                }
                // Nothing built within reach after a real search. A shaft is what keeps a barren
                // spawn - a small island, a bare ice plain - from being a dead run.
                ctx.debug.decide("no structure found in " + ironScoutFailures
                        + " searches; sinking a shaft as the last resort");
                return new MineTask(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), 64,
                        ctx.level.getMinY(), ctx.level.getMaxY(), needed - ingots - raw,
                        true, true, false, true,
                        SpeedrunIronPolicy.PROSPECT_STAIR_STEPS,
                        SpeedrunIronPolicy.PROSPECT_MAX_ATTEMPTS);
            }
            if (!InventoryHelper.has(ctx.player, Items.FURNACE, 1)
                    && BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                    Set.of(Blocks.FURNACE), 8, ctx.level.getMinY(), ctx.level.getMaxY()) == null) {
                return craftAtRememberedTable(CraftTask.of(Items.FURNACE, 1, true));
            }
            return new SmeltTask(Set.of(Items.RAW_IRON, Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE),
                    Items.IRON_INGOT, needed - ingots);
        }

        // The furnace is lit and standing here anyway, so raw meat is cooked for the cost of
        // walking back to it - which is nothing, because the run is already there. Cooked food is
        // worth roughly twice the raw version and carries no rotten-flesh risk, and the alternative
        // is carrying raw chicken into the Nether. Only when there is actually meat to cook.
        if (cookAttempts < MAX_COOK_ATTEMPTS && hasUsableFurnace(ctx)) {
            for (Map.Entry<Item, Item> recipe : RAW_TO_COOKED.entrySet()) {
                int raw = InventoryHelper.count(ctx.player, recipe.getKey());
                if (raw <= 0) {
                    continue;
                }
                cookAttempts++;
                int cooked = InventoryHelper.count(ctx.player, recipe.getValue());
                ctx.debug.decide("furnace is already here; cook the " + raw + " raw "
                        + InventoryHelper.itemName(recipe.getKey()));
                return new SmeltTask(Set.of(recipe.getKey()), recipe.getValue(), cooked + raw);
            }
        }

        phase = Phase.FLINT;
        phaseAttempts = 0;
        return null;
    }

    /**
     * Resets the barren-area budget when the run actually obtains more iron. Without this, one
     * raw ore found late in a search still leaves the phase one failed scout away from aborting,
     * even though the route has evidence that its current strategy is working.
     */
    private void noteIronProgress(BotContext ctx) {
        int total = InventoryHelper.count(ctx.player, Items.IRON_INGOT)
                + InventoryHelper.count(ctx.player, stack -> stack.is(Items.RAW_IRON)
                || stack.is(Items.IRON_ORE) || stack.is(Items.DEEPSLATE_IRON_ORE));
        if (total <= ironProgressCount) {
            return;
        }
        ironProgressCount = total;
        ironSearchRounds = 0;
        phaseAttempts = 0;
        ctx.debug.decide("iron progress found; reset the area search budget");
    }

    /**
     * Walks to a fresh loaded area before the next visible scout. The target is deliberately
     * cardinal and bounded: it changes the search centre without granting knowledge of anything
     * beyond the chunks the client loads while travelling.
     */
    private Task ironRelocationTask(BotContext ctx) {
        Direction direction = Direction.from2DDataValue((ironSearchRounds + 1) % 4);
        int distance = Math.min(96, 32 + ironSearchRounds * 8);
        BlockPos start = ctx.player.blockPosition();
        BlockPos target = start.offset(direction.getStepX() * distance, 0,
                direction.getStepZ() * distance);
        ctx.debug.decide("leave the exhausted iron pocket: walk " + direction.getName()
                + " " + distance + " blocks");
        return new GotoTask(new Goals.NearXZ(target.getX(), target.getZ(), 4), true, true);
    }

    /** Check once per nearby area; an empty view is not permission to start a food expedition. */
    private boolean shouldCheckFoodOpportunity(BotContext ctx) {
        BlockPos here = ctx.player.blockPosition();
        return foodOpportunityAnchor == null
                || foodOpportunityAnchor.distSqr(here) > 24.0 * 24.0;
    }

    /**
     * Re-check local food while any Overworld preparation task is travelling or mining.
     *
     * <p>This is deliberately a position-based gate rather than a timer: a player notices a
     * sheep or a field after walking into a new clearing, not because a food radar woke up. The
     * empty check is cheap and records the current area; only {@link FoodTask#hasImmediateOpportunity}
     * can interrupt the active route.</p>
     */
    private boolean shouldCheckPreparationFood(BotContext ctx) {
        // Below a reserve, not merely "carrying something".
        //
        // Gating on hasFood meant one piece of raw chicken was enough to walk straight past a herd
        // or a wheat farm, and the run would then reach the iron phase with a single item and go
        // hungry in the middle of it - buying the same food later at the price of a hunt, from
        // wherever it happened to be standing. Food on the way is nearly free; food when needed is
        // a detour.
        if (ctx.level.dimension() != Level.OVERWORLD || carriedFood(ctx) >= FOOD_RESERVE) {
            return false;
        }
        if (phase != Phase.WOOD && phase != Phase.STONE && phase != Phase.IRON
                && phase != Phase.FLINT && phase != Phase.PORTAL) {
            return false;
        }
        return shouldCheckFoodOpportunity(ctx);
    }

    /**
     * Whether hunger has become the most expensive thing about the run.
     * <p>
     * Vanilla refuses to sprint at six or less, and sprint is thirty percent of walking speed and
     * all of swimming speed. A run under the cliff with nothing to eat gets slower at everything,
     * including at fetching the food that would fix it.
     */
    private boolean shouldSearchForFoodUrgently(BotContext ctx) {
        if (ctx.level.dimension() != Level.OVERWORLD || hungrySearchCooldown > 0) {
            return false;
        }
        if (!isOverworldPreparation()) {
            return false;
        }
        return ctx.player.getFoodData().getFoodLevel() <= SPRINT_FOOD_FLOOR && !hasFood(ctx);
    }

    /** Eating an item already carried is survival maintenance, not a food search. */
    private boolean shouldEatCarriedFood(BotContext ctx) {
        if (ctx.level.dimension() != Level.OVERWORLD
                || ctx.player.getFoodData().getFoodLevel() > 12
                || phase == Phase.FOOD || phase == Phase.ENDGAME || phase == Phase.DONE) {
            return false;
        }
        if (foodEatUnavailableAt != null
                && foodEatUnavailableAt.distSqr(ctx.player.blockPosition()) <= 64.0) {
            return false;
        }
        return InventoryHelper.count(ctx.player, stack -> stack.has(DataComponents.FOOD)) > 0;
    }

    private Task flintTask(BotContext ctx) {
        // Flint is only ever wanted to make a flint and steel. A looted one - or a fire charge,
        // which lights a portal just as well - means this whole gravel search is dead work.
        if (hasLighter(ctx) || obsidianRouteReady(ctx)) {
            phase = Phase.PORTAL;
            phaseAttempts = 0;
            return null;
        }
        if (!InventoryHelper.has(ctx.player, Items.FLINT, 1)) {
            // Gravel picked up earlier is rolls in hand. Take them before walking anywhere: the
            // shoreline, the crossing and the dig are all just ways of finding gravel, and this
            // already has some.
            if (gravelKnapAttempts < MAX_GRAVEL_KNAPS
                    && InventoryHelper.has(ctx.player, Items.GRAVEL, 1)) {
                // Somewhere to stand comes first. The first attempt at this failed three ticks into
                // the phase - on a wreck deck with nothing placeable in reach - and because one
                // attempt was all it got, the run went back to hunting for gravel while carrying
                // four blocks of it. Reconnect to ground the way the gravel search itself does.
                if (ctx.level.dimension() == Level.OVERWORLD
                        && SurfaceRecoveryPolicy.shouldRecover(
                                SurfaceRecoveryTask.needsDryGroundRecovery(ctx),
                                surfaceRecoveryUnavailable)) {
                    return new SurfaceRecoveryTask();
                }
                gravelKnapAttempts++;
                ctx.debug.decide("gravel already carried; knap it here before searching for more");
                return new FlintFromGravelTask();
            }
            // The whole phase is gravel-breaking, so buy the speed first if the iron dig happened
            // to turn up a diamond that has no other use on this route.
            if (shouldCraftDiamondShovel(ctx)) {
                diamondShovelTried = true;
                ctx.debug.decide("spare diamond; a shovel pays for itself over a gravel search");
                return craftAtRememberedTable(CraftTask.of(Items.DIAMOND_SHOVEL, 1, true));
            }
            if (flintExplorePending) {
                if (ctx.level.dimension() == Level.OVERWORLD
                        && SurfaceRecoveryPolicy.shouldRecover(
                                SurfaceRecoveryTask.needsDryGroundRecovery(ctx),
                                surfaceRecoveryUnavailable)) {
                    // Gravel is normally exposed at the surface. Reconnect to a visible dry
                    // shoreline/ground before asking ExploreTask to search for it, and let the
                    // next tick derive the actual search from the recovered position.
                    return new SurfaceRecoveryTask();
                }
                flintExplorePending = false;
                return new ExploreTask(Set.of(Blocks.GRAVEL), 64, 8, 16, true);
            }
            // Gravel is a shoreline block, so go and stand on the shoreline before searching. This
            // comes ahead of both the crossing and the dig: the near bank is cheaper than the far
            // one, and it is the search a person would run first.
            if (!flintShoreTried && ctx.level.dimension() == Level.OVERWORLD) {
                BlockPos shore = nearestShoreStand(ctx);
                if (shore != null && shore.distManhattan(ctx.player.blockPosition()) > ON_SHORE_DISTANCE) {
                    flintShoreTried = true;
                    ctx.debug.decide("gravel is a beach block; searching the waterline before digging");
                    status = describe() + " - walking to the waterline to look for gravel";
                    return new GotoTask(new Goals.Near(shore, 2), true, false);
                }
                flintShoreTried = true;
            }
            // On a shoreline the land search has already failed and the water is the only unsearched
            // direction - and gravel is a beach and river-bed block, so the far bank is exactly
            // where it is. Block visibility treats water as opaque on purpose (mining blind through
            // it is a drowning), so the gravel under the shallows is invisible from here however
            // obvious it looks to a person. Crossing is the honest way to reach it, and it is what a
            // player does instead of sinking a shaft inland. Once per phase; digging remains the
            // fallback when there is no water, or the crossing fails.
            if (!flintBoatTried && ctx.level.dimension() == Level.OVERWORLD
                    && BoatTask.crossingNearby(ctx, FLINT_CROSSING_RADIUS)) {
                flintBoatTried = true;
                ctx.debug.decide("no gravel on this shore; crossing the water instead of digging");
                return new BoatTask(null, true);
            }
            // A failed surface scout is only a location verdict. The next attempt must be able to
            // open a short, physical face through grass/dirt to reach gravel that is visible as a
            // surface clue but not directly actionable from the current block. Without this
            // bounded prospect branch, one occluded gravel block consumes the whole flint phase.
            return new MineTask(Set.of(Blocks.GRAVEL), 32, ctx.level.getMinY(), ctx.level.getMaxY(),
                    8, false, true, false, true, 4, 4);
        }
        phase = Phase.PORTAL;
        phaseAttempts = 0;
        return null;
    }

    private Task portalTask(BotContext ctx) {
        // Enough obsidian to raise a frame outright. No bucket, no gravel, no lava pool - place the
        // frame and light it. This is the route a looted or mined obsidian supply unlocks.
        if (obsidianRouteReady(ctx)) {
            return new BuildPortalTask(BuildPortalTask.FrameMode.FULL_OBSIDIAN, Set.of(), true);
        }
        if (!InventoryHelper.has(ctx.player, Items.BUCKET, 1)) {
            return craftAtRememberedTable(CraftTask.of(Items.BUCKET, 1, true));
        }
        if (!hasLighter(ctx)) {
            return CraftTask.of(Items.FLINT_AND_STEEL, 1, false);
        }
        // A shield is useful protection, but it is not part of the portal kit. Do not turn a
        // completed bucket/flint-and-steel setup into a deterministic retry when the earlier wood
        // phase spent its last planks on tools or bread.
        if (!InventoryHelper.has(ctx.player, Items.SHIELD, 1) && canCraftShield(ctx)) {
            return craftAtRememberedTable(CraftTask.of(Items.SHIELD, 1, true));
        }
        return new LavaCastPortalTask(knownLavaPool);
    }

    private static boolean canCraftShield(BotContext ctx) {
        return InventoryHelper.count(ctx.player, Items.IRON_INGOT) >= 1
                && InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.PLANKS)) >= 6;
    }

    private void advance(BotContext ctx, Task finished) {
        switch (phase) {
            case WOOD -> {
                if (finished instanceof MineTask mine) {
                    // MineTask completed (or partially completed) a local visible scan. A new
                    // MineTask at the same position would reset its head scanner and repeat the
                    // same sweep, so every unfinished wood request must physically scout a new
                    // area. This matters after a ruined portal supplies only part of the logs.
                    int needed = SpeedrunResourcePolicy.woodProductsNeeded(needsShield(ctx));
                    if (!mine.madeProgress() || woodProducts(ctx) < needed) {
                        woodExplorePending = true;
                        woodMineAfterExplore = false;
                    }
                } else if (finished instanceof ExploreTask explore) {
                    if (explore.getFound() != null) {
                        woodExplorePending = false;
                        woodMineAfterExplore = true;
                    } else {
                        // Keep the scout mode selected for the next bounded retry if no tree was
                        // visible in the directions it tried.
                        woodExplorePending = true;
                    }
                }
                phaseAttempts++;
            }
            case FORTRESS_SCAN -> {
                if (finished instanceof ExploreTask explore) {
                    fortress = explore.getFound();
                }
                phase = fortress == null ? Phase.FORTRESS_SCAN : Phase.FORTRESS_APPROACH;
                phaseAttempts = 0;
            }
            case FORTRESS_APPROACH -> {
                phase = Phase.BLAZE;
                phaseAttempts = 0;
            }
            case BLAZE -> {
                phase = Phase.PEARLS;
                phaseAttempts = 0;
            }
            case NETHER_EXIT -> {
                phase = Phase.BLAZE_POWDER;
                phaseAttempts = 0;
            }
            case PEARLS -> {
                phase = Phase.NETHER_EXIT;
                phaseAttempts = 0;
            }
            case IRON -> {
                if (ironScoutActive && finished instanceof ExploreTask explore) {
                    ironScoutActive = false;
                    BlockPos seen = explore.getFound();
                    // The scout hunts ore and structures in one pass, so its find is either. Only
                    // the structure half was ever read: noteOpportunity classifies the block and
                    // gives up on anything that is not a village or wreck marker, which is every
                    // piece of iron ore. So a scout that succeeded at exactly its job threw the
                    // answer away, and the phase asked it to go and look again - "spotted Iron Ore
                    // at -37, 110, 181" alternating with "looking for a village or wreck before
                    // digging" for 863 ticks without moving.
                    if (isIronOre(ctx, seen) && !exhaustedIronPockets.contains(seen.asLong())) {
                        ironOreHint = seen.immutable();
                        ctx.debug.decide("the scout found iron at " + seen.toShortString()
                                + "; go and mine it");
                    } else {
                        noteOpportunity(ctx, seen);
                    }
                } else if (finished instanceof OpportunityTask && !ironSuppliesReady(ctx)) {
                    // A structure that did not finish the job is a reason to keep walking and
                    // looking for the next one, not a reason to sink a shaft beside it. The scout
                    // failing to find anything is what sends this phase underground.
                    ironExplorePending = true;
                }
                phaseAttempts++;
            }
            case ENDGAME -> {
                phase = Phase.DONE;
                phaseAttempts = 0;
            }
            default -> phaseAttempts++;
        }

        if (phase == Phase.PORTAL && ctx.level.dimension() == Level.NETHER) {
            netherPortal = findPortal(ctx, ctx.player.blockPosition(), 64);
            phase = Phase.FORTRESS_SCAN;
            phaseAttempts = 0;
        }
        if (phase == Phase.NETHER_EXIT && ctx.level.dimension() != Level.NETHER) {
            phase = Phase.BLAZE_POWDER;
        }
        if (phase == Phase.BLAZE_POWDER && InventoryHelper.has(ctx.player, Items.BLAZE_POWDER, 14)) {
            phase = Phase.EYES;
        }
        if (phase == Phase.EYES && InventoryHelper.has(ctx.player, Items.ENDER_EYE, EYES_NEEDED)) {
            phase = Phase.ENDGAME;
        }
    }

    /**
     * Records a scout sighting that is a structure rather than the ore itself.
     * <p>
     * The scout hands back one block, so what it saw has to be read off the world: a rail means a
     * mineshaft, netherrack in the Overworld means a ruined portal, and so on. Sites already worked
     * are ignored, or the same chest would be walked back to on the next round.
     */
    /**
     * Puts the hotbar back into its fixed order every so often.
     *
     * <p>Cheap and idempotent - it only swaps when something is in the wrong slot - but not free,
     * so it runs on a cooldown rather than every tick. Skipped while a container screen is open:
     * crafting and chest handling drive the same inventory menu, and reshuffling slots underneath
     * them is how a half-finished craft loses its ingredients.
     */
    private void tidyHotbar(BotContext ctx) {
        if (--hotbarTidyCooldown > 0) {
            return;
        }
        hotbarTidyCooldown = HOTBAR_TIDY_TICKS;
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            return;
        }
        HotbarLayout.arrange(ctx);
    }

    private static boolean isIronOre(BotContext ctx, BlockPos pos) {
        if (pos == null || !ctx.level.hasChunkAt(pos)) {
            return false;
        }
        Block block = ctx.level.getBlockState(pos).getBlock();
        return block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE;
    }

    private void noteOpportunity(BotContext ctx, BlockPos seen) {
        if (seen == null || workedOpportunities.contains(seen.asLong())) {
            return;
        }
        SpeedrunOpportunity kind = SpeedrunOpportunity.classify(
                ctx.level.getBlockState(seen).getBlock());
        if (kind == null) {
            return;
        }
        opportunity = seen.immutable();
        opportunityKind = kind;
        ctx.debug.decide("spotted a " + kind.label() + " at " + seen.toShortString()
                + "; working it before digging");
    }

    private boolean retryable(BotContext ctx, Task finished) {
        if (phase == Phase.WOOD || phase == Phase.STONE || phase == Phase.FOOD
                || phase == Phase.IRON || phase == Phase.FLINT || phase == Phase.FORTRESS_SCAN
                || phase == Phase.BLAZE || phase == Phase.PEARLS) {
            if (phase == Phase.IRON && finished instanceof ExploreTask) {
                // A failed scout is a location verdict, not a failed gathering attempt. The
                // following MineTask is the bounded physical fallback that can expose ore behind
                // a stone/grass lip. The physical failure path above owns the area budget, so a
                // generic retry here must not hide a recoverable new search. In particular, do not
                // spend the small generic phase retry budget: an underground route failure is
                // expected while scouting a pocket and must still hand control to MineTask.
                ironExplorePending = false;
                ironScoutActive = false;
                return true;
            }
            if (phase == Phase.FLINT && finished instanceof ExploreTask) {
                // A failed gravel scout is not proof that flint is unavailable. Let the bounded
                // physical MineTask expose a grass/dirt-covered gravel pocket before spending a
                // gather retry on another scouting area.
                flintExplorePending = false;
                return true;
            }
            if (phase == Phase.WOOD && finished instanceof MineTask) {
                // A failed local wood attempt is still a location verdict. Do not rebuild the same
                // scanner in place, even when it broke one or two logs before the approach failed.
                woodExplorePending = true;
                woodMineAfterExplore = false;
            }
            phaseAttempts++;
            return phaseAttempts < MAX_GATHER_ATTEMPTS;
        }
        return false;
    }

    private String describe() {
        return switch (phase) {
            case START -> "choosing a route";
            case WOOD -> "getting wood and a crafting table";
            case STONE -> "making the stone kit";
            case FOOD -> "gathering food";
            case IRON -> "getting bucket iron";
            case FLINT -> "looking for flint";
            case PORTAL -> "casting a Nether portal";
            case FORTRESS_SCAN -> "looking for a visible fortress";
            case FORTRESS_APPROACH -> "approaching the fortress";
            case BLAZE -> "collecting blaze rods";
            case NETHER_EXIT -> "returning through the Nether portal";
            case PEARLS -> "collecting ender pearls";
            case BLAZE_POWDER -> "crafting blaze powder";
            case EYES -> "crafting eyes of ender";
            case ENDGAME -> "finishing the End sequence";
            case DONE -> "done";
        };
    }

    /** Publishes mission-level state that remains readable while child tasks replace their detail. */
    private void publishRouteTelemetry(BotContext ctx) {
        routeTicks++;
        if (hungrySearchCooldown > 0) {
            hungrySearchCooldown--;
        }
        ctx.debug.diversions = "food=" + foodDiversions + " (" + foodDiversionsFailed + " failed)"
                + ";night=" + nightDiversions + " (" + nightDiversionFailures + " failed)";
        BlockPos position = ctx.player.blockPosition();
        String signature = phase + "|" + ctx.level.dimension()
                + "|" + InventoryHelper.count(ctx.player, Items.COBBLESTONE)
                + "|" + InventoryHelper.count(ctx.player, Items.IRON_INGOT)
                + "|" + InventoryHelper.count(ctx.player, Items.BLAZE_ROD)
                + "|" + InventoryHelper.count(ctx.player, Items.ENDER_PEARL)
                + "|" + InventoryHelper.count(ctx.player, Items.ENDER_EYE)
                + "|" + ctx.player.getFoodData().getFoodLevel();
        boolean moved = lastRoutePosition != null
                && lastRoutePosition.distSqr(position) > 16;
        String childActivity = current == null
                ? "none"
                : current.name() + ":" + current.status();
        String actionTarget = String.valueOf(ctx.debug.breakTarget) + "/"
                + String.valueOf(ctx.debug.placementTarget) + "/"
                + String.valueOf(ctx.debug.movementTarget);
        String activitySignature = signature + "|child=" + childActivity
                + "|action=" + actionTarget;
        if (activitySignature.equals(lastRouteSignature) && !moved && phase == lastRoutePhase) {
            routeNoProgressTicks++;
        } else {
            routeNoProgressTicks = 0;
        }
        if (moved) {
            lastRoutePosition = position;
        } else if (lastRoutePosition == null) {
            lastRoutePosition = position;
        }
        lastRouteSignature = activitySignature;
        if (lastRoutePhase != null && phase != lastRoutePhase) {
            // A new phase starts from a new position and a new requirement, so an earlier verdict
            // that recovery could not help here says nothing about what it can do now.
            surfaceRecoveryUnavailable = false;
        }
        lastRoutePhase = phase;

        ctx.debug.missionProgress = "phase " + phase
                + " | cobble " + InventoryHelper.count(ctx.player, Items.COBBLESTONE)
                + " (remaining " + remainingStoneCost(ctx) + ")"
                + " | wood " + woodProducts(ctx)
                + " | iron " + InventoryHelper.count(ctx.player, Items.IRON_INGOT) + "/" + ironNeeded(ctx)
                + " | obsidian " + InventoryHelper.count(ctx.player, Items.OBSIDIAN)
                + "/" + SpeedrunIronPolicy.PORTAL_OBSIDIAN
                + " | rods " + InventoryHelper.count(ctx.player, Items.BLAZE_ROD) + "/" + BLAZE_RODS_NEEDED
                + " | pearls " + InventoryHelper.count(ctx.player, Items.ENDER_PEARL) + "/" + PEARLS_NEEDED
                + " | eyes " + InventoryHelper.count(ctx.player, Items.ENDER_EYE) + "/" + EYES_NEEDED;
        ctx.debug.missionMemory = "fortress=" + shortPos(fortress)
                + " portal=" + shortPos(netherPortal)
                + " chest=" + netherChestChecked
                + " avoided(blaze/pearl)=" + unreachableBlazes.size() + "/" + unreachableEndermen.size()
                + " attempts=" + phaseAttempts;
        ctx.debug.missionLoop = routeNoProgressTicks >= 120
                ? "possible loop: no phase/inventory/task/action change for " + routeNoProgressTicks
                + " ticks in " + phase + " (" + childActivity + ")"
                : "no phase/inventory/task/action change " + routeNoProgressTicks + " ticks";
    }

    private static String shortPos(BlockPos pos) {
        return pos == null ? "-" : pos.toShortString();
    }

    private static boolean hasWeapon(BotContext ctx) {
        return InventoryHelper.anyMatch(ctx.player, stack -> stack.is(Items.WOODEN_SWORD)
                || stack.is(Items.STONE_SWORD) || stack.is(Items.IRON_SWORD)
                || stack.is(Items.DIAMOND_SWORD) || stack.is(Items.NETHERITE_SWORD)
                || stack.is(Items.WOODEN_AXE) || stack.is(Items.STONE_AXE)
                || stack.is(Items.IRON_AXE) || stack.is(Items.DIAMOND_AXE)
                || stack.is(Items.NETHERITE_AXE) || stack.is(Items.BOW));
    }

    private static boolean hasArmor(BotContext ctx) {
        return InventoryHelper.anyMatch(ctx.player, stack -> stack.has(DataComponents.EQUIPPABLE)
                && stack.get(DataComponents.EQUIPPABLE).slot().isArmor());
    }

    private static boolean needsShield(BotContext ctx) {
        return !InventoryHelper.has(ctx.player, Items.SHIELD, 1) && !hasArmor(ctx);
    }

    private static int woodProducts(BotContext ctx) {
        return planks(ctx) + InventoryHelper.count(ctx.player,
                stack -> stack.is(ItemTags.LOGS)) * PLANKS_PER_LOG;
    }

    private boolean hasFood(BotContext ctx) {
        return InventoryHelper.count(ctx.player, stack -> stack.has(
                DataComponents.FOOD)) >= foodNeeded();
    }

    private int foodNeeded() {
        return ROUTE_SCAVENGE.equals(routeStyle) ? FOOD_NEEDED + 2 : FOOD_NEEDED;
    }

    /**
     * Supplies worth taking from a visible Nether chest before committing to an enderman hunt.
     * This is deliberately item-based: the container still has to pass Vision and be reachable,
     * so it does not turn a loaded bastion into an x-ray target.
     */
    /**
     * What is worth carrying out of an Overworld structure chest.
     *
     * <p>Scoped to things this run actually spends: the portal kit, the iron it would otherwise
     * mine, food, and the two items that change the route outright - a flint and steel or fire
     * charge skips the whole gravel phase, and a diamond pickaxe is the only thing that makes a
     * ruined portal's own obsidian harvestable. Gold and trinkets are left behind; an inventory
     * slot is worth more than a loot count.</p>
     */
    private static boolean isOverworldSupply(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.has(DataComponents.FOOD)
                || stack.is(Items.OBSIDIAN)
                || stack.is(Items.FLINT_AND_STEEL)
                || stack.is(Items.FIRE_CHARGE)
                || stack.is(Items.FLINT)
                || stack.is(Items.BUCKET)
                || stack.is(Items.IRON_INGOT)
                || stack.is(Items.RAW_IRON)
                || stack.is(Items.IRON_PICKAXE)
                || stack.is(Items.IRON_SWORD)
                || stack.is(Items.IRON_AXE)
                || stack.is(Items.DIAMOND)
                || stack.is(Items.DIAMOND_PICKAXE)
                || stack.is(Items.DIAMOND_SWORD)
                || stack.is(Items.ENDER_PEARL);
    }

    private static boolean isNetherSupply(ItemStack stack) {
        return FoodContainerTask.isFoodSupply(stack)
                || stack.is(Items.ENDER_PEARL)
                || stack.is(Items.OBSIDIAN)
                || stack.is(Items.CRYING_OBSIDIAN)
                || stack.is(Items.GOLD_INGOT)
                || stack.is(Items.GOLD_BLOCK)
                || stack.is(Items.IRON_INGOT)
                || stack.is(Items.STRING)
                || stack.is(Items.FIRE_CHARGE);
    }

    private static boolean needsNetherFood(BotContext ctx) {
        return ctx.level.dimension() == Level.NETHER
                && InventoryHelper.count(ctx.player, stack -> stack.has(DataComponents.FOOD))
                < NETHER_EMERGENCY_FOOD;
    }

    private static int planks(BotContext ctx) {
        return InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.PLANKS));
    }

    /**
     * Makes a table-dependent craft reuse the table established by the route instead of failing
     * after a mining or scouting phase moved the player away from it.
     */
    private static CraftTask craftAtRememberedTable(CraftTask task) {
        return task.withTableSearchRadius(LONG_ROUTE_TABLE_SEARCH_RADIUS);
    }

    private static Set<Block> woodBlocks() {
        return Set.of(Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
                Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG,
                Blocks.BAMBOO_BLOCK);
    }

    private static BlockPos findPortal(BotContext ctx, BlockPos centre, int radius) {
        return BlockScanner.findNearest(ctx.level, centre, Set.of(Blocks.NETHER_PORTAL), radius,
                ctx.level.getMinY(), ctx.level.getMaxY(), Set.of(),
                (pos, state) -> ctx.omniscientMining() || Vision.isVisible(ctx, pos));
    }

    /**
     * Gathers food the way a speedrunner does: visible village/shipwreck supplies in the overworld,
     * with a bounded visible hoglin fallback in the Nether. Every world read is limited to loaded,
     * visible blocks and entities; this task must not turn a food phase into an animal radar.
     */
    private static final class FoodTask implements Task {
        private enum State {
            SURFACE, FARM, CHEST, VILLAGE, SHIP, NETHER_CHEST, HUNT, LOOT, ROAM, ANIMAL_ROAM
        }

        /** How far to look for village hay/crops before searching containers. */
        private static final int FARM_RADIUS = 40;
        /** Visible chest range covers an ordinary village or a nearby shipwreck without X-ray. */
        private static final int FOOD_CONTAINER_RADIUS = 64;
        /** A shipwreck can be visible before its chest is exposed to the player. */
        private static final int SHIP_SCOUT_RADIUS = 128;
        /**
         * Keep searching for a visible settlement/shipwreck before touching the animal fallback,
         * but do not spend the whole hunger bar proving that this forest has no village. Eight
         * bounded, committed surface sweeps give a fresh world a real chance to reach a nearby
         * source while keeping the search finite and visible-only. When hunger is already low,
         * FoodSearchPolicy shortens this budget and the travel distance rather than disabling the
         * only remaining way to reach a food source.
         */
        /** Give the visible-only Nether fallback a few chances to encounter a loaded hoglin. */
        private static final int MAX_ANIMAL_SEARCH_ROAMS = 3;
        /** Opportunistic food must stay close enough to the route that it cannot hide a resource cave. */
        private static final int OPPORTUNISTIC_RADIUS = 32;
        /** Do not circle one wreck forever when it contains no food. */
        private static final int MAX_SHIP_APPROACHES = 2;
        /** Leave a hunger reserve for the visible-animal fallback instead of starving mid-roam. */
        private static final int MIN_FOOD_TO_KEEP_PROSPECTING = 12;
        /** Bales worth taking; three is twenty-seven wheat, which is nine bread. */
        private static final int HAY_WANTED = 3;
        private static final int CROP_WANTED = 24;
        private static final int WHEAT_PER_BREAD = 3;
        private static final int WHEAT_PER_HAY = 9;
        /** Edible the moment they are picked; wheat is in here for the bread it becomes. */
        private static final Set<Block> FOOD_CROPS = Set.of(
                Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS, Blocks.WHEAT);
        /** Targets used only to judge a loaded biome; the actual source still has to be visible. */
        private static final Set<Block> FOOD_BIOME_TARGETS = Set.of(
                Blocks.HAY_BLOCK, Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES,
                Blocks.BEETROOTS, Blocks.MELON, Blocks.SWEET_BERRY_BUSH);
        /** A shoreline is the human-visible route to a shipwreck, not a structure locator. */
        private static final Set<Block> SHORELINE_BIOME_TARGETS = Set.of(
                Blocks.WATER, Blocks.KELP, Blocks.SEAGRASS);
        /**
         * Vanilla shipwrecks use oak or spruce hull pieces. This is deliberately a block-shape
         * hint, not a structure lookup: the candidate still has to be visible, near water, and
         * part of a visible mixed wood cluster before the bot walks toward it.
         */
        private static final Set<Block> SHIP_WOOD = Set.of(
                Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS,
                Blocks.OAK_LOG, Blocks.SPRUCE_LOG,
                Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS,
                Blocks.OAK_SLAB, Blocks.SPRUCE_SLAB,
                Blocks.OAK_FENCE, Blocks.SPRUCE_FENCE,
                Blocks.OAK_TRAPDOOR, Blocks.SPRUCE_TRAPDOOR);
        /** Wooden entrances are visible waypoints for entering a village house before chest search. */
        private static final Set<Block> VILLAGE_DOORS = Set.of(
                Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.BIRCH_DOOR, Blocks.JUNGLE_DOOR,
                Blocks.ACACIA_DOOR, Blocks.DARK_OAK_DOOR, Blocks.MANGROVE_DOOR, Blocks.CHERRY_DOOR,
                Blocks.BAMBOO_DOOR, Blocks.CRIMSON_DOOR, Blocks.WARPED_DOOR);
        /**
         * Land animals if there are any, fish only if there are not.
         * <p>
         * {@link KillTask} takes the nearest target, and near is the wrong test here: a cod five
         * blocks away beats a cow twenty away and is worth a third as much. Fish flee, they flee
         * into water, and every one is a separate swim - a measured run spent 1,824 ticks chasing
         * salmon and cod while carrying an axe past a field. They stay on the list because a
         * shoreline sometimes has nothing else, which is why they were added; they just stop
         * outranking a pig.
         */
        private Set<EntityType<?>> overworldTargets(BotContext ctx) {
            if (landAnimalVisible(ctx)) {
                return LAND_FOOD_ANIMALS;
            }
            // Topping up is not worth going fishing for.
            //
            // A fish has to be swum to, it flees, it cannot be crit, and the bot has to get back
            // out of the water afterwards: a measured run spent 3,328 ticks in the middle of the
            // iron phase to land two salmon. That is a fine trade when the alternative is starving
            // and a terrible one when there is already food in the bag, so the fallback to fish
            // belongs to the hungry search only. An opportunistic hunt with no land animal in sight
            // simply declines and carries on.
            return opportunistic ? Set.of() : OVERWORLD_FOOD_ANIMALS;
        }

        private boolean landAnimalVisible(BotContext ctx) {
            AABB box = ctx.player.getBoundingBox().inflate(opportunistic ? 24.0 : 64.0);
            return !ctx.level.getEntities(ctx.player, box,
                    entity -> entity instanceof net.minecraft.world.entity.LivingEntity living
                            && LAND_FOOD_ANIMALS.contains(entity.getType())
                            && !KillTask.isWorthlessCalf(living)
                            && Vision.isEntityVisible(ctx, living)).isEmpty();
        }

        /**
         * Fish are food, and on a shoreline they are often the only food.
         * <p>
         * Salmon and cod were missing here, which is why a run could stand beside a river full of
         * them and report that it could not find anything to eat. Pufferfish are deliberately absent
         * - eating one is poison - and tropical fish are not food at all.
         */
        private static final Set<EntityType<?>> OVERWORLD_FOOD_ANIMALS = Set.of(
                EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN,
                EntityType.RABBIT, EntityType.SALMON, EntityType.COD);
        /** The same list without the swimming half, used whenever anything on land is in sight. */
        private static final Set<EntityType<?>> LAND_FOOD_ANIMALS = Set.of(
                EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN,
                EntityType.RABBIT);

        private final int wanted;
        private final boolean opportunistic;
        private State state = State.FARM;
        /** Survives the KillTask rebuild each hunt cycle; see {@link KillTask} for why that matters. */
        private final java.util.Set<Integer> unreachableMobs = new java.util.HashSet<>();
        /** Chests already opened and found not to contain a useful food supply. */
        private final java.util.Set<Long> unavailableContainers = new java.util.HashSet<>();
        /** Visible hull pieces already used as approach anchors in this food pass. */
        private final java.util.Set<Long> approachedShips = new java.util.HashSet<>();
        private Task current;
        private int roams;
        private int animalRoams;
        private int shipApproaches;
        private int sourceHeadingStopsRemaining;
        private Float sourceHeading;
        private boolean emergencySourceSearch;
        private BlockPos shipCheckedFrom;
        private boolean exhaustedWithoutFood;
        private boolean opportunisticNoSource;
        /** The route position at which a visible local food opportunity was noticed. */
        private BlockPos opportunisticAnchor;
        private String status = "";

        FoodTask(int wanted) {
            this(wanted, false);
        }

        FoodTask(int wanted, boolean opportunistic) {
            this.wanted = wanted;
            this.opportunistic = opportunistic;
        }

        @Override
        public String name() { return "Gather Food"; }

        @Override
        public String status() { return status; }

        boolean exhaustedWithoutFood() {
            return exhaustedWithoutFood;
        }

        @Override
        public void onStart(BotContext ctx) {
            boolean nether = ctx.level.dimension() == Level.NETHER;
            emergencySourceSearch = !nether
                    && ctx.player.getFoodData().getFoodLevel() <= MIN_FOOD_TO_KEEP_PROSPECTING;
            // In the overworld, low hunger changes how far we are willing to roam, not which
            // sources we prefer. A wreck or village already in sight is a better speedrun source
            // than an animal chase, even when the food bar is nearly empty. The Nether has no
            // overworld settlements, so hoglins remain its direct emergency fallback.
            boolean alreadyOnSurface = isSurfacePosition(ctx, ctx.player.blockPosition());
            opportunisticNoSource = false;
            opportunisticAnchor = opportunistic
                    ? ctx.player.blockPosition().immutable()
                    : null;
            if (opportunistic) {
                State local = immediateState(ctx);
                if (local == null) {
                    opportunisticNoSource = true;
                    state = State.FARM;
                } else {
                    state = local;
                }
            } else {
                state = FoodSearchPolicy.shouldStartWithAnimalFallback(nether)
                        ? State.NETHER_CHEST
                        : FoodSearchPolicy.shouldPrepareSurface(!nether, alreadyOnSurface)
                                ? State.SURFACE
                                : State.FARM;
            }
            current = null;
            roams = 0;
            animalRoams = 0;
            unreachableMobs.clear();
            unavailableContainers.clear();
            approachedShips.clear();
            shipApproaches = 0;
            sourceHeadingStopsRemaining = 0;
            sourceHeading = null;
            shipCheckedFrom = null;
            exhaustedWithoutFood = false;
            publishFoodDebug(ctx);
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            publishFoodDebug(ctx);
            if (opportunistic && opportunisticNoSource) {
                status = "no visible food opportunity; continuing the route";
                return TaskStatus.SUCCESS;
            }
            if (opportunistic && ctx.level.dimension() == Level.OVERWORLD) {
                if (opportunisticAnchor != null
                        && opportunisticAnchor.distSqr(ctx.player.blockPosition())
                        > OPPORTUNISTIC_RADIUS * OPPORTUNISTIC_RADIUS) {
                    stopCurrent(ctx);
                    status = "nearby food opportunity drifted away; continuing the route";
                    return TaskStatus.SUCCESS;
                }
                // An empty animal scan must never turn a route interruption into a new hunt.
                // Normal FOOD-phase searches have their own bounded fallback; this instance is
                // only allowed to take the thing that was visible when the route was interrupted.
                if (state == State.ROAM || state == State.ANIMAL_ROAM) {
                    stopCurrent(ctx);
                    status = "no visible local food opportunity; continuing the route";
                    return TaskStatus.SUCCESS;
                }
            }
            if (InventoryHelper.count(ctx.player, stack -> stack.has(
                    net.minecraft.core.component.DataComponents.FOOD)) >= wanted) {
                stopCurrent(ctx);
                status = "gathered enough food";
                return TaskStatus.SUCCESS;
            }

            // Hunger can fall while a normal source sweep is travelling.  The initial budget is
            // deliberately chosen once in onStart, but it must not stay generous after the
            // reserve has been spent: abandoning a long random roam is safer than starving while
            // proving that this biome has no visible village or wreck.  Keep an in-progress
            // settlement/ship approach alive because it may already be heading for food in sight;
            // only the expendable random roam is cut short.
            if (FoodSearchPolicy.shouldShortenSourceSearch(
                    emergencySourceSearch,
                    ctx.level.dimension() == Level.OVERWORLD,
                    ctx.player.getFoodData().getFoodLevel(),
                    MIN_FOOD_TO_KEEP_PROSPECTING)) {
                emergencySourceSearch = true;
                sourceHeadingStopsRemaining = 0;
                if (state == State.ROAM && current != null) {
                    stopCurrent(ctx);
                    if (ctx.level.dimension() == Level.OVERWORLD) {
                        return deferOverworldFood(ctx, "food reserve is low; no visible source");
                    }
                    status = "food reserve is low; stopping random source search";
                    return TaskStatus.FAILED;
                }
                status = "food reserve is low; shortening source search";
            }

            // The stone phase normally returns to its staging block, but a partial staircase can
            // still finish in a pocket. Do not let a food scan start from there: chest/ship/village
            // visibility and surface prospecting are meaningful only after the bot has reached a
            // walkable surface again. Re-arm this check after every movement sub-task as well, so
            // a failed roam cannot rebuild the same underground request forever.
            if (current == null && state != State.SURFACE
                    && ctx.level.dimension() == Level.OVERWORLD
                    && !isSurfacePosition(ctx, ctx.player.blockPosition())) {
                state = State.SURFACE;
            }

            if (current == null) {
                current = switch (state) {
                    case SURFACE -> new SurfaceRecoveryTask();
                    case FARM -> farmTask(ctx);
                    case CHEST -> new FoodContainerTask(FOOD_CONTAINER_RADIUS, unavailableContainers);
                    case VILLAGE -> new VillageEntryTask(FOOD_CONTAINER_RADIUS, unavailableContainers);
                    case SHIP -> {
                        if (shipApproaches++ >= MAX_SHIP_APPROACHES) {
                            yield null;
                        }
                        yield new VisibleShipApproachTask(SHIP_SCOUT_RADIUS, approachedShips);
                    }
                    case NETHER_CHEST -> new FoodContainerTask(FOOD_CONTAINER_RADIUS, unavailableContainers);
                    case HUNT -> animalHuntTask(ctx);
                    // A hunt is for food, so do not chase unrelated drops such as eggs. The generic
                    // LootTask is shared by combat callers, but this caller must carry its food
                    // predicate through every rebuild of the hunt loop.
                    case LOOT -> new LootTask(32, 80,
                            stack -> stack.has(DataComponents.FOOD));
                    case ROAM -> {
                        if (!FoodSearchPolicy.allowSourceRoam(emergencySourceSearch)) {
                            status = "source search is too hungry for another roam";
                            yield null;
                        }
                        if (roams++ >= FoodSearchPolicy.maxSourceRoams(emergencySourceSearch)) {
                            status = "could not find enough food nearby";
                            yield null;
                        }
                        // Food prospecting must remain a surface walk. Mining toward a sampled
                        // surface target turns a shoreline search into an underground tunnel and
                        // hides the very structures this phase is meant to notice. GotoTask still
                        // owns its shared climb-out recovery for a pocket left by the stone phase.
                        yield new GotoTask(new Goals.Near(randomSurfaceTarget(ctx), 8), true, false);
                    }
                    case ANIMAL_ROAM -> {
                        if (opportunistic && ctx.level.dimension() == Level.OVERWORLD) {
                            status = "no visible local animal; continuing the route";
                            yield null;
                        }
                        if (animalRoams++ >= MAX_ANIMAL_SEARCH_ROAMS) {
                            status = "no visible animals found nearby";
                            yield null;
                        }
                        // Animal fallback is an emergency clearing search. Do not send it up a
                        // mountain because BiomeScout preferred a crop-friendly biome; the extra
                        // elevation and long route spend the reserve before the next sightline.
                        yield new GotoTask(new Goals.Near(randomAnimalTarget(ctx), 8), true, true);
                    }
                };
                if (current == null) {
                    if (opportunistic && ctx.level.dimension() == Level.OVERWORLD
                            && (state == State.ROAM || state == State.ANIMAL_ROAM)) {
                        status = "no visible local food opportunity; continuing the route";
                        return TaskStatus.SUCCESS;
                    }
                    if (state == State.SURFACE) {
                        status = "could not find a walkable surface nearby";
                        return TaskStatus.FAILED;
                    }
                    if (state == State.FARM) {
                        // No visible hay or mature crop here. Check settlement/shipwreck containers
                        // before spending the run's time and hunger on moving animals.
                        state = State.CHEST;
                        return TaskStatus.RUNNING;
                    }
                    if (state == State.VILLAGE) {
                        // A shipwreck is a visual opportunity, not a destination to search for by
                        // block index. Only spend a scan when this area contains a visible wood /
                        // shoreline clue, and remember the area after checking it once.
                        boolean checkedHere = shipCheckedFrom != null
                                && shipCheckedFrom.distSqr(ctx.player.blockPosition()) <= 256.0;
                        BlockPos clue = visibleShipClue(ctx);
                        shipCheckedFrom = ctx.player.blockPosition().immutable();
                        if (FoodSearchPolicy.shouldProbeVisibleShipwreck(clue != null, checkedHere)) {
                            ctx.debug.target("visible shipwreck clue", clue,
                                    Vision.inspect(ctx, clue).verdict());
                            ctx.debug.decide("visible shoreline clue; check this shipwreck once");
                            state = State.SHIP;
                        } else {
                            ctx.debug.target("shipwreck clue", clue,
                                    clue == null ? "none visible; keep walking" : "already checked here");
                            ctx.debug.decide(clue == null
                                    ? "no shipwreck clue in view; skip structure search and keep walking"
                                    : "shipwreck area already checked; keep walking");
                            state = State.ROAM;
                        }
                        return TaskStatus.RUNNING;
                    }
                    if (state == State.NETHER_CHEST) {
                        // The first Nether food check is deliberately chest-first: a visible
                        // bastion chest is the speedrunner fallback, while hoglins remain the
                        // bounded visible-only fallback after that check.
                        state = State.HUNT;
                        ctx.debug.decide("no visible Nether food chest; fall back to visible hoglins");
                        return TaskStatus.RUNNING;
                    }
                    if (state == State.SHIP) {
                        // A ship-like silhouette was not visible from this position. Continue the
                        // bounded surface search; this is still preferable to an animal radar.
                        state = State.ROAM;
                        return TaskStatus.RUNNING;
                    }
                    if (state == State.ROAM && ctx.level.dimension() == Level.OVERWORLD) {
                        return deferOverworldFood(ctx, "bounded source search finished without food");
                    }
                    if (state == State.HUNT && animalRoams < MAX_ANIMAL_SEARCH_ROAMS) {
                        if (opportunistic && ctx.level.dimension() == Level.OVERWORLD) {
                            status = "no visible local animal; continuing the route";
                            return TaskStatus.SUCCESS;
                        }
                        // A KillTask only sees the current loaded, visible slice. Move a bounded
                        // distance and try again rather than treating one empty sightline as proof
                        // that the biome has no animals.
                        state = State.ANIMAL_ROAM;
                        status = "no visible animals here; checking another nearby clearing";
                        return TaskStatus.RUNNING;
                    }
                    return TaskStatus.FAILED;
                }
                current.start(ctx);
            }

            TaskStatus result = current.tick(ctx);
            status = state + " - " + current.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            boolean emptyAnimalScan = state == State.HUNT
                    && result == TaskStatus.SUCCESS
                    && current instanceof KillTask kill
                    && kill.killedCount() == 0;
            current.stop(ctx);
            current = null;
            if (opportunistic) {
                status = result == TaskStatus.SUCCESS
                        ? "took a nearby food opportunity"
                        : "nearby food opportunity was unavailable; continuing the route";
                return TaskStatus.SUCCESS;
            }
            if (state == State.SURFACE && result == TaskStatus.FAILED) {
                status = "could not reach open ground for food search";
                return TaskStatus.FAILED;
            }
            if (emptyAnimalScan) {
                if (opportunistic && ctx.level.dimension() == Level.OVERWORLD) {
                    status = "no visible local animal; continuing the route";
                    return TaskStatus.SUCCESS;
                }
                if (animalRoams >= MAX_ANIMAL_SEARCH_ROAMS) {
                    status = "no visible hoglins found nearby";
                    return TaskStatus.FAILED;
                }
                // KillTask treats an empty radius as a successful scan. Move before rebuilding
                // it, otherwise the food phase can spin forever over the same sightline.
                state = State.ANIMAL_ROAM;
                status = "no visible animals here; checking another nearby clearing";
                return TaskStatus.RUNNING;
            }
            if (state == State.ROAM && result == TaskStatus.FAILED
                    && ctx.level.dimension() == Level.OVERWORLD) {
                return deferOverworldFood(ctx, "could not reach the next surface clearing");
            }
            state = switch (state) {
                case SURFACE -> State.FARM;
                // Stay on FARM while it keeps finding work: a haystack is several blocks and a
                // field is many crops, and each is a separate task.
                // A successful harvest/craft may still leave another visible field in the same
                // village. Re-check FARM first; leaving for a random roam here is how the bot
                // walked away from the village while it still had local wheat to turn into bread.
                case FARM -> State.FARM;
                case CHEST -> result == TaskStatus.FAILED ? State.VILLAGE : State.FARM;
                case VILLAGE -> {
                    if (result != TaskStatus.FAILED) {
                        yield State.FARM;
                    }
                    boolean checkedHere = shipCheckedFrom != null
                            && shipCheckedFrom.distSqr(ctx.player.blockPosition()) <= 256.0;
                    BlockPos clue = visibleShipClue(ctx);
                    shipCheckedFrom = ctx.player.blockPosition().immutable();
                    if (FoodSearchPolicy.shouldProbeVisibleShipwreck(clue != null, checkedHere)) {
                        ctx.debug.target("visible shipwreck clue", clue,
                                Vision.inspect(ctx, clue).verdict());
                        ctx.debug.decide("visible shoreline clue; check this shipwreck once");
                        yield State.SHIP;
                    }
                    ctx.debug.target("shipwreck clue", clue,
                            clue == null ? "none visible; keep walking" : "already checked here");
                    ctx.debug.decide(clue == null
                            ? "no shipwreck clue in view; skip structure search and keep walking"
                            : "shipwreck area already checked; keep walking");
                    yield State.ROAM;
                }
                case SHIP -> result == TaskStatus.SUCCESS ? State.CHEST : State.ROAM;
                case NETHER_CHEST -> State.HUNT;
                case HUNT -> State.LOOT;
                case LOOT -> State.HUNT;
                // Roaming may well have walked into a village, so look for standing food again
                // before starting another chase.
                case ROAM -> State.FARM;
                case ANIMAL_ROAM -> State.HUNT;
            };
            return TaskStatus.RUNNING;
        }

        private TaskStatus deferOverworldFood(BotContext ctx, String reason) {
            exhaustedWithoutFood = true;
            status = reason + "; continuing toward the Nether chest fallback";
            ctx.debug.decide("skip repeated food search: continue to Nether bastion fallback");
            return TaskStatus.SUCCESS;
        }

        /** Returns only a source that is already in the player's visible, loaded area. */
        boolean hasImmediateOpportunity(BotContext ctx) {
            return immediateState(ctx) != null;
        }

        private State immediateState(BotContext ctx) {
            if (ctx.level.dimension() != Level.OVERWORLD) {
                return null;
            }
            if (InventoryHelper.count(ctx.player, Items.WHEAT) >= WHEAT_PER_BREAD
                    || InventoryHelper.has(ctx.player, Items.HAY_BLOCK, 1)
                    || visibleFood(ctx, Set.of(Blocks.HAY_BLOCK), state -> true,
                            ctx.omniscientMining()) != null
                    || visibleFood(ctx, FOOD_CROPS, com.etka.lune.bot.util.CropHelper::isMature,
                            ctx.omniscientHarvesting()) != null) {
                return State.FARM;
            }
            // Structures are deliberately not an opportunistic interruption. A visible door or
            // shoreline clue is useful to the dedicated FOOD phase, but it is not a reason to
            // abandon an iron/stone route; villages and bastions remain the planned fallback.
            if (hasVisibleOverworldAnimal(ctx)) {
                return State.HUNT;
            }
            if (hasVisibleFoodDrop(ctx)) {
                return State.LOOT;
            }
            return null;
        }

        private BlockPos visibleVillageDoor(BotContext ctx) {
            return BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(), VILLAGE_DOORS,
                    FOOD_CONTAINER_RADIUS, ctx.level.getMinY(), ctx.level.getMaxY(), Set.of(),
                    (pos, state) -> Vision.isVisible(ctx, pos));
        }

        private boolean hasVisibleOverworldAnimal(BotContext ctx) {
            AABB box = ctx.player.getBoundingBox().inflate(24.0);
            return !ctx.level.getEntities(ctx.player, box,
                    entity -> entity instanceof net.minecraft.world.entity.LivingEntity living
                            && OVERWORLD_FOOD_ANIMALS.contains(entity.getType())
                            // A field of calves is not a food opportunity: they drop nothing.
                            && !KillTask.isWorthlessCalf(living)
                            && living.distanceToSqr(ctx.player) <= 24.0 * 24.0
                            && Vision.isEntityVisible(ctx, living)).isEmpty();
        }

        private boolean hasVisibleFoodDrop(BotContext ctx) {
            AABB box = ctx.player.getBoundingBox().inflate(16.0);
            return !ctx.level.getEntities(ctx.player, box,
                    entity -> entity instanceof ItemEntity item
                            && item.isAlive()
                            && !item.hasPickUpDelay()
                            && item.getItem().has(DataComponents.FOOD)
                            && item.distanceToSqr(ctx.player) <= 16.0 * 16.0
                            && Vision.isEntityVisible(ctx, item)).isEmpty();
        }

        private void publishFoodDebug(BotContext ctx) {
            int maxRoams = FoodSearchPolicy.maxSourceRoams(emergencySourceSearch);
            ctx.debug.intent = "food policy: " + state.name().toLowerCase()
                    + (ctx.level.dimension() == Level.NETHER
                    ? " (Nether fallback may hunt visible hoglins)"
                    : " (Overworld prefers farm, village and shipwreck food)");
            ctx.debug.searchAttempt = roams;
            ctx.debug.searchLimit = maxRoams;
            ctx.debug.memory = "ship anchors " + approachedShips.size()
                    + ", chests " + unavailableContainers.size()
                    + ", mobs written off " + unreachableMobs.size()
                    + ", ship area " + (shipCheckedFrom == null ? "new" : shipCheckedFrom.toShortString());
            ctx.debug.giveUp = "surface roams " + roams + "/" + maxRoams
                    + ", ship approaches " + shipApproaches + "/" + MAX_SHIP_APPROACHES
                    + ", animal roams " + animalRoams + "/" + MAX_ANIMAL_SEARCH_ROAMS;
            ctx.debug.nextDecision = switch (state) {
                case FARM -> "check visible hay/crops, then village containers";
                case CHEST, VILLAGE -> "check only visible settlement/container targets";
                case SHIP -> "turn/glance for visible shipwreck wood, then approach it";
                case NETHER_CHEST -> "check visible bastion-style chests before hunting hoglins";
                case ROAM -> "walk a bounded surface distance before looking again";
                case HUNT, ANIMAL_ROAM -> "Nether only: scan for visible hoglins";
                case LOOT -> "collect nearby food drops, then rescan mobs";
                case SURFACE -> "recover to dry surface before food perception";
            };
        }

        /**
         * The next bit of standing food worth taking, or null when there is none in sight.
         * <p>
         * Ordered so that anything already in the bag is finished before more is gathered - a bale
         * carried around is not food until it has been through wheat and bread, and a bot that keeps
         * collecting without converting ends up starving next to a stack of hay.
         */
        private Task farmTask(BotContext ctx) {
            int wheat = InventoryHelper.count(ctx.player, Items.WHEAT);
            if (wheat >= WHEAT_PER_BREAD) {
                int loaves = InventoryHelper.count(ctx.player, Items.BREAD) + wheat / WHEAT_PER_BREAD;
                return craftAtRememberedTable(CraftTask.of(Items.BREAD, loaves, true));
            }
            int bales = InventoryHelper.count(ctx.player, Items.HAY_BLOCK);
            if (bales > 0) {
                return CraftTask.of(Items.WHEAT, wheat + bales * WHEAT_PER_HAY, false);
            }
            if (visibleFood(ctx, Set.of(Blocks.HAY_BLOCK), state -> true,
                    ctx.omniscientMining()) != null) {
                return new MineTask(Set.of(Blocks.HAY_BLOCK), FARM_RADIUS,
                        ctx.level.getMinY(), ctx.level.getMaxY(), opportunistic ? 1 : HAY_WANTED,
                        false, false, false, true);
            }
            if (visibleFood(ctx, FOOD_CROPS, com.etka.lune.bot.util.CropHelper::isMature,
                    ctx.omniscientHarvesting()) != null) {
                return new HarvestTask(FOOD_CROPS, FARM_RADIUS, CROP_WANTED);
            }
            return null;
        }

        /** Nearest matching block the bot can actually see, or null. Never an X-ray search. */
        private BlockPos visibleFood(BotContext ctx, Set<Block> blocks,
                                     java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> extra,
                                     boolean omniscient) {
            return BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(), blocks,
                    FARM_RADIUS, ctx.level.getMinY(), ctx.level.getMaxY(), Set.of(),
                    (pos, blockState) -> extra.test(blockState)
                            && (omniscient || Vision.isVisible(ctx, pos)));
        }

        /** Finds only a visible shoreline wood clue; it never turns a shipwreck into a locator. */
        private BlockPos visibleShipClue(BotContext ctx) {
            if (ctx.level.dimension() != Level.OVERWORLD) {
                return null;
            }
            return BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(), SHIP_WOOD,
                    SHIP_SCOUT_RADIUS, ctx.level.getMinY(), ctx.level.getMaxY(), Set.of(),
                    (pos, state) -> Vision.isVisible(ctx, pos) && nearVisibleWater(ctx, pos));
        }

        private static boolean nearVisibleWater(BotContext ctx, BlockPos pos) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos nearby = pos.offset(dx, dy, dz);
                        if (ctx.level.hasChunkAt(nearby)
                                && ctx.level.getBlockState(nearby).getFluidState().is(FluidTags.WATER)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private Task animalHuntTask(BotContext ctx) {
            // Do not punch livestock.
            //
            // A sheep is eight hits with a bare fist and about three with a sword, and at the very
            // start of a run - no weapon, no tools - the bot would walk up to the first animal it
            // saw and spend the opening minute swinging at it. The animal is not going anywhere;
            // the same kill after the stone kit costs a third as long. Opportunistic hunts wait for
            // a weapon, while a genuinely hungry one goes ahead regardless, because starving is
            // worse than a slow kill.
            if (opportunistic && ctx.level.dimension() == Level.OVERWORLD
                    && !hasWeapon(ctx)) {
                return null;
            }
            Set<EntityType<?>> targets = ctx.level.dimension() == Level.NETHER
                    ? Set.of(EntityType.HOGLIN)
                    : overworldTargets(ctx);
            if (targets.isEmpty()) {
                // Nothing here worth stopping for.
                return null;
            }
            return new KillTask(targets, opportunistic ? 24 : 64,
                    new KillOptions(false, true, false,
                            KillOptions.EndermanSafety.DIRECT,
                            KillOptions.WeaponPreference.SWORD, false),
                    unreachableMobs);
        }

        /**
         * Pick a roam destination from the surface heightmap, never from the player's current Y.
         * A failed source check may leave the player beside a cave or in shallow water; carrying
         * that Y into the next destination makes food search drift underground. The heading is
         * held for two sweeps so the bot commits to a direction like a player walking a coastline,
         * and every third sweep biases toward water where a shipwreck can actually be seen.
         */
        private BlockPos randomSurfaceTarget(BotContext ctx) {
            BlockPos current = ctx.player.blockPosition();
            if (sourceHeading == null || sourceHeadingStopsRemaining <= 0) {
                Set<Block> directionTargets = FoodSearchPolicy.shouldProspectShoreline(roams)
                        ? SHORELINE_BIOME_TARGETS
                        : FOOD_BIOME_TARGETS;
                sourceHeading = BiomeScout.choose(ctx, directionTargets, sourceHeading, null)
                        .map(BiomeScout.Heading::yaw)
                        .orElse(null);
                sourceHeadingStopsRemaining = 2;
            }
            sourceHeadingStopsRemaining--;
            BlockPos best = null;
            int bestScore = Integer.MAX_VALUE;
            for (int attempt = 0; attempt < 32; attempt++) {
                int x;
                int z;
                if (sourceHeading != null) {
                    int roamMin = FoodSearchPolicy.sourceRoamMinDistance(emergencySourceSearch);
                    int roamMax = FoodSearchPolicy.sourceRoamMaxDistance(emergencySourceSearch);
                    int distance = roamMin
                            + ctx.player.getRandom().nextInt(roamMax - roamMin + 1);
                    int lateral = ctx.player.getRandom().nextInt(49) - 24;
                    double radians = Math.toRadians(sourceHeading);
                    x = current.getX() + (int) Math.round(-Math.sin(radians) * distance
                            + Math.cos(radians) * lateral);
                    z = current.getZ() + (int) Math.round(Math.cos(radians) * distance
                            + Math.sin(radians) * lateral);
                } else {
                    x = current.getX() + ctx.player.getRandom().nextInt(97) - 48;
                    z = current.getZ() + ctx.player.getRandom().nextInt(97) - 48;
                }
                BlockPos chunkProbe = new BlockPos(x, ctx.level.getMinY(), z);
                if (!ctx.level.hasChunkAt(chunkProbe)) {
                    continue;
                }
                BlockPos drySurface = SurfaceRecoveryTask.findDrySurface(ctx, x, z);
                if (drySurface == null
                        || !FoodSearchPolicy.staysOnSurfaceBand(current.getY(), drySurface.getY())) {
                    continue;
                }
                int horizontal = Math.abs(drySurface.getX() - current.getX())
                        + Math.abs(drySurface.getZ() - current.getZ());
                int score = FoodSearchPolicy.surfaceTargetScore(current.getY(), drySurface.getY(), horizontal);
                if (score < bestScore) {
                    best = drySurface;
                    bestScore = score;
                }
                // Keep the committed heading when it is a safe surface route. Do not blindly
                // accept a mountain column that biome knowledge may have rated highly; the
                // ship/village scan must not spend its reserve climbing to a new Y.
                if (Math.abs(drySurface.getY() - current.getY()) <= 4) {
                    return drySurface;
                }
            }
            if (best != null) {
                return best;
            }
            BlockPos currentDrySurface = SurfaceRecoveryTask.findDrySurface(ctx,
                    current.getX(), current.getZ());
            return currentDrySurface != null ? currentDrySurface : current;
        }

        /**
         * Pick a nearby, similarly elevated dry clearing for the visible-animal fallback.
         *
         * A food hunt is not a sightseeing expedition: selecting the highest loaded surface in
         * the direction chosen for crops can turn one empty animal scan into a climb up a cliff.
         * Prefer a short horizontal move whose feet height is close to the current one, while
         * still keeping the loaded-chunk and dry-surface checks used by ordinary prospecting.
         */
        private BlockPos randomAnimalTarget(BotContext ctx) {
            BlockPos current = ctx.player.blockPosition();
            BlockPos best = null;
            int bestScore = Integer.MAX_VALUE;
            for (int attempt = 0; attempt < 32; attempt++) {
                double angle = ctx.player.getRandom().nextDouble() * Math.PI * 2.0;
                int distance = 12 + ctx.player.getRandom().nextInt(21);
                int x = current.getX() + (int) Math.round(Math.cos(angle) * distance);
                int z = current.getZ() + (int) Math.round(Math.sin(angle) * distance);
                BlockPos probe = new BlockPos(x, ctx.level.getMinY(), z);
                if (!ctx.level.hasChunkAt(probe)) {
                    continue;
                }
                BlockPos dry = SurfaceRecoveryTask.findDrySurface(ctx, x, z);
                if (dry == null
                        || !FoodSearchPolicy.staysOnSurfaceBand(current.getY(), dry.getY())) {
                    continue;
                }
                int vertical = Math.abs(dry.getY() - current.getY());
                int horizontal = Math.abs(dry.getX() - current.getX())
                        + Math.abs(dry.getZ() - current.getZ());
                int score = FoodSearchPolicy.surfaceTargetScore(current.getY(), dry.getY(), horizontal);
                if (score < bestScore) {
                    best = dry;
                    bestScore = score;
                }
                if (vertical <= 6) {
                    return dry;
                }
            }
            return best != null ? best : current;
        }

        /** True when the player's feet are on the top walkable band of the current column. */
        private static boolean isSurfacePosition(BotContext ctx, BlockPos feet) {
            return SurfaceRecoveryTask.isOnDrySurface(ctx, feet);
        }

        @Override
        public void onStop(BotContext ctx) {
            stopCurrent(ctx);
            ctx.input.reset();
        }

        private void stopCurrent(BotContext ctx) {
            if (current != null) {
                current.stop(ctx);
                current = null;
            }
        }
    }

    /**
     * Walks toward a shipwreck-like wood cluster that is actually visible from the current
     * position. The bot does not use a structure locator or inspect unloaded chunks: this is only
     * a prospecting step for the case where the wreck is visible but its chest is still hidden by
     * the hull or distance.
     */
    private static final class VisibleShipApproachTask implements Task {
        private final int radius;
        private final Set<Long> approached;
        private final HeadScanner headScanner = new HeadScanner(HeadScanner.Style.GLANCE);
        private final TargetIndex index = new TargetIndex();
        private BlockPos scanCentre;
        private BlockPos target;
        private GotoTask approach;
        private String status = "";

        VisibleShipApproachTask(int radius, Set<Long> approached) {
            this.radius = Math.max(1, radius);
            this.approached = approached;
        }

        @Override
        public String name() {
            return "Approach Visible Shipwreck";
        }

        @Override
        public String status() {
            return status;
        }

        @Override
        public void onStart(BotContext ctx) {
            target = null;
            scanCentre = ctx.player.blockPosition();
            headScanner.reset(ctx.player);
            index.invalidate();
            status = "checking ahead for a visible shipwreck";
            ctx.debug.intent = "looking for a visible shipwreck silhouette";
            ctx.debug.searchAnchor = scanCentre;
            ctx.debug.searchLimit = 1;
            ctx.debug.giveUp = "glance, then sweep once; no structure locator";
            ctx.debug.decide("glance ahead first; widen only if the coastline gives no clue");
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            if (target == null) {
                BlockPos live = ctx.player.blockPosition();
                if (scanCentre == null || scanCentre.distSqr(live) > 9.0) {
                    scanCentre = live;
                    headScanner.reset(ctx.player);
                    index.invalidate();
                }

                if (!Vision.isPanoramic()
                        && (headScanner.isTurning() || headScanner.isVerticalGlance())) {
                    headScanner.tickTurn(ctx);
                    status = "looking for shipwreck wood - " + headScanner.status();
                    return TaskStatus.RUNNING;
                }

                int scanLimit = Math.min(radius, (int) Vision.maxRange(ctx));
                int yMin = Math.max(ctx.level.getMinY(), scanCentre.getY() - 24);
                int yMax = Math.min(ctx.level.getMaxY(), scanCentre.getY() + 24);
                if (!index.isUsable(scanCentre, FoodTask.SHIP_WOOD, scanLimit, yMin, yMax)) {
                    index.rebuild(ctx.level, scanCentre, FoodTask.SHIP_WOOD, scanLimit, yMin, yMax);
                }
                ctx.debug.intent = "checking visible wood clusters near water";
                ctx.debug.searchAnchor = scanCentre;
                ctx.debug.searchCandidates = index.size();
                target = index.nearest(ctx.level, scanCentre, approached,
                        (pos, state) -> isVisibleShipCandidate(ctx, pos, state));
                if (target != null) {
                    approached.add(target.asLong());
                    ctx.debug.target("visible shipwreck wood", target, Vision.inspect(ctx, target).verdict()
                            + "; shape/water test passed");
                    ctx.debug.decide("shipwreck candidate passed; approach before searching its chest");
                    status = "visible shipwreck wood at " + target.toShortString();
                } else if (!Vision.isPanoramic() && headScanner.advance()) {
                    ctx.debug.decide("candidate not in current glance; turn to the next view");
                    status = "no shipwreck ahead; checking " + headScanner.status();
                    return TaskStatus.RUNNING;
                } else if (!Vision.isPanoramic() && headScanner.escalate(ctx.player)) {
                    // A ship seen on the shoreline is often off the initial facing. Start with
                    // the cheap human glance, then widen to the shared sweep before concluding
                    // that the visible coastline has no wreck. This keeps the initial look
                    // natural without making a side-on wreck invisible to the speedrun.
                    status = "glance was empty; widening shipwreck search to a sweep";
                    ctx.debug.decide("glance empty; widen to a human-like sweep before giving up");
                    return TaskStatus.RUNNING;
                } else {
                    BlockPos candidate = index.nearestCandidate(ctx.level, scanCentre, approached);
                    if (candidate != null) {
                        var sight = Vision.inspect(ctx, candidate);
                        ctx.debug.target("nearest ship-wood candidate", candidate,
                                sight.verdict() + "; visible cluster did not pass ship test");
                        ctx.debug.decide("wood exists here, but it is not actionable shipwreck evidence yet");
                    } else {
                        ctx.debug.target("ship-wood candidate", null, "none in loaded scan cube");
                        ctx.debug.decide("no loaded ship-wood candidate; continue bounded shoreline roam");
                    }
                    status = "no visible shipwreck-like structure";
                    return TaskStatus.FAILED;
                }
            }
            if (target == null) {
                return TaskStatus.FAILED;
            }
            if (approach == null) {
                approach = new GotoTask(new Goals.Near(target, 6), true, false, true);
                approach.start(ctx);
            }
            TaskStatus result = approach.tick(ctx);
            if (result == TaskStatus.SUCCESS) {
                approach.stop(ctx);
                approach = null;
                status = "near visible shipwreck; checking its chest";
                return TaskStatus.SUCCESS;
            }
            if (result == TaskStatus.FAILED) {
                approach.stop(ctx);
                approach = null;
                status = "could not reach visible shipwreck";
                return TaskStatus.FAILED;
            }
            status = "walking to visible shipwreck";
            ctx.debug.intent = "approaching remembered visible shipwreck wood";
            ctx.debug.giveUp = "shared route recovery; then visible chest scan";
            return TaskStatus.RUNNING;
        }

        @Override
        public void onStop(BotContext ctx) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
            ctx.input.reset();
        }

        private static boolean isVisibleShipCandidate(BotContext ctx, BlockPos pos, BlockState state) {
            // BlockScanner only searches loaded columns today, but keep the invariant here as
            // well: this prospecting heuristic must never turn a partially loaded search result
            // into knowledge about an unloaded shipwreck.
            if (!ctx.level.hasChunkAt(pos)) {
                return false;
            }
            if (!Vision.isVisible(ctx, pos) || !nearWater(ctx, pos)) {
                return false;
            }

            int visiblePlanks = 0;
            int visibleSupports = 0;
            int waterlinePlanks = 0;
            int deckFeatures = 0;
            int visibleMasonry = 0;
            BlockPos.MutableBlockPos nearby = new BlockPos.MutableBlockPos();
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        nearby.setWithOffset(pos, dx, dy, dz);
                        if (!ctx.level.hasChunkAt(nearby)) {
                            continue;
                        }
                        BlockState nearbyState = ctx.level.getBlockState(nearby);
                        if (!Vision.isReachable(ctx, nearby)) {
                            continue;
                        }
                        if (FoodTask.SHIP_WOOD.contains(nearbyState.getBlock())
                                && nearbyState.is(BlockTags.PLANKS)) {
                            visiblePlanks++;
                            if (isWaterAdjacent(ctx, nearby)) {
                                waterlinePlanks++;
                            }
                        } else if (FoodTask.SHIP_WOOD.contains(nearbyState.getBlock())
                                && (nearbyState.is(BlockTags.LOGS)
                                || nearbyState.is(BlockTags.STAIRS)
                                || nearbyState.is(BlockTags.SLABS)
                                || nearbyState.is(BlockTags.FENCES))) {
                            visibleSupports++;
                            if (nearbyState.is(BlockTags.STAIRS)
                                    || nearbyState.is(BlockTags.SLABS)
                                    || nearbyState.is(BlockTags.FENCES)) {
                                deckFeatures++;
                            }
                        } else if (isVillageMasonry(nearbyState)) {
                            visibleMasonry++;
                        }
                    }
                }
            }
            return FoodSearchPolicy.looksLikeVisibleShipwreck(visiblePlanks, visibleSupports,
                    waterlinePlanks, deckFeatures, visibleMasonry);
        }

        private static boolean isWaterAdjacent(BotContext ctx, BlockPos pos) {
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = pos.relative(direction);
                if (ctx.level.hasChunkAt(adjacent)
                        && ctx.level.getBlockState(adjacent).getFluidState().is(FluidTags.WATER)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isVillageMasonry(BlockState state) {
            return state.is(Blocks.COBBLESTONE)
                    || state.is(Blocks.MOSSY_COBBLESTONE)
                    || state.is(BlockTags.WALLS);
        }

        private static boolean nearWater(BotContext ctx, BlockPos pos) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -6; dz <= 6; dz++) {
                        BlockPos nearby = pos.offset(dx, dy, dz);
                        if (!ctx.level.hasChunkAt(nearby)) {
                            continue;
                        }
                        if (ctx.level.getBlockState(nearby).getFluidState().is(FluidTags.WATER)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    /**
     * Enters a small number of doors that are actually visible, then repeats the chest search from
     * inside. Village chests are often behind a wall and therefore invisible from the road; a
     * player who has recognised a house would open its door rather than conclude that the village
     * has no supplies. The door list is bounded and every failed entrance is remembered for this
     * food pass, so this cannot turn into an omniscient house sweep.
     */
    private static final class VillageEntryTask implements Task {
        private static final int MAX_ENTRIES = 6;
        private static final int INSIDE_RADIUS = 10;

        private final int radius;
        private final Set<Long> unavailableContainers;
        private final Set<Long> unavailableDoors = new java.util.HashSet<>();
        private BlockPos door;
        private GotoTask approach;
        private GotoTask inside;
        private FoodContainerTask loot;
        private int entries;
        private String status = "";

        VillageEntryTask(int radius, Set<Long> unavailableContainers) {
            this.radius = Math.max(1, radius);
            this.unavailableContainers = unavailableContainers;
        }

        @Override
        public String name() {
            return "Check Visible Village Houses";
        }

        @Override
        public String status() {
            return status;
        }

        @Override
        public void onStart(BotContext ctx) {
            door = null;
            approach = null;
            inside = null;
            loot = null;
            entries = 0;
            unavailableDoors.clear();
            status = "checking visible village entrances";
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            if (loot != null) {
                TaskStatus result = loot.tick(ctx);
                status = "inside a visible village house - " + loot.status();
                if (result == TaskStatus.RUNNING) {
                    return TaskStatus.RUNNING;
                }
                loot.stop(ctx);
                loot = null;
                if (result == TaskStatus.SUCCESS) {
                    return TaskStatus.SUCCESS;
                }
                blacklistDoor(ctx);
                return TaskStatus.RUNNING;
            }

            if (inside != null) {
                TaskStatus result = inside.tick(ctx);
                if (result == TaskStatus.RUNNING) {
                    status = "stepping through a visible village doorway";
                    return TaskStatus.RUNNING;
                }
                inside.stop(ctx);
                inside = null;
                if (result == TaskStatus.SUCCESS) {
                    loot = new FoodContainerTask(INSIDE_RADIUS, unavailableContainers);
                    loot.start(ctx);
                    status = "inside a visible village house; checking its chest";
                    return TaskStatus.RUNNING;
                }
                status = "could not enter the visible village house";
                blacklistDoor(ctx);
                return TaskStatus.RUNNING;
            }

            if (door == null) {
                if (entries >= MAX_ENTRIES) {
                    status = "no food in the visible village houses checked";
                    return TaskStatus.FAILED;
                }
                door = BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                        FoodTask.VILLAGE_DOORS, radius, ctx.level.getMinY(), ctx.level.getMaxY(),
                        unavailableDoors, (pos, state) -> Vision.isVisible(ctx, pos));
                if (door == null) {
                    status = "no visible village entrance";
                    return TaskStatus.FAILED;
                }
                entries++;
            }

            if (approach == null) {
                approach = new GotoTask(new Goals.Adjacent(door, 2.5), false, true, true);
                approach.start(ctx);
            }
            TaskStatus walk = approach.tick(ctx);
            if (walk == TaskStatus.RUNNING) {
                status = "walking to a visible village doorway";
                return TaskStatus.RUNNING;
            }
            approach.stop(ctx);
            approach = null;
            if (walk == TaskStatus.FAILED) {
                status = "could not reach a visible village doorway";
                blacklistDoor(ctx);
                return TaskStatus.RUNNING;
            }

            if (!inReach(ctx, door)) {
                status = "arrived near a visible doorway; adjusting position";
                return TaskStatus.RUNNING;
            }
            if (!BlockPlacer.use(ctx, door)) {
                status = "aiming at a visible village doorway";
                return TaskStatus.RUNNING;
            }

            BlockPos insideTarget = insideTarget(ctx, door);
            inside = new GotoTask(new Goals.Near(insideTarget, 1), true, false, true);
            inside.start(ctx);
            status = "opening a visible village doorway";
            return TaskStatus.RUNNING;
        }

        private void blacklistDoor(BotContext ctx) {
            if (door != null) {
                unavailableDoors.add(door.asLong());
            }
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
            if (inside != null) {
                inside.stop(ctx);
                inside = null;
            }
            door = null;
        }

        private static BlockPos insideTarget(BotContext ctx, BlockPos door) {
            BlockState state = ctx.level.getBlockState(door);
            Direction facing = state.hasProperty(DoorBlock.FACING)
                    ? state.getValue(DoorBlock.FACING) : Direction.NORTH;
            BlockPos first = door.relative(facing);
            BlockPos second = door.relative(facing.getOpposite());
            if (isWalkableSpace(ctx, first)) {
                return first;
            }
            return isWalkableSpace(ctx, second) ? second : door;
        }

        private static boolean isWalkableSpace(BotContext ctx, BlockPos feet) {
            return MovementHelper.isPassable(ctx.level, feet)
                    && MovementHelper.isPassable(ctx.level, feet.above())
                    && MovementHelper.isSolidFloor(ctx.level, feet.below());
        }

        private static boolean inReach(BotContext ctx, BlockPos pos) {
            return ctx.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= 20.0;
        }

        @Override
        public void onStop(BotContext ctx) {
            if (loot != null) {
                loot.stop(ctx);
                loot = null;
            }
            if (inside != null) {
                inside.stop(ctx);
                inside = null;
            }
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
            ctx.input.reset();
        }
    }

    /**
     * Opens visible village/shipwreck containers and takes only food or food ingredients.
     * <p>
     * A chest is deliberately found through the loaded block view and {@link Vision}; this is a
     * useful approximation of what a player can notice while travelling, whereas a structure
     * locator would know about buried or unloaded chests. Empty or irrelevant containers are
     * blacklisted for this food pass so the task never reopens the same chest forever.
     */
    /**
     * Walks to a structure the scout spotted and takes what it came for.
     *
     * <p>The four opportunities differ in what they look like from a distance and not much in what
     * you do once you arrive: empty the chests, and pick up any ore the structure has already
     * exposed. So this is one task rather than four, with a fixed budget - an opportunity is a
     * shortcut, and a shortcut that turns into a project is worse than not taking it.</p>
     */
    private static final class OpportunityTask implements Task {
        /** How far from the marker the site's own chests and ore are worth looking for. */
        private static final int SITE_RADIUS = 24;
        /** Enough for a shipwreck's two holds or a smithy plus a neighbour, and no more. */
        private static final int MAX_CONTAINERS = 4;
        /** Total budget. A site that has not paid out by now is costing more than it is worth. */
        private static final int BUDGET_TICKS = 2400;

        /** Hostiles this close, and in view, are dealt with before anything else at the site. */
        private static final int THREAT_RADIUS = 12;
        /** Leave rather than trade hits at half health; the run matters more than the chest. */
        private static final float ABANDON_HEALTH = 10.0F;
        /** How often the walk in stops to check whether a chest has come into view. */
        private static final int SIGHT_CHECK_INTERVAL = 10;

        private final SpeedrunOpportunity kind;
        private final BlockPos site;
        private final Set<Long> containers;
        private final Set<Integer> givenUpThreats = new java.util.HashSet<>();

        private GotoTask approach;
        private Task work;
        private boolean arrived;
        private boolean containersDone;
        private boolean oreDone;
        private boolean fighting;
        private int lootedContainers;
        private int ticks;
        private String status = "";

        OpportunityTask(SpeedrunOpportunity kind, BlockPos site, Set<Long> containers) {
            this.kind = kind;
            this.site = site;
            this.containers = containers;
        }

        @Override
        public String name() {
            return "Work " + kind.label();
        }

        @Override
        public String status() {
            return status;
        }

        @Override
        public void onStart(BotContext ctx) {
            ctx.debug.intent = "working the " + kind.label() + " at " + site.toShortString();
            status = "heading for the " + kind.label();
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            ctx.debug.giveUp = "site budget " + ticks + "/" + BUDGET_TICKS + " ticks, chests "
                    + lootedContainers + "/" + MAX_CONTAINERS;
            if (ticks++ >= BUDGET_TICKS) {
                stopWork(ctx);
                status = "spent long enough at the " + kind.label();
                // Never a failure. This is opportunistic work; the phase behind it still has its
                // own route to iron and must not be failed by a chest that would not open.
                return TaskStatus.SUCCESS;
            }

            // A pillager outpost is guarded, and standing in one turning your head is how a run
            // ends. Deal with what is shooting at you before going back to looking at scenery, and
            // walk away entirely once the fight stops being worth a chest.
            if (ctx.player.getHealth() <= ABANDON_HEALTH) {
                stopWork(ctx);
                stopApproach(ctx);
                status = "leaving the " + kind.label() + " - took too much damage";
                ctx.debug.decide("abandon site: health " + (int) ctx.player.getHealth());
                return TaskStatus.SUCCESS;
            }
            if (fighting || threatened(ctx)) {
                return tickFight(ctx);
            }

            // A loot started on the way in has to finish before the walk resumes, or the route
            // rebuilds and carries the bot back past the open chest.
            if (work instanceof FoodContainerTask) {
                return tickContainers(ctx);
            }

            if (!arrived) {
                return tickApproach(ctx);
            }
            if (!containersDone) {
                return tickContainers(ctx);
            }
            if (!oreDone) {
                return tickOre(ctx);
            }

            status = "took " + lootedContainers + " chest" + (lootedContainers == 1 ? "" : "s")
                    + " from the " + kind.label();
            return TaskStatus.SUCCESS;
        }

        /**
         * Hostile mob types close enough to be a problem right now. Proximity only - KillTask does
         * the reachability work, and a pillager behind a wall is still one that shoots through the
         * window a second later.
         */
        private Set<EntityType<?>> threats(BotContext ctx) {
            Set<EntityType<?>> types = new java.util.HashSet<>();
            AABB box = ctx.player.getBoundingBox().inflate(THREAT_RADIUS);
            for (Entity entity : ctx.level.getEntities(ctx.player, box)) {
                if (entity instanceof Monster monster && monster.isAlive()
                        && !givenUpThreats.contains(entity.getId())
                        // KillTask uses the same visibility rule when it selects a mob. Do not
                        // interrupt a long approach for a monster hidden behind the portal wall:
                        // that creates a finished KillTask every tick while the player stands
                        // still, unable to act on the threat.
                        && Vision.isEntityVisible(ctx, monster)) {
                    types.add(monster.getType());
                }
            }
            return types;
        }

        private boolean threatened(BotContext ctx) {
            return !threats(ctx).isEmpty();
        }

        private TaskStatus tickFight(BotContext ctx) {
            if (work != null && !(work instanceof KillTask)) {
                stopWork(ctx);
            }
            if (work == null) {
                Set<EntityType<?>> targets = threats(ctx);
                if (targets.isEmpty()) {
                    fighting = false;
                    return TaskStatus.RUNNING;
                }
                stopApproach(ctx);
                fighting = true;
                work = new KillTask(targets, THREAT_RADIUS,
                        new KillOptions(false, true, false, KillOptions.EndermanSafety.DIRECT,
                                KillOptions.WeaponPreference.SWORD, false), givenUpThreats);
                work.start(ctx);
                ctx.debug.decide("clearing " + targets.size() + " hostile type(s) at the "
                        + kind.label() + " before looting");
            }
            TaskStatus result = work.tick(ctx);
            status = "fighting at the " + kind.label() + " - " + work.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            stopWork(ctx);
            fighting = false;
            return TaskStatus.RUNNING;
        }

        private TaskStatus tickApproach(BotContext ctx) {
            // Loot what the walk goes past. An outpost's chest is on the top floor and a shipwreck's
            // is in the hold, so "arrive, then look around" finds neither - the bot walks straight
            // past the thing it came for and then reports there was nothing visible from the door.
            if (!containersDone && ticks % SIGHT_CHECK_INTERVAL == 0
                    && FoodContainerTask.findVisible(ctx, SITE_RADIUS, containers) != null) {
                stopApproach(ctx);
                return tickContainers(ctx);
            }
            if (ctx.player.blockPosition().distSqr(site) <= 64.0) {
                arrived = true;
                stopApproach(ctx);
                return TaskStatus.RUNNING;
            }
            if (approach == null) {
                // Break and swim are both on: a mineshaft opening may need a blocked ledge cleared
                // and a shipwreck's hold is on the seabed.
                approach = new GotoTask(new Goals.Near(site, 4), true, true, true);
                approach.start(ctx);
            }
            TaskStatus result = approach.tick(ctx);
            status = "walking to the " + kind.label() + " - " + approach.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            stopApproach(ctx);
            if (result == TaskStatus.FAILED) {
                status = "could not reach the " + kind.label();
                return TaskStatus.SUCCESS;
            }
            arrived = true;
            return TaskStatus.RUNNING;
        }

        private TaskStatus tickContainers(BotContext ctx) {
            if (lootedContainers >= MAX_CONTAINERS) {
                containersDone = true;
                return TaskStatus.RUNNING;
            }
            if (work == null) {
                work = new FoodContainerTask(SITE_RADIUS, containers,
                        SpeedrunTask::isOverworldSupply, kind.label());
                work.start(ctx);
            }
            TaskStatus result = work.tick(ctx);
            status = work.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            stopWork(ctx);
            if (result == TaskStatus.SUCCESS) {
                lootedContainers++;
                return TaskStatus.RUNNING;
            }
            // No more visible containers here. That is the normal way this finishes - but only once
            // the bot has actually got to the site. Nothing visible from halfway up the approach
            // says nothing about what is inside.
            if (arrived) {
                containersDone = true;
            }
            return TaskStatus.RUNNING;
        }

        private TaskStatus tickOre(BotContext ctx) {
            if (work == null) {
                // Visible ore only, no prospecting: the point of a mineshaft or a ravine is the
                // iron already showing in the wall. Sinking a shaft here is the fallback the iron
                // phase owns, not something a detour should start.
                work = new MineTask(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), SITE_RADIUS,
                        ctx.level.getMinY(), ctx.level.getMaxY(), 8, true, false, false, true);
                work.start(ctx);
            }
            TaskStatus result = work.tick(ctx);
            status = "checking the " + kind.label() + " for exposed iron - " + work.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            stopWork(ctx);
            oreDone = true;
            return TaskStatus.RUNNING;
        }

        private void stopApproach(BotContext ctx) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
        }

        private void stopWork(BotContext ctx) {
            if (work != null) {
                work.stop(ctx);
                work = null;
            }
        }

        @Override
        public void onStop(BotContext ctx) {
            stopApproach(ctx);
            stopWork(ctx);
            ctx.input.reset();
        }
    }

    private static final class FoodContainerTask implements Task {
        private static final Set<Block> CONTAINERS = Set.of(
                Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);
        private static final int ACTION_COOLDOWN = 5;
        private static final int MAX_OPEN_ATTEMPTS = 6;

        private final int radius;
        private final Set<Long> unavailable;
        private final Predicate<ItemStack> supplyPredicate;
        /** What this pass is after, so the HUD does not call a ruined portal cache a food chest. */
        private final String label;
        private BlockPos target;
        private GotoTask approach;
        private int cooldown;
        private int openAttempts;
        private int moved;
        private String status = "";

        FoodContainerTask(int radius, Set<Long> unavailable) {
            this(radius, unavailable, FoodContainerTask::isFoodSupply, "food");
        }

        FoodContainerTask(int radius, Set<Long> unavailable, Predicate<ItemStack> supplyPredicate) {
            this(radius, unavailable, supplyPredicate, "supply");
        }

        FoodContainerTask(int radius, Set<Long> unavailable, Predicate<ItemStack> supplyPredicate,
                          String label) {
            this.radius = Math.max(1, radius);
            this.unavailable = unavailable;
            this.supplyPredicate = supplyPredicate;
            this.label = label;
        }

        @Override
        public String name() {
            return "Loot " + label + " container";
        }

        @Override
        public String status() {
            return status;
        }

        @Override
        public void onStart(BotContext ctx) {
            target = null;
            approach = null;
            cooldown = 0;
            openAttempts = 0;
            moved = 0;
            status = "searching visible " + label + " containers";
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            if (target != null && !CONTAINERS.contains(ctx.level.getBlockState(target).getBlock())) {
                unavailable.add(target.asLong());
                target = null;
                stopApproach(ctx);
            }

            if (target == null) {
                target = findVisible(ctx, radius, unavailable);
                if (target == null) {
                    status = "no visible " + label + " chest";
                    return TaskStatus.FAILED;
                }
            }

            if (cooldown > 0) {
                cooldown--;
                return TaskStatus.RUNNING;
            }

            if (!isContainerOpen(ctx)) {
                return openContainer(ctx);
            }

            AbstractContainerMenu menu = ctx.player.containerMenu;
            for (Slot slot : menu.slots) {
                if (slot.container == ctx.player.getInventory()) {
                    continue;
                }
                ItemStack stack = slot.getItem();
                if (!supplyPredicate.test(stack)) {
                    continue;
                }
                ctx.gameMode.handleContainerInput(menu.containerId, slot.index, 0,
                        ContainerInput.QUICK_MOVE, ctx.player);
                moved += stack.getCount();
                cooldown = ACTION_COOLDOWN;
                status = "taking " + stack.getHoverName().getString() + " from a " + label + " chest";
                return TaskStatus.RUNNING;
            }

            closeMenu(ctx);
            unavailable.add(target.asLong());
            if (moved == 0) {
                status = "visible chest had no " + label + ", checking the next source";
                return TaskStatus.FAILED;
            }
            status = "took " + moved + " " + label + " items from a visible chest";
            return TaskStatus.SUCCESS;
        }

        private TaskStatus openContainer(BotContext ctx) {
            if (openAttempts >= MAX_OPEN_ATTEMPTS) {
                unavailable.add(target.asLong());
                closeMenu(ctx);
                status = "could not open the visible " + label + " chest";
                return TaskStatus.FAILED;
            }

            if (inReach(ctx, target)) {
                if (BlockPlacer.use(ctx, target)) {
                    openAttempts++;
                    cooldown = ACTION_COOLDOWN + 2;
                    status = "opening a visible " + label + " chest";
                    return TaskStatus.RUNNING;
                }
                status = "aiming at the visible " + label + " chest";
                return TaskStatus.RUNNING;
            }

            if (approach == null) {
                // Sprinting matters here more than anywhere: a sighted shipwreck or ruined
                // portal is routinely a hundred blocks off, and half of that is swimming, where
                // sprint is the difference between a crawl and a swim.
                approach = new GotoTask(containerApproachGoal(target), true, true, true);
                approach.start(ctx);
            }
            TaskStatus walk = approach.tick(ctx);
            if (walk == TaskStatus.SUCCESS) {
                stopApproach(ctx);
                // A broad distance goal can be satisfied beside a portal frame while its
                // obsidian still blocks the chest face. Do not rebuild that already-satisfied
                // goal forever: this chest is deterministic from this side, so skip it and let
                // the visible-container sweep try another chest or return to the ore phase.
                if (!inReach(ctx, target)) {
                    unavailable.add(target.asLong());
                    target = null;
                    openAttempts = 0;
                    status = "arrived beside a blocked " + label + " chest; checking the next source";
                    return TaskStatus.RUNNING;
                }
                status = "arrived at a visible " + label + " chest";
                return TaskStatus.RUNNING;
            }
            if (walk == TaskStatus.FAILED) {
                stopApproach(ctx);
                unavailable.add(target.asLong());
                status = "could not reach the visible " + label + " chest";
                return TaskStatus.FAILED;
            }
            status = "walking to a visible " + label + " chest";
            return TaskStatus.RUNNING;
        }

        private void stopApproach(BotContext ctx) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
        }

        /** The nearest container the player can actually see, or null. Shared with the site walk. */
        static BlockPos findVisible(BotContext ctx, int radius, Set<Long> unavailable) {
            return BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(), CONTAINERS,
                    radius, ctx.level.getMinY(), ctx.level.getMaxY(), unavailable,
                    (pos, state) -> Vision.isVisible(ctx, pos));
        }

        private static boolean isContainerOpen(BotContext ctx) {
            return ctx.player.containerMenu != null
                    && ctx.player.containerMenu != ctx.player.inventoryMenu;
        }

        private static boolean inReach(BotContext ctx, BlockPos pos) {
            return ctx.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= 20.0
                    && BlockPlacer.hasLineOfSight(ctx, pos);
        }

        /** Stand on an actual side of the chest, rather than satisfying a radius in front of a frame. */
        private static Goals.Any containerApproachGoal(BlockPos target) {
            return new Goals.Any(List.of(
                    new Goals.Block(target.north()),
                    new Goals.Block(target.south()),
                    new Goals.Block(target.east()),
                    new Goals.Block(target.west()),
                    new Goals.Block(target.north().east()),
                    new Goals.Block(target.north().west()),
                    new Goals.Block(target.south().east()),
                    new Goals.Block(target.south().west())));
        }

        private static boolean isFoodSupply(ItemStack stack) {
            return !stack.isEmpty()
                    && (stack.has(DataComponents.FOOD)
                    || stack.is(Items.WHEAT)
                    || stack.is(Items.HAY_BLOCK));
        }

        private static void closeMenu(BotContext ctx) {
            if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
                ctx.player.closeContainer();
                ctx.mc.setScreen(null);
            }
        }

        @Override
        public void onStop(BotContext ctx) {
            stopApproach(ctx);
            closeMenu(ctx);
            ctx.input.reset();
        }
    }

    /** Walks into a portal and completes when the requested dimension is active. */
    private static final class PortalTravelTask implements Task {
        private static final int TIMEOUT = 900;

        private final ResourceKey<Level> dimension;
        private final BlockPos anchor;
        private final int searchRadius;
        private BlockPos portal;
        private GotoTask approach;
        private int ticks;
        private String status = "";

        PortalTravelTask(ResourceKey<Level> dimension, BlockPos anchor, int searchRadius) {
            this.dimension = dimension;
            this.anchor = anchor;
            this.searchRadius = searchRadius;
        }

        @Override
        public String name() { return "Use Nether Portal"; }

        @Override
        public String status() { return status; }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            if (ctx.level.dimension() == dimension) {
                status = "arrived in the target dimension";
                return TaskStatus.SUCCESS;
            }

            if (portal == null || !ctx.level.getBlockState(portal).is(Blocks.NETHER_PORTAL)) {
                portal = anchor != null && ctx.level.getBlockState(anchor).is(Blocks.NETHER_PORTAL)
                        ? anchor : findPortal(ctx, ctx.player.blockPosition(), searchRadius);
                if (portal == null) {
                    status = "no Nether portal in the loaded area";
                    return TaskStatus.FAILED;
                }
            }

            if (approach == null) {
                approach = new GotoTask(new Goals.Near(portal, 2), true, false);
                approach.start(ctx);
            }
            TaskStatus walk = approach.tick(ctx);
            if (walk == TaskStatus.RUNNING) {
                status = "walking to portal";
                return TaskStatus.RUNNING;
            }
            if (walk == TaskStatus.FAILED) {
                status = "could not reach portal";
                return TaskStatus.FAILED;
            }
            approach.stop(ctx);
            approach = null;

            ctx.look.lookAt(ctx.player, Vec3.atCenterOf(portal));
            ctx.input.forward = true;
            ctx.input.sprint = true;
            if (++ticks > TIMEOUT) {
                status = "portal did not change dimension";
                return TaskStatus.FAILED;
            }
            status = "entering portal";
            return TaskStatus.RUNNING;
        }

        @Override
        public void onStop(BotContext ctx) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
            ctx.input.reset();
        }
    }

    /**
     * Casts a ten-obsidian portal from visible source fluids with two buckets. This is intentionally
     * conservative: if the water or lava source cannot be seen and reached, the task stops instead
     * of treating loaded terrain as an oracle.
     */
    private static final class LavaCastPortalTask implements Task {

        /** A pool noticed earlier in the run, so this does not start its search from nothing. */
        private final BlockPos rememberedLava;

        LavaCastPortalTask(BlockPos rememberedLava) {
            this.rememberedLava = rememberedLava;
        }

        private enum State { FIND_PORTAL, CHOOSE_SOURCES, CHOOSE_BASE, CAST, LIGHT, ENTER }

        /**
         * A lava pool is rarer and less uniformly distributed than shoreline water. Give the
         * physical scout enough stops to examine a complete set of committed headings, while
         * keeping the search bounded so a bad seed cannot turn into an endless wander.
         */
        private static final int FLUID_SCOUT_RADIUS = 192;
        private static final int MAX_FLUID_SCOUT_STOPS = 24;
        private static final int FLUID_SCOUT_STEP = 24;

        private State state = State.FIND_PORTAL;
        private BlockPos portal;
        private BlockPos waterSource;
        private BlockPos lavaSource;
        private BlockPos base;
        private GotoTask approach;
        /** A bounded physical search for a fluid source outside the current view. */
        private ExploreTask fluidScout;
        private Block fluidScoutTarget;
        private int frameIndex;
        private int frameStep;
        private int lightTicks;
        private String buildStrategy = PortalBuildingPolicy.DEFAULT;
        private List<BlockPos> frameOrder = List.of();
        /** The current cast must finish even if feedback reorders the remaining frame. */
        private BlockPos frameTarget;
        private String status = "";

        @Override
        public String name() { return "Cast Nether Portal"; }

        @Override
        public String status() { return status; }

        @Override
        public TaskProgress learningProgress() {
            return new TaskProgress(frameIndex, 14, "frame blocks");
        }

        @Override
        public LearningContext learningContext(BotContext ctx) {
            return new LearningContext("skill", "lava-cast-portal",
                    ctx.level.dimension().identifier().toString(), "frame=complete-fourteen");
        }

        @Override
        public List<String> learningActions(BotContext ctx) {
            return PortalBuildingPolicy.ACTIONS;
        }

        @Override
        public void onLearningAction(BotContext ctx, String action) {
            buildStrategy = PortalBuildingPolicy.ACTIONS.contains(action)
                    ? action : PortalBuildingPolicy.DEFAULT;
            if (base != null) {
                frameOrder = PortalBuildingPolicy.order(base, true,
                        buildStrategy, ctx.player.getX());
            }
        }

        @Override
        public void onStart(BotContext ctx) {
            state = State.FIND_PORTAL;
            portal = null;
            waterSource = null;
            lavaSource = null;
            base = null;
            frameIndex = 0;
            frameStep = 0;
            lightTicks = 0;
            buildStrategy = PortalBuildingPolicy.DEFAULT;
            frameOrder = List.of();
            frameTarget = null;
            fluidScout = null;
            fluidScoutTarget = null;
            stopApproach(ctx);
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            ctx.debug.intent = "portal phase: " + state.name().toLowerCase();
            ctx.debug.memory = "water source " + position(waterSource)
                    + ", lava source " + position(lavaSource)
                    + ", base " + position(base);
            ctx.debug.giveUp = "fluid scout " + (fluidScout == null ? "not started" : "active")
                    + "; frame " + frameIndex + "/14 step " + frameStep
                    + "; max " + MAX_FLUID_SCOUT_STOPS + " committed stops";
            if (ctx.level.dimension() == Level.NETHER) {
                status = "entered the Nether";
                return TaskStatus.SUCCESS;
            }

            BlockPos foundPortal = findPortal(ctx, ctx.player.blockPosition(), 48);
            if (foundPortal != null) {
                portal = foundPortal;
                state = State.ENTER;
            }

            return switch (state) {
                case FIND_PORTAL -> findSources(ctx);
                case CHOOSE_SOURCES -> chooseBase(ctx);
                case CHOOSE_BASE -> cast(ctx);
                case CAST -> cast(ctx);
                case LIGHT -> light(ctx);
                case ENTER -> enter(ctx);
            };
        }

        @Override
        public void onStop(BotContext ctx) {
            if (fluidScout != null) {
                fluidScout.stop(ctx);
                fluidScout = null;
                fluidScoutTarget = null;
            }
            stopApproach(ctx);
            ctx.input.reset();
        }

        private TaskStatus findSources(BotContext ctx) {
            if (fluidScout != null) {
                TaskStatus scouted = fluidScout.tick(ctx);
                status = "scouting for a visible " + fluidName(fluidScoutTarget)
                        + " source - " + fluidScout.status();
                ctx.debug.nextDecision = "finish this bounded physical scout, then report its result";
                if (scouted == TaskStatus.RUNNING) {
                    return TaskStatus.RUNNING;
                }

                BlockPos found = fluidScout.getFound();
                fluidScout.stop(ctx);
                fluidScout = null;
                Block target = fluidScoutTarget;
                fluidScoutTarget = null;
                if (scouted == TaskStatus.SUCCESS && found != null) {
                    if (target == Blocks.LAVA) {
                        lavaSource = found;
                    } else {
                        waterSource = found;
                    }
                    status = "spotted a visible " + fluidName(target) + " source";
                    ctx.debug.target("visible " + fluidName(target) + " source", found,
                            Vision.inspect(ctx, found).verdict());
                    ctx.debug.decide("remember source at " + found.toShortString()
                            + "; continue to the other fluid");
                    return TaskStatus.RUNNING;
                }

                status = "could not find a visible reachable " + fluidName(target) + " source";
                ctx.debug.decide("give up: " + fluidName(target)
                        + " not found within " + MAX_FLUID_SCOUT_STOPS + " physical stops");
                return TaskStatus.FAILED;
            }

            if (waterSource == null) {
                waterSource = findSource(ctx, Blocks.WATER, ctx.player.blockPosition(), 64);
            }
            if (lavaSource == null) {
                lavaSource = findSource(ctx, Blocks.LAVA, ctx.player.blockPosition(), 64);
            }
            if (lavaSource == null && rememberedLava != null
                    && ctx.level.getBlockState(rememberedLava).getFluidState().isSource()) {
                // Seen earlier in the run. Still checked against the live world, so a pool that has
                // since been covered or drained does not send the route somewhere pointless.
                lavaSource = rememberedLava;
                ctx.debug.decide("using the lava pool noticed earlier at " + rememberedLava.toShortString());
            }
            if (waterSource == null || lavaSource == null) {
                // Water is normally already visible from a shoreline. Lava is the scarce source,
                // so search for it first; if the shoreline is hidden, the same bounded scout can
                // reconnect with a visible water source afterward. This moves the player and
                // turns while searching instead of widening a block scan into an X-ray.
                fluidScoutTarget = lavaSource == null ? Blocks.LAVA : Blocks.WATER;
                fluidScout = new ExploreTask(Set.of(fluidScoutTarget), FLUID_SCOUT_RADIUS,
                        MAX_FLUID_SCOUT_STOPS, FLUID_SCOUT_STEP, true,
                        HeadScanner.Style.GLANCE, false,
                        state -> state.getFluidState().isSource());
                fluidScout.start(ctx);
                status = "scouting for a visible " + fluidName(fluidScoutTarget) + " source";
                ctx.debug.nextDecision = "walk and turn physically; do not inspect hidden chunks";
                return TaskStatus.RUNNING;
            }
            state = State.CHOOSE_SOURCES;
            status = "found water and lava sources";
            ctx.debug.decide("both fluid sources remembered; choose a flat portal base");
            return TaskStatus.RUNNING;
        }

        private String position(BlockPos pos) {
            return pos == null ? "none" : pos.toShortString();
        }

        private String fluidName(Block block) {
            return block == Blocks.LAVA ? "lava" : "water";
        }

        private TaskStatus chooseBase(BotContext ctx) {
            base = NetherPortalFrame.findBase(ctx, true, false);
            if (base == null) {
                status = "no flat space for a portal frame";
                return TaskStatus.FAILED;
            }
            frameOrder = PortalBuildingPolicy.order(base, true,
                    buildStrategy, ctx.player.getX());
            state = State.CAST;
            status = "casting a portal frame";
            return TaskStatus.RUNNING;
        }

        private TaskStatus cast(BotContext ctx) {
            // A normal lava pool contains several source blocks. Once one is bucketed it may no
            // longer be a source, and the next source can be outside the current ray from the
            // frame. Walk and look again instead of treating that physical gap as a failed portal.
            if (fluidScout != null) {
                return tickCastSourceScout(ctx);
            }

            List<BlockPos> frame = frameOrder.isEmpty()
                    ? NetherPortalFrame.positions(base, true) : frameOrder;
            frameIndex = (int) frame.stream()
                    .filter(pos -> ctx.level.getBlockState(pos).is(Blocks.OBSIDIAN))
                    .count();
            if (frameIndex >= frame.size()) {
                frameTarget = null;
                state = State.LIGHT;
                return TaskStatus.RUNNING;
            }

            if (frameTarget != null && ctx.level.getBlockState(frameTarget).is(Blocks.OBSIDIAN)) {
                frameTarget = null;
                frameStep = 0;
                stopApproach(ctx);
            }
            if (frameTarget == null) {
                frameTarget = frame.stream()
                        .filter(pos -> !ctx.level.getBlockState(pos).is(Blocks.OBSIDIAN))
                        .findFirst()
                        .orElse(null);
            }
            if (frameTarget == null) {
                state = State.LIGHT;
                return TaskStatus.RUNNING;
            }

            BlockPos target = frameTarget;
            ctx.debug.target("portal frame " + (frameIndex + 1) + "/" + frame.size(), target,
                    "state " + targetStateName(ctx, target) + ", step " + frameStep);
            BlockState targetState = ctx.level.getBlockState(target);
            if (targetState.is(Blocks.OBSIDIAN)) {
                frameIndex = Math.min(frame.size(), frameIndex + 1);
                frameTarget = null;
                frameStep = 0;
                return TaskStatus.RUNNING;
            }
            if (!BlockPlacer.isReplaceable(ctx, target)) {
                status = "portal frame is blocked at " + target;
                return TaskStatus.FAILED;
            }

            if (frameStep == 0) {
                // The scout already saw the lava pool, then the bot walked to the frame. Requiring
                // the source to remain inside the current head direction made the first frame
                // block fail even though the pool was still line-of-sight reachable by turning.
                lavaSource = sourceAfterTravel(ctx, Blocks.LAVA, lavaSource);
                if (lavaSource == null) {
                    startCastSourceScout(ctx, Blocks.LAVA);
                    return TaskStatus.RUNNING;
                }
                TaskStatus moved = moveTo(ctx, lavaSource, 3.5, "getting lava");
                if (moved != TaskStatus.SUCCESS) {
                    return moved;
                }
                if (!InventoryHelper.anyMatch(ctx.player, stack -> stack.is(Items.LAVA_BUCKET))
                        && !takeSource(ctx, lavaSource, Items.LAVA_BUCKET)) {
                    status = "aiming at the lava source";
                    return TaskStatus.RUNNING;
                }
                frameStep = 1;
                stopApproach(ctx);
                return TaskStatus.RUNNING;
            }

            if (frameStep == 1) {
                TaskStatus moved = moveTo(ctx, target, 3.5, "placing lava");
                if (moved != TaskStatus.SUCCESS) {
                    return moved;
                }
                if (!ctx.level.getBlockState(target).is(Blocks.LAVA)) {
                    if (!InventoryHelper.anyMatch(ctx.player, stack -> stack.is(Items.LAVA_BUCKET))) {
                        status = "waiting for the lava bucket";
                        return TaskStatus.RUNNING;
                    }
                    if (!placeFluid(ctx, Items.LAVA_BUCKET, target)) {
                        status = "aiming at the portal frame";
                        return TaskStatus.RUNNING;
                    }
                    if (!ctx.level.getBlockState(target).is(Blocks.LAVA)) {
                        status = "waiting for the lava source to place";
                        return TaskStatus.RUNNING;
                    }
                }
                frameStep = 2;
                stopApproach(ctx);
                return TaskStatus.RUNNING;
            }

            if (frameStep == 2) {
                waterSource = sourceAfterTravel(ctx, Blocks.WATER, waterSource);
                if (waterSource == null) {
                    startCastSourceScout(ctx, Blocks.WATER);
                    return TaskStatus.RUNNING;
                }
                TaskStatus moved = moveTo(ctx, waterSource, 3.5, "getting water");
                if (moved != TaskStatus.SUCCESS) {
                    return moved;
                }
                if (!InventoryHelper.anyMatch(ctx.player, stack -> stack.is(Items.WATER_BUCKET))
                        && !takeSource(ctx, waterSource, Items.WATER_BUCKET)) {
                    status = "aiming at the water source";
                    return TaskStatus.RUNNING;
                }
                frameStep = 3;
                stopApproach(ctx);
                return TaskStatus.RUNNING;
            }

            TaskStatus moved = moveTo(ctx, target, 3.5, "cooling the frame");
            if (moved != TaskStatus.SUCCESS) {
                return moved;
            }
            if (!ctx.level.getBlockState(target).is(Blocks.OBSIDIAN)) {
                if (!InventoryHelper.anyMatch(ctx.player, stack -> stack.is(Items.WATER_BUCKET))) {
                    status = "waiting for the water bucket";
                    return TaskStatus.RUNNING;
                }
                if (!coolLava(ctx, target)) {
                    status = "aiming water at the lava";
                    return TaskStatus.RUNNING;
                }
            }
            if (ctx.level.getBlockState(target).is(Blocks.OBSIDIAN)) {
                frameIndex = Math.min(frame.size(), frameIndex + 1);
                frameTarget = null;
                frameStep = 0;
                stopApproach(ctx);
            }
            status = "cast " + frameIndex + "/14 obsidian blocks";
            return TaskStatus.RUNNING;
        }

        /** Starts a bounded, visible-only walk when the remembered source is no longer usable. */
        private void startCastSourceScout(BotContext ctx, Block block) {
            fluidScoutTarget = block;
            fluidScout = new ExploreTask(Set.of(block), FLUID_SCOUT_RADIUS,
                    MAX_FLUID_SCOUT_STOPS, FLUID_SCOUT_STEP, true,
                    HeadScanner.Style.GLANCE, false,
                    state -> state.getFluidState().isSource());
            fluidScout.start(ctx);
            status = "scouting for another visible " + fluidName(block) + " source";
            ctx.debug.nextDecision = "walk and turn for the next source before casting this block";
            ctx.debug.decide("remembered source depleted; start a bounded physical fluid scout");
        }

        /** Ticks the source recovery without mixing it with the frame-placement step counter. */
        private TaskStatus tickCastSourceScout(BotContext ctx) {
            Block target = fluidScoutTarget;
            TaskStatus result = fluidScout.tick(ctx);
            status = "scouting for another visible " + fluidName(target)
                    + " source - " + fluidScout.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }

            BlockPos found = fluidScout.getFound();
            fluidScout.stop(ctx);
            fluidScout = null;
            fluidScoutTarget = null;
            if (result == TaskStatus.SUCCESS && found != null) {
                if (target == Blocks.LAVA) {
                    lavaSource = found;
                } else {
                    waterSource = found;
                }
                ctx.debug.target("visible " + fluidName(target) + " source", found,
                        Vision.inspect(ctx, found).verdict());
                ctx.debug.decide("found the next " + fluidName(target)
                        + " source; resume this portal frame");
                status = "found another visible " + fluidName(target) + " source";
                return TaskStatus.RUNNING;
            }

            status = "no additional visible " + fluidName(target) + " source found";
            ctx.debug.decide("give up: no next " + fluidName(target)
                    + " source within the bounded physical scout");
            return TaskStatus.FAILED;
        }

        private TaskStatus light(BotContext ctx) {
            if (portal != null || findPortal(ctx, ctx.player.blockPosition(), 48) != null) {
                portal = portal == null ? findPortal(ctx, ctx.player.blockPosition(), 48) : portal;
                state = State.ENTER;
                return TaskStatus.RUNNING;
            }
            if (++lightTicks > 80) {
                status = "could not light the completed portal frame";
                return TaskStatus.FAILED;
            }
            BlockPos support = base.offset(1, 0, 0);
            if (InventoryHelper.equip(ctx, stack -> stack.is(Items.FLINT_AND_STEEL)) < 0) {
                status = "flint and steel disappeared";
                return TaskStatus.FAILED;
            }
            Vec3 hit = Vec3.atCenterOf(support).add(0.0, 0.5, 0.0);
            ctx.look.lookAt(ctx.player, hit);
            if (ctx.look.isLookingAt(ctx.player, hit, 15.0F)) {
                ctx.gameMode.useItemOn(ctx.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(hit, Direction.UP, support, false));
                ctx.player.swing(InteractionHand.MAIN_HAND);
            }
            status = "lighting the portal";
            return TaskStatus.RUNNING;
        }

        private TaskStatus enter(BotContext ctx) {
            if (ctx.level.dimension() == Level.NETHER) {
                status = "entered the Nether";
                return TaskStatus.SUCCESS;
            }
            if (portal == null) {
                portal = findPortal(ctx, ctx.player.blockPosition(), 64);
            }
            if (portal == null) {
                status = "portal disappeared";
                return TaskStatus.FAILED;
            }
            TaskStatus moved = moveTo(ctx, portal, 2.0, "entering the portal");
            if (moved != TaskStatus.SUCCESS) {
                return moved;
            }
            ctx.look.lookAt(ctx.player, Vec3.atCenterOf(portal));
            ctx.input.forward = true;
            ctx.input.sprint = true;
            status = "entering the Nether";
            return TaskStatus.RUNNING;
        }

        private TaskStatus moveTo(BotContext ctx, BlockPos target, double tolerance, String what) {
            if (approach == null) {
                approach = new GotoTask(new Goals.Adjacent(target, tolerance), true, false);
                approach.start(ctx);
            }
            TaskStatus result = approach.tick(ctx);
            status = what;
            if (result == TaskStatus.FAILED) {
                status = "could not reach " + what;
            }
            return result;
        }

        private void stopApproach(BotContext ctx) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
        }

        private boolean takeSource(BotContext ctx, BlockPos source, net.minecraft.world.item.Item filled) {
            if (InventoryHelper.equip(ctx, stack -> stack.is(Items.BUCKET)) < 0) {
                return false;
            }
            Vec3 hit = Vec3.atCenterOf(source);
            ctx.look.lookAt(ctx.player, hit);
            if (!ctx.look.isLookingAt(ctx.player, hit, 15.0F)) {
                return false;
            }
            if (!source.equals(BucketHelper.pickupTarget(ctx))) {
                return false;
            }
            BucketHelper.use(ctx);
            return InventoryHelper.anyMatch(ctx.player, stack -> stack.is(filled));
        }

        private boolean placeFluid(BotContext ctx, net.minecraft.world.item.Item bucket, BlockPos target) {
            if (InventoryHelper.equip(ctx, stack -> stack.is(bucket)) < 0) {
                return false;
            }
            Direction supportDirection = BlockPlacer.findSupport(ctx, target);
            if (supportDirection == null) {
                return false;
            }
            BlockPos support = target.relative(supportDirection);
            Direction face = supportDirection.getOpposite();
            Vec3 hit = Vec3.atCenterOf(support).add(
                    face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            ctx.look.lookAt(ctx.player, hit);
            if (!ctx.look.isLookingAt(ctx.player, hit, 15.0F)) {
                return false;
            }
            if (!target.equals(BucketHelper.placementTarget(ctx))) {
                return false;
            }
            BucketHelper.use(ctx);
            return true;
        }

        /**
         * Water has to land <em>beside</em> the lava, not in it: emptying a bucket into a lava
         * source replaces it and leaves water, while a neighbouring water block is what turns the
         * source into obsidian. Above is the block a player picks, and it is the one the old
         * upward-facing hit result was already asking for.
         *
         * <p>It needs a solid face to click, same as any other fluid: the block above a frame
         * column whose only neighbours are air and the lava itself cannot be poured into by anyone,
         * bot or player, and this reports that rather than pretending otherwise.
         */
        private boolean coolLava(BotContext ctx, BlockPos target) {
            return placeFluid(ctx, Items.WATER_BUCKET, target.above());
        }

        private BlockPos findSource(BotContext ctx, Block block, BlockPos centre, int radius) {
            return BlockScanner.findNearest(ctx.level, centre, Set.of(block), radius,
                    ctx.level.getMinY(), ctx.level.getMaxY(), Set.of(),
                    (pos, state) -> (ctx.omniscientMining() || Vision.isVisible(ctx, pos))
                            && state.getFluidState().isSource());
        }

        /**
         * Finds a source the player could see after turning toward it. This is used after a
         * source was already discovered and the player walked to the portal frame; requiring the
         * source to remain in the current head direction would discard valid, remembered sight.
         */
        private BlockPos findReachableSource(BotContext ctx, Block block, BlockPos centre, int radius) {
            return BlockScanner.findNearest(ctx.level, centre, Set.of(block), radius,
                    ctx.level.getMinY(), ctx.level.getMaxY(), Set.of(),
                    (pos, state) -> (ctx.omniscientMining() || Vision.isReachable(ctx, pos))
                            && state.getFluidState().isSource());
        }

        /** A source found by a previous physical look remains valid route memory after walking. */
        private BlockPos sourceAfterTravel(BotContext ctx, Block block, BlockPos remembered) {
            if (remembered != null && ctx.level.getBlockState(remembered).is(block)
                    && ctx.level.getBlockState(remembered).getFluidState().isSource()) {
                ctx.debug.target("remembered " + fluidName(block) + " source", remembered,
                        "previously visible; return to it for the next cast step");
                return remembered;
            }
            return findReachableSource(ctx, block, ctx.player.blockPosition(), FLUID_SCOUT_RADIUS);
        }

        private String targetStateName(BotContext ctx, BlockPos pos) {
            return ctx.level.getBlockState(pos).getBlock().getName().getString();
        }

    }
}
