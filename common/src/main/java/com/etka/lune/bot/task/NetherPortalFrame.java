package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** Shared geometry and site selection for every task that builds a Nether portal. */
final class NetherPortalFrame {

    private static final int WIDTH = 4;
    private static final int HEIGHT = 5;

    private NetherPortalFrame() {}

    /**
     * Returns the placement order a player can build from the floor upward. Omitting corners is
     * the vanilla minimum: the portal only needs the ten blocks around its 2x3 opening.
     */
    static List<BlockPos> positions(BlockPos base, boolean includeCorners) {
        List<BlockPos> positions = new ArrayList<>(includeCorners ? 14 : 10);
        for (int x = 0; x < WIDTH; x++) {
            if (includeCorners || x == 1 || x == 2) {
                positions.add(base.offset(x, 0, 0));
            }
        }
        for (int y = 1; y < HEIGHT - 1; y++) {
            positions.add(base.offset(0, y, 0));
            positions.add(base.offset(WIDTH - 1, y, 0));
        }
        for (int x = 0; x < WIDTH; x++) {
            if (includeCorners || x == 1 || x == 2) {
                positions.add(base.offset(x, HEIGHT - 1, 0));
            }
        }
        return positions;
    }

    static boolean isCorner(BlockPos base, BlockPos pos) {
        int x = pos.getX() - base.getX();
        int y = pos.getY() - base.getY();
        return pos.getZ() == base.getZ()
                && (x == 0 || x == WIDTH - 1)
                && (y == 0 || y == HEIGHT - 1);
    }

    /** Finds a nearby flat, empty frame site without inspecting unloaded terrain. */
    static BlockPos findBase(BotContext ctx, boolean includeCorners, boolean allowOccupiedCorners) {
        BlockPos feet = ctx.player.blockPosition();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!MovementHelper.isSolidFloor(ctx.level, candidate.below())) {
                        continue;
                    }
                    boolean clear = true;
                    for (BlockPos frame : positions(candidate, includeCorners)) {
                        if (frameCanBeUsed(ctx, candidate, frame, allowOccupiedCorners)) {
                            continue;
                        }
                        clear = false;
                        break;
                    }
                    for (int x = 1; clear && x <= 2; x++) {
                        for (int y = 1; y <= 3; y++) {
                            if (!BlockPlacer.isReplaceable(ctx, candidate.offset(x, y, 0))) {
                                clear = false;
                                break;
                            }
                        }
                    }
                    if (clear && candidate.distSqr(feet) <= 64) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean frameCanBeUsed(BotContext ctx, BlockPos base, BlockPos pos,
                                          boolean allowOccupiedCorners) {
        if (BlockPlacer.isReplaceable(ctx, pos)
                || ctx.level.getBlockState(pos).is(Blocks.OBSIDIAN)) {
            return true;
        }
        return allowOccupiedCorners && isCorner(base, pos)
                && ctx.level.getBlockState(pos).isSolid();
    }
}
