package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.catalog.Saplings;
import com.etka.lune.bot.memory.StumpLedger;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.CropHelper;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.util.Lang;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plants a sapling back where each tree Chop Wood felled used to stand.
 *
 * <p>Chop Wood writes a note in {@link StumpLedger} whenever it takes the foot of a trunk, and this
 * card is the one that acts on those notes: it walks to each stump within its radius, nearest first,
 * and puts a sapling in the ground there. The tree's own kind when one is carried, so an oak wood
 * stays an oak wood; any other sapling that will grow in that spot when it is not, because a clearing
 * with a birch in it is still a forest next week and a bare one is not.</p>
 *
 * <p>It does not go and get saplings. They come from the leaves of the trees that were felled, some
 * time after the trunk is gone, and picking them up is the Loot card's job - which is why a lumber
 * task puts Loot in front of this card and runs the pair once per trip. A stump that could not be
 * planted this time stays in the ledger, and the next pass finds it again with the saplings that
 * trip brought in.</p>
 *
 * <h2>What it answers</h2>
 * <ul>
 *   <li><b>Success</b> when it planted something, or when there was nothing to plant: no stumps
 *       noted nearby is a job already done, the same answer Deposit gives with nothing to put
 *       away.</li>
 *   <li><b>Fail</b> when stumps are waiting and no carried sapling will grow in any of them, so the
 *       graph can go and fetch some.</li>
 * </ul>
 */
public final class ReplantTask implements Task {

    /** Close enough to reach the ground under a stump from where the walk stops. */
    private static final int ARRIVAL = 2;
    /** How long one stump may take once the bot is beside it, before it is left for another pass. */
    private static final int PLANT_TICKS = 100;

    private enum Phase { CHOOSE, WALKING, PLANTING }

    private final int radius;
    private final StatusText status = new StatusText();
    /** Stumps given up on in this run; they stay in the ledger for the next one. */
    private final Set<Long> passedOver = new HashSet<>();

    private Phase phase = Phase.CHOOSE;
    private StumpLedger.Stump stump;
    private Item sapling;
    private GotoTask walk;
    private int plantTicks;
    private int planted;

    public ReplantTask(int radius) {
        this.radius = Math.max(1, radius);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.replant.name");
    }

    /** The English this is, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Replant Trees");
    }

    /** The walks inside are learned by the walk; planting a sapling is one click. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    @Override
    public boolean madeProgress() {
        return planted > 0;
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        return switch (phase) {
            case CHOOSE -> choose(ctx);
            case WALKING -> walk(ctx);
            case PLANTING -> plant(ctx);
        };
    }

    private TaskStatus choose(BotContext ctx) {
        List<StumpLedger.Stump> waiting = StumpLedger.get()
                .near(ctx.level, ctx.player.blockPosition(), radius);
        boolean anyWaiting = false;
        for (StumpLedger.Stump candidate : waiting) {
            if (passedOver.contains(candidate.pos().asLong())) {
                continue;
            }
            if (!plantable(ctx, candidate.pos())) {
                // Somebody built on it, flooded it, or the ground went: it is not a stump any more.
                StumpLedger.get().forget(candidate.pos());
                continue;
            }
            anyWaiting = true;
            Item chosen = saplingFor(ctx, candidate);
            if (chosen != null) {
                stump = candidate;
                sapling = chosen;
                walk = new GotoTask(new Goals.Near(candidate.pos(), ARRIVAL), false, false);
                walk.start(ctx);
                phase = Phase.WALKING;
                status.set("lune.status.replant.walking", candidate.pos().getX(),
                        candidate.pos().getY(), candidate.pos().getZ());
                return TaskStatus.RUNNING;
            }
        }
        if (planted > 0) {
            status.set("lune.status.replant.planted", planted);
            return TaskStatus.SUCCESS;
        }
        if (anyWaiting) {
            status.set("lune.status.replant.no_sapling");
            return TaskStatus.FAILED;
        }
        status.set("lune.status.replant.nothing_to_plant");
        return TaskStatus.SUCCESS;
    }

    private TaskStatus walk(BotContext ctx) {
        TaskStatus result = walk.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        walk.stop(ctx);
        walk = null;
        if (result == TaskStatus.FAILED) {
            passOver();
            return TaskStatus.RUNNING;
        }
        plantTicks = 0;
        phase = Phase.PLANTING;
        return TaskStatus.RUNNING;
    }

    private TaskStatus plant(BotContext ctx) {
        BlockPos at = stump.pos();
        if (!plantable(ctx, at)) {
            // Something took the spot while the bot was on its way - a sapling that dropped and
            // rooted on its own, or a block somebody put there. Either way it is not a stump now.
            StumpLedger.get().forget(at);
            phase = Phase.CHOOSE;
            return TaskStatus.RUNNING;
        }
        if (++plantTicks > PLANT_TICKS || !BlockPlacer.hasLineOfSight(ctx, at.below())) {
            // Out of sight of the ground it would be planted in, or taking too long: a player does
            // not plant through a wall, and one stubborn stump is not worth the rest of the trip.
            passOver();
            return TaskStatus.RUNNING;
        }
        switch (CropHelper.plantOn(ctx, at, sapling)) {
            case PLANTED -> {
                StumpLedger.get().forget(at);
                planted++;
                phase = Phase.CHOOSE;
                status.set("lune.status.replant.planted_one", at.getX(), at.getY(), at.getZ());
            }
            // The sapling ran out between choosing it and using it; choose again with what is left.
            case NO_ITEM -> phase = Phase.CHOOSE;
            default -> status.set("lune.status.replant.planting", at.getX(), at.getY(), at.getZ());
        }
        return TaskStatus.RUNNING;
    }

    /** Leaves this stump for another pass and goes on to the next one. */
    private void passOver() {
        if (stump != null) {
            passedOver.add(stump.pos().asLong());
        }
        stump = null;
        sapling = null;
        phase = Phase.CHOOSE;
    }

    /** Open where the trunk was, and still standing on ground a tree could grow from. */
    private static boolean plantable(BotContext ctx, BlockPos pos) {
        return BlockPlacer.isReplaceable(ctx, pos)
                && !ctx.level.getBlockState(pos).liquid()
                && Saplings.canRoot(ctx.level.getBlockState(pos.below()));
    }

    /**
     * The sapling to put in this stump: the tree's own when one is carried and it will grow there,
     * otherwise the first carried sapling the ground accepts, otherwise none.
     */
    private static Item saplingFor(BotContext ctx, StumpLedger.Stump stump) {
        Item own = Saplings.forLog(stump.log());
        if (own != Items.AIR && InventoryHelper.count(ctx.player, own) > 0
                && grows(ctx, own, stump.pos())) {
            return own;
        }
        int slot = InventoryHelper.findSlot(ctx.player,
                stack -> Saplings.isSapling(stack) && grows(ctx, stack.getItem(), stump.pos()));
        return slot < 0 ? null : ctx.player.getInventory().getItem(slot).getItem();
    }

    /** Asked of the sapling itself, which knows what it will take root in better than any list. */
    private static boolean grows(BotContext ctx, Item item, BlockPos pos) {
        return item instanceof BlockItem block
                && block.getBlock().defaultBlockState().canSurvive(ctx.level, pos);
    }

    @Override
    public void onStop(BotContext ctx) {
        if (walk != null) {
            walk.stop(ctx);
            walk = null;
        }
        ctx.input.reset();
    }
}
