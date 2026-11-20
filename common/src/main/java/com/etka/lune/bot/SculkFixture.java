package com.etka.lune.bot;

import com.etka.lune.Constants;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The sculk corridor: Self Preservation's sneak examined where every step can be heard.
 *
 * <p>A flat walkway laid high above the spawn, as the lava maze is, with sculk sensors standing
 * beside the lane the way they line a deep dark floor: ordinary ones and calibrated ones, which
 * hear twice as far, plus a few shriekers that are allowed to summon. The task walks the lane one
 * way and back, so every sensor gets walked past over and over. Nothing else in the arena can make
 * a sound, so every activation on the watcher is a step the card let slip.</p>
 *
 * <p>Two halves on two threads, like the maze's. {@link #build} lays the corridor on the
 * integrated server and puts the bot at its west end; {@link Check} watches from the client tick
 * and writes one line per run to {@code config/lune-sculk-checks.txt}: how many times any sensor
 * went active, how many times a shrieker shrieked, whether a warden ever stood in the arena, and
 * of the ticks the bot spent inside a sensor's hearing, how many it spent crouched. A run where
 * the sneak covers the whole walk reads {@code verdict=quiet}; the worst reads
 * {@code verdict=warden}.</p>
 */
final class SculkFixture {

    /** How far above the bot the walkway is laid, so whatever the spawn is holds nothing else. */
    static final int RISE = 16;
    /** The lane's length along x: long enough that the far sensors load before they are reached. */
    static final int LENGTH = 76;
    /** The lane runs three blocks wide down the middle, and the sensors stand on the outer lanes. */
    static final int HALF_WIDTH = 2;
    /** Blocks of clear air above the sensors, so nothing brushes a head on the way past. */
    static final int HEADROOM = 4;
    /** How often a sensor or shrieker stands: a steady beat of ears all down the walk. */
    static final int SPACING = 9;
    /**
     * What a sensor hears, and what the watcher counts as earshot: the raw eight or sixteen, not
     * the card's margin - the card's margin is its own safety, the question here is whether a step
     * inside the real radius made a sound.
     */
    static final double SENSOR_RANGE = 8.0;
    static final double CALIBRATED_RANGE = 16.0;
    /**
     * The terrain stretch, in offsets along the corridor: the floor climbs one bare block
     * (a jump both ways), carries a half slab, leaves by a stair, climbs again and ends on
     * another bare drop - so each direction meets a jump up, a slab and a stair, and an edge
     * a crouch cannot walk off.
     */
    static final int CLIMB_A_START = 31;
    static final int CLIMB_A_END = 39;
    static final int STAIR_X = 40;
    static final int CLIMB_B_START = 42;
    static final int CLIMB_B_END = 44;
    static final int SLAB_X = 34;
    /**
     * The maze: hedges of sensors across the lane, each a block thick with one door in it, the
     * doors staggered so the only way east is a serpentine that never stops brushing past an ear.
     * Between the hedges stand lone sensors, dips in the floor, pits it cannot climb, pockets of
     * lava and one trench it can only hop - the corridor's every hazard with nowhere straight to
     * walk through it.
     */
    static final int MAZE_HALF_WIDTH = 6;
    static final int[] HEDGE_XS = {10, 16, 22, 28, 34, 40, 46, 52, 58, 64};
    /** The door each hedge leaves open, as the z it starts at; every door is three cells wide. */
    static final int[] DOOR_ZS = {-1, 4, -6, -1, 3, -5, -1, 4, -6, 0};
    /** How far round the bot the watcher looks for a warden. */
    static final double WARDEN_RADIUS = 48.0;
    /** Beside {@code lune-runs}, never in it: the same convention as the maze's checks file. */
    static final String CHECKS_FILE = "lune-sculk-checks.txt";

    /**
     * The arena's rules, as the maze's keeper keeps them: normal difficulty, which the shriekers
     * need if a warden is ever to come, and nothing else spawning or moving. No weather and no
     * clock, so the run reads identically in any spawn.
     */
    private static final String[] RULES = {
            "difficulty normal",
            "gamerule mob_griefing false",
            "gamerule spawn_monsters false",
            "gamerule spawn_mobs false",
            "gamerule advance_time false",
            "gamerule advance_weather false",
            // The world must be silent for the run to mean anything: leaves severed by the carve
            // would keep dying of decay, and the lava pockets would keep lighting what they can
            // reach - both say block_destroy, and a sensor cannot tell it from a foot. No random
            // ticks, no fire spread: everything the ears hear is then the bot's.
            "gamerule random_tick_speed 0",
            "gamerule fire_spread_radius_around_player -1",
            "weather clear",
            "time set day"};

    private SculkFixture() {}

    /**
     * Where the corridor was laid: the block the bot starts on, and every sensor and shrieker,
     * so the watcher can read their phases without another sweep.
     */
    record Placed(BlockPos westEnd, List<BlockPos> sensors, List<BlockPos> shriekers) {}

    /**
     * Lays the corridor east of the bot and stands it at the west end. Runs on the integrated
     * server's thread, as {@code LavaMazeFixture.build} does.
     *
     * <p>The lane is five blocks of stone bricks across, walled in glass so a drift off the middle
     * is a brush against a wall and not a fall. The sensors stand on the outer lanes on top of the
     * floor, alternating sides and kinds - an ordinary sensor every nine blocks on one side, a
     * calibrated one every other nine on the other - with two more ordinary ones right on the
     * walking line, the closest pass there is. Three shriekers stand between them, told they may
     * summon: four shrieks and the run has a warden.</p>
     *
     * <p>The lane is not flat all the way: the floor climbs one bare block (a jump, both ways),
     * carries a half slab, leaves by a stair, climbs again and ends on another bare drop, so each
     * direction meets a jump up, a slab and a stair, and an edge a crouch cannot simply walk off.
     * The watcher counts the airborne and landing ticks inside earshot too, so a loud landing is
     * attributed, not folded into the walk.</p>
     */
    static Placed build(MinecraftServer server, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        for (String command : RULES) {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        BlockState floor = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState sensor = Blocks.SCULK_SENSOR.defaultBlockState();
        BlockState calibrated = Blocks.CALIBRATED_SCULK_SENSOR.defaultBlockState();
        BlockState shrieker = Blocks.SCULK_SHRIEKER.defaultBlockState()
                .setValue(SculkShriekerBlock.CAN_SUMMON, true);
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);

        int floorY = Math.min(level.getMaxY() - HEADROOM - 3, player.getBlockY() + RISE);
        BlockPos origin = new BlockPos(player.getBlockX(), floorY, player.getBlockZ());
        List<BlockPos> sensors = new ArrayList<>();
        List<BlockPos> shriekers = new ArrayList<>();

        for (int x = 0; x <= LENGTH; x++) {
            boolean raised = raised(x);
            for (int z = -HALF_WIDTH - 1; z <= HALF_WIDTH + 1; z++) {
                BlockPos base = origin.offset(x, 0, z);
                boolean edge = z == -HALF_WIDTH - 1 || z == HALF_WIDTH + 1;
                if (edge) {
                    put(level, base, glass);
                } else {
                    put(level, base, floor);
                    if (raised) {
                        put(level, base.above(), floor);
                    }
                }
                int clearFrom = raised && !edge ? 2 : 1;
                for (int dy = clearFrom; dy <= HEADROOM + 1; dy++) {
                    BlockPos at = base.above(dy);
                    if (edge && dy <= 3) {
                        put(level, at, glass);
                    } else if (!level.getBlockState(at).isAir()) {
                        put(level, at, air);
                    }
                }
            }
        }
        // A half slab on the first plateau and a stair leaving it: the quiet climbs.
        for (int z = -HALF_WIDTH; z <= HALF_WIDTH; z++) {
            put(level, origin.offset(SLAB_X, 2, z), slab);
            put(level, origin.offset(STAIR_X, 1, z), stair);
        }

        for (int i = 0, x = 6; x <= LENGTH - 6; x += SPACING, i++) {
            // Alternate the side and the kind, so the walk meets both ears on both flanks.
            int z = (i & 1) == 0 ? HALF_WIDTH : -HALF_WIDTH;
            int dy = raised(x) ? 2 : 1;
            BlockPos at = origin.offset(x, dy, z);
            put(level, at, (i & 2) == 0 ? sensor : calibrated);
            sensors.add(at);
        }
        // Two right on the walking line: the closest pass there is.
        for (int x = 27; x <= 48; x += 21) {
            BlockPos at = origin.offset(x, 1, 0);
            put(level, at, sensor);
            sensors.add(at);
        }
        for (int i = 0, x = 21; x <= LENGTH - 10 && shriekers.size() < 3; x += 22, i++) {
            int z = (i & 1) == 0 ? -HALF_WIDTH : HALF_WIDTH;
            int dy = raised(x) ? 2 : 1;
            BlockPos at = origin.offset(x, dy, z);
            put(level, at, shrieker);
            shriekers.add(at);
        }
        // And a row of them on the wall tops at the corners - the corridor's ends and the lips
        // where the lane changes shape, which is where a hop or a slide is likeliest to slip a
        // step. A shrieker cannot hear a step itself: it answers a sensor's relay or a foot set
        // on it. Sitting above the lane they are pure tripwires - if anything wakes a sensor
        // near a corner, the corner's shrieker is the flag that the quiet did not hold there.
        for (int cx : new int[]{0, LENGTH, CLIMB_A_START - 1, STAIR_X + 1, CLIMB_B_END + 1}) {
            for (int z = -HALF_WIDTH - 1; z <= HALF_WIDTH + 1; z += (HALF_WIDTH + 1) * 2) {
                BlockPos at = origin.offset(cx, HEADROOM, z);
                put(level, at, shrieker);
                shriekers.add(at);
            }
        }

        BlockPos feet = origin.offset(1, 1, 0);
        player.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
                java.util.Set.of(), -90.0F, 0.0F, true);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        Constants.LOG.info("AutoRun: fixture sculk - corridor {} blocks east of {}, {} sensors,"
                        + " {} shriekers, bot at {}",
                LENGTH, origin.toShortString(), sensors.size(), shriekers.size(),
                feet.toShortString());
        return new Placed(feet, sensors, shriekers);
    }

    /** Whether the corridor floor stands one block higher at this offset along it. */
    private static boolean raised(int x) {
        return x >= CLIMB_A_START && x <= CLIMB_A_END || x >= CLIMB_B_START && x <= CLIMB_B_END;
    }

    /**
     * The maze's version of the corridor: no walking line at all, just ears.
     *
     * <p>Ten hedges of sensors stand across the lane, each a block thick and full from wall to
     * wall but for one three-cell door, and the doors are staggered so the way east weaves the
     * whole width and never stops being beside a sensor. In the open lanes between the hedges
     * stand lone sensors, a dip a body can step into and out of, two pits three blocks deep,
     * two pockets of lava laid flush with the floor, and one trench a crouch can only leave by
     * the hop. Shriekers that may summon sit at door mouths and on the wall tops at the ends
     * and middle, so that a step heard anywhere is a shriek somewhere. The same examiner reads
     * it: it counts from block positions, not from the shape they were laid in.</p>
     */
    static Placed buildMaze(MinecraftServer server, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        for (String command : RULES) {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        BlockState floor = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState lava = Blocks.LAVA.defaultBlockState();
        BlockState sensor = Blocks.SCULK_SENSOR.defaultBlockState();
        BlockState calibrated = Blocks.CALIBRATED_SCULK_SENSOR.defaultBlockState();
        BlockState shrieker = Blocks.SCULK_SHRIEKER.defaultBlockState()
                .setValue(SculkShriekerBlock.CAN_SUMMON, true);

        int floorY = Math.min(level.getMaxY() - HEADROOM - 3, player.getBlockY() + RISE);
        BlockPos origin = new BlockPos(player.getBlockX(), floorY, player.getBlockZ());
        List<BlockPos> sensors = new ArrayList<>();
        List<BlockPos> shriekers = new ArrayList<>();

        for (int x = 0; x <= LENGTH; x++) {
            for (int z = -MAZE_HALF_WIDTH - 1; z <= MAZE_HALF_WIDTH + 1; z++) {
                BlockPos base = origin.offset(x, 0, z);
                boolean edge = z == -MAZE_HALF_WIDTH - 1 || z == MAZE_HALF_WIDTH + 1;
                int clearFrom;
                if (edge) {
                    put(level, base, glass);
                    clearFrom = 1;
                } else {
                    int floorAt = mazeFloor(x, z);
                    if (floorAt == Integer.MIN_VALUE) {
                        put(level, base.below(), lava);
                        clearFrom = 0;
                    } else {
                        put(level, floorAt == 0 ? base : base.below(-floorAt), floor);
                        clearFrom = floorAt + 1;
                    }
                }
                for (int dy = clearFrom; dy <= HEADROOM + 1; dy++) {
                    BlockPos at = base.above(dy);
                    if (edge && dy <= 3) {
                        put(level, at, glass);
                    } else if (!level.getBlockState(at).isAir()) {
                        put(level, at, air);
                    }
                }
            }
        }

        // The hedges: a sensor on every cell of the line but the door's three, kind alternating
        // so the walk meets both ears on both flanks.
        for (int i = 0; i < HEDGE_XS.length; i++) {
            int x = HEDGE_XS[i];
            int door = DOOR_ZS[i];
            for (int z = -MAZE_HALF_WIDTH; z <= MAZE_HALF_WIDTH; z++) {
                if (z >= door && z <= door + 2) {
                    continue;
                }
                BlockPos at = origin.offset(x, 1, z);
                put(level, at, ((x + z) & 3) == 0 ? calibrated : sensor);
                sensors.add(at);
            }
        }
        // Lone ears in the open lanes, for the spaces the hedges leave. None near the start: the
        // card's own sneak is a scan and a packet late, so an ear inside earshot of the spawn
        // hears the run's first steps before it - a quiet apron is the fixture's to give.
        for (int[] at : new int[][]{{13, -4}, {13, 3}, {25, -2}, {25, 4}, {43, -4}, {43, 2},
                {61, -3}, {61, 3}, {70, 0}, {73, -2}}) {
            BlockPos pos = origin.offset(at[0], 1, at[1]);
            put(level, pos, (at[0] & 1) == 0 ? calibrated : sensor);
            sensors.add(pos);
        }
        // Shriekers beside a few door mouths, where the squeeze is tightest...
        for (int[] at : new int[][]{{15, 3}, {21, -3}, {33, 2}, {47, -4}, {57, -3}}) {
            BlockPos pos = origin.offset(at[0], 1, at[1]);
            put(level, pos, shrieker);
            shriekers.add(pos);
        }
        // ...and on the wall tops at the ends and the middle, as pure tripwires.
        for (int x : new int[]{0, LENGTH / 2, LENGTH}) {
            for (int z = -MAZE_HALF_WIDTH - 1; z <= MAZE_HALF_WIDTH + 1;
                    z += (MAZE_HALF_WIDTH + 1) * 2) {
                BlockPos pos = origin.offset(x, HEADROOM, z);
                put(level, pos, shrieker);
                shriekers.add(pos);
            }
        }

        BlockPos feet = origin.offset(0, 1, 0);
        player.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
                java.util.Set.of(), -90.0F, 0.0F, true);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        Constants.LOG.info("AutoRun: fixture sculk-maze - {} hedges on the lane east of {},"
                        + " {} sensors, {} shriekers, bot at {}",
                HEDGE_XS.length, origin.toShortString(), sensors.size(), shriekers.size(),
                feet.toShortString());
        return new Placed(feet, sensors, shriekers);
    }

    /**
     * What the maze floor does at a cell, as the y it rests at: 0 the ordinary level, -1 a dip
     * a body can step down and hop back up - the trench among them, a two-cell channel through
     * the lane's middle - -3 a pit it cannot climb out of, and {@link Integer#MIN_VALUE} a lava
     * pocket, whose lava stands a block under the rim.
     */
    private static int mazeFloor(int x, int z) {
        if (x >= 13 && x <= 14 && z >= -1 && z <= 1) {
            return -1;
        }
        if (x >= 30 && x <= 31 && z >= 2 && z <= 3
                || x >= 49 && x <= 50 && z >= -4 && z <= -3) {
            return -3;
        }
        if (x >= 44 && x <= 45 && z >= -3 && z <= 1) {
            return -1;
        }
        if (x >= 19 && x <= 20 && z >= -5 && z <= -4
                || x >= 55 && x <= 56 && z >= 3 && z <= 4) {
            return Integer.MIN_VALUE;
        }
        return 0;
    }

    private static void put(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * The examiner. Reads the arena from the client tick and keeps the run's account.
     *
     * <p>A sensor going {@code ACTIVE} is one vibration heard; the count of them is the count of
     * loud steps the run took, wherever the card was looking. A shrieker's {@code SHRIEKING} face
     * is a warning level given, and four of those is a warden, which the watcher reports as its
     * own entry rather than waiting for the task to notice. Earshot is measured at the real eight
     * and sixteen blocks: every tick the bot stood inside it while not crouched is a tick a sensor
     * was free to hear, whether or not it happened to.</p>
     *
     * <p>The first watched tick only primes the phase map: whatever the build and the teleport
     * stirred up is before the run, not part of it.</p>
     *
     * <p>Client thread only, apart from {@link Placed}, which the server writes once.</p>
     */
    static final class Check {
        private Placed arena;
        private boolean primed;
        private int ticks;
        private final Map<BlockPos, SculkSensorPhase> phases = new HashMap<>();
        private final Map<BlockPos, Boolean> shrieking = new HashMap<>();
        private final Map<BlockPos, Integer> perSensor = new HashMap<>();
        private int woken;
        private int shrieks;
        private int wardenTick = -1;
        private int maxWardens;
        private int earshotTicks;
        private int quietTicks;
        private int airborneTicks;
        private int jumpTicks;
        private int landings;
        private boolean wasAirborne;
        private int guardTicks;
        private int sculkTicks;
        private double walked;
        private Vec3 lastAt;
        private boolean reported;

        void watch(Minecraft mc, BotEngine engine, Placed placed, int tick) {
            LocalPlayer player = mc.player;
            if (placed == null || player == null || mc.level == null || reported) {
                return;
            }
            arena = placed;
            ticks = tick;

            for (BlockPos pos : placed.sensors()) {
                BlockState state = mc.level.getBlockState(pos);
                if (!state.is(Blocks.SCULK_SENSOR) && !state.is(Blocks.CALIBRATED_SCULK_SENSOR)) {
                    continue;
                }
                SculkSensorPhase now = SculkSensorBlock.getPhase(state);
                SculkSensorPhase before = phases.put(pos, now);
                if (primed && now == SculkSensorPhase.ACTIVE && before != SculkSensorPhase.ACTIVE) {
                    woken++;
                    perSensor.merge(pos, 1, Integer::sum);
                    Constants.LOG.info(
                            "AutoRun: sculk - sensor {} woke at tick {}, player {} ground={} sneak={}"
                                    + " fall={} keys={}",
                            pos.toShortString(), tick, player.position(), player.onGround(),
                            player.isShiftKeyDown(), round(player.fallDistance),
                            player.input.keyPresses);
                    // Which game event did it hear, and where from? The vibration it is processing
                    // is the server's field, so ask the server's block entity rather than the
                    // unsynced client copy - its event name, band and source position name the
                    // culprit outright (step=1, hit_ground=2, entity_action=4, entity_damage=7,
                    // block_destroy=12, teleport=14 - the full map is VibrationSystem's).
                    if (mc.getSingleplayerServer() != null) {
                        mc.getSingleplayerServer().execute(() -> {
                            if (!(mc.getSingleplayerServer().overworld().getBlockEntity(pos)
                                    instanceof net.minecraft.world.level.block.entity
                                            .SculkSensorBlockEntity ear)) {
                                return;
                            }
                            int freq = ear.getLastVibrationFrequency();
                            net.minecraft.world.level.gameevent.vibrations.VibrationInfo info =
                                    ear.getVibrationData().getCurrentVibration();
                            Constants.LOG.info("AutoRun: sculk - sensor {} heard {} (freq {})"
                                            + " at {} from {}",
                                    pos.toShortString(),
                                    info == null ? vibrationName(freq)
                                            : net.minecraft.core.registries.BuiltInRegistries
                                                    .GAME_EVENT.getKey(info.gameEvent().value()),
                                    freq,
                                    info == null ? "?" : info.pos(),
                                    info == null ? "?" : info.entity());
                        });
                    }
                }
            }
            for (BlockPos pos : placed.shriekers()) {
                BlockState state = mc.level.getBlockState(pos);
                if (!state.is(Blocks.SCULK_SHRIEKER)) {
                    continue;
                }
                boolean now = state.getValue(SculkShriekerBlock.SHRIEKING);
                if (primed && now && !shrieking.getOrDefault(pos, false)) {
                    shrieks++;
                    Constants.LOG.info("AutoRun: sculk - shrieker {} shrieking at tick {}, player {}",
                            pos.toShortString(), tick, player.position());
                }
                shrieking.put(pos, now);
            }
            primed = true;

            List<Warden> wardens = mc.level.getEntitiesOfClass(Warden.class,
                    player.getBoundingBox().inflate(WARDEN_RADIUS));
            if (!wardens.isEmpty() && wardenTick < 0) {
                wardenTick = tick;
                Constants.LOG.info("AutoRun: sculk - a warden stands in the arena at tick {}", tick);
            }
            maxWardens = Math.max(maxWardens, wardens.size());

            Vec3 at = player.position();
            if (lastAt != null) {
                walked += Math.hypot(at.x - lastAt.x, at.z - lastAt.z);
            }
            lastAt = at;

            boolean heard = false;
            for (BlockPos pos : placed.sensors()) {
                double reach = mc.level.getBlockState(pos).is(Blocks.CALIBRATED_SCULK_SENSOR)
                        ? CALIBRATED_RANGE : SENSOR_RANGE;
                if (pos.distToCenterSqr(at) <= reach * reach) {
                    heard = true;
                    break;
                }
            }
            if (heard) {
                earshotTicks++;
                if (player.isShiftKeyDown()) {
                    quietTicks++;
                }
                if (!player.onGround()) {
                    airborneTicks++;
                }
                if (player.input.keyPresses.jump()) {
                    jumpTicks++;
                }
                if (wasAirborne && player.onGround()) {
                    landings++;
                }
            }
            wasAirborne = !player.onGround();

            if (engine.getCurrent() instanceof TaskRunner runner) {
                StatusText answering = runner.answeringGuardLine();
                if (answering != null) {
                    guardTicks++;
                    if ("creeping_past_sculk_sensor".equals(LavaMazeFixture.shortKey(answering))) {
                        sculkTicks++;
                    }
                }
            }
        }

        /**
         * The vibration a sensor last held, named from its band: {@code VibrationInfo} is already
         * gone by the time the server thread is asked, but the band persists on the block entity
         * and every vanilla event lives alone in its band (VibrationSystem's registry is the map:
         * step=1, hit_ground=2, entity_action=4, entity_damage=7, block_destroy=12,
         * block_place=13, teleport=14, entity_die/explode=15).
         */
        private static String vibrationName(int freq) {
            return switch (freq) {
                case 1 -> "step/swim/flap";
                case 2 -> "hit_ground/projectile_land/splash";
                case 3 -> "item_interact/elytra";
                case 4 -> "entity_action";
                case 5 -> "entity_dismount/equip/shear";
                case 6 -> "entity_mount/drink/eat/prime_fuse";
                case 7 -> "ENTITY_DAMAGE";
                case 8 -> "entity_interact";
                case 9 -> "entity_roar/shake";
                case 10 -> "block_open/activate/etc";
                case 11 -> "block_change";
                case 12 -> "BLOCK_DESTROY/fluid_pickup";
                case 13 -> "block_place/fluid_place";
                case 14 -> "teleport/entity_place/lightning";
                case 15 -> "entity_die/explode";
                default -> "?";
            };
        }

        /**
         * Writes the run's one line, once. The verdict is the worst thing the corridor heard: a
         * warden, then a shriek, then any sensor woken at all, and {@code quiet} when the sneak
         * covered the walk - the card's margin means it also counts ticks the player spent inside
         * earshot standing up.
         */
        void conclude(Minecraft mc, long worldSeed, String reason) {
            if (reported) {
                return;
            }
            reported = true;
            String verdict = wardenTick >= 0 ? "warden"
                    : shrieks > 0 ? "shrieked"
                    : woken > 0 ? "heard" : "quiet";
            String line = "seed=" + worldSeed
                    + " ticks=" + ticks
                    + " verdict=" + verdict
                    + " sensors=" + (arena == null ? "-" : arena.sensors().size())
                    + " woken=" + woken
                    + " sensorshit=" + perSensor.size()
                    + " shriekers=" + (arena == null ? "-" : arena.shriekers().size())
                    + " shrieks=" + shrieks
                    + " wardens=" + maxWardens
                    + " warden=" + (wardenTick < 0 ? "-" : wardenTick)
                    + " earshot=" + earshotTicks
                    + " quiet=" + quietTicks
                    + " exposed=" + (earshotTicks - quietTicks)
                    + " airborne=" + airborneTicks
                    + " jumps=" + jumpTicks
                    + " landings=" + landings
                    + " guardticks=" + guardTicks
                    + " sculkticks=" + sculkTicks
                    + " walked=" + round(walked)
                    + " reason=" + reason.replace(' ', '_');
            Constants.LOG.info("AutoRun: sculk - {}", line);
            try {
                Path directory = Services.PLATFORM.getConfigDir();
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(CHECKS_FILE), line + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                Constants.LOG.warn("AutoRun: could not write the sculk check", e);
            }
        }
    }
}
