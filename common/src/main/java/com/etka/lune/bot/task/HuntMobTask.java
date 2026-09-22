package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.learning.LearningScope;
import com.etka.lune.bot.knowledge.BiomeScout;
import com.etka.lune.bot.knowledge.Need;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Roams, hunts one mob type, and collects its drops until the requested amount is reached.
 *
 * <p>This is deliberately separate from {@link KillTask}: Kill remains the general nearby-entity
 * command, while the specialised Hunt commands can roam and prepare the right combat behaviour for
 * one enemy family.</p>
 */
public class HuntMobTask implements Task {

    private static final int HUNT_RADIUS = 64;
    private static final int LOOT_RADIUS = 32;
    private static final int ROAM_DISTANCE = 80;
    private static final int MAX_ROAMS = 12;

    private enum State {
        FIND, HUNT, LOOT, ROAM
    }

    private final EntityType<?> targetType;
    private final Predicate<ItemStack> dropMatches;
    private final String dropLabel;
    private final String label;
    private final int wanted;
    private final KillOptions options;

    /**
     * Mobs already written off, kept out here because the hunt builds a fresh {@link KillTask}
     * every time it cycles back to hunting. Held inside Kill it would be wiped each cycle, and the
     * hunt would walk back to the same unreachable animal for as long as the task ran.
     */
    private final java.util.Set<Integer> unreachableMobs;
    private final boolean clearUnreachableOnStart;

    private int startDrops;
    private State state = State.FIND;
    private Task current;
    private int roams;
    private int gainedDrops;
    private final StatusText status = new StatusText();

    public HuntMobTask(EntityType<?> targetType, Item drop, String label, int wanted,
                       KillOptions options) {
        this(targetType, stack -> stack.is(drop), InventoryHelper.itemName(drop),
                label, wanted, options, null);
    }

    public HuntMobTask(EntityType<?> targetType, Predicate<ItemStack> dropMatches,
                       String dropLabel, String label, int wanted, KillOptions options) {
        this(targetType, dropMatches, dropLabel, label, wanted, options, null);
    }

    /**
     * Lets a long-running caller retain failed mob ids when it rebuilds this hunt after a retry.
     * A fresh hunt keeps the old behaviour; only an explicit shared set survives the rebuild.
     */
    public HuntMobTask(EntityType<?> targetType, Predicate<ItemStack> dropMatches,
                       String dropLabel, String label, int wanted, KillOptions options,
                       java.util.Set<Integer> sharedUnreachableMobs) {
        this.targetType = targetType;
        this.dropMatches = dropMatches;
        this.dropLabel = dropLabel;
        this.label = label;
        this.wanted = Math.max(1, wanted);
        this.options = options == null ? KillOptions.basic() : options;
        this.unreachableMobs = sharedUnreachableMobs == null
                ? new java.util.HashSet<>() : sharedUnreachableMobs;
        this.clearUnreachableOnStart = sharedUnreachableMobs == null;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.hunt_mob.name", com.etka.lune.bot.command.Param.Choice.optionLabel(label));
    }

    /** English on purpose: this is the learner's row key, and is never shown. */
    @Override
    public String learningId() {
        return Task.learningName("Hunt " + label);
    }

    /**
     * A hunt is measured in the drops it was sent for, whichever item those are. The progress bar
     * names the drop; the rows are keyed {@code unit=items}, because the drop's name is rendered
     * text and the rows have to be the same rows in every language.
     */
    @Override
    public LearningScope learningScope() {
        return LearningScope.of(learningId(), "lune.unit.items");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return new TaskProgress(gainedDrops, wanted, dropLabel);
    }

    @Override
    public void onStart(BotContext ctx) {
        startDrops = InventoryHelper.count(ctx.player, dropMatches);
        state = State.FIND;
        current = null;
        roams = 0;
        gainedDrops = 0;
        if (clearUnreachableOnStart) {
            unreachableMobs.clear();
        }
        status.clear();
        publishHuntDebug(ctx);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        publishHuntDebug(ctx);
        int gained = gained(ctx);
        gainedDrops = Math.max(0, gained);
        if (gained >= wanted) {
            stopCurrent(ctx);
            status.set("lune.status.hunt_mob.gathered", gained, dropLabel);
            return TaskStatus.SUCCESS;
        }

        if (current != null) {
            // Cut a roam short the moment the quarry is actually in view.
            //
            // The hunt only ever looked at the end of a walk: ROAM picked a point up to eighty
            // blocks away, walked the whole way, and only then spent a tick asking whether anything
            // was killable. Anything grazing beside the route went unnoticed. Measured, that is not
            // a small loss - two ten-minute runs spent 99.4% and 99.6% of themselves in ROAM,
            // covered 1861 blocks between them, and killed nothing at all.
            //
            // The block searches were told this long ago: "scan while turning, not only at stops -
            // a player notices a tree as it swings into view." A sheep is no different.
            if (state == State.ROAM) {
                // Look around on the way, or the cone never contains anything. Vision requires a
                // target to be inside the view cone, and while walking the head points down the
                // route - so a sheep off to one side is invisible for the whole journey however
                // close it passes. Steering does not care where the head is pointing
                // (PathExecutor.steer presses keys off the relative angle), so this costs nothing
                // but the turn itself.
                if (!scanner.isTurning() && !scanner.isVerticalGlance()) {
                    scanner.reset(ctx.player);
                }
                scanner.tickTurn(ctx);
                if (quarryInSight(ctx)) {
                    stopCurrent(ctx);
                    scanner.finish();
                    return advance(ctx, TaskStatus.SUCCESS);
                }
            }
            TaskStatus result = current.tick(ctx);
            status.set("lune.status.detail", state, current.statusLine());
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            stopCurrent(ctx);
            return advance(ctx, result);
        }

        return advance(ctx, TaskStatus.SUCCESS);
    }

    @Override
    public void onStop(BotContext ctx) {
        stopCurrent(ctx);
        ctx.input.reset();
    }

    private TaskStatus advance(BotContext ctx, TaskStatus previous) {
        switch (state) {
            case FIND -> state = LootTask.hasDropsNearby(ctx, LOOT_RADIUS) ? State.LOOT : State.ROAM;
            case HUNT -> state = previous == TaskStatus.FAILED ? State.ROAM : State.LOOT;
            case LOOT -> state = State.ROAM;
            case ROAM -> state = State.HUNT;
        }

        // The roam budget is the hunt's, not the first decision's.
        //
        // It used to be asked only on the way out of FIND, which the machine enters once and never
        // returns to - so once the cycle was turning, nothing counted it. A twenty-minute survival
        // run spent forty-eight per cent of itself here: hunt finds no sheep, loot finds no drops,
        // the roam cannot find a route, hunt again, about twenty-seven hundred times, and it came
        // home with no wool and no mutton. Counted where a roam actually begins, the same budget
        // that was meant to stop this does stop it.
        if (state == State.ROAM && roams++ >= maxRoams()) {
            status.set("lune.status.hunt_mob.roamed_too_much_only", roams, maxRoams(), gained(ctx), dropLabel);
            return TaskStatus.FAILED;
        }

        current = createTask(ctx);
        if (current == null) {
            status.set("lune.status.hunt_mob.failed", state);
            return TaskStatus.FAILED;
        }
        current.start(ctx);
        status.set("lune.status.hunt_mob.started", state);
        return TaskStatus.RUNNING;
    }

    private Task createTask(BotContext ctx) {
        return switch (state) {
            case HUNT -> new KillTask(Set.of(targetType), HUNT_RADIUS, options, unreachableMobs);
            case LOOT -> new LootTask(LOOT_RADIUS);
            case ROAM -> new GotoTask(new Goals.Near(pickRoamTarget(ctx), 8), true, false);
            default -> null;
        };
    }

    /**
     * Whether a living target of the wanted kind is visible from here right now.
     *
     * <p>Deliberately the same {@link Vision} test the kill itself will apply, so a sighting that
     * stops a roam is a sighting the hunt can act on - noticing something the next step refuses is
     * how a bot ends up walking back and forth between the same two decisions. Mobs already written
     * off as unreachable are skipped for the same reason.</p>
     */
    private boolean quarryInSight(BotContext ctx) {
        if (--sightCheckCooldown > 0) {
            return false;
        }
        // A handful of entity ray casts, several times a second, is cheap next to the route search
        // this is riding along with - but not free, so it is not run every tick.
        sightCheckCooldown = SIGHT_CHECK_INTERVAL;
        AABB box = ctx.player.getBoundingBox().inflate(HUNT_RADIUS);
        for (LivingEntity mob : ctx.level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e.getType() == targetType
                        && !unreachableMobs.contains(e.getId()))) {
            if (Vision.isEntityVisible(ctx, mob)) {
                return true;
            }
        }
        return false;
    }

    /** Ticks between sight checks while roaming; see {@link #quarryInSight}. */
    private static final int SIGHT_CHECK_INTERVAL = 5;
    private int sightCheckCooldown;
    /** Sweeps the view while walking so the cone gets a chance to contain something. */
    private final HeadScanner scanner = new HeadScanner(HeadScanner.Style.SWEEP);

    /** Specialized hunters may need a larger, still bounded, search budget. */
    protected int maxRoams() {
        return MAX_ROAMS;
    }

    private void publishHuntDebug(BotContext ctx) {
        ctx.debug.searchAttempt = roams;
        ctx.debug.searchLimit = maxRoams();
        ctx.debug.giveUp = "hunt roams " + roams + "/" + maxRoams();
        ctx.debug.memory = unreachableMobs.size() + " unreachable "
                + targetType.getDescription().getString() + " ids remembered";
        // Loaded nearby, against how many of those the bot can actually see.
        //
        // Without both numbers a fruitless hunt is unreadable: "roamed for ten minutes and killed
        // nothing" is the same sentence whether the bot walked through an empty grassland or past a
        // flock it could not see, and those want opposite fixes. Two runs of this task differed by
        // 88% and 99.5% roaming on the same seed with the same code, which is the point at which
        // guessing stops being worth anything.
        int loaded = 0;
        int visible = 0;
        for (LivingEntity mob : ctx.level.getEntitiesOfClass(LivingEntity.class,
                ctx.player.getBoundingBox().inflate(HUNT_RADIUS),
                e -> e.isAlive() && e.getType() == targetType)) {
            loaded++;
            if (Vision.isEntityVisible(ctx, mob)) {
                visible++;
            }
        }
        ctx.debug.sightTally = "loaded=" + loaded + ";visible=" + visible
                + ";writtenOff=" + unreachableMobs.size();
    }

    private int gained(BotContext ctx) {
        return InventoryHelper.count(ctx.player, dropMatches) - startDrops;
    }

    private void stopCurrent(BotContext ctx) {
        if (current != null) {
            current.stop(ctx);
            current = null;
        }
    }

    /**
     * Where to walk next while searching.
     *
     * <p>Deliberately a fresh random point each time. Committing to a heading for several legs was
     * tried, on the reasoning that the block searches are told to do exactly that - "re-deciding
     * every stop is what makes a bot wander in circles" - and it made the hunt strictly worse:
     * 98.3% of a run roaming with nothing killed, against 88.2% and four drops for the random walk.
     * The rule does not carry over, because the quarry does not sit still. A committed heading
     * walks away from a herd; criss-crossing keeps coming back over ground the animals have since
     * wandered into.</p>
     */
    private BlockPos pickRoamTarget(BotContext ctx) {
        BlockPos p = ctx.player.blockPosition();
        RandomSource random = ctx.player.getRandom();
        // Walk toward country the quarry lives in, when the bot has an opinion about that.
        //
        // A random point every leg is a random walk, and a random walk decides the whole run by
        // luck: two ten-minute hunts on one seed, same code, came back with thirty-one drops and
        // with nothing, and the journal says why - one spent 62% of its ticks with a sheep loaded
        // within sixty-four blocks, the other 0%. Neither was a sighting problem. It was where the
        // dice sent them.
        //
        // BiomeScout is the same knowledge Explore's "smart direction" uses. Needs of ANY get no
        // opinion back, which is every hunt but the animal ones, so they keep the random walk.
        //
        // What this bought, measured over four runs on one seed: the share of ticks with a sheep
        // loaded within sixty-four blocks went from a 0%-to-62% lottery on the random walk to 33%
        // and 50% on the two biome-led runs - the empty run stopped happening. Drops did not follow
        // (random 0/4/31, biome 18/3) because the limit moved: on the 50% run the bot was near
        // sheep half the time and could *see* one for 2.3% of it. Whatever is worth doing next for
        // this task is about sight through terrain, not about where it walks.
        var heading = BiomeScout.chooseForNeeds(ctx, java.util.Set.of(roamNeed()), lastRoamYaw, null);
        if (heading.isPresent()) {
            lastRoamYaw = heading.get().yaw();
            int dx = Math.round(-Mth.sin(lastRoamYaw * Mth.DEG_TO_RAD) * ROAM_DISTANCE);
            int dz = Math.round(Mth.cos(lastRoamYaw * Mth.DEG_TO_RAD) * ROAM_DISTANCE);
            return new BlockPos(p.getX() + dx, p.getY(), p.getZ() + dz);
        }
        int dx = Mth.randomBetweenInclusive(random, -ROAM_DISTANCE, ROAM_DISTANCE);
        int dz = Mth.randomBetweenInclusive(random, -ROAM_DISTANCE, ROAM_DISTANCE);
        return new BlockPos(p.getX() + dx, p.getY(), p.getZ() + dz);
    }

    /**
     * What kind of country this hunt's quarry lives in.
     *
     * <p>{@link Need#ANY} means "no opinion", which leaves the random walk in place. Only hunts
     * whose target really is tied to a biome should override it; a creeper is no likelier in a
     * meadow than anywhere else.</p>
     */
    protected Need roamNeed() {
        return Need.ANY;
    }

    /** The heading last chosen, offered back to BiomeScout so it can favour keeping to it. */
    private Float lastRoamYaw;
}
