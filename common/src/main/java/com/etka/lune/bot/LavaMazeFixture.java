package com.etka.lune.bot;

import com.etka.lune.Constants;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.compat.Screenshots;
import com.etka.lune.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The lava maze: Self Preservation examined on ground where a wrong step is the last one.
 *
 * <p>Every escape the card makes on foot goes through {@code SafeStep}, whose rules were written
 * after a bastion: the keys go where the check looked, ground beside a drop that hurts only when
 * nothing else is left, a diagonal only between two pieces of ground. Nothing held the card to them
 * since. The arenas are flat floors, and a fight on a flat floor never asks where the edge is. Here
 * every corridor is an edge: a {@link LavaMaze} of walkway over a sea of lava, every wall a maze
 * would have a hole straight down into it, and a chaser from the mob keeper on the walkway with the
 * bot. The task runs nothing but Self Preservation, so whatever moves the bot is the card.</p>
 *
 * <p>{@code lava-maze} is the flat maze, {@code lava-maze-2} and {@code -3} the same with wider
 * corridors, and {@code lava-maze-bastion} the bad side of a bastion: blackstone rooms at three
 * heights in a lava sea standing level with the lowest of them, lava pockets in their corners,
 * bridges, gaps and leaps between them, and walls to be cornered against (see
 * {@link LavaMaze.Style#BASTION}). A Zombie cannot jump a gap, so a chaser left on another island
 * than the bot's is moved over to the bot's after {@link #STRANDED_TICKS}, and a new one is only
 * ever put on the bot's own island. Without that, one jump would end the test: the chaser would
 * stand at the edge for the rest of the run, close enough to keep the card answering it and unable
 * to come any nearer.</p>
 *
 * <p>Two halves on two threads. {@link #build} lays the maze on the integrated server and puts the
 * bot in the middle of it; {@link Check} watches from the client tick and writes one line per run
 * to {@code config/lune-lava-maze-checks.txt}: whether the bot got away every time, fell, or was
 * caught, how many holes it jumped and how often it landed on another island, and for a fall what
 * the card was doing on the last tick its feet were on the walkway and who had just hit it.
 * {@code the run harness} tabulates the lines.</p>
 */
final class LavaMazeFixture {

    /**
     * How far above the bot the walkway is laid. Up in the air, so whatever the spawn is - a
     * forest, a hillside, the sea - nothing but the maze is in it.
     */
    static final int RISE = 16;
    /**
     * Lava between the maze and its wall, besides the maze's own outer ring of holes: three blocks
     * from the last walkway to the wall, which no jump from the walkway clears.
     */
    static final int MARGIN = 2;
    /**
     * How high the glass round the lava stands above the lowest walkway. Out of reach of a jump, and
     * more than the five blocks Self Preservation's retreat looks up for somewhere to stand above
     * the highest floor, so the top of it is never a place it tries to run to.
     */
    static final int RIM_RISE = LavaMaze.MAX_HEIGHT + 7;
    /** Feet this far under the lowest walkway's top have left it: the only thing below is lava. */
    static final double FALL_DEPTH = 0.5;
    /** A hostile this near is a chase begun: Self Preservation's own default distance. */
    static final double ENCOUNTER_RADIUS = 8.0;
    /** Ticks with nothing that near before a chase counts as over, however it ended. */
    static final int ENCOUNTER_QUIET_TICKS = 60;
    /** How far round the bot the examiner keeps track of hostiles, and of whether they died. */
    static final double SCAN_RADIUS = 16.0;
    /**
     * Ticks a chaser may stand on another island than the bot's before it is moved to the bot's.
     * Long enough for the jump to be seen to have worked - the chaser at the edge, the card still
     * answering it across the gap - and short of the run going quiet.
     */
    static final int STRANDED_TICKS = 100;
    /** Nearer than this a chaser is put in the bot's face, not on its trail. */
    static final double MIN_CHASER_DISTANCE = 5.0;
    /** How far a chaser's own route search reaches, in blocks: past the walk round a twelve-room maze. */
    static final double CHASER_FOLLOW_RANGE = 96.0;
    /** Ticks a chaser on the bot's island may stand without moving a block before it counts as stuck. */
    static final int STUCK_TICKS = 200;
    /** Nearer the bot than this a chaser standing still is hitting it, not stuck. */
    static final double STUCK_REACH = 4.0;
    /** Beside {@code lune-runs}, never in it: the batch script takes the newest .txt there. */
    static final String CHECKS_FILE = "lune-lava-maze-checks.txt";

    /**
     * The arena's rules, as the Ghast arena keeps them: daylight held, no weather, nothing spawning
     * but what the keeper puts there, and no mob griefing, so a Creeper's blast cannot open a new
     * hole in the floor halfway through a run and make the next one a different maze.
     */
    private static final String[] RULES = {
            "difficulty normal",
            "gamerule mob_griefing false",
            "gamerule spawn_monsters false",
            "gamerule spawn_mobs false",
            "gamerule advance_time false",
            "gamerule advance_weather false",
            "weather clear",
            "time set day"};

    /** Chasers on another island than the bot's, by id, and for how many ticks. Server thread. */
    private static final Map<Integer, Integer> stranded = new ConcurrentHashMap<>();
    /** Where each chaser was at the keeper's last check, and for how many ticks it has not moved. */
    private static final Map<Integer, Vec3> lastSeenAt = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> stillFor = new ConcurrentHashMap<>();
    /** Chasers moved to the bot's island this run; read by the examiner on the client. */
    private static final AtomicInteger herded = new AtomicInteger();

    private LavaMazeFixture() {}

    /**
     * Where a maze was laid: tile {@code (0, 0)} of the lowest walkway is {@code origin}.
     *
     * @param seed what the plan was generated from, so a run can be repeated
     */
    record Placed(BlockPos origin, LavaMaze maze, long seed) {

        /** The height the feet stand at on the lowest walkway; below it there is only lava. */
        double walkTop() {
            return origin.getY() + 1.0;
        }

        /** Whether the walkway runs under this point of the world. */
        boolean walkwayUnder(double x, double z) {
            return maze.walkway(tileX(x), tileZ(z));
        }

        /** The island under this point of the world, or -1 over a hole. */
        int islandAt(double x, double z) {
            return maze.island(tileX(x), tileZ(z));
        }

        /** How high the walkway under this point stands, or -1 over a hole. */
        int heightAt(double x, double z) {
            return maze.height(tileX(x), tileZ(z));
        }

        /** Where the feet go to stand on a tile. */
        BlockPos feetOn(int[] tile) {
            return origin.offset(tile[0], maze.height(tile[0], tile[1]) + 1, tile[1]);
        }

        private int tileX(double x) {
            return Mth.floor(x) - origin.getX();
        }

        private int tileZ(double z) {
            return Mth.floor(z) - origin.getZ();
        }
    }

    /**
     * The plan a fixture word asks for: "lava-maze" has corridors one block wide, "lava-maze-2"
     * two, "lava-maze-bastion" is the bad side of a bastion seven rooms across, and
     * "lava-maze-bastion-9" the same nine rooms across.
     */
    static LavaMaze plan(String part, long seed) {
        if (part.equals("lava-maze-bastion") || part.startsWith("lava-maze-bastion-")) {
            int rooms = LavaMaze.BASTION_ROOMS;
            if (part.startsWith("lava-maze-bastion-")) {
                try {
                    rooms = Integer.parseInt(part.substring("lava-maze-bastion-".length()));
                } catch (NumberFormatException e) {
                    Constants.LOG.warn("AutoRun: '{}' is not a number of rooms, laying seven", part);
                }
            }
            return LavaMaze.bastion(rooms, seed);
        }
        int width = 1;
        if (part.startsWith("lava-maze-")) {
            try {
                width = Integer.parseInt(part.substring("lava-maze-".length()));
            } catch (NumberFormatException e) {
                Constants.LOG.warn("AutoRun: '{}' is not a lava maze, laying the flat one", part);
            }
        }
        return LavaMaze.generate(LavaMaze.Style.FLAT, width, seed);
    }

    /**
     * Lays the maze above the bot and stands it on the walkway nearest the middle. Runs on the
     * integrated server's thread.
     *
     * <p>Column by column from the bottom: a floor, a sea of lava on it, and the walkway, each tile
     * at its height on a column of its own. The flat maze's sea is a block under the walkway, so its
     * holes are drops; the bastion's stands level with the lowest floor, so a hole beside that floor
     * is lava to the brim. Walls stand on columns too, under a wall block's cap. Glass round the lot
     * holds the lava in, and above the walkway the air is cleared to the top of the glass, reading
     * before it writes, because up here it is nearly always air already.</p>
     *
     * <p>Every block goes in once, with {@link Block#UPDATE_CLIENTS} and no neighbour updates, as the
     * lake does. A lava source still asks to flow as it is placed, and at every level it fills there
     * is nothing beside it but lava, rock or the glass, so it has nowhere to go.</p>
     */
    static Placed build(MinecraftServer server, ServerPlayer player, LavaMaze maze, long seed) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        for (String command : RULES) {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        boolean bastion = maze.style() == LavaMaze.Style.BASTION;
        BlockState basin = (bastion ? Blocks.BLACKSTONE : Blocks.STONE).defaultBlockState();
        BlockState column = (bastion ? Blocks.BLACKSTONE : Blocks.STONE_BRICKS).defaultBlockState();
        BlockState wallBody = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        // A wall block on top stands half a block over its own: nothing walks onto it, and the
        // route search never counts it as somewhere to stand.
        BlockState wallCap = Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState();
        BlockState lava = Blocks.LAVA.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        // Glass above the lava, so a third-person frame shows the maze rather than a stone box.
        BlockState glass = Blocks.GLASS.defaultBlockState();
        // The top of the lava, from the walkway's level: a block under it, or level with it.
        int seaTop = bastion ? 0 : -1;

        int size = maze.size();
        int walkY = Math.min(level.getMaxY() - RIM_RISE - 2, player.getBlockY() + RISE);
        BlockPos origin = new BlockPos(player.getBlockX() - size / 2, walkY, player.getBlockZ() - size / 2);
        int low = -MARGIN - 1;
        int high = size + MARGIN;
        int cleared = 0;
        for (int x = low; x <= high; x++) {
            for (int z = low; z <= high; z++) {
                BlockPos base = origin.offset(x, 0, z);
                put(level, base.below(2), basin);
                if (x == low || x == high || z == low || z == high) {
                    put(level, base.below(), basin);
                    for (int dy = 0; dy <= RIM_RISE; dy++) {
                        put(level, base.above(dy), glass);
                    }
                    continue;
                }
                boolean walkway = maze.walkway(x, z);
                boolean wall = maze.wall(x, z);
                int top = walkway || wall ? maze.height(x, z) : -1;
                BlockState body = wall ? wallBody : column;
                BlockState cap = wall ? wallCap : floorBlock(maze, x, z, seed);
                for (int dy = -1; dy <= RIM_RISE; dy++) {
                    BlockPos at = base.above(dy);
                    if (dy >= 0 && dy < top) {
                        put(level, at, body);
                    } else if (dy >= 0 && dy == top) {
                        put(level, at, cap);
                    } else if (dy <= seaTop) {
                        put(level, at, lava);
                    } else if (!level.getBlockState(at).isAir()) {
                        put(level, at, air);
                        cleared++;
                    }
                }
            }
        }
        stranded.clear();
        lastSeenAt.clear();
        stillFor.clear();
        herded.set(0);
        Placed placed = new Placed(origin, maze, seed);
        BlockPos feet = placed.feetOn(maze.start());
        player.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
                java.util.Set.of(), 0.0F, 0.0F, true);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        Constants.LOG.info("AutoRun: fixture lava-maze - {}, {}x{} blocks, corridors {} wide, {} walkway tiles"
                        + " on {} island(s), {} gaps, {} leaps, lowest walkway at y={}, seed {}, bot at {}"
                        + " ({} blocks cleared)\n{}",
                maze.style().name().toLowerCase(Locale.ROOT), size, size, maze.width(), maze.walkwayCount(),
                maze.islandCount(), maze.gaps(), maze.leaps(), walkY, seed, feet.toShortString(), cleared,
                maze.plan());
        return placed;
    }

    private static void put(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    /**
     * What a tile's floor is made of: stone bricks on the flat maze, and on the bastion its own
     * mix - mostly polished blackstone bricks, a share of them cracked, plain and polished
     * blackstone, and the odd gilded block - the same for the same seed.
     */
    private static BlockState floorBlock(LavaMaze maze, int x, int z, long seed) {
        if (maze.style() != LavaMaze.Style.BASTION) {
            return Blocks.STONE_BRICKS.defaultBlockState();
        }
        int roll = Math.floorMod(Long.hashCode((x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ seed), 100);
        Block block = roll < 55 ? Blocks.POLISHED_BLACKSTONE_BRICKS
                : roll < 75 ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS
                : roll < 87 ? Blocks.BLACKSTONE
                : roll < 97 ? Blocks.POLISHED_BLACKSTONE
                : Blocks.GILDED_BLACKSTONE;
        return block.defaultBlockState();
    }

    /**
     * Where the keeper puts a chaser: a walkway tile on the bot's own island between {@code min}
     * and {@code max} blocks from it, or the furthest one on that island when none is and that one
     * is at least {@link #MIN_CHASER_DISTANCE} away. Null when there is nowhere - the bot in the air
     * over a hole, or on an island too small to be chased on - and the keeper asks again later.
     *
     * <p>Never anywhere else: the keeper's own search takes the first block with something under
     * it, which here is the surface of the lava, and another island is one the chaser can never
     * leave.</p>
     */
    static BlockPos chaserFeet(Placed placed, ServerPlayer player, Random random, double min, double max) {
        int home = placed.islandAt(player.getX(), player.getZ());
        if (home < 0) {
            return null;
        }
        LavaMaze maze = placed.maze();
        double x = player.getX() - placed.origin().getX();
        double z = player.getZ() - placed.origin().getZ();
        List<int[]> ring = maze.tilesBetween(x, z, min, max, home);
        if (!ring.isEmpty()) {
            return placed.feetOn(ring.get(random.nextInt(ring.size())));
        }
        int[] furthest = null;
        double best = -1.0;
        for (int[] tile : maze.tilesBetween(x, z, MIN_CHASER_DISTANCE, Double.MAX_VALUE, home)) {
            double dx = tile[0] + 0.5 - x;
            double dz = tile[1] + 0.5 - z;
            if (dx * dx + dz * dz > best) {
                best = dx * dx + dz * dz;
                furthest = tile;
            }
        }
        return furthest == null ? null : placed.feetOn(furthest);
    }

    /**
     * Lets a chaser find the long way round. A Zombie searches its follow range's worth for a way to
     * its target - thirty-five blocks, which is 560 steps of search - and settles for the nearest
     * point it reached when that runs out. On a nine-room bastion (2026-10-18) three of them stood
     * behind a wall a dozen blocks from the bot for a minute, the walk round being longer than
     * their search, and the bot never had anything to run from.
     */
    static void lengthenSearch(Mob mob) {
        AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range != null && range.getBaseValue() < CHASER_FOLLOW_RANGE) {
            range.setBaseValue(CHASER_FOLLOW_RANGE);
        }
    }

    /**
     * Moves a chaser to the bot's island, where the keeper would have put a new one, when it has
     * stood on another island for {@link #STRANDED_TICKS}, or on the bot's own without moving a
     * block for {@link #STUCK_TICKS} while out of reach of it - stuck behind a wall, its search for
     * a way round spent. Called by the keeper every {@code interval} ticks with the chasers it is
     * keeping; server thread.
     *
     * <p>Moved rather than replaced, so the examiner on the client sees a mob go somewhere else
     * rather than one vanish, which it would count as a chaser killed.</p>
     */
    static void herd(Placed placed, ServerPlayer player, List<Mob> chasers, Random random, int interval,
                     double min, double max) {
        int home = placed.islandAt(player.getX(), player.getZ());
        if (home < 0) {
            return;
        }
        for (Mob mob : chasers) {
            lengthenSearch(mob);
            int island = placed.islandAt(mob.getX(), mob.getZ());
            if (island < 0) {
                stranded.remove(mob.getId());
                continue;
            }
            Vec3 before = lastSeenAt.put(mob.getId(), mob.position());
            int still = before != null && before.distanceToSqr(mob.position()) < 1.0
                    ? stillFor.merge(mob.getId(), interval, Integer::sum) : 0;
            if (still == 0) {
                stillFor.remove(mob.getId());
            }
            boolean stuck = island == home && still >= STUCK_TICKS && mob.distanceTo(player) > STUCK_REACH;
            if (island == home && !stuck) {
                stranded.remove(mob.getId());
                continue;
            }
            if (!stuck && stranded.merge(mob.getId(), interval, Integer::sum) < STRANDED_TICKS) {
                continue;
            }
            BlockPos feet = chaserFeet(placed, player, random, min, max);
            if (feet == null) {
                continue;
            }
            stranded.remove(mob.getId());
            stillFor.remove(mob.getId());
            lastSeenAt.remove(mob.getId());
            mob.getNavigation().stop();
            mob.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            mob.setDeltaMovement(Vec3.ZERO);
            mob.fallDistance = 0.0F;
            herded.incrementAndGet();
            Constants.LOG.info("AutoRun: lava maze - {} {} moved to the bot's island {}, at {}", kind(mob),
                    stuck ? "stuck for " + still / 20 + "s" : "stranded on island " + island, home,
                    feet.toShortString());
        }
    }

    /** The shortest name a reader needs: Self Preservation's own lines lose their prefix. */
    static String shortKey(StatusText line) {
        if (line == null || line.isBlank()) {
            return "-";
        }
        // A retreat along a route reads "<what> - <the route's own line>", with the first half
        // already in words. The route's key is the one there is to keep.
        if ("lune.status.detail".equals(line.key())) {
            Object[] args = line.args();
            if (args.length > 0 && args[args.length - 1] instanceof StatusText inner && !inner.isBlank()) {
                return "detail>" + trim(inner.key());
            }
        }
        return trim(line.key());
    }

    private static String trim(String key) {
        for (String prefix : new String[] {"lune.status.self_preservation.", "lune.status."}) {
            if (key.startsWith(prefix)) {
                return key.substring(prefix.length());
            }
        }
        return key;
    }

    /** What hurt the player, as the game names the damage, and who did it when it was someone. */
    static String describe(DamageSource source) {
        if (source == null) {
            return "-";
        }
        Entity attacker = source.getEntity();
        return attacker == null ? source.getMsgId() : source.getMsgId() + "(" + kind(attacker) + ")";
    }

    private static String kind(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * The examiner. Reads the bot from the client tick and keeps the run's account.
     *
     * <p>A chase starts when a hostile comes within {@link #ENCOUNTER_RADIUS} and ends when none has
     * been that near for {@link #ENCOUNTER_QUIET_TICKS}. It ends one of four ways: the bot fell,
     * its chaser died (punched into the lava, as often as not), the bot was killed, or it got away -
     * the escape the test is asking about. A fall is feet that have dropped under the lowest walkway,
     * or any touch of lava; the verdict is the worst that happened, so one fall makes the run a fall
     * whatever else went well.</p>
     *
     * <p>A leap is a time in the air over a hole that ends on the walkway: a jump across a gap, or a
     * hit that carried the bot over one and set it down on the far side. A crossing is a landing on
     * another island than the last one stood on, which nothing but a leap gets.</p>
     *
     * <p>Client thread only, apart from {@link Placed}, which the server writes once.</p>
     */
    static final class Check {
        private Placed placed;
        private int ticks;
        private float lastHealth = -1.0F;
        private float minHealth = Float.MAX_VALUE;
        private int hits;
        private double damage;
        private int lastHitTick = Integer.MIN_VALUE;
        private String lastHitBy = "-";
        /** Ticks Self Preservation had the controls. */
        private int guardTicks;
        private int guardFails;
        private boolean guardFailing;
        private String lastGuardFail = "-";
        /** What the card was doing on the last tick the feet were on the walkway, and when that was. */
        private String groundGuard = "-";
        private int groundTick;
        private int groundHeight = -1;
        /** Ticks stood with the middle of the body over a hole, held up by the edge of a tile. */
        private int edgeTicks;
        private double closest = Double.MAX_VALUE;

        private boolean overHole;
        private int leaps;
        private int island = -1;
        private int crossings;

        private boolean falling;
        private int falls;
        private boolean climbedOut;
        private String firstFall = "-";

        private boolean chasing;
        private int quietTicks;
        private boolean chaseFell;
        private boolean chaseKilled;
        private int encounters;
        private int escaped;
        private int won;
        private int fellChases;
        private int caught;

        /** Hostiles in range, by id, and whether each was in lava when last seen. */
        private final Map<Integer, Boolean> tracked = new HashMap<>();
        private int kills;
        private int lavaKills;
        private boolean reported;

        void watch(Minecraft mc, BotEngine engine, Placed maze, int tick) {
            LocalPlayer player = mc.player;
            if (maze == null || player == null || mc.level == null || reported) {
                return;
            }
            placed = maze;
            ticks = tick;

            String guard = null;
            if (engine.getCurrent() instanceof TaskRunner runner) {
                StatusText answering = runner.answeringGuardLine();
                if (answering != null) {
                    guard = shortKey(answering);
                    guardTicks++;
                }
                // The card failing on the Countdown's While pin; the task wires that back round, so
                // the run goes on with a fresh card and this is the only trace of it.
                StatusText line = runner.statusLine();
                boolean failed = "lune.status.task_runner.while_failed".equals(line.key());
                if (failed && !guardFailing) {
                    guardFails++;
                    Object[] args = line.args();
                    lastGuardFail = args.length > 0 && args[0] instanceof StatusText inner
                            ? shortKey(inner) : "-";
                }
                guardFailing = failed;
            }

            float health = player.getHealth();
            if (lastHealth >= 0.0F && health < lastHealth - 0.01F) {
                hits++;
                damage += lastHealth - health;
                lastHitTick = tick;
                lastHitBy = describe(player.getLastDamageSource());
            }
            lastHealth = health;
            minHealth = Math.min(minHealth, health);

            double nearest = Double.MAX_VALUE;
            List<Mob> hostiles = mc.level.getEntitiesOfClass(Mob.class,
                    player.getBoundingBox().inflate(SCAN_RADIUS), mob -> mob.isAlive() && mob instanceof Enemy);
            for (Mob mob : hostiles) {
                nearest = Math.min(nearest, player.distanceTo(mob));
                tracked.put(mob.getId(), mob.isInLava());
            }
            closest = Math.min(closest, nearest);
            for (Iterator<Map.Entry<Integer, Boolean>> it = tracked.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Boolean> entry = it.next();
                Entity mob = mc.level.getEntity(entry.getKey());
                if (mob != null && mob.isAlive()) {
                    continue;
                }
                it.remove();
                kills++;
                if (entry.getValue() || (mob != null && mob.isInLava())) {
                    lavaKills++;
                }
                if (chasing) {
                    chaseKilled = true;
                }
            }

            boolean inLava = player.isInLava();
            boolean below = player.getY() < maze.walkTop() - FALL_DEPTH;
            boolean standing = player.onGround() && !below && !inLava;
            if (standing) {
                groundGuard = guard == null ? "-" : guard;
                groundTick = tick;
                if (falling) {
                    falling = false;
                    climbedOut = true;
                }
                int under = maze.islandAt(player.getX(), player.getZ());
                if (under < 0) {
                    // Held up by the edge of a tile with the middle of the body over the hole. A
                    // leap that came down like this is counted once the body is over the tile.
                    edgeTicks++;
                } else {
                    if (overHole) {
                        leaps++;
                        overHole = false;
                    }
                    if (island >= 0 && under != island) {
                        crossings++;
                    }
                    island = under;
                    groundHeight = maze.heightAt(player.getX(), player.getZ());
                }
            } else if (!below && !inLava && !maze.walkwayUnder(player.getX(), player.getZ())) {
                overHole = true;
            }
            if (!falling && (inLava || below)) {
                falling = true;
                overHole = false;
                falls++;
                if (chasing) {
                    chaseFell = true;
                }
                if (falls == 1) {
                    firstFall = describeFall(player, tick, nearest, inLava);
                    Constants.LOG.info("AutoRun: lava maze - the bot fell: {}", firstFall);
                    Screenshots.save(mc, String.format(Locale.ROOT, "lava-maze-%d-fall-%05d.png",
                            maze.seed(), tick));
                }
            }

            // A chaser walking up to the edge of the lava the bot is burning in is not a new chase;
            // the one that ended in the lava is still the story.
            boolean near = nearest <= ENCOUNTER_RADIUS;
            if (!chasing && near && !falling) {
                chasing = true;
                encounters++;
                quietTicks = 0;
                chaseFell = false;
                chaseKilled = false;
            } else if (chasing) {
                quietTicks = near ? 0 : quietTicks + 1;
                if (quietTicks >= ENCOUNTER_QUIET_TICKS) {
                    endChase(false);
                }
            }
        }

        private void endChase(boolean died) {
            chasing = false;
            if (chaseFell) {
                fellChases++;
            } else if (died) {
                caught++;
            } else if (chaseKilled) {
                won++;
            } else {
                escaped++;
            }
        }

        /**
         * What killed the bot, as the server has it. The client's last damage source is the last
         * damage it was told of, and the blow that kills can come with the death and never be told:
         * on 2026-10-18 a Creeper's blast took the last seventeen health in one tick, the server
         * said "blown up by Creeper", and this line said the Zombie whose hit came nine ticks before.
         * The integrated server's own player keeps the blow; the client's is the fallback, for a run
         * with no server of its own to ask.
         */
        private static String killedBy(Minecraft mc, LocalPlayer player) {
            MinecraftServer server = mc.getSingleplayerServer();
            ServerPlayer onServer = server == null ? null : server.getPlayerList().getPlayer(player.getUUID());
            DamageSource source = onServer == null ? null : onServer.getLastDamageSource();
            return describe(source != null ? source : player.getLastDamageSource());
        }

        private String describeFall(LocalPlayer player, int tick, double nearest, boolean inLava) {
            Vec3 motion = player.getDeltaMovement();
            return tick + "@" + round(player.getX()) + "," + round(player.getY()) + "," + round(player.getZ())
                    + ";into=" + (inLava ? "lava" : "hole")
                    + ";guard=" + groundGuard
                    + ";from=h" + groundHeight
                    + ";airborne=" + (tick - groundTick)
                    + ";hit=" + (lastHitTick == Integer.MIN_VALUE ? "-" : (tick - lastHitTick) + ":" + lastHitBy)
                    + ";mob=" + (nearest == Double.MAX_VALUE ? "-" : round(nearest))
                    + ";sprint=" + (player.isSprinting() ? "yes" : "no")
                    + ";speed=" + String.format(Locale.ROOT, "%.2f", Math.hypot(motion.x, motion.z));
        }

        /**
         * Writes the run's one line, once. The verdict is the worst thing that happened: a fall,
         * then being killed, then - when no chaser ever came near - untested, and otherwise escaped.
         */
        void conclude(Minecraft mc, long worldSeed, String mobs, String reason) {
            if (reported) {
                return;
            }
            reported = true;
            LocalPlayer player = mc.player;
            boolean died = player != null && !player.isAlive();
            if (chasing) {
                // Dead without having climbed out of a fall: the lava, not the chaser.
                chaseFell |= falling;
                endChase(died);
            } else if (died && falls == 0) {
                caught++;
            }
            String killer = died ? killedBy(mc, player) : "-";
            if (died && lastHealth > 0.0F) {
                // The blow that killed is a hit too, and the run ends before a tick can count it.
                hits++;
                damage += lastHealth;
                minHealth = 0.0F;
                lastHitTick = ticks;
                lastHitBy = killer;
            }
            String death = died ? ticks + ":" + killer : "-";
            String verdict = falls > 0 ? "fell"
                    : died ? "caught"
                    : encounters == 0 ? "untested"
                    : "escaped";
            Placed maze = placed;
            String line = "seed=" + worldSeed
                    + " maze=" + (maze == null ? "-" : Long.toString(maze.seed()))
                    + " style=" + (maze == null ? "-" : maze.maze().style().name().toLowerCase(Locale.ROOT))
                    + " width=" + (maze == null ? "-" : Integer.toString(maze.maze().width()))
                    + " size=" + (maze == null ? "-" : Integer.toString(maze.maze().size()))
                    + " islands=" + (maze == null ? "-" : Integer.toString(maze.maze().islandCount()))
                    + " mobs=" + (mobs.isEmpty() ? "-" : mobs)
                    + " ticks=" + ticks
                    + " verdict=" + verdict
                    + " encounters=" + encounters
                    + " escaped=" + escaped
                    + " won=" + won
                    + " fellchases=" + fellChases
                    + " caught=" + caught
                    + " falls=" + falls
                    + " climbed=" + (climbedOut ? "yes" : "no")
                    + " leaps=" + leaps
                    + " crossings=" + crossings
                    + " herded=" + herded.get()
                    + " hits=" + hits
                    + " damage=" + round(damage)
                    + " minhealth=" + (minHealth == Float.MAX_VALUE ? "-" : round(minHealth))
                    + " closest=" + (closest == Double.MAX_VALUE ? "-" : round(closest))
                    + " edge=" + edgeTicks
                    + " guardticks=" + guardTicks
                    + " guardfails=" + guardFails
                    + " guardfail=" + lastGuardFail
                    + " kills=" + kills
                    + " lavakills=" + lavaKills
                    + " fall=" + firstFall
                    + " death=" + death
                    + " reason=" + reason.replace(' ', '_');
            Constants.LOG.info("AutoRun: lava maze - {}", line);
            try {
                Path directory = Services.PLATFORM.getConfigDir();
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(CHECKS_FILE), line + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                Constants.LOG.warn("AutoRun: could not write the lava maze check", e);
            }
        }
    }
}
