package com.etka.lune.bot.memory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blocks the server would not let her break, wherever she was when she tried.
 *
 * <p>A claimed base, a spawn area, or a modpack that guards a block - LOTR's loot barrels and
 * chests refuse anybody who does not hold sneak, so that one player cannot empty them for
 * everybody - all answer a break the same way: the swing runs, the block stays, or comes straight
 * back. Nothing on the client says so ahead of time, so it is learned by trying, once, and kept
 * here so that no route plans through it again and no card swings at it again
 * ({@link com.etka.lune.bot.path.MovementHelper#isBreakable}).</p>
 *
 * <p>A refusal is a fact about the block that stands there now: once something else stands in
 * that spot it no longer applies. Lookups never write, because the route search reads this for
 * every block it prices.</p>
 */
public final class RefusedBreaks {

    private static final RefusedBreaks INSTANCE = new RefusedBreaks();
    /** A long session in a protected town is still a few dozen; this only bounds a pathological one. */
    private static final int MAX_ENTRIES = 4096;

    private final Map<Long, Block> refused = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75F, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Block> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private RefusedBreaks() {}

    public static RefusedBreaks get() {
        return INSTANCE;
    }

    public void remember(BlockPos pos, BlockState state) {
        refused.put(pos.asLong(), state.getBlock());
    }

    /** Whether the block standing at {@code pos} now is one the server already refused. */
    public boolean isRefused(BlockPos pos, BlockState state) {
        return !refused.isEmpty() && refused.get(pos.asLong()) == state.getBlock();
    }

    public int size() {
        return refused.size();
    }
}
