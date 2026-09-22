package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.learning.LearningScope;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockBreaker;
import com.etka.lune.bot.util.ExposedVein;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * Digs a straight corridor of a given height, one block at a time.
 * <p>
 * Each step clears the whole column ahead before moving into it, so the bot never walks into a
 * half-dug space and never leaves a floating block over its head. Digging uses the pathfinder only
 * for the single step forward, which keeps the corridor straight rather than letting A* wander off
 * through whatever gap it finds.
 */
public final class TunnelTask implements Task {

    /** Value of the direction parameter meaning "whichever way I'm facing when this starts". */
    public static final String FACING = "Facing";

    private final String directionChoice;
    private final int length;
    private final int height;
    /**
     * What the corridor stops for, or empty when it is only a corridor.
     *
     * <p>The Tunnel card digs and nothing else: it has no target parameter, so nothing here fires
     * for it. Stripmine does have one, and a strip mine that walks its branches past the ore it was
     * cut to find is a hole in the ground with a progress bar.</p>
     */
    private final Set<Block> targets;

    private final BlockBreaker breaker = new BlockBreaker();
    private BlockPos origin;
    private Direction direction;
    private int advanced;
    private GotoTask stepForward;
    /** Ticks spent trying to move into the block ahead; see the give-up in onTick. */
    private int stuckTicks;
    /** How long one block of corridor may take before the corridor is declared unwalkable. */
    private static final int MAX_STEP_TICKS = 200;
    /** Veins this corridor has given up on: lava behind one, or one that would not break. */
    private final Set<Long> refusedVeins = new HashSet<>();
    private BlockPos veinTarget;
    private int veinTicks;
    /**
     * How long one exposed block may be swung at before the corridor leaves it.
     * <p>
     * Fifteen seconds. The slowest thing a job of this kind legitimately asks for is ancient
     * debris, which is about five and a half seconds with a diamond pickaxe and a little over four
     * with a netherite one. Anything still standing at three times that is not slow, it is a block
     * the bot cannot actually break from where it is - and the corridor has somewhere else to be.
     */
    private static final int MAX_VEIN_TICKS = 300;
    private final StatusText status = new StatusText();

    public TunnelTask(String directionChoice, int length, int height) {
        this(directionChoice, length, height, Set.of());
    }

    public TunnelTask(String directionChoice, int length, int height, Set<Block> targets) {
        this.directionChoice = directionChoice;
        this.length = length;
        this.height = Math.max(2, height);
        this.targets = Set.copyOf(targets);
    }

    /** For Stripmine, which knows its direction up front and picks its own starting point. */
    public TunnelTask(Direction direction, BlockPos origin, int length, int height) {
        this(direction, origin, length, height, Set.of());
    }

    /** The same, for a shaft or branch that is being cut in search of something. */
    public TunnelTask(Direction direction, BlockPos origin, int length, int height, Set<Block> targets) {
        this(direction.getName(), length, height, targets);
        this.direction = direction;
        this.origin = origin;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.tunnel.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Tunnel");
    }

    @Override
    public LearningScope learningScope() {
        return LearningScope.of(learningId(), "lune.unit.blocks");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return new TaskProgress(advanced, length, Lang.get("lune.unit.blocks"));
    }

    @Override
    public void onStart(BotContext ctx) {
        if (origin == null) {
            origin = ctx.player.blockPosition();
        }
        stuckTicks = 0;
        if (direction == null) {
            direction = FACING.equalsIgnoreCase(directionChoice)
                    ? ctx.player.getDirection()
                    : parseDirection(directionChoice, ctx.player.getDirection());
        }
    }

    private static Direction parseDirection(String name, Direction fallback) {
        for (Direction candidate : Direction.Plane.HORIZONTAL) {
            if (candidate.getName().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return fallback;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (advanced >= length) {
            breaker.stop(ctx);
            status.set("lune.status.tunnel.dug_blocks", advanced);
            return TaskStatus.SUCCESS;
        }

        // Not from under water.
        //
        // A corridor dug while submerged fills in behind the bot as fast as it is opened, and the
        // bot has no way to breathe in it. One run walked into a lake on its travel leg, started a
        // tunnel at y=60 with its head under, and kept digging through 160 ticks of drowning damage
        // until it died - the Self Preservation card fired three times and could not get it out of
        // the hole it was still busy making.
        //
        // Failing rather than waiting: the task's Fail edge is where "so do something else"
        // belongs, and the guard needs the bot to stop digging before it can swim anywhere.
        if (ctx.player.isUnderWater()) {
            breaker.stop(ctx);
            status.set("lune.status.tunnel.under_water_not_starting_corridor_here");
            return TaskStatus.FAILED;
        }

        // Take what the last few blocks opened before cutting the next one. A vein is only exposed
        // for as long as the bot is standing beside it: keep walking and it is behind a wall of its
        // own corridor, and nothing comes back for it.
        TaskStatus vein = mineExposedTarget(ctx);
        if (vein != null) {
            return vein;
        }

        BlockPos ahead = origin.relative(direction, advanced + 1);

        // Clear the full column before stepping into it, lowest block first so the bot always has
        // somewhere to stand.
        for (int y = 0; y < height; y++) {
            BlockPos block = ahead.above(y);
            if (MovementHelper.isPassable(ctx.level, block)) {
                continue;
            }
            if (breaker.isOutOfReach(ctx, block)) {
                // Shouldn't normally happen one block ahead, but knockback and falls happen.
                return walkTo(ctx, ahead, "lune.status.tunnel.repositioning");
            }
            BlockBreaker.Progress progress = breaker.tick(ctx, block, true);
            if (progress == BlockBreaker.Progress.NO_TOOL) {
                status.set("lune.status.goto.break", ctx.level.getBlockState(block).getBlock().getName().getString());
                return TaskStatus.FAILED;
            }
            if (progress == BlockBreaker.Progress.HAZARD) {
                status.set(breaker.getFailureReason());
                return TaskStatus.FAILED;
            }
            status.set("lune.status.tunnel.digging", (advanced + 1), length);
            return TaskStatus.RUNNING;
        }
        breaker.stop(ctx);

        if (Mth.floor(ctx.player.getX()) == ahead.getX() && Mth.floor(ctx.player.getZ()) == ahead.getZ()) {
            advanced++;
            stuckTicks = 0;
            if (stepForward != null) {
                stepForward.stop(ctx);
                stepForward = null;
            }
            return TaskStatus.RUNNING;
        }
        // One block forward, or this corridor is not happening.
        //
        // The step is a full pathfind, and when the block ahead cannot be stood on the search has
        // nowhere to go - so it expands its entire budget, every tick, forever. Measured: a run
        // that met this at "advancing 1/24" sat there for 670 attempts at ten thousand nodes and a
        // median of 395 ms each, which took the game from twenty ticks a second to about three.
        // Being stuck is bad; being stuck at 3 TPS is the bot making the whole client unusable.
        //
        // Ten seconds is far longer than stepping one block into cleared space can honestly take,
        // so exceeding it is not slowness, it is a corridor that cannot be walked. Failing hands
        // the task its Fail edge, which is where the decision belongs.
        if (++stuckTicks > MAX_STEP_TICKS) {
            status.set("lune.status.tunnel.couldnt_move_into_corridor_after_s", (MAX_STEP_TICKS / 20), advanced, length);
            return TaskStatus.FAILED;
        }
        return walkTo(ctx, ahead, "lune.status.tunnel.advancing_n_of_n", advanced + 1, length);
    }

    /**
     * Breaks one exposed target block beside the corridor, or null when there is none to break.
     *
     * <p>Two answers differ from the staircase's, and both are because of where this digs. A
     * hazard behind a vein - lava, nearly always, and at the depths a strip mine works at there is
     * lava behind a great many things - stops <em>that vein</em> rather than the corridor: the bot
     * leaves it alone and carries on cutting, which is what a player does. A block that will not
     * come loose is put aside the same way. What still fails the whole job is having no tool for
     * the thing the player asked for, because every other block in the branch will answer the
     * same.</p>
     */
    private TaskStatus mineExposedTarget(BotContext ctx) {
        if (targets.isEmpty()) {
            return null;
        }
        BlockPos candidate = ExposedVein.next(ctx, ctx.player.blockPosition(), targets,
                Set.of(), refusedVeins);
        if (candidate == null) {
            veinTarget = null;
            veinTicks = 0;
            return null;
        }
        if (!candidate.equals(veinTarget)) {
            veinTarget = candidate.immutable();
            veinTicks = 0;
        }

        BlockBreaker.Progress progress = breaker.tick(ctx, candidate, false);
        if (progress == BlockBreaker.Progress.NO_TOOL) {
            breaker.stop(ctx);
            status.set("lune.status.route.needs_better_tool",
                    ctx.level.getBlockState(candidate).getBlock().getName().getString());
            return TaskStatus.FAILED;
        }
        if (progress == BlockBreaker.Progress.HAZARD || ++veinTicks > MAX_VEIN_TICKS) {
            breaker.stop(ctx);
            refusedVeins.add(candidate.asLong());
            veinTarget = null;
            veinTicks = 0;
            // Null rather than RUNNING: the corridor gets on with the block ahead this same tick.
            return null;
        }
        status.set("lune.status.tunnel.mining_exposed",
                ctx.level.getBlockState(candidate).getBlock().getName().getString(),
                (advanced + 1), length);
        return TaskStatus.RUNNING;
    }

    private TaskStatus walkTo(BotContext ctx, BlockPos target, String key,
                              Object... args) {
        if (stepForward == null) {
            // Deliberately the exact block, Y included.
            //
            // Half the advancing time goes into searches that burn the 2500-node minimum budget -
            // measured, 2474 of 5333 snapshots, worst case 648 ms - to shuffle one block along a
            // corridor already cleared, and asking for the column instead (Goals.NearXZ radius 0,
            // which is what the arrival check above actually tests) removes every one of them.
            //
            // It is still wrong, because NearXZ has no Y term at all: the bot satisfies "in that
            // column" at any height, so it digs down out of the corridor rather than stepping along
            // it. Tunnelled distance over one run fell from 489 blocks to 98. The height is the
            // part that matters, so the goal stays exact.
            //
            // What was actually costing the time is the third argument. This used to allow
            // breaking, and a digging search is deliberately exempt from the distance scaling that
            // bounds a walking one - so stepping a single block asked for the whole ten-thousand
            // node budget rather than the 2500 floor. On a corridor it could not enter that ran
            // every tick at a median of 395 ms, and took the client from 20 TPS to about 3.
            //
            // It never needed to dig anyway: the loop above clears the entire column before this is
            // reached, so by construction there is nothing in the way. If something is, that is a
            // corridor worth abandoning rather than tunnelling sideways out of.
            stepForward = new GotoTask(new Goals.Block(target), false, false);
            stepForward.start(ctx);
        }
        TaskStatus result = stepForward.tick(ctx);
        if (result == TaskStatus.FAILED) {
            status.set("lune.status.tunnel.couldnt_move_into_corridor");
            return TaskStatus.FAILED;
        }
        status.set(key, args);
        return TaskStatus.RUNNING;
    }

    /** Where the corridor has reached, so Stripmine can start a branch from it. */
    public BlockPos currentEnd() {
        return origin == null ? null : origin.relative(direction, advanced);
    }

    @Override
    public void onPause(BotContext ctx) {
        breaker.stop(ctx);
        veinTarget = null;
        veinTicks = 0;
        if (stepForward != null) {
            stepForward.stop(ctx);
            stepForward = null;
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        breaker.stop(ctx);
        veinTarget = null;
        veinTicks = 0;
        if (stepForward != null) {
            stepForward.stop(ctx);
            stepForward = null;
        }
        ctx.input.reset();
    }
}
