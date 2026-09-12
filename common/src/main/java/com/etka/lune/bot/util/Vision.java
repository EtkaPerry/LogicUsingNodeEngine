package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Simulates what the bot can actually see from its current position and facing.
 * <p>
 * In human-like mode this is used to stop the bot from mining or harvesting blocks it
 * has no line-of-sight to. The default behaviour should be human-like, so by default
 * targets must pass {@link #isVisible}.
 */
public final class Vision {

    /**
     * Total cone of vision, in radians. A target behind the player is not visible until the player
     * turns toward it; callers that want a wider search use {@link HeadScanner} to turn physically.
     */
    public static final double TOTAL_FOV = Math.toRadians(120.0);

    /**
     * Cone of vision for living things, which is wider than for blocks.
     *
     * <p>A block has to be looked at to be noticed; that is what the 120 degrees above is for, and
     * it is what keeps mining honest. An animal is a different question. It moves, and peripheral
     * vision is at its best on movement - a person crossing a field does not walk past a sheep
     * because it was thirty degrees off centre.</p>
     *
     * <p>Measured, that gap was most of the hunt: over one ten-minute run a sheep was loaded within
     * sixty-four blocks for 62% of the time and passed the sight test for 15%, leaving 47% of the
     * run spent near animals the bot could not see. Still short of a circle - something directly
     * behind is not visible until the head comes round, which is what {@link HeadScanner} is
     * for.</p>
     */
    public static final double ENTITY_FOV = Math.toRadians(180.0);
    /**
     * Inside this distance a clear line of sight is enough on its own, whichever way the head
     * happens to point.
     * <p>
     * The view cone exists to stop the bot acting on blocks it could not have seen. At arm's length
     * that is not what it does: it makes the bot forget the tree it is standing against the moment
     * it looks down at the drops. A person does not re-discover the trunk they are touching, and the
     * cost of pretending otherwise is severe - the block goes missing from a search that is already
     * turning its head, so the nearest candidate is rejected on the tick the head is elsewhere and
     * some tree across the field wins instead.
     * <p>
     * This concedes nothing to X-ray. The ray test still has to pass, so anything behind rock stays
     * hidden; all that is waived is the requirement to be facing a block that is close enough to
     * touch.
     */
    private static final double CLOSE_QUARTERS = 6.0;
    /**
     * How many see-through blocks a single ray is willing to pass before giving up.
     * <p>
     * Six was chosen when each "pass" often failed to clear the block it was in, so the number was
     * really a budget for a march rather than a count of blocks. Now that a pass crosses exactly one
     * cell, six is a thin canopy: an oak seen from below and to one side puts eight or nine leaves
     * between the eyes and the trunk, and the trunk is not hidden - a person standing under a tree
     * can see it perfectly well. Still bounded, because seeing through an arbitrary depth of foliage
     * is not sight, and the count is what stops it.
     */
    private static final int MAX_TRANSPARENT_PASSES = 6;

    /**
     * Furthest distance the bot can spot a block. It matches the player's render distance exactly -
     * a human sees to the edge of their loaded chunks, and so does the bot. The section-pruned
     * {@link TargetIndex} keeps even a full render-distance scan cheap, so no artificial block cap
     * is needed any more.
     */
    public static double maxRange(BotContext ctx) {
        return ctx.mc.options.renderDistance().get() * 16.0;
    }

    private Vision() {}

    /**
     * A point safely inside the block's real outline, suitable for sight and interaction rays.
     * Block centre is wrong for short shapes such as mature beetroot: it can land exactly on or
     * above the outline boundary, so the eyes appear aimed at the crop while the crosshair misses
     * it. Full blocks retain their ordinary centre.
     */
    public static Vec3 blockAimPoint(BotContext ctx, BlockPos pos) {
        BlockState state = ctx.level.getBlockState(pos);
        VoxelShape shape = state.getShape(ctx.level, pos);
        if (shape.isEmpty()) {
            return Vec3.atCenterOf(pos);
        }
        var bounds = shape.bounds();
        return new Vec3(pos.getX() + (bounds.minX + bounds.maxX) * 0.5,
                pos.getY() + (bounds.minY + bounds.maxY) * 0.5,
                pos.getZ() + (bounds.minZ + bounds.maxZ) * 0.5);
    }

    /** Whether callers should rely on the ray test instead of spending ticks turning the head. */
    public static boolean isPanoramic() {
        return TOTAL_FOV >= Math.PI * 2.0 - 1.0e-6;
    }

    /**
     * A reasoned sight check for diagnostics. The old boolean helpers remain the source used by
     * behavior, while this report lets the overlay distinguish "behind me" from "behind stone".
     */
    public record SightReport(boolean loaded, boolean inRange, boolean inView, boolean reachable,
                              String verdict, BlockPos blocker) {
        public boolean visible() {
            return loaded && inRange && inView && reachable;
        }
    }

    /** Explains whether the player could act on a block now, or after turning toward it. */
    public static SightReport inspect(BotContext ctx, BlockPos pos) {
        if (pos == null) {
            return new SightReport(false, false, false, false, "no position", null);
        }
        if (!ctx.level.hasChunkAt(pos)) {
            return new SightReport(false, false, false, false, "unloaded", null);
        }

        Vec3 eye = ctx.player.getEyePosition();
        Vec3 centre = blockAimPoint(ctx, pos);
        double distanceSqr = eye.distanceToSqr(centre);
        double range = maxRange(ctx);
        if (distanceSqr > range * range) {
            return new SightReport(true, false, false, false, "out of range", null);
        }

        boolean closeQuarters = distanceSqr <= CLOSE_QUARTERS * CLOSE_QUARTERS;
        boolean inCone = inCone(ctx, eye, centre);
        boolean inView = inCone || closeQuarters;
        RayReport ray = rayReport(ctx, pos, eye, centre);
        if (!ray.reachable) {
            return new SightReport(true, true, inView, false, ray.verdict, ray.blocker);
        }
        String verdict = inCone ? "visible"
                : closeQuarters ? "visible (close by)" : "outside view (turn toward it)";
        return new SightReport(true, true, inView, true, verdict, null);
    }

    /**
     * Whether a block falls inside the view cone right now, with no ray cast.
     * <p>
     * This is deliberately not a visibility test - it answers only "am I facing it", which is the
     * one part of {@link #inspect} that changes every time the head moves. Searches that sweep a
     * large candidate list use it to tell a candidate they have ruled out from one they merely have
     * their back to, so that turning the head is what resolves the second kind rather than a
     * rebuild several seconds later.
     */
    public static boolean isInView(BotContext ctx, BlockPos pos) {
        if (pos == null || !ctx.level.hasChunkAt(pos)) {
            return false;
        }
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 centre = blockAimPoint(ctx, pos);
        return eye.distanceToSqr(centre) <= CLOSE_QUARTERS * CLOSE_QUARTERS
                || inCone(ctx, eye, centre);
    }

    private static boolean inCone(BotContext ctx, Vec3 eye, Vec3 centre) {
        Vec3 to = centre.subtract(eye).normalize();
        Vec3 look = ctx.player.getViewVector(1.0F);
        double angle = Math.acos(Mth.clamp(look.dot(to), -1.0, 1.0));
        return angle <= TOTAL_FOV / 2.0;
    }

    /**
     * @return true if the block is within the bot's view cone, within range, and not hidden
     *         behind another block.
     */
    public static boolean isVisible(BotContext ctx, BlockPos pos) {
        return inspect(ctx, pos).visible();
    }

    /**
     * Whether a living entity is currently visible to the player. Entity searches use the same
     * view cone and obstruction test as block searches, but ray-test the entity's centre because
     * entities are not blocks and therefore cannot be the final block hit of a level clip.
     */
    public static boolean isEntityVisible(BotContext ctx, Entity entity) {
        return isEntityVisible(ctx.mc, ctx.player, ctx.level, entity);
    }

    /**
     * Client-facing form used by diagnostics that do not need to construct a full bot context.
     * It deliberately shares the same range, view-cone, and obstruction rules as the bot form.
     */
    public static boolean isEntityVisible(Minecraft mc, LocalPlayer player, ClientLevel level,
                                          Entity entity) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 centre = entity.getBoundingBox().getCenter();
        double distanceSqr = eye.distanceToSqr(centre);
        double range = mc.options.renderDistance().get() * 16.0;
        if (distanceSqr > range * range) {
            return false;
        }

        Vec3 to = centre.subtract(eye).normalize();
        double angle = Math.acos(Mth.clamp(player.getViewVector(1.0F).dot(to), -1.0, 1.0));
        if (angle > ENTITY_FOV / 2.0) {
            return false;
        }

        // Fluids do not stop this ray, unlike the block test. A player can see a salmon in a river
        // and a cow standing in a pond; treating the water surface as a wall made every fish in the
        // game permanently invisible to the bot, which is why a shoreline could report no food.
        // Block visibility keeps water opaque, because mining blind through water is a drowning.
        BlockHitResult hit = level.clip(new ClipContext(eye, centre,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return true;
        }

        // A block hit very close to the entity centre can be the floor/wall the entity is standing
        // against. Only reject a block that is materially closer to the eyes than the entity.
        double hitDistanceSqr = eye.distanceToSqr(hit.getLocation());
        return hitDistanceSqr + 0.25 >= distanceSqr;
    }

    /**
     * Same ray test as {@link #isVisible} but ignores where the player is currently looking. This
     * answers "could the player see this block if it turned its head?" which is what matters for
     * adjacent blocks in a stair or a vein the player is about to look at.
     */
    public static boolean isReachable(BotContext ctx, BlockPos pos) {
        return inspect(ctx, pos).reachable();
    }

    /** Whether the block's real outline could be hit from a hypothetical eye position. */
    public static boolean isReachableFrom(BotContext ctx, BlockPos pos, Vec3 eye) {
        if (pos == null || !ctx.level.hasChunkAt(pos)) {
            return false;
        }
        return rayReport(ctx, pos, eye, blockAimPoint(ctx, pos)).reachable();
    }

    private record RayReport(boolean reachable, String verdict, BlockPos blocker) {}

    private static RayReport rayReport(BotContext ctx, BlockPos pos, Vec3 eye, Vec3 centre) {
        Vec3 ray = centre.subtract(eye);
        double distance = Math.sqrt(ray.lengthSqr());
        if (distance <= 0.0) {
            return new RayReport(false, "zero distance", null);
        }
        Vec3 dir = ray.scale(1.0 / distance);

        // A matching block must be exposed to the player's eyes. Even if a block is visually
        // transparent in vanilla, treating leaves, glass and fluids as opaque keeps harvesting and
        // mining from acting through a screen of blocks the bot cannot safely target through.
        Vec3 rayStart = eye;
        for (int pass = 0; pass < MAX_TRANSPARENT_PASSES; pass++) {
            BlockHitResult hit = ctx.level.clip(new ClipContext(rayStart, centre,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, ctx.player));
            if (hit.getType() != HitResult.Type.BLOCK) {
                return new RayReport(false, "ray hit no block", null);
            }
            if (hit.getBlockPos().equals(pos)) {
                return new RayReport(true, "visible", null);
            }
            BlockState hitState = ctx.level.getBlockState(hit.getBlockPos());
            if (!isSeeThrough(hitState)) {
                return new RayReport(false,
                        "occluded by " + hitState.getBlock().getName().getString(),
                        hit.getBlockPos().immutable());
            }

            rayStart = pastBlock(hit.getBlockPos(), hit.getLocation(), dir);
        }
        return new RayReport(false, "too many transparent blocks", null);
    }

    /**
     * The first point along {@code dir} that lies outside {@code block}, starting from a point on
     * its surface.
     * <p>
     * This used to be a fixed march: twenty steps of 0.05, then give up with "ray trapped in
     * transparent block". Twenty steps is one block of travel, and a ray only crosses a cube in one
     * block of travel when it runs square down an axis. Anything angled needs up to √2 across a
     * face diagonal and √3 corner to corner, so any oblique look through a leaf ran out of march
     * and reported the target hidden.
     * <p>
     * That is not a tuning miss, it is the difference between felling a tree and walking away from
     * it. The log above the last cut is behind exactly one leaf and is almost never square-on, so it
     * came back invisible, the tree was declared finished, and the bot went looking for another one
     * with most of this one still standing. Measured against a person on the same seed, the bot
     * walked 236 blocks for the wood a person got in 48; two runs showed 539 and 151 of these.
     * <p>
     * There is no need to march at all. The exit is where the ray meets the far side of the cell,
     * which is one division per axis.
     */
    static Vec3 pastBlock(BlockPos block, Vec3 from, Vec3 dir) {
        double exit = Math.min(axisExit(from.x, dir.x, block.getX()),
                Math.min(axisExit(from.y, dir.y, block.getY()),
                        axisExit(from.z, dir.z, block.getZ())));
        if (!Double.isFinite(exit)) {
            // Every axis unreachable means a zero direction, which the caller cannot produce - it
            // normalises and returns early on zero length. Leave the cell upward anyway: handing
            // back the point we were given would spin the caller's loop, and scaling a zero
            // direction is not a nudge.
            return new Vec3(from.x, block.getY() + 1.0 + 1.0E-4, from.z);
        }
        // Just past the boundary, so the next clip starts in the neighbouring cell rather than on
        // the face between them.
        return from.add(dir.scale(exit + 1.0E-4));
    }

    /** Distance along the ray to leave one cell on one axis, or infinity if it never does. */
    private static double axisExit(double from, double direction, int cell) {
        if (Math.abs(direction) < 1.0E-9) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = direction > 0.0 ? cell + 1.0 : cell;
        double distance = (boundary - from) / direction;
        // Starting exactly on the face it is leaving through: cross the whole cell instead of
        // standing still, which would leave the caller clipping the same block for ever.
        return distance > 1.0E-6 ? distance : Math.abs(1.0 / direction);
    }

    /**
     * Blocks a sight ray may pass through on its way to a target.
     *
     * <p>Air, and leaves. Leaves used to be opaque here on the reasoning that the bot should not
     * act through a screen of blocks it cannot safely target through - which is right about
     * <em>acting</em> and wrong about <em>seeing</em>. A person standing in a forest sees the
     * trunks; they are not hidden by the canopy in front of them.</p>
     *
     * <p>Treating them as walls produced exactly the behaviour that looks broken from outside. In
     * one recorded birch-forest run the bot spent 421 of 1,091 ticks - 38.6% of the run - turning
     * its head between trees, because after each log its sight rays kept coming back "occluded by
     * Birch Leaves" and the head scanner widened from a glance to a full 360-degree sweep looking
     * for a trunk that happened to line up through a gap. Every tree it was looking for was
     * already in front of it.</p>
     *
     * <p>{@link #MAX_TRANSPARENT_PASSES} still bounds this at six, so the bot cannot see through
     * an entire forest, and it does not change what the bot can <em>reach</em>: a target that is
     * still behind leaves when the bot arrives is re-checked and rescanned from there.</p>
     */
    private static boolean isSeeThrough(BlockState state) {
        return state.isAir() || state.is(net.minecraft.tags.BlockTags.LEAVES);
    }
}
