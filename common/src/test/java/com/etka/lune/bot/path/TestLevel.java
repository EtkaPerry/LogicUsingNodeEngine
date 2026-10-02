package com.etka.lune.bot.path;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A hand-built voxel scene, so the real pathfinder can be run against terrain a test describes.
 * <p>
 * The alternative was to test the search by restating its rules in a second place and comparing the
 * two, which mostly proves the restatement was copied accurately. A scene is the actual question
 * being asked - "there is a two-block hole here, what do you do" - and the answer comes from the
 * real {@link AStarPathfinder} over real {@link net.minecraft.world.level.block.state.BlockState}s.
 * <p>
 * Everything not placed is air, and the scene has no chunk loading, lighting or entities. That is
 * enough for the movement predicates, which only ever ask about collision shapes and fluids.
 */
public final class TestLevel implements BlockGetter {

    private static boolean booted;

    private final Map<Long, BlockState> blocks = new HashMap<>();
    private final BlockState air;

    private TestLevel() {
        this.air = Blocks.AIR.defaultBlockState();
    }

    /**
     * Starts an empty scene, bootstrapping Minecraft's registries the first time.
     * <p>
     * The bootstrap costs about five seconds and is what makes block states usable outside the game.
     * It runs once per test JVM, so only suites that actually build a scene pay for it.
     */
    public static TestLevel scene() {
        if (!booted) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bindFluidTags();
            booted = true;
        }
        return new TestLevel();
    }

    /**
     * Gives the fluid registry the two tags the game gives it, which a bootstrap alone does not.
     *
     * <p>Tags come from data packs, and nothing loads one here, so every tag is empty: water placed
     * in a scene failed {@code FluidTags.WATER} and the movement predicates read it as a passable
     * block with a floor under it. A scene with a lake in it was a scene with a lawn. These are the
     * vanilla contents, so binding them only makes the scene what it says it is.</p>
     */
    private static void bindFluidTags() {
        Map<TagKey<Fluid>, List<Holder<Fluid>>> tags = Map.of(
                FluidTags.WATER, List.of(BuiltInRegistries.FLUID.wrapAsHolder(Fluids.WATER),
                        BuiltInRegistries.FLUID.wrapAsHolder(Fluids.FLOWING_WATER)),
                FluidTags.LAVA, List.of(BuiltInRegistries.FLUID.wrapAsHolder(Fluids.LAVA),
                        BuiltInRegistries.FLUID.wrapAsHolder(Fluids.FLOWING_LAVA)));
        BuiltInRegistries.FLUID.prepareTagReload(
                new TagLoader.LoadResult<>(Registries.FLUID, tags)).apply();
    }

    public TestLevel set(int x, int y, int z, Block block) {
        blocks.put(BlockPos.asLong(x, y, z), block.defaultBlockState());
        return this;
    }

    /** One block in a state of its own: a door's half and facing, an open gate. */
    public TestLevel set(int x, int y, int z, BlockState state) {
        blocks.put(BlockPos.asLong(x, y, z), state);
        return this;
    }

    /** Solid stone filling the inclusive x/z rectangle at one height. */
    public TestLevel floor(int x0, int x1, int z0, int z1, int y) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                set(x, y, z, Blocks.STONE);
            }
        }
        return this;
    }

    /** A solid box, for walls and for rock to tunnel through. */
    public TestLevel fill(int x0, int x1, int y0, int y1, int z0, int z1, Block block) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z, block);
                }
            }
        }
        return this;
    }

    /** Removes whatever is there, leaving air. */
    public TestLevel carve(int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    blocks.remove(BlockPos.asLong(x, y, z));
                }
            }
        }
        return this;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos.asLong(), air);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinY() {
        return -64;
    }
}
