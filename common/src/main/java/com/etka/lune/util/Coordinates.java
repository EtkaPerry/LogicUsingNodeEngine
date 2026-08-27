package com.etka.lune.util;

import net.minecraft.core.BlockPos;

/**
 * Parsing and formatting for typed coordinates.
 * <p>
 * Deliberately forgiving about separators - "100 20 60", "100, 20, 60" and "100,20,60" all work,
 * because the natural thing to do is paste whatever the F3 screen or a friend's message gave you.
 */
public final class Coordinates {

    private Coordinates() {}

    /** @return the parsed position, or null if the text isn't three whole numbers */
    public static BlockPos parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.trim().split("[\\s,]+");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Formats for display and for round-tripping back through {@link #parse}. */
    public static String format(BlockPos pos) {
        return pos == null ? "" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
