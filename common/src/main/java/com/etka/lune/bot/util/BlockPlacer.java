package com.etka.lune.bot.util;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.path.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Places a block the way a player does: hold it, look at the face of an existing block, right-click.
 * <p>
 * Minecraft has no "place in mid-air" action - every placement is an interaction with a neighbouring
 * block's face. So the real work here is finding a neighbour that already exists and is solid enough
 * to click, which is also why placing into open space (a bridge over a gap) has to build outward
 * from something rather than dropping blocks anywhere.
 */
public final class BlockPlacer {

    private static final double REACH = 4.5;
    private static final float AIM_TOLERANCE = 15.0F;

    private BlockPlacer() {}

    /** Explains one placement attempt so callers can stop retrying a deterministic failure. */
    public enum PlacementResult {
        PLACED,
        ALREADY_PRESENT,
        WAITING_FOR_AIM,
        WAITING_FOR_JUMP,
        CLICK_NOT_CONFIRMED,
        NO_MATERIAL,
        NO_SUPPORT,
        BLOCKED,
        OUT_OF_REACH;

        /** Human-readable result; name() remains the diagnostic identifier. */
        public String displayName() {
            return switch (this) {
                case PLACED -> Lang.get("lune.placement.placed");
                case ALREADY_PRESENT -> Lang.get("lune.placement.already_present");
                case WAITING_FOR_AIM -> Lang.get("lune.placement.waiting_for_aim");
                case WAITING_FOR_JUMP -> Lang.get("lune.placement.waiting_for_jump");
                case CLICK_NOT_CONFIRMED -> Lang.get("lune.placement.click_not_confirmed");
                case NO_MATERIAL -> Lang.get("lune.placement.no_material");
                case NO_SUPPORT -> Lang.get("lune.placement.no_support");
                case BLOCKED -> Lang.get("lune.placement.blocked");
                case OUT_OF_REACH -> Lang.get("lune.placement.out_of_reach");
            };
        }

        public boolean isTransient() {
            return this == WAITING_FOR_AIM
                    || this == WAITING_FOR_JUMP
                    || this == CLICK_NOT_CONFIRMED;
        }
    }

    /** Whether {@code pos} is free to build into. */
    public static boolean isReplaceable(BotContext ctx, BlockPos pos) {
        BlockState state = ctx.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced() || state.is(Blocks.WATER);
    }

    /**
     * Finds a neighbouring block whose face can be clicked to place into {@code pos}.
     *
     * @return the direction from {@code pos} toward that neighbour, or null if it's floating free
     */
    public static Direction findSupport(BotContext ctx, BlockPos pos) {
        // Down first: placing on top of the floor is the most reliable, and it's what a player does.
        for (Direction direction : new Direction[]{
                Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
            BlockPos neighbour = pos.relative(direction);
            if (MovementHelper.isSolidFloor(ctx.level, neighbour)
                    || ctx.level.getBlockState(neighbour).isSolid()) {
                return direction;
            }
        }
        return null;
    }

    /** True if a block could be placed here this tick, ignoring whether we hold one. */
    public static boolean canPlaceAt(BotContext ctx, BlockPos pos) {
        return isReplaceable(ctx, pos)
                && findSupport(ctx, pos) != null
                && canOccupyPlacementSpace(ctx, pos)
                && ctx.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= REACH * REACH;
    }

    /**
     * Finds a carried full-motion block suitable for a quick combat shelter.
     * Decorative blocks such as torches, rails and flowers are deliberately excluded: a block that
     * can be placed is not necessarily a block that stops an Enderman from reaching the player.
     */
    public static Block findSolidMaterial(BotContext ctx) {
        Block[] preferred = {
                Blocks.COBBLESTONE, Blocks.STONE, Blocks.DIRT, Blocks.NETHERRACK,
                Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS
        };
        for (Block block : preferred) {
            if (InventoryHelper.findSlot(ctx.player, stack -> stack.is(block.asItem())) >= 0) {
                return block;
            }
        }

        int slot = InventoryHelper.findSlot(ctx.player, stack -> {
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return false;
            }
            BlockState state = blockItem.getBlock().defaultBlockState();
            return state.blocksMotion() && !state.isAir();
        });
        if (slot < 0) {
            return null;
        }
        ItemStack stack = ctx.player.getInventory().getItem(slot);
        return stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
    }

    private static boolean intersectsPlayer(BotContext ctx, BlockPos pos) {
        return ctx.player.getBoundingBox().intersects(new AABB(pos));
    }

    /**
     * Allows the one legitimate self-overlap: placing a pillar into the current feet block. The
     * interaction itself is what makes the player leave the ground, so requiring the bounding box
     * to be clear before the jump can be applied creates a circular failure: the recovery can never
     * place its first block. Vanilla still validates the actual use interaction; the player must
     * remain clear of all ordinary bridge, shelter, and work-site placements.
     */
    private static boolean canOccupyPlacementSpace(BotContext ctx, BlockPos pos) {
        return !intersectsPlayer(ctx, pos)
                || pos.equals(ctx.player.blockPosition());
    }

    /**
     * Attempts to place {@code block} at {@code pos}. Aims first and only clicks once on target, so
     * it may need several ticks.
     *
     * @return true once the block is actually there
     */
    public static boolean place(BotContext ctx, Block block, BlockPos pos) {
        PlacementResult result = tryPlace(ctx, block, pos);
        return result == PlacementResult.PLACED || result == PlacementResult.ALREADY_PRESENT;
    }

    /** Attempts one vanilla placement and returns a reason when the caller should stop retrying. */
    public static PlacementResult tryPlace(BotContext ctx, Block block, BlockPos pos) {
        String blockName = block.getName().getString();
        if (ctx.level.getBlockState(pos).is(block)) {
            ctx.debug.placement(pos, blockName, "already present");
            return PlacementResult.ALREADY_PRESENT;
        }
        if (!isReplaceable(ctx, pos)) {
            ctx.debug.placement(pos, blockName, "blocked by "
                    + ctx.level.getBlockState(pos).getBlock().getName().getString());
            return PlacementResult.BLOCKED;
        }
        Item item = block.asItem();
        if (InventoryHelper.equip(ctx, stack -> stack.is(item) && stack.getItem() instanceof BlockItem) < 0) {
            ctx.debug.placement(pos, blockName, "no " + blockName + " in inventory");
            return PlacementResult.NO_MATERIAL;
        }
        Direction toSupport = findSupport(ctx, pos);
        if (toSupport == null) {
            ctx.debug.placement(pos, blockName, "no solid support face");
            return PlacementResult.NO_SUPPORT;
        }
        if (ctx.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            ctx.debug.placement(pos, blockName, "out of reach");
            return PlacementResult.OUT_OF_REACH;
        }

        // A jump-pillar deliberately places into the block currently occupied by the player's
        // feet. The interaction can be requested on the same tick as the jump; waiting for a
        // prior airborne tick makes the recovery loop miss the narrow placement window. Every
        // other placement keeps the collision guard.
        if (!canOccupyPlacementSpace(ctx, pos)) {
            if (ctx.player.onGround()) {
                ctx.input.jump = true;
            }
            ctx.debug.placement(pos, blockName, "waiting to clear placement space");
            return PlacementResult.WAITING_FOR_JUMP;
        }

        BlockPos support = pos.relative(toSupport);
        // Click the support's face that points back at the empty space we want to fill.
        Direction face = toSupport.getOpposite();
        Vec3 hit = Vec3.atCenterOf(support).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);

        ctx.look.lookAt(ctx.player, hit);
        if (!ctx.look.isLookingAt(ctx.player, hit, AIM_TOLERANCE)) {
            ctx.debug.placement(pos, blockName, "turning to support " + support.toShortString());
            return PlacementResult.WAITING_FOR_AIM;
        }

        ctx.gameMode.useItemOn(ctx.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, support, false));
        ctx.player.swing(InteractionHand.MAIN_HAND);
        if (ctx.level.getBlockState(pos).is(block)) {
            ctx.debug.blocksPlaced++;
            ctx.debug.placement(pos, blockName, "placed");
            return PlacementResult.PLACED;
        }
        ctx.debug.placement(pos, blockName, "clicked; waiting for world confirmation");
        return PlacementResult.CLICK_NOT_CONFIRMED;
    }

    /**
     * Right-clicks an existing block, e.g. to open a crafting table.
     * <p>
     * Aim and distance are not the same thing as line of sight, and the difference matters: without
     * the ray cast a bot opens a crafting table through the wall it is standing behind, or a chest
     * through the hull of the shipwreck it never got into. No player can do that. It also hides
     * real failures - the route did not arrive, and nothing ever says so, because the interaction
     * worked anyway.
     */
    public static boolean use(BotContext ctx, BlockPos pos) {
        Vec3 centre = Vec3.atCenterOf(pos);
        ctx.look.lookAt(ctx.player, centre);
        if (!ctx.look.isLookingAt(ctx.player, centre, AIM_TOLERANCE)) {
            return false;
        }
        Vec3 eye = ctx.player.getEyePosition();
        if (eye.distanceToSqr(centre) > REACH * REACH) {
            return false;
        }
        if (!hasLineOfSight(ctx, pos)) {
            ctx.debug.decide("cannot reach " + ctx.level.getBlockState(pos).getBlock()
                    .getName().getString() + "; something is in the way");
            return false;
        }
        BlockHitResult hit = ctx.level.clip(new ClipContext(eye, centre,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player));
        ctx.gameMode.useItemOn(ctx.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit.getLocation(), hit.getDirection(), pos, false));
        return true;
    }

    /**
     * Whether the player could click the block from their current position if they were already
     * looking at it. Distance and aim are intentionally separate: callers use this to decide
     * whether a route has arrived at a real interaction position, while {@link #use} still eases
     * the view and waits for its normal aim tolerance before sending the click.
     */
    public static boolean hasLineOfSight(BotContext ctx, BlockPos pos) {
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        if (eye.distanceToSqr(centre) > REACH * REACH) {
            return false;
        }
        // OUTLINE, matching the crosshair: a vine or a pane of glass stops a right-click the same
        // way it stops the player's own.
        BlockHitResult hit = ctx.level.clip(new ClipContext(eye, centre,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /** A flat, reachable spot on solid ground, never on top of a tree or inside leaves. */
    public static BlockPos findPlacementSpot(BotContext ctx) {
        return findPlacementSpot(ctx, java.util.Collections.emptySet());
    }

    /** A flat, reachable spot on solid ground, skipping spots that have already failed. */
    public static BlockPos findPlacementSpot(BotContext ctx, java.util.Set<Long> skip) {
        BlockPos feet = ctx.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (skip.contains(candidate.asLong())) {
                        continue;
                    }
                    if (!canPlaceAt(ctx, candidate)) {
                        continue;
                    }
                    BlockState support = ctx.level.getBlockState(candidate.below());
                    if (support.is(BlockTags.LEAVES) || support.is(BlockTags.LOGS)) {
                        continue;
                    }
                    // Foliage above would suffocate the player when they stand up from the table.
                    if (!MovementHelper.isPassable(ctx.level, candidate.above())) {
                        continue;
                    }
                    double distance = ctx.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(candidate));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }

        // Last resort: jump and place at the player's own feet, but only on solid ground (not a log
        // or leaves) and only if there is headroom to actually jump.
        if (best == null && !skip.contains(feet.asLong())) {
            BlockState support = ctx.level.getBlockState(feet.below());
            if (!support.is(BlockTags.LEAVES) && !support.is(BlockTags.LOGS)
                    && isReplaceable(ctx, feet)
                    && (MovementHelper.isSolidFloor(ctx.level, feet.below()) || support.isSolid())
                    && MovementHelper.isPassable(ctx.level, feet.above())
                    && MovementHelper.isPassable(ctx.level, feet.above(2))) {
                best = feet;
            }
        }
        return best;
    }
}
