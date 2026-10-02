package com.etka.lune.bot.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/**
 * Whether something is being carried up by a geyser.
 *
 * <p>Geysers came with 26.2. A block of potent sulfur with water standing on it erupts while a
 * magma block (every so often) or lava (all the time) lies under it, and everything in the column
 * over it - the water, and six blocks of air for every block of water - is pushed upward each tick.
 * That is {@code PotentSulfurBlockEntity}'s rule, restated here in terms every target shares:
 * 26.1.2 has no sulfur, so the block is recognised by its registry name and its state by the
 * state's own name, and on that version they simply never match.</p>
 *
 * <p>Read-only. It says where a geyser has hold of something; nothing here decides to use one.</p>
 */
public final class Geysers {

    private static final Identifier POTENT_SULFUR = Identifier.parse("minecraft:potent_sulfur");
    private static final String STATE = "potent_sulfur_state";
    /** The game erupts through at most four blocks of water standing on the sulfur... */
    static final int MAX_WATER = 4;
    /** ...and pushes up six blocks of the column for every one of them. */
    static final int LIFT_PER_WATER = 6;

    private Geysers() {}

    /** Whether an erupting geyser has hold of this entity now, in any column its box touches. */
    public static boolean lifting(Level level, Entity entity) {
        AABB box = entity.getBoundingBox();
        int feet = Mth.floor(box.minY);
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                if (liftedInColumn(level, x, z, feet, box)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a body between {@code feetY} and {@code headY} is inside the lift of a geyser whose
     * sulfur is at {@code sulfurY} under {@code water} blocks of water: from the block above the
     * sulfur up to six blocks per block of water, the box the game pushes.
     */
    static boolean inLift(int sulfurY, int water, double feetY, double headY) {
        if (water < 1 || water > MAX_WATER) {
            return false;
        }
        double bottom = sulfurY + 1;
        double top = bottom + water * LIFT_PER_WATER;
        return feetY < top && headY > bottom;
    }

    /** Looks down one column for the first thing that is not open air or water, and asks it. */
    private static boolean liftedInColumn(Level level, int x, int z, int feet, AABB box) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, feet, z);
        int reach = MAX_WATER * (LIFT_PER_WATER + 1) + 1;
        for (int y = feet; y >= feet - reach; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            // Anything solid between her and a geyser shields her from it, so only the first
            // solid block down the column matters.
            return erupting(state) && inLift(y, waterAbove(level, pos), box.minY, box.maxY);
        }
        return false;
    }

    /**
     * The water standing on the sulfur, counted the way the game counts it: source blocks with
     * nothing solid in them, then open air. Deeper water, or a lid, is no geyser at all.
     */
    private static int waterAbove(Level level, BlockPos sulfur) {
        BlockPos.MutableBlockPos pos = sulfur.mutable();
        for (int water = 0; water <= MAX_WATER; water++) {
            pos.move(0, 1, 0);
            boolean open = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
            if (!level.getFluidState(pos).isSourceOfType(Fluids.WATER)) {
                return open ? water : 0;
            }
            if (!open) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean erupting(BlockState state) {
        if (!POTENT_SULFUR.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
            return false;
        }
        Property<?> property = state.getBlock().getStateDefinition().getProperty(STATE);
        if (property == null) {
            return false;
        }
        String value = valueName(state, property);
        return value.equals("erupting") || value.equals("continuous");
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
