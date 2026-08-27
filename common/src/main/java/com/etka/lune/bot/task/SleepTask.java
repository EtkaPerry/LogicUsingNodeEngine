package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.catalog.FlowerCatalog;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns a flock of sheep into a bed, and the bed into a skipped night.
 *
 * <p>Sleeping is worth more to an unattended bot than the hour it saves. Night is when the hostile
 * pressure that produces most of a run's interruptions arrives, and a bed answers three problems at
 * once: the mobs never spawn, the hunger clock advances through a night the bot would otherwise
 * spend walking, and the spawn point moves to wherever the run has actually got to.</p>
 *
 * <p>Like {@code EnsureToolTask} this is a dependency resolver rather than a fixed sequence: every
 * time a step finishes it asks again what is still missing. Wool gets dyed, dye gets crafted from
 * whatever flower is in reach, and supplies consumed by one step simply reappear as the next
 * question - which matters here because the crafting grid eats the planks a bed needs to make the
 * table it is crafted on.</p>
 */
public final class SleepTask implements Task {

    /** A diversion, not an expedition: sheep and flowers must be close to be worth the stop. */
    private static final int DEFAULT_RADIUS = 32;
    /** Sheep have to be in view to trigger the diversion; roaming for them is a different job. */
    private static final int SHEEP_VIEW_RADIUS = 32;
    /** How far to sweep for the wool a killed sheep dropped. */
    private static final int LOOT_RADIUS = 16;
    /** Kill/collect rounds before the flock is written off, so a fleeing one is not chased forever. */
    private static final int MAX_WOOL_ROUNDS = 6;
    /** Dusk is roughly a fifth of a Minecraft day; waiting longer than half a day is not waiting. */
    private static final int MAX_WAIT_FOR_NIGHT_TICKS = 12000;
    /** Ticks in bed before concluding the night is not going to pass (other players are awake). */
    private static final int MAX_SLEEP_TICKS = 400;
    /** Ticks spent clicking one bed spot before trying a different one. */
    private static final int MAX_PLACE_TICKS = 60;
    /** Sleep refusals - monsters nearby, obstructed - before this is reported honestly. */
    private static final int MAX_SLEEP_ATTEMPTS = 5;
    /** Ticks between right-clicks, so one refusal is not sent twenty times a second. */
    private static final int SLEEP_CLICK_INTERVAL = 10;
    /** Planks for a crafting table, when the bed has to be crafted and there is no table around. */
    private static final int PLANKS_PER_TABLE = 4;
    /** Bed steps that may fail before the whole diversion is abandoned. */
    private static final int MAX_STEP_FAILURES = 3;

    private final boolean waitForNight;
    private final boolean reclaim;
    private final int radius;

    private Task current;
    private String currentLabel = "";
    private BlockPos bedPos;
    private Block bedBlock;
    private boolean slept;
    private int waitTicks;
    private int placeTicks;
    private int sleepTicks;
    private int sleepAttempts;
    private int clickCooldown;
    private int stepFailures;
    private int woolRounds;
    /** Wool held when the round counter last advanced, so productive rounds cost nothing. */
    private int woolAtLastRound;
    private final Set<Long> badBedSpots = new HashSet<>();
    private String status = "";

    public SleepTask(boolean waitForNight, boolean reclaim) {
        this(waitForNight, reclaim, DEFAULT_RADIUS);
    }

    public SleepTask(boolean waitForNight, boolean reclaim, int radius) {
        this.waitForNight = waitForNight;
        this.reclaim = reclaim;
        this.radius = Math.max(4, radius);
    }

    @Override
    public String name() {
        return "Sleep";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        current = null;
        currentLabel = "";
        bedPos = null;
        bedBlock = null;
        slept = false;
        waitTicks = 0;
        placeTicks = 0;
        sleepTicks = 0;
        sleepAttempts = 0;
        clickCooldown = 0;
        stepFailures = 0;
        woolRounds = 0;
        woolAtLastRound = 0;
        badBedSpots.clear();
        status = "checking for a bed";
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        publishDebug(ctx);

        if (ctx.player.isSleeping()) {
            return tickAsleep(ctx);
        }
        if (slept) {
            return finish(ctx);
        }

        if (current != null) {
            TaskStatus result = current.tick(ctx);
            status = currentLabel + " - " + current.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            String failedStatus = current.status();
            current.stop(ctx);
            current = null;
            if (result == TaskStatus.FAILED && ++stepFailures >= MAX_STEP_FAILURES) {
                status = "gave up making a bed: " + failedStatus;
                ctx.debug.decide("bed steps kept failing; continuing without sleeping");
                return TaskStatus.FAILED;
            }
            return TaskStatus.RUNNING;
        }

        if (bedPos == null && !hasBed(ctx)) {
            return startBedStep(ctx);
        }

        if (!canSleepNow(ctx)) {
            if (!waitForNight) {
                status = "bed ready; it is not dark enough to sleep";
                return TaskStatus.SUCCESS;
            }
            if (++waitTicks > MAX_WAIT_FOR_NIGHT_TICKS) {
                status = "bed ready, but nightfall never came";
                return TaskStatus.SUCCESS;
            }
            status = "bed ready, waiting for nightfall";
            ctx.debug.intent = "waiting for it to get dark before using the bed";
            return TaskStatus.RUNNING;
        }

        return tickPlaceAndSleep(ctx);
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

    // --- sleeping ---------------------------------------------------------------------------

    /**
     * Whether a bed may be used here at all.
     * <p>
     * Asked of the world rather than of the clock: the same question covers a thunderstorm at noon
     * and the Nether, where the rule is not "too bright" but "the bed detonates".
     */
    static boolean canSleepNow(BotContext ctx) {
        BedRule rule = ctx.level.environmentAttributes()
                .getValue(EnvironmentAttributes.BED_RULE, ctx.player.position());
        return !rule.explodes() && rule.canSleep(ctx.level);
    }

    private TaskStatus tickAsleep(BotContext ctx) {
        slept = true;
        ctx.input.reset();
        status = "asleep (" + ctx.player.getSleepTimer() + " ticks)";
        ctx.debug.intent = "sleeping through the night";
        if (++sleepTicks > MAX_SLEEP_TICKS) {
            // Somebody else is keeping the night going. Standing up is better than lying in a bed
            // until the mission's own deadline runs out.
            ctx.player.connection.send(new ServerboundPlayerCommandPacket(
                    ctx.player, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
            status = "the night did not pass; leaving the bed";
            ctx.debug.decide("night is not passing; get up and carry on");
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus tickPlaceAndSleep(BotContext ctx) {
        if (bedPos != null && !isOurBed(ctx, bedPos)) {
            // Broken, or the placement never landed where it was asked to.
            badBedSpots.add(bedPos.asLong());
            bedPos = null;
            placeTicks = 0;
        }

        if (bedPos == null) {
            BlockPos spot = findBedSpot(ctx);
            if (spot == null) {
                status = "nowhere flat enough to put a bed";
                ctx.debug.decide("no two-block clearing for the bed; continuing without sleeping");
                return TaskStatus.FAILED;
            }
            Block block = carriedBedBlock(ctx);
            if (block == null) {
                status = "the bed is no longer in the inventory";
                return TaskStatus.FAILED;
            }
            BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(ctx, block, spot);
            status = "placing the bed - " + placement.name().toLowerCase();
            ctx.debug.intent = "placing a bed to sleep in";
            if (placement == BlockPlacer.PlacementResult.PLACED
                    || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
                bedPos = spot.immutable();
                bedBlock = block;
                placeTicks = 0;
                return TaskStatus.RUNNING;
            }
            if (!placement.isTransient() || ++placeTicks > MAX_PLACE_TICKS) {
                // A bed needs its head block free as well as its foot, and nothing in the placement
                // result says which of the two was in the way. Retire the spot and try another.
                badBedSpots.add(spot.asLong());
                placeTicks = 0;
            }
            return TaskStatus.RUNNING;
        }

        if (clickCooldown > 0) {
            clickCooldown--;
            status = "waiting for the bed to accept";
            return TaskStatus.RUNNING;
        }

        if (sleepAttempts >= MAX_SLEEP_ATTEMPTS) {
            status = "the bed refused (" + describeSleepRefusal(ctx) + ")";
            ctx.debug.decide("bed refused repeatedly; continuing without sleeping");
            return TaskStatus.FAILED;
        }

        ctx.debug.intent = "getting into the bed";
        if (BlockPlacer.use(ctx, bedPos)) {
            sleepAttempts++;
            clickCooldown = SLEEP_CLICK_INTERVAL;
            status = "getting into the bed (attempt " + sleepAttempts + ")";
        } else {
            status = "walking into reach of the bed";
            approachBed(ctx);
        }
        return TaskStatus.RUNNING;
    }

    /** The two answers a player would give when a bed will not take them. */
    private String describeSleepRefusal(BotContext ctx) {
        AABB area = new AABB(bedPos).inflate(8.0, 5.0, 8.0);
        for (Entity entity : ctx.level.getEntities(ctx.player, area,
                entity -> entity instanceof net.minecraft.world.entity.monster.Enemy && entity.isAlive())) {
            return "monsters nearby: " + entity.getType().getDescription().getString();
        }
        return "obstructed or too far away";
    }

    private void approachBed(BotContext ctx) {
        if (current == null) {
            current = new GotoTask(new com.etka.lune.bot.path.Goals.Near(bedPos, 2), false, false);
            currentLabel = "walking to the bed";
            current.start(ctx);
        }
    }

    private TaskStatus finish(BotContext ctx) {
        if (!reclaim || bedPos == null || !isOurBed(ctx, bedPos)) {
            status = describeNightOutcome(ctx);
            return TaskStatus.SUCCESS;
        }
        if (current == null) {
            // Taking the bed with it is what makes this worth doing once rather than every night.
            current = new MineTask(Set.of(bedBlock), 8, ctx.level.getMinY(), ctx.level.getMaxY(),
                    1, false, false);
            currentLabel = "picking the bed back up";
            current.start(ctx);
            return TaskStatus.RUNNING;
        }
        TaskStatus result = current.tick(ctx);
        status = currentLabel + " - " + current.status();
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        current.stop(ctx);
        current = null;
        bedPos = null;
        // Whether or not the bed came back, the night is what this was about.
        status = describeNightOutcome(ctx);
        return TaskStatus.SUCCESS;
    }

    /**
     * Says whether the night actually passed.
     * <p>
     * Getting out of a bed is not the same as morning. On a server with other players awake the
     * bot lies there, gives up, and stands in exactly the darkness it went to bed to avoid - and a
     * journal that reports that as "slept through the night" is worse than no journal.
     */
    private String describeNightOutcome(BotContext ctx) {
        return canSleepNow(ctx) ? "left the bed; the night did not pass" : "slept through the night";
    }

    // --- getting a bed ----------------------------------------------------------------------

    /**
     * Starts the first step that is still missing. Re-derived rather than sequenced, so supplies
     * consumed by one step are simply asked for again.
     */
    private TaskStatus startBedStep(BotContext ctx) {
        Map<String, Integer> wool = woolByColour(ctx);
        Map<String, Integer> dye = availableDye(ctx);
        BedPolicy.Plan plan = BedPolicy.plan(wool, dye);
        ctx.debug.memory = "wool=" + wool + "; dye reachable=" + dye;

        switch (plan.step()) {
            case NEED_WOOL -> {
                // Only fruitless rounds count. A round that came back with wool is progress, and
                // charging it against the budget is how a bot ends up two wool short of a bed,
                // standing next to the third, with its patience already spent.
                int carried = carriedWool(ctx);
                if (carried > woolAtLastRound) {
                    woolAtLastRound = carried;
                    woolRounds = 0;
                }
                if (++woolRounds > MAX_WOOL_ROUNDS) {
                    status = "the flock did not yield three wool";
                    return TaskStatus.FAILED;
                }
                // Drops first, and deliberately before looking for more sheep: a sheep that has
                // just been killed is no longer a visible sheep, so checking the flock first would
                // walk away from the wool it just dropped.
                if (LootTask.hasDropsNearby(ctx, LOOT_RADIUS)) {
                    return start(ctx, new LootTask(LOOT_RADIUS), "collecting wool");
                }
                int sheep = visibleSheep(ctx);
                if (sheep <= 0) {
                    status = "no sheep in sight for a bed";
                    ctx.debug.decide("no visible sheep; a bed is not available here");
                    return TaskStatus.FAILED;
                }
                ctx.debug.decide("take the flock: " + sheep + " sheep in view, "
                        + plan.woolStillNeeded() + " wool short");
                // Kill rather than roam: the whole point of this diversion is that the animals are
                // already in front of the bot, and a roaming hunt would walk eighty blocks first.
                return start(ctx, new KillTask(Set.of(EntityType.SHEEP), SHEEP_VIEW_RADIUS,
                        KillOptions.basic()), "taking wool from the flock");
            }
            case NO_MATCHING_DYE -> {
                // Three wool of three colours with no flowers is not a dead end while there are
                // still sheep standing there: one more fleece is a far better bet than a dye that
                // does not grow here. Only give up once the flock is gone too.
                if (visibleSheep(ctx) > 0 && woolRounds <= MAX_WOOL_ROUNDS) {
                    woolRounds++;
                    ctx.debug.decide("colours do not match and nothing to dye with; take another fleece");
                    return start(ctx, new KillTask(Set.of(EntityType.SHEEP), SHEEP_VIEW_RADIUS,
                            KillOptions.basic()), "taking another fleece to find a matching colour");
                }
                status = "wool colours do not match and no usable flower is in reach";
                ctx.debug.decide("mismatched wool with no dye source; continuing without a bed");
                return TaskStatus.FAILED;
            }
            case DYE -> {
                DyeColor colour = FlowerCatalog.colourByName(plan.colour());
                if (colour == null) {
                    status = "unknown wool colour " + plan.colour();
                    return TaskStatus.FAILED;
                }
                return startDyeStep(ctx, colour, plan.woolToDye());
            }
            default -> {
                return startCraftStep(ctx);
            }
        }
    }

    private TaskStatus startDyeStep(BotContext ctx, DyeColor colour, int woolToDye) {
        int carriedDye = InventoryHelper.count(ctx.player, stack -> FlowerCatalog.isDye(stack, colour));
        if (carriedDye >= woolToDye) {
            ctx.debug.decide("dye " + woolToDye + " wool " + colour.getSerializedName());
            return start(ctx, CraftTask.matching(stack -> FlowerCatalog.isWool(stack, colour),
                            colour.getSerializedName() + " wool", BedPolicy.WOOL_PER_BED, false),
                    "dyeing wool " + colour.getSerializedName());
        }

        Set<Block> flowers = FlowerCatalog.flowersFor(colour);
        int carriedFlowers = InventoryHelper.count(ctx.player,
                stack -> isFlowerItem(stack, flowers));
        if (carriedFlowers > 0) {
            ctx.debug.decide("craft " + colour.getSerializedName() + " dye from the flowers carried");
            return start(ctx, CraftTask.matching(stack -> FlowerCatalog.isDye(stack, colour),
                            colour.getSerializedName() + " dye", woolToDye, false),
                    "crafting " + colour.getSerializedName() + " dye");
        }

        // Worst case one dye per flower: the tall ones give two, which only ever means a spare.
        int wanted = BedPolicy.flowersNeeded(woolToDye - carriedDye, 1);
        ctx.debug.decide("pick " + wanted + " flowers for " + colour.getSerializedName() + " dye");
        return start(ctx, new MineTask(flowers, radius, ctx.level.getMinY(), ctx.level.getMaxY(),
                wanted, false, false), "picking flowers");
    }

    private TaskStatus startCraftStep(BotContext ctx) {
        int planks = InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.PLANKS));
        boolean carriesTable = InventoryHelper.has(ctx.player, Items.CRAFTING_TABLE, 1);
        int planksWanted = BedPolicy.PLANKS_PER_BED + (carriesTable ? 0 : PLANKS_PER_TABLE);
        if (planks < BedPolicy.PLANKS_PER_BED) {
            ctx.debug.decide("make planks for the bed");
            return start(ctx, CraftTask.ofTag(ItemTags.PLANKS, "planks", planksWanted, false),
                    "making planks");
        }
        if (!carriesTable && planks >= BedPolicy.PLANKS_PER_BED + PLANKS_PER_TABLE
                && !CraftTask.tableInReach(ctx, 6)) {
            ctx.debug.decide("make a crafting table for the bed");
            return start(ctx, CraftTask.of(Items.CRAFTING_TABLE, 1, false), "making a crafting table");
        }
        ctx.debug.decide("craft the bed");
        return start(ctx, CraftTask.ofTag(ItemTags.BEDS, "bed", 1, true), "crafting the bed");
    }

    private TaskStatus start(BotContext ctx, Task task, String label) {
        current = task;
        currentLabel = label;
        current.start(ctx);
        status = label;
        return TaskStatus.RUNNING;
    }

    // --- world queries ----------------------------------------------------------------------

    static boolean hasBed(BotContext ctx) {
        return InventoryHelper.anyMatch(ctx.player, stack -> stack.is(ItemTags.BEDS));
    }

    /** Wool of every colour, for deciding whether a flock is still worth stopping for. */
    static int carriedWool(BotContext ctx) {
        return InventoryHelper.count(ctx.player, stack -> stack.is(ItemTags.WOOL));
    }

    private Block carriedBedBlock(BotContext ctx) {
        int slot = InventoryHelper.findSlot(ctx.player, stack -> stack.is(ItemTags.BEDS));
        if (slot < 0) {
            return null;
        }
        ItemStack stack = ctx.player.getInventory().getItem(slot);
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem item ? item.getBlock() : null;
    }

    private boolean isOurBed(BotContext ctx, BlockPos pos) {
        BlockState state = ctx.level.getBlockState(pos);
        return state.getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }


    /**
     * A foot block with its head block free in the same direction.
     * <p>
     * Vanilla puts the head one step along the player's facing, and refuses the placement outright
     * if that block is occupied - so a spot is only usable together with its neighbour, and the
     * neighbour has to be the one further from the player.
     */
    private BlockPos findBedSpot(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos foot = feet.offset(dx, dy, dz);
                    if (badBedSpots.contains(foot.asLong()) || foot.equals(feet)) {
                        continue;
                    }
                    if (!BlockPlacer.canPlaceAt(ctx, foot)) {
                        continue;
                    }
                    Direction away = awayFromPlayer(feet, foot);
                    BlockPos head = foot.relative(away);
                    if (!BlockPlacer.isReplaceable(ctx, head)
                            || BlockPlacer.findSupport(ctx, head) == null) {
                        continue;
                    }
                    double distance = feet.distSqr(foot);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = foot;
                    }
                }
            }
        }
        return best;
    }

    private static Direction awayFromPlayer(BlockPos feet, BlockPos foot) {
        int dx = foot.getX() - feet.getX();
        int dz = foot.getZ() - feet.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private int visibleSheep(BotContext ctx) {
        return visibleSheep(ctx, SHEEP_VIEW_RADIUS);
    }

    /** Sheep the bot can actually see, so the diversion is triggered by sight and not by radar. */
    static int visibleSheep(BotContext ctx, int viewRadius) {
        AABB area = ctx.player.getBoundingBox().inflate(viewRadius);
        int seen = 0;
        for (Entity entity : ctx.level.getEntities(ctx.player, area,
                entity -> entity.getType() == EntityType.SHEEP && entity.isAlive()
                        // Lambs carry no wool, so a field of them is not three sheep.
                        && !(entity instanceof net.minecraft.world.entity.LivingEntity living
                                && KillTask.isWorthlessCalf(living)))) {
            if (Vision.isEntityVisible(ctx, entity)) {
                seen++;
            }
        }
        return seen;
    }

    private Map<String, Integer> woolByColour(BotContext ctx) {
        Map<String, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < ctx.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = ctx.player.getInventory().getItem(slot);
            String colour = FlowerCatalog.woolColourName(stack);
            if (colour != null) {
                counts.merge(colour, stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * How many dyes of each colour are within reach: what is already carried, plus what the visible
     * flowers would craft into. Flowers that cannot be seen do not count, for the same reason
     * hidden ore does not.
     */
    private Map<String, Integer> availableDye(BotContext ctx) {
        Map<String, Integer> available = new HashMap<>();
        for (int slot = 0; slot < ctx.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = ctx.player.getInventory().getItem(slot);
            DyeColor carried = stack.get(net.minecraft.core.component.DataComponents.DYE);
            if (carried != null) {
                available.merge(carried.getSerializedName(), stack.getCount(), Integer::sum);
            }
        }
        for (Map.Entry<Block, Integer> entry : visibleFlowers(ctx).entrySet()) {
            FlowerCatalog.Dye dye = FlowerCatalog.dyeOf(entry.getKey());
            if (dye != null) {
                available.merge(FlowerCatalog.colourName(dye.colour()),
                        entry.getValue() * dye.perFlower(), Integer::sum);
            }
        }
        return available;
    }

    /** Flowers in the loaded area that the bot can see, counted per kind. */
    private Map<Block, Integer> visibleFlowers(BotContext ctx) {
        Map<Block, Integer> found = new HashMap<>();
        Set<Block> known = FlowerCatalog.flowers();
        BlockPos centre = ctx.player.blockPosition();
        // Flowers grow on the surface the bot is standing on, so this is a thin slab rather than a
        // cube: the search runs on the tick a bed step is chosen and should not cost more than it
        // saves.
        int reach = Math.min(radius, 16);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (!ctx.level.isLoaded(cursor)) {
                        continue;
                    }
                    Block block = ctx.level.getBlockState(cursor).getBlock();
                    if (!known.contains(block)) {
                        continue;
                    }
                    if (!Vision.isVisible(ctx, cursor)) {
                        continue;
                    }
                    found.merge(block, 1, Integer::sum);
                }
            }
        }
        return found;
    }

    private static boolean isFlowerItem(ItemStack stack, Set<Block> flowers) {
        for (Block flower : flowers) {
            if (stack.is(flower.asItem())) {
                return true;
            }
        }
        return false;
    }

    private void publishDebug(BotContext ctx) {
        ctx.debug.giveUp = "sleep attempts " + sleepAttempts + "/" + MAX_SLEEP_ATTEMPTS
                + "; bed steps failed " + stepFailures + "/" + MAX_STEP_FAILURES;
    }
}
