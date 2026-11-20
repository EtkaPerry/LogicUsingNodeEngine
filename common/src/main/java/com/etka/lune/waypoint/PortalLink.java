package com.etka.lune.waypoint;

import net.minecraft.core.BlockPos;

/**
 * A portal the bot knows of: where it stands, and which dimension it takes you to.
 *
 * <p>Kept apart from {@link Waypoint} and {@link Discovery} for the same reason they are kept
 * apart from each other. Nobody names a portal, so it is not a waypoint; and there are many of
 * them per dimension pair, so it is not a discovery either - a discovery is looked up by what it
 * is, and one answer replaces the last, while every portal is worth its own line because the
 * nearest one to where you are going is the one that shortens the trip.</p>
 *
 * <p>A record that only ever saw one side says what a vanilla Nether portal would lead to; one
 * the bot actually rode says where it went. The route to a place trusts the ridden kind no more
 * than the seen kind - a portal can be broken between visits - so a memory that turns out wrong
 * is forgotten, not argued with.</p>
 *
 * @param dimension the dimension the portal stands in, in the form a {@link Waypoint} stores
 * @param leadsTo   the dimension on the other side
 * @param foundAt   wall-clock milliseconds, so the list reads as a history
 */
public record PortalLink(String dimension, int x, int y, int z, String leadsTo, long foundAt) {

    public static PortalLink at(BlockPos pos, String dimension, String leadsTo, long foundAt) {
        return new PortalLink(dimension, pos.getX(), pos.getY(), pos.getZ(), leadsTo, foundAt);
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /**
     * Where vanilla's own rule sends a lit Nether portal in {@code dimension}: the Nether from
     * anywhere but the Nether, the Overworld from inside it. The pairing is hardcoded - vanilla
     * reads no dimension pair off the frame - so a Nether portal in a modded world still aims at
     * the Nether when it works at all. What a mod's own portal block does is that mod's to say.
     */
    public static String vanillaOtherEnd(String dimension) {
        return "minecraft:the_nether".equals(dimension)
                ? "minecraft:overworld"
                : "minecraft:the_nether";
    }
}
