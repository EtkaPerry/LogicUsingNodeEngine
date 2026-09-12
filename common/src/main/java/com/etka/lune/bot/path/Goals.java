package com.etka.lune.bot.path;

import com.etka.lune.util.Lang;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * The concrete goals Lune uses. All heuristics are octile distance (diagonal-aware and never
 * larger than the true cost), which keeps A* admissible while still pulling hard toward the target.
 */
public final class Goals {

    /** Cost of one straight step; matches the pathfinder's own edge costs. */
    static final double STEP = 1.0;
    /** Cost of one diagonal step. */
    static final double DIAGONAL = Math.sqrt(2.0);

    private Goals() {}

    /** Octile distance: the cheapest possible route ignoring obstacles. */
    static double octile(int dx, int dz) {
        int ax = Math.abs(dx);
        int az = Math.abs(dz);
        int diagonal = Math.min(ax, az);
        int straight = Math.abs(ax - az);
        return diagonal * DIAGONAL + straight * STEP;
    }

    /** Stand on one exact block. */
    public record Block(BlockPos target) implements Goal {
        @Override
        public boolean isReached(BlockPos pos) {
            return pos.equals(target);
        }

        @Override
        public double heuristic(BlockPos pos) {
            return octile(pos.getX() - target.getX(), pos.getZ() - target.getZ())
                    + Math.abs(pos.getY() - target.getY());
        }

        @Override
        public String describe() {
            return Lang.get("lune.goal.block", target.getX(), target.getY(), target.getZ());
        }
    }

    /** Get within {@code radius} blocks of a point - the usual goal for "go roughly there". */
    public record Near(BlockPos target, int radius) implements Goal {
        @Override
        public boolean isReached(BlockPos pos) {
            return pos.distSqr(target) <= (double) radius * radius;
        }

        @Override
        public double heuristic(BlockPos pos) {
            double h = octile(pos.getX() - target.getX(), pos.getZ() - target.getZ())
                    + Math.abs(pos.getY() - target.getY());
            return Math.max(0.0, h - radius);
        }

        @Override
        public String describe() {
            return Lang.get("lune.goal.within", radius, target.getX(), target.getY(),
                    target.getZ());
        }
    }

    /** Reach an X/Z column at any height - for long-distance travel where Y doesn't matter. */
    public record XZ(int x, int z) implements Goal {
        @Override
        public boolean isReached(BlockPos pos) {
            return pos.getX() == x && pos.getZ() == z;
        }

        @Override
        public double heuristic(BlockPos pos) {
            return octile(pos.getX() - x, pos.getZ() - z);
        }

        @Override
        public String describe() {
            return Lang.get("lune.goal.column", x, z);
        }
    }

    /** Reach within a horizontal radius of an X/Z point, regardless of terrain height. */
    public record NearXZ(int x, int z, int radius) implements Goal {
        @Override
        public boolean isReached(BlockPos pos) {
            long dx = (long) pos.getX() - x;
            long dz = (long) pos.getZ() - z;
            return dx * dx + dz * dz <= (long) radius * radius;
        }

        @Override
        public double heuristic(BlockPos pos) {
            return Math.max(0.0, octile(pos.getX() - x, pos.getZ() - z) - radius);
        }

        @Override
        public String describe() {
            return Lang.get("lune.goal.within_horizontally", radius, x, z);
        }
    }

    /** Reach a Y level - used by stripmine to descend to the ore band first. */
    public record YLevel(int y) implements Goal {
        @Override
        public boolean isReached(BlockPos pos) {
            return pos.getY() == y;
        }

        @Override
        public double heuristic(BlockPos pos) {
            return Math.abs(pos.getY() - y);
        }

        @Override
        public String describe() {
            return Lang.get("lune.goal.y_level", y);
        }
    }

    /**
     * Stand anywhere the player could reach {@code target} from - i.e. adjacent to it, or one
     * block up so they can look down at it. This is what Mine uses: it wants to be <em>next to</em>
     * the ore, not inside it.
     */
    public record Adjacent(BlockPos target, double reach) implements Goal {
        @Override
        public boolean isReached(BlockPos pos) {
            // Compare from roughly eye height, since that's what the reach check uses in game.
            double dx = (pos.getX() + 0.5) - (target.getX() + 0.5);
            double dy = (pos.getY() + 1.6) - (target.getY() + 0.5);
            double dz = (pos.getZ() + 0.5) - (target.getZ() + 0.5);
            return dx * dx + dy * dy + dz * dz <= reach * reach;
        }

        @Override
        public double heuristic(BlockPos pos) {
            double h = octile(pos.getX() - target.getX(), pos.getZ() - target.getZ())
                    + Math.abs(pos.getY() - target.getY());
            return Math.max(0.0, h - reach);
        }

        @Override
        public String describe() {
            return Lang.get("lune.goal.reach_of", target.getX(), target.getY(), target.getZ());
        }
    }

    /** Satisfied by whichever sub-goal is closest - used to head for the nearest of many ores. */
    public record Any(List<Goal> goals) implements Goal {
        @Override
        public boolean isReached(BlockPos pos) {
            for (Goal goal : goals) {
                if (goal.isReached(pos)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public double heuristic(BlockPos pos) {
            double best = Double.POSITIVE_INFINITY;
            for (Goal goal : goals) {
                best = Math.min(best, goal.heuristic(pos));
            }
            return best == Double.POSITIVE_INFINITY ? 0.0 : best;
        }

        @Override
        public String describe() {
            return Lang.get("lune.goal.any_of", goals.size());
        }
    }
}
