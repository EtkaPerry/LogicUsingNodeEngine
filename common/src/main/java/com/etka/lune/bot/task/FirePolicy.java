package com.etka.lune.bot.task;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * Pure rules for putting out a burning player; kept testable without a world.
 *
 * <p>Water is the whole answer. The fire goes out on the first tick the body is inside a water
 * block, so there are only two ways to do it: bring the water to the body with a bucket, or take the
 * body to the water. The bucket is the quick one - a pour at the feet and a scoop to get it back -
 * and it is also the one that does nothing where poured water boils away, which is what the Nether
 * does to it. Running into water in sight is what is left there, and what is left anywhere without a
 * bucket.
 */
final class FirePolicy {

    /** How a burning player is being put out. */
    enum Method { NONE, POUR, RUN }

    /**
     * How high a freshly poured block of water stands. A source is eight ninths of a block, so a
     * body standing on top of the cell it went into is not in it.
     */
    static final double WATER_HEIGHT = 8.0 / 9.0;

    private FirePolicy() {}

    /**
     * The bucket wherever it works, the run where it does not, and nothing when neither can.
     *
     * @param canPour a water bucket is carried, water poured here stays, and the bucket has not
     *                already had its go at this fire
     * @param canRun  water is in sight or already being run to, and no run has failed on this fire
     */
    static Method choose(boolean canPour, boolean canRun) {
        if (canPour) {
            return Method.POUR;
        }
        return canRun ? Method.RUN : Method.NONE;
    }

    /**
     * Whether a block of water poured into {@code water} would touch a body with these bounds.
     *
     * <p>Asked of the cell the bucket would actually fill rather than of "the block under the feet",
     * because those differ in exactly the cases that matter: a leaf or a full slab underfoot takes
     * the water inside itself, below the body, and a tuft of grass moves the pour up a block.
     */
    static boolean reachesBody(AABB body, BlockPos water) {
        return body.intersects(water.getX(), water.getY(), water.getZ(),
                water.getX() + 1.0, water.getY() + WATER_HEIGHT, water.getZ() + 1.0);
    }
}
