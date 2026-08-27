package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/**
 * Places blocks to cross a gap, one step at a time.
 * <p>
 * Each step puts a block at the next floor position, waits for it to appear, then walks onto it. The
 * next step is taken from that new position, so the bridge stays straight even over a long drop.
 */
public final class BridgeTask implements Task {

    public static final String FACING = "Facing";

    private final String directionChoice;
    private final int length;
    private final Set<Block> materials;

    private Direction direction;
    private BlockPos origin;
    private BlockPos currentFloor;
    private int placed;
    private int placementWaitTicks;
    private GotoTask stepForward;
    private String status = "";

    public BridgeTask(String directionChoice, int length, Set<Block> materials) {
        this.directionChoice = directionChoice;
        this.length = Math.max(1, length);
        this.materials = Set.copyOf(materials);
    }

    @Override
    public String name() {
        return "Bridge";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return new TaskProgress(placed, length, "blocks");
    }

    @Override
    public void onStart(BotContext ctx) {
        if (direction == null) {
            direction = FACING.equalsIgnoreCase(directionChoice)
                    ? ctx.player.getDirection()
                    : parseDirection(directionChoice, ctx.player.getDirection());
        }
        if (origin == null) {
            origin = ctx.player.blockPosition();
            currentFloor = origin.below();
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
        if (materials.isEmpty()) {
            status = "no bridge blocks selected";
            return TaskStatus.FAILED;
        }

        if (placed >= length) {
            status = "bridged " + placed + " blocks";
            return TaskStatus.SUCCESS;
        }

        BlockPos nextFloor = currentFloor.relative(direction);

        // If the next floor block is empty, place something there.
        if (BlockPlacer.isReplaceable(ctx, nextFloor)) {
            ctx.debug.placement(nextFloor, materials.isEmpty() ? "-"
                    : materials.iterator().next().getName().getString(), "bridge floor target");
            if (!hasMaterial(ctx)) {
                status = "no bridge blocks in inventory";
                ctx.debug.placement(nextFloor, "-", "no bridge material in inventory");
                return TaskStatus.FAILED;
            }
            if (!BlockPlacer.canPlaceAt(ctx, nextFloor)) {
                status = "nowhere to place the next block";
                ctx.debug.placement(nextFloor, "bridge block", "no support or placement space");
                return TaskStatus.FAILED;
            }
            BlockPlacer.PlacementResult placement = placeBlock(ctx, nextFloor);
            if (placement == BlockPlacer.PlacementResult.PLACED
                    || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
                placementWaitTicks = 0;
                status = "placed block " + (placed + 1) + "/" + length;
            } else if (!placement.isTransient() || ++placementWaitTicks > 12) {
                status = "could not place bridge block at " + nextFloor.toShortString()
                        + " (" + placement.name().toLowerCase() + ")";
                ctx.debug.decide("stop bridge: placement failed at " + nextFloor.toShortString());
                return TaskStatus.FAILED;
            } else {
                status = "placing block " + (placed + 1) + "/" + length;
            }
            return TaskStatus.RUNNING;
        }

        // Walk onto the next block (or the one that was already there).
        BlockPos nextFeet = nextFloor.above();
        if (ctx.player.blockPosition().equals(nextFeet)
                || (Mth.floor(ctx.player.getX()) == nextFeet.getX()
                        && Mth.floor(ctx.player.getZ()) == nextFeet.getZ()
                        && Mth.floor(ctx.player.getY()) == nextFeet.getY())) {
            placed++;
            currentFloor = nextFloor;
            if (stepForward != null) {
                stepForward.stop(ctx);
                stepForward = null;
            }
            return TaskStatus.RUNNING;
        }

        return walkTo(ctx, nextFeet, "crossing " + (placed + 1) + "/" + length);
    }

    private boolean hasMaterial(BotContext ctx) {
        for (Block material : materials) {
            Item item = material.asItem();
            if (item != null && InventoryHelper.findSlot(ctx.player, stack -> stack.is(item)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private BlockPlacer.PlacementResult placeBlock(BotContext ctx, BlockPos pos) {
        // Try every selected material until one is in the inventory and gets placed.
        BlockPlacer.PlacementResult last = BlockPlacer.PlacementResult.NO_MATERIAL;
        for (Block material : materials) {
            last = BlockPlacer.tryPlace(ctx, material, pos);
            if (last == BlockPlacer.PlacementResult.PLACED
                    || last == BlockPlacer.PlacementResult.ALREADY_PRESENT
                    || last.isTransient()) {
                return last;
            }
        }
        return last;
    }

    private TaskStatus walkTo(BotContext ctx, BlockPos target, String what) {
        if (stepForward == null) {
            stepForward = new GotoTask(new Goals.Block(target), false, false);
            stepForward.start(ctx);
        }
        TaskStatus result = stepForward.tick(ctx);
        if (result == TaskStatus.FAILED) {
            status = "couldn't step onto the bridge";
            return TaskStatus.FAILED;
        }
        status = what;
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(BotContext ctx) {
        if (stepForward != null) {
            stepForward.stop(ctx);
            stepForward = null;
        }
        ctx.input.reset();
    }
}
