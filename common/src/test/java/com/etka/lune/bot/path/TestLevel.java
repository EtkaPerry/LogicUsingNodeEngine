package com.etka.lune.bot.path;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
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
            booted = true;
        }
        return new TestLevel();
    }

    public TestLevel set(int x, int y, int z, Block block) {
        blocks.put(BlockPos.asLong(x, y, z), block.defaultBlockState());
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
