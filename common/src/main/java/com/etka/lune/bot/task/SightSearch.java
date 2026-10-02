package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.util.TargetIndex;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds one of a kind of block by sight, the way a player would.
 *
 * <p>The index under it knows where every such block is, through rock and behind the bot alike.
 * That is cheap to keep and wrong to act on, so {@link #seen} only ever answers with a block
 * {@link Vision#isVisible} accepts. When none is, {@link #goSee} does what a player does about
 * something they know is there and cannot see: turns round, when all that hides it is the way they
 * are facing, and otherwise walks to where it can be seen from. A block walked right up to that
 * still cannot be seen is given up on, so no walk is made twice for the same nothing. A search
 * built with nowhere to stand never walks at all: it only turns.</p>
 *
 * <p>{@link #lookRound} is the other half, for a job that has walked somewhere to look rather than
 * toward anything it knows of: a glance ahead and to either side, widening to a sweep.</p>
 */
final class SightSearch {

    /** How far the bot moves before the index is taken again: the same settle every scan keys on. */
    private static final int RESCAN_DISTANCE = 3;
    /**
     * An index with nothing in it is taken again this often, standing still or not: what is wanted
     * may not be there yet. The exit portal opens ten seconds after the dragon dies.
     */
    private static final int EMPTY_RETAKE_TICKS = 20;
    /** Ticks the head may spend turning toward one block before the turn is admitted not to help. */
    private static final int TURN_PATIENCE = 40;
    /** Walks that ended with nothing in sight before there is nowhere left worth walking to. */
    private static final int MAX_FAILED_WALKS = 4;

    /** Where to stand to see a block. {@code closer} is the second try, after a first stand did not. */
    interface Stand {
        Goal toSee(BlockPos pos, boolean closer);
    }

    private final Set<Block> blocks;
    private final Predicate<BlockState> wanted;
    private final int radius;
    private final Stand stand;
    private final TargetIndex index = new TargetIndex();
    private final HeadScanner scanner = new HeadScanner(HeadScanner.Style.GLANCE);
    /** Walked to twice and still not seen, or seen and never reached: not offered again. */
    private final Set<Long> givenUp = new HashSet<>();
    /** Faced, and still not seen: whatever hides these, it is not the way the head points. */
    private final Set<Long> faced = new HashSet<>();
    private final StatusText status = new StatusText();

    private BlockPos anchor;
    private long takenAt;
    private BlockPos turningToward;
    private int turnTicks;
    private GotoTask walk;
    private BlockPos walkingTo;
    private boolean closer;
    private int failedWalks;

    /**
     * @param wanted which of those blocks count, by state: an empty frame, not a filled one
     * @param radius how far round the bot the index reaches
     * @param stand  where to go to see one that is hidden, or null for a search that only looks
     */
    SightSearch(Set<Block> blocks, Predicate<BlockState> wanted, int radius, Stand stand) {
        this.blocks = Set.copyOf(blocks);
        this.wanted = wanted;
        this.radius = radius;
        this.stand = stand;
    }

    /**
     * A search that only looks: it turns toward what the bot merely has its back to, and walks
     * toward nothing it cannot see. For a job whose radius the player sets, where walking to
     * whatever the index knows would find things through the ground - Use Nether Portal's reaches
     * 128 blocks, and on a server a lit portal is somebody's base.
     */
    SightSearch(Set<Block> blocks, Predicate<BlockState> wanted, int radius) {
        this(blocks, wanted, radius, null);
    }

    /**
     * The nearest wanted block the bot can see from where it stands, or null. Nothing turns, and a
     * walk taken to see one ends the moment one is seen.
     */
    BlockPos seen(BotContext ctx) {
        refresh(ctx);
        BlockPos found = index.nearest(ctx.level, ctx.player.blockPosition(), givenUp,
                (pos, state) -> wanted.test(state) && Vision.isVisible(ctx, pos));
        if (found != null) {
            stopWalk(ctx);
            walkingTo = null;
            closer = false;
        }
        return found;
    }

    /** Whether any wanted block is left in reach of the index at all: seen or not, given up or not. */
    boolean anyLeft(BotContext ctx) {
        refresh(ctx);
        return index.nearest(ctx.level, ctx.player.blockPosition(), Set.of(),
                (pos, state) -> wanted.test(state)) != null;
    }

    boolean isWalking() {
        return walk != null;
    }

    /** Never offered again: seen, but the job could not get to it. */
    void giveUp(BlockPos pos) {
        givenUp.add(pos.asLong());
    }

    /**
     * One tick of going to see one. A walk under way carries on; otherwise the head turns toward a
     * block only the way it faces is hiding, or the bot sets off for the nearest one not given up
     * on - the only way anything hidden behind something is ever seen.
     *
     * @return {@link TaskStatus#FAILED} once there is nothing left to walk toward, or too many
     *         walks have come to nothing - for a search that only looks, once there is nothing
     *         left to turn toward - and {@link TaskStatus#RUNNING} otherwise
     */
    TaskStatus goSee(BotContext ctx) {
        if (walk == null && turnToward(ctx)) {
            return TaskStatus.RUNNING;
        }
        if (walk == null) {
            if (stand == null) {
                // Nowhere to stand: turning was all this search was ever going to do.
                return TaskStatus.FAILED;
            }
            if (walkingTo == null) {
                closer = false;
                walkingTo = index.nearest(ctx.level, ctx.player.blockPosition(), givenUp,
                        (pos, state) -> wanted.test(state));
                if (walkingTo == null) {
                    return TaskStatus.FAILED;
                }
            }
            walk = new GotoTask(stand.toSee(walkingTo, closer), true, false);
            walk.start(ctx);
        }
        TaskStatus result = walk.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            status.set("lune.status.sight.going_to_see", name(ctx, walkingTo), walk.statusLine());
            return TaskStatus.RUNNING;
        }
        stopWalk(ctx);
        if (result == TaskStatus.SUCCESS && !closer) {
            // Stood where it should have come into sight and it has not: once more, beside it.
            // Asking for the same stand again would be a walk that is over before it starts.
            closer = true;
            return TaskStatus.RUNNING;
        }
        givenUp.add(walkingTo.asLong());
        walkingTo = null;
        closer = false;
        return ++failedWalks > MAX_FAILED_WALKS ? TaskStatus.FAILED : TaskStatus.RUNNING;
    }

    /**
     * Turns the head toward the nearest wanted block that nothing hides but the way the bot is
     * facing: in range, with a clear line to it, and outside the view cone. Something behind a
     * wall is not this, and no amount of turning would show it.
     *
     * @return true while there is such a block and the head is on its way round to it
     */
    boolean turnToward(BotContext ctx) {
        refresh(ctx);
        BlockPos behind = index.nearest(ctx.level, ctx.player.blockPosition(), givenUp,
                (pos, state) -> wanted.test(state) && !faced.contains(pos.asLong())
                        && merelyBehind(ctx, pos));
        if (behind == null) {
            turningToward = null;
            return false;
        }
        if (!behind.equals(turningToward)) {
            turningToward = behind;
            turnTicks = 0;
        } else if (++turnTicks > TURN_PATIENCE) {
            faced.add(behind.asLong());
            turningToward = null;
            return false;
        }
        scanner.finish();
        ctx.look.lookAt(ctx.player, Vision.blockAimPoint(ctx, behind));
        status.set("lune.status.explore.turning_toward_behind_current_view", name(ctx, behind));
        return true;
    }

    /**
     * One tick of looking round from where the bot stands. Visibility is not tested here: callers
     * ask {@link #seen} every tick, turning or not, since a player notices a thing as it swings
     * into view.
     *
     * @return false once the look has been all the way round
     */
    boolean lookRound(BotContext ctx) {
        if (Vision.isPanoramic()) {
            return false;
        }
        boolean settled = true;
        if (scanner.isTurning() || scanner.isVerticalGlance()) {
            settled = scanner.tickTurn(ctx);
        }
        status.set(scanner.statusLine());
        return !settled || scanner.advance() || scanner.escalate(ctx.player);
    }

    /** Looks afresh from where the bot now stands: the index is taken again, the look starts narrow. */
    void reset(BotContext ctx) {
        anchor = null;
        index.invalidate();
        scanner.reset(ctx.player);
        faced.clear();
        turningToward = null;
        turnTicks = 0;
    }

    StatusText statusLine() {
        return status;
    }

    void stop(BotContext ctx) {
        stopWalk(ctx);
    }

    private static boolean merelyBehind(BotContext ctx, BlockPos pos) {
        Vision.SightReport sight = Vision.inspect(ctx, pos);
        return sight.reachable() && !sight.inView();
    }

    /** Keyed on a settled anchor, not the live position, or a bot treading water rebuilds every tick. */
    private void refresh(BotContext ctx) {
        BlockPos live = ctx.player.blockPosition();
        if (anchor == null || anchor.distSqr(live) > (double) RESCAN_DISTANCE * RESCAN_DISTANCE) {
            anchor = live;
        }
        long now = ctx.level.getGameTime();
        if (index.size() == 0 && now - takenAt >= EMPTY_RETAKE_TICKS) {
            index.invalidate();
        }
        int yMin = ctx.level.getMinY();
        int yMax = ctx.level.getMaxY() - 1;
        if (!index.isUsable(anchor, blocks, radius, yMin, yMax)) {
            index.rebuild(ctx.level, anchor, blocks, radius, yMin, yMax);
            takenAt = now;
        }
    }

    private void stopWalk(BotContext ctx) {
        if (walk != null) {
            walk.stop(ctx);
            walk = null;
        }
    }

    /** By the game's own name for it. */
    private static String name(BotContext ctx, BlockPos pos) {
        return ctx.level.getBlockState(pos).getBlock().getName().getString();
    }
}
