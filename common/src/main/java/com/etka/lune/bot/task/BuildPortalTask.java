package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BlockScanner;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Builds and lights a Nether portal from blocks carried by the player. */
public final class BuildPortalTask implements Task {

    public enum FrameMode {
        /** A visually complete frame while using the ten-obsidian minimum. */
        RESOURCE_SAVING("10 obsidian + dirt/cobblestone corners", true, false, 10),
        /** The speedrun shape: the four corners are left open. */
        TEN_OBSIDIAN("10 obsidian, open corners", false, false, 10),
        /** A rectangular frame made entirely from obsidian. */
        FULL_OBSIDIAN("14 obsidian, full frame", true, true, 14);

        private final String label;
        private final boolean includeCorners;
        private final boolean cornersAreObsidian;
        private final int obsidianNeeded;

        FrameMode(String label, boolean includeCorners, boolean cornersAreObsidian, int obsidianNeeded) {
            this.label = label;
            this.includeCorners = includeCorners;
            this.cornersAreObsidian = cornersAreObsidian;
            this.obsidianNeeded = obsidianNeeded;
        }

        public String label() {
            return label;
        }

        public int obsidianNeeded() {
            return obsidianNeeded;
        }

        private static FrameMode fromChoice(String choice) {
            return Arrays.stream(values())
                    .filter(mode -> mode.label.equals(choice))
                    .findFirst()
                    .orElse(RESOURCE_SAVING);
        }
    }

    private static final int PLACE_TIMEOUT_TICKS = 80;
    private static final int LIGHT_TIMEOUT_TICKS = 80;
    private static final double PLACE_REACH = 3.5;
    private static final float AIM_TOLERANCE = 15.0F;

    private final FrameMode mode;
    private final Set<Block> cornerMaterials;
    private final boolean enterAfterBuild;

    private State state = State.CHOOSE_BASE;
    private BlockPos base;
    private BlockPos portal;
    private GotoTask approach;
    private int frameIndex;
    private int placeTicks;
    private int lightTicks;
    private String buildStrategy = PortalBuildingPolicy.DEFAULT;
    private List<BlockPos> frameOrder = List.of();
    /** Keeps an in-progress placement stable when live feedback changes the remaining order. */
    private BlockPos frameTarget;
    private String status = "";

    private enum State { CHOOSE_BASE, BUILD, LIGHT, ENTER, DONE }

    public BuildPortalTask(String modeChoice, Set<Block> cornerMaterials) {
        this(FrameMode.fromChoice(modeChoice), cornerMaterials, false);
    }

    /** Package-visible variant used by the complete-game route, which enters after lighting. */
    BuildPortalTask(FrameMode mode, Set<Block> cornerMaterials, boolean enterAfterBuild) {
        this.mode = mode == null ? FrameMode.RESOURCE_SAVING : mode;
        this.cornerMaterials = cornerMaterials == null ? Set.of() : Set.copyOf(cornerMaterials);
        this.enterAfterBuild = enterAfterBuild;
    }

    public static List<String> modeChoices() {
        return Arrays.stream(FrameMode.values()).map(FrameMode::label).toList();
    }

    @Override
    public String name() {
        return "Build Nether Portal";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        int target = mode.includeCorners ? 14 : 10;
        return new TaskProgress(frameIndex, target, "frame blocks");
    }

    @Override
    public LearningContext learningContext(BotContext ctx) {
        String phase = "frame=" + PortalBuildingPolicy.frameBucket(
                mode.includeCorners, mode.obsidianNeeded) + ";enter=" + enterAfterBuild;
        return new LearningContext("skill", "portal-building",
                ctx.level.dimension().identifier().toString(), phase);
    }

    @Override
    public List<String> learningActions(BotContext ctx) {
        return mode.includeCorners ? PortalBuildingPolicy.ACTIONS
                : List.of(PortalBuildingPolicy.DEFAULT);
    }

    @Override
    public void onLearningAction(BotContext ctx, String action) {
        buildStrategy = PortalBuildingPolicy.ACTIONS.contains(action)
                ? action : PortalBuildingPolicy.DEFAULT;
        if (base != null) {
            frameOrder = PortalBuildingPolicy.order(base, mode.includeCorners,
                    buildStrategy, ctx.player.getX());
        }
    }

    @Override
    public void onStart(BotContext ctx) {
        state = State.CHOOSE_BASE;
        base = null;
        portal = null;
        frameIndex = 0;
        placeTicks = 0;
        lightTicks = 0;
        buildStrategy = PortalBuildingPolicy.DEFAULT;
        frameOrder = List.of();
        frameTarget = null;
        status = "";
        stopApproach(ctx);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        ctx.debug.intent = "portal builder: " + state.name().toLowerCase();
        ctx.debug.giveUp = "frame " + frameIndex + "/" + (mode.includeCorners ? 14 : 10)
                + ", block " + placeTicks + "/" + PLACE_TIMEOUT_TICKS + " ticks";

        if (enterAfterBuild && ctx.level.dimension() == Level.NETHER) {
            status = "entered the Nether";
            return TaskStatus.SUCCESS;
        }

        BlockPos foundPortal = findPortal(ctx, ctx.player.blockPosition(), 48);
        if (foundPortal != null) {
            portal = foundPortal;
            state = enterAfterBuild ? State.ENTER : State.DONE;
        }

        return switch (state) {
            case CHOOSE_BASE -> chooseBase(ctx);
            case BUILD -> build(ctx);
            case LIGHT -> light(ctx);
            case ENTER -> enter(ctx);
            case DONE -> {
                status = "Nether portal is built and lit";
                yield TaskStatus.SUCCESS;
            }
        };
    }

    private TaskStatus chooseBase(BotContext ctx) {
        if (mode == FrameMode.RESOURCE_SAVING && cornerMaterials.isEmpty()) {
            status = "choose dirt or cobblestone for the portal corners";
            return TaskStatus.FAILED;
        }
        base = NetherPortalFrame.findBase(ctx, mode.includeCorners, mode == FrameMode.RESOURCE_SAVING);
        if (base == null) {
            status = "no flat space for a portal frame";
            return TaskStatus.FAILED;
        }
        frameOrder = PortalBuildingPolicy.order(base, mode.includeCorners,
                buildStrategy, ctx.player.getX());
        state = State.BUILD;
        status = "building a " + mode.label + " portal frame";
        return TaskStatus.RUNNING;
    }

    private TaskStatus build(BotContext ctx) {
        List<BlockPos> frame = frameOrder.isEmpty()
                ? NetherPortalFrame.positions(base, mode.includeCorners) : frameOrder;
        frameIndex = completedFrameBlocks(ctx, frame);
        if (frameIndex >= frame.size()) {
            frameTarget = null;
            state = State.LIGHT;
            return TaskStatus.RUNNING;
        }

        if (frameTarget == null || targetIsComplete(ctx, frameTarget,
                NetherPortalFrame.isCorner(base, frameTarget))) {
            frameTarget = frame.stream()
                    .filter(pos -> !targetIsComplete(ctx, pos, NetherPortalFrame.isCorner(base, pos)))
                    .findFirst()
                    .orElse(null);
            placeTicks = 0;
            stopApproach(ctx);
        }
        if (frameTarget == null) {
            state = State.LIGHT;
            return TaskStatus.RUNNING;
        }

        BlockPos target = frameTarget;
        boolean corner = NetherPortalFrame.isCorner(base, target);
        ctx.debug.target("portal frame " + (frameIndex + 1) + "/" + frame.size(), target,
                ctx.level.getBlockState(target).getBlock().getName().getString());

        if (targetIsComplete(ctx, target, corner)) {
            frameIndex = Math.min(frame.size(), frameIndex + 1);
            frameTarget = null;
            placeTicks = 0;
            stopApproach(ctx);
            return TaskStatus.RUNNING;
        }

        Block material = materialFor(corner);
        if (material == null) {
            status = corner
                    ? "no dirt or cobblestone left for a portal corner"
                    : "ran out of obsidian at frame block " + (frameIndex + 1);
            return TaskStatus.FAILED;
        }
        if (!BlockPlacer.isReplaceable(ctx, target)) {
            status = "portal frame is blocked at " + target.toShortString();
            return TaskStatus.FAILED;
        }

        if (!inReach(ctx, target)) {
            if (approach == null) {
                approach = new GotoTask(new Goals.Adjacent(target, PLACE_REACH), true, false);
                approach.start(ctx);
            }
            TaskStatus walk = approach.tick(ctx);
            status = "moving to place frame block " + (frameIndex + 1);
            if (walk == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            stopApproach(ctx);
            if (walk == TaskStatus.FAILED) {
                status = "could not reach frame block " + (frameIndex + 1);
                return TaskStatus.FAILED;
            }
            return TaskStatus.RUNNING;
        }
        stopApproach(ctx);

        if (++placeTicks > PLACE_TIMEOUT_TICKS) {
            status = "could not place frame block " + (frameIndex + 1);
            return TaskStatus.FAILED;
        }
        BlockPlacer.PlacementResult placement = place(ctx, material, target);
        if (placement == BlockPlacer.PlacementResult.PLACED
                || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
            frameIndex = Math.min(frame.size(), frameIndex + 1);
            frameTarget = null;
            placeTicks = 0;
            status = "placed " + frameIndex + "/" + frame.size() + " frame blocks";
            return TaskStatus.RUNNING;
        }
        if (!placement.isTransient()) {
            status = "could not place frame block " + (frameIndex + 1)
                    + " (" + placement.name().toLowerCase() + ")";
            return TaskStatus.FAILED;
        }
        status = "placing frame block " + (frameIndex + 1);
        return TaskStatus.RUNNING;
    }

    private int completedFrameBlocks(BotContext ctx, List<BlockPos> frame) {
        int completed = 0;
        for (BlockPos pos : frame) {
            if (targetIsComplete(ctx, pos, NetherPortalFrame.isCorner(base, pos))) {
                completed++;
            }
        }
        return completed;
    }

    private boolean targetIsComplete(BotContext ctx, BlockPos target, boolean corner) {
        if (ctx.level.getBlockState(target).is(Blocks.OBSIDIAN)) {
            return true;
        }
        return corner && mode == FrameMode.RESOURCE_SAVING
                && !BlockPlacer.isReplaceable(ctx, target);
    }

    private Block materialFor(boolean corner) {
        if (!corner || mode.cornersAreObsidian) {
            return Blocks.OBSIDIAN;
        }
        return cornerMaterials.stream()
                .filter(block -> block != null && block.asItem() != Items.AIR)
                .findFirst()
                .orElse(null);
    }

    private BlockPlacer.PlacementResult place(BotContext ctx, Block preferred, BlockPos target) {
        if (preferred == Blocks.OBSIDIAN) {
            return BlockPlacer.tryPlace(ctx, preferred, target);
        }
        BlockPlacer.PlacementResult last = BlockPlacer.PlacementResult.NO_MATERIAL;
        for (Block material : cornerMaterials) {
            if (material == null || material.asItem() == Items.AIR
                    || InventoryHelper.findSlot(ctx.player, stack -> stack.is(material.asItem())) < 0) {
                continue;
            }
            last = BlockPlacer.tryPlace(ctx, material, target);
            if (last == BlockPlacer.PlacementResult.PLACED
                    || last == BlockPlacer.PlacementResult.ALREADY_PRESENT
                    || last.isTransient()) {
                return last;
            }
        }
        return last;
    }

    private TaskStatus light(BotContext ctx) {
        if (portal != null || (portal = findPortal(ctx, ctx.player.blockPosition(), 48)) != null) {
            state = enterAfterBuild ? State.ENTER : State.DONE;
            return TaskStatus.RUNNING;
        }
        if (++lightTicks > LIGHT_TIMEOUT_TICKS) {
            status = "could not light the completed portal frame";
            return TaskStatus.FAILED;
        }
        if (InventoryHelper.equip(ctx, stack -> stack.is(Items.FLINT_AND_STEEL)) < 0
                && InventoryHelper.equip(ctx, stack -> stack.is(Items.FIRE_CHARGE)) < 0) {
            status = "nothing left to light the portal with";
            return TaskStatus.FAILED;
        }
        BlockPos support = base.offset(1, 0, 0);
        Vec3 hit = Vec3.atCenterOf(support).add(0.0, 0.5, 0.0);
        ctx.look.lookAt(ctx.player, hit);
        if (ctx.look.isLookingAt(ctx.player, hit, AIM_TOLERANCE)) {
            ctx.gameMode.useItemOn(ctx.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.UP, support, false));
            ctx.player.swing(InteractionHand.MAIN_HAND);
        }
        status = "lighting the portal";
        return TaskStatus.RUNNING;
    }

    private TaskStatus enter(BotContext ctx) {
        if (ctx.level.dimension() == Level.NETHER) {
            status = "entered the Nether";
            return TaskStatus.SUCCESS;
        }
        if (portal == null) {
            portal = findPortal(ctx, ctx.player.blockPosition(), 64);
        }
        if (portal == null) {
            status = "portal disappeared";
            return TaskStatus.FAILED;
        }
        if (!inReach(ctx, portal)) {
            if (approach == null) {
                approach = new GotoTask(new Goals.Near(portal, 2), true, false);
                approach.start(ctx);
            }
            TaskStatus walk = approach.tick(ctx);
            status = "walking to the portal";
            if (walk == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            stopApproach(ctx);
            if (walk == TaskStatus.FAILED) {
                status = "could not reach the portal";
                return TaskStatus.FAILED;
            }
        }
        ctx.look.lookAt(ctx.player, Vec3.atCenterOf(portal));
        ctx.input.forward = true;
        status = "entering the Nether";
        return TaskStatus.RUNNING;
    }

    private static BlockPos findPortal(BotContext ctx, BlockPos centre, int radius) {
        return BlockScanner.findNearest(ctx.level, centre, Set.of(Blocks.NETHER_PORTAL), radius,
                ctx.level.getMinY(), ctx.level.getMaxY());
    }

    private static boolean inReach(BotContext ctx, BlockPos pos) {
        return ctx.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))
                <= PLACE_REACH * PLACE_REACH + 4.0;
    }

    private void stopApproach(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        stopApproach(ctx);
        ctx.input.reset();
    }
}
