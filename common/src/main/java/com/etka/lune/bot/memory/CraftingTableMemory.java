package com.etka.lune.bot.memory;

import com.etka.lune.bot.BotContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * Remembers crafting tables the bot has placed or found, so later crafting steps can walk back to
 * them instead of burning a fresh table every time.
 * <p>
 * A table is only ever trusted if the chunk around it is loaded and the block is still a crafting
 * table. Tables that have been broken or that the bot already failed to reach are kept on a
 * blacklist so the bot doesn't spin on the same unreachable table forever.
 */
public final class CraftingTableMemory {

    private static final CraftingTableMemory INSTANCE = new CraftingTableMemory();

    private final Set<Long> knownTables = new HashSet<>();
    private final Set<Long> unreachable = new HashSet<>();

    private CraftingTableMemory() {}

    public static CraftingTableMemory get() {
        return INSTANCE;
    }

    /** Remembers a table the bot placed or successfully used. */
    public void remember(BlockPos pos) {
        knownTables.add(pos.asLong());
        unreachable.remove(pos.asLong());
    }

    /** Removes a table that no longer exists. */
    public void forget(BlockPos pos) {
        knownTables.remove(pos.asLong());
        unreachable.remove(pos.asLong());
    }

    /** Marks a table the bot couldn't reach or open; it won't be returned by {@link #findNearest}. */
    public void markUnreachable(BlockPos pos) {
        unreachable.add(pos.asLong());
    }

    /**
     * Finds the nearest remembered table that still exists. Returns null when none of the remembered
     * tables are still crafting tables in the current world snapshot.
     */
    public BlockPos findNearest(BotContext ctx, BlockPos centre, int maxRadius) {
        if (knownTables.isEmpty()) {
            return null;
        }

        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (long packed : knownTables) {
            BlockPos pos = BlockPos.of(packed);
            if (unreachable.contains(packed) || !isLoaded(ctx, pos)
                    || ctx.level.getBlockState(pos).getBlock() != Blocks.CRAFTING_TABLE) {
                continue;
            }
            double distance = pos.distSqr(centre);
            if (distance <= (long) maxRadius * maxRadius && distance < nearestDistance) {
                nearest = pos;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /** Counts remembered tables, ignoring whether they're currently loaded. */
    public int size() {
        return knownTables.size();
    }

    private static boolean isLoaded(BotContext ctx, BlockPos pos) {
        return ctx.level.isLoaded(pos);
    }
}
