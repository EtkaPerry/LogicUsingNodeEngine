package com.etka.lune.bot.task;

/**
 * Turns what a map draws into somewhere to walk.
 * <p>
 * A filled map stores a centre in world coordinates and a scale, and every marker on it stores a
 * position in the map's own little coordinate space: a signed byte, so -128 to 127 across the whole
 * picture, whatever area that picture covers. A treasure map's red cross is one of those markers.
 * Reading it is the difference between "there is a cross on this map" and "the chest is at 1240,
 * -376", and it is pure arithmetic, so it lives here where it can be checked without a world.
 * <p>
 * The transform is the inverse of the one the game uses when it places a marker: the marker index is
 * the offset from the centre, in blocks, divided by the blocks-per-pixel for the scale, doubled and
 * rounded. So a marker resolves to a square {@link #accuracy} blocks across rather than to an exact
 * block - at scale 3, half a marker step is thirty-two blocks, and no amount of arithmetic recovers
 * what the rounding threw away. That is why the job that uses this walks to the square and then
 * searches it, rather than trusting a single coordinate.
 */
public final class MapTargetPolicy {

    /** Widest scale vanilla maps use; a level-4 map is 2048 blocks across. */
    public static final int MAX_SCALE = 4;

    private MapTargetPolicy() {}

    /** Blocks per map pixel at this scale: 1, 2, 4, 8, 16. */
    public static int blocksPerPixel(int scale) {
        return 1 << Math.clamp(scale, 0, MAX_SCALE);
    }

    /** How far across the world the whole picture reaches, in blocks. */
    public static int coverage(int scale) {
        return 128 * blocksPerPixel(scale);
    }

    /**
     * How far out a decoded marker can be, in blocks.
     * <p>
     * Markers are stored at half-pixel resolution, which alone would put this at half the
     * blocks-per-pixel. It is a whole pixel because of how the game rounds: it adds a half and then
     * truncates toward zero, which rounds up on one side of the centre and down on the other, so a
     * marker west of centre can land a further step out than one east of it. Measured over the full
     * range, worst case ran to about three quarters of a pixel; a whole one is the honest number to
     * hand a caller.
     * <p>
     * A caller that promises better than this is lying about what the map said - which is why the
     * job that uses it walks to a square and searches, rather than announcing an exact block.
     */
    public static int accuracy(int scale) {
        return blocksPerPixel(scale);
    }

    /**
     * The world coordinate a marker sits at, on one axis.
     *
     * @param centre   the map's centre on this axis, in world coordinates
     * @param scale    the map's scale, 0 to {@link #MAX_SCALE}
     * @param marker   the marker's position on this axis, -128 to 127
     */
    public static int worldCoordinate(int centre, int scale, int marker) {
        return centre + Math.round(marker * blocksPerPixel(scale) / 2.0F);
    }

    /**
     * Whether a marker sits inside the picture rather than pinned to its edge.
     * <p>
     * The game clamps a marker to the border when the thing it points at is off the map, and a
     * clamped marker says only "somewhere that way". Walking to the edge of the picture and
     * declaring arrival would be wrong, so callers check this and say so instead.
     */
    public static boolean isOnTheMap(int marker) {
        return marker > -128 && marker < 127;
    }
}
