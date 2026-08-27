package com.etka.lune.bot;

import com.etka.lune.Constants;
import com.etka.lune.bot.task.RoutineTask;
import com.etka.lune.routine.Routine;
import com.etka.lune.routine.RoutineStore;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
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

import java.util.Optional;

/**
 * Unattended test harness: join a world, start a routine, stop on a budget, quit.
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
 * -Dlune.autorun.routine=Speedrun      the routine to start once the world is loaded
 * -Dlune.autorun.createWorld=name      generate a brand new world with a random seed and join it
 * -Dlune.autorun.delayTicks=100        settling time after join, for chunks and the server
 * -Dlune.autorun.fixture=fishing        prepare a rod, water pool, and view for fishing tests
 * -Dlune.autorun.fixture=fishing-small  prepare a rod and a two-block water target
 * -Dlune.autorun.fixture=fishing-one    prepare a rod and a one-block water target
 * -Dlune.autorun.fixture=food,tools     comma-separated; see {@link #prepareFixture}
 * -Dlune.autorun.stopAfterTicks=12000  hard budget; 0 means run until the routine ends
 * -Dlune.autorun.quit=true             close the client afterwards, so a shell run terminates
 * </pre>
 *
 * <p>Each run generates its own world on purpose. Repeating one saved world makes a suite that
 * measures a single spawn rather than the bot: two runs in a row were lost to the same barren,
 * frozen shoreline, which says nothing about whether a change helped. A new seed every time turns
 * a run into a sample, and a bad spawn into one data point instead of the permanent testbed.</p>
 */
public final class AutoRun {

    private static final String ROUTINE_KEY = "lune.autorun.routine";
    private static final String WORLD_KEY = "lune.autorun.createWorld";
    private static final String SEED_KEY = "lune.autorun.seed";
    private static final String DELAY_KEY = "lune.autorun.delayTicks";
    private static final String FIXTURE_KEY = "lune.autorun.fixture";
    private static final String BUDGET_KEY = "lune.autorun.stopAfterTicks";
    private static final String QUIT_KEY = "lune.autorun.quit";

    /** Long enough for the integrated server to hand over the chunks around the spawn. */
    private static final int DEFAULT_DELAY_TICKS = 100;
    /** Ticks to let the title screen finish loading before asking it to build a world. */
    private static final int TITLE_SETTLE_TICKS = 40;

    private static final String routineName = System.getProperty(ROUTINE_KEY, "").trim();
    private static final String worldName = System.getProperty(WORLD_KEY, "").trim();
    private static final int delayTicks = intProperty(DELAY_KEY, DEFAULT_DELAY_TICKS);
    private static final int budgetTicks = intProperty(BUDGET_KEY, 0);
    private static final boolean quitWhenDone = Boolean.getBoolean(QUIT_KEY);
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

    private AutoRun() {}

    /** True when the client was launched as a test run rather than by a player. */
    public static boolean isConfigured() {
        return !routineName.isEmpty();
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
        if (!isConfigured() || finished || worldName.isEmpty() || worldRequested) {
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
     * Ends the run when something outside the routine ended it: the player died, or the world went
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

        if (mc.level != lastLevel) {
            // A fresh world, including one loaded after a previous run finished.
            lastLevel = mc.level;
            joinTicks = 0;
            runTicks = 0;
            started = false;
            fixturePrepared = false;
        }

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
            finish(mc, engine, "autorun routine finished after " + runTicks + " ticks");
        }
    }

    private static void start(Minecraft mc, BotEngine engine) {
        started = true;
        Optional<Routine> routine = RoutineStore.get().byName(routineName);
        if (routine.isEmpty()) {
            Constants.LOG.error("AutoRun: no routine named '{}'. Known routines: {}",
                    routineName, RoutineStore.get().names());
            finish(mc, engine, "autorun could not find routine " + routineName);
            return;
        }
        Constants.LOG.info("AutoRun: starting routine '{}' (budget {} ticks)",
                routineName, budgetTicks);
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[Lune] autorun: " + routineName));
        }
        engine.runNow(new RoutineTask(routine.get()));
    }

    /**
     * Builds the explicit test fixtures requested by the harness. Normal autoruns remain
     * untouched: a fishing routine still correctly fails when the player has no rod.
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
                    if (!part.startsWith("fishing")) {
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
