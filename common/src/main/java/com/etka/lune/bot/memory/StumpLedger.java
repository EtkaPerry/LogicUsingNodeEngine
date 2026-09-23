package com.etka.lune.bot.memory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where trees stood before Chop Wood took them down.
 *
 * <p>A note, not a behaviour. Chop Wood writes a line here each time it takes the lowest log of a
 * trunk that stood on ground a sapling could root in, and does nothing else about it: whether that
 * place is ever planted again is the Replant Trees card's job, and a task without that card leaves
 * a clearing behind exactly as it always did. The same bargain as the death note and Recover Death
 * Drop - the one who was there writes down where, and a separate card decides what to do about
 * it.</p>
 *
 * <p>It has to be a ledger rather than something the chopping card keeps to itself, because the
 * sapling for a tree arrives long after the tree is gone: leaves decay on their own clock, the drops
 * are swept up by a later Loot card, and by then the Chop Wood card that felled it has finished.</p>
 *
 * <h2>Belongs to one world</h2>
 *
 * <p>A position means nothing without the world it was in. The ledger remembers which one it was
 * written in, weakly, and empties itself the first time it is asked about any other - joining a
 * server, loading another save, or stepping through a portal. Planting a sapling in the Nether at
 * the coordinates of a birch felled in the Overworld is the mistake that rule exists to rule out.</p>
 */
public final class StumpLedger {

    /** A felled tree: where its trunk met the ground, and which wood it was. */
    public record Stump(BlockPos pos, Block log) {}

    /**
     * More than a long lumber shift fells before it replants, and small enough that a forgotten
     * ledger does not become a map of every tree the bot has ever touched.
     */
    static final int CAPACITY = 256;

    private static final StumpLedger INSTANCE = new StumpLedger();

    /** Insertion-ordered, so the oldest note is the one dropped when the ledger is full. */
    private final Map<Long, Stump> stumps = new LinkedHashMap<>();
    private WeakReference<Object> world = new WeakReference<>(null);

    StumpLedger() {}

    public static StumpLedger get() {
        return INSTANCE;
    }

    /** Writes down a felled tree. A second note for the same place replaces the first. */
    public synchronized void note(Object level, BlockPos pos, Block log) {
        if (level == null || pos == null || log == null) {
            return;
        }
        bind(level);
        BlockPos at = pos.immutable();
        stumps.remove(at.asLong());
        stumps.put(at.asLong(), new Stump(at, log));
        Iterator<Long> oldest = stumps.keySet().iterator();
        while (stumps.size() > CAPACITY && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /**
     * Stumps within {@code radius} blocks of {@code centre}, nearest first.
     *
     * <p>Distance is measured in three dimensions, so a tree felled on the cliff above the bot is
     * not mistaken for one beside it.</p>
     */
    public synchronized List<Stump> near(Object level, BlockPos centre, int radius) {
        List<Stump> found = new ArrayList<>();
        if (level == null || centre == null) {
            return found;
        }
        bind(level);
        long limit = (long) radius * radius;
        for (Stump stump : stumps.values()) {
            if (stump.pos().distSqr(centre) <= limit) {
                found.add(stump);
            }
        }
        found.sort(Comparator.comparingDouble(stump -> stump.pos().distSqr(centre)));
        return found;
    }

    /** Crosses a stump off, because it has been planted or can never be. */
    public synchronized void forget(BlockPos pos) {
        if (pos != null) {
            stumps.remove(pos.asLong());
        }
    }

    public synchronized int size() {
        return stumps.size();
    }

    synchronized void clear() {
        stumps.clear();
        world = new WeakReference<>(null);
    }

    /** Ties the ledger to the world it is being asked about, emptying it if that world is new. */
    private void bind(Object level) {
        if (world.get() != level) {
            stumps.clear();
            world = new WeakReference<>(level);
        }
    }
}
