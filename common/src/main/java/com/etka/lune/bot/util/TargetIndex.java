package com.etka.lune.bot.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleFunction;

/**
 * A fast, cached index of candidate target blocks around the player.
 * <p>
 * The old approach walked every block position in a growing cube shell - {@code O(r³)} lookups that
 * freeze the client once the radius approaches the render distance. This index instead walks the
 * chunk sections that overlap the search region and uses each section's block palette to skip any
 * 16×16×16 volume that contains none of the wanted blocks. For a typical ore search that discards
 * well over 99% of the world without ever reading individual blocks, so even a full render-distance
 * scan stays cheap.
 * <p>
 * The candidate list is built once per region and reused while the player stands still. Turning the
 * head to look around never rebuilds the index - it only re-filters the cached candidates by
 * visibility - which is what makes a four-view scan cost one world pass instead of four.
 * <p>
 * Callers must key the region on a settled anchor position rather than the live
 * {@code blockPosition()}. A player treading water or sliding down a slope changes block position
 * every tick, and since {@link #isUsable} demands an exact match, feeding it the live position would
 * discard the cache every tick and leave only the rebuild cost.
 */
public final class TargetIndex {

    private final List<BlockPos> candidates = new ArrayList<>();

    private BlockPos centre;
    private Set<Block> targets;
    private int radius;
    private int yMin;
    private int yMax;
    private boolean built;

    /** Distance-sorted view of {@link #candidates}, cached per observer position. */
    private List<BlockPos> sorted;
    private BlockPos sortedOrigin;
    /** Where the next bounded sweep resumes, so a large candidate set is covered over time. */
    private int cursor;

    /**
     * True when the cached index still describes the requested region. Any change of centre, target
     * set, radius or vertical band forces a rebuild.
     */
    public boolean isUsable(BlockPos centre, Set<Block> targets, int radius, int yMin, int yMax) {
        return built
                && centre.equals(this.centre)
                && targets.equals(this.targets)
                && radius == this.radius
                && yMin == this.yMin
                && yMax == this.yMax;
    }

    /**
     * Rebuilds the candidate list for the given region using chunk-section palette pruning.
     * Candidates are every block matching {@code targets} inside the cube
     * {@code centre ± radius}, clamped to {@code [yMin, yMax]} and to loaded chunks.
     */
    public void rebuild(LevelReader level, BlockPos centre, Set<Block> targets,
                        int radius, int yMin, int yMax) {
        candidates.clear();
        sorted = null;
        sortedOrigin = null;
        cursor = 0;
        this.centre = centre;
        this.targets = Set.copyOf(targets);
        this.radius = radius;
        this.yMin = Math.min(yMin, yMax);
        this.yMax = Math.max(yMin, yMax);
        this.built = true;

        if (targets.isEmpty() || radius < 0) {
            return;
        }

        int minX = centre.getX() - radius;
        int maxX = centre.getX() + radius;
        int minZ = centre.getZ() - radius;
        int maxZ = centre.getZ() + radius;
        int minY = Math.max(this.yMin, level.getMinY());
        int maxY = Math.min(this.yMax, level.getMaxY() - 1);
        if (minY > maxY || minX > maxX || minZ > maxZ) {
            return;
        }

        int minSectionIndex = level.getSectionIndex(minY);
        int maxSectionIndex = level.getSectionIndex(maxY);
        int minChunkX = SectionPos.blockToSectionCoord(minX);
        int maxChunkX = SectionPos.blockToSectionCoord(maxX);
        int minChunkZ = SectionPos.blockToSectionCoord(minZ);
        int maxChunkZ = SectionPos.blockToSectionCoord(maxZ);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionIndex = minSectionIndex; sectionIndex <= maxSectionIndex; sectionIndex++) {
                    if (sectionIndex < 0 || sectionIndex >= sections.length) {
                        continue;
                    }
                    LevelChunkSection section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    // The palette test is the whole point: a section whose palette holds none of the
                    // wanted blocks is skipped without reading a single one of its 4096 positions.
                    if (!section.maybeHas(state -> targets.contains(state.getBlock()))) {
                        continue;
                    }
                    collectFromSection(level, section, chunkX, chunkZ, sectionIndex,
                            minX, maxX, minZ, maxZ, minY, maxY, targets);
                }
            }
        }
    }

    private void collectFromSection(LevelHeightAccessor height, LevelChunkSection section,
                                    int chunkX, int chunkZ, int sectionIndex,
                                    int minX, int maxX, int minZ, int maxZ, int minY, int maxY,
                                    Set<Block> targets) {
        int baseX = SectionPos.sectionToBlockCoord(chunkX);
        int baseZ = SectionPos.sectionToBlockCoord(chunkZ);
        int baseY = SectionPos.sectionToBlockCoord(height.getSectionYFromSectionIndex(sectionIndex));

        int localYStart = Math.max(0, minY - baseY);
        int localYEnd = Math.min(15, maxY - baseY);
        int localZStart = Math.max(0, minZ - baseZ);
        int localZEnd = Math.min(15, maxZ - baseZ);
        int localXStart = Math.max(0, minX - baseX);
        int localXEnd = Math.min(15, maxX - baseX);

        Leash leash = Leash.get();
        for (int ly = localYStart; ly <= localYEnd; ly++) {
            for (int lz = localZStart; lz <= localZEnd; lz++) {
                for (int lx = localXStart; lx <= localXEnd; lx++) {
                    BlockState state = section.getBlockState(lx, ly, lz);
                    if (!targets.contains(state.getBlock())) {
                        continue;
                    }
                    // Work outside the leash is not work: offering it is what sends a job walking
                    // out of the area the player asked it to stay in, and no amount of walking the
                    // bot back afterwards is as good as never having chosen it.
                    if (!leash.within(baseX + lx + 0.5, baseZ + lz + 0.5, leash.radius())) {
                        continue;
                    }
                    candidates.add(new BlockPos(baseX + lx, baseY + ly, baseZ + lz));
                }
            }
        }
    }

    /**
     * Returns the nearest candidate to {@code origin} that is still the expected block, is not in
     * {@code excluded}, and passes {@code filter}. Candidates are tested in distance order so the
     * expensive visibility ray-cast runs on as few blocks as possible.
     */
    public BlockPos nearest(BlockGetter level, BlockPos origin, Set<Long> excluded,
                            BiPredicate<BlockPos, BlockState> filter) {
        if (candidates.isEmpty()) {
            return null;
        }
        for (BlockPos pos : inDistanceOrder(origin)) {
            if (excluded.contains(pos.asLong())) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!targets.contains(state.getBlock())) {
                continue;
            }
            if (filter.test(pos, state)) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Returns the accepted candidate with the lowest caller-supplied score.
     *
     * <p>This is used by learned work-site tactics after a target group has already been selected.
     * The caller's normal filter still decides what is visible and safe; the score changes order,
     * never eligibility.</p>
     */
    public BlockPos best(BlockGetter level, Set<Long> excluded,
                         BiPredicate<BlockPos, BlockState> filter,
                         ToDoubleFunction<BlockPos> score) {
        if (candidates.isEmpty() || score == null) {
            return null;
        }
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (BlockPos pos : candidates) {
            if (excluded.contains(pos.asLong())) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!targets.contains(state.getBlock()) || !filter.test(pos, state)) {
                continue;
            }
            double candidateScore = score.applyAsDouble(pos);
            if (candidateScore < bestScore) {
                best = pos;
                bestScore = candidateScore;
            }
        }
        return best;
    }

    /**
     * A bounded slice of {@link #nearest}: tests at most {@code maxChecks} candidates and resumes
     * from where the previous bounded call stopped, wrapping around the list.
     * <p>
     * Visibility is a ray cast per candidate, and an ore search can index thousands of blocks that
     * are all buried. Running every one of them on every tick is affordable while the bot is
     * standing still and looking around; it is not while it is also walking and the client is
     * rendering. Any visible target is worth stopping for, so a slice per tick finds one just as
     * surely - it simply takes a few ticks to work through a large candidate set. The sweep still
     * begins at the nearest candidate after every rebuild, so the closest blocks are always the
     * first ones considered.
     */
    public BlockPos nearestBounded(BlockGetter level, BlockPos origin, Set<Long> excluded,
                                   BiPredicate<BlockPos, BlockState> filter, int maxChecks) {
        if (candidates.isEmpty() || maxChecks <= 0) {
            return null;
        }
        List<BlockPos> ordered = inDistanceOrder(origin);
        int size = ordered.size();
        if (cursor >= size) {
            cursor = 0;
        }
        // The budget is a budget of *ray casts*, not of candidates.
        //
        // An ore index in stone is overwhelmingly blocks sealed on all six sides, and a sealed block
        // cannot be visible from anywhere - so ray-casting it is guaranteed wasted work. Spending
        // the budget on them is why a walking bot could pass ore it could plainly see: with sixteen
        // thousand candidates and sixty-four casts a tick, the sweep only reached a fraction of the
        // list before moving eight blocks rebuilt the index and started it again. Six block lookups
        // to reject a buried candidate costs far less than the cast it replaces, so those are
        // skipped for free and the budget goes to blocks that might actually be in view.
        int spent = 0;
        int scanned = 0;
        while (spent < maxChecks && scanned < size) {
            BlockPos pos = ordered.get((cursor + scanned) % size);
            scanned++;
            if (excluded.contains(pos.asLong())) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!targets.contains(state.getBlock())) {
                continue;
            }
            if (!hasExposedFace(level, pos)) {
                continue;
            }
            spent++;
            if (filter.test(pos, state)) {
                return pos;
            }
        }
        cursor = (cursor + scanned) % size;
        return null;
    }

    /**
     * Whether any of the six faces touches something that could be seen through. Cheap, and false
     * for the great majority of an underground ore index.
     */
    private static boolean hasExposedFace(BlockGetter level, BlockPos pos) {
        for (Direction face : Direction.values()) {
            BlockState neighbour = level.getBlockState(pos.relative(face));
            if (neighbour.isAir() || !neighbour.isSolidRender()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the nearest still-present indexed block without applying a visibility filter.
     * <p>
     * This is deliberately diagnostic only: callers must not act on this result unless their
     * normal filter accepts it. It lets the HUD say "wood exists at X, but is behind stone" instead
     * of making a long-running visible search look like it has no idea what is nearby.
     */
    public BlockPos nearestCandidate(BlockGetter level, BlockPos origin, Set<Long> excluded) {
        if (candidates.isEmpty()) {
            return null;
        }
        for (BlockPos pos : inDistanceOrder(origin)) {
            if (excluded.contains(pos.asLong())) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (targets.contains(state.getBlock())) {
                return pos;
            }
        }
        return null;
    }

    /** Number of matching blocks currently indexed, for status/debug text. */
    public int size() {
        return candidates.size();
    }

    public void invalidate() {
        built = false;
        candidates.clear();
        sorted = null;
        sortedOrigin = null;
        cursor = 0;
    }

    private List<BlockPos> inDistanceOrder(BlockPos origin) {
        if (sorted != null && origin.equals(sortedOrigin)) {
            return sorted;
        }
        sorted = new ArrayList<>(candidates);
        // Distance first, then which side of the work it is on. See SearchOrderPolicy: inside a
        // solid deposit every neighbour ties on distance, and insertion order used to hand that
        // decision to the scan, which fills sections bottom-up and so always chose straight down.
        sorted.sort(Comparator.comparingDouble(origin::distSqr).thenComparingInt(
                pos -> SearchOrderPolicy.verticalPreference(origin.getY(), pos.getY())));
        sortedOrigin = origin;
        return sorted;
    }
}
