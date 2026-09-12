package com.etka.lune.bot;

import com.etka.lune.Constants;
import com.etka.lune.bot.knowledge.BiomeKnowledge;
import com.etka.lune.bot.memory.BlockMemory;
import com.etka.lune.bot.memory.CraftingTableMemory;
import com.etka.lune.bot.task.SpeedrunOpportunity;
import com.etka.lune.bot.util.OmniscientAccess;
import com.etka.lune.bot.util.TargetIndex;
import com.etka.lune.config.BotConfig;
import com.etka.lune.platform.BuildFeatures;
import com.etka.lune.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent, human-readable telemetry for one bot run.
 *
 * <p>The HUD is intentionally short-lived and point-in-time. This journal keeps the same shared
 * decision state over time, which makes loops and failed assumptions diagnosable after the client
 * has moved on. It is deliberately change-driven, with a one-second heartbeat while a run is
 * otherwise repeating.</p>
 */
public final class RunTrace implements AutoCloseable {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int HEARTBEAT_TICKS = 20;
    private static final int LANDMARK_RADIUS = 6;
    private static final int LANDMARK_VERTICAL = 4;
    private static final int MAX_LANDMARKS = 24;

    /** Blocks that are useful clues for villages, shipwrecks, bastions, fortresses, or work sites. */
    private static final List<Block> LANDMARK_BLOCKS = List.of(
            Blocks.CHEST, Blocks.BARREL, Blocks.CRAFTING_TABLE, Blocks.FURNACE,
            Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BREWING_STAND, Blocks.ANVIL,
            Blocks.BELL, Blocks.HAY_BLOCK, Blocks.BOOKSHELF, Blocks.WHITE_BED,
            Blocks.END_PORTAL_FRAME, Blocks.SPAWNER, Blocks.NETHER_BRICKS,
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN
    );

    /** Ticks a mission may repeat itself before the journal says so in its own voice. */
    private static final int STALL_TICKS = 2400;
    /** How many statuses the closing summary lists, most expensive first. */
    private static final int SUMMARY_STATUSES = 12;
    /** Floor for the structure scan when the render distance is set very low. */
    private static final int MIN_STRUCTURE_RADIUS = 32;
    /** Blocks moved before the structure search re-runs; the distance stays live in between. */
    private static final int STRUCTURE_REANCHOR = 32;
    /** Structures are found by their ground-level blocks, so a tall band buys nothing. */
    private static final int STRUCTURE_VERTICAL = 16;

    private final Path file;
    private final BufferedWriter writer;
    private String lastSignature = "";
    private int lastSnapshotTick = Integer.MIN_VALUE;
    private BlockPos landmarkCentre;
    private String landmarkSummary = "none detected";
    private String structureSummary = "unknown";
    private final TargetIndex structureIndex = new TargetIndex();
    private BlockPos structureAnchor;
    private BlockPos structureFound;
    private String structureDimension = "";
    private boolean closed;

    // --- accumulated for the closing summary -------------------------------------------------
    private final Map<String, Integer> phaseTicks = new LinkedHashMap<>();
    private final Map<String, Integer> statusTicks = new HashMap<>();
    private final Map<String, Integer> eventCounts = new LinkedHashMap<>();
    private String currentPhase = "";
    private int firstTick = -1;
    private int lastTick;
    private int minHealth = Integer.MAX_VALUE;
    private int minFood = Integer.MAX_VALUE;
    /** Motion ledger; see {@link #accumulateMotion}. */
    private double lastX = Double.MIN_VALUE;
    private double lastZ;
    private double blocksTravelled;
    private int movingTicks;
    private int sprintingTicks;
    private int jumps;
    private boolean wasAirborne;
    private String lastMission = "";
    private int missionUnchangedSince = -1;
    private int stallsReported;
    private String lastDiversions = "";
    private String lastFailures = "";

    private RunTrace(Path file, BufferedWriter writer) {
        this.file = file;
        this.writer = writer;
    }

    /** Opens a new run file, returning {@code null} if diagnostics cannot be written. */
    public static RunTrace open(LocalPlayer player, ClientLevel level, BotConfig config) {
        try {
            Path directory = Services.PLATFORM.getConfigDir().resolve("lune-runs");
            Files.createDirectories(directory);
            String base = "run-" + FILE_TIME.format(LocalDateTime.now());
            Path file = directory.resolve(base + ".txt");
            int suffix = 2;
            while (Files.exists(file)) {
                file = directory.resolve(base + "-" + suffix++ + ".txt");
            }
            BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            RunTrace trace = new RunTrace(file, writer);
            trace.line("Lune run trace");
            trace.line("format=3");
            trace.line("started=" + LocalDateTime.now());
            trace.line("dimension=" + level.dimension());
            // Written so a run can be repeated from its own journal rather than from a log that has
            // since rotated away. 0 means the world was not generated by the test harness.
            trace.line("seed=" + AutoRun.worldSeed());
            trace.line("start=" + player.blockPosition().toShortString()
                    + " yaw=" + round(player.getYRot()) + " pitch=" + round(player.getXRot()));
            boolean omniscientAllowed = OmniscientAccess.isAllowed(Minecraft.getInstance());
            String configLine = "omniscientMining:" + (config.omniscientMining && omniscientAllowed)
                    + ",omniscientHarvesting:" + (config.omniscientHarvesting && omniscientAllowed);
            configLine += ",learningEnabled:" + config.learningEnabled
                    + ",explore:" + com.etka.lune.bot.learning.LearningStore.get().explorationMode();
            if (BuildFeatures.approvalFeedback()) {
                configLine += ",userLearningEnabled:" + config.userLearningEnabled;
            }
            trace.line("config=" + configLine
                    + ",nodeBudget:" + config.nodeBudget
                    + ",repathInterval:" + config.repathInterval);
            trace.line("columns=tick,type,task,mission,intent,status,position,look,vitals,world,action,target,blocked,giveup,blocks,tools,inventory,memory,flow"
                    + ",learning"
                    + ",structure,landmarks,path,sight,motion,decisions");
            return trace;
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not open Lune run trace", e);
            return null;
        }
    }

    public Path file() {
        return file;
    }

    /** Records one event immediately, regardless of whether the snapshot changed. */
    public void event(int tick, String event, DebugInfo debug, LocalPlayer player, ClientLevel level) {
        if (closed) {
            return;
        }
        eventCounts.merge(safe(event), 1, Integer::sum);
        line(format(tick, "EVENT:" + safe(event), debug, player, level, true));
    }

    /** Records a changed snapshot and a once-per-second heartbeat for repeated states. */
    public void tick(int tick, DebugInfo debug, LocalPlayer player, ClientLevel level) {
        if (closed) {
            return;
        }
        accumulate(tick, debug, player);
        checkForStall(tick, debug, player, level);
        String signature = signature(debug, player, level);
        if (!signature.equals(lastSignature) || tick - lastSnapshotTick >= HEARTBEAT_TICKS) {
            line(format(tick, "SNAPSHOT", debug, player, level, false));
            lastSignature = signature;
            lastSnapshotTick = tick;
        }
    }

    /**
     * Totals for the closing summary, charged one tick at a time.
     * <p>
     * Reconstructing this afterwards means an awk pass over tens of megabytes to answer questions
     * as basic as "where did the time go", which is exactly the question worth answering first.
     */
    private void accumulate(int tick, DebugInfo debug, LocalPlayer player) {
        if (firstTick < 0) {
            firstTick = tick;
        }
        lastTick = tick;
        String phase = phaseOf(debug.missionProgress);
        if (!phase.isEmpty()) {
            currentPhase = phase;
            phaseTicks.merge(phase, 1, Integer::sum);
        }
        String status = generalise(debug.taskStatus);
        if (!status.isBlank()) {
            statusTicks.merge(status, 1, Integer::sum);
        }
        minHealth = Math.min(minHealth, Math.round(player.getHealth()));
        minFood = Math.min(minFood, player.getFoodData().getFoodLevel());
        accumulateMotion(player);
        if (!debug.diversions.isBlank()) {
            lastDiversions = debug.diversions;
        }
        if (!debug.failures.isEmpty()) {
            lastFailures = debug.failureSnapshot().toString();
        }
    }

    /**
     * How the bot actually moved, charged one tick at a time.
     * <p>
     * Everything else in the journal describes intent - the block it wants, the route it planned,
     * the watchdog it is near. None of it answers the question a watcher actually asks, which is
     * whether the bot got on with it. Ground covered, how much of it was at a run, and how often it
     * left the ground are three numbers that make "it looks sluggish" into something with a value,
     * and make a change to the movement rules provable rather than a matter of taste.
     */
    private void accumulateMotion(LocalPlayer player) {
        if (lastX != Double.MIN_VALUE) {
            double dx = player.getX() - lastX;
            double dz = player.getZ() - lastZ;
            double step = Math.sqrt(dx * dx + dz * dz);
            // A teleport, a respawn or a dimension change is not travel, and letting one through
            // makes the whole distance figure meaningless.
            if (step < 2.0) {
                blocksTravelled += step;
                if (step > 0.01) {
                    movingTicks++;
                    if (player.isSprinting()) {
                        sprintingTicks++;
                    }
                }
            }
        }
        lastX = player.getX();
        lastZ = player.getZ();
        boolean airborne = !player.onGround();
        if (airborne && !wasAirborne && !player.isInWater()) {
            jumps++;
        }
        wasAirborne = airborne;
    }

    /** What the search was looking at, and which way of choosing produced the current target. */
    private static String sightSummary(DebugInfo debug) {
        String tally = debug.sightTally.isBlank() ? "-" : debug.sightTally;
        String source = debug.selectionSource.isBlank() ? "-" : debug.selectionSource;
        return "via=" + source + ";" + tally;
    }

    /** The motion ledger as a journal column. */
    private String motionSummary() {
        long sprintShare = movingTicks == 0 ? 0 : Math.round(100.0 * sprintingTicks / movingTicks);
        return "walked=" + round(blocksTravelled)
                + ";moving=" + movingTicks + "t"
                + ";sprint=" + sprintShare + "%"
                + ";airborne=" + jumps;
    }

    /**
     * Says so, loudly and in the file, when the mission has stopped moving.
     * <p>
     * A run that holds {@code iron 0/4} for fourteen thousand ticks is the most important thing
     * that happened, and it is completely invisible in a journal made of per-tick lines that all
     * look reasonable on their own.
     */
    private void checkForStall(int tick, DebugInfo debug, LocalPlayer player, ClientLevel level) {
        // Mission progress plus the generalised status, because only the speedrun keeps a mission
        // line - an ordinary task that spends three minutes "coming next to the tree" is just as
        // stuck, and keying on the mission alone would never say so.
        String mission = debug.missionProgress + "|" + generalise(debug.taskStatus);
        if (!mission.equals(lastMission)) {
            lastMission = mission;
            missionUnchangedSince = tick;
            stallsReported = 0;
            return;
        }
        if (missionUnchangedSince < 0 || mission.isBlank() || mission.equals("|")) {
            missionUnchangedSince = tick;
            return;
        }
        int stalled = tick - missionUnchangedSince;
        if (stalled >= STALL_TICKS * (stallsReported + 1)) {
            stallsReported++;
            eventCounts.merge("stall", 1, Integer::sum);
            line(format(tick, "EVENT:stall(" + stalled + " ticks with no mission change)",
                    debug, player, level, true));
        }
    }

    /** Strips positions and counts so "walking to target - 12 blocks left" totals as one status. */
    private static String generalise(String status) {
        if (status == null) {
            return "";
        }
        return status.replaceAll("-?\\d+, -?\\d+(, -?\\d+)?", "POS").replaceAll("\\d+", "N");
    }

    private static String phaseOf(String missionProgress) {
        if (missionProgress == null) {
            return "";
        }
        int start = missionProgress.indexOf("phase ");
        if (start < 0) {
            return "";
        }
        int end = start + 6;
        while (end < missionProgress.length()
                && (Character.isUpperCase(missionProgress.charAt(end)) || missionProgress.charAt(end) == '_')) {
            end++;
        }
        return missionProgress.substring(start + 6, end);
    }

    @Override
    public void close() {
        close("run ended");
    }

    public void close(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        writeSummary();
        line("END reason=" + safe(reason));
        try {
            writer.close();
        } catch (IOException e) {
            Constants.LOG.warn("Could not close Lune run trace {}", file, e);
        }
    }

    /**
     * Twenty lines that answer the questions a run is actually read for, so the file does not have
     * to be. Written before END so the verdict and the reason sit together at the tail.
     */
    private void writeSummary() {
        // Inclusive of both ends: the first accumulated tick is a tick of the run, not a fencepost.
        int total = Math.max(1, lastTick - Math.max(firstTick, 0) + 1);
        line("");
        line("SUMMARY ticks=" + total + " (" + seconds(total) + "s)"
                + " reachedPhase=" + (currentPhase.isEmpty() ? "-" : currentPhase)
                + " minHealth=" + (minHealth == Integer.MAX_VALUE ? "-" : minHealth)
                + " minFood=" + (minFood == Integer.MAX_VALUE ? "-" : minFood));
        for (Map.Entry<String, Integer> phase : phaseTicks.entrySet()) {
            line("SUMMARY phase " + pad(phase.getKey(), 16) + " " + share(phase.getValue(), total));
        }
        List<Map.Entry<String, Integer>> statuses = new ArrayList<>(statusTicks.entrySet());
        statuses.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
        for (Map.Entry<String, Integer> status : statuses.subList(0, Math.min(SUMMARY_STATUSES, statuses.size()))) {
            line("SUMMARY status " + share(status.getValue(), total) + "  " + status.getKey());
        }
        if (!eventCounts.isEmpty()) {
            line("SUMMARY events " + eventCounts);
        }
        // The headline number for any change to movement or target choice. Blocks per minute of
        // travel and the share of it spent at a run are what a watcher means by "it looks better".
        line("SUMMARY motion " + motionSummary()
                + ";idle=" + share(total - movingTicks, total).trim());
        line("SUMMARY diversions " + (lastDiversions.isBlank() ? "none" : lastDiversions));
        line("SUMMARY failures " + (lastFailures.isBlank() ? "none" : lastFailures));
    }

    private static String share(int ticks, int total) {
        return String.format(java.util.Locale.ROOT, "%6d ticks %5.1fs %4.1f%%",
                ticks, ticks / 20.0, 100.0 * ticks / total);
    }

    private static String seconds(int ticks) {
        return String.format(java.util.Locale.ROOT, "%.0f", ticks / 20.0);
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    private static String vitals(LocalPlayer player) {
        return Vitals.describe(player.getHealth(), player.getMaxHealth(),
                player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(),
                player.getAirSupply(), player.getMaxAirSupply(), player.getArmorValue(),
                damageSource(player));
    }

    private static String damageSource(LocalPlayer player) {
        DamageSource source = player.getLastDamageSource();
        if (source == null) {
            return "";
        }
        Entity attacker = source.getEntity();
        return attacker == null
                ? source.getMsgId()
                : source.getMsgId() + " (" + attacker.getType().getDescription().getString() + ")";
    }

    /**
     * The world state that explains behaviour nothing in the bot's own telemetry accounts for.
     * <p>
     * Whether it was dark is the single most useful missing fact: it decides whether a bed may be
     * used, whether hostile mobs are spawning, and whether a bot losing health on the surface was
     * unlucky or simply out after dusk. Light level separates "night" from "underground", which
     * look identical in a position column.
     */
    private static String world(ClientLevel level, BlockPos feet) {
        return "time=" + Math.floorMod(level.getDefaultClockTime(), 24000L)
                + ";dark=" + level.isDarkOutside()
                + ";sky=" + level.getBrightness(LightLayer.SKY, feet)
                + ";block=" + level.getBrightness(LightLayer.BLOCK, feet)
                + ";raining=" + level.isRaining()
                // The biome explains behaviour that looks like a bug until you know where it
                // happened. A mangrove swamp is roots, mud and standing water in every direction:
                // routes that fail there are the terrain, not the pathfinder losing its mind.
                + ";biome=" + BiomeKnowledge.name(level.getBiome(feet))
                // Blocks between the player's head and open sky. The single number that says
                // "buried" without having to cross-reference a Y coordinate against the terrain:
                // Y=63 is the surface on a beach and forty metres of stone under a mountain.
                + ";depth=" + depthBelowSky(level, feet);
    }

    /** How far the player would have to dig straight up to see the sun; 0 when already outside. */
    private static int depthBelowSky(ClientLevel level, BlockPos feet) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                feet.getX(), feet.getZ());
        return Math.max(0, surface - feet.getY());
    }

    private static String flowSummary(DebugInfo debug) {
        return "diversions=" + (debug.diversions.isBlank() ? "none" : debug.diversions)
                + ";failures=" + debug.failureSnapshot()
                + ";safety=" + (debug.safety.isBlank() ? "-" : debug.safety)
                + ";safetyEpisodes=" + debug.safetyEpisodes;
    }

    /** Exposed for focused tests and for keeping the meaning of a blocked state consistent. */
    static String blockedReason(DebugInfo debug) {
        return BlockedReason.describe(debug.obstruction, debug.placementVerdict, debug.breakVerdict,
                debug.targetVerdict, debug.noProgressTicks, debug.goalNoProgressTicks, debug.missionLoop);
    }

    private String format(int tick, String type, DebugInfo debug, LocalPlayer player,
                          ClientLevel level, boolean includeLandmarks) {
        BlockPos feet = player.blockPosition();
        if (includeLandmarks || landmarkCentre == null || landmarkCentre.distSqr(feet) > 16) {
            landmarkCentre = feet.immutable();
            landmarkSummary = nearbyLandmarks(level, feet);
        }
        structureSummary = describeNearestStructure(level, feet);

        String action = "keys=" + safe(debug.keys)
                + ";move=" + pos(debug.movementTarget, debug.movementLabel)
                + ";place=" + pos(debug.placementTarget, debug.placementBlock + "/" + debug.placementVerdict)
                + ";break=" + pos(debug.breakTarget, debug.breakBlock + "/" + debug.breakVerdict);
        String target = safe(debug.targetLabel) + "@" + pos(debug.targetPos, debug.targetVerdict);
        String blocks = "feet=" + blockAt(level, feet)
                + ";below=" + blockAt(level, feet.below())
                + ";obstruction=" + pos(debug.obstructionPos, debug.obstruction);
        String mission = debug.missionProgress.isBlank() ? debug.missionMemory : debug.missionProgress
                + (debug.missionMemory.isBlank() ? "" : " | " + debug.missionMemory);
        String path = "goal=" + safe(debug.goal)
                + ";node=" + pos(debug.currentNode, "")
                + ";index=" + debug.pathIndex + "/" + debug.pathLength
                + ";expanded=" + debug.nodesExpanded
                + ";ms=" + round(debug.searchMillis)
                + ";reached=" + debug.reachedGoal
                + ";repaths=" + debug.repaths;

        String learning = " learning=" + safe(learningSummary(debug));
        return "[" + tick + "] " + type
                + " task=" + safe(debug.taskName)
                + " mission=" + safe(mission)
                + " intent=" + safe(debug.intent)
                + " status=" + safe(debug.taskStatus)
                + " position=" + feet.toShortString()
                + " look=yaw:" + round(player.getYRot()) + ",pitch:" + round(player.getXRot())
                + " vitals=" + safe(vitals(player))
                + " world=" + safe(world(level, feet))
                + " action=" + safe(action)
                + " target=" + safe(target)
                + " blocked=" + safe(blockedReason(debug))
                // How close the running job is to each of its own give-up limits. Twenty-three
                // places set this and nothing recorded it, so a journal could show a bot walking
                // toward the same tree for seven minutes without ever saying which watchdog was
                // supposed to stop it - or that one of them was being reset every two blocks.
                + " giveup=" + safe(debug.giveUp)
                + " blocks=" + safe(blocks)
                + " tools=" + safe(toolSummary(player))
                + " inventory=" + safe(inventorySummary(player))
                + " memory=" + safe(memorySummary(debug))
                + " flow=" + safe(flowSummary(debug))
                + learning
                + " structure=" + safe(structureSummary)
                + " landmarks=" + safe(landmarkSummary)
                + " path=" + safe(path)
                + " sight=" + safe(sightSummary(debug))
                + " motion=" + safe(motionSummary())
                + " decisions=" + safe(decisionSummary(debug));
    }

    private static String signature(DebugInfo debug, LocalPlayer player, ClientLevel level) {
        return debug.taskName + "|" + debug.taskStatus + "|" + debug.intent + "|" + debug.giveUp + "|"
                + debug.targetLabel + "|" + debug.targetPos + "|" + debug.targetVerdict + "|"
                + debug.nextDecision + "|" + debug.obstruction + "|" + debug.missionProgress + "|"
                + debug.missionLoop + "|" + debug.movementTarget + "|" + debug.placementTarget + "|"
                + debug.breakTarget + "|" + debug.keys + "|" + player.blockPosition() + "|"
                + level.dimension() + "|" + debug.learningContext + "|" + debug.learningAction
                + "|" + debug.learningReward + "|" + debug.learningUpdates + "|"
                + Vitals.signature(player.getHealth(), player.getFoodData().getFoodLevel(),
                        player.getAirSupply(), player.getMaxAirSupply());
    }

    private static String memorySummary(DebugInfo debug) {
        return "debug=" + debug.memory + ";mission=" + debug.missionMemory
                + ";blockMemory=" + BlockMemory.get().size()
                + ";unreachable=" + BlockMemory.get().getUnreachable().size()
                + ";tables=" + CraftingTableMemory.get().size();
    }

    private static String learningSummary(DebugInfo debug) {
        String automatic = BuildFeatures.approvalFeedback()
                ? ";auto=" + debug.automaticVerdict + ";autoTicks=" + debug.automaticTicks
                        + ";usualTicks=" + debug.automaticUsualTicks
                        + ";autoReason=" + debug.automaticReason
                : "";
        return "state=" + debug.learningContext + ";action=" + debug.learningAction
                + ";reward=" + round(debug.learningReward)
                + ";updates=" + debug.learningUpdates
                + ";memory=" + debug.learningMemory + automatic;
    }

    private static String toolSummary(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        StringBuilder tools = new StringBuilder("main=").append(item(player.getMainHandItem()))
                .append(";off=").append(item(player.getOffhandItem())).append(";hotbar=");
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            if (slot > 0) {
                tools.append(',');
            }
            tools.append(slot).append(':').append(item(inventory.getItem(slot)));
        }
        return tools.toString();
    }

    private static String inventorySummary(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        return counts.isEmpty() ? "empty" : counts.toString();
    }

    /**
     * The nearest recognisable structure and how far away it is.
     * <p>
     * Deliberately <em>not</em> filtered by visibility. The useful signal is the gap between what
     * was there and what the bot acted on - this column reports; the sighting sweep decides.
     * <p>
     * The reach is the player's view distance, and the cost rules are the same as the sweep's: the
     * search re-runs only when the bot has moved {@link #STRUCTURE_REANCHOR} blocks or the found
     * block disappeared, and every line in between just recomputes the distance to the cached find.
     * Re-scanning a view-distance region on every four-block step was a per-line world scan, which
     * is a frame stutter wearing a diagnostic's clothes.
     */
    private String describeNearestStructure(ClientLevel level, BlockPos feet) {
        String dimension = level.dimension().identifier().toString();
        if (!dimension.equals(structureDimension)) {
            // Nether coordinates overlap Overworld ones, so a stale index would "find" the
            // structure it indexed in the other world.
            structureDimension = dimension;
            structureAnchor = null;
            structureFound = null;
            structureIndex.invalidate();
        }
        if (structureFound != null
                && SpeedrunOpportunity.classify(level.getBlockState(structureFound).getBlock()) == null) {
            structureFound = null;
            structureAnchor = null;
        }
        int radius = (int) Math.max(MIN_STRUCTURE_RADIUS,
                net.minecraft.client.Minecraft.getInstance().options.renderDistance().get() * 16.0);
        if (structureAnchor == null
                || structureAnchor.distSqr(feet) > (double) STRUCTURE_REANCHOR * STRUCTURE_REANCHOR) {
            structureAnchor = feet.immutable();
            Set<Block> markers = SpeedrunOpportunity.allMarkers();
            int yMin = Math.max(level.getMinY(), structureAnchor.getY() - STRUCTURE_VERTICAL);
            int yMax = Math.min(level.getMaxY() - 1, structureAnchor.getY() + STRUCTURE_VERTICAL);
            structureIndex.rebuild(level, structureAnchor, markers, radius, yMin, yMax);
            structureFound = structureIndex.nearest(level, structureAnchor, Set.of(),
                    (pos, state) -> markers.contains(state.getBlock()));
        }
        if (structureFound == null) {
            return "none within " + radius;
        }
        SpeedrunOpportunity kind = SpeedrunOpportunity.classify(
                level.getBlockState(structureFound).getBlock());
        long distance = Math.round(Math.sqrt(feet.distSqr(structureFound)));
        return (kind == null ? "structure" : kind.label())
                + " at " + structureFound.toShortString() + " (" + distance + " blocks)";
    }

    private static String nearbyLandmarks(ClientLevel level, BlockPos centre) {
        Map<String, List<String>> found = new LinkedHashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = Math.max(level.getMinY(), centre.getY() - LANDMARK_VERTICAL);
        int maxY = Math.min(level.getMaxY() - 1, centre.getY() + LANDMARK_VERTICAL);
        for (int y = minY; y <= maxY && found.size() < MAX_LANDMARKS; y++) {
            for (int x = centre.getX() - LANDMARK_RADIUS;
                 x <= centre.getX() + LANDMARK_RADIUS && found.size() < MAX_LANDMARKS; x++) {
                for (int z = centre.getZ() - LANDMARK_RADIUS;
                     z <= centre.getZ() + LANDMARK_RADIUS && found.size() < MAX_LANDMARKS; z++) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    Block block = level.getBlockState(cursor).getBlock();
                    if (!LANDMARK_BLOCKS.contains(block)) {
                        continue;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                    found.computeIfAbsent(id, ignored -> new ArrayList<>()).add(cursor.immutable().toShortString());
                }
            }
        }
        if (found.isEmpty()) {
            return "none detected";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : found.entrySet()) {
            parts.add(entry.getKey() + "@" + String.join("|", entry.getValue()));
        }
        return String.join(",", parts);
    }

    private static String blockAt(ClientLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return "unloaded";
        }
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    private static String item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount();
    }

    private static String pos(BlockPos pos, String label) {
        String value = pos == null ? "-" : pos.toShortString();
        return label == null || label.isBlank() ? value : value + "/" + label;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String decisionSummary(DebugInfo debug) {
        return "next=" + debug.nextDecision + debug.decisionRepeatSuffix()
                + ";recent=" + debug.decisionSnapshot();
    }

    private void line(String value) {
        try {
            writer.write(value);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            Constants.LOG.warn("Could not write Lune run trace {}", file, e);
            closed = true;
        }
    }
}
