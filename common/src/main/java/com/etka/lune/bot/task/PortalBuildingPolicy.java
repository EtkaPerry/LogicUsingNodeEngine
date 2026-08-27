package com.etka.lune.bot.task;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Safe, support-preserving frame orders available to every Nether portal builder. */
public final class PortalBuildingPolicy {

    public static final String ALTERNATING = PortalOrderPolicy.ALTERNATING;
    public static final String NEAREST_COLUMN = PortalOrderPolicy.NEAREST_COLUMN;
    public static final String FARTHEST_COLUMN = PortalOrderPolicy.FARTHEST_COLUMN;
    public static final List<String> ACTIONS = PortalOrderPolicy.ACTIONS;
    public static final String DEFAULT = ALTERNATING;

    private PortalBuildingPolicy() {}

    /**
     * Reorders a complete 4x5 frame without ever asking for an unsupported floating block.
     * Open-corner ten-block frames retain their existing order because they require scaffolding.
     */
    static List<BlockPos> order(BlockPos base, boolean includeCorners, String action,
                                double playerX) {
        return PortalOrderPolicy.offsets(includeCorners, action, playerX - base.getX()).stream()
                .map(offset -> base.offset(offset.x(), offset.y(), 0))
                .toList();
    }

    public static String frameBucket(boolean includeCorners, int obsidian) {
        return (includeCorners ? "complete" : "open") + "-" + (obsidian <= 10 ? "ten" : "fourteen");
    }
}
