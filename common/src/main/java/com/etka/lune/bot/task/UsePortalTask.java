package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BlockScanner;
import com.etka.lune.bot.util.Vision;
import com.etka.lune.util.Lang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Walks into a lit Nether portal and stands in it until the world on the other side loads.
 *
 * <p>The speedrun has always needed this twice - into the Nether and back out - and kept it to
 * itself. As a card it is what lets an ordinary task cross over: build a portal, use it, work, come
 * back. It finds the portal the way a player does, by seeing one, so a frame behind a hill is not
 * found until the bot has walked round the hill.</p>
 *
 * <h2>Turning round, not walking round</h2>
 *
 * <p>A portal the bot has its back to is turned toward, as a player turns round: the
 * {@link SightSearch} under the card knows where every lit portal in its radius is, and one with a
 * clear line to it, outside the view cone, gets the head brought round until it is seen. One
 * hidden behind something is not walked toward. The End's searches do that, because a player in a
 * portal room knows where its frames are; nothing tells a player what is behind a hill, and a card
 * whose radius the player sets, up to 128 blocks, would walk to every lit portal inside it - on a
 * server, other players' bases, found through the ground. Seeing what cannot be seen is the
 * omniscient cheat's to grant, and the card still honours that cheat.</p>
 *
 * <h2>Standing in it, not walking through it</h2>
 *
 * <p>A portal only takes a player who stays inside it for a few seconds. Walking at it with the
 * forward key held carries the bot straight through a one-block-thick sheet and out the far side,
 * and turning round to walk back through does the same thing again - a bot that paces back and forth
 * across a portal for its whole timeout, never quite long enough inside to go anywhere. So once the
 * bot is beside the frame it steps into the lowest portal block and lets go of every key.</p>
 *
 * <p>With no destination given it takes the portal wherever it leads, which is the question a card
 * on a canvas is asking: from the Overworld that is the Nether, and from the Nether it is home.</p>
 */
public final class UsePortalTask implements Task {

    /** Two minutes: time to walk to a portal already in sight, step in and be carried across. */
    private static final int TIMEOUT = 2400;
    /**
     * How long to stand inside a portal that carries nobody anywhere before giving up on it. A
     * portal takes a player in four seconds, so ten is not impatience.
     */
    private static final int INSIDE_PATIENCE = 200;

    private final ResourceKey<Level> destination;
    private final BlockPos anchor;
    private final int searchRadius;
    /** Built with nowhere to stand: it looks and it turns, and it never walks to see. */
    private final SightSearch sight;
    private final StatusText status = new StatusText();

    private ResourceKey<Level> origin;
    private BlockPos portal;
    /** The open cell in front of the portal, on the side the bot is approaching from. */
    private BlockPos front;
    private GotoTask approach;
    private int ticks;
    private int insideTicks;

    /**
     * @param destination the dimension this crossing must end in, or null for whichever one the
     *                    portal leads to
     * @param anchor      a portal block already known to the caller, preferred while it stands
     */
    public UsePortalTask(ResourceKey<Level> destination, BlockPos anchor, int searchRadius) {
        this.destination = destination;
        this.anchor = anchor;
        this.searchRadius = Math.max(1, searchRadius);
        this.sight = new SightSearch(Set.of(Blocks.NETHER_PORTAL), state -> true, this.searchRadius);
    }

    /** The card: whichever portal is in sight, to wherever it goes. */
    public static UsePortalTask card(int radius) {
        return new UsePortalTask(null, null, radius);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.use_portal.name");
    }

    @Override
    public String learningId() {
        return Task.learningName("Use Nether Portal");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        ResourceKey<Level> here = ctx.level.dimension();
        if (origin == null) {
            origin = here;
        }
        if (destination != null ? here == destination : here != origin) {
            ctx.input.reset();
            status.set("lune.status.use_portal.arrived");
            return TaskStatus.SUCCESS;
        }
        if (++ticks > TIMEOUT) {
            status.set("lune.status.use_portal.did_not_take");
            return TaskStatus.FAILED;
        }

        if (portal == null || !ctx.level.getBlockState(portal).is(Blocks.NETHER_PORTAL)) {
            if (portal != null) {
                // Gone out under the bot, as a ghast's fireball can do. The walk and the step were
                // both for that one, and nothing may be held down while the head turns.
                portal = null;
                stopApproach(ctx);
                ctx.input.reset();
            }
            BlockPos found = choose(ctx);
            if (found == null) {
                return lookBehind(ctx);
            }
            portal = footOf(ctx, found);
            front = frontOf(ctx, portal, ctx.player.blockPosition());
        }

        BlockPos feet = ctx.player.blockPosition();
        if (ctx.level.getBlockState(feet).is(Blocks.NETHER_PORTAL)) {
            // Inside. Every key up, and wait for the game to carry the bot across.
            stopApproach(ctx);
            ctx.input.reset();
            if (++insideTicks > INSIDE_PATIENCE) {
                // Standing in a portal that is not going anywhere - a server that forbids the
                // Nether, or a portal the game has not linked. Nothing more waiting will change.
                status.set("lune.status.use_portal.did_not_take");
                return TaskStatus.FAILED;
            }
            status.set("lune.status.use_portal.waiting");
            return TaskStatus.RUNNING;
        }
        insideTicks = 0;

        if (approach == null && feet.distSqr(front) > 1) {
            // To the face of the sheet, never its edge: from the side there is obsidian in the way.
            approach = new GotoTask(new Goals.Near(front, 1), true, false);
            approach.start(ctx);
        }
        if (approach != null) {
            TaskStatus walk = approach.tick(ctx);
            if (walk == TaskStatus.RUNNING) {
                status.set("lune.status.use_portal.walking");
                return TaskStatus.RUNNING;
            }
            stopApproach(ctx);
            if (walk == TaskStatus.FAILED) {
                status.set("lune.status.use_portal.could_not_reach");
                return TaskStatus.FAILED;
            }
        }

        // Beside the frame: face the lowest portal block and take the last step in at a walk, so the
        // step ends inside the sheet rather than on the far side of it.
        Vec3 into = Vec3.atBottomCenterOf(portal);
        ctx.look.lookAt(ctx.player, into.add(0, 1.0, 0));
        ctx.input.reset();
        ctx.input.forward = true;
        status.set("lune.status.use_portal.stepping_in");
        return TaskStatus.RUNNING;
    }

    /**
     * The portal to use: the caller's own while it stands, and otherwise the nearest one in sight.
     * Omniscient mining takes the nearest at all, as it always has here. Which cheat lets a job act
     * on what it cannot see is that job's to say, so the cheat is read here rather than inside
     * {@link SightSearch}, whose other callers honour none.
     */
    private BlockPos choose(BotContext ctx) {
        if (anchor != null && ctx.level.getBlockState(anchor).is(Blocks.NETHER_PORTAL)) {
            return anchor;
        }
        return ctx.omniscientMining()
                ? findPortal(ctx, ctx.player.blockPosition(), searchRadius)
                : sight.seen(ctx);
    }

    /**
     * Nothing lit in sight. One the bot merely has its back to - a clear line to it, outside the
     * view cone - gets the head turned toward it, and is taken the moment it swings into view.
     * Anything else is not in sight, and the card says so.
     */
    private TaskStatus lookBehind(BotContext ctx) {
        if (sight.turnToward(ctx)) {
            status.set(sight.statusLine());
            return TaskStatus.RUNNING;
        }
        status.set("lune.status.use_portal.none_in_sight");
        return TaskStatus.FAILED;
    }

    /**
     * The cell to walk into the portal from: in front of one of its two faces, whichever is nearer
     * the bot and open. A portal built against a wall has one face, and that is the one used.
     */
    private static BlockPos frontOf(BotContext ctx, BlockPos foot, BlockPos from) {
        Direction.Axis axis = ctx.level.getBlockState(foot).getValue(NetherPortalBlock.AXIS);
        Direction across = axis == Direction.Axis.X ? Direction.NORTH : Direction.WEST;
        BlockPos near = foot.relative(across);
        BlockPos far = foot.relative(across.getOpposite());
        if (from.distSqr(far) < from.distSqr(near)) {
            BlockPos swap = near;
            near = far;
            far = swap;
        }
        return open(ctx, near) || !open(ctx, far) ? near.immutable() : far.immutable();
    }

    /** Room for a player to stand: nothing solid at the feet or the head. */
    private static boolean open(BotContext ctx, BlockPos pos) {
        return BlockPlacer.isReplaceable(ctx, pos) && BlockPlacer.isReplaceable(ctx, pos.above());
    }

    /** The lowest portal block in that column: the one a player's feet go into. */
    private static BlockPos footOf(BotContext ctx, BlockPos pos) {
        BlockPos foot = pos;
        while (ctx.level.getBlockState(foot.below()).is(Blocks.NETHER_PORTAL)) {
            foot = foot.below();
        }
        return foot.immutable();
    }

    /**
     * The nearest lit portal the bot can see from where it stands, the way it faces now, and never
     * one behind a wall - or, under Omniscient mining, the nearest at all. One look and no turning:
     * the speedrun's own checks use it, and the card only when the cheat spares it looking.
     */
    public static BlockPos findPortal(BotContext ctx, BlockPos centre, int radius) {
        return BlockScanner.findNearest(ctx.level, centre, Set.of(Blocks.NETHER_PORTAL), radius,
                ctx.level.getMinY(), ctx.level.getMaxY(), Set.of(),
                (pos, state) -> ctx.omniscientMining() || Vision.isVisible(ctx, pos));
    }

    private void stopApproach(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        stopApproach(ctx);
        ctx.input.reset();
    }
}
