package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockBreaker;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BlockScanner;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.Vision;
import com.etka.lune.util.Lang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Takes the dragon egg and turns it into an item in the bag.
 *
 * <p>The egg is the one block in the game that cannot be mined. Both halves of clicking it -
 * {@code attack} and {@code useWithoutItem} - call the same {@code teleport}, which moves it up to
 * fifteen blocks away and leaves nothing behind, so a card that treats it like ore loses it every
 * time it swings. What does collect it is its own gravity: the egg is a falling block, and a
 * falling block that comes to rest where it cannot be placed pops into an item instead of settling.
 * A torch is the usual cannot-be-placed.</p>
 *
 * <p>So this card does what a player does, in three steps:</p>
 * <ol>
 *   <li><b>Knock it off the fountain.</b> The egg spawns on top of the exit portal's bedrock, and
 *       bedrock cannot be hollowed out, so there is nowhere to put the torch. One punch teleports
 *       it onto the island, where the ground is end stone. Land on bedrock again, punch again -
 *       {@link #canExtractHere} is what asks the question, so the rule is "the ground under it must
 *       be diggable", not "it is standing on the fountain".</li>
 *   <li><b>Stand three blocks down beside it.</b> Not two. {@link BlockBreaker} works on whatever
 *       the eye's ray actually lands on, and from any shallower stance the ray to the block two
 *       under the egg clips the block one under it - or the egg itself - first. Three down puts
 *       both of them at head height, where the ray is level and hits what it was asked for. Every
 *       break is made with the egg's own position protected, so a ray that resolves onto it is
 *       refused rather than swung at.</li>
 *   <li><b>Hollow, torch, break.</b> Clear the block two below, drop a torch into that pocket,
 *       then break the block directly under the egg. The egg falls one block onto the torch and
 *       breaks into an item at the bot's feet.</li>
 * </ol>
 *
 * <p>It requires what it needs and conjures nothing: a pickaxe for the end stone and a torch for
 * the pocket. Neither is crafted here - that is an Ensure Tool or a Craft card in front of it.</p>
 *
 * <p>It also leaves a hole three deep beside the egg, standing in it. Climbing out is
 * {@link GotoTask}'s recovery, which every card that moves next goes through, and the stone this
 * card just dug is what that recovery pillars with.</p>
 */
public final class DragonEggTask implements Task {

    /** How far below the egg the bot stands to work. See the class note: two is not enough. */
    static final int STANCE_DEPTH = 3;
    /** The pocket the torch goes in, counted down from the egg. */
    static final int TORCH_DEPTH = 2;

    /** Punches before admitting the egg keeps landing somewhere it cannot be worked. */
    private static final int MAX_PUNCHES = 8;
    /** Restarts after the egg moved on its own - it fell further than the torch, say. */
    private static final int MAX_RESTARTS = 4;
    /** Ticks between the punch and looking again, so the server's move has arrived. */
    private static final int SETTLE_TICKS = 10;
    /**
     * Ticks to let the egg fall before reading the world again.
     *
     * <p>Breaking the support is not the end of it: the server turns the egg into a falling entity,
     * drops it two blocks onto the torch and spawns the item, and the client keeps drawing the old
     * block until that arrives. Reading the level in the same breath as the break sees an egg that
     * is no longer there - a measured run called a clean drop "it fell past the torch" and punched
     * the ghost of it while the real one was landing.</p>
     */
    private static final int FALL_TICKS = 20;
    /** Ticks one phase may spend before the attempt is written off. */
    private static final int PHASE_DEADLINE = 400;
    /** Close enough to click the egg, a little inside the vanilla 4.5 reach. */
    private static final double PUNCH_REACH = 4.0;
    /** How far the pickup sweep will chase the drop. */
    private static final int COLLECT_RADIUS = 4;
    /** Ticks the sweep spends on one drop before giving up on it. */
    private static final int COLLECT_DEADLINE = 200;
    /** How closely the view must be aimed before punching. */
    private static final float AIM_TOLERANCE = 12.0F;

    /** Torches in the order they are looked for; any of them stops a falling block. */
    private static final List<Block> TORCHES =
            List.of(Blocks.TORCH, Blocks.SOUL_TORCH, Blocks.REDSTONE_TORCH);

    /**
     * Vantage points tried around the last sighting before admitting the egg is lost.
     *
     * <p>A punch throws the egg up to fifteen blocks and the bot does not watch it go, so the
     * spot it was standing on is the middle of a circle rather than a place to keep staring at.
     * Four stops is enough to see behind the rise the bot happens to be standing against, which
     * is what hid it in the measured run: a full sweep from the podium found nothing at all.</p>
     */
    private static final int MAX_SEEK_STOPS = 4;
    /** How far from the last sighting a vantage point sits: a new angle, not more range. */
    private static final int SEEK_RADIUS = 9;
    /** Which way to walk for each of those stops, in order. */
    private static final Direction[] SEEK_HEADINGS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

    private enum Phase { SEARCH, SEEK, APPROACH, PUNCH, SETTLE, STANCE, POCKET, TORCH, DROP, COLLECT }

    private final int radius;
    private final StatusText status = new StatusText();
    private final BlockBreaker breaker = new BlockBreaker();
    private final HeadScanner headScanner = new HeadScanner(HeadScanner.Style.GLANCE);

    private Phase phase = Phase.SEARCH;
    private BlockPos egg;
    /** Where the egg was the last time the bot had eyes on it; the middle of where to look next. */
    private BlockPos lastSeen;
    private int seekStops;
    /** The column the bot digs down in, at the egg's own height. */
    private BlockPos stance;
    /** Raised when a break is refused because the ray resolved onto a protected block. */
    private int extraDepth;
    private GotoTask approach;
    private Phase afterApproach = Phase.PUNCH;
    private boolean approachingStance;
    private LootTask sweep;

    private int eggsAtStart;
    private int punches;
    private int restarts;
    private int phaseTicks;
    private int settleTicks;
    private boolean scanExhausted;
    private boolean collected;

    public DragonEggTask(int radius) {
        this.radius = Math.max(1, radius);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.dragon_egg.name");
    }

    /** The English this is, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Collect Dragon Egg");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public boolean madeProgress() {
        return collected;
    }

    @Override
    public void onStart(BotContext ctx) {
        // Counted rather than tested: a bag that already holds an egg would make "do I have one"
        // true before the card has done anything, and the egg on the fountain would stay there.
        eggsAtStart = InventoryHelper.count(ctx.player, Items.DRAGON_EGG);
        headScanner.reset(ctx.player);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (InventoryHelper.count(ctx.player, Items.DRAGON_EGG) > eggsAtStart) {
            collected = true;
            breaker.stop(ctx);
            status.set("lune.status.dragon_egg.collected");
            return TaskStatus.SUCCESS;
        }
        if (++phaseTicks > deadline()) {
            return fail("lune.status.dragon_egg.gave_up", Lang.get(phaseKey()));
        }
        return switch (phase) {
            case SEARCH -> search(ctx);
            case SEEK -> seek(ctx);
            case APPROACH -> approach(ctx);
            case PUNCH -> punch(ctx);
            case SETTLE -> settle(ctx);
            case STANCE -> digStance(ctx);
            case POCKET -> hollowPocket(ctx);
            case TORCH -> placeTorch(ctx);
            case DROP -> dropIt(ctx);
            case COLLECT -> collect(ctx);
        };
    }

    // ---------------------------------------------------------------- finding it

    /**
     * Looks for the egg the way a player does after it has jumped: glance ahead first, widen to a
     * sweep, and accept only what the eyes can actually see.
     */
    private TaskStatus search(BotContext ctx) {
        BlockPos found = lookForEgg(ctx);
        if (found != null) {
            egg = found.immutable();
            lastSeen = egg;
            headScanner.reset(ctx.player);
            return plan(ctx);
        }
        if (!scanExhausted) {
            return TaskStatus.RUNNING;
        }
        // Standing still and looking harder is not a plan. If the egg has been seen at all, the
        // bot knows roughly where it is - a punch throws it fifteen blocks, not out of the world -
        // so go and look from somewhere else. With no sighting at all there is nothing to circle.
        if (lastSeen != null && seekStops < MAX_SEEK_STOPS) {
            return go(Phase.SEEK);
        }
        return fail("lune.status.dragon_egg.none_in_sight");
    }

    /**
     * Walks to another vantage point around the last sighting, looking as it goes.
     *
     * <p>The measured failure this exists for: the bot punched the egg off the podium, swept its
     * head through every heading from the spot it was standing on, and never saw it - the island
     * is not flat, and an egg on the ground behind a two-block rise is hidden from a player as
     * surely as from a bot. Moving is the only thing that answers it.</p>
     */
    private TaskStatus seek(BotContext ctx) {
        BlockPos found = visibleEgg(ctx);
        if (found != null) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
            egg = found.immutable();
            lastSeen = egg;
            headScanner.reset(ctx.player);
            return plan(ctx);
        }
        if (approach == null) {
            BlockPos vantage = lastSeen.relative(
                    SEEK_HEADINGS[seekStops % SEEK_HEADINGS.length], SEEK_RADIUS);
            seekStops++;
            // Horizontal goal: the ground out there is whatever height the island makes it, and
            // walking is what this is for. No breaking, for the same reason as the approach.
            approach = new GotoTask(new Goals.NearXZ(vantage.getX(), vantage.getZ(), 2),
                    false, false);
            approach.start(ctx);
        }
        status.set("lune.status.dragon_egg.looking_from_another_spot", seekStops, MAX_SEEK_STOPS);
        TaskStatus walk = approach.tick(ctx);
        if (walk == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        // Arrived, or could not get there. Either way, look from wherever this is.
        approach.stop(ctx);
        approach = null;
        headScanner.reset(ctx.player);
        scanExhausted = false;
        return go(Phase.SEARCH);
    }

    /** The nearest dragon egg the bot can actually see from where it stands, or null. */
    private BlockPos visibleEgg(BotContext ctx) {
        BlockPos centre = ctx.player.blockPosition();
        int limit = Math.min(radius, (int) Vision.maxRange(ctx));
        return BlockScanner.findNearest(ctx.level, centre, Set.of(Blocks.DRAGON_EGG), limit,
                centre.getY() - radius, centre.getY() + radius, Set.of(),
                (pos, state) -> Vision.isVisible(ctx, pos));
    }

    private BlockPos lookForEgg(BotContext ctx) {
        boolean settled = true;
        if (headScanner.isTurning() || headScanner.isVerticalGlance()) {
            // Visibility is tested every tick, turning or not: a player notices the egg as it
            // swings into view rather than only at the end of the turn.
            settled = headScanner.tickTurn(ctx);
            status.set(headScanner.statusLine());
            ctx.debug.searchHeading = headScanner.status();
        }

        BlockPos found = visibleEgg(ctx);
        if (found != null) {
            scanExhausted = false;
            return found;
        }
        if (!settled) {
            return null;
        }
        if (headScanner.advance()) {
            status.set("lune.status.scan.looking", headScanner.statusLine());
            return null;
        }
        if (headScanner.escalate(ctx.player)) {
            status.set("lune.status.dragon_egg.looking_around");
            return null;
        }
        scanExhausted = true;
        return null;
    }

    /**
     * Decides which of the two jobs this egg needs - a punch, or the hole beside it - and walks to
     * the place that job is done from.
     */
    private TaskStatus plan(BotContext ctx) {
        if (!canExtractHere(ctx.level, egg)) {
            // The fountain, or anywhere else with bedrock or a drop underneath it.
            return walkTo(ctx, new Goals.Adjacent(egg, PUNCH_REACH), Phase.PUNCH, false);
        }
        BlockPos column = stanceColumn(ctx.level, egg, ctx.player.blockPosition(), extraDepth);
        if (column == null) {
            return walkTo(ctx, new Goals.Adjacent(egg, PUNCH_REACH), Phase.PUNCH, false);
        }
        stance = column;
        return walkTo(ctx, new Goals.Block(stance), Phase.STANCE, true);
    }

    private TaskStatus walkTo(BotContext ctx, Goal goal, Phase next, boolean toStance) {
        if (approach != null) {
            approach.stop(ctx);
        }
        // Never with breaking enabled: a route that may mine its way through is a route that may
        // decide the egg is the cheapest block in its path, and one swing is all it takes to lose
        // it. Walking is enough to stand beside a block that is by definition in the open.
        approach = new GotoTask(goal, false, false);
        approach.start(ctx);
        afterApproach = next;
        approachingStance = toStance;
        return go(Phase.APPROACH);
    }

    private TaskStatus approach(BotContext ctx) {
        if (!stillThere(ctx)) {
            return restart(ctx);
        }
        TaskStatus walk = approach.tick(ctx);
        if (walk == TaskStatus.FAILED) {
            approach.stop(ctx);
            approach = null;
            if (approachingStance) {
                // The spot beside it is blocked off. Moving the egg is the recovery: it is the one
                // thing this card can do that changes where the work has to happen.
                return walkTo(ctx, new Goals.Adjacent(egg, PUNCH_REACH), Phase.PUNCH, false);
            }
            return fail("lune.status.dragon_egg.cannot_reach", egg.getX(), egg.getY(), egg.getZ());
        }
        if (walk == TaskStatus.RUNNING) {
            status.set(approachingStance
                    ? "lune.status.dragon_egg.walking_beside"
                    : "lune.status.dragon_egg.walking_to_punch",
                    egg.getX(), egg.getY(), egg.getZ());
            return TaskStatus.RUNNING;
        }
        approach.stop(ctx);
        approach = null;
        return go(afterApproach);
    }

    // ---------------------------------------------------------------- moving it

    /** One deliberate left-click. Vanilla teleports the egg on the first click, before any break. */
    private TaskStatus punch(BotContext ctx) {
        if (!stillThere(ctx)) {
            return restart(ctx);
        }
        if (punches >= MAX_PUNCHES) {
            return fail("lune.status.dragon_egg.moved_too_often", punches);
        }
        if (breaker.isOutOfReach(ctx, egg)) {
            return walkTo(ctx, new Goals.Adjacent(egg, PUNCH_REACH), Phase.PUNCH, false);
        }
        Vec3 aim = Vision.blockAimPoint(ctx, egg);
        ctx.look.lookAt(ctx.player, aim);
        if (!ctx.look.isLookingAt(ctx.player, aim, AIM_TOLERANCE)) {
            status.set("lune.status.dragon_egg.knocking_off");
            return TaskStatus.RUNNING;
        }
        Vec3 eye = ctx.player.getEyePosition();
        Direction face = Direction.getApproximateNearest(
                eye.x - aim.x, eye.y - aim.y, eye.z - aim.z);
        ctx.gameMode.startDestroyBlock(egg, face);
        // Let go straight away. The teleport has already happened; holding the button down only
        // starts breaking whatever the egg was sitting on.
        ctx.gameMode.stopDestroyBlock();
        punches++;
        settleTicks = SETTLE_TICKS;
        // Where it was is the middle of where it now is: the teleport is centred on this block.
        lastSeen = egg;
        seekStops = 0;
        egg = null;
        stance = null;
        extraDepth = 0;
        status.set("lune.status.dragon_egg.knocking_off");
        return go(Phase.SETTLE);
    }

    /** The teleport is the server's; the client learns where the egg went a tick or two later. */
    private TaskStatus settle(BotContext ctx) {
        status.set("lune.status.dragon_egg.jumped");
        if (--settleTicks > 0) {
            return TaskStatus.RUNNING;
        }
        headScanner.reset(ctx.player);
        scanExhausted = false;
        return go(Phase.SEARCH);
    }

    // ---------------------------------------------------------------- the hole, the torch, the drop

    /** Digs straight down in the column beside the egg until the work is at head height. */
    private TaskStatus digStance(BotContext ctx) {
        if (!stillThere(ctx)) {
            return restart(ctx);
        }
        BlockPos feet = ctx.player.blockPosition();
        if (feet.getX() != stance.getX() || feet.getZ() != stance.getZ()) {
            // Slipped out of the column - off a ledge, or pushed. Ask for the walk again.
            return plan(ctx);
        }
        int wanted = egg.getY() - STANCE_DEPTH - extraDepth;
        if (feet.getY() <= wanted) {
            return go(Phase.POCKET);
        }
        status.set("lune.status.dragon_egg.digging_stance");
        // Straight down at its own feet: the one aim no ray can resolve onto the egg.
        return switch (breaker.tick(ctx, feet.below(), false, protectedBlocks())) {
            case FINISHED, WORKING -> TaskStatus.RUNNING;
            case NO_TOOL -> fail("lune.status.dragon_egg.nothing_to_dig_with");
            case HAZARD -> failFromBreaker(ctx);
        };
    }

    /** Clears the block the torch goes in, two under the egg. */
    private TaskStatus hollowPocket(BotContext ctx) {
        if (!stillThere(ctx)) {
            return restart(ctx);
        }
        BlockPos pocket = torchSpot(egg);
        if (BlockPlacer.isReplaceable(ctx, pocket)) {
            breaker.stop(ctx);
            return go(Phase.TORCH);
        }
        status.set("lune.status.dragon_egg.hollowing");
        // The block under the egg is protected as well as the egg: breaking it now would drop the
        // egg into a pocket that has no torch in it yet, and the whole job would start again.
        return switch (breaker.tick(ctx, pocket, false, protectedWhileHollowing())) {
            case FINISHED, WORKING -> TaskStatus.RUNNING;
            case NO_TOOL -> fail("lune.status.dragon_egg.nothing_to_dig_with");
            case HAZARD -> deeperOrFail(ctx);
        };
    }

    private TaskStatus placeTorch(BotContext ctx) {
        if (!stillThere(ctx)) {
            return restart(ctx);
        }
        BlockPos pocket = torchSpot(egg);
        Block torch = carriedTorch(ctx);
        if (torch == null) {
            return fail("lune.status.dragon_egg.no_torch");
        }
        status.set("lune.status.dragon_egg.placing_torch");
        return switch (BlockPlacer.tryPlace(ctx, torch, pocket)) {
            case PLACED, ALREADY_PRESENT -> go(Phase.DROP);
            case NO_MATERIAL -> fail("lune.status.dragon_egg.no_torch");
            // Nothing to hang it on, or something moved into the pocket. Either way this spot is
            // no good and the egg is better off somewhere else.
            case NO_SUPPORT, BLOCKED -> walkTo(ctx, new Goals.Adjacent(egg, PUNCH_REACH),
                    Phase.PUNCH, false);
            default -> TaskStatus.RUNNING;
        };
    }

    /** Breaks what the egg is standing on, so it falls the one block onto the torch. */
    private TaskStatus dropIt(BotContext ctx) {
        if (!stillThere(ctx)) {
            // Already gone: it fell while this phase was starting.
            return waitForTheFall();
        }
        BlockPos support = supportOf(egg);
        if (BlockPlacer.isReplaceable(ctx, support)) {
            breaker.stop(ctx);
            return waitForTheFall();
        }
        status.set("lune.status.dragon_egg.breaking_support");
        return switch (breaker.tick(ctx, support, false, protectedBlocks())) {
            case FINISHED -> waitForTheFall();
            case WORKING -> TaskStatus.RUNNING;
            case NO_TOOL -> fail("lune.status.dragon_egg.nothing_to_dig_with");
            case HAZARD -> deeperOrFail(ctx);
        };
    }

    private TaskStatus collect(BotContext ctx) {
        breaker.stop(ctx);
        if (settleTicks > 0) {
            settleTicks--;
            status.set("lune.status.dragon_egg.waiting_to_land");
            return TaskStatus.RUNNING;
        }
        if (sweep == null) {
            sweep = new LootTask(COLLECT_RADIUS, COLLECT_DEADLINE,
                    stack -> stack.is(Items.DRAGON_EGG));
            sweep.start(ctx);
        }
        status.set("lune.status.dragon_egg.collecting");
        if (sweep.tick(ctx) == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        sweep.stop(ctx);
        sweep = null;
        if (InventoryHelper.count(ctx.player, Items.DRAGON_EGG) > eggsAtStart) {
            // Picked up during the same tick the sweep finished in; the test at the top of onTick
            // ran before that happened.
            collected = true;
            status.set("lune.status.dragon_egg.collected");
            return TaskStatus.SUCCESS;
        }
        // The sweep ended with nothing in the bag. Either the egg is a block again somewhere - it
        // fell past the torch and settled - or the drop is gone.
        BlockPos again = BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                Set.of(Blocks.DRAGON_EGG), Math.min(radius, (int) Vision.maxRange(ctx)),
                ctx.player.blockPosition().getY() - radius,
                ctx.player.blockPosition().getY() + radius);
        if (again != null && restarts < MAX_RESTARTS) {
            restarts++;
            egg = again.immutable();
            extraDepth = 0;
            status.set("lune.status.dragon_egg.fell_further");
            return plan(ctx);
        }
        return fail("lune.status.dragon_egg.could_not_pick_up");
    }

    // ---------------------------------------------------------------- the rules, asked of the world

    /** Where the torch goes: two blocks under the egg, which is where the egg comes to rest. */
    static BlockPos torchSpot(BlockPos egg) {
        return egg.below(TORCH_DEPTH);
    }

    /** What the egg is standing on, and the last block to break. */
    static BlockPos supportOf(BlockPos egg) {
        return egg.below();
    }

    /**
     * Whether the egg can be dropped where it is standing.
     *
     * <p>Three things have to be true, and on the exit fountain none of them are: the block under
     * it has to be breakable, the pocket two under has to be breakable, and the floor under
     * <em>that</em> has to hold the egg up. A floor that does not - air, water, another drop - lets
     * the egg fall straight past the torch, which is a slower way of doing nothing.</p>
     */
    static boolean canExtractHere(BlockGetter level, BlockPos egg) {
        BlockPos support = supportOf(egg);
        BlockPos pocket = torchSpot(egg);
        return MovementHelper.isBreakable(level, support)
                && MovementHelper.isBreakable(level, pocket)
                && !FallingBlock.isFree(level.getBlockState(pocket.below()));
    }

    /**
     * The column beside the egg to dig down in, or null when there is none.
     *
     * <p>Only the four straight neighbours: a diagonal one puts a corner between the eye and the
     * work, and the ray finds the corner. The bot has to be able to stand there before it digs,
     * every block it digs through has to be breakable, and there has to be a floor at the bottom -
     * otherwise "dig down three" is a fall of unknown depth.</p>
     */
    static BlockPos stanceColumn(BlockGetter level, BlockPos egg, BlockPos from, int extraDepth) {
        int depth = STANCE_DEPTH + extraDepth;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos feet = egg.relative(direction);
            if (!MovementHelper.canStandAt(level, feet)) {
                continue;
            }
            boolean diggable = true;
            for (int below = 1; below <= depth; below++) {
                if (!MovementHelper.isBreakable(level, feet.below(below))) {
                    diggable = false;
                    break;
                }
            }
            if (!diggable || !MovementHelper.isSolidFloor(level, feet.below(depth + 1))) {
                continue;
            }
            double distance = feet.distSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = feet;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- plumbing

    /** The egg is never broken, so it is never a legal target for any swing this card makes. */
    private Set<Long> protectedBlocks() {
        return egg == null ? Set.of() : Set.of(egg.asLong());
    }

    private Set<Long> protectedWhileHollowing() {
        return egg == null ? Set.of() : Set.of(egg.asLong(), supportOf(egg).asLong());
    }

    private Block carriedTorch(BotContext ctx) {
        for (Block torch : TORCHES) {
            if (InventoryHelper.findSlot(ctx.player, stack -> stack.is(torch.asItem())) >= 0) {
                return torch;
            }
        }
        return null;
    }

    private boolean stillThere(BotContext ctx) {
        return egg != null && ctx.level.getBlockState(egg).is(Blocks.DRAGON_EGG);
    }

    /** The egg moved without being asked - another player, or a fall. Start from looking again. */
    private TaskStatus restart(BotContext ctx) {
        if (restarts++ >= MAX_RESTARTS) {
            return fail("lune.status.dragon_egg.moved_too_often", punches + restarts);
        }
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        breaker.stop(ctx);
        egg = null;
        stance = null;
        extraDepth = 0;
        headScanner.reset(ctx.player);
        scanExhausted = false;
        status.set("lune.status.dragon_egg.jumped");
        return go(Phase.SEARCH);
    }

    /**
     * A break refused because the ray resolved onto the egg or its support is a stance that is not
     * low enough, not a reason to stop. Anything else refused is the world saying no.
     */
    private TaskStatus deeperOrFail(BotContext ctx) {
        boolean aimBlocked = "lune.status.break.refusing_route_support"
                .equals(breaker.getFailureReason().key());
        if (aimBlocked && extraDepth == 0) {
            extraDepth = 1;
            breaker.stop(ctx);
            status.set("lune.status.dragon_egg.digging_stance");
            return go(Phase.STANCE);
        }
        return failFromBreaker(ctx);
    }

    private TaskStatus failFromBreaker(BotContext ctx) {
        breaker.stop(ctx);
        StatusText reason = breaker.getFailureReason();
        if (reason.key() == null || reason.key().isEmpty()) {
            return fail("lune.status.dragon_egg.gave_up", Lang.get(phaseKey()));
        }
        status.set(reason);
        return TaskStatus.FAILED;
    }

    /** What the card was in the middle of, for the line that says it ran out of patience. */
    private String phaseKey() {
        return switch (phase) {
            case SEARCH, SEEK, SETTLE -> "lune.status.dragon_egg.step.looking";
            case APPROACH -> "lune.status.dragon_egg.step.walking";
            case PUNCH -> "lune.status.dragon_egg.step.knocking";
            case STANCE, POCKET -> "lune.status.dragon_egg.step.digging";
            case TORCH -> "lune.status.dragon_egg.step.torch";
            case DROP -> "lune.status.dragon_egg.step.dropping";
            case COLLECT -> "lune.status.dragon_egg.step.collecting";
        };
    }

    /**
     * How long this card is willing to spend on the step it is on.
     *
     * <p>Only on its own work. Walking and sweeping are sub-tasks with give-up rules of their own,
     * and a walk across the island legitimately takes longer than any number written here - a
     * deadline over the top of one is a card that fails while the bot is still making progress.</p>
     */
    private int deadline() {
        return switch (phase) {
            case APPROACH, SEEK, COLLECT -> Integer.MAX_VALUE;
            default -> PHASE_DEADLINE;
        };
    }

    /** Into the pickup, but not before the egg has had time to hit the torch. */
    private TaskStatus waitForTheFall() {
        settleTicks = FALL_TICKS;
        return go(Phase.COLLECT);
    }

    private TaskStatus go(Phase next) {
        phase = next;
        phaseTicks = 0;
        return TaskStatus.RUNNING;
    }

    private TaskStatus fail(String key, Object... args) {
        status.set(key, args);
        return TaskStatus.FAILED;
    }

    @Override
    public void onStop(BotContext ctx) {
        breaker.stop(ctx);
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        if (sweep != null) {
            sweep.stop(ctx);
            sweep = null;
        }
        ctx.input.reset();
    }
}
