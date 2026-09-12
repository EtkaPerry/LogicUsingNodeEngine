package com.etka.lune.bot;

import com.etka.lune.util.Lang;
import com.etka.lune.Constants;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.platform.BuildFeatures;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskStore;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Unattended test harness: join a world, start a task, stop on a budget, quit.
 *
 * <p>Every long run so far has been started by hand, which quietly shaped what was measurable. A
 * ten-minute route that needs a human to press three keys first cannot be run twice on the same
 * seed, cannot be run overnight, and cannot be compared before and after a change - so the only
 * evidence a fix ever got was one journal from one run someone happened to sit through.</p>
 *
 * <p>Configured entirely through system properties so it leaves no trace in the user's saved
 * config and cannot fire by accident:</p>
 *
 * <pre>
 * -Dlune.autorun.task=Speedrun      the task to start once the world is loaded
 * -Dlune.autorun.createWorld=name      generate a brand new world with a random seed and join it
 * -Dlune.autorun.delayTicks=100        settling time after join, for chunks and the server
 * -Dlune.autorun.fixture=fishing        prepare a rod, water pool, and view for fishing tests
 * -Dlune.autorun.fixture=fishing-small  prepare a rod and a two-block water target
 * -Dlune.autorun.fixture=fishing-one    prepare a rod and a one-block water target
 * -Dlune.autorun.fixture=ghast          a platform twenty blocks up with one Ghast shelling it
 * -Dlune.autorun.fixture=ghast-3        the same arena, kept stocked with three
 * -Dlune.autorun.fixture=food,tools     comma-separated; see {@link #prepareFixture}
 * -Dlune.autorun.stopAfterTicks=12000  hard budget; 0 means run until the task ends
 * -Dlune.autorun.quit=true             close the client afterwards, so a shell run terminates
 * </pre>
 *
 * <p>Each run generates its own world on purpose. Repeating one saved world makes a suite that
 * measures a single spawn rather than the bot: two runs in a row were lost to the same barren,
 * frozen shoreline, which says nothing about whether a change helped. A new seed every time turns
 * a run into a sample, and a bad spawn into one data point instead of the permanent testbed.</p>
 */
public final class AutoRun {

    private static final String TASK_KEY = "lune.autorun.task";
    /**
     * The key before tasks stopped being called routines. A literal on purpose: it names a
     * property in run scripts that already exist, so it must not follow the code.
     */
    private static final String LEGACY_TASK_KEY = "lune.autorun.routine";
    private static final String WORLD_KEY = "lune.autorun.createWorld";
    private static final String SEED_KEY = "lune.autorun.seed";
    private static final String DELAY_KEY = "lune.autorun.delayTicks";
    private static final String FIXTURE_KEY = "lune.autorun.fixture";
    private static final String BUDGET_KEY = "lune.autorun.stopAfterTicks";
    private static final String QUIT_KEY = "lune.autorun.quit";
    /** Journal a human playing, with no task and no bot input. See {@link #isRecordingSession}. */
    private static final String RECORD_KEY = "lune.autorun.record";
    /** Hold the learner still, so a benchmark measures the code and not the bandit. */
    private static final String FREEZE_LEARNING_KEY = "lune.autorun.freezeLearning";

    /** Long enough for the integrated server to hand over the chunks around the spawn. */
    private static final int DEFAULT_DELAY_TICKS = 100;
    /** Ticks to let the title screen finish loading before asking it to build a world. */
    private static final int TITLE_SETTLE_TICKS = 40;

    /**
     * How far above the terrain the Ghast arena floor sits, in blocks.
     *
     * <p>Above it, not in it. A Ghast needs an unbroken line to the player before its shoot goal will
     * even start, so on ordinary ground every hill in between is a fireball that never happens.
     */
    private static final int GHAST_PLATFORM_RISE = 20;
    /** Air kept above the middle of the floor, so the bot has sky over it and room to jump. */
    private static final int GHAST_HEADROOM = 20;
    /** Half-width of the sidestepping room in the middle, where the full headroom is worth having. */
    private static final int GHAST_CENTRE_RADIUS = 14;
    /**
     * Height of the band cleared out to the Ghasts, above the floor.
     *
     * <p>Two cleared volumes rather than one, because the cost is not symmetrical. Over the middle the
     * full headroom is worth having and it is only a radius of fourteen. Out where the Ghasts hover
     * the only thing needed is a corridor the shots can cross - a Ghast is four blocks tall and is
     * put no higher than three above the floor - and clearing twenty blocks of it instead of eight,
     * across six times the area, is the difference between a hitch and a freeze on a mountain seed.
     */
    private static final int GHAST_FLIGHT_BAND = 8;
    /**
     * How far out a Ghast is put, in blocks.
     *
     * <p>The flight is the thing being watched. A fireball leaves at a tenth of a block per tick and
     * tops out at 1.9, so from twenty-odd blocks it takes over a second to arrive - long enough to
     * see the head come round, the bot hold still, and the shot go back.
     */
    private static final int GHAST_RING_MIN = 18;
    private static final int GHAST_RING_MAX = 28;
    /**
     * Half-width of the arena floor: past the ring the Ghasts fly on, not just the bit underfoot.
     *
     * <p>Not generosity. It is the only thing that keeps them level with the bot. Vanilla's
     * float-around goal looks up the terrain height under wherever it fancies going, and when that
     * height is below the position it picked it <em>mirrors the move downward</em> instead - so a
     * Ghast in open air is steered at the ground every single time it chooses somewhere to be.
     *
     * <p>With nothing under them they sank past the platform within a minute, dropped out of the
     * four-block window a Ghast needs to hold a target, and went quiet - while the keeper cheerfully
     * replaced Ghasts that had not died. Eighteen of them in one measured two-minute run, against two
     * kills. Floor under the whole ring turns the same rule into a box they settle in: down is still
     * where they are steered, and down is now three blocks away.
     */
    private static final int GHAST_FLOOR_RADIUS = GHAST_RING_MAX + 6;
    /** Places tried for one Ghast before giving up and leaving it to the next keeper tick. */
    private static final int GHAST_SPAWN_ATTEMPTS = 16;
    /** Ticks between arena checks. A Ghast spends sixty winding up, so this is not a race. */
    private static final int GHAST_KEEPER_INTERVAL = 20;
    /** How far from the middle a Ghast still counts as one of the arena's, in blocks. */
    private static final double GHAST_ARENA_RADIUS = 48.0;
    /** More than this in the air at once stops being a test and starts being a fireworks display. */
    private static final int MAX_ARENA_GHASTS = 8;

    private static final String taskName = System.getProperty(TASK_KEY,
            System.getProperty(LEGACY_TASK_KEY, "")).trim();
    private static final String worldName = System.getProperty(WORLD_KEY, "").trim();
    private static final int delayTicks = intProperty(DELAY_KEY, DEFAULT_DELAY_TICKS);
    private static final int budgetTicks = intProperty(BUDGET_KEY, 0);
    private static final boolean quitWhenDone = Boolean.getBoolean(QUIT_KEY);
    private static final boolean recordSession = Boolean.getBoolean(RECORD_KEY);
    private static final boolean freezeLearning = Boolean.getBoolean(FREEZE_LEARNING_KEY);
    private static final String fixture = System.getProperty(FIXTURE_KEY, "").trim().toLowerCase();

    /** Seed of the world this harness generated, so the journal can record how to repeat the run. */
    private static long worldSeed;

    private static Object lastLevel;
    private static int joinTicks;
    private static int runTicks;
    private static int titleTicks;
    private static boolean worldRequested;
    private static boolean started;
    private static boolean finished;
    private static boolean fixturePrepared;

    /**
     * Middle of the Ghast arena floor, or null when this run has not built one.
     *
     * <p>Volatile because the two halves of the arena live on different threads: the build runs on
     * the integrated server and the keeper is driven from the client tick.
     */
    private static volatile BlockPos ghastArena;
    private static volatile int ghastsWanted;
    private static int ghastKeeperTicks;
    /** Server-thread only, inside the keeper. */
    private static final java.util.Random ghastRandom = new java.util.Random();

    private AutoRun() {}

    /** True when the client was launched as a test run rather than by a player. */
    public static boolean isConfigured() {
        return !BuildFeatures.releaseBuild() && !taskName.isEmpty();
    }

    /**
     * Whether this session is a human being recorded rather than a bot being driven.
     * <p>
     * The bot's own numbers only mean something next to a person's, and until now there was no way
     * to get a person's in the same units. Twelve measured runs say the bot gathers wood at four
     * point eight logs a minute; nobody knows what that is a fraction of. So this opens the ordinary
     * run journal, starts no task, and lets the player play: the motion ledger, the inventory and
     * the position trail are recorded exactly as they are for a run, and the same analysis reads
     * both. Nothing here touches the controls - {@code BotEngine.isDriving()} is false without a
     * task, so the input hook passes the player's own keys straight through.
     */
    public static boolean isRecordingSession() {
        return !BuildFeatures.releaseBuild() && recordSession;
    }

    /**
     * Whether the harness should build a world: for a bot run, or for a recorded human session.
     * <p>
     * World creation used to be gated on there being a task, which meant the only way to get a
     * seeded world was to hand it to the bot. Recording a person on the same seed the bot ran is
     * the whole point of the comparison, so the two have to be separable.
     */
    private static boolean wantsWorld() {
        return !worldName.isEmpty() && (isConfigured() || isRecordingSession());
    }

    /** Seed of the generated world, or 0 when this session did not generate one. */
    public static long worldSeed() {
        return worldSeed;
    }

    /**
     * Runs before there is a world, which is where a fresh one gets built.
     * <p>
     * Called from every client tick rather than from the engine's, because the engine deliberately
     * does nothing without a player - and at the title screen there isn't one yet.
     */
    public static void beforeWorld(Minecraft mc) {
        if (!wantsWorld() || finished || worldRequested) {
            return;
        }
        if (mc.level != null) {
            // Already in a world; nothing to create.
            worldRequested = true;
            return;
        }
        if (++titleTicks < TITLE_SETTLE_TICKS) {
            return;
        }
        worldRequested = true;
        try {
            LevelSettings settings = new LevelSettings(worldName, GameType.SURVIVAL,
                    LevelSettings.DifficultySettings.DEFAULT, false, WorldDataConfiguration.DEFAULT);
            // A named seed makes a run repeatable, which is the only way to tell a fix from luck:
            // the same terrain, the same spawn, the same village or frozen river, before and after.
            String requested = System.getProperty(SEED_KEY, "").trim();
            WorldOptions options;
            if (requested.isEmpty()) {
                options = WorldOptions.defaultWithRandomSeed();
            } else {
                options = new WorldOptions(Long.parseLong(requested), true, false);
            }
            worldSeed = options.seed();
            Constants.LOG.info("AutoRun: creating world '{}' with seed {}", worldName, worldSeed);
            mc.createWorldOpenFlows().createFreshLevel(worldName, settings, options,
                    WorldPresets::createNormalWorldDimensions, mc.screen);
        } catch (RuntimeException e) {
            Constants.LOG.error("AutoRun: could not create world '{}'", worldName, e);
            if (quitWhenDone) {
                mc.schedule(mc::stop);
            }
        }
    }

    /**
     * Ends the run when something outside the task ended it: the player died, or the world went
     * away underneath them.
     *
     * <p>{@link #tick} only runs with a live player, which is exactly the state a dead one is not
     * in. Without this the client sits on the death screen for the rest of the night: the budget
     * never advances because nothing is ticking, {@code quit} never fires, and the batch lane is
     * held by a corpse. Three of six lanes spent seventy minutes that way before this existed, and
     * the batch quietly ran at half its stated width.</p>
     */
    public static void runInterrupted(Minecraft mc, String reason) {
        if (!isConfigured() || finished || !started) {
            return;
        }
        finished = true;
        Constants.LOG.info("AutoRun: {} after {} ticks", reason, runTicks);
        if (!quitWhenDone) {
            return;
        }
        Constants.LOG.info("AutoRun: closing the client");
        mc.schedule(mc::stop);
    }

    /** Drives the harness. Called once per engine tick with a live player and level. */
    public static void tick(Minecraft mc, BotEngine engine) {
        if (!isConfigured() || finished) {
            return;
        }
        if (freezeLearning) {
            // A benchmark has to be able to attribute a difference to the change being tested. The
            // learner persists to disk and updates every run, so without this the bot that runs
            // after a fix is not the same bot that ran before it, and a measured improvement may be
            // the bandit drifting rather than the code getting better. Twelve runs were measured
            // this way before anyone noticed. Held every tick because the config screen and the
            // learner itself can both write it back.
            com.etka.lune.config.BotConfig config = com.etka.lune.config.BotConfig.get();
            config.learningEnabled = false;
            config.userLearningEnabled = false;
        }

        if (mc.level != lastLevel) {
            // A fresh world, including one loaded after a previous run finished.
            lastLevel = mc.level;
            joinTicks = 0;
            runTicks = 0;
            started = false;
            fixturePrepared = false;
            ghastArena = null;
        }

        maintainGhastArena(mc);

        if (!started) {
            if (!fixturePrepared) {
                prepareFixture(mc);
                fixturePrepared = true;
            }
            if (++joinTicks < delayTicks) {
                return;
            }
            start(mc, engine);
            return;
        }

        runTicks++;
        if (budgetTicks > 0 && runTicks >= budgetTicks) {
            finish(mc, engine, "autorun budget reached after " + runTicks + " ticks");
            return;
        }
        if (engine.isIdle()) {
            finish(mc, engine, "autorun task finished after " + runTicks + " ticks");
        }
    }

    private static void start(Minecraft mc, BotEngine engine) {
        started = true;
        Optional<TaskGraph> task = TaskStore.get().byName(taskName);
        if (task.isEmpty()) {
            Constants.LOG.error("AutoRun: no task named '{}'. Known tasks: {}",
                    taskName, TaskStore.get().names());
            finish(mc, engine, "autorun could not find task " + taskName);
            return;
        }
        Constants.LOG.info("AutoRun: starting task '{}' (budget {} ticks)",
                taskName, budgetTicks);
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(Lang.get("lune.gui.auto_run.lune_autorun", taskName)));
        }
        engine.runNow(new TaskRunner(task.get()));
    }

    /**
     * Builds the explicit test fixtures requested by the harness. Normal autoruns remain
     * untouched: a fishing task still correctly fails when the player has no rod.
     *
     * <p>Fixtures compose, comma-separated, because a coverage batch needs to combine them:</p>
     *
     * <pre>
     * food     a stack of cooked beef, so a fifteen-minute run is not cut short by the hunger
     *          safety stop after two. What the run is measuring is the job, not whether that
     *          spawn happened to have a cow.
     * tools    an iron kit and a crafting table, for the jobs that otherwise spend their whole
     *          budget getting to the point where they can begin.
     * chest    a chest beside the spawn, so Deposit has somewhere to deposit.
     * furnace  a furnace, coal and raw iron, so Smelt has something to smelt.
     * drops    a scatter of dropped items, so Loot has something to pick up.
     * farm     a tilled field of mature crops, so Harvest has a field.
     * gap      a chasm across the bot's path, so Bridge has something to cross.
     * obsidian obsidian, corner blocks and a lighter, so Build Nether Portal can build.
     * fishing[-small|-one]  a rod and a pool, in three target sizes.
     * ghast[-N]             a platform twenty blocks up, kept stocked with N Ghasts (default 1).
     * </pre>
     *
     * <p>None of this is available outside the harness: the properties are set by the run scripts
     * and leave nothing in the user's config.</p>
     *
     * <p>Everything here runs on the integrated server's own thread. The harness ticks on the
     * render thread, and giving a {@code ServerPlayer} items from there means the server is
     * broadcasting that inventory change - and walking the advancement listeners for it - while
     * another thread is still adding stacks. That is a {@code ConcurrentModificationException} deep
     * inside vanilla's criterion triggers, and it took a run down within thirty seconds of
     * handing the bot a seven-item kit.</p>
     */
    private static void prepareFixture(Minecraft mc) {
        if (fixture.isEmpty() || mc.player == null || mc.getSingleplayerServer() == null) {
            return;
        }
        java.util.Set<String> parts = new java.util.LinkedHashSet<>();
        for (String part : fixture.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        java.util.UUID playerId = mc.player.getUUID();
        mc.getSingleplayerServer().execute(() -> applyFixture(mc, playerId, parts));
    }

    private static void applyFixture(Minecraft mc, java.util.UUID playerId,
                                     java.util.Set<String> parts) {
        if (mc.getSingleplayerServer() == null) {
            return;
        }
        ServerPlayer serverPlayer = mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);
        if (serverPlayer == null) {
            Constants.LOG.warn("AutoRun: no server player for the fixture");
            return;
        }
        boolean anyFishing = parts.stream().anyMatch(part -> part.startsWith("fishing"));
        if (anyFishing) {
            prepareFishing(mc, serverPlayer, parts);
        }
        if (parts.stream().anyMatch(part -> part.startsWith("ghast"))) {
            prepareGhastArena(mc, serverPlayer, parts);
        }
        for (String part : parts) {
            switch (part) {
                case "food" -> giveFood(serverPlayer);
                case "tools" -> giveTools(serverPlayer);
                case "chest" -> placeChest(serverPlayer);
                case "furnace" -> placeFurnace(serverPlayer);
                case "drops" -> scatterDrops(serverPlayer);
                case "farm" -> plantFarm(serverPlayer);
                case "gap" -> digGap(serverPlayer);
                case "obsidian" -> giveObsidian(serverPlayer);
                default -> {
                    if (!part.startsWith("fishing") && !part.startsWith("ghast")) {
                        Constants.LOG.warn("AutoRun: unknown fixture '{}'", part);
                    }
                }
            }
        }
        serverPlayer.inventoryMenu.broadcastChanges();
    }

    /** Puts a stack in the first empty slot, so composed fixtures do not overwrite each other. */
    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            Constants.LOG.warn("AutoRun: no room for fixture item {}", stack);
        }
    }

    private static void giveFood(ServerPlayer player) {
        give(player, new ItemStack(Items.COOKED_BEEF, 64));
        Constants.LOG.info("AutoRun: fixture food");
    }

    private static void giveTools(ServerPlayer player) {
        give(player, new ItemStack(Items.IRON_PICKAXE));
        give(player, new ItemStack(Items.IRON_AXE));
        give(player, new ItemStack(Items.IRON_SHOVEL));
        give(player, new ItemStack(Items.IRON_SWORD));
        give(player, new ItemStack(Items.CRAFTING_TABLE, 1));
        give(player, new ItemStack(Items.TORCH, 64));
        give(player, new ItemStack(Items.COBBLESTONE, 64));
        Constants.LOG.info("AutoRun: fixture tools");
    }

    /**
     * Enough obsidian for the largest frame, plus the corners and the light.
     *
     * <p>Portal building is one of the richer learned jobs - three frame orders, two frame
     * shapes - and it is unreachable from a survival spawn inside a run budget: obsidian means
     * diamonds or a lava cast first. Handing it over is the only way that policy gets sampled at
     * all, and the thing being measured is the build order, not the mining that paid for it.</p>
     */
    private static void giveObsidian(ServerPlayer player) {
        give(player, new ItemStack(Items.OBSIDIAN, 24));
        give(player, new ItemStack(Items.FLINT_AND_STEEL));
        give(player, new ItemStack(Items.DIRT, 16));
        Constants.LOG.info("AutoRun: fixture obsidian");
    }

    private static void placeChest(ServerPlayer player) {
        BlockPos chest = groundBeside(player, 2, 0);
        player.level().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        Constants.LOG.info("AutoRun: fixture chest at {}", chest);
    }

    private static void placeFurnace(ServerPlayer player) {
        BlockPos furnace = groundBeside(player, -2, 0);
        player.level().setBlockAndUpdate(furnace, Blocks.FURNACE.defaultBlockState());
        give(player, new ItemStack(Items.RAW_IRON, 32));
        give(player, new ItemStack(Items.COAL, 32));
        Constants.LOG.info("AutoRun: fixture furnace at {}", furnace);
    }

    private static void scatterDrops(ServerPlayer player) {
        // Spread wide enough that Loot has to travel between them; a pile at the bot's feet
        // measures nothing but the pickup box.
        Item[] kinds = { Items.OAK_LOG, Items.COBBLESTONE, Items.RAW_IRON, Items.APPLE,
                Items.BONE, Items.STICK };
        int dropped = 0;
        for (int i = 0; i < 18; i++) {
            double angle = i * (Math.PI * 2 / 18.0);
            double distance = 4.0 + (i % 4) * 3.0;
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            BlockPos ground = surfaceAt(player, (int) Math.round(x), (int) Math.round(z));
            ItemEntity item = new ItemEntity(player.level(), ground.getX() + 0.5,
                    ground.getY() + 0.5, ground.getZ() + 0.5,
                    new ItemStack(kinds[i % kinds.length], 3));
            item.setDeltaMovement(0, 0, 0);
            player.level().addFreshEntity(item);
            dropped++;
        }
        Constants.LOG.info("AutoRun: fixture drops x{}", dropped);
    }

    private static void plantFarm(ServerPlayer player) {
        var level = player.level();
        Block[] crops = { Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS };
        int planted = 0;
        for (int x = -6; x <= 6; x++) {
            for (int z = 3; z <= 12; z++) {
                BlockPos soil = surfaceAt(player, player.getBlockX() + x, player.getBlockZ() + z);
                level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
                Block crop = crops[Math.floorMod(x + z, crops.length)];
                BlockState mature = crop.defaultBlockState();
                for (var property : mature.getProperties()) {
                    // Every vanilla crop stores ripeness in an integer "age"; planting them at
                    // zero would give Harvest a field it correctly refuses to touch.
                    if (property instanceof IntegerProperty age && "age".equals(age.getName())) {
                        mature = mature.setValue(age, java.util.Collections.max(age.getPossibleValues()));
                    }
                }
                level.setBlockAndUpdate(soil.above(), mature);
                planted++;
            }
        }
        Constants.LOG.info("AutoRun: fixture farm, {} mature crops", planted);
    }

    private static void digGap(ServerPlayer player) {
        var level = player.level();
        BlockPos feet = player.blockPosition();
        for (int forward = 6; forward <= 20; forward++) {
            for (int side = -6; side <= 6; side++) {
                for (int depth = 0; depth < 12; depth++) {
                    level.setBlockAndUpdate(feet.offset(side, -depth, forward),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        Constants.LOG.info("AutoRun: fixture gap ahead of {}", feet);
    }

    /** A solid block the bot can stand next to, at the surface beside the spawn. */
    private static BlockPos groundBeside(ServerPlayer player, int dx, int dz) {
        return surfaceAt(player, player.getBlockX() + dx, player.getBlockZ() + dz);
    }

    /** The first block at or below the player's height that has something solid under it. */
    private static BlockPos surfaceAt(ServerPlayer player, int x, int z) {
        var level = player.level();
        int top = Math.min(level.getMaxY() - 1, player.getBlockY() + 4);
        int bottom = Math.max(level.getMinY() + 1, player.getBlockY() - 8);
        for (int y = top; y > bottom; y--) {
            BlockPos candidate = new BlockPos(x, y, z);
            if (level.getBlockState(candidate).isAir()
                    && !level.getBlockState(candidate.below()).isAir()) {
                return candidate;
            }
        }
        return new BlockPos(x, player.getBlockY(), z);
    }

    private static void prepareFishing(Minecraft mc, ServerPlayer serverPlayer,
                                       java.util.Set<String> parts) {
        boolean oneBlockFishingTarget = parts.contains("fishing-one");
        boolean smallFishingTarget = oneBlockFishingTarget || parts.contains("fishing-small");

        var inventory = serverPlayer.getInventory();
        inventory.clearContent();
        inventory.setItem(0, new ItemStack(Items.FISHING_ROD));
        inventory.setSelectedSlot(0);
        serverPlayer.inventoryMenu.broadcastChanges();

        BlockPos platform = serverPlayer.blockPosition().below();
        var level = serverPlayer.level();
        int minX = smallFishingTarget ? 0 : -3;
        int maxX = smallFishingTarget ? 0 : 3;
        int minZ = smallFishingTarget ? 4 : 1;
        int maxZ = smallFishingTarget ? (oneBlockFishingTarget ? 4 : 5) : 7;

        if (smallFishingTarget) {
            // Keep only one/two visible water columns, but clear the surrounding open-water area
            // so vanilla can fish there. The player's stone platform at z=0 stays outside that
            // area; the target is deliberately a little farther away than the original fixture.
            for (int x = -2; x <= 2; x++) {
                for (int z = minZ - 2; z <= maxZ + 2; z++) {
                    for (int y = -2; y <= 20; y++) {
                        level.setBlockAndUpdate(platform.offset(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int depth = 0; depth < 5; depth++) {
                    level.setBlockAndUpdate(platform.offset(x, -depth, z), Blocks.WATER.defaultBlockState());
                }
                level.setBlockAndUpdate(platform.offset(x, -5, z), Blocks.STONE.defaultBlockState());
                for (int y = 1; y <= 20; y++) {
                    // FishingHook requires the bobber's open-water area to see the sky.
                    level.setBlockAndUpdate(platform.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(platform, Blocks.STONE.defaultBlockState());

        // Face the middle of the pool from its edge so the bot's normal rod use lands in water.
        // The small fixture deliberately starts mis-aimed so the test proves the node turns
        // toward the one/two-block target before it casts.
        float startingYaw = smallFishingTarget ? 35.0F : 0.0F;
        float startingPitch = smallFishingTarget ? 10.0F : 35.0F;
        serverPlayer.setYRot(startingYaw);
        serverPlayer.setXRot(startingPitch);
        // The client's copy of the player belongs to the render thread, and this runs on the
        // server's; hand the two rotations to their own owners.
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.setYRot(startingYaw);
                mc.player.setXRot(startingPitch);
            }
        });
        Constants.LOG.info("AutoRun: prepared fishing fixture at {}", platform);
    }

    /**
     * A flat floor twenty blocks up with a Ghast shelling it.
     *
     * <p>There is no way to meet a Ghast on purpose. They live in one dimension, arrive at a trickle,
     * and the answer to one - look at it, hold still, bat the fireball back as it arrives - happens
     * inside a window three ticks wide that a survival run might reach once an hour. Watching that
     * behaviour, or measuring it, means building the encounter rather than waiting for it.
     *
     * <p>So the arena is the encounter with everything else removed: open sky, a floor that cannot be
     * walked off by accident, a Ghast at a known distance, no other mob in the world, and no weather
     * or nightfall to change the picture halfway through.
     *
     * <p>The rules go through the command dispatcher rather than the gamerule and clock APIs. They are
     * the arena's rules rather than its geometry, a command states each one in a line, and the time
     * system in particular is the kind of thing that gets rewritten between versions. One of them is
     * not cosmetic: {@code mob_griefing} off is what stops a landed fireball cratering a platform
     * twenty blocks up and turning the test into a fall.
     */
    private static void prepareGhastArena(Minecraft mc, ServerPlayer serverPlayer,
                                          java.util.Set<String> parts) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !(serverPlayer.level() instanceof ServerLevel level)) {
            return;
        }
        for (String command : new String[] {
                "difficulty normal",
                "gamerule mob_griefing false",
                "gamerule spawn_monsters false",
                "gamerule spawn_mobs false",
                "gamerule advance_time false",
                "gamerule advance_weather false",
                "weather clear",
                "time set day" }) {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), command);
        }

        int floorY = Math.min(level.getMaxY() - GHAST_HEADROOM - 2,
                serverPlayer.getBlockY() + GHAST_PLATFORM_RISE);
        BlockPos centre = new BlockPos(serverPlayer.getBlockX(), floorY, serverPlayer.getBlockZ());
        for (int dx = -GHAST_FLOOR_RADIUS; dx <= GHAST_FLOOR_RADIUS; dx++) {
            for (int dz = -GHAST_FLOOR_RADIUS; dz <= GHAST_FLOOR_RADIUS; dz++) {
                level.setBlockAndUpdate(centre.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
        int cleared = clearAir(level, centre, GHAST_CENTRE_RADIUS, GHAST_HEADROOM)
                + clearAir(level, centre, GHAST_FLOOR_RADIUS, GHAST_FLIGHT_BAND);

        serverPlayer.teleportTo(level, centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5,
                java.util.Set.of(), serverPlayer.getYRot(), 0.0F, true);
        serverPlayer.setDeltaMovement(Vec3.ZERO);
        serverPlayer.fallDistance = 0.0F;
        give(serverPlayer, new ItemStack(Items.IRON_SWORD));
        give(serverPlayer, new ItemStack(Items.COOKED_BEEF, 16));
        // Not for the fireball - anything in hand bats one of those. This is so the arena is not
        // quietly testing a bot with nothing to build with: the ordinary hostile branch reaches for
        // cover and a pillar when a Ghast closes, and an empty pack turns those into no-ops.
        give(serverPlayer, new ItemStack(Items.COBBLESTONE, 64));

        ghastsWanted = ghastCount(parts);
        ghastKeeperTicks = 0;
        ghastArena = centre;
        Constants.LOG.info("AutoRun: Ghast arena floor at {} ({} blocks cleared), keeping {} Ghast(s)",
                centre, cleared, ghastsWanted);
    }

    /**
     * Opens a square of air above the floor, and says how many blocks it had to move.
     *
     * <p>Reads before it writes. Twenty blocks above the terrain is usually open sky, a block read is
     * far cheaper than a block update, and the unconditional version of this is tens of thousands of
     * updates inside one tick - which on a hillside seed is a visible freeze rather than a fixture.
     */
    private static int clearAir(ServerLevel level, BlockPos centre, int radius, int height) {
        int cleared = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy <= height; dy++) {
                    BlockPos above = centre.offset(dx, dy, dz);
                    if (!level.getBlockState(above).isAir()) {
                        level.setBlockAndUpdate(above, Blocks.AIR.defaultBlockState());
                        cleared++;
                    }
                }
            }
        }
        return cleared;
    }

    /** "ghast" is one and "ghast-3" is three: the count is the suffix, the way the fishing sizes are. */
    private static int ghastCount(java.util.Set<String> parts) {
        int wanted = 1;
        for (String part : parts) {
            if (!part.startsWith("ghast")) {
                continue;
            }
            String suffix = part.substring("ghast".length());
            if (!suffix.startsWith("-")) {
                continue;
            }
            try {
                wanted = Math.max(wanted,
                        Math.clamp(Integer.parseInt(suffix.substring(1)), 1, MAX_ARENA_GHASTS));
            } catch (NumberFormatException e) {
                Constants.LOG.warn("AutoRun: '{}' is not a Ghast count", part);
            }
        }
        return wanted;
    }

    /**
     * Keeps the arena stocked, which is the half a one-shot fixture cannot do.
     *
     * <p>A batted fireball kills its Ghast outright - that is the whole point of the deflection - so
     * a fixture that spawns one and walks away buys the run a single shot to look at. Replacing what
     * has gone turns the arena into something that can be left running while the behaviour happens
     * twenty times over.
     */
    private static void maintainGhastArena(Minecraft mc) {
        BlockPos centre = ghastArena;
        // Not until the card is actually running. The floor and the teleport happen during the join
        // settle, which is right - the arena has to exist before anything stands on it - but a Ghast
        // put up at the same moment gets a free shot in while nothing is watching for one. A measured
        // run opened at sixteen hearts with "dmg=fireball (Ghast)" already in its first line.
        if (centre == null || !started || mc.player == null || mc.getSingleplayerServer() == null) {
            return;
        }
        if (++ghastKeeperTicks < GHAST_KEEPER_INTERVAL) {
            return;
        }
        ghastKeeperTicks = 0;
        java.util.UUID playerId = mc.player.getUUID();
        int wanted = ghastsWanted;
        mc.getSingleplayerServer().execute(() -> stockGhasts(mc, playerId, centre, wanted));
    }

    private static void stockGhasts(Minecraft mc, java.util.UUID playerId, BlockPos centre,
                                    int wanted) {
        if (mc.getSingleplayerServer() == null) {
            return;
        }
        ServerPlayer serverPlayer = mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);
        if (serverPlayer == null || !(serverPlayer.level() instanceof ServerLevel level)) {
            return;
        }
        java.util.List<Ghast> present = level.getEntitiesOfClass(Ghast.class,
                new AABB(centre).inflate(GHAST_ARENA_RADIUS), Ghast::isAlive);
        for (Ghast ghast : present) {
            // A Ghast only takes a target whose height is within four blocks of its own, and one
            // that has drifted while floating about quietly stops shooting. Handing the target back
            // is what stops the arena going silent for no reason anybody watching can see.
            if (ghast.getTarget() == null) {
                ghast.setTarget(serverPlayer);
            }
        }
        for (int spawned = present.size(); spawned < wanted; spawned++) {
            spawnArenaGhast(level, serverPlayer, centre);
        }
    }

    /**
     * Puts one Ghast somewhere it can actually shoot from.
     *
     * <p>A random bearing is not enough. The ring reaches further out than the cleared band, so a
     * bearing can land inside a hillside - where the Ghast is stuck in rock, cannot see the player,
     * and the arena quietly has one fewer shooter than it reports. Both conditions are checked
     * before the spawn commits: the body fits, and there is a clear line to the player, which is what
     * vanilla's own shoot goal requires before it will wind up.
     *
     * <p>Giving up after {@link #GHAST_SPAWN_ATTEMPTS} rather than forcing one in is deliberate. The
     * keeper comes back in a second, and a Ghast in a wall is worse than a Ghast a moment late.
     */
    private static void spawnArenaGhast(ServerLevel level, ServerPlayer serverPlayer,
                                        BlockPos centre) {
        Ghast ghast = EntityType.GHAST.create(level, EntitySpawnReason.COMMAND);
        if (ghast == null) {
            Constants.LOG.warn("AutoRun: could not create a Ghast");
            return;
        }
        for (int attempt = 0; attempt < GHAST_SPAWN_ATTEMPTS; attempt++) {
            double angle = ghastRandom.nextDouble() * Math.PI * 2.0;
            double distance = GHAST_RING_MIN
                    + ghastRandom.nextDouble() * (GHAST_RING_MAX - GHAST_RING_MIN);
            ghast.snapTo(centre.getX() + 0.5 + Math.cos(angle) * distance,
                    // Level with the floor, give or take: a Ghast only takes a target within four
                    // blocks of its own height, so one parked overhead never fires at all.
                    centre.getY() + ghastRandom.nextInt(4),
                    centre.getZ() + 0.5 + Math.sin(angle) * distance,
                    (float) Math.toDegrees(angle) + 90.0F, 0.0F);
            if (!level.noCollision(ghast) || !ghast.hasLineOfSight(serverPlayer)) {
                continue;
            }
            ghast.setPersistenceRequired();
            ghast.setTarget(serverPlayer);
            level.addFreshEntity(ghast);
            Constants.LOG.info("AutoRun: Ghast at {}, {} blocks out", ghast.blockPosition(),
                    Math.round(distance));
            return;
        }
        Constants.LOG.warn("AutoRun: no clear spot for a Ghast around {} after {} tries",
                centre, GHAST_SPAWN_ATTEMPTS);
    }

    private static void finish(Minecraft mc, BotEngine engine, String reason) {
        finished = true;
        Constants.LOG.info("AutoRun: {}", reason);
        engine.stopAll(reason);
        if (!quitWhenDone) {
            return;
        }
        // Leaving the world first means the level is saved and the journal is already closed by
        // the stop above, so the shell sees a complete run rather than a half-written file.
        Constants.LOG.info("AutoRun: closing the client");
        mc.schedule(mc::stop);
    }

    private static int intProperty(String key, int fallback) {
        try {
            String raw = System.getProperty(key);
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            Constants.LOG.warn("AutoRun: {} is not a number, using {}", key, fallback);
            return fallback;
        }
    }
}
