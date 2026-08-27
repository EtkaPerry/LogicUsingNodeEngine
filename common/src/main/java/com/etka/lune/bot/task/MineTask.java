package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.learning.LearningStore;
import com.etka.lune.bot.learning.SkillOutcome;
import com.etka.lune.bot.memory.BlockMemory;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.knowledge.OreKnowledge;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockBreaker;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.TargetIndex;
import com.etka.lune.bot.util.ToolSelector;
import com.etka.lune.bot.util.Vision;
import com.etka.lune.bot.util.WorkSite;
import com.etka.lune.platform.BuildFeatures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds the nearest matching block, walks into reach of it, and mines it - then repeats until
 * nothing is left within the search radius.
 * <p>
 * Blocks the pathfinder cannot get to (sealed in stone, across a ravine) are blacklisted after a
 * failed approach so the task moves on instead of retargeting the same unreachable ore forever.
 */
public final class MineTask implements Task {

    /** Vanilla block interaction range. */
    private static final double REACH = 4.5;
    /**
     * Trees are approached before swinging instead of being punched from maximum interaction
     * range. Adjacent and diagonal-adjacent block centres both fit inside this distance.
     */
    private static final double TREE_HORIZONTAL_REACH = 1.75;
    /** Horizontal drift in water that has to be corrected before it costs the bot its target. */
    private static final double BRACE_DISTANCE = 1.2;
    /** Shared wording so a tool failure can be recognised when it bubbles up from an approach. */
    static final String NO_TOOL_PREFIX = "need a better tool";
    /** A dropped log is normally picked up quickly; do not let one bad drop stall the mission. */
    private static final int MAX_SWEEP_TICKS = 100;
    /** A single visible block must not consume the whole mining/search deadline. */
    private static final int MAX_TARGET_WORK_TICKS = 180;
    /**
     * A physical prospect may use several short stair segments, but it must still leave a barren
     * pocket in a bounded amount of game time.  Without a total budget, each completed segment
     * reset the local progress counter and a hidden ore hint could pull the bot through an entire
     * underground region while never exposing a visible target.
     */
    private static final int MAX_PROSPECT_TICKS = 2400;
    /**
     * A stair segment that ends this quickly was refused by the terrain, not tried.
     * <p>
     * Every reason {@link StaircaseProspectTask} can bail out on its first tick - no stable ground,
     * unbreakable face, gravel overhead, water in the column, no floor under the next step - is a
     * property of one direction, and rotating to the next direction is the right response. Charging
     * a full attempt for it is not: four instant refusals on a slope spend the entire prospecting
     * budget in four ticks and hand the mission back to a surface walk that has already failed. The
     * observed run did exactly that, then found iron within 140 ticks the one time a stair was
     * allowed to dig.
     */
    private static final int PROSPECT_REJECT_TICKS = 20;
    /** Allow a server a few ticks to spawn the item entity before writing the drop off. */
    private static final int DROP_APPEAR_GRACE_TICKS = 8;
    /** Pickup attempts after a tree break should be shorter than an explicit Loot command. */
    private static final int TREE_DROP_ATTEMPT_DEADLINE = 80;
    /** Let a decision remain visible instead of replacing it with another scan one tick later. */
    private static final int DECISION_TICKS = 12;
    /** How far the bot must move before a look around counts as a look from somewhere new. */
    private static final int RESCAN_DISTANCE = 3;
    /** Two seconds between "is there somewhere better now" checks during a long approach. */
    private static final int RECONSIDER_INTERVAL_TICKS = 40;
    /** How far to look for an open cave before committing to digging a staircase. */
    private static final int CAVE_SEARCH_RADIUS = 24;
    /** How far below to consider; a cave mouth above the bot is usually just the hill it is on. */
    private static final int CAVE_SEARCH_DEPTH = 12;
    /** How close a spawner makes an opening not worth entering; a cave spider room is about this. */
    private static final int SPAWNER_GUARD_RADIUS = 12;
    /** Passable blocks in a 3x3x3 that mean "walk-in space", matching the staircase's own test. */
    private static final int CAVE_AIR_THRESHOLD = 12;
    /** Tallest trunk worth following down; a giant spruce is around 30 logs. */
    private static final int MAX_TRUNK_SCAN = 32;
    /** Cap on the connected-log walk, so a forest grown together cannot become one huge search. */
    private static final int MAX_TREE_BLOCKS = 512;

    private final Set<Block> targets;
    private final int radius;
    private final int yMin;
    private final int yMax;

    private final Set<Long> unreachable = new HashSet<>();
    private final BlockBreaker breaker = new BlockBreaker();
    private BlockPos target;
    private GotoTask approach;
    private boolean breaking;
    private int mined;
    private int broken;
    private String status = "";
    private int decisionTicks;
    private BlockPos workTarget;
    private int targetWorkTicks;

    /** Stop after this many blocks. 0 means keep going until the radius is exhausted. */
    private final int limit;
    /** Go and craft a tool when the target needs one we lack, instead of failing. */
    private final boolean autoTool;
    /** When no visible target remains, walk to a new spot and keep searching. */
    private final boolean prospect;
    /** Wood gathering commits to the current tree before the numeric limit may end the task. */
    private final boolean finishCurrentTree;
    /** Optional full-circle fallback after the current view contains no matching block. */
    private final boolean checkAround;
    private BlockPos treeAnchor;
    /** The trunk foot, held back until everything above it is gone. */
    private BlockPos deferredStump;
    private Block treeType;
    /** Connected logs discovered when the visible tree was selected; used only to keep the commit local. */
    private final Set<Long> currentTreeLogs = new HashSet<>();
    private int treeLookY;
    private boolean treeViewPending;
    /** Per-tree learned tactic; deliberately independent of the parent mission or route. */
    private LearningStore.SkillChoice treeSkillChoice;
    private String observedTreeStrategy = TreeChoppingPolicy.DEFAULT;
    private int taskTicks;
    private int treeStrategyStartedTick;
    private int treeStrategyMinedStart;
    private int treeExpectedLogs;
    private int treeMaxHorizontalRadiusSquared;

    /** If true, fall back to remembered blocks when no visible target is in range. */
    private final boolean useMemory;
    /** True when the current target was recalled from {@link BlockMemory} rather than seen right now. */
    private boolean memoryTarget;
    /** A visible wall block being opened because it is the first blocker in front of a target. */
    private boolean exposingBlocker;
    private BlockState exposureBlockState;
    private BlockPos exposureOre;
    private int exposureAttempts;
    /** A visible head-level block above a mined wall block, needed for a walkable passage. */
    private BlockPos passageTarget;
    private BlockState passageBlockState;
    private BlockPos passageAdvance;
    private GotoTask passageApproach;
    private boolean clearingPassage;
    /** A previously seen target used only as an approach point for a fresh local scan. */
    private BlockPos hintedScanCentre;
    private GotoTask hintApproach;
    private EnsureToolTask toolTask;
    /** How far to chase the drops from a block we just broke. */
    private static final int COLLECT_RADIUS = 6;
    private LootTask sweeper;
    /**
     * Drops this task has already failed to reach, shared into every rebuilt sweeper. Without it a
     * recreated sweep forgets and re-chases the same wedged item forever.
     */
    private final Set<Integer> sweepWrittenOff = new HashSet<>();
    private boolean sweepPending;
    /** Whether the current drop sweep is for a requested target rather than route clearance. */
    private boolean sweepCountsTarget;
    private int sweepTicks;
    private int emptySweepTicks;
    private int inventoryBeforeBreak;
    /** Targets supplied by a parent task that remembers previous failed gathering attempts. */
    private final Set<Long> seededUnreachable = new HashSet<>();

    /** Prospecting state: descend through safe stairs to expose underground blocks. */
    private static final int DEFAULT_PROSPECT_MAX_ATTEMPTS = 8;
    private static final int DEFAULT_PROSPECT_STAIR_STEPS = 6;
    /** A full look-around must end in a decision, even if the camera cannot settle normally. */
    private static final int MAX_AREA_SCAN_TICKS = 240;
    /**
     * Faces the bot may open toward ore it can nearly see, per mining task.
     * <p>
     * Three was too few to be useful anywhere it mattered. In a cave - which is now where the iron
     * phase deliberately goes - the ore is routinely one or two blocks behind the wall you are
     * standing at, and a player simply mines the face. With the old cap the bot exhausted three
     * exposures, then spent the rest of the task reporting "no actionable target" at ore it knew
     * was there: a measured run stood still for 5,427 ticks doing exactly that, at Y40, with
     * `blocked=target=occluded` on 4,819 of them, and finished with iron 0/4.
     * <p>
     * Still bounded, and each exposure is still a single visible, safe, non-falling block within
     * twelve - this widens a cave wall, it does not authorise a tunnel.
     */
    private static final int MAX_VISIBLE_BLOCK_EXPOSURES = 16;
    /**
     * How much rock may sit between the blocker and the ore for opening it to be worth doing.
     *
     * <p>{@code sight.blocker()} is the *first* block the ray meets, not the last one before the
     * ore. With ore behind ten blocks of stone, opening that first block reveals more stone, the
     * ore stays occluded, and the next tick picks the same ore and the next block along - which is
     * how a run spends its whole exposure budget and then reports "budget spent" at ore it never
     * got near. One block in the way is a wall face worth breaking; ten is a tunnel, and a tunnel
     * is the staircase prospect's job.
     */
    private static final int MAX_BLOCKER_TO_ORE = 2;
    /** How far a blocker can be and still be worth walking over to open. */
    private static final int MAX_EXPOSURE_DISTANCE = 12;
    private final int prospectMaxAttempts;
    private final int prospectStairSteps;
    private Task prospectTask;
    private int prospectMinedSinceStart;
    private final Set<Long> prospectProtectedRoute = new HashSet<>();
    private int prospectAttempts;
    private int prospectTicks;
    private Direction prospectBaseDirection;
    /** Directions the terrain refused without any digging, and where that was measured. */
    private final EnumSet<Direction> prospectRejected = EnumSet.noneOf(Direction.class);
    private BlockPos prospectRejectAnchor;
    /** Direction, age and progress of the segment currently being dug. */
    private Direction prospectDirection;
    private int prospectSegmentTicks;
    private int prospectStepsSinceStart;
    /** One look for an already-open cave per pocket, before any digging is considered. */
    private boolean caveEntryTried;
    /** Ticks until the committed target is checked against whatever has since loaded. */
    private int reconsiderTicks = RECONSIDER_INTERVAL_TICKS;
    private BlockPos scanCentre;
    private int scanTicks;
    private final HeadScanner headScanner = new HeadScanner();
    /** A narrow-then-wide local scan used to finish branches of the already committed tree. */
    private final HeadScanner treeScanner = new HeadScanner(HeadScanner.Style.GLANCE);
    private final TargetIndex index = new TargetIndex();
    /** Keeps digging local to the last block broken, instead of jumping between two faces. */
    private final WorkSite site = new WorkSite();
    /** Learned ordering for ordinary mining; tree felling owns its finer per-tree episode below. */
    private String miningStrategy = MiningPolicy.DEFAULT;

    public MineTask(Set<Block> targets, int radius, int yMin, int yMax) {
        this(targets, radius, yMin, yMax, 0, true, false);
    }

    public MineTask(Set<Block> targets, int radius, int yMin, int yMax, int limit) {
        this(targets, radius, yMin, yMax, limit, true, false);
    }

    public MineTask(Set<Block> targets, int radius, int yMin, int yMax, int limit, boolean autoTool) {
        this(targets, radius, yMin, yMax, limit, autoTool, false);
    }

    public MineTask(Set<Block> targets, int radius, int yMin, int yMax, int limit, boolean autoTool, boolean prospect) {
        this(targets, radius, yMin, yMax, limit, autoTool, prospect, false);
    }

    public MineTask(Set<Block> targets, int radius, int yMin, int yMax, int limit,
                    boolean autoTool, boolean prospect, boolean finishCurrentTree) {
        this(targets, radius, yMin, yMax, limit, autoTool, prospect, finishCurrentTree, false);
    }

    public MineTask(Set<Block> targets, int radius, int yMin, int yMax, int limit,
                    boolean autoTool, boolean prospect, boolean finishCurrentTree,
                    boolean checkAround) {
        this(targets, radius, yMin, yMax, limit, autoTool, prospect, finishCurrentTree,
                checkAround, DEFAULT_PROSPECT_STAIR_STEPS, DEFAULT_PROSPECT_MAX_ATTEMPTS);
    }

    public MineTask(Set<Block> targets, int radius, int yMin, int yMax, int limit,
                    boolean autoTool, boolean prospect, boolean finishCurrentTree,
                    boolean checkAround, int prospectStairSteps) {
        this(targets, radius, yMin, yMax, limit, autoTool, prospect, finishCurrentTree,
                checkAround, prospectStairSteps, DEFAULT_PROSPECT_MAX_ATTEMPTS);
    }

    public MineTask(Set<Block> targets, int radius, int yMin, int yMax, int limit,
                    boolean autoTool, boolean prospect, boolean finishCurrentTree,
                    boolean checkAround, int prospectStairSteps, int prospectMaxAttempts) {
        this.autoTool = autoTool;
        this.prospect = prospect;
        this.finishCurrentTree = finishCurrentTree;
        this.checkAround = checkAround;
        this.useMemory = true;
        this.targets = Set.copyOf(targets);
        this.radius = radius;
        this.yMin = Math.min(yMin, yMax);
        this.yMax = Math.max(yMin, yMax);
        this.limit = Math.max(0, limit);
        this.prospectStairSteps = Math.max(1, prospectStairSteps);
        this.prospectMaxAttempts = Math.max(1, prospectMaxAttempts);
    }

    @Override
    public String name() {
        return "Mine";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public boolean automaticSkillLearning() {
        return !finishCurrentTree;
    }

    @Override
    public LearningContext learningContext(BotContext ctx) {
        String dimension = ctx.level.dimension().identifier().toString();
        String phase = "targets=" + targetKey()
                + ";amount=" + MiningPolicy.amountBucket(limit)
                + ";radius=" + MiningPolicy.radiusBucket(radius)
                + ";prospect=" + prospect;
        return new LearningContext("skill", "block-mining", dimension, phase);
    }

    @Override
    public List<String> learningActions(BotContext ctx) {
        return MiningPolicy.ACTIONS;
    }

    @Override
    public void onLearningAction(BotContext ctx, String action) {
        miningStrategy = MiningPolicy.ACTIONS.contains(action) ? action : MiningPolicy.DEFAULT;
    }

    @Override
    public TaskProgress progress() {
        return limit <= 0 ? null : new TaskProgress(mined, limit,
                finishCurrentTree ? "logs" : "blocks");
    }

    @Override
    public TaskProgress learningProgress() {
        return new TaskProgress(mined, limit <= 0 ? Math.max(1, mined) : limit,
                finishCurrentTree ? "logs" : "blocks");
    }

    @Override
    public boolean madeProgress() {
        return broken > 0;
    }

    /**
     * How many of the blocks this task was actually sent for it got.
     *
     * <p>Distinct from {@link #madeProgress()}, which counts any block broken at all and is right
     * for telemetry. It is wrong for deciding whether to stay put: a prospecting staircase breaks
     * dozens of stone blocks on its way past no iron whatsoever, so "broke something" is true for
     * a search that achieved nothing. The iron phase read it as a reason not to relocate and spent
     * thousands of ticks rebuilding the same search on the same spot without moving.
     */
    public int minedTargets() {
        return mined;
    }

    /** Carry local target failures into a follow-up mining attempt in the same larger mission. */
    public MineTask avoiding(Set<Long> positions) {
        seededUnreachable.addAll(positions);
        return this;
    }

    /**
     * Walk back to a block the caller saw before starting this bounded physical prospect. The
     * block is never mined from memory: once nearby, the normal Vision filter and blocker-opening
     * path decide what can actually be acted on.
     */
    public MineTask nearHint(BlockPos position) {
        hintedScanCentre = position == null ? null : position.immutable();
        return this;
    }

    /** Snapshot of targets this run decided were not worth trying again. */
    public Set<Long> unreachableTargets() {
        return Set.copyOf(unreachable);
    }

    @Override
    public void onStart(BotContext ctx) {
        cancelTreeSkill(ctx);
        mined = 0;
        broken = 0;
        miningStrategy = MiningPolicy.DEFAULT;
        taskTicks = 0;
        unreachable.clear();
        unreachable.addAll(BlockMemory.get().getUnreachable());
        unreachable.addAll(seededUnreachable);
        prospectAttempts = 0;
        prospectTicks = 0;
        prospectProtectedRoute.clear();
        prospectBaseDirection = ctx.player.getDirection();
        prospectRejected.clear();
        prospectRejectAnchor = null;
        caveEntryTried = false;
        stopProspect(ctx);
        target = null;
        treeAnchor = null;
        deferredStump = null;
        treeType = null;
        currentTreeLogs.clear();
        treeViewPending = false;
        treeSkillChoice = null;
        observedTreeStrategy = TreeChoppingPolicy.DEFAULT;
        treeStrategyStartedTick = 0;
        treeStrategyMinedStart = 0;
        treeExpectedLogs = 0;
        treeMaxHorizontalRadiusSquared = 0;
        memoryTarget = false;
        hintApproach = hintedScanCentre == null
                || new Goals.Near(hintedScanCentre, 2).isReached(ctx.player.blockPosition())
                ? null
                : new GotoTask(new Goals.Near(hintedScanCentre, 2), true, false, false);
        exposingBlocker = false;
        exposureBlockState = null;
        exposureOre = null;
        exposureAttempts = 0;
        passageTarget = null;
        passageBlockState = null;
        passageAdvance = null;
        passageApproach = null;
        clearingPassage = false;
        sweepPending = false;
        sweepCountsTarget = false;
        sweepTicks = 0;
        emptySweepTicks = 0;
        inventoryBeforeBreak = InventoryHelper.totalItemCount(ctx.player);
        decisionTicks = 0;
        workTarget = null;
        targetWorkTicks = 0;
        resetScan(ctx);
        ctx.debug.intent = "looking for visible " + targetNames();
        ctx.debug.giveUp = "mine " + (limit <= 0 ? "until none remain" : "up to " + limit)
                + "; prospect " + prospectAttempts + "/" + prospectMaxAttempts;
        ctx.debug.decide("scan current view before moving or prospecting");
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        taskTicks++;
        syncRejectedTreeStrategy(ctx);
        ctx.debug.intent = target == null ? "looking for visible " + targetNames()
                : "working on the selected " + targetNames() + " block";
        ctx.debug.giveUp = "mine " + (limit <= 0 ? "until none remain" : mined + "/" + limit)
                + "; prospect " + prospectAttempts + "/" + prospectMaxAttempts;
        if (targets.isEmpty()) {
            status = "no blocks selected";
            return TaskStatus.FAILED;
        }

        if (hintApproach != null) {
            TaskStatus result = hintApproach.tick(ctx);
            status = "returning to the remembered ore pocket - " + hintApproach.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            hintApproach.stop(ctx);
            hintApproach = null;
            hintedScanCentre = null;
            if (result == TaskStatus.FAILED) {
                status = "could not return to the remembered ore pocket; prospecting here";
            } else {
                resetScan(ctx);
                status = "returned to the remembered ore pocket; rescanning the visible wall";
            }
        }

        if (decisionTicks > 0) {
            decisionTicks--;
            return TaskStatus.RUNNING;
        }

        // The target is gone - either we just broke it, or the world changed under us. An exposed
        // blocker and head-clearance blocks are deliberately not requested targets, so neither may
        // increment the mined counter or make an iron vein look complete after its wall is opened.
        BlockState currentTargetState = target == null ? null : ctx.level.getBlockState(target);
        boolean targetGone = target != null && (clearingPassage
                ? passageBlockState != null
                        && currentTargetState.getBlock() != passageBlockState.getBlock()
                : exposingBlocker
                        ? exposureBlockState != null
                                && currentTargetState.getBlock() != exposureBlockState.getBlock()
                        : !targets.contains(currentTargetState.getBlock()));
        if (targetGone) {
            boolean openedBlocker = exposingBlocker;
            boolean openedPassage = clearingPassage;
            BlockPos finished = target.immutable();
            if (breaking && !openedBlocker && !openedPassage) {
                // Do not count a block until its drop has either been picked up automatically or
                // collected by the bounded sweep below. This is important for Get Tools: a log
                // disappearing is not the same thing as the bot actually receiving the log.
                broken++;
            }
            // Rank the next candidate from the block just broken, so mining works outward through
            // the pocket it is standing in instead of jumping to whatever is nearest to the player
            // after every step.
            site.workedAt(finished);
            if (!openedBlocker && !openedPassage && finishCurrentTree && treeAnchor != null) {
                treeLookY = Math.max(treeLookY, finished.getY() + 1);
                treeViewPending = true;
            }
            if (!openedPassage) {
                // The same two-high opening is useful when the first visible wall block was a
                // non-target blocker on the way to an ore. Its drop still remains uncounted below.
                planTwoHighPassage(ctx, finished);
            }
            stopBreaking(ctx);
            clearTarget(ctx);
            if (openedBlocker) {
                status = "opened visible " + exposureBlockState.getBlock().getName().getString()
                        + " toward " + (exposureOre == null ? "the ore" : "the visible ore");
                exposingBlocker = false;
                exposureBlockState = null;
                exposureOre = null;
            }
            if (openedPassage) {
                status = "cleared the head of the two-block-high opening";
                clearingPassage = false;
                passageBlockState = null;
                passageTarget = null;
                startPassageAdvance(ctx);
            }
            sweepCountsTarget = !openedBlocker && !openedPassage;
            sweepPending = true;
            sweepTicks = 0;
            emptySweepTicks = 0;
        }

        // Collect what was just broken before moving on. Blocks are mined from up to 4.5 blocks
        // away, so drops land well outside pickup range - a bot that skips this mines all day and
        // gathers nothing, and recipes that unlock on pickup never unlock at all.
        if (sweepPending) {
            TaskStatus sweep = collectDrops(ctx);
            if (sweep == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            sweepPending = false;
            if (limit > 0 && mined >= limit && passageTarget == null
                    && passageAdvance == null && passageApproach == null
                    && (!finishCurrentTree || treeAnchor == null)) {
                status = "mined " + mined;
                return TaskStatus.SUCCESS;
            }
        }

        // A one-block opening is not a corridor. Step into the cleared column before scanning so
        // the next view contains the blocks around the work site, not just the face outside it.
        if (passageApproach != null) {
            TaskStatus result = passageApproach.tick(ctx);
            status = "entering the two-block-high opening - " + passageApproach.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            passageApproach.stop(ctx);
            passageApproach = null;
            if (result == TaskStatus.SUCCESS) {
                resetScan(ctx);
                status = "inside the opening; checking the surrounding blocks";
            } else {
                status = "could not enter the opening; checking from this side";
            }
        }

        // The head clearance is deliberately handled as a non-target. It is only selected after
        // the feet block was actually mined, so this never turns the target index into permission
        // to tunnel through hidden rock.
        if (target == null && passageTarget != null) {
            BlockState state = ctx.level.getBlockState(passageTarget);
            if (passageBlockState != null
                    && state.getBlock() == passageBlockState.getBlock()
                    && !MovementHelper.isPassable(ctx.level, passageTarget)) {
                target = passageTarget;
                clearingPassage = true;
                memoryTarget = false;
                status = "clearing the head of the two-block-high opening";
            } else {
                passageTarget = null;
                passageBlockState = null;
                startPassageAdvance(ctx);
            }
        }

        if (target == null && passageTarget == null && passageApproach == null
                && passageAdvance != null) {
            startPassageAdvance(ctx);
        }
        if (passageApproach != null) {
            status = "preparing to enter the two-block-high opening";
            return TaskStatus.RUNNING;
        }

        if (limit > 0 && mined >= limit && passageTarget == null
                && passageAdvance == null && passageApproach == null
                && target == null && (!finishCurrentTree || treeAnchor == null)) {
            status = "mined " + mined;
            return TaskStatus.SUCCESS;
        }

        if (target == null) {
            boolean scanComplete = findTarget(ctx);
            if (target == null) {
                if (!scanComplete) {
                    return TaskStatus.RUNNING;
                }
                if (finishCurrentTree && treeAnchor != null) {
                    // No more visible/reachable logs belonging to this tree. Only now may the
                    // requested count finish the wood-gathering step; otherwise choose one new
                    // tree and commit to that one in the same way.
                    finishTreeSkill(ctx, true);
                    treeAnchor = null;
                    deferredStump = null;
                    treeType = null;
                    treeViewPending = false;
                    resetScan(ctx);
                    if (limit > 0 && mined >= limit) {
                        status = "felled one tree (" + mined + " logs)";
                        return TaskStatus.SUCCESS;
                    }
                    status = "tree finished, looking for another";
                    return TaskStatus.RUNNING;
                }
                // If a previous find/scan already remembered a target, use it. Only start digging for
                // new ore when there is nothing remembered and prospecting is explicitly enabled.
                if (useMemory) {
                    int memoryRadius = Math.max(radius, 64);
                    BlockPos memory = BlockMemory.get().findNearest(ctx, ctx.player.blockPosition(),
                            targets, memoryRadius);
                    // A remembered block may be walked back to even though it is no longer in
                    // sight. Nothing is remembered that was not seen first - every caller of
                    // BlockMemory.remember records a position that has just passed the visibility
                    // test - so going back to one is not X-ray, it is the thing a person does after
                    // spotting ore in a cliff face and walking round to it.
                    //
                    // Requiring it to *still* be visible was the same as not remembering at all.
                    // Ore goes out of sight as soon as the approach changes the angle, so the bot
                    // spotted iron, started walking, lost the line of sight and dropped it - with
                    // twenty perfectly good positions in memory and "none currently selected" on
                    // screen while it stood in a tunnel looking for something else to see.
                    if (memory != null) {
                        target = memory;
                        memoryTarget = true;
                        status = "returning to remembered "
                                + ctx.level.getBlockState(memory).getBlock().getName().getString();
                        return TaskStatus.RUNNING;
                    }
                }
                if (prospect) {
                    ctx.debug.nextDecision = "no actionable target; expose a bounded staircase segment";
                    // A prospecting stair breaks ordinary stone before it knows which ore the
                    // newly opened wall contains. That is fine with a wooden pick for stone, but
                    // it is not fine when the requested block is iron (or diamond): the pick can
                    // break the stair and then silently destroy the ore drop. Ensure the tool
                    // for the actual mining target before starting another physical prospect, so
                    // a long stair cannot outlive the only suitable pickaxe and fall back to wood.
                    TaskStatus prospectTool = ensureProspectTool(ctx);
                    if (prospectTool != null) {
                        return prospectTool;
                    }
                    return prospect(ctx);
                }
                status = "mined " + mined + ", nothing visible within " + radius + " blocks";
                if (ctx.omniscientMining()) {
                    status = "mined " + mined + ", nothing left within " + radius + " blocks";
                }
                ctx.debug.decide("no visible target remains; finish this mining step");
                return TaskStatus.SUCCESS;
            }
        }

        // A prospecting stair is only an exploration aid. The moment it exposes a valid block,
        // abandon the remainder of that segment and handle the actual Mine target.
        if (prospectTask != null) {
            stopProspect(ctx);
        }

        // Keep the committed block visible while the shared GotoTask brings us into reach. The
        // action marker is cleared at the start of every engine tick, so publishing only from the
        // actual swing made the intended ore disappear during the approach.
        ctx.debug.target("mine " + ctx.level.getBlockState(target).getBlock().getName().getString(),
                target, ctx.omniscientMining() ? "omniscient mode" : Vision.inspect(ctx, target).verdict());

        // Checked before walking anywhere: without the right pickaxe the block would be destroyed
        // with no drop, and there is no point crossing thirty blocks to find that out.
        if (!ToolSelector.canHarvest(ctx.player, ctx.level.getBlockState(target))) {
            if (!autoTool) {
                status = NO_TOOL_PREFIX + " to mine "
                        + ctx.level.getBlockState(target).getBlock().getName().getString();
                return TaskStatus.FAILED;
            }
            return acquireTool(ctx);
        }
        if (toolTask != null) {
            toolTask.stop(ctx);
            toolTask = null;
        }

        Vec3 centre = Vec3.atCenterOf(target);
        boolean inReach = ctx.player.getEyePosition().distanceToSqr(centre) <= REACH * REACH;
        // The target was selected from the old scan position.  A route can legitimately bring us
        // around a corner, under a canopy, or onto the other side of a wall; in that case the
        // block is no longer an actionable face even though its coordinates are unchanged.  Do
        // not let BlockBreaker keep attacking the first leaf/stone in front of it forever.  The
        // target is blacklisted for this bounded search and the normal visible scan gets a chance
        // to choose another face/tree from where we actually are.  Use reachability rather than
        // visibility here because the bot is allowed to turn toward a target after arriving.
        if (inReach && !ctx.omniscientMining() && !Vision.isReachable(ctx, target)) {
            String reason = Vision.inspect(ctx, target).verdict();
            unreachable.add(target.asLong());
            BlockMemory.get().markUnreachable(target);
            stopBreaking(ctx);
            if (exposingBlocker) {
                exposingBlocker = false;
                exposureBlockState = null;
                exposureOre = null;
            }
            if (clearingPassage) {
                clearPassagePlan();
            }
            clearTarget(ctx);
            status = "selected block is no longer reachable after approach (" + reason
                    + "); rescanning from here";
            decisionTicks = DECISION_TICKS;
            return TaskStatus.RUNNING;
        }
        // Once the swing is under way, tolerate more drift than it took to start. A current nudges
        // the bot a fraction of a block out of the tight tree tolerance, and abandoning the block to
        // walk one step back restarts the break from zero - which is how a log gets attacked ten
        // times and still reports none done.
        double treeReach = breaking ? TREE_HORIZONTAL_REACH * 2.0 : TREE_HORIZONTAL_REACH;
        boolean closeToTree = !finishCurrentTree || isHorizontallyCloseToLog(
                ctx.player.getX(), ctx.player.getZ(), target, treeReach);
        if (inReach && closeToTree) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
            braceAgainstCurrent(ctx, target);
            return breakTarget(ctx, centre);
        }

        return walkToTarget(ctx);
    }

    /**
     * Goes and makes a tool good enough for the current target, then resumes mining. Suspends the
     * approach so the bot isn't holding a stale route across a wood-gathering detour.
     */
    private TaskStatus acquireTool(BotContext ctx) {
        stopBreaking(ctx);
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        if (toolTask == null) {
            toolTask = new EnsureToolTask(ctx.level.getBlockState(target).getBlock(), checkAround);
            toolTask.start(ctx);
        }
        TaskStatus result = toolTask.tick(ctx);
        status = "getting a tool - " + toolTask.status();
        if (result == TaskStatus.FAILED) {
            status = toolTask.status();
            return TaskStatus.FAILED;
        }
        if (result == TaskStatus.SUCCESS) {
            toolTask.stop(ctx);
            toolTask = null;
        }
        return TaskStatus.RUNNING;
    }

    /**
     * Prospecting has no selected target yet, so the normal target-tool guard below cannot run.
     * Pick the first requested block that needs a correct tool and keep that dependency alive
     * across stair segments. This is shared by every prospecting MineTask, not just speedrun iron.
     */
    private TaskStatus ensureProspectTool(BotContext ctx) {
        if (!autoTool) {
            return null;
        }
        Block required = targets.stream()
                .filter(block -> block.defaultBlockState().requiresCorrectToolForDrops())
                .findFirst()
                .orElse(null);
        if (required == null || ToolSelector.canHarvest(ctx.player, required.defaultBlockState())) {
            if (toolTask != null) {
                toolTask.stop(ctx);
                toolTask = null;
            }
            return null;
        }

        stopBreaking(ctx);
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        if (toolTask == null) {
            toolTask = new EnsureToolTask(required, checkAround);
            toolTask.start(ctx);
        }
        TaskStatus result = toolTask.tick(ctx);
        status = "getting a prospecting tool - " + toolTask.status();
        if (result == TaskStatus.FAILED) {
            status = toolTask.status();
            return TaskStatus.FAILED;
        }
        if (result == TaskStatus.SUCCESS) {
            toolTask.stop(ctx);
            toolTask = null;
        }
        return TaskStatus.RUNNING;
    }

    /** Runs a short pickup sweep. Returns RUNNING while still collecting. */
    private TaskStatus collectDrops(BotContext ctx) {
        // Reused rather than recreated per sweep: the sweeper remembers drops it couldn't reach,
        // and a fresh one each time would throw that away and re-chase the same item after every
        // single block. It returns SUCCESS immediately when there's nothing to collect.
        if (sweeper == null) {
            sweeper = new LootTask(COLLECT_RADIUS,
                    finishCurrentTree ? TREE_DROP_ATTEMPT_DEADLINE : 120,
                    stack -> true, sweepWrittenOff);
            sweeper.start(ctx);
        }
        sweepTicks++;
        TaskStatus result = sweeper.tick(ctx);
        if (result == TaskStatus.RUNNING && sweepTicks <= MAX_SWEEP_TICKS) {
            status = "collecting drops";
            return TaskStatus.RUNNING;
        }

        boolean collected = InventoryHelper.totalItemCount(ctx.player) > inventoryBeforeBreak;
        if (!collected && result != TaskStatus.RUNNING && emptySweepTicks++ < DROP_APPEAR_GRACE_TICKS) {
            // A block update and its item entity do not always arrive in the same client tick.
            // Retry a few short scans before deciding the drop is genuinely lost.
            sweeper.stop(ctx);
            sweeper = null;
            status = "checking whether the drop landed";
            return TaskStatus.RUNNING;
        }

        if (collected) {
            if (sweepCountsTarget) {
                mined++;
            }
            status = "collected the drop";
        } else {
            if (sweepTicks > MAX_SWEEP_TICKS) {
                status = "drop took too long, leaving it";
            } else {
                status = "couldn't collect the drop, moving on";
            }
            if (finishCurrentTree && treeAnchor != null) {
                abandonCurrentTree(ctx, "that tree's drop isn't worth fighting for; leaving it");
            }
        }
        sweepCountsTarget = false;
        sweepTicks = 0;
        emptySweepTicks = 0;
        return TaskStatus.SUCCESS;
    }

    private TaskStatus breakTarget(BotContext ctx, Vec3 centre) {
        String name = ctx.level.getBlockState(target).getBlock().getName().getString();
        if (!target.equals(workTarget)) {
            workTarget = target.immutable();
            targetWorkTicks = 0;
        }
        targetWorkTicks++;
        if (targetWorkTicks > MAX_TARGET_WORK_TICKS) {
            BlockPos failed = exposingBlocker && exposureOre != null ? exposureOre : target;
            unreachable.add(failed.asLong());
            BlockMemory.get().markUnreachable(failed);
            stopBreaking(ctx);
            if (clearingPassage) {
                clearPassagePlan();
            }
            clearTarget(ctx);
            status = "block work stalled; abandoning this visible pocket and rescanning";
            decisionTicks = DECISION_TICKS;
            return TaskStatus.RUNNING;
        }
        if (!breaking) {
            inventoryBeforeBreak = InventoryHelper.totalItemCount(ctx.player);
        }
        BlockState toolState = exposingBlocker && exposureOre != null
                ? ctx.level.getBlockState(exposureOre)
                : ctx.level.getBlockState(target);
        BlockBreaker.Progress progress = breaker.tick(ctx, target, true, prospectProtectedRoute, toolState);
        if (progress == BlockBreaker.Progress.NO_TOOL) {
            status = NO_TOOL_PREFIX + " to mine " + name;
            return TaskStatus.FAILED;
        }
        if (progress == BlockBreaker.Progress.HAZARD) {
            status = breaker.getFailureReason();
            unreachable.add(target.asLong());
            BlockMemory.get().markUnreachable(target);
            stopBreaking(ctx);
            if (clearingPassage) {
                clearPassagePlan();
            }
            clearTarget(ctx);
            workTarget = null;
            targetWorkTicks = 0;
            if (finishCurrentTree && treeAnchor != null) {
                abandonCurrentTree(ctx, "that tree is unsafe to work on; trying somewhere else");
            }
            return TaskStatus.RUNNING;
        }
        breaking = true;
        status = "mining " + name + " (" + mined + " done)";
        ctx.debug.target("mine " + name, target, ctx.omniscientMining()
                ? "omniscient mode" : Vision.inspect(ctx, target).verdict());
        ctx.debug.intent = "mining the selected visible block";
        return TaskStatus.RUNNING;
    }

    private TaskStatus walkToTarget(BotContext ctx) {
        stopBreaking(ctx);
        if (approach == null) {
            Goal reachable = reachableMiningPosition(ctx);
            if (reachable == null) {
                unreachable.add(target.asLong());
                BlockMemory.get().markUnreachable(target);
                clearTarget(ctx);
                if (finishCurrentTree && treeAnchor != null) {
                    abandonCurrentTree(ctx, "can't get into a sensible position at that tree; leaving it");
                } else {
                    status = "no reachable place to mine that block; trying the next one";
                    decisionTicks = DECISION_TICKS;
                }
                return TaskStatus.RUNNING;
            }
            // A tree is a local, visible work site. Do not tunnel toward it or invoke the generic
            // bridge/pillar recovery: both behaviours make a simple ground-level chop look like a
            // navigation failure and can leave blocks placed under the player.
            approach = new GotoTask(reachable, true, !finishCurrentTree, false, !finishCurrentTree);
            approach.start(ctx);
        }

        if (reconsiderTarget(ctx)) {
            return TaskStatus.RUNNING;
        }

        TaskStatus result = approach.tick(ctx);
        if (result == TaskStatus.SUCCESS) {
            // In range now; release the route so it isn't ticked again as a finished no-op.
            approach.stop(ctx);
            approach = null;
            return TaskStatus.RUNNING;
        }
        if (result == TaskStatus.FAILED) {
            // The path was blocked (stone in the way, water, etc). Blacklist this target and try the
            // next nearest one rather than giving up immediately - there may be a tree or ore behind
            // it with a clear path.
            String reason = approach.status();
            unreachable.add(target.asLong());
            BlockMemory.get().markUnreachable(target);
            clearTarget(ctx);
            if (finishCurrentTree && treeAnchor != null) {
                abandonCurrentTree(ctx, "can't reach that tree cleanly; leaving it (" + reason + ")");
            } else {
                status = "target unreachable, trying the next one (" + reason + ")";
                decisionTicks = DECISION_TICKS;
            }
            return TaskStatus.RUNNING;
        }

        status = (finishCurrentTree ? "coming next to the tree" : "walking to target")
                + " - " + approach.status();
        return TaskStatus.RUNNING;
    }

    /**
     * Enumerates real places the player could mine the target from. This prevents A* spending its
     * full budget chasing the target's Y coordinate when a floating canopy log has no standable or
     * climbable position within reach.
     */
    private Goal reachableMiningPosition(BotContext ctx) {
        List<BlockPos> positions = new ArrayList<>();
        double safeReachSqr = (REACH - 0.35) * (REACH - 0.35);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    boolean occupiable = MovementHelper.canStandAt(ctx.level, feet)
                            || (MovementHelper.isClimbable(ctx.level, feet)
                                    && MovementHelper.hasBodyClearance(ctx.level, feet));
                    if (!occupiable) {
                        continue;
                    }
                    double eyeX = feet.getX() + 0.5;
                    double eyeY = feet.getY() + 1.6;
                    double eyeZ = feet.getZ() + 0.5;
                    double targetX = target.getX() + 0.5;
                    double targetY = target.getY() + 0.5;
                    double targetZ = target.getZ() + 0.5;
                    double distance = (eyeX - targetX) * (eyeX - targetX)
                            + (eyeY - targetY) * (eyeY - targetY)
                            + (eyeZ - targetZ) * (eyeZ - targetZ);
                    if (distance <= safeReachSqr
                            && (!finishCurrentTree || isHorizontallyCloseToLog(
                                    feet.getX() + 0.5, feet.getZ() + 0.5, target))) {
                        positions.add(feet);
                    }
                }
            }
        }
        if (positions.isEmpty()) {
            return null;
        }
        // Keep Any's per-node heuristic cheap. The nearest candidates provide alternatives around
        // obstacles without multiplying every A* expansion by hundreds of equivalent positions.
        BlockPos player = ctx.player.blockPosition();
        // Dry footing first. MovementHelper.canStandAt counts water as standable, which is right for
        // the pathfinder - it has to be able to plan a swim - but it is wrong for somewhere to stand
        // and work. A current moves the bot every tick, and the reach test it just satisfied fails
        // again immediately, so it re-approaches instead of finishing the block.
        java.util.Comparator<BlockPos> preference = java.util.Comparator.comparingInt(
                (BlockPos pos) -> MovementHelper.isWater(ctx.level, pos) ? 1 : 0);
        if (finishCurrentTree) {
            // Fell a tree from the ground up. Ranking purely by nearness keeps the bot wherever it
            // already is, so once it is up among the branches every following log is also reached
            // from up there - and the only way between two points inside a canopy is through
            // leaves. Lowest first, nearest to break the tie.
            positions.sort(preference.thenComparingInt(BlockPos::getY)
                    .thenComparingDouble(player::distSqr));
        } else {
            positions.sort(preference.thenComparingDouble(player::distSqr));
        }
        List<Goal> nearest = positions.stream().limit(24)
                .map(pos -> (Goal) new Goals.Block(pos)).toList();
        return new Goals.Any(nearest);
    }

    /**
     * Follows a trunk down to the lowest log that is still a legitimate target.
     * <p>
     * Only logs that pass the caller's own filter are accepted, so this stays inside the visibility
     * rules - it does not let the bot select a trunk base it has never laid eyes on. Descending past
     * a hidden log is fine: what matters is that whatever is finally chosen was seen.
     */
    /**
     * Picks the log to open a tree on: the second one up, keeping the stump for last.
     * <p>
     * Nobody fells a tree by starting at the top, and nobody starts on the stump either. The second
     * log is the one already at eye level from where you are standing, and leaving the bottom one
     * in place keeps the trunk you are cutting anchored to the ground rather than dropping the whole
     * column the moment the first cut lands. The stump is taken at the end, once everything above it
     * has gone - and only if the job still needs it.
     */
    private BlockPos startingLog(BotContext ctx, BlockPos base,
            java.util.function.BiPredicate<BlockPos, net.minecraft.world.level.block.state.BlockState> filter,
            BlockPos fallback) {
        BlockPos second = base.above();
        net.minecraft.world.level.block.state.BlockState state = ctx.level.getBlockState(second);
        net.minecraft.world.level.block.state.BlockState baseState = ctx.level.getBlockState(base);
        boolean baseUsable = targets.contains(baseState.getBlock())
                && !unreachable.contains(base.asLong())
                && filter.test(base, baseState);
        boolean secondUsable = state.getBlock() == baseState.getBlock()
                && !unreachable.contains(second.asLong())
                && filter.test(second, state);
        if (!baseUsable) {
            // trunkBase may be hidden below leaves or grass. Never replace a visible selection with
            // that hidden block; keep the original sighted log and let the next local scan expose
            // the lower trunk naturally.
            return secondUsable ? second : fallback;
        }
        if (!secondUsable) {
            // A one-log tree, or the second is hidden. Nothing to defer; take the visible base.
            return base;
        }
        deferredStump = base;
        return second;
    }

    /**
     * Follows a trunk down to where it meets the ground.
     * <p>
     * Purely geometric: it does not require each log on the way down to be visible. Where the trunk
     * of a tree you are already looking at reaches the ground is not hidden knowledge, it is the
     * shape of the thing in front of you, and a grass tuft covering the bottom log should not make
     * the bot count the tree as starting a block higher. Only the log finally chosen has to pass
     * the visibility filter, and that check stays with the caller.
     */
    private BlockPos trunkBase(BotContext ctx, BlockPos log) {
        Block type = ctx.level.getBlockState(log).getBlock();
        // Walk the whole connected body of logs, not just straight down. A large oak's branches
        // reach out sideways and diagonally, and a branch log has air beneath it - so descending
        // from whichever log was spotted first stops dead at the branch and calls that the bottom
        // of the tree. That is how the bot ended up starting its cut in the canopy. Spreading
        // through the connections instead reaches the trunk from any branch it happens to see.
        Set<Long> seen = new HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        seen.add(log.asLong());
        queue.add(log);
        BlockPos lowest = log;

        while (!queue.isEmpty() && seen.size() < MAX_TREE_BLOCKS) {
            BlockPos at = queue.poll();
            if (at.getY() < lowest.getY()) {
                lowest = at;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = at.offset(dx, dy, dz);
                        if (Math.abs(next.getY() - log.getY()) > MAX_TRUNK_SCAN
                                || ctx.level.getBlockState(next).getBlock() != type
                                || !seen.add(next.asLong())) {
                            continue;
                        }
                        queue.add(next);
                    }
                }
            }
        }
        return lowest;
    }

    /** Returns the bounded connected log component around a visible seed. */
    private Set<Long> connectedTreeLogs(BotContext ctx, BlockPos seed) {
        Block type = ctx.level.getBlockState(seed).getBlock();
        Set<Long> seen = new HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        seen.add(seed.asLong());
        queue.add(seed);
        while (!queue.isEmpty() && seen.size() < MAX_TREE_BLOCKS) {
            BlockPos at = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = at.offset(dx, dy, dz);
                        if (Math.abs(next.getY() - seed.getY()) > MAX_TRUNK_SCAN
                                || ctx.level.getBlockState(next).getBlock() != type
                                || !seen.add(next.asLong())) {
                            continue;
                        }
                        queue.add(next);
                    }
                }
            }
        }
        return seen;
    }

    private static boolean isHorizontallyCloseToLog(double x, double z, BlockPos log) {
        return isHorizontallyCloseToLog(x, z, log, TREE_HORIZONTAL_REACH);
    }

    private static boolean isHorizontallyCloseToLog(double x, double z, BlockPos log, double reach) {
        double dx = x - (log.getX() + 0.5);
        double dz = z - (log.getZ() + 0.5);
        return dx * dx + dz * dz <= reach * reach;
    }

    /**
     * Swims back into the block while working in water.
     * <p>
     * Standing in a current is sometimes unavoidable - a tree in a swamp has no dry side - and the
     * bot has to actively hold its place or it is carried off mid-swing. Only pushes once it has
     * actually started drifting, so it is not shoving itself into the trunk the whole time.
     */
    private void braceAgainstCurrent(BotContext ctx, BlockPos block) {
        if (!ctx.player.isInWater()) {
            return;
        }
        // Forward in water follows the view, so a steeply raised aim would swim the bot upward
        // instead of back into position. Only hold station against a block at roughly its own level.
        if (block.getY() > ctx.player.getBlockY() + 1) {
            return;
        }
        double dx = ctx.player.getX() - (block.getX() + 0.5);
        double dz = ctx.player.getZ() - (block.getZ() + 0.5);
        if (dx * dx + dz * dz <= BRACE_DISTANCE * BRACE_DISTANCE) {
            return;
        }
        // breakTarget aims the view at the block on the same tick, so forward is toward it.
        ctx.input.forward = true;
    }

    /**
     * After mining a pickaxe block directly beside the player, make the opening human-walkable.
     *
     * <p>The old flow stopped after the feet-level block disappeared. The player was then looking
     * into a one-block-high hole, so the next scan saw the same wall from outside and either picked
     * one more isolated face or gave up. Only a directly adjacent, visible rock head block is opened
     * here; this is a local passage, not a license to tunnel toward hidden ore.</p>
     */
    private void planTwoHighPassage(BotContext ctx, BlockPos finished) {
        if (finishCurrentTree || !isPickaxeMiningTask()) {
            return;
        }
        BlockPos feet = ctx.player.blockPosition();
        if (finished.getY() != feet.getY()
                || Math.abs(finished.getX() - feet.getX())
                        + Math.abs(finished.getZ() - feet.getZ()) != 1) {
            return;
        }

        BlockPos head = finished.above();
        if (!MovementHelper.isSolidFloor(ctx.level, finished.below())
                || MovementHelper.isWater(ctx.level, finished.below())
                || MovementHelper.isLava(ctx.level, finished.below())) {
            return;
        }
        BlockState headState = ctx.level.getBlockState(head);
        if (MovementHelper.isPassable(ctx.level, head)) {
            if (MovementHelper.canStandAt(ctx.level, finished)) {
                passageAdvance = finished.immutable();
            }
            return;
        }
        // If the head is itself requested, let the normal target selection count and mine it.
        if (targets.contains(headState.getBlock())
                || !isExposableRock(headState.getBlock())
                || !MovementHelper.isBreakable(ctx.level, head)
                || MovementHelper.isWater(ctx.level, head)
                || MovementHelper.isLava(ctx.level, head)
                || MovementHelper.fallingBlocksAbove(ctx.level, head) > 0
                || MovementHelper.wouldOpenWater(ctx.level, head)
                || MovementHelper.wouldOpenLava(ctx.level, head)) {
            return;
        }
        if (!ctx.omniscientMining() && !Vision.isVisible(ctx, head)) {
            return;
        }
        if (!ToolSelector.canHarvest(ctx.player, headState)) {
            return;
        }

        passageTarget = head.immutable();
        passageBlockState = headState;
        passageAdvance = finished.immutable();
        ctx.debug.decide("opening the visible head block so the mined wall becomes two blocks high");
    }

    /** Starts the short walk into a passage after its head block has been cleared. */
    private void startPassageAdvance(BotContext ctx) {
        if (passageAdvance == null) {
            return;
        }
        BlockPos destination = passageAdvance;
        passageAdvance = null;
        if (!MovementHelper.canStandAt(ctx.level, destination)) {
            status = "two-block opening is not safe to enter";
            return;
        }
        passageApproach = new GotoTask(new Goals.Block(destination), true, false, false, false);
        passageApproach.start(ctx);
    }

    /** Drops a failed head-clearance attempt without making the same one-block opening again. */
    private void clearPassagePlan() {
        clearingPassage = false;
        passageTarget = null;
        passageBlockState = null;
        passageAdvance = null;
    }

    /** Mine/ore tasks need a pickaxe; wood, crops and flowers must never become tunnels. */
    private boolean isPickaxeMiningTask() {
        return targets.stream().anyMatch(block -> block.defaultBlockState().requiresCorrectToolForDrops());
    }

    /**
     * Looks up from a long walk to check whether somewhere better has come into view.
     * <p>
     * Chunks load as the bot approaches, so the best target when the walk started is routinely not
     * the best one a hundred blocks later - a run was seen crossing three hundred blocks to one
     * sighted tree, arriving inside a forest, and continuing to the original tree. A committed tree
     * is exempt: finishing the trunk it is already standing in is a deliberate behaviour, not an
     * oversight.
     *
     * @return true when the target changed and the caller should restart its approach next tick
     */
    private boolean reconsiderTarget(BotContext ctx) {
        if (target == null || treeAnchor != null || memoryTarget) {
            return false;
        }
        if (--reconsiderTicks > 0) {
            return false;
        }
        reconsiderTicks = RECONSIDER_INTERVAL_TICKS;

        BlockPos feet = ctx.player.blockPosition();
        double remaining = Math.sqrt(feet.distSqr(target));
        if (remaining < MinePolicy.MIN_REMAINING_TO_RECONSIDER) {
            return false;
        }

        BlockPos candidate = index.nearestCandidate(ctx.level, feet, unreachable);
        if (candidate == null || candidate.equals(target)) {
            return false;
        }
        double distance = Math.sqrt(feet.distSqr(candidate));
        if (!MinePolicy.shouldSwitchTarget(remaining, distance)
                || !Vision.isVisible(ctx, candidate)) {
            return false;
        }

        ctx.debug.decide("closer " + ctx.level.getBlockState(candidate).getBlock().getName().getString()
                + " came into view at " + Math.round(distance) + " blocks; dropping the "
                + Math.round(remaining) + "-block walk");
        clearTarget(ctx);
        status = "somewhere closer came into view; retargeting";
        return true;
    }

    private void clearTarget(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        target = null;
        memoryTarget = false;
        resetScan(ctx);
    }

    /**
     * Remembers every matching block in the current tree-shaped region. A failed log should make
     * the bot choose a different tree, not select the next log in the same canopy and repeat the
     * exact failed plan.
     */
    private void abandonCurrentTree(BotContext ctx, String reason) {
        finishTreeSkill(ctx, false);
        if (treeAnchor != null && treeType != null) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -32; dy <= 32; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        BlockPos pos = treeAnchor.offset(dx, dy, dz);
                        if (ctx.level.getBlockState(pos).getBlock() == treeType) {
                            unreachable.add(pos.asLong());
                        }
                    }
                }
            }
        }
        treeAnchor = null;
        deferredStump = null;
        treeType = null;
        currentTreeLogs.clear();
        treeViewPending = false;
        resetScan(ctx);
        status = reason;
        decisionTicks = DECISION_TICKS;
    }

    /** Starts one size/tool/distance-normalised tree-chopping episode. */
    private void beginTreeSkill(BotContext ctx) {
        if (!finishCurrentTree || treeAnchor == null) {
            return;
        }
        treeExpectedLogs = Math.max(1, currentTreeLogs.size());
        treeStrategyStartedTick = taskTicks;
        treeStrategyMinedStart = mined;
        treeMaxHorizontalRadiusSquared = 0;
        for (long packed : currentTreeLogs) {
            BlockPos log = BlockPos.of(packed);
            int dx = log.getX() - treeAnchor.getX();
            int dz = log.getZ() - treeAnchor.getZ();
            treeMaxHorizontalRadiusSquared = Math.max(treeMaxHorizontalRadiusSquared,
                    dx * dx + dz * dz);
        }

        float destroySpeed = bestDestroySpeed(ctx, treeAnchor);
        double distance = Math.sqrt(ctx.player.distanceToSqr(Vec3.atCenterOf(treeAnchor)));
        String dimension = ctx.level.dimension().identifier().toString();
        LearningContext context = new LearningContext("skill", "tree-chopping", dimension,
                TreeChoppingPolicy.phase(treeExpectedLogs, destroySpeed, distance));
        if (BuildFeatures.releaseBuild() || ctx.config.learningEnabled) {
            treeSkillChoice = ctx.learning.chooseSkill(context, TreeChoppingPolicy.ACTIONS,
                    TreeChoppingPolicy.DEFAULT);
            observedTreeStrategy = treeSkillChoice.action();
            if (ctx.learningSession.active()) {
                ctx.learningSession.decision(context, observedTreeStrategy);
            }
        } else {
            treeSkillChoice = null;
            observedTreeStrategy = TreeChoppingPolicy.DEFAULT;
        }
        ctx.debug.learningContext = context.key();
        ctx.debug.learningAction = observedTreeStrategy;
        ctx.debug.decide("tree-chopping skill chose " + observedTreeStrategy + " for "
                + TreeChoppingPolicy.sizeBucket(treeExpectedLogs) + " tree");
    }

    /** A rejected live tactic starts a fresh measurement segment under its replacement. */
    private void syncRejectedTreeStrategy(BotContext ctx) {
        if (treeSkillChoice == null || !treeSkillChoice.active()
                || observedTreeStrategy.equals(treeSkillChoice.action())) {
            return;
        }
        int completedUnderOldStrategy = Math.max(0, mined - treeStrategyMinedStart);
        treeExpectedLogs = Math.max(1, treeExpectedLogs - completedUnderOldStrategy);
        treeStrategyMinedStart = mined;
        treeStrategyStartedTick = taskTicks;
        observedTreeStrategy = treeSkillChoice.action();
        ctx.debug.decide("player rejected the tree tactic; using " + observedTreeStrategy
                + " for the remaining logs");
    }

    private String activeTreeStrategy() {
        return treeSkillChoice == null ? observedTreeStrategy : treeSkillChoice.action();
    }

    /**
     * Ends the current tree measurement because this task is going away, not because the tactic
     * failed. Starting or stopping the whole job is not a verdict on the log order it happened to
     * be using, and scoring it as one buried tree chopping under -10s: 455 of these closed in a
     * single pair of runs, one for every tree the bot re-anchored on.
     */
    private void cancelTreeSkill(BotContext ctx) {
        if (treeSkillChoice == null) {
            return;
        }
        if (Math.max(0, mined - treeStrategyMinedStart) > 0) {
            // It did cut logs. Bank them at the rate it was cutting them.
            finishTreeSkill(ctx, true);
            return;
        }
        ctx.learning.abandonSkill(treeSkillChoice);
        treeSkillChoice = null;
        observedTreeStrategy = TreeChoppingPolicy.DEFAULT;
    }

    private void finishTreeSkill(BotContext ctx, boolean completed) {
        if (treeSkillChoice == null) {
            return;
        }
        int collected = Math.max(0, mined - treeStrategyMinedStart);
        long elapsed = Math.max(1L, taskTicks - treeStrategyStartedTick);
        String action = treeSkillChoice.action();
        SkillOutcome outcome = ctx.learning.recordSkillOutcome(treeSkillChoice, completed,
                collected, treeExpectedLogs, elapsed);
        ctx.debug.learningBestTicksPerUnit =
                ctx.learning.bestSkillTicksPerUnit(treeSkillChoice.context());
        if (ctx.learningSession.active()) {
            ctx.learningSession.skillOutcome("tree-chopping", action, outcome);
        }
        ctx.debug.learningReward = ctx.learningSession.reward();
        ctx.debug.learningMemory = ctx.learningSession.summary(BuildFeatures.approvalFeedback())
                + "; " + ctx.learning.summary(BuildFeatures.approvalFeedback());
        ctx.debug.decide("tree-chopping " + action + ": " + outcome.summary());
        treeSkillChoice = null;
        observedTreeStrategy = TreeChoppingPolicy.DEFAULT;
    }

    private static float bestDestroySpeed(BotContext ctx, BlockPos log) {
        BlockState state = ctx.level.getBlockState(log);
        int slot = ToolSelector.bestSlot(ctx.player, state);
        if (slot == ToolSelector.NO_SLOT) {
            return 1.0F;
        }
        return ctx.player.getInventory().getItem(slot).getDestroySpeed(state);
    }

    private void stopBreaking(BotContext ctx) {
        breaker.stop(ctx);
        breaking = false;
    }

    /**
     * No visible targets in range. Dig a reversible staircase segment and scan again underground.
     * This exposes blocks honestly without X-ray or a dangerous straight-down shaft.
     */
    private TaskStatus prospect(BotContext ctx) {
        if (++prospectTicks >= MAX_PROSPECT_TICKS) {
            if (prospectTask != null) {
                prospectTask.stop(ctx);
                prospectTask = null;
            }
            resetScan(ctx);
            status = "prospecting timed out after " + MAX_PROSPECT_TICKS
                    + " ticks; abandoning this pocket";
            ctx.debug.giveUp = "prospect time " + prospectTicks + "/" + MAX_PROSPECT_TICKS;
            ctx.debug.decide("prospect budget exhausted; abandon pocket and relocate");
            return TaskStatus.FAILED;
        }
        if (prospectTask == null) {
            if (prospectAttempts >= prospectMaxAttempts) {
                status = "mined " + mined + ", dug " + prospectMaxAttempts
                        + " stair segments, no targets found";
                return TaskStatus.SUCCESS;
            }
            // A cave someone else already dug beats a staircase every time: it is open ore in open
            // air, it costs no blocks and no time, and a run was seen walking past three of them to
            // sink its own shaft. Only ever tried once per pocket, and only for an opening the bot
            // can actually see and reach - an unlit hole thirty blocks through rock is not an offer.
            if (!caveEntryTried) {
                caveEntryTried = true;
                BlockPos mouth = findVisibleCaveMouth(ctx);
                if (mouth != null) {
                    ctx.debug.decide("open cave at " + mouth.toShortString()
                            + "; walking into it instead of digging");
                    status = "taking the open cave instead of digging";
                    prospectTask = new GotoTask(new Goals.Near(mouth, 2), true, false);
                    prospectDirection = null;
                    prospectSegmentTicks = 0;
                    prospectStepsSinceStart = 0;
                    prospectMinedSinceStart = 0;
                    prospectTask.start(ctx);
                    return TaskStatus.RUNNING;
                }
            }

            forgetRejectionsAfterMoving(ctx);
            Direction direction = nextProspectDirection(ctx);
            if (direction == null) {
                // Every direction was refused from this exact spot, so there is no stair to dig
                // here at all. That is a different answer from "dug and found nothing", and the
                // caller needs it: the pocket is not barren, it is unworkable, and the fix is to
                // stand somewhere else rather than to spend the remaining attempts in place.
                status = "mined " + mined + ", no diggable stair direction here";
                ctx.debug.decide("terrain refused every stair direction; relocate before prospecting again");
                return TaskStatus.SUCCESS;
            }
            int targetY = OreKnowledge.prospectYFor(ctx, targets);
            int bottom = Math.max(targetY, ctx.level.getMinY() + 2);
            int available = ctx.player.blockPosition().getY() - bottom;
            prospectDirection = direction;
            prospectSegmentTicks = 0;
            prospectStepsSinceStart = 0;
            if (available <= 0) {
                // Already at the ore layer. Dig a short horizontal branch to expose it instead of
                // stopping or tunneling straight down past the best level.
                prospectTask = new TunnelTask(direction, null, prospectStairSteps, 2);
                prospectMinedSinceStart = 0;
                prospectTask.start(ctx);
                status = "branching at y " + targetY + " toward " + direction.getName();
            } else {
                int steps = Math.min(prospectStairSteps, available);
                prospectTask = new StaircaseProspectTask(direction, steps, targets,
                        prospectProtectedRoute);
                prospectMinedSinceStart = 0;
                prospectTask.start(ctx);
                status = "digging prospecting stairs " + direction.getName();
            }
        }

        prospectSegmentTicks++;
        TaskStatus result = prospectTask.tick(ctx);
        String segmentStatus = prospectTask.status();
        if (prospectTask instanceof StaircaseProspectTask stairs) {
            int newlyMined = stairs.drainMatchingBlocksBroken();
            if (newlyMined > 0) {
                mined += newlyMined;
                prospectMinedSinceStart += newlyMined;
            }
            int newSteps = stairs.drainCompletedSteps();
            if (newSteps > 0) {
                prospectStepsSinceStart += newSteps;
                // Do not finish an eight-step segment blindly. Each new stair reveals fresh walls,
                // so return control to Mine for an immediate visible-target scan.
                resetScan(ctx);
                status = "checking the newly exposed stair face";
                return TaskStatus.RUNNING;
            }
        }
        if (limit > 0 && mined >= limit) {
            stopProspect(ctx);
            resetScan(ctx);
            sweepPending = true;
            status = "prospecting exposed and mined " + mined + " target blocks";
            return TaskStatus.RUNNING;
        }
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }

        boolean refused = wasRefusedByTerrain(result, segmentStatus);
        Direction attempted = prospectDirection;
        int minedThisSegment = prospectMinedSinceStart;
        stopProspect(ctx);

        // Failed stairs still change what the player can see. Always scan the exposed face before
        // choosing another direction; otherwise partial stairs can skip visible stone repeatedly.
        resetScan(ctx);

        if (refused) {
            // Not an attempt: nothing was dug, so nothing was learned about the pocket - only about
            // this one heading. Remember the heading and keep the budget for a stair that digs.
            if (attempted != null) {
                prospectRejected.add(attempted);
            }
            status = "stairs " + (attempted == null ? "here" : attempted.getName())
                    + " refused (" + segmentStatus + "); trying another heading";
            ctx.debug.decide("terrain refused the " + (attempted == null ? "current" : attempted.getName())
                    + " stair; rotate without spending a prospect attempt");
            return TaskStatus.RUNNING;
        }

        prospectAttempts++;
        if (minedThisSegment > 0) {
            sweepPending = true;
            status = "prospecting mined " + minedThisSegment + " target blocks";
        } else if (result == TaskStatus.SUCCESS) {
            // Reached the spot. Try a fresh scan next tick.
            status = "stair segment finished; scanning the exposed face";
        } else {
            status = "stair segment ended (" + segmentStatus + "); trying another";
        }
        return TaskStatus.RUNNING;
    }

    /**
     * The nearest visible opening into a real underground space.
     * <p>
     * "Cave" here means what it means to a player looking at one: a pocket of air big enough to
     * walk into, with solid ground to stand on, under enough rock that it is not simply the sky.
     * The same air-count threshold the staircase uses to notice it has broken into one.
     */
    private BlockPos findVisibleCaveMouth(BotContext ctx) {
        return visibleCaveMouth(ctx, CAVE_SEARCH_RADIUS);
    }

    /** Shared with the speedrun, which prefers going down an opening to walking the surface. */
    static BlockPos visibleCaveMouth(BotContext ctx, int radius) {
        BlockPos feet = ctx.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -CAVE_SEARCH_DEPTH; dy <= 2; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!ctx.level.isLoaded(candidate)) {
                        continue;
                    }
                    double distance = feet.distSqr(candidate);
                    if (distance >= bestDistance) {
                        continue;
                    }
                    if (!MovementHelper.isPassable(ctx.level, candidate)
                            || !MovementHelper.isPassable(ctx.level, candidate.above())
                            || !MovementHelper.isSolidFloor(ctx.level, candidate.below())) {
                        continue;
                    }
                    if (ctx.level.canSeeSky(candidate)) {
                        // Open air, not a cave.
                        continue;
                    }
                    if (openNeighbours(ctx, candidate) < CAVE_AIR_THRESHOLD) {
                        continue;
                    }
                    // Not every hole in the ground is worth going down. A mineshaft is a cave with
                    // a cave spider spawner in it, and venom does not care how much iron is on the
                    // walls: two runs went in on full health and came out on one heart, poisoned,
                    // surviving only because poison cannot land the last half. Cheap to check, and
                    // there is always another opening.
                    if (spawnerNear(ctx, candidate)) {
                        continue;
                    }
                    if (!Vision.isVisible(ctx, candidate)) {
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
     * Whether a monster spawner sits close enough to make an opening not worth entering.
     * <p>
     * This deliberately does <em>not</em> apply the {@link Vision} test that every target search
     * does, and the exception is worth stating plainly rather than hiding: it is a refusal, not a
     * discovery. Seeing a spawner through rock never helps the bot find anything - it only ever
     * stops it walking somewhere. Gating hazard avoidance on line of sight would mean learning
     * about the cave spider room by standing in it, which is exactly the outcome two runs already
     * demonstrated. If the honest version is wanted, the alternative is to react to poison damage
     * and retreat, which costs the health this avoids losing.
     */
    private static boolean spawnerNear(BotContext ctx, BlockPos site) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SPAWNER_GUARD_RADIUS; dx <= SPAWNER_GUARD_RADIUS; dx++) {
            for (int dy = -SPAWNER_GUARD_RADIUS; dy <= SPAWNER_GUARD_RADIUS; dy++) {
                for (int dz = -SPAWNER_GUARD_RADIUS; dz <= SPAWNER_GUARD_RADIUS; dz++) {
                    cursor.set(site.getX() + dx, site.getY() + dy, site.getZ() + dz);
                    if (ctx.level.isLoaded(cursor)
                            && ctx.level.getBlockState(cursor).is(Blocks.SPAWNER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int openNeighbours(BotContext ctx, BlockPos centre) {
        int open = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (MovementHelper.isPassable(ctx.level, centre.offset(dx, dy, dz))) {
                        open++;
                    }
                }
            }
        }
        return open;
    }

    /**
     * Chooses the next heading to dig, skipping the ones this spot has already refused.
     * <p>
     * Null means every heading has been refused from here, which is a real answer rather than a
     * reason to keep rotating.
     */
    private Direction nextProspectDirection(BotContext ctx) {
        Direction facing = prospectBaseDirection == null
                ? ctx.player.getDirection() : prospectBaseDirection;
        Direction[] rotation = {
                facing, facing.getClockWise(), facing.getOpposite(), facing.getCounterClockWise()
        };
        for (int offset = 0; offset < rotation.length; offset++) {
            Direction candidate = rotation[Math.floorMod(prospectAttempts + offset, rotation.length)];
            if (!prospectRejected.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Rejections describe the ground the bot is standing on, so they expire as soon as it is
     * standing somewhere else. Six steps down, a heading that had gravel over it at the surface is
     * an ordinary wall again.
     */
    private void forgetRejectionsAfterMoving(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        if (prospectRejectAnchor == null) {
            prospectRejectAnchor = feet.immutable();
            return;
        }
        if (prospectRejectAnchor.distSqr(feet) >= (double) RESCAN_DISTANCE * RESCAN_DISTANCE) {
            prospectRejected.clear();
            prospectRejectAnchor = feet.immutable();
        }
    }

    /**
     * Whether the segment that just ended was refused by the terrain rather than tried.
     * <p>
     * A refusal digs nothing, enters no step and ends almost immediately. A missing tool is
     * excluded deliberately: it is not a property of the heading, and rotating away from it would
     * spend all four headings on the same answer without ever going to make a pickaxe.
     */
    private boolean wasRefusedByTerrain(TaskStatus result, String segmentStatus) {
        if (result != TaskStatus.FAILED) {
            return false;
        }
        if (prospectStepsSinceStart > 0 || prospectMinedSinceStart > 0) {
            return false;
        }
        if (segmentStatus != null && segmentStatus.startsWith(NO_TOOL_PREFIX)) {
            return false;
        }
        return prospectSegmentTicks <= PROSPECT_REJECT_TICKS;
    }

    /**
     * Finds the nearest visible target using the cached, section-pruned {@link TargetIndex}.
     * <p>
     * The index is built once per scan anchor and reused until the bot really moves, so turning the
     * head through its four views only re-filters the cached candidates by visibility instead of
     * re-walking the whole cube each time.
     *
     * @return true once there is nothing left to see (or a target was chosen); false while the bot
     *         is still turning or has more views to check
     */
    private boolean findTarget(BotContext ctx) {
        BlockPos centre = ctx.player.blockPosition();
        // Restart the scan only on a real move, not on any change of block position. Treading water
        // or sliding down a slope shifts the block position every tick, and restarting on that
        // would reset the scan forever and leave the bot turning on the spot without ever settling
        // on a block to mine. A player who shuffles a block does not start their search over either.
        if (scanCentre == null || scanCentre.distSqr(centre) > RESCAN_DISTANCE * RESCAN_DISTANCE) {
            scanCentre = centre;
            headScanner.reset(ctx.player);
            treeScanner.reset(ctx.player);
        }

        // Once a tree is chosen, inspect that tree again after every log instead of spinning around
        // the whole world. Aim one block above the last cut so the newly exposed trunk is visible.
        if (!ctx.omniscientMining() && treeAnchor != null && treeViewPending) {
            Vec3 treeView = new Vec3(treeAnchor.getX() + 0.5, treeLookY + 0.5,
                    treeAnchor.getZ() + 0.5);
            ctx.look.lookAt(ctx.player, treeView);
            if (!ctx.look.isLookingAt(ctx.player, treeView, 4.0F)) {
                status = "checking the current tree above the last log";
                return false;
            }
            treeViewPending = false;
            // The dedicated local scanner starts from the last trunk view. It first checks that
            // view, then glances left/right, and only then widens to a full sweep if a branch is not
            // visible from the initial angle.
            treeScanner.reset(ctx.player);
        }

        // Once a tree is committed, scanning around it is always worthwhile even when the command's
        // initial search was intentionally limited to the current view. The filter below restricts
        // this scan to the committed tree, so it cannot steal a different tree while turning.
        boolean areaCheck = !ctx.omniscientMining()
                && (treeAnchor != null || checkAround);
        HeadScanner scanner = treeAnchor != null ? treeScanner : headScanner;
        boolean viewSettled = true;
        if (areaCheck && !Vision.isPanoramic()
                && (scanner.isTurning() || scanner.isVerticalGlance())) {
            // A player notices a block while the head is turning. Search every tick instead of
            // waiting for every vertical glance to settle; the settled flag only controls when it
            // is legal to advance to the next view.
            viewSettled = scanner.tickTurn(ctx);
            scanTicks++;
            ctx.debug.searchHeading = scanner.status();
            ctx.debug.searchView = scanner.horizontalViewNumber();
            ctx.debug.searchViewCount = scanner.horizontalViewCount();
            ctx.debug.scanTicks = scanTicks;
        }

        int scanLimit = ctx.omniscientMining()
                ? radius
                : Math.min(radius, (int) Vision.maxRange(ctx));

        // Key the index on the settled scan centre, not the live position. A rebuild walks every
        // chunk section in the cube, so keying it on a position that drifts every tick would throw
        // the cache away every tick and undo the point of caching it at all.
        if (!index.isUsable(scanCentre, targets, scanLimit, yMin, yMax)) {
            index.rebuild(ctx.level, scanCentre, targets, scanLimit, yMin, yMax);
        }
        ctx.debug.searchAnchor = scanCentre;
        // The stop counter is only meaningful while prospecting. Showing an 0/8 prospect budget
        // during an ordinary area scan makes the HUD look as if a hidden retry is in progress.
        ctx.debug.searchLimit = prospect ? prospectMaxAttempts : 0;
        ctx.debug.searchAttempt = prospectAttempts;
        ctx.debug.searchCandidates = index.size();
        if (areaCheck && !Vision.isPanoramic()) {
            ctx.debug.searchHeading = scanner.status();
            ctx.debug.searchView = scanner.horizontalViewNumber();
            ctx.debug.searchViewCount = scanner.horizontalViewCount();
            ctx.debug.scanTicks = scanTicks;
        }

        java.util.function.BiPredicate<BlockPos, net.minecraft.world.level.block.state.BlockState> filter =
                (pos, state) -> !prospectProtectedRoute.contains(pos.asLong())
                        && !pos.equals(deferredStump)
                        && belongsToCurrentTree(pos, state)
                        && (ctx.omniscientMining() || Vision.isVisible(ctx, pos));
        // Ordinary mining stays ranked from WorkSite. A committed tree may instead use the
        // skill learner's safe ordering; every candidate still passes the same tree and Vision
        // filter above, so learning changes sequence rather than knowledge or eligibility.
        BlockPos workFocus = site.focus(ctx, scanCentre);
        if (finishCurrentTree && treeAnchor != null) {
            BlockPos anchor = treeAnchor;
            String strategy = activeTreeStrategy();
            target = index.best(ctx.level, unreachable, filter, pos ->
                    TreeChoppingPolicy.candidateScore(strategy,
                            anchor.getX(), anchor.getY(), anchor.getZ(),
                            workFocus.getX(), workFocus.getY(), workFocus.getZ(),
                            pos.getX(), pos.getY(), pos.getZ(),
                            treeMaxHorizontalRadiusSquared));
        } else if (MiningPolicy.NEAREST_VISIBLE.equals(miningStrategy)) {
            target = index.nearest(ctx.level, scanCentre, unreachable, filter);
        } else if (MiningPolicy.LEVEL_FIRST.equals(miningStrategy)) {
            target = index.best(ctx.level, unreachable, filter, pos ->
                    MiningPolicy.levelFirstScore(
                            workFocus.getX(), workFocus.getY(), workFocus.getZ(),
                            pos.getX(), pos.getY(), pos.getZ()));
        } else {
            target = index.nearest(ctx.level, workFocus, unreachable, filter);
        }
        if (target != null) {
            memoryTarget = false;
            target = topOfFallingColumn(ctx, target);
            if (!finishCurrentTree && ctx.level.getBlockState(target).is(BlockTags.LOGS)) {
                // Minimum wood requests deliberately stop after the required drops, but they
                // should still begin at the grounded part of a visible trunk.  Starting from the
                // nearest canopy log makes a jungle gather climb into leaves and can leave the
                // pathfinder circling a two-block-away branch after the first log is collected.
                // Only substitute a lower log when that exact block is also visible; the
                // human-like Vision rule must not be weakened by this convenience.
                BlockPos grounded = visibleGroundLog(ctx, target, filter);
                if (grounded != null) {
                    target = grounded;
                }
            }
            if (finishCurrentTree && treeAnchor == null) {
                // Start at the foot of the trunk, not at whatever log happened to be nearest. The
                // nearest log of a tall spruce is usually one in the canopy, and a bot that commits
                // to a canopy log has to get inside the canopy to reach it - which means chewing
                // through leaves, and often ending up standing on top of the tree.
                BlockPos spotted = target;
                currentTreeLogs.clear();
                currentTreeLogs.addAll(connectedTreeLogs(ctx, spotted));
                target = startingLog(ctx, trunkBase(ctx, spotted), filter, spotted);
                treeAnchor = target;
                treeType = ctx.level.getBlockState(target).getBlock();
                treeLookY = target.getY() + 1;
                beginTreeSkill(ctx);
            }
            BlockMemory.get().remember(target, ctx.level.getBlockState(target).getBlock());
            ctx.debug.target("mine " + ctx.level.getBlockState(target).getBlock().getName().getString(),
                    target, ctx.omniscientMining() ? "omniscient mode" : Vision.inspect(ctx, target).verdict());
            ctx.debug.memory = BlockMemory.get().size() + " remembered positions; selected from work site";
            ctx.debug.decide("target selected; route to a reachable mining position");
            status = "spotted " + ctx.level.getBlockState(target).getBlock().getName().getString();
            return true;
        }

        // Everything above the stump has gone. Reaching here at all means the job still wants more
        // wood, so this is the "if needed" the stump was being held back for.
        if (finishCurrentTree && deferredStump != null) {
            BlockPos stump = deferredStump;
            deferredStump = null;
            net.minecraft.world.level.block.state.BlockState state = ctx.level.getBlockState(stump);
            if (targets.contains(state.getBlock()) && !unreachable.contains(stump.asLong())
                    && (ctx.omniscientMining() || Vision.isVisible(ctx, stump))) {
                target = stump;
                memoryTarget = false;
                status = "taking the stump";
                return true;
            }
        }

        // In panoramic mode Vision has already checked the complete loaded, line-of-sight view.
        // Do not enter the physical head-scanner state machine after that check: the scanner is
        // intentionally never ticked in panoramic mode, so waiting for it to settle would leave
        // MineTask forever at "glancing level, ahead" without reaching prospecting or completion.
        if (areaCheck && !Vision.isPanoramic()) {
            if (scanTicks >= MAX_AREA_SCAN_TICKS) {
                scanner.finish();
                status = "scan budget exhausted; moving on from this spot";
                ctx.debug.nextDecision = prospect
                        ? "scan timed out; expose a bounded staircase segment"
                        : "scan timed out; finish this mining step";
                ctx.debug.decide("scan budget exhausted; stop repeating this view");
                return true;
            }
            if (!viewSettled || scanner.isTurning() || scanner.isVerticalGlance()) {
                status = scanner.status();
                return false;
            }
            if (prospect || isEnclosed(ctx, centre)) {
                // No point spinning in a stone box. For prospecting, one view is enough; for
                // non-prospecting, if every side is solid the other views will show the same stone.
                scanner.finish();
                return true;
            }
            if (scanner.advance()) {
                status = scanner.status();
                return false;
            }
            // The current tree starts with the cheap glance. If that misses a side branch, widen
            // once to the full sweep before declaring the committed tree complete.
            if (treeAnchor != null && scanner == treeScanner && treeScanner.escalate(ctx.player)) {
                status = "glance missed a branch; widening the current-tree scan";
                return false;
            }
        }
        status = treeAnchor != null
                ? "checking remaining visible logs in the current tree"
                : "looking for visible blocks";
        BlockPos candidate = index.nearestCandidate(ctx.level, site.focus(ctx, scanCentre), unreachable);
        if (candidate != null) {
            ctx.debug.target("nearest " + ctx.level.getBlockState(candidate).getBlock().getName().getString(),
                    candidate, Vision.inspect(ctx, candidate).verdict());
            if (prospect && exposureAttempts < MAX_VISIBLE_BLOCK_EXPOSURES) {
                Vision.SightReport sight = Vision.inspect(ctx, candidate);
                BlockPos blocker = sight.blocker();
                if (canExposeVisibleBlock(ctx, blocker) && isWallFace(ctx, blocker, candidate)) {
                    target = blocker.immutable();
                    exposureBlockState = ctx.level.getBlockState(target);
                    exposureOre = candidate.immutable();
                    exposingBlocker = true;
                    exposureAttempts++;
                    memoryTarget = false;
                    ctx.debug.target("expose " + exposureBlockState.getBlock().getName().getString(),
                            target, "visible blocker before "
                                    + ctx.level.getBlockState(candidate).getBlock().getName().getString());
                    ctx.debug.decide("open the visible blocker before prospecting farther");
                    status = "opening visible " + exposureBlockState.getBlock().getName().getString()
                            + " toward the ore";
                    return true;
                }
            }
            // "Not actionable" was all this used to say, and a run spent 1,842 ticks saying it
            // while stood in front of iron it could see. Which of the several refusals fired is
            // the whole diagnosis, so name it.
            ctx.debug.decide("matching block exists, but it is not actionable: "
                    + refusalReason(ctx, candidate));
        } else {
            ctx.debug.target("mine candidate", null, "none in loaded scan cube");
            ctx.debug.decide("no loaded matching block; continue scan or bounded prospect");
        }
        ctx.debug.memory = BlockMemory.get().size() + " remembered positions; none currently selected";
        return true;
    }

    /**
     * A bounded cave interaction: if the scan found an ore behind a block that is itself visible,
     * open that one block first. This models a player mining the wall face they can see and avoids
     * turning the ore index into permission to tunnel through arbitrary hidden material.
     */
    /**
     * Why a visible-but-occluded candidate did not become a dig, in the journal's words.
     *
     * <p>Every clause here is a real refusal that has cost a run time. Reporting only that the
     * block "is not actionable" made them indistinguishable from each other and from simply having
     * nothing to do, which is how a capped exposure budget hid for several runs.
     */
    private String refusalReason(BotContext ctx, BlockPos candidate) {
        if (!prospect) {
            return "this step does not prospect";
        }
        if (exposureAttempts >= MAX_VISIBLE_BLOCK_EXPOSURES) {
            return "exposure budget spent (" + exposureAttempts + ")";
        }
        BlockPos blocker = Vision.inspect(ctx, candidate).blocker();
        if (blocker == null) {
            return "occluded with no identifiable blocker";
        }
        if (!canExposeVisibleBlock(ctx, blocker)) {
            int distance = blocker.distManhattan(ctx.player.blockPosition());
            if (distance > MAX_EXPOSURE_DISTANCE) {
                return "blocker " + distance + " blocks away";
            }
            if (!Vision.isVisible(ctx, blocker)) {
                return "the blocker itself is out of sight";
            }
            return "blocker is " + ctx.level.getBlockState(blocker).getBlock().getName().getString()
                    + ", which is not worth opening";
        }
        if (!isWallFace(ctx, blocker, candidate)) {
            return ctx.level.canSeeSky(blocker.above())
                    ? "the blocker is open ground, not a wall face"
                    : "there is " + blocker.distManhattan(candidate)
                            + " blocks of rock between the blocker and the ore";
        }
        return "no reason found";
    }

    private boolean canExposeVisibleBlock(BotContext ctx, BlockPos blocker) {
        if (blocker == null || !ctx.level.hasChunkAt(blocker)
                || !Vision.isVisible(ctx, blocker)
                || blocker.distManhattan(ctx.player.blockPosition()) > MAX_EXPOSURE_DISTANCE) {
            return false;
        }
        BlockState state = ctx.level.getBlockState(blocker);
        if (!isExposableRock(state.getBlock())
                || !MovementHelper.isBreakable(ctx.level, blocker)
                || MovementHelper.isWater(ctx.level, blocker)
                || MovementHelper.isLava(ctx.level, blocker)
                || MovementHelper.fallingBlocksAbove(ctx.level, blocker) > 0) {
            return false;
        }
        return autoTool || ToolSelector.canHarvest(ctx.player, state);
    }

    /**
     * Exposing is for the wall face in front of you, not for the ground under your feet.
     *
     * <p>Standing on the surface with iron forty blocks down, the sight ray to that ore leaves
     * through the terrain skin, so {@code sight.blocker()} is the grass the bot is standing on.
     * Breaking it "toward the ore" is really the first block of a shaft, and because the budget
     * allows several attempts the bot wanders off and repeats it - the scatter of one-block holes
     * across the landscape. A real wall face is roughly level with what it hides and has ground
     * above it; both tests below fail for the terrain skin and pass inside a cave or a cutting.
     */
    private static boolean isWallFace(BotContext ctx, BlockPos blocker, BlockPos ore) {
        return blocker.distManhattan(ore) <= MAX_BLOCKER_TO_ORE
                && !ctx.level.canSeeSky(blocker.above());
    }

    private static boolean isExposableRock(Block block) {
        return block == Blocks.STONE
                || block == Blocks.DEEPSLATE
                || block == Blocks.GRANITE
                || block == Blocks.DIORITE
                || block == Blocks.ANDESITE
                || block == Blocks.TUFF
                || block == Blocks.COBBLESTONE
                || block == Blocks.COBBLED_DEEPSLATE
                || block == Blocks.DIRT;
    }

    /**
     * Raises a gravel or sand target to the top of its own column.
     *
     * <p>Falling blocks are the one material where the block you can see is the wrong one to break.
     * Taking the bottom of a bank drops everything above it into the hole - onto the bot, which
     * then jumps at a wall of gravel, and onto the drop, which disappears under it. Digging the top
     * block instead leaves the column intact and the drop in the open, which is how a person clears
     * a gravel bank.
     *
     * <p>Only raised while the higher block is genuinely visible: the sight rule is not relaxed for
     * convenience, so a column buried in a cliff face is still mined where it can actually be seen.
     */
    private BlockPos topOfFallingColumn(BotContext ctx, BlockPos chosen) {
        if (!MovementHelper.isFallingBlock(ctx.level.getBlockState(chosen))) {
            return chosen;
        }
        int stacked = MovementHelper.fallingBlocksAbove(ctx.level, chosen);
        if (!FallingBlockPolicy.wouldCollapse(stacked)) {
            return chosen;
        }
        BlockPos raised = chosen;
        for (int step = 0; step < FallingBlockPolicy.digOffset(stacked); step++) {
            BlockPos above = raised.above();
            if (!ctx.omniscientMining() && !Vision.isVisible(ctx, above)) {
                break;
            }
            raised = above;
        }
        if (!raised.equals(chosen)) {
            ctx.debug.decide("gravel column: digging the top block so it cannot bury the drop");
        }
        return raised;
    }

    private String targetNames() {
        return targets.stream()
                .map(block -> block.getName().getString())
                .sorted()
                .limit(3)
                .reduce((a, b) -> a + ", " + b)
                .orElse("blocks");
    }

    /**
     * The same target set as {@link #targetNames()}, written the way the other learned jobs write
     * theirs. Display names are translated, so a profile keyed on them would file the same work
     * under "Cobblestone" here and "Bruchstein" on a German client - and the table that ships in
     * the jar would match neither of the two. Registry ids are the same everywhere.
     */
    private String targetKey() {
        return targets.stream()
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .sorted()
                .limit(4)
                .reduce((a, b) -> a + "," + b)
                .orElse("none");
    }

    private boolean isWoodTargetSet() {
        return targets.stream().anyMatch(block -> {
            BlockState state = block.defaultBlockState();
            return state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS);
        });
    }

    /** Finds the lowest currently visible log in the connected trunk, without committing to its tree. */
    private BlockPos visibleGroundLog(BotContext ctx, BlockPos spotted,
                                      java.util.function.BiPredicate<BlockPos,
                                              net.minecraft.world.level.block.state.BlockState> filter) {
        BlockPos base = trunkBase(ctx, spotted);
        BlockPos[] candidates = {base, base.above()};
        for (BlockPos candidate : candidates) {
            net.minecraft.world.level.block.state.BlockState state = ctx.level.getBlockState(candidate);
            if (targets.contains(state.getBlock())
                    && !unreachable.contains(candidate.asLong())
                    && filter.test(candidate, state)) {
                return candidate;
            }
        }
        return spotted;
    }

    /** True when the player is boxed in on all sides by non-target, solid-looking blocks. */
    private boolean isEnclosed(BotContext ctx, BlockPos centre) {
        for (Direction direction : Direction.values()) {
            BlockPos side = centre.relative(direction);
            net.minecraft.world.level.block.state.BlockState state = ctx.level.getBlockState(side);
            if (state.isAir() || state.liquid() || targets.contains(state.getBlock())) {
                return false;
            }
            if (!state.isSolidRender()) {
                return false;
            }
        }
        return true;
    }

    private void resetScan(BotContext ctx) {
        scanCentre = null;
        scanTicks = 0;
        index.invalidate();
        headScanner.reset(ctx.player);
        treeScanner.reset(ctx.player);
    }

    private boolean belongsToCurrentTree(BlockPos pos,
                                         net.minecraft.world.level.block.state.BlockState state) {
        if (!finishCurrentTree || treeAnchor == null) {
            return true;
        }
        if (!currentTreeLogs.isEmpty()) {
            return currentTreeLogs.contains(pos.asLong()) && state.getBlock() == treeType;
        }
        int dx = Math.abs(pos.getX() - treeAnchor.getX());
        int dz = Math.abs(pos.getZ() - treeAnchor.getZ());
        int dy = pos.getY() - treeAnchor.getY();
        // A generous canopy width handles 2x2 jungle trees and branches without wandering into a
        // different species elsewhere in the forest.
        return state.getBlock() == treeType && dx <= 4 && dz <= 4 && dy >= -32 && dy <= 32;
    }

    private void stopProspect(BotContext ctx) {
        if (prospectTask != null) {
            prospectTask.stop(ctx);
            prospectTask = null;
        }
        prospectDirection = null;
        prospectSegmentTicks = 0;
        prospectStepsSinceStart = 0;
    }

    @Override
    public void onPause(BotContext ctx) {
        stopBreaking(ctx);
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        if (hintApproach != null) {
            hintApproach.stop(ctx);
            hintApproach = null;
        }
        if (passageApproach != null) {
            passageApproach.stop(ctx);
            passageApproach = null;
        }
        stopProspect(ctx);
    }

    @Override
    public void onStop(BotContext ctx) {
        cancelTreeSkill(ctx);
        if (hintApproach != null) {
            hintApproach.stop(ctx);
            hintApproach = null;
        }
        if (passageApproach != null) {
            passageApproach.stop(ctx);
            passageApproach = null;
        }
        stopBreaking(ctx);
        clearTarget(ctx);
        stopProspect(ctx);

        ctx.input.reset();
    }
}
