package com.etka.lune.bot.util;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.function.LongConsumer;

/**
 * Every block the bot has looked through or looked at, and which of the two.
 *
 * <p>A search that is not allowed to know what it has not seen needs somewhere to keep what it
 * has. Sight lines are cast with {@link Vision#firstSeen}; each one crosses a run of cells the
 * eyes passed through and ends on the block that stopped them, and both halves are written down
 * here. A cell nobody has looked at is simply absent - unknown, rather than assumed to be rock
 * or assumed to be air.</p>
 *
 * <p>The newest look wins. A wall that has since been dug through reads as open the next time a
 * line passes it, and a gap somebody has since filled reads as stopped.</p>
 */
public final class SightMap {

    public static final byte UNSEEN = 0;
    /** The eyes passed through this cell. */
    public static final byte OPEN = 1;
    /** The eyes stopped here. */
    public static final byte STOPPED = 2;

    /** Longer than any look is cast, so a bad pair of points cannot walk the grid forever. */
    private static final int MAX_CELLS = 1024;

    private final Long2ByteOpenHashMap cells = new Long2ByteOpenHashMap();

    public byte state(BlockPos pos) {
        return state(pos.asLong());
    }

    public byte state(long key) {
        return cells.get(key);
    }

    public boolean isOpen(long key) {
        return cells.get(key) == OPEN;
    }

    public int size() {
        return cells.size();
    }

    /**
     * Records one sight line: every cell from {@code from} to {@code to} as open, except the one
     * the line stopped in, which is recorded as stopped.
     *
     * @param stoppedAt the block the line stopped at, or null when it ran its whole length
     * @param opened    told about each cell this line is the first to see through
     */
    public void trace(Vec3 from, Vec3 to, BlockPos stoppedAt, LongConsumer opened) {
        long stop = stoppedAt == null ? Long.MIN_VALUE : stoppedAt.asLong();
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        int x = Mth.floor(from.x);
        int y = Mth.floor(from.y);
        int z = Mth.floor(from.z);
        int stepX = dx > 0.0 ? 1 : dx < 0.0 ? -1 : 0;
        int stepY = dy > 0.0 ? 1 : dy < 0.0 ? -1 : 0;
        int stepZ = dz > 0.0 ? 1 : dz < 0.0 ? -1 : 0;
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        // How far along the line, as a fraction of it, the next boundary on each axis is.
        double nextX = stepX == 0 ? Double.POSITIVE_INFINITY
                : (stepX > 0 ? x + 1 - from.x : from.x - x) * deltaX;
        double nextY = stepY == 0 ? Double.POSITIVE_INFINITY
                : (stepY > 0 ? y + 1 - from.y : from.y - y) * deltaY;
        double nextZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : (stepZ > 0 ? z + 1 - from.z : from.z - z) * deltaZ;
        for (int visited = 0; visited < MAX_CELLS; visited++) {
            long key = BlockPos.asLong(x, y, z);
            if (key == stop) {
                break;
            }
            if (cells.put(key, OPEN) != OPEN && opened != null) {
                opened.accept(key);
            }
            if (nextX <= nextY && nextX <= nextZ) {
                if (nextX > 1.0) {
                    break;
                }
                x += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                if (nextY > 1.0) {
                    break;
                }
                y += stepY;
                nextY += deltaY;
            } else {
                if (nextZ > 1.0) {
                    break;
                }
                z += stepZ;
                nextZ += deltaZ;
            }
        }
        if (stoppedAt != null) {
            cells.put(stop, STOPPED);
        }
    }
}
