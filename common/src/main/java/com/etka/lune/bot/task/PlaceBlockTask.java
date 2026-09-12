package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Set;

/**
 * Puts one block in one place.
 * <p>
 * The smallest building action there is, and deliberately so: it places a block and it does not dig,
 * fetch, walk a route it was not given, or decide what to build. Everything larger is a routine made
 * of these and of the cards beside them - a wall is this card behind a Walk, a floor is this card
 * behind a loop.
 * <p>
 * <b>Where</b> is the whole design of the card. A bot cannot be told "the block you are looking at",
 * because at the moment it runs the bot's head belongs to whatever card is driving it - so the
 * answer has to be a spot that means the same thing whenever the pulse arrives. Three do:
 * <ul>
 *   <li>relative to the bot - in front of it, under it, over it - which is what composes with Walk;
 *   <li>a coordinate, pointed at with the crosshair while writing the routine;
 *   <li>a waypoint, by name, which the Save Waypoint card can write from inside the same routine.
 * </ul>
 */
public final class PlaceBlockTask implements Task {

    /** Ticks of trying before concluding the spot cannot be reached or clicked. */
    private static final int DEADLINE_TICKS = 100;
    /** Close enough to click a block face, a little inside the vanilla 4.5 reach. */
    private static final double REACH = 4.0;

    /** Where the block goes. */
    public enum Where {
        IN_FRONT("In front"),
        UNDER("Under me"),
        ABOVE("Above me"),
        COORDINATES("Coordinates"),
        WAYPOINT("Waypoint");

        private final String label;

        Where(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean isRelative() {
            return this == IN_FRONT || this == UNDER || this == ABOVE;
        }

        public static List<String> labels() {
            return java.util.Arrays.stream(values()).map(Where::label).toList();
        }

        public static Where fromLabel(String label) {
            for (Where where : values()) {
                if (where.label.equalsIgnoreCase(label)) {
                    return where;
                }
            }
            return IN_FRONT;
        }
    }

    private final Set<Block> materials;
    private final Where where;
    private final BlockPos coordinates;

    private BlockPos target;
    private GotoTask approach;
    private int ticks;
    private boolean placed;
    private final StatusText status = new StatusText();

    public PlaceBlockTask(Set<Block> materials, Where where, BlockPos coordinates) {
        this.materials = materials == null ? Set.of() : Set.copyOf(materials);
        this.where = where == null ? Where.IN_FRONT : where;
        this.coordinates = coordinates;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.place_block.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Place block");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public boolean madeProgress() {
        return placed;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (materials.isEmpty()) {
            status.set("lune.status.fail.pick_block_to_place");
            return TaskStatus.FAILED;
        }

        // Resolved once. A relative spot has to be pinned before the bot walks anywhere, or "in
        // front of me" walks away from itself, one block at a time, forever.
        if (target == null) {
            target = resolveTarget(ctx);
            if (target == null) {
                if (where == Where.WAYPOINT) {
                    status.set("lune.status.place_block.waypoint_not_world");
                } else {
                    status.set("lune.status.place_block.no_spot_given_place_into");
                }
                return TaskStatus.FAILED;
            }
        }

        if (ctx.level.getBlockState(target).isAir() || BlockPlacer.isReplaceable(ctx, target)) {
            if (++ticks > DEADLINE_TICKS) {
                status.set("lune.status.place_block.could_not_place_block", target.toShortString());
                return TaskStatus.FAILED;
            }
        } else {
            // Something is there. If it is what was asked for, the job is already done; if not,
            // clearing it is the Mine card's job, not this one's.
            boolean wanted = materials.stream().anyMatch(block -> ctx.level.getBlockState(target).is(block));
            if (wanted) {
                status.set("lune.status.place_block.already", ctx.level.getBlockState(target).getBlock().getName().getString(), target.toShortString());
            } else {
                status.set("lune.status.place_block.already_2", ctx.level.getBlockState(target).getBlock().getName().getString(), target.toShortString());
            }
            return wanted ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }

        Block material = carriedMaterial(ctx);
        if (material == null) {
            status.set("lune.status.place_block.not_carrying", describeMaterials());
            return TaskStatus.FAILED;
        }

        if (!inReach(ctx, target)) {
            return walkTo(ctx);
        }
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }

        BlockPlacer.PlacementResult result = BlockPlacer.tryPlace(ctx, material, target);
        return switch (result) {
            case PLACED, ALREADY_PRESENT -> {
                placed = true;
                status.set("lune.status.place_block.placed", material.getName().getString(), target.toShortString());
                yield TaskStatus.SUCCESS;
            }
            case NO_SUPPORT -> {
                status.set("lune.status.place_block.nothing_place_against", target.toShortString());
                yield TaskStatus.FAILED;
            }
            case BLOCKED -> {
                status.set("lune.status.place_block.something_already", target.toShortString());
                yield TaskStatus.FAILED;
            }
            case NO_MATERIAL -> {
                status.set("lune.status.place_block.not_carrying", describeMaterials());
                yield TaskStatus.FAILED;
            }
            // Aiming, jumping clear of its own feet, and waiting for the server to confirm are all
            // states the next tick resolves. The deadline above is what stops them being forever.
            default -> {
                status.set("lune.status.place_block.placing", target.toShortString());
                yield TaskStatus.RUNNING;
            }
        };
    }

    /** The spot to build into, in the terms the card was configured with. */
    private BlockPos resolveTarget(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        return switch (where) {
            case IN_FRONT -> feet.relative(ctx.player.getDirection());
            case UNDER -> feet.below();
            case ABOVE -> feet.above(2);
            case COORDINATES, WAYPOINT -> coordinates;
        };
    }

    private TaskStatus walkTo(BotContext ctx) {
        if (approach == null) {
            approach = new GotoTask(new Goals.Adjacent(target, REACH), false, true);
            approach.start(ctx);
        }
        TaskStatus walk = approach.tick(ctx);
        if (walk == TaskStatus.FAILED) {
            approach.stop(ctx);
            approach = null;
            status.set("lune.status.place_block.cannot_get_within_reach", target.toShortString());
            return TaskStatus.FAILED;
        }
        status.set("lune.status.place_block.walking", target.toShortString());
        return TaskStatus.RUNNING;
    }

    private static boolean inReach(BotContext ctx, BlockPos pos) {
        return ctx.player.getEyePosition()
                .distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= REACH * REACH;
    }

    /** The first of the chosen blocks the bot is actually carrying. */
    private Block carriedMaterial(BotContext ctx) {
        for (Block block : materials) {
            Item item = block.asItem();
            if (InventoryHelper.findSlot(ctx.player, stack -> stack.is(item)) >= 0) {
                return block;
            }
        }
        return null;
    }

    private String describeMaterials() {
        if (materials.isEmpty()) {
            return Lang.get("lune.status.place_block.anything_to_place");
        }
        Block first = materials.iterator().next();
        return materials.size() == 1
                ? first.getName().getString()
                : Lang.get("lune.status.place_block.first_or_any_chosen",
                        first.getName().getString(), materials.size());
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
