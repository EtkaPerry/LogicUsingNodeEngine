package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.WhileMonitor;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.learning.TaskLearning;
import com.etka.lune.bot.catalog.BlockCatalog;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockBreaker;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BoatHelper;
import com.etka.lune.bot.util.BucketHelper;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.path.WaterEscape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A While circuit that acts only while it actively handles immediate danger. */
public final class SelfPreservationTask implements WhileMonitor {

    private static final int MAX_ESCAPE_ATTEMPTS = 3;
    /** Do not let a blocked retreat spend an entire creeper fuse in the route executor. */
    private static final int MAX_MONSTER_RECOVERY_TICKS = 20;
    private static final int THREAT_SCAN_RADIUS = 64;
    /** A newly built wall can hide a zombie for more than a few client ticks. */
    private static final int SAFE_CONFIRM_TICKS = 30;
    /** Keep a recently seen hostile in the monitor while it is briefly behind cover. */
    private static final int HOSTILE_MEMORY_TICKS = 80;
    private static final int ENTITY_SCAN_INTERVAL = 5;
    /** How long a mob that hit the player stays a threat without being seen again. */
    private static final int UNSEEN_ATTACKER_TICKS = 200;
    private static final int MAX_HEALTH_WAIT_TICKS = 200;
    private static final int EMERGENCY_BUILD_TICKS = 24;
    private static final double EMERGENCY_ATTACK_REACH = 3.2;
    /** A creeper's fuse is shorter than a normal path search; keep moving until cover breaks LOS. */
    private static final double CREEPER_SAFE_DISTANCE = 10.0;
    /** Notice a visible creeper before it reaches the ordinary mob-monitor range. */
    private static final double CREEPER_ALERT_DISTANCE = 12.0;
    /** A direct sprint that does not change blocks is a terrain stall, not a viable escape. */
    private static final int MAX_CREEPER_STALL_TICKS = 6;
    /** Do not spend a whole enderman encounter repeatedly reconsidering the same roof placement. */
    private static final int MAX_ENDERMAN_SHELTER_TICKS = 30;
    /** Do not let a ranged mob pin the player in a completed pillar forever. */
    private static final int RANGED_STRAFE_SWITCH_TICKS = 24;

    /**
     * How far the player must already have dropped before the clutch takes the controls, in
     * blocks. A vanilla jump peaks 1.25 blocks up and the pathfinder plans gap jumps that land a
     * block below take-off, so anything shorter than this may still be a jump in progress - and
     * seizing the keys mid-arc cuts exactly the air control that was going to clear the gap,
     * causing the fall the clutch exists to survive. Past it, the player is falling whatever they
     * meant to do.
     */
    private static final double COMMITTED_FALL = 2.5;
    private static final float CLUTCH_AIM_TOLERANCE = 10.0F;
    private static final float CLUTCH_TURN_SPEED = 120.0F;
    private static final float STRAIGHT_DOWN = 90.0F;
    /** A fall this deep ends in water, in a boat, or in the respawn screen, well inside six seconds. */
    private static final int CLUTCH_TIMEOUT = 120;
    /**
     * Ticks to wait for a placed hull to be sent back before deciding the spot was refused. Only
     * the server spawns a boat, so this is a round trip: one tick on an integrated server, and the
     * ping on anything else.
     */
    private static final int BOAT_ARRIVAL_TICKS = 6;
    /** Hulls one fall may spend. Two is one honest retry; a third is just littering the drop. */
    private static final int MAX_BOAT_PLACEMENTS = 2;
    /** Ticks the clutch may stay seated before the ride is treated as over, wherever it stopped. */
    private static final int MAX_BOAT_RIDE_TICKS = 400;
    /**
     * Ticks to stay seated before sneaking out, however settled the hull looks.
     *
     * <p>Getting out is a round trip of its own, and the tick the client learns it is seated is
     * often the tick the hull has already touched down - so reading "on the ground" literally makes
     * the bot stand up again before the server has finished sitting it down, and the whole clutch
     * reads as a hiccup rather than a save.
     */
    private static final int MIN_BOAT_RIDE_TICKS = 3;
    /**
     * Ticks spent breaking the hull to get it back. The safe-confirm window usually ends the
     * attempt first; this is the backstop for a boat that has drifted somewhere awkward.
     */
    private static final int MAX_BOAT_RECOVER_TICKS = 40;
    /** How far to look for the hull that was just placed - it can only be a few blocks below. */
    private static final double BOAT_SEARCH_RADIUS = 6.0;
    /** Ticks spent trying to get a cushion down before the attempt is written off. */
    private static final int MAX_CUSHION_TICKS = 20;
    /** Ticks spent digging the cushion back up afterwards; a slime block is worth a few seconds. */
    private static final int MAX_CUSHION_RECOVER_TICKS = 40;

    /**
     * Blocks worth landing on, and the share of the fall each one still charges for.
     *
     * <p>Ordered weakest first on purpose. A clutch spends whatever it lands on, so the rule is to
     * pick the cheapest thing that actually survives the drop rather than the best thing carried -
     * hay is a wheat farm, a slime block is a swamp expedition, and both leave the player standing.
     *
     * <p>A web is not a landing at all: it catches the player inside it and vanilla clears the fall
     * distance every tick they are stuck, which comes to the same nothing as a slime block.
     *
     * <p>Beds are deliberately absent. They are two blocks long, so "does it fit" stops being a
     * question about one tile, and the run needs them for the dragon.
     */
    private record Cushion(Block block, double damageShare) {}

    private static final List<Cushion> CUSHIONS = List.of(
            new Cushion(Blocks.HAY_BLOCK, 0.2),
            new Cushion(Blocks.HONEY_BLOCK, 0.2),
            new Cushion(Blocks.SLIME_BLOCK, 0.0));
    /**
     * How far below to look for the landing, in blocks. This is the drift budget: near terminal
     * velocity the player covers two blocks a tick, so twelve blocks is six ticks of walking and
     * about half a block of correction - less than the width of the mistake it has to fix.
     */
    private static final double CLUTCH_SCAN_DEPTH = 24.0;
    /** Keeps a corner ray off the exact seam between two columns. */
    private static final double CORNER_INSET = 0.001;
    /** Close enough to the middle of the target tile to stop walking at it. */
    private static final double ALIGN_DEADZONE = 0.3;
    /** Standing height, for asking whether a block of water would reach the body. */
    private static final double PLAYER_HEIGHT = 1.8;
    /** Half the body width plus half a block: how far off centre water still touches the player. */
    private static final double FOOTPRINT_OVERLAP = 0.8;
    /**
     * Ticks of sideways travel still owed to the current speed once the key is released. Air drag
     * is 0.91 a tick, so the tail of a press is {@code 0.91 / (1 - 0.91)} - about ten times the
     * speed it ended at, which is far more than the half block this is ever correcting.
     */
    private static final double AIR_GLIDE_TICKS = 10.0;

    private enum Threat { NONE, AIR, LAVA, FALL, MONSTER, HEALTH }

    private final boolean protectAir;
    private final String airComparison;
    private final int airThreshold;
    private final boolean protectLava;
    private final boolean protectMonsters;
    private final String monsterComparison;
    private final int monsterDistance;
    private final boolean protectHealth;
    private final String healthComparison;
    private final int healthThreshold;
    private final boolean protectFall;
    private final int fallThreshold;

    private Threat threat = Threat.NONE;
    private LivingEntity hostile;
    private Task recovery;
    private int escapeAttempts;
    /** Retreats that succeeded against the same hostile, carried across route rebuilds. */
    private int monsterEscapes;
    private int trackedHostileId = -1;
    /** Ticks spent on the current retreat, above the rebuilt GotoTask. */
    private int monsterRecoveryTicks;
    private int safeTicks;
    private int lastEntityScanTick = Integer.MIN_VALUE;
    private LivingEntity cachedNearest;
    private LivingEntity rememberedHostile;
    private int lastHostileSeenTick = Integer.MIN_VALUE;
    private boolean confirmingSafe;
    private int healthWaitTicks;
    /**
     * Set when an eat-to-recover attempt fails while a hostile is present, and required to be clear
     * before the monster branch will hand back to eating. It is what stops the two recovery paths
     * calling each other until the stack runs out.
     */
    private boolean foodRecoveryFailed;
    private BlockPos coverBase;
    private int coverTicks;
    private BlockPos pillarBlock;
    private int pillarTicks;
    /** Position history for the direct creeper escape, above any ordinary route task. */
    private BlockPos creeperLastBlock;
    private int creeperStallTicks;
    /** Alternates the emergency strafe side when terrain blocks a straight retreat. */
    private int creeperEscapeTicks;
    /** The emergency retreat attempt must outlive the route task that may fail. */
    private boolean rangedRetreatTried;
    private int rangedStrafeTicks;
    private boolean rangedStrafeLeft;
    /** Number of blocks in the current emergency wall; keep the cover bounded and useful. */
    private int coverHeight;
    /** Once a Creeper escape has reached the pillar, do not re-equip blocks every tick. */
    private boolean pillarSecured;
    /** Endermen are handled under a two-block roof; they must never enter the normal melee branch. */
    private BlockPos endermanShelterBase;
    private BlockPos endermanShelterSupport;
    private BlockPos endermanShelterRoof;
    private Block endermanShelterMaterial;
    private int endermanShelterTicks;
    /** Position history for close melee defense; a backward press can be blocked by a cave wall. */
    private BlockPos meleeLastBlock;
    private int meleeStallTicks;
    private int meleeStrafeTicks;
    private boolean meleeStrafeLeft;
    private String status = "watching";
    /** One bounded emergency at a time; the outer monitor itself may live forever. */
    private RecoveryEpisode learningEpisode;
    private String recoveryStrategy = SelfPreservationPolicy.DIRECT;

    private ClutchPolicy.Method clutch = ClutchPolicy.Method.NONE;
    private boolean bucketPlaced;
    /** Where the clutch water actually went, so it can be picked back up after landing. */
    private BlockPos clutchWater;
    private int clutchTicks;
    /** A boat placement has gone out to the server and the hull has not been seen yet. */
    private boolean boatPlaced;
    private int boatWaitTicks;
    private int boatPlacements;
    /** Set once the player is actually seated, which is the moment the fall stops counting. */
    private boolean boatBoarded;
    private int boatRideTicks;
    private int boatRecoverTicks;
    /**
     * What the clutch actually managed, kept so a fall that ends badly can say why rather than
     * leaving "it put a boat down and did not get in" as the whole account.
     */
    private int boatPlaceTick = Integer.MIN_VALUE;
    private double boatPlaceDrop;
    private double boatPlaceSpeed;
    private int boatBoardAttempts;
    private int boatSneakBlocks;
    /** Where the cushion went, so it can be dug back up once the fall is over. */
    private BlockPos cushionPos;
    private Block cushionBlock;
    private int cushionTicks;
    private int cushionRecoverTicks;
    private final BlockBreaker cushionBreaker = new BlockBreaker();

    public SelfPreservationTask(boolean protectAir, String airComparison, int airThreshold,
                                boolean protectLava, boolean protectMonsters,
                                String monsterComparison, int monsterDistance,
                                boolean protectHealth, String healthComparison,
                                int healthThreshold, boolean protectFall, int fallThreshold) {
        this.protectAir = protectAir;
        this.airComparison = airComparison;
        this.airThreshold = airThreshold;
        this.protectLava = protectLava;
        this.protectMonsters = protectMonsters;
        this.monsterComparison = monsterComparison;
        this.monsterDistance = monsterDistance;
        this.protectHealth = protectHealth;
        this.healthComparison = healthComparison;
        this.healthThreshold = healthThreshold;
        this.protectFall = protectFall;
        this.fallThreshold = fallThreshold;
    }

    @Override
    public String name() {
        return "Self Preservation";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public boolean automaticSkillLearning() {
        // This monitor is normally infinite. Each actual threat owns a bounded child episode.
        return false;
    }

    @Override
    public boolean shouldTakeControl(BotContext ctx) {
        if (!ctx.player.isAlive()) {
            ctx.input.reset();
            stopRecovery(ctx);
            threat = Threat.NONE;
            hostile = null;
            status = "player is dead";
            return false;
        }
        hostile = null;
        if (protectAir && ctx.player.isUnderWater()
                && compare(ctx.player.getAirSupply(), airComparison, airThreshold)) {
            threat = Threat.AIR;
            safeTicks = 0;
            confirmingSafe = false;
            return true;
        }
        if (protectLava && ctx.player.isInLava()) {
            threat = Threat.LAVA;
            safeTicks = 0;
            confirmingSafe = false;
            return true;
        }
        // Still sitting in the boat counts. The fall itself ended the moment the player was seated,
        // but handing control back there would leave the next routine steering a hull halfway down
        // a ravine instead of walking, so the threat lasts until they have climbed out of it.
        if (protectFall && (ridingClutchBoat(ctx) || isFallingAndDeadly(ctx))) {
            threat = Threat.FALL;
            safeTicks = 0;
            confirmingSafe = false;
            return true;
        }

        LivingEntity nearest = nearestThreat(ctx);
        double nearestDistance = nearest == null
                ? Double.POSITIVE_INFINITY
                : Math.sqrt(nearest.distanceToSqr(ctx.player));
        boolean visibleCreeperBuffer = nearest instanceof Creeper
                && ("At most".equals(monsterComparison) || "Less than".equals(monsterComparison))
                && nearestDistance <= Math.max(monsterDistance, CREEPER_ALERT_DISTANCE);
        // The same rule as the scan, and it has to be here too: this is the gate that actually
        // starts the escape, so leaving it sighted-only would keep the bot standing under fire no
        // matter what the scan returned. A mob that is hitting the player is a threat on that
        // evidence alone.
        if (protectMonsters && nearest != null
                && (hasLineOfSight(ctx, nearest) || justAttackedThePlayer(ctx, nearest))
                && (compare(nearestDistance, monsterComparison, monsterDistance)
                || visibleCreeperBuffer)) {
            rememberHostile(ctx, nearest);
            if (trackedHostileId != nearest.getId()) {
                trackedHostileId = nearest.getId();
                monsterEscapes = 0;
                monsterRecoveryTicks = 0;
                creeperLastBlock = null;
                creeperStallTicks = 0;
                creeperEscapeTicks = 0;
                coverHeight = 0;
                pillarBlock = null;
                pillarTicks = 0;
                pillarSecured = false;
                rangedRetreatTried = false;
                rangedStrafeTicks = 0;
                rangedStrafeLeft = false;
                meleeLastBlock = null;
                meleeStallTicks = 0;
                meleeStrafeTicks = 0;
                meleeStrafeLeft = false;
                endermanShelterBase = null;
                endermanShelterSupport = null;
                endermanShelterRoof = null;
                endermanShelterMaterial = null;
                endermanShelterTicks = 0;
            }
            hostile = nearest;
            // A melee mob below a completed pillar cannot reach the player, but the old
            // priority order kept this monitor in MONSTER mode anyway. That prevented the
            // health branch from ever eating, so a damaged player could stand safely above a
            // zombie until the next path attempt or a stray hit killed it. Let health recovery
            // take the turn only when the geometry really makes eating safe; ranged threats
            // still keep priority unless their line of fire is broken.
            if (protectHealth && compare(ctx.player.getHealth(), healthComparison, healthThreshold)
                    && hasEdibleFood(ctx) && safeToRecoverHealth(ctx, nearest)) {
                threat = Threat.HEALTH;
                safeTicks = 0;
                confirmingSafe = false;
                return true;
            }
            threat = Threat.MONSTER;
            safeTicks = 0;
            confirmingSafe = false;
            return true;
        }
        LivingEntity recentlySeen = recentlyRememberedHostile(ctx);
        if (protectMonsters && recentlySeen != null) {
            // The hostile is allowed to disappear from the ray cast briefly after we place a
            // wall. Releasing the monitor at that exact moment lets the routine walk back into
            // the same shaft while the mob pathfinds around the cover.
            hostile = recentlySeen;
            threat = Threat.MONSTER;
            safeTicks = 0;
            confirmingSafe = false;
            return true;
        }
        if (protectHealth && compare(ctx.player.getHealth(), healthComparison, healthThreshold)) {
            hostile = recentlySeen != null ? recentlySeen : nearest;
            threat = hostile == null ? Threat.HEALTH : Threat.MONSTER;
            safeTicks = 0;
            confirmingSafe = false;
            return true;
        }
        if (threat != Threat.NONE && safeTicks++ < SAFE_CONFIRM_TICKS) {
            confirmingSafe = true;
            return true;
        }
        threat = Threat.NONE;
        confirmingSafe = false;
        trackedHostileId = -1;
        monsterEscapes = 0;
        monsterRecoveryTicks = 0;
        rememberedHostile = null;
        lastHostileSeenTick = Integer.MIN_VALUE;
        coverBase = null;
        coverTicks = 0;
        coverHeight = 0;
        pillarBlock = null;
        pillarTicks = 0;
        pillarSecured = false;
        rangedRetreatTried = false;
        rangedStrafeTicks = 0;
        rangedStrafeLeft = false;
        return false;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (learningEpisode == null || learningEpisode.episodeThreat != threat) {
            finishLearningEpisode(ctx, TaskStatus.SUCCESS);
            learningEpisode = createLearningEpisode(ctx);
            learningEpisode.start(ctx);
        }
        TaskStatus result = learningEpisode.tick(ctx);
        if (result != TaskStatus.RUNNING) {
            finishLearningEpisode(ctx, result);
        }
        return result;
    }

    /** Performs one tick of the currently selected emergency strategy. */
    private TaskStatus tickThreat(BotContext ctx) {
        if (!ctx.player.isAlive()) {
            ctx.input.reset();
            stopRecovery(ctx);
            status = "player died";
            return TaskStatus.FAILED;
        }
        if (confirmingSafe) {
            stopRecovery(ctx);
            if (threat == Threat.FALL
                    && (bucketPlaced || boatPlacements > 0 || boatBoarded || cushionPos != null)) {
                return recoverClutch(ctx);
            }
            status = "confirming the danger is gone";
            return TaskStatus.RUNNING;
        }
        return switch (threat) {
            case AIR -> escapeWater(ctx);
            case LAVA -> escapeLava(ctx);
            case FALL -> fallClutch(ctx);
            case MONSTER -> escapeMonster(ctx);
            case HEALTH -> recoverHealth(ctx);
            case NONE -> TaskStatus.SUCCESS;
        };
    }

    private RecoveryEpisode createLearningEpisode(BotContext ctx) {
        Threat episodeThreat = threat;
        List<String> actions;
        String phase;
        if (episodeThreat == Threat.MONSTER && hostile != null) {
            boolean creeper = hostile instanceof Creeper;
            boolean enderman = isEnderman(hostile);
            boolean ranged = isRangedThreat(hostile);
            boolean canBuild = emergencyMaterial(ctx) != null;
            boolean canFight = InventoryHelper.anyMatch(ctx.player, InventoryHelper::isCombatWeapon);
            actions = SelfPreservationPolicy.monsterActions(
                    creeper, enderman, ranged, canBuild, canFight);
            phase = "threat=monster;kind=" + hostile.getType().getDescriptionId()
                    + ";distance=" + SelfPreservationPolicy.distanceBucket(
                            ctx.player.distanceTo(hostile))
                    + ";health=" + SelfPreservationPolicy.healthBucket(ctx.player.getHealth())
                    + ";build=" + canBuild + ";weapon=" + canFight;
        } else if (episodeThreat == Threat.FALL) {
            Cushion cushion = bestCushion(ctx);
            boolean water = canWaterClutch(ctx);
            boolean boat = canBoatClutch(ctx);
            boolean boatTime = boatHasTime(ctx);
            actions = SelfPreservationPolicy.fallActions(
                    water, cushion != null, boat, boatTime);
            phase = "threat=fall;depth=" + SelfPreservationPolicy.fallBucket(ctx.player.fallDistance)
                    + ";water=" + water + ";cushion=" + (cushion != null)
                    + ";boat=" + boat + ";boat-time=" + boatTime;
        } else {
            actions = List.of(SelfPreservationPolicy.DIRECT);
            phase = "threat=" + episodeThreat.name().toLowerCase()
                    + ";health=" + SelfPreservationPolicy.healthBucket(ctx.player.getHealth());
        }
        LearningContext context = new LearningContext("skill", "self-preservation",
                ctx.level.dimension().identifier().toString(), phase);
        return new RecoveryEpisode(episodeThreat, context, actions);
    }

    private void finishLearningEpisode(BotContext ctx, TaskStatus result) {
        if (learningEpisode == null) {
            return;
        }
        // A release happens between monitor ticks, so publish its successful terminal result before
        // stop() asks the shared learner to score the episode.
        TaskLearning.afterTick(learningEpisode, ctx, result);
        learningEpisode.stop(ctx);
        learningEpisode = null;
        recoveryStrategy = SelfPreservationPolicy.DIRECT;
    }

    private final class RecoveryEpisode implements Task {
        private final Threat episodeThreat;
        private final LearningContext context;
        private final List<String> actions;

        private RecoveryEpisode(Threat episodeThreat, LearningContext context, List<String> actions) {
            this.episodeThreat = episodeThreat;
            this.context = context;
            this.actions = actions == null || actions.isEmpty()
                    ? List.of(SelfPreservationPolicy.DIRECT) : List.copyOf(actions);
        }

        @Override
        public String name() {
            return "Self Preservation " + episodeThreat.name().toLowerCase();
        }

        @Override
        public LearningContext learningContext(BotContext ctx) {
            return context;
        }

        @Override
        public List<String> learningActions(BotContext ctx) {
            return actions;
        }

        @Override
        public void onLearningAction(BotContext ctx, String action) {
            recoveryStrategy = actions.contains(action) ? action : actions.get(0);
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            return tickThreat(ctx);
        }
    }

    private TaskStatus escapeWater(BotContext ctx) {
        stopRecovery(ctx);
        WaterEscape.tickToAir(ctx);
        status = "escaping to air (" + ctx.player.getAirSupply() + " left)";
        return ctx.player.isUnderWater() ? TaskStatus.RUNNING : TaskStatus.SUCCESS;
    }

    private TaskStatus escapeLava(BotContext ctx) {
        if (!ctx.player.isInLava()) {
            status = "escaped lava";
            return TaskStatus.SUCCESS;
        }
        ctx.input.jump = true;
        if (recovery == null) {
            BlockPos safe = nearestSafeGround(ctx);
            if (safe == null) {
                status = "no safe ground near the lava";
                return ++escapeAttempts >= MAX_ESCAPE_ATTEMPTS ? TaskStatus.FAILED : TaskStatus.RUNNING;
            }
            recovery = new GotoTask(new Goals.Near(safe, 0), true, true);
            recovery.start(ctx);
        }
        return tickRecovery(ctx, "escaping lava");
    }

    private TaskStatus escapeMonster(BotContext ctx) {
        if (hostile == null || !hostile.isAlive()) {
            status = "threat gone";
            return TaskStatus.SUCCESS;
        }
        if (hostile instanceof Creeper) {
            return escapeCreeper(ctx);
        }
        if (isEnderman(hostile)) {
            return escapeEnderman(ctx);
        }
        if (SelfPreservationPolicy.COVER_FIRST.equals(recoveryStrategy)) {
            return emergencyMonsterDefense(ctx, "learned cover-first response");
        }
        if (SelfPreservationPolicy.PILLAR_FIRST.equals(recoveryStrategy) && !pillarSecured) {
            EmergencyBuild pillar = buildUp(ctx, emergencyMaterial(ctx));
            if (pillar != EmergencyBuild.UNAVAILABLE) {
                pillarSecured = pillar == EmergencyBuild.SECURED;
                status = pillarSecured
                        ? "learned pillar response secured above " + hostile.getName().getString()
                        : "trying learned pillar response against " + hostile.getName().getString();
                return TaskStatus.RUNNING;
            }
        }
        if (SelfPreservationPolicy.COUNTERATTACK_FIRST.equals(recoveryStrategy)) {
            return emergencyMeleeCombat(ctx);
        }
        // GotoTask can remain RUNNING while its partial route shuffles at a waypoint. That is
        // acceptable for ordinary travel, but a creeper fuse is not a route-search budget: after a
        // short bounded attempt, abandon the route and build cover or fight immediately.
        if (++monsterRecoveryTicks > MAX_MONSTER_RECOVERY_TICKS) {
            return emergencyMonsterDefense(ctx, "retreat timed out");
        }
        if (monsterEscapes >= MAX_ESCAPE_ATTEMPTS) {
            return emergencyMonsterDefense(ctx, "retreat limit reached");
        }
        if (recovery == null) {
            BlockPos away = retreatPoint(ctx, hostile);
            if (away == null) {
                return emergencyMonsterDefense(ctx, "no walking route");
            }
            recovery = new GotoTask(new Goals.Near(away, 2), true, false);
            recovery.start(ctx);
        }
        return tickRecovery(ctx, "escaping " + hostile.getName().getString());
    }

    /**
     * Creepers need a different recovery from skeletons and zombies. A normal GotoTask can spend
     * several ticks reconsidering a route while the fuse is already running, and a route target
     * chosen before the threat was seen can even pull the player sideways around the mob. Keep the
     * player's movement pointed directly away from the creeper, build a wall opportunistically,
     * and only release the monitor once there is both distance and broken line of sight.
     */
    private TaskStatus escapeCreeper(BotContext ctx) {
        stopRecovery(ctx);

        BlockPos currentBlock = ctx.player.blockPosition();
        if (currentBlock.equals(creeperLastBlock)) {
            creeperStallTicks++;
        } else {
            creeperLastBlock = currentBlock;
            creeperStallTicks = 0;
        }

        Vec3 away = ctx.player.position().subtract(hostile.position());
        away = new Vec3(away.x, 0.0, away.z);
        if (away.lengthSqr() < 1.0E-4) {
            away = Vec3.directionFromRotation(0.0F, ctx.player.getYRot() + 180.0F);
            away = new Vec3(away.x, 0.0, away.z);
        } else {
            away = away.normalize();
        }

        Block material = emergencyMaterial(ctx);

        // Ranged mobs punish the time spent placing a wall in the open. If a weapon is already
        // carried, close under a zig-zag immediately and let the normal attack check finish the
        // fight; repeatedly rebuilding cover while the skeleton keeps a firing lane was slower
        // than the arrows. Cover remains the fallback for a player with no weapon at all.
        if (isRangedThreat(hostile)) {
            ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
            if (InventoryHelper.isCombatWeapon(held)) {
                return evadeRangedThreat(ctx);
            }
            int weaponSlot = InventoryHelper.equipCombatWeapon(ctx);
            if (weaponSlot >= 0) {
                status = "equipping a weapon to fight " + hostile.getName().getString();
                return TaskStatus.RUNNING;
            }
        }

        EmergencyBuild cover = buildCover(ctx, material);
        if (SelfPreservationPolicy.COVER_FIRST.equals(recoveryStrategy)
                && cover == EmergencyBuild.BUILDING) {
            status = "building learned cover before retreating from Creeper";
            return TaskStatus.RUNNING;
        }
        if (cover == EmergencyBuild.SECURED && !(hostile instanceof Creeper)) {
            coverBase = null;
            coverTicks = 0;
            coverHeight = 0;
        }

        // A placement failure is deterministic at the current spot. Stop the direct sprint and
        // let the emergency branch choose a pillar or weapon immediately; continuing to hold W
        // is how a Creeper reaches the player while the cover support is being reconsidered.
        if (cover == EmergencyBuild.SECURED || cover == EmergencyBuild.UNAVAILABLE) {
            return emergencyMonsterDefense(ctx, "direct Creeper cover handoff");
        }

        // The direct sprint is intentionally cheap, but it cannot solve a one-block lip, a
        // water edge, or a partial route waypoint. Once the player has failed to change blocks for
        // a few ticks, hand the same hostile to the full emergency defense instead of waiting out
        // the fuse with W held against the obstruction.
        if (creeperStallTicks >= MAX_CREEPER_STALL_TICKS) {
            return emergencyMonsterDefense(ctx, "direct creeper retreat blocked");
        }

        // Placement aims at the support face, so movement is applied after it and wins for this
        // tick. Jumping lets the player clear a one-block lip instead of standing in the fuse.
        Vec3 safeAway = safeEmergencyDirection(ctx, away);
        if (safeAway == null) {
            return emergencyMonsterDefense(ctx, "no safe emergency step");
        }
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, ctx.player.position().add(safeAway));
        ctx.input.forward = true;
        ctx.input.sprint = true;
        ctx.input.jump = needsEmergencyJump(ctx, safeAway);

        double distance = ctx.player.distanceTo(hostile);
        boolean concealed = !hasLineOfSight(ctx, hostile);
        if (concealed && distance >= CREEPER_SAFE_DISTANCE) {
            status = "behind cover from Creeper";
        } else if (cover == EmergencyBuild.BUILDING) {
            status = "sprinting away while building cover from Creeper";
        } else if (cover == EmergencyBuild.SECURED) {
            status = "sprinting away behind cover from Creeper";
        } else {
            status = "sprinting directly away from Creeper";
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus recoverHealth(BotContext ctx) {
        if (recovery == null && !hasEdibleFood(ctx)) {
            // No food is a resource shortage, not a reason to release control in a hostile
            // tunnel. Keep the monitor alive so a newly visible mob is handled on the next scan.
            ctx.input.reset();
            status = hostile != null && hostile.isAlive()
                    ? "no food - defending from " + hostile.getName().getString()
                    : "no food - staying safe until health recovers";
            if (hostile != null && hostile.isAlive()) {
                threat = Threat.MONSTER;
                return emergencyMonsterDefense(ctx, "no food for recovery");
            }
            return TaskStatus.RUNNING;
        }
        if (recovery == null) {
            recovery = new EatTask(stack -> true, 20);
            recovery.start(ctx);
        }
        TaskStatus result = recovery.tick(ctx);
        status = "recovering health - " + recovery.status();
        if (result == TaskStatus.FAILED) {
            stopRecovery(ctx);
            ctx.input.reset();
            if (hostile != null && hostile.isAlive()) {
                // Latched before delegating, and required to be clear by the health branch over
                // there. Without it the two methods call each other forever: the eat failed but the
                // food is still in the bag, so "can I recover?" keeps saying yes while "did it
                // work?" keeps saying no - which is a StackOverflowError, not a slow bot.
                foodRecoveryFailed = true;
                threat = Threat.MONSTER;
                return emergencyMonsterDefense(ctx, "no food for recovery");
            }
            status = "no food - staying safe until health recovers";
            return TaskStatus.RUNNING;
        }
        if (result == TaskStatus.SUCCESS) {
            stopRecovery(ctx);
            status = "waiting for health regeneration";
            if (++healthWaitTicks > MAX_HEALTH_WAIT_TICKS) {
                status = "health did not recover";
                return TaskStatus.FAILED;
            }
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus tickRecovery(BotContext ctx, String action) {
        TaskStatus result = recovery.tick(ctx);
        status = action + (recovery.status().isBlank() ? "" : " - " + recovery.status());
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        stopRecovery(ctx);
        if (result == TaskStatus.SUCCESS) {
            if (threat == Threat.MONSTER) {
                // A following mob can make every fresh GotoTask look successful while the bot
                // still flees forever. Keep this count in the monitor, above the rebuilt route,
                // and switch to cover or combat after a few distinct retreats.
                monsterEscapes++;
                monsterRecoveryTicks = 0;
            } else {
                escapeAttempts = 0;
            }
            return TaskStatus.RUNNING;
        }
        if (threat == Threat.MONSTER) {
            return emergencyMonsterDefense(ctx, "retreat route blocked");
        }
        return ++escapeAttempts >= MAX_ESCAPE_ATTEMPTS ? TaskStatus.FAILED : TaskStatus.RUNNING;
    }

    /**
     * A route can be impossible in a one-wide mine shaft. A monster must not turn that into a
     * routine failure: first build a two-high wall, then try a one-block pillar, then fight with
     * whatever the player is holding until there is a new opening.
     */
    private TaskStatus emergencyMonsterDefense(BotContext ctx, String reason) {
        stopRecovery(ctx);
        if (hostile == null || !hostile.isAlive()) {
            status = "threat gone";
            return TaskStatus.SUCCESS;
        }

        // Swimming while a hostile is present is not a safe health-recovery state. The player can
        // be unable to place a wall because every adjacent face is water, and eating keeps the
        // monitor cycling through a stationary animation while drowned mobs close in. Surface
        // first; combat resumes once the eyes are in breathable air.
        if (ctx.player.isUnderWater()) {
            WaterEscape.tickToAir(ctx);
            status = "escaping to air while evading " + hostile.getName().getString();
            return TaskStatus.RUNNING;
        }

        if (protectHealth && !foodRecoveryFailed
                && compare(ctx.player.getHealth(), healthComparison, healthThreshold)
                && hasEdibleFood(ctx) && safeToRecoverHealth(ctx, hostile)) {
            threat = Threat.HEALTH;
            return recoverHealth(ctx);
        }

        // A creeper is an explosive threat, not a normal melee mob. Once the route is blocked,
        // standing still to place a pillar leaves the player inside the blast radius. Keep the
        // emergency branch moving horizontally and change the strafe side when the direct path
        // stalls; cover remains the opportunistic first attempt in escapeCreeper().
        if (hostile instanceof Creeper) {
            return emergencyCreeperRetreat(ctx, reason);
        }

        if (isEnderman(hostile)) {
            return defendEnderman(ctx, reason);
        }

        // A wall or pillar is useful only while there is room to place it. Once a melee hostile
        // is already inside reach, spending the next ticks on BlockPlacer leaves the player
        // stationary at exactly the distance where a zombie or polar bear can keep hitting. Keep
        // the weapon handoff and retreat in one bounded action instead; building can resume after
        // the player has created space.
        if (!(hostile instanceof Creeper) && !isRangedThreat(hostile)
                && ctx.player.distanceTo(hostile) <= EMERGENCY_ATTACK_REACH + 0.8) {
            return emergencyMeleeCombat(ctx);
        }

        Block material = emergencyMaterial(ctx);
        EmergencyBuild cover = buildCover(ctx, material);
        if (cover == EmergencyBuild.BUILDING) {
            status = "building cover from " + hostile.getName().getString();
            return TaskStatus.RUNNING;
        }
        if (cover == EmergencyBuild.SECURED) {
            // A creeper fuse cannot tolerate another ordinary GotoTask. Stay on the emergency
            // side of the wall and either climb or fight; route replanning is safe for slower
            // hostiles, but can leave a creeper at point-blank range while the path is rebuilt.
            if (hostile instanceof Creeper) {
                // Keep the wall target so buildCover can finish its bounded second block on the
                // next tick. Clearing it here made the bot place a new one-block wall every tick.
            } else {
            // A completed wall is only a momentary shield. Returning "blocked" forever leaves
            // the player standing in the skeleton's firing lane (a one-block wall can still be
            // shot around or over). Use the new cover as a chance to get a real retreat route;
            // if that route is unavailable, the weapon handoff below gets a chance to fight.
            BlockPos away = retreatPoint(ctx, hostile);
            if (away != null && !rangedRetreatTried) {
                rangedRetreatTried = isRangedThreat(hostile);
                recovery = new GotoTask(new Goals.Near(away, 2), true, false);
                recovery.start(ctx);
                coverBase = null;
                coverTicks = 0;
                return tickRecovery(ctx, "retreating behind emergency cover");
            }
            coverBase = null;
            coverTicks = 0;
            }
        }

        // Finish the physical Creeper defense before selecting a weapon. BlockPlacer equips the
        // building material, so doing this after the weapon handoff creates a sword/cobblestone
        // oscillation and leaves the player standing in the fuse.
        if (hostile instanceof Creeper && !pillarSecured) {
            EmergencyBuild pillar = buildUp(ctx, material);
            if (pillar != EmergencyBuild.UNAVAILABLE) {
                if (pillar == EmergencyBuild.SECURED) {
                    pillarSecured = true;
                }
                status = pillar == EmergencyBuild.SECURED
                        ? "built up above Creeper"
                        : "building upward to escape Creeper";
                return TaskStatus.RUNNING;
            }
        }

        // No safe route and nothing useful to place. Equip a carried weapon before spending more
        // emergency blocks. A
        // route task can leave the sword in the main inventory after mining or collecting a drop;
        // swinging the currently selected block/tool in that state burns the few emergency ticks
        // available and lets a skeleton keep shooting. This monitor is shared by every routine,
        // so the emergency handoff must repair the hand state here rather than rely on callers.
        ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!InventoryHelper.isCombatWeapon(held)) {
            int weaponSlot = InventoryHelper.equipCombatWeapon(ctx);
            if (weaponSlot >= 0) {
                status = "equipping a weapon to fight " + hostile.getName().getString();
                return TaskStatus.RUNNING;
            }
        }

        // Do not give up; defend with the equipped weapon (or, only if none exists, the current
        // item) until the hostile dies, moves, or a path opens. Attack before building a pillar
        // when the mob has already reached the player.
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, hostile.getEyePosition());
        if (ctx.player.distanceTo(hostile) <= EMERGENCY_ATTACK_REACH
                && ctx.player.getAttackStrengthScale(0.0F) >= 1.0F
                && ctx.look.isLookingAt(ctx.player, hostile.getEyePosition(), 15.0F)) {
            ctx.gameMode.attack(ctx.player, hostile);
            ctx.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            status = "fighting " + hostile.getName().getString() + " from emergency cover";
            return TaskStatus.RUNNING;
        }

        // A pillar is not cover from arrows: once it is secured, staying still just gives a
        // skeleton/stray a stationary target. Try one real retreat above the route task; after
        // that route has failed, keep moving laterally while jumping so the hostile cannot keep
        // a fixed firing lane. This also gives the player a chance to round the terrain instead
        // of alternating between a block and a sword forever.
        if (isRangedThreat(hostile)) {
            return evadeRangedThreat(ctx);
        }

        EmergencyBuild pillar = buildUp(ctx, material);
        if (pillar != EmergencyBuild.UNAVAILABLE) {
            status = pillar == EmergencyBuild.SECURED
                    ? "built up above " + hostile.getName().getString()
                    : "building upward to escape " + hostile.getName().getString();
            return TaskStatus.RUNNING;
        }

        // A melee mob outside attack reach is not a reason to stand still. This fallback is
        // deliberately movement-only: the attack branch above remains responsible for swings,
        // while this branch keeps a depleted builder from being pinned in place forever.
        return evadeMeleeThreat(ctx);
    }

    /** Strike at close range while backpedalling so a melee hostile cannot pin the player in place. */
    private TaskStatus emergencyMeleeCombat(BotContext ctx) {
        stopRecovery(ctx);

        BlockPos currentBlock = ctx.player.blockPosition();
        if (currentBlock.equals(meleeLastBlock)) {
            meleeStallTicks++;
        } else {
            meleeLastBlock = currentBlock;
            meleeStallTicks = 0;
        }

        ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!InventoryHelper.isCombatWeapon(held)) {
            int weaponSlot = InventoryHelper.equipCombatWeapon(ctx);
            if (weaponSlot >= 0) {
                status = "equipping a weapon while retreating from " + hostile.getName().getString();
                // Equip changes the selected hotbar slot immediately. Re-read the hand so the
                // first emergency tick can swing instead of waiting through another mob hit.
                held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
            }
        }

        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, hostile.getEyePosition());
        // A sword is preferable, but a pickaxe, block, or even an empty hand is safer than
        // standing still while a zombie is already in reach. The normal game mode attack call
        // applies the held item's damage when there is one and otherwise falls back to fists.
        // The monitor has already selected this living hostile from the shared, line-of-sight
        // threat scan.  Do not make the emergency swing depend on the smoothed camera having
        // reached the target on the same tick: at point-blank range that extra visual gate leaves
        // a zombie free to hit us while the retreat turn is still settling.  The server validates
        // the entity reach on the attack packet, so an out-of-reach packet is harmless, while a
        // charged in-reach swing is the only useful action when backpedalling is blocked.
        boolean attacked = ctx.player.getAttackStrengthScale(0.0F) >= 1.0F;
        if (attacked) {
            ctx.gameMode.attack(ctx.player, hostile);
            ctx.player.swing(InteractionHand.MAIN_HAND);
        }

        // Backpedalling is the safest default, but it can be aimed directly into the wall of a
        // one-wide shaft. After a short unchanged-block window, strafe and alternate sides so the
        // mob cannot pin the player at the same coordinates. Jumping is retained to clear a lip;
        // the attack above continues on every cooldown-ready tick while space is created.
        Vec3 desired = awayFrom(ctx, hostile);
        if (meleeStallTicks >= 3) {
            if (++meleeStrafeTicks >= 8) {
                meleeStrafeTicks = 0;
                meleeStrafeLeft = !meleeStrafeLeft;
            }
            desired = meleeStrafeLeft
                    ? new Vec3(-desired.z, 0.0, desired.x)
                    : new Vec3(desired.z, 0.0, -desired.x);
        }
        Vec3 safeDirection = safeEmergencyDirection(ctx, desired);
        if (safeDirection != null) {
            ctx.look.urgent();
        ctx.look.lookAt(ctx.player, ctx.player.position().add(safeDirection));
            ctx.input.forward = true;
            ctx.input.sprint = true;
            ctx.input.jump = needsEmergencyJump(ctx, safeDirection);
        } else {
            ctx.input.reset();
        }
        status = safeDirection == null
                ? "holding safe ground while fighting " + hostile.getName().getString()
                : attacked
                ? (meleeStallTicks >= 3
                        ? "fighting " + hostile.getName().getString() + " while strafing"
                        : "fighting " + hostile.getName().getString() + " while retreating")
                : (meleeStallTicks >= 3
                        ? "strafing before striking " + hostile.getName().getString()
                        : "retreating before striking " + hostile.getName().getString());
        return TaskStatus.RUNNING;
    }

    /** Retreat horizontally from a creeper when a route or cover action has stalled. */
    private TaskStatus emergencyCreeperRetreat(BotContext ctx, String reason) {
        stopRecovery(ctx);
        ctx.input.reset();

        Vec3 away = ctx.player.position().subtract(hostile.position());
        away = new Vec3(away.x, 0.0, away.z);
        if (away.lengthSqr() < 1.0E-4) {
            away = Vec3.directionFromRotation(0.0F, ctx.player.getYRot() + 180.0F);
            away = new Vec3(away.x, 0.0, away.z);
        } else {
            away = away.normalize();
        }

        Vec3 safeAway = safeEmergencyDirection(ctx, away);
        if (safeAway == null) {
            return emergencyCreeperFallback(ctx, reason);
        }
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, ctx.player.position().add(safeAway));
        ctx.input.forward = true;
        ctx.input.sprint = true;
        ctx.input.jump = needsEmergencyJump(ctx, safeAway);

        // Switch the preferred side periodically so a single blocked direction cannot turn this
        // into a standstill; safeEmergencyDirection still rejects every unsafe candidate.
        creeperEscapeTicks++;

        double distance = ctx.player.distanceTo(hostile);
        boolean concealed = !hasLineOfSight(ctx, hostile);
        status = concealed && distance >= CREEPER_SAFE_DISTANCE
                ? "behind cover from Creeper"
                : "retreating from Creeper - " + reason;
        return TaskStatus.RUNNING;
    }

    /**
     * A cliff edge or a one-block pocket can leave no safe horizontal step. Standing still is not
     * a recovery: a Creeper will finish its fuse before the terrain changes. Swing once for
     * knockback, then spend the carried blocks on a pillar under the player so the next tick has
     * a real escape surface.
     */
    private TaskStatus emergencyCreeperFallback(BotContext ctx, String reason) {
        ctx.input.reset();
        if (hostile == null || !hostile.isAlive()) {
            status = "threat gone";
            return TaskStatus.SUCCESS;
        }

        ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!InventoryHelper.isCombatWeapon(held)) {
            int weaponSlot = InventoryHelper.equipCombatWeapon(ctx);
            if (weaponSlot >= 0) {
                held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
            }
        }

        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, hostile.getEyePosition());
        boolean attacked = ctx.player.distanceTo(hostile) <= EMERGENCY_ATTACK_REACH
                && ctx.player.getAttackStrengthScale(0.0F) >= 1.0F;
        if (attacked) {
            ctx.gameMode.attack(ctx.player, hostile);
            ctx.player.swing(InteractionHand.MAIN_HAND);
        }

        Block material = emergencyMaterial(ctx);
        if (material != null) {
            EmergencyBuild pillar = buildUp(ctx, material);
            if (pillar != EmergencyBuild.UNAVAILABLE) {
                if (pillar == EmergencyBuild.SECURED) {
                    pillarSecured = true;
                }
                status = pillar == EmergencyBuild.SECURED
                        ? "built upward while evading Creeper"
                        : "building upward while evading Creeper";
                return TaskStatus.RUNNING;
            }
        }

        status = attacked
                ? "fighting Creeper from unsafe ground - " + reason
                : "no safe step or pillar while evading Creeper - " + reason;
        return TaskStatus.RUNNING;
    }

    /** Move away from a melee hostile when no route or building material remains. */
    private TaskStatus evadeMeleeThreat(BotContext ctx) {
        stopRecovery(ctx);
        Vec3 away = ctx.player.position().subtract(hostile.position());
        away = new Vec3(away.x, 0.0, away.z);
        if (away.lengthSqr() < 1.0E-4) {
            away = Vec3.directionFromRotation(0.0F, ctx.player.getYRot() + 180.0F);
            away = new Vec3(away.x, 0.0, away.z);
        } else {
            away = away.normalize();
        }
        Vec3 safeAway = safeEmergencyDirection(ctx, away);
        if (safeAway == null) {
            ctx.input.reset();
            status = "holding safe ground while evading " + hostile.getName().getString();
            return TaskStatus.RUNNING;
        }
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, ctx.player.position().add(safeAway));
        ctx.input.forward = true;
        ctx.input.sprint = true;
        ctx.input.jump = needsEmergencyJump(ctx, safeAway);
        status = "moving away from " + hostile.getName().getString()
                + " without building material";
        return TaskStatus.RUNNING;
    }

    private TaskStatus evadeRangedThreat(BotContext ctx) {
        stopRecovery(ctx);
        if (++rangedStrafeTicks >= RANGED_STRAFE_SWITCH_TICKS) {
            rangedStrafeTicks = 0;
            rangedStrafeLeft = !rangedStrafeLeft;
        }
        double distance = ctx.player.distanceTo(hostile);
        Vec3 toHostile = hostile.position().subtract(ctx.player.position());
        toHostile = new Vec3(toHostile.x, 0.0, toHostile.z);
        if (toHostile.lengthSqr() < 1.0E-4) {
            toHostile = Vec3.directionFromRotation(0.0F, ctx.player.getYRot());
            toHostile = new Vec3(toHostile.x, 0.0, toHostile.z);
        } else {
            toHostile = toHostile.normalize();
        }
        Vec3 strafe = rangedStrafeLeft
                ? new Vec3(-toHostile.z, 0.0, toHostile.x)
                : new Vec3(toHostile.z, 0.0, -toHostile.x);
        Vec3 desired = distance > EMERGENCY_ATTACK_REACH + 0.4
                ? toHostile.add(strafe).normalize()
                : strafe;
        if (distance > EMERGENCY_ATTACK_REACH + 0.4) {
            // A lateral-only strafe survives arrows but never reaches the shooter. Close while
            // changing the firing angle, then the attack check above takes over at sword range.
            desired = toHostile.add(strafe).normalize();
        } else if (ctx.player.getAttackStrengthScale(0.0F) < 0.9F) {
            // Create space while the sword recovers instead of standing in melee range.
            desired = toHostile.scale(-1.0).add(strafe).normalize();
        }
        Vec3 safeDirection = safeEmergencyDirection(ctx, desired);
        if (safeDirection == null) {
            ctx.input.reset();
            status = "holding safe ground while evading " + hostile.getName().getString();
            return TaskStatus.RUNNING;
        }
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, ctx.player.position().add(safeDirection));
        ctx.input.forward = true;
        ctx.input.sprint = true;
        ctx.input.jump = needsEmergencyJump(ctx, safeDirection);
        status = "strafing out of arrow line from " + hostile.getName().getString();
        return TaskStatus.RUNNING;
    }

    private enum EmergencyBuild { BUILDING, SECURED, UNAVAILABLE }

    private EmergencyBuild buildCover(BotContext ctx, Block material) {
        if (material == null) {
            return EmergencyBuild.UNAVAILABLE;
        }
        if (coverBase == null) {
            Direction toward = Direction.getApproximateNearest(
                    hostile.getX() - ctx.player.getX(), 0.0, hostile.getZ() - ctx.player.getZ());
            if (!toward.getAxis().isHorizontal()) {
                return EmergencyBuild.UNAVAILABLE;
            }
            coverBase = ctx.player.blockPosition().relative(toward);
            coverTicks = 0;
            coverHeight = 0;
        }

        if (coverHeight >= 2) {
            return EmergencyBuild.SECURED;
        }
        BlockPos next = coverBase.above(coverHeight);
        if (!BlockPlacer.isReplaceable(ctx, next)) {
            return coverHeight > 0 ? EmergencyBuild.SECURED : EmergencyBuild.UNAVAILABLE;
        }
        if (++coverTicks > EMERGENCY_BUILD_TICKS) {
            ctx.debug.placement(next, material.getName().getString(), "emergency cover timed out");
            return EmergencyBuild.UNAVAILABLE;
        }
        if (!BlockPlacer.canPlaceAt(ctx, next)) {
            ctx.debug.placement(next, material.getName().getString(),
                    "no support or placement space");
            return EmergencyBuild.UNAVAILABLE;
        }
        BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(ctx, material, next);
        if (!placement.isTransient()
                && placement != BlockPlacer.PlacementResult.PLACED
                && placement != BlockPlacer.PlacementResult.ALREADY_PRESENT) {
            return EmergencyBuild.UNAVAILABLE;
        }
        if (placement == BlockPlacer.PlacementResult.PLACED
                || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
            coverHeight++;
            return coverHeight >= 2 ? EmergencyBuild.SECURED : EmergencyBuild.BUILDING;
        }
        return EmergencyBuild.BUILDING;
    }

    private EmergencyBuild buildUp(BotContext ctx, Block material) {
        if (material == null) {
            return EmergencyBuild.UNAVAILABLE;
        }
        if (pillarBlock != null && !BlockPlacer.isReplaceable(ctx, pillarBlock)) {
            return EmergencyBuild.SECURED;
        }
        if (++pillarTicks > EMERGENCY_BUILD_TICKS) {
            ctx.debug.placement(pillarBlock, material.getName().getString(),
                    "emergency pillar timed out");
            return EmergencyBuild.UNAVAILABLE;
        }
        if (pillarBlock == null) {
            pillarBlock = ctx.player.blockPosition();
        }
        // BlockPlacer asks the player to jump clear when this is their feet block, then places into
        // the space below them on a later tick. This is a normal one-block pillar, not a teleport.
        ctx.input.jump = true;
        BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(ctx, material, pillarBlock);
        if (!placement.isTransient()
                && placement != BlockPlacer.PlacementResult.PLACED
                && placement != BlockPlacer.PlacementResult.ALREADY_PRESENT) {
            return EmergencyBuild.UNAVAILABLE;
        }
        return placement == BlockPlacer.PlacementResult.PLACED
                || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT
                ? EmergencyBuild.SECURED : EmergencyBuild.BUILDING;
    }

    /** Keep an Enderman encounter safe without looking at or attacking the Enderman. */
    private TaskStatus escapeEnderman(BotContext ctx) {
        stopRecovery(ctx);
        ctx.input.reset();
        if (hasEndermanShelter(ctx, ctx.player.blockPosition())) {
            lookAtSafeGround(ctx);
            status = "under two-block shelter from Enderman";
            return TaskStatus.RUNNING;
        }
        return defendEnderman(ctx, "no shelter yet");
    }

    private TaskStatus defendEnderman(BotContext ctx, String reason) {
        stopRecovery(ctx);
        ctx.input.reset();
        BlockPos feet = ctx.player.blockPosition();
        if (endermanShelterBase == null || !endermanShelterBase.equals(feet)) {
            endermanShelterBase = feet;
            endermanShelterSupport = null;
            endermanShelterRoof = feet.above(2);
            endermanShelterMaterial = null;
            endermanShelterTicks = 0;
        }
        if (hasEndermanShelter(ctx, feet)) {
            lookAtSafeGround(ctx);
            status = "under two-block shelter from Enderman";
            return TaskStatus.RUNNING;
        }
        if (++endermanShelterTicks > MAX_ENDERMAN_SHELTER_TICKS) {
            // A failed roof is deterministic here. Move only along a visibly safe direction and
            // keep the camera below the Enderman instead of falling into the normal attack path.
            Vec3 away = awayFrom(ctx, hostile);
            Vec3 safeAway = safeEmergencyDirection(ctx, away);
            if (safeAway != null) {
                ctx.look.lookAt(ctx.player, ctx.player.position().add(safeAway.scale(0.75)));
                ctx.input.forward = true;
                ctx.input.sprint = true;
                ctx.input.jump = needsEmergencyJump(ctx, safeAway);
                status = "escaping Enderman without eye contact - " + reason;
            } else {
                lookAtSafeGround(ctx);
                status = "no safe Enderman shelter or step - " + reason;
            }
            return TaskStatus.RUNNING;
        }
        if (endermanShelterMaterial == null) {
            endermanShelterMaterial = BlockPlacer.findSolidMaterial(ctx);
            if (endermanShelterMaterial == null) {
                status = "no solid block for Enderman shelter";
                return TaskStatus.RUNNING;
            }
        }
        if (endermanShelterRoof == null) {
            endermanShelterRoof = feet.above(2);
        }
        if (BlockPlacer.isReplaceable(ctx, endermanShelterRoof)
                && BlockPlacer.canPlaceAt(ctx, endermanShelterRoof)) {
            BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(
                    ctx, endermanShelterMaterial, endermanShelterRoof);
            if (placement == BlockPlacer.PlacementResult.PLACED
                    || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
                lookAtSafeGround(ctx);
                status = "building Enderman shelter roof";
                return TaskStatus.RUNNING;
            }
        }
        if (endermanShelterSupport == null) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = feet.relative(direction).above();
                if (BlockPlacer.canPlaceAt(ctx, candidate)) {
                    endermanShelterSupport = candidate;
                    break;
                }
            }
        }
        if (endermanShelterSupport != null && MovementHelper.isPassable(ctx.level, endermanShelterSupport)) {
            BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(
                    ctx, endermanShelterMaterial, endermanShelterSupport);
            lookAtSafeGround(ctx);
            status = placement == BlockPlacer.PlacementResult.PLACED
                    || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT
                    ? "building Enderman shelter support"
                    : "placing Enderman shelter support";
            return TaskStatus.RUNNING;
        }
        lookAtSafeGround(ctx);
        status = "avoiding Enderman eye contact while finding shelter";
        return TaskStatus.RUNNING;
    }

    private static boolean hasEndermanShelter(BotContext ctx, BlockPos feet) {
        return MovementHelper.isPassable(ctx.level, feet)
                && MovementHelper.isPassable(ctx.level, feet.above())
                && !MovementHelper.isPassable(ctx.level, feet.above(2));
    }

    private static void lookAtSafeGround(BotContext ctx) {
        ctx.look.lookAt(ctx.player, ctx.player.position().add(0.0, -1.0, 0.0));
    }

    private static Block emergencyMaterial(BotContext ctx) {
        Block fallback = null;
        for (Block candidate : BlockCatalog.buildingBlocks()) {
            Item item = candidate.asItem();
            if (item == null) {
                continue;
            }
            int slot = InventoryHelper.findSlot(ctx.player, stack -> stack.is(item));
            if (slot < 0) {
                continue;
            }
            if (fallback == null) {
                fallback = candidate;
            }
            // Keep a tiny stack in reserve for a later staircase or bridge when another local
            // building block is available. This also prevents a timed-out three-block cobble
            // stack from being selected again while a full dirt stack sits in the inventory.
            if (ctx.player.getInventory().getItem(slot).getCount() >= 4) {
                return candidate;
            }
        }
        return fallback;
    }

    /** Return a horizontal direction whose next local step is visibly standable. */
    private static Vec3 safeEmergencyDirection(BotContext ctx, Vec3 desired) {
        if (!ctx.player.onGround() || ctx.player.isInWater() || ctx.player.isInLava()) {
            return null;
        }
        Vec3 horizontal = new Vec3(desired.x, 0.0, desired.z);
        if (horizontal.lengthSqr() < 1.0E-4) {
            return null;
        }
        double baseAngle = Math.atan2(horizontal.z, horizontal.x);
        double[] turns = {0.0, Math.PI / 4.0, -Math.PI / 4.0, Math.PI / 2.0,
                -Math.PI / 2.0, 3.0 * Math.PI / 4.0, -3.0 * Math.PI / 4.0, Math.PI};
        BlockPos feet = ctx.player.blockPosition();
        for (double turn : turns) {
            Vec3 direction = new Vec3(Math.cos(baseAngle + turn), 0.0,
                    Math.sin(baseAngle + turn));
            int dx = direction.x > 0.25 ? 1 : direction.x < -0.25 ? -1 : 0;
            int dz = direction.z > 0.25 ? 1 : direction.z < -0.25 ? -1 : 0;
            if (dx == 0 && dz == 0) {
                continue;
            }
            BlockPos adjacent = feet.offset(dx, 0, dz);
            if (safeEmergencyLanding(ctx, adjacent)
                    || safeEmergencyLanding(ctx, adjacent.below())
                    || safeEmergencyLanding(ctx, adjacent.above())) {
                return direction;
            }
        }
        return null;
    }

    private static boolean safeEmergencyLanding(BotContext ctx, BlockPos feet) {
        return ctx.level.hasChunkAt(feet)
                && MovementHelper.canStandAt(ctx.level, feet, false)
                && !MovementHelper.nearLava(ctx.level, feet);
    }

    private static boolean needsEmergencyJump(BotContext ctx, Vec3 direction) {
        BlockPos feet = ctx.player.blockPosition();
        int dx = direction.x > 0.25 ? 1 : direction.x < -0.25 ? -1 : 0;
        int dz = direction.z > 0.25 ? 1 : direction.z < -0.25 ? -1 : 0;
        if (dx == 0 && dz == 0) {
            return false;
        }
        BlockPos adjacent = feet.offset(dx, 0, dz);
        return !safeEmergencyLanding(ctx, adjacent)
                && safeEmergencyLanding(ctx, adjacent.above());
    }

    private static Vec3 awayFrom(BotContext ctx, LivingEntity entity) {
        Vec3 away = ctx.player.position().subtract(entity.position());
        away = new Vec3(away.x, 0.0, away.z);
        if (away.lengthSqr() < 1.0E-4) {
            away = Vec3.directionFromRotation(0.0F, ctx.player.getYRot() + 180.0F);
            return new Vec3(away.x, 0.0, away.z);
        }
        return away.normalize();
    }

    private static boolean isRangedThreat(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        String id = entity.getType().getDescriptionId();
        return id.endsWith(".skeleton") || id.endsWith(".stray") || id.endsWith(".bogged")
                || id.endsWith(".pillager") || id.endsWith(".blaze") || id.endsWith(".ghast")
                || id.endsWith(".shulker") || id.endsWith(".vex") || id.endsWith(".guardian")
                || id.endsWith(".elder_guardian");
    }

    private static boolean isEnderman(LivingEntity entity) {
        return entity != null && entity.getType() == EntityType.ENDERMAN;
    }

    private LivingEntity nearestThreat(BotContext ctx) {
        if (lastEntityScanTick != Integer.MIN_VALUE
                && ctx.player.tickCount - lastEntityScanTick < ENTITY_SCAN_INTERVAL
                && (cachedNearest == null || cachedNearest.isAlive())) {
            return cachedNearest;
        }
        lastEntityScanTick = ctx.player.tickCount;
        AABB box = ctx.player.getBoundingBox().inflate(THREAT_SCAN_RADIUS);
        // Line of sight decides whether a mob that has done nothing yet is worth reacting to. It
        // must not decide whether one that is *currently shooting* counts, which is what ANDing it
        // over everything did: a skeleton firing from behind a rise was filtered out of the threat
        // list entirely, so the bot stood there being shot down to two hearts as though not seeing
        // the archer meant the archer could not see it. Being hit is knowing.
        List<Entity> found = ctx.level.getEntities(ctx.player, box, entity ->
                entity instanceof LivingEntity living && living.isAlive()
                        && (isDangerousHostile(entity)
                                || (isNeutralUntilProvoked(entity) && justAttackedThePlayer(ctx, living))
                                || isRecentPlayerAttacker(ctx, living))
                        && (hasLineOfSight(ctx, living) || justAttackedThePlayer(ctx, living)));
        cachedNearest = found.stream()
                .map(LivingEntity.class::cast)
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(ctx.player)))
                .orElse(null);
        return cachedNearest;
    }

    /**
     * Safety threats include hostile mobs and neutral animals that become lethal when provoked.
     * Polar bears do not implement vanilla's Enemy marker, so leaving this classification to
     * that marker alone lets one kill an unattended player without ever entering the monitor.
     */
    private static boolean isDangerousHostile(Entity entity) {
        return entity instanceof Enemy;
    }

    /**
     * A neutral animal that has not done anything yet.
     *
     * <p>Polar bears were classed as permanently dangerous so that one could not maul an unattended
     * player. That is the wrong shape for a mob that ignores you until provoked: the bot walled
     * itself in beside a bear that was minding its own business and then stayed there, because the
     * bear never stopped being a "threat" and so the escape never finished. Being left alone is the
     * normal case.
     *
     * <p>They are still handled the moment one actually attacks - {@link #justAttackedThePlayer}
     * catches that whether or not the bear is in sight - so the protection that mattered survives
     * and the standing around does not.
     */
    private static boolean isNeutralUntilProvoked(Entity entity) {
        return entity.getType().getDescriptionId().endsWith(".polar_bear");
    }

    private void rememberHostile(BotContext ctx, LivingEntity entity) {
        rememberedHostile = entity;
        lastHostileSeenTick = ctx.player.tickCount;
    }

    private LivingEntity recentlyRememberedHostile(BotContext ctx) {
        if (rememberedHostile == null || !rememberedHostile.isAlive()
                || lastHostileSeenTick == Integer.MIN_VALUE
                || ctx.player.tickCount - lastHostileSeenTick > HOSTILE_MEMORY_TICKS) {
            return null;
        }
        double releaseDistance = Math.min(THREAT_SCAN_RADIUS,
                Math.max(monsterDistance + 8.0, 16.0));
        return ctx.player.distanceToSqr(rememberedHostile) <= releaseDistance * releaseDistance
                ? rememberedHostile : null;
    }

    private static boolean hasEdibleFood(BotContext ctx) {
        return InventoryHelper.findSlot(ctx.player,
                stack -> stack.has(DataComponents.FOOD)) >= 0;
    }

    /**
     * A low-health player may eat while a melee mob is trapped below a real two-block rise.
     * Ranged mobs are allowed only when their line of fire is broken or they are already far
     * enough away that the eating animation is not an immediate death sentence.
     */
    private static boolean safeToRecoverHealth(BotContext ctx, LivingEntity hostile) {
        if (hostile == null || !hostile.isAlive()) {
            return true;
        }
        if (ctx.player.isInWater() || ctx.player.isUnderWater()) {
            return false;
        }
        double distance = ctx.player.distanceTo(hostile);
        if (isRangedThreat(hostile)) {
            return distance > 16.0 || !hasLineOfSight(ctx, hostile);
        }
        BlockPos playerFeet = ctx.player.blockPosition();
        BlockPos hostileFeet = hostile.blockPosition();
        boolean elevated = playerFeet.getY() >= hostileFeet.getY() + 2;
        double horizontal = ctx.player.distanceToSqr(hostile);
        return elevated && horizontal > 1.5 * 1.5;
    }

    /**
     * A fall is worth a bucket the moment the ground is known to be far enough down - not once the
     * player has already fallen that far. Waiting for {@code fallDistance} to reach the threshold
     * spends exactly the part of the fall the clutch needs: the head still has to swing ninety
     * degrees and the bucket still has to reach the hand, and a ten-block drop is over in about
     * seventeen ticks. Looking down to see how bad it is going to be is also what a player does.
     */
    private boolean isFallingAndDeadly(BotContext ctx) {
        if (!canClutch(ctx)) {
            return false;
        }
        double fallen = ctx.player.fallDistance;
        if (fallen < COMMITTED_FALL) {
            return false;
        }
        double predicted = fallen + dropBelow(ctx, fallThreshold);
        boolean highUp = predicted >= fallThreshold;
        boolean lethal = Math.max(0.0, predicted - 3.0) >= ctx.player.getHealth();
        return highUp || lethal;
    }

    /**
     * Airborne, on the way down, and carrying something that can still stop it.
     *
     * <p>The last clause is the point: a fall nobody can do anything about is not a threat worth
     * taking control of. Seizing the keys there would only mean the routine loses them for the last
     * second of its life.
     */
    private static boolean canClutch(BotContext ctx) {
        if (ctx.player.onGround() || ctx.player.isInWater() || ctx.player.isInLava()) {
            return false;
        }
        if (ctx.player.getAbilities().flying || ctx.player.isCreative() || ctx.player.isSpectator()) {
            return false;
        }
        if (ctx.player.isFallFlying() || wearingElytra(ctx.player)) {
            return false;
        }
        // Only "is the player descending". The first tick of a fall moves 0.078 blocks, so a
        // coarser gate here quietly costs the reaction time the clutch was just given.
        if (ctx.player.getDeltaMovement().y >= 0.0) {
            return false;
        }
        return canWaterClutch(ctx) || canBoatClutch(ctx);
    }

    /**
     * Water is the better clutch wherever it works at all: one packet, no round trip, and it can
     * still be poured on the tick before impact. The Nether is where it stops working - a bucket
     * emptied there evaporates on the spot - and used to be where this monitor gave up entirely.
     */
    private static boolean canWaterClutch(BotContext ctx) {
        return hasWaterBucket(ctx.player) && !ctx.level.dimension().equals(Level.NETHER);
    }

    private static boolean canBoatClutch(BotContext ctx) {
        return BoatHelper.carrying(ctx.player);
    }

    /**
     * Blocks between the player's feet and whatever stops them, or infinity when nothing does
     * within {@code maxDepth}. Fluids count as a landing: dropping into a lake is not a fall the
     * bot needs to spend a bucket on.
     */
    private static double dropBelow(BotContext ctx, double maxDepth) {
        BlockHitResult landing = landingSurface(ctx, maxDepth, ClipContext.Fluid.ANY);
        return landing == null
                ? Double.POSITIVE_INFINITY
                : Math.max(0.0, ctx.player.getY() - landing.getLocation().y);
    }

    /**
     * What the player's body will actually come down on, which is not what the crosshair is over.
     *
     * <p>A player is 0.6 blocks wide and lands on the highest block any part of that footprint
     * touches, so someone straddling a one-block step comes to rest on the upper tile while a ray
     * from between their eyes goes down the lower one. Pouring by the crosshair there puts the
     * water in the next column along and the player lands dry beside it - which is exactly the
     * position this was tested from. Sampling the four corners of the body box covers every column
     * the footprint can overlap, and the highest hit is the one that stops the fall.
     */
    private static BlockHitResult landingSurface(BotContext ctx, double maxDepth,
                                                 ClipContext.Fluid fluid) {
        AABB body = ctx.player.getBoundingBox();
        double feetY = ctx.player.getY();
        double floor = Math.max(feetY - maxDepth, ctx.level.getMinY() - 1.0);
        BlockHitResult highest = null;
        for (double x : new double[] {body.minX + CORNER_INSET, body.maxX - CORNER_INSET}) {
            for (double z : new double[] {body.minZ + CORNER_INSET, body.maxZ - CORNER_INSET}) {
                BlockHitResult hit = ctx.level.clip(new ClipContext(new Vec3(x, feetY, z),
                        new Vec3(x, floor, z), ClipContext.Block.COLLIDER, fluid, ctx.player));
                if (hit.getType() != HitResult.Type.BLOCK) {
                    continue;
                }
                if (highest == null || hit.getLocation().y > highest.getLocation().y) {
                    highest = hit;
                }
            }
        }
        return highest;
    }

    private static boolean wearingElytra(Player player) {
        return player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private static boolean hasWaterBucket(Player player) {
        return InventoryHelper.findSlot(player, stack -> stack.is(Items.WATER_BUCKET)) >= 0;
    }

    private TaskStatus fallClutch(BotContext ctx) {
        if (!ctx.player.isAlive()) {
            return TaskStatus.FAILED;
        }
        AbstractBoat seat = BoatHelper.ridden(ctx);
        if (seat != null) {
            // Seated is saved: a passenger's fall distance is cleared every tick they are in there,
            // so from here the job is to ride it down and then get out again.
            boatBoarded = true;
            return rideItOut(ctx, seat);
        }
        if (!stillFalling(ctx)) {
            if (bucketPlaced || boatPlacements > 0 || boatBoarded || cushionPos != null) {
                return recoverClutch(ctx);
            }
            status = "landed safely";
            return TaskStatus.SUCCESS;
        }
        if (bucketPlaced) {
            status = "falling through water";
            return TaskStatus.RUNNING;
        }
        if (clutchTicks++ > CLUTCH_TIMEOUT) {
            status = "could not clutch in time";
            return TaskStatus.FAILED;
        }
        Cushion cushion = bestCushion(ctx);
        clutch = ClutchPolicy.choosePreferred(clutch,
                SelfPreservationPolicy.preferredClutch(recoveryStrategy),
                canWaterClutch(ctx), cushion != null, canBoatClutch(ctx),
                boatHasTime(ctx), boatPlacements > 0);
        if (clutch == ClutchPolicy.Method.BOAT) {
            return boatClutch(ctx);
        }
        if (clutch == ClutchPolicy.Method.CUSHION) {
            return cushionClutch(ctx, cushion);
        }
        if (clutch == ClutchPolicy.Method.NONE) {
            status = "nothing left to clutch with";
            return TaskStatus.FAILED;
        }
        TaskStatus water = placeWaterBucket(ctx);
        if (water != TaskStatus.FAILED || (!canBoatClutch(ctx) && cushion == null)) {
            return water;
        }
        // The bucket let us down - dropped, or never made it into the hotbar - and the ground is
        // still coming. Nothing has been aimed yet on a tick that ends this way, so whatever is
        // left can have the rest of it rather than waiting for the next one.
        if (cushion != null && !boatHasTime(ctx)) {
            clutch = ClutchPolicy.Method.CUSHION;
            ctx.debug.decide("no water bucket to clutch with, landing on "
                    + cushion.block().getName().getString());
            return cushionClutch(ctx, cushion);
        }
        clutch = ClutchPolicy.Method.BOAT;
        ctx.debug.decide("no water bucket to clutch with, going for a boat");
        return boatClutch(ctx);
    }

    /**
     * Whether a hull could still be placed and boarded before the ground arrives.
     *
     * <p>Asked of the impact speed rather than the current one, because the whole point is to
     * decide at the top of a fall that is about to become too fast, while there is still a choice
     * to make. Standing reach is the window: a boat placed lower than the arm can reach is not a
     * boat that exists.
     */
    private boolean boatHasTime(BotContext ctx) {
        double drop = dropBelow(ctx, CLUTCH_SCAN_DEPTH);
        if (Double.isInfinite(drop)) {
            // Nothing under us for twenty-four blocks. Whatever it turns out to be, the arrival
            // will be fast; a hull would go down on the last tick, which is what it did.
            return false;
        }
        return ClutchPolicy.boatHasTime(reachBelowFeet(ctx), drop,
                -ctx.player.getDeltaMovement().y);
    }

    /**
     * Lands on something soft.
     *
     * <p>This is the one clutch that keeps working at terminal velocity, because putting a block
     * down is a single packet the server acts on when it arrives - no hull to be sent back, no
     * boarding to be accepted. It is also the one that spends something the run may want later,
     * which is why the choice above only reaches here when a boat could not have been climbed
     * into in time.
     */
    private TaskStatus cushionClutch(BotContext ctx, Cushion cushion) {
        if (cushion == null) {
            status = "nothing soft to land on";
            return TaskStatus.FAILED;
        }
        if (cushionPos != null) {
            status = "falling onto " + cushion.block().getName().getString();
            return TaskStatus.RUNNING;
        }

        ctx.look.urgent();
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);

        BlockHitResult landing = landingSurface(ctx, CLUTCH_SCAN_DEPTH, ClipContext.Fluid.NONE);
        if (landing == null) {
            ctx.look.lookAtRotation(ctx.player, ctx.player.getYRot(), STRAIGHT_DOWN);
            status = "nothing below to soften yet";
            return TaskStatus.RUNNING;
        }
        // Same aim as the other two: head down, feet walking the body over the tile it lands on.
        // The cushion goes into the space in front of the face we are falling at - the block the
        // feet are about to occupy - so that it is what the landing is measured against.
        BlockPos ground = landing.getBlockPos();
        ctx.look.lookAtRotation(ctx.player, steerOver(ctx, ground), STRAIGHT_DOWN);
        BlockPos target = ground.relative(landing.getDirection());
        // Re-asked now that there is somewhere to put it: the choice above only knew what was in
        // the bag, and a berry bush on stone or a ladder in open air is not a landing.
        Cushion placeable = bestCushion(ctx, target);
        if (placeable != null) {
            cushion = placeable;
        }

        ctx.debug.target("cushion clutch", target, String.format("feet=%.2f landing=%.2f %s",
                ctx.player.getY(), landing.getLocation().y, cushion.block().getName().getString()));

        // Counted from the first tick the ground is actually reachable, not from the top of the
        // fall: a drop long enough to be worth clutching spends most of itself with nothing in
        // range at all, and a budget that started up there would run out before the attempt did.
        if (++cushionTicks > MAX_CUSHION_TICKS) {
            status = "could not get a landing down in time";
            return TaskStatus.RUNNING;
        }
        BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(ctx, cushion.block(), target);
        if (placement == BlockPlacer.PlacementResult.PLACED
                || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
            cushionPos = target;
            cushionBlock = cushion.block();
            ctx.debug.decide(String.format(
                    "cushion clutch: %s at %s, leaving %d of a %.0f block fall",
                    cushion.block().getName().getString(), target.toShortString(),
                    ClutchPolicy.fallDamage(ctx.player.fallDistance
                            + (ctx.player.getY() - landing.getLocation().y), cushion.damageShare()),
                    ctx.player.fallDistance + (ctx.player.getY() - landing.getLocation().y)));
            status = "landing on " + cushion.block().getName().getString();
            return TaskStatus.RUNNING;
        }
        status = placement.isTransient()
                ? "putting " + cushion.block().getName().getString() + " under the landing"
                : "cannot place " + cushion.block().getName().getString() + " down there";
        return TaskStatus.RUNNING;
    }

    /**
     * The cheapest carried block that still leaves the player standing, or the softest one there
     * is when none of them do. Nothing is spent on a fall that was survivable anyway - that check
     * is the caller's, which only reaches here once the fall is already known to be fatal.
     */
    private Cushion bestCushion(BotContext ctx) {
        return bestCushion(ctx, null);
    }

    /**
     * The cheapest carried block that still leaves the player standing, or the softest one there
     * is when none of them do. Nothing is spent on a fall that was survivable anyway - that check
     * is the caller's, which only reaches here once the fall is already known to be fatal.
     *
     * @param target where the block would go, or null when the question is only "is there
     *               anything at all" - a berry bush wants soil under it and a ladder wants a wall,
     *               so what can be placed is not the same question as what is carried
     */
    private Cushion bestCushion(BotContext ctx, BlockPos target) {
        double drop = dropBelow(ctx, CLUTCH_SCAN_DEPTH);
        // A drop deeper than the scan is a drop of unknown depth, and the cheapest thing that
        // survives cannot be worked out from a number nobody has. Take the softest thing carried
        // and be wrong in the direction that leaves the player alive.
        boolean unknownDepth = Double.isInfinite(drop);
        double distance = ctx.player.fallDistance + (unknownDepth ? 0.0 : drop);
        Cushion softest = null;
        for (Cushion cushion : carriedCushions(ctx)) {
            if (target != null
                    && !cushion.block().defaultBlockState().canSurvive(ctx.level, target)) {
                continue;
            }
            if (!unknownDepth
                    && ClutchPolicy.survives(distance, cushion.damageShare(), ctx.player.getHealth())) {
                return cushion;
            }
            if (softest == null || cushion.damageShare() < softest.damageShare()) {
                softest = cushion;
            }
        }
        return softest;
    }

    /**
     * Everything in the bag worth landing on, cheapest first.
     *
     * <p>The named three soften a landing through {@code fallOn}. The rest are found by asking
     * vanilla rather than by listing them: anything in {@code #fall_damage_resetting} - cobweb, a
     * sweet berry bush, and every climbable, so ladders, vines and scaffolding - clears the fall
     * distance outright when a fast fall sweeps through it, which is a better landing than hay and
     * usually a cheaper one. Reading the tag also means a block added to it by a later version
     * starts working here without anyone editing this list.
     */
    private List<Cushion> carriedCushions(BotContext ctx) {
        List<Cushion> carried = new ArrayList<>();
        for (Cushion cushion : CUSHIONS) {
            Item item = cushion.block().asItem();
            if (item != null && InventoryHelper.anyMatch(ctx.player, stack -> stack.is(item))) {
                carried.add(cushion);
            }
        }
        Inventory inventory = ctx.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Block block = Block.byItem(stack.getItem());
            if (block == Blocks.AIR
                    || !block.defaultBlockState().is(BlockTags.FALL_DAMAGE_RESETTING)) {
                continue;
            }
            Cushion reset = new Cushion(block, 0.0);
            if (!carried.contains(reset)) {
                carried.add(reset);
            }
        }
        return carried;
    }

    /** Digs the cushion back up. Bounded: a slime block is worth seconds, not a campaign. */
    private TaskStatus recoverCushion(BotContext ctx) {
        if (cushionPos == null || cushionBlock == null) {
            return TaskStatus.SUCCESS;
        }
        if (!ctx.level.getBlockState(cushionPos).is(cushionBlock)) {
            cushionBreaker.stop(ctx);
            status = "landing already gone";
            return TaskStatus.SUCCESS;
        }
        if (!ctx.player.onGround() && !ctx.player.isInWater()) {
            // A slime block throws the player back up, so "landed" can be a bounce in progress.
            // Swinging at a block from mid-air is how the recovery talks itself out of reach.
            status = "waiting to settle";
            return TaskStatus.RUNNING;
        }
        if (++cushionRecoverTicks > MAX_CUSHION_RECOVER_TICKS
                || cushionBreaker.isOutOfReach(ctx, cushionPos)) {
            cushionBreaker.stop(ctx);
            status = "left the landing behind";
            return TaskStatus.SUCCESS;
        }
        BlockBreaker.Progress progress = cushionBreaker.tick(ctx, cushionPos);
        if (progress == BlockBreaker.Progress.NO_TOOL) {
            cushionBreaker.stop(ctx);
            status = "nothing to dig the landing back up with";
            return TaskStatus.SUCCESS;
        }
        status = "picking the landing back up";
        return TaskStatus.RUNNING;
    }

    /**
     * Puts a boat under the fall and gets into it.
     *
     * <p>Landing on a hull is worth nothing - that is an ordinary landing on whatever block is
     * under it, at full damage. Only riding one cancels a fall, so every tick here is spent either
     * boarding a hull or making one to board, and boarding wins whenever both are possible: a boat
     * already within reach beats a better-placed one that does not exist yet.
     *
     * <p>Nothing in this branch crouches, and that is deliberate. The boarding packet carries the
     * shift state and a boat reads a crouching player as having meant something else entirely, so
     * the one habit the water clutch leans on is the one this must not pick up.
     */
    private TaskStatus boatClutch(BotContext ctx) {
        // At every degree per tick the head is allowed: the same ninety-degree swing the water
        // clutch needs, for the same reason - the placement ray is the view vector.
        ctx.look.urgent();
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);

        BlockHitResult landing = landingSurface(ctx, CLUTCH_SCAN_DEPTH, ClipContext.Fluid.NONE);
        // Head straight down, feet doing the aiming, exactly as the water clutch does it: pressing
        // forward is yaw-relative, so steering by yaw walks the body over the tile it will land on
        // without ever moving the ray the boat is placed along.
        float yaw = landing == null ? ctx.player.getYRot() : steerOver(ctx, landing.getBlockPos());
        ctx.look.lookAtRotation(ctx.player, yaw, STRAIGHT_DOWN);

        AbstractBoat hull = BoatHelper.nearest(ctx, BOAT_SEARCH_RADIUS);
        // Our own hull is worth waiting for however far below it still is - we are falling at it.
        // Somebody else's, moored off to one side, is only worth a tick if it is already in reach:
        // the drop does not go there, and standing on ceremony over it costs the placement.
        if (hull != null && (boatPlacements > 0 || BoatHelper.mightReachToBoard(ctx, hull))) {
            return board(ctx, hull);
        }
        if (boatPlaced) {
            // A refused placement and a slow one look identical from the client, so a hull that
            // never turns up is the only evidence there is that the spot was no good.
            if (++boatWaitTicks <= BOAT_ARRIVAL_TICKS) {
                status = "waiting for the boat to appear";
                return TaskStatus.RUNNING;
            }
            boatPlaced = false;
            boatWaitTicks = 0;
        }
        return placeClutchBoat(ctx, landing);
    }

    private TaskStatus board(BotContext ctx, AbstractBoat hull) {
        // Deliberately the server's own allowance rather than the tidy walking-up-to-it distance:
        // the hull is only in front of a falling player for two or three ticks, and a packet the
        // server turns down costs nothing next to a tick spent waiting to be closer.
        if (!BoatHelper.mightReachToBoard(ctx, hull)) {
            status = "falling toward the boat";
            return TaskStatus.RUNNING;
        }
        if (ctx.player.isShiftKeyDown()) {
            // Sneaking cancels boarding, and the interaction packet carries the shift state, so
            // sending it now would only tell the server we meant to crouch. The bot's own keys are
            // cleared every tick, so this can only be one tick of a habit from another branch.
            boatSneakBlocks++;
            status = "standing up to board the boat";
            return TaskStatus.RUNNING;
        }
        boatBoardAttempts++;
        BoatHelper.board(ctx, hull);
        status = "getting into the boat";
        return TaskStatus.RUNNING;
    }

    private TaskStatus placeClutchBoat(BotContext ctx, BlockHitResult landing) {
        if (boatPlacements >= MAX_BOAT_PLACEMENTS) {
            // Out of attempts, but not out of fall: keep the head down and the body over the
            // landing anyway. This is a hard landing, not a reason to stop the run - and the
            // ground may still turn into somewhere a hull fits before it arrives.
            status = "nowhere to put another boat";
            return TaskStatus.RUNNING;
        }
        if (BoatHelper.equip(ctx) < 0) {
            // Nothing left to put down. If one was already spent on this fall it is somewhere
            // below us and the aim above is still worth holding, so that is not a run-stopping
            // failure - only having had nothing from the start is.
            if (boatPlacements > 0) {
                status = "boat already spent on this fall";
                return TaskStatus.RUNNING;
            }
            status = "no boat to clutch with";
            return TaskStatus.FAILED;
        }
        if (!BoatHelper.inHand(ctx)) {
            status = "taking out a boat";
            return TaskStatus.RUNNING;
        }
        if (landing == null) {
            // Nothing to aim at yet, but the head is already in position for when there is.
            status = "nothing below to put a boat on yet";
            return TaskStatus.RUNNING;
        }

        Vec3 hullAt = BoatHelper.placementTarget(ctx);
        ctx.debug.target("boat clutch", landing.getBlockPos(), String.format(
                "feet=%.2f landing=%.2f hull=%s",
                ctx.player.getY(), landing.getLocation().y,
                hullAt == null ? "out-of-reach"
                        : String.format("%.1f/%.1f/%.1f", hullAt.x, hullAt.y, hullAt.z)));

        if (hullAt == null || hullAt.y > ctx.player.getY()) {
            status = String.format("ground %.1f blocks below the boat's reach",
                    ctx.player.getY() - landing.getLocation().y - reachBelowFeet(ctx));
            return TaskStatus.RUNNING;
        }
        if (!BoatHelper.fits(ctx, hullAt)) {
            // A hull is nearly a block and a half across and has to land flat. A shaft, a crevice
            // or a one-block ledge has nowhere to put one, and falling closer will not change that.
            status = "no room for a boat down there";
            return TaskStatus.RUNNING;
        }

        BoatHelper.place(ctx);
        boatPlaced = true;
        boatPlacements++;
        boatWaitTicks = 0;
        boatPlaceTick = ctx.player.tickCount;
        boatPlaceDrop = ctx.player.getY() - hullAt.y;
        boatPlaceSpeed = Math.max(0.01, -ctx.player.getDeltaMovement().y);
        // The whole clutch lives or dies on this number: the hull has to come back from the server
        // and be climbed into before the ground arrives, and the arm is only about three blocks
        // long, so a fast fall simply does not have the ticks.
        ctx.debug.decide(String.format(
                "boat clutch: hull %.1f blocks down at %.2f blocks/tick - about %.1f ticks to board",
                boatPlaceDrop, boatPlaceSpeed, boatPlaceDrop / boatPlaceSpeed));
        status = "putting a boat down to land in";
        return TaskStatus.RUNNING;
    }

    /**
     * Says how the boat clutch went, once per fall.
     *
     * <p>"It put a boat down and did not get in" has three completely different causes - no time
     * for the round trip, a refused interaction, or a crouch cancelling it - and from outside they
     * look identical. One line that separates them is worth more than another guess.
     */
    private void reportBoatClutch(BotContext ctx) {
        if (boatPlaceTick == Integer.MIN_VALUE) {
            return;
        }
        String account = String.format(
                "hull placed %.1f blocks up at %.2f blocks/tick; %d ticks to landing, "
                        + "%d boarding packets sent, %d skipped for sneak",
                boatPlaceDrop, boatPlaceSpeed, ctx.player.tickCount - boatPlaceTick,
                boatBoardAttempts, boatSneakBlocks);
        boatPlaceTick = Integer.MIN_VALUE;
        if (boatBoarded) {
            ctx.chat("Boat clutch worked - " + account);
            return;
        }
        ctx.debug.recordFailure(name() + "/boat clutch", account);
        ctx.chat("Boat clutch missed - " + account);
    }

    /**
     * Sits still while the boat finishes the fall, then climbs out.
     *
     * <p>Nothing is pressed on the way down on purpose: a ridden hull is steered by exactly the
     * keys that steer the player, so a stray forward from anywhere else would row the boat off the
     * ledge it is in the middle of saving us from.
     */
    private TaskStatus rideItOut(BotContext ctx, AbstractBoat hull) {
        if (boatRideTicks++ < MIN_BOAT_RIDE_TICKS) {
            // Seated, and staying that way for a moment. A clutch onto the landing itself boards a
            // hull that is already resting on the ground, so "it has settled, get out" is true on
            // the very tick the boarding arrives - and standing straight back up is how a save
            // turns back into a fall.
            status = "in the boat, fall cancelled";
            return TaskStatus.RUNNING;
        }
        boolean settled = hull.onGround() || hull.isInWater() || !hull.isAlive();
        if (settled || boatRideTicks > MAX_BOAT_RIDE_TICKS) {
            // Sneak is how a passenger leaves a vehicle, and the input layer already owns it.
            ctx.input.sneak = true;
            status = "getting out of the boat";
            return TaskStatus.RUNNING;
        }
        status = "riding the boat down";
        return TaskStatus.RUNNING;
    }

    private TaskStatus recoverClutch(BotContext ctx) {
        // Both ways into recovery come through here - the tick the falling stops, and the
        // safe-confirm window afterwards - which is the only place the account of the fall is
        // guaranteed to be written. The landing tick alone is not: by then the player is on the
        // ground, so the fall branch that used to hold this is no longer the one being ticked.
        reportBoatClutch(ctx);
        if (cushionPos != null) {
            return recoverCushion(ctx);
        }
        return boatPlacements > 0 || boatBoarded ? recoverBoat(ctx) : recoverBucket(ctx);
    }

    /**
     * Gets out of the boat, then gets the boat back.
     *
     * <p>Climbing out is not optional. A monitor that hands control back while the bot is still
     * seated has left every routine after it rowing instead of walking, and nothing downstream
     * knows to check. Breaking the hull afterwards is optional - it is five planks, and one of them
     * may be the next fall, so it is worth a few seconds and not worth a fight.
     */
    private TaskStatus recoverBoat(BotContext ctx) {
        if (!ctx.player.isAlive()) {
            return TaskStatus.FAILED;
        }
        if (BoatHelper.ridden(ctx) != null) {
            ctx.input.sneak = true;
            status = "getting out of the boat";
            return TaskStatus.RUNNING;
        }
        if (!ctx.player.onGround() && !ctx.player.isInWater()) {
            status = "waiting to land";
            return TaskStatus.RUNNING;
        }
        AbstractBoat hull = BoatHelper.nearest(ctx, BOAT_SEARCH_RADIUS);
        if (hull == null) {
            status = "boat gone";
            return TaskStatus.SUCCESS;
        }
        if (++boatRecoverTicks > MAX_BOAT_RECOVER_TICKS) {
            status = "left the boat behind";
            return TaskStatus.SUCCESS;
        }
        Vec3 centre = hull.getBoundingBox().getCenter();
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.lookAt(ctx.player, centre);
        if (!ctx.look.isLookingAt(ctx.player, centre, CLUTCH_AIM_TOLERANCE)) {
            status = "looking at the boat";
            return TaskStatus.RUNNING;
        }
        if (!BoatHelper.withinBoardingRange(ctx, hull)) {
            status = "boat out of reach";
            return TaskStatus.SUCCESS;
        }
        BoatHelper.strike(ctx, hull);
        status = "picking the boat back up";
        return TaskStatus.RUNNING;
    }

    /**
     * Whether the player is sitting in a boat this monitor put them in.
     *
     * <p>Keyed off the method being attempted rather than off {@link #boatBoarded}, because the
     * flag is set in the tick and this is asked before it. A seated player reads as "not falling"
     * from every angle - delta movement is zeroed, fall distance is cleared - so a monitor that
     * waited for the flag would decide the danger was over on the very tick the boarding worked,
     * and the recovery would climb straight back out twenty blocks up.
     */
    private boolean ridingClutchBoat(BotContext ctx) {
        return (boatBoarded || clutch == ClutchPolicy.Method.BOAT)
                && BoatHelper.ridden(ctx) != null;
    }

    /**
     * Walks the body over the tile it is going to land on, and returns the yaw to hold while doing
     * it. Movement is yaw-relative and ignores pitch, so pressing forward drifts the fall.
     *
     * <p>Steering with the yaw is free only because the pitch stays at ninety: the view vector is
     * straight down whatever the yaw is, so the ray that decides the pour never moves. Aiming the
     * <em>head</em> at an off-centre tile instead is what killed a thirty-nine block test fall -
     * the aim point is a bearing the walk itself keeps changing, so the head chased it round, the
     * pitch came off ninety with it, and the ray was pointing at a neighbouring column for the
     * whole of the last twelve blocks. Air control is about two hundredths of a block per tick
     * squared: nothing over one tick, and enough over a fall worth a bucket.
     *
     * <p>Pressing only once the turn has arrived matters for the same reason - forward mid-turn
     * spends the drift in whatever direction the head happens to be facing.
     */
    private static float steerOver(BotContext ctx, BlockPos ground) {
        double targetX = ground.getX() + 0.5;
        double targetZ = ground.getZ() + 0.5;
        if (within(ALIGN_DEADZONE, targetX - ctx.player.getX(), targetZ - ctx.player.getZ())) {
            return ctx.player.getYRot();
        }
        // Steer by where the drift already ends up, not by where the body is now. Air drag only
        // takes a tenth of the sideways speed per tick, so a press keeps paying out for about ten
        // ticks after it stops - press on the current gap and the correction sails a block and a
        // half past the column it was aiming for, then turns round and does it again.
        Vec3 drift = ctx.player.getDeltaMovement();
        double dx = targetX - (ctx.player.getX() + drift.x * AIR_GLIDE_TICKS);
        double dz = targetZ - (ctx.player.getZ() + drift.z * AIR_GLIDE_TICKS);
        if (within(ALIGN_DEADZONE, dx, dz)) {
            return ctx.player.getYRot();
        }
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        if (Math.abs(Mth.wrapDegrees(wantYaw - ctx.player.getYRot())) <= CLUTCH_AIM_TOLERANCE) {
            ctx.input.forward = true;
        }
        return wantYaw;
    }

    private static boolean within(double radius, double dx, double dz) {
        return dx * dx + dz * dz <= radius * radius;
    }

    /** Airborne and still on the way down. Landing, water and a bounce all end the clutch. */
    private static boolean stillFalling(BotContext ctx) {
        return !ctx.player.onGround() && !ctx.player.isInWater() && !ctx.player.isInLava()
                && ctx.player.fallDistance > 0.0;
    }

    private TaskStatus placeWaterBucket(BotContext ctx) {
        if (!hasWaterBucket(ctx.player)) {
            status = "no water bucket";
            return TaskStatus.FAILED;
        }

        int slot = InventoryHelper.equip(ctx, stack -> stack.is(Items.WATER_BUCKET));
        if (slot < 0) {
            status = "no water bucket in hotbar";
            return TaskStatus.FAILED;
        }

        ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!held.is(Items.WATER_BUCKET)) {
            status = "equipping water bucket";
            return TaskStatus.RUNNING;
        }

        // At every degree per tick the head is allowed: an eased ninety-degree turn takes about
        // thirteen ticks at the ordinary acceleration, which is most of the fall this is meant to
        // survive, and urgency is exactly the case it was written for.
        ctx.look.urgent();
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);

        BlockHitResult landing = landingSurface(ctx, CLUTCH_SCAN_DEPTH, ClipContext.Fluid.NONE);
        if (landing == null) {
            // Nothing to aim at yet, but the head can be in position before there is.
            ctx.look.lookAtRotation(ctx.player, ctx.player.getYRot(), STRAIGHT_DOWN);
            // Distinct from the reach message below on purpose: "still falling past nothing" and
            // "the ground is there and the arm is too short" are different failures, and a journal
            // that spells both the same way is a journal that cannot tell them apart afterwards.
            status = "nothing below to land on yet";
            return TaskStatus.RUNNING;
        }

        // The head holds straight down and the feet do the aiming: walk the body over the tile it
        // lands on, so the pour and the landing are the same column even on a straddled step.
        BlockPos ground = landing.getBlockPos();
        ctx.look.lookAtRotation(ctx.player, steerOver(ctx, ground), STRAIGHT_DOWN);

        // Crouching is what keeps a waterloggable landing from swallowing the bucket: vanilla only
        // pours *into* leaves, a slab or a fence when the player is not sneaking, and drops the
        // water on top of them when they are. That is the whole technique for clutching onto a
        // canopy, and walking off the tree - which is what this used to try - cannot work anyway:
        // a canopy is wider than the block of drift a fall can buy. Crouching also lowers the eye
        // by a third of a block, so the ground comes into bucket range a third of a block earlier,
        // which is worth holding whenever the walk is not using the same ticks.
        boolean soaks = BucketHelper.soaksUpWater(ctx, ground);
        if (soaks || !ctx.input.forward) {
            ctx.input.sneak = true;
        }
        if (soaks && !ctx.player.isShiftKeyDown()) {
            // The key only reaches the player and the server next tick, and pouring before it
            // arrives is how the one bucket gets spent filling a leaf.
            status = "crouching to pour on top";
            return TaskStatus.RUNNING;
        }

        BlockPos wouldLand = BucketHelper.placementTarget(ctx);
        ctx.debug.target("water clutch", ground, String.format(
                "feet=%.2f landing=%.2f ray=%s crouched=%s",
                ctx.player.getY(), landing.getLocation().y,
                wouldLand == null ? "out-of-reach" : wouldLand.toShortString(),
                ctx.player.isShiftKeyDown()));

        if (wouldLand == null || !catchesTheFall(ctx, wouldLand, landing.getLocation().y)) {
            status = wouldLand == null
                    ? String.format("ground %.1f blocks below the bucket's reach",
                            ctx.player.getY() - landing.getLocation().y - reachBelowFeet(ctx))
                    : "lining up over the landing";
            return TaskStatus.RUNNING;
        }

        BucketHelper.use(ctx);

        held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.is(Items.BUCKET)) {
            bucketPlaced = true;
            clutchWater = wouldLand;
            status = "water placed";
        } else {
            status = "placing water";
        }
        return TaskStatus.RUNNING;
    }

    /**
     * How far below the feet a placement can still reach, in blocks - the arm's length less the
     * height of the eye it is measured from. This is the whole window a clutch has: about 2.9
     * blocks standing and 3.2 crouched, against a fall that covers up to 3.9 blocks in a tick.
     *
     * <p>It is also why the two clutches are not interchangeable. Water needs this window once, on
     * the tick it pours; a boat needs it early enough that the hull can come back from the server
     * and be climbed into, which is two or three ticks of falling that a fast enough drop does not
     * have. The boat is the answer when there is no bucket, not a better bucket.
     */
    private static double reachBelowFeet(BotContext ctx) {
        return ctx.player.blockInteractionRange() - ctx.player.getEyeHeight();
    }

    /**
     * Whether a block of water there is water the body ends up inside.
     *
     * <p>Tested against the landing rather than against one block picked in advance, because the
     * two rays involved need not agree: the fall is predicted with collision shapes and the bucket
     * aims with outlines, so a tuft of grass or a flower standing on the landing tile moves the
     * pour up a block without moving the landing. Insisting on one exact block pours nothing at
     * all in those cases, when the water would have caught the player perfectly well.
     */
    private static boolean catchesTheFall(BotContext ctx, BlockPos water, double landingY) {
        return water.getY() < landingY + PLAYER_HEIGHT
                && water.getY() + 1.0 > landingY
                && Math.abs(water.getX() + 0.5 - ctx.player.getX()) < FOOTPRINT_OVERLAP
                && Math.abs(water.getZ() + 0.5 - ctx.player.getZ()) < FOOTPRINT_OVERLAP;
    }

    private TaskStatus recoverBucket(BotContext ctx) {
        if (!ctx.player.isAlive()) {
            return TaskStatus.FAILED;
        }
        if (clutchWater == null || !bucketPlaced) {
            return TaskStatus.SUCCESS;
        }

        BlockPos waterPos = clutchWater;
        if (!ctx.level.getBlockState(waterPos).getFluidState().is(FluidTags.WATER)) {
            status = "water gone";
            return TaskStatus.SUCCESS;
        }

        if (!ctx.player.onGround() && !ctx.player.isInWater()) {
            status = "waiting to land";
            return TaskStatus.RUNNING;
        }

        int slot = InventoryHelper.equip(ctx, stack -> stack.is(Items.BUCKET));
        if (slot < 0) {
            status = "no empty bucket";
            return TaskStatus.SUCCESS;
        }

        ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.is(Items.WATER_BUCKET)) {
            status = "water bucket recovered";
            return TaskStatus.SUCCESS;
        }
        if (!held.is(Items.BUCKET)) {
            status = "bucket not in hand";
            return TaskStatus.SUCCESS;
        }

        Vec3 target = Vec3.atCenterOf(waterPos);
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.lookAt(ctx.player, target);
        if (!ctx.look.isLookingAt(ctx.player, target, CLUTCH_AIM_TOLERANCE)) {
            status = "looking at water";
            return TaskStatus.RUNNING;
        }

        if (!waterPos.equals(BucketHelper.pickupTarget(ctx))) {
            status = "water out of reach";
            return TaskStatus.SUCCESS;
        }

        BucketHelper.use(ctx);

        held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.is(Items.WATER_BUCKET)) {
            status = "picked water back up";
            return TaskStatus.SUCCESS;
        }

        status = "collecting water";
        return TaskStatus.RUNNING;
    }

    /** Walls count as safety: do not keep fleeing a hostile that cannot see/reach through rock. */
    private static boolean hasLineOfSight(BotContext ctx, LivingEntity entity) {
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 target = entity.getEyePosition();
        return ctx.level.clip(new net.minecraft.world.level.ClipContext(eye, target,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, ctx.player)).getType()
                != net.minecraft.world.phys.HitResult.Type.BLOCK;
    }

    /**
     * Whether this mob has hit the player recently, whether or not the player can see it.
     *
     * <p>Distinct from {@link #isRecentPlayerAttacker}, which only ever matched other *players*
     * and so did nothing for the case that actually kills runs: a skeleton. For an arrow, vanilla
     * records the shooter rather than the projectile as the last attacker, so this names the
     * archer even when only the arrow was ever in view.
     */
    private static boolean justAttackedThePlayer(BotContext ctx, LivingEntity entity) {
        return entity == ctx.player.getLastHurtByMob()
                && ctx.player.tickCount - ctx.player.getLastHurtByMobTimestamp()
                        < UNSEEN_ATTACKER_TICKS;
    }

    private static boolean isRecentPlayerAttacker(BotContext ctx, LivingEntity entity) {
        if (!(entity instanceof Player) || entity != ctx.player.getLastHurtByMob()) {
            return false;
        }
        int age = ctx.player.tickCount - ctx.player.getLastHurtByMobTimestamp();
        return age >= 0 && age < 100;
    }

    private static BlockPos nearestSafeGround(BotContext ctx) {
        BlockPos origin = ctx.player.blockPosition();
        for (int radius = 1; radius <= 12; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos candidate = MovementHelper.findStandableNearY(ctx.level,
                            origin.getX() + dx, origin.getZ() + dz, origin.getY(),
                            ctx.level.getMinY(), ctx.level.getMaxY() - 2, 4);
                    if (candidate != null && !MovementHelper.nearLava(ctx.level, candidate)
                            && !MovementHelper.isWater(ctx.level, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos retreatPoint(BotContext ctx, LivingEntity hostile) {
        double awayX = ctx.player.getX() - hostile.getX();
        double awayZ = ctx.player.getZ() - hostile.getZ();
        double length = Math.max(0.001, Math.hypot(awayX, awayZ));
        double baseAngle = Math.atan2(awayZ, awayX);
        double[] turns = {0.0, Math.PI / 6, -Math.PI / 6, Math.PI / 3, -Math.PI / 3,
                Math.PI / 2, -Math.PI / 2};
        for (int distance : new int[]{14, 11, 8, 5}) {
            for (double turn : turns) {
                double angle = baseAngle + turn;
                int x = (int) Math.floor(ctx.player.getX() + Math.cos(angle) * distance);
                int z = (int) Math.floor(ctx.player.getZ() + Math.sin(angle) * distance);
                BlockPos candidate = MovementHelper.findStandableNearY(ctx.level, x, z,
                        ctx.player.blockPosition().getY(), ctx.level.getMinY(),
                        ctx.level.getMaxY() - 2, 5);
                if (candidate != null && !MovementHelper.nearLava(ctx.level, candidate)
                        && candidate.distSqr(hostile.blockPosition())
                        > ctx.player.blockPosition().distSqr(hostile.blockPosition())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean compare(double actual, String comparison, double expected) {
        return switch (comparison) {
            case "Less than" -> actual < expected;
            case "Greater than" -> actual > expected;
            case "At least" -> actual >= expected;
            default -> actual <= expected; // "At most" and imported/legacy junk
        };
    }

    @Override
    public void onControlReleased(BotContext ctx) {
        finishLearningEpisode(ctx, ctx.player.isAlive() ? TaskStatus.SUCCESS : TaskStatus.FAILED);
        stopRecovery(ctx);
        threat = Threat.NONE;
        hostile = null;
        escapeAttempts = 0;
        monsterEscapes = 0;
        trackedHostileId = -1;
        monsterRecoveryTicks = 0;
        rememberedHostile = null;
        lastHostileSeenTick = Integer.MIN_VALUE;
        safeTicks = 0;
        confirmingSafe = false;
        healthWaitTicks = 0;
        foodRecoveryFailed = false;
        coverBase = null;
        coverTicks = 0;
        coverHeight = 0;
        pillarBlock = null;
        pillarTicks = 0;
        pillarSecured = false;
        rangedRetreatTried = false;
        rangedStrafeTicks = 0;
        rangedStrafeLeft = false;
        meleeLastBlock = null;
        meleeStallTicks = 0;
        meleeStrafeTicks = 0;
        meleeStrafeLeft = false;
        creeperLastBlock = null;
        creeperStallTicks = 0;
        creeperEscapeTicks = 0;
        clutch = ClutchPolicy.Method.NONE;
        bucketPlaced = false;
        clutchWater = null;
        clutchTicks = 0;
        boatPlaced = false;
        boatWaitTicks = 0;
        boatPlacements = 0;
        boatBoarded = false;
        boatRideTicks = 0;
        boatRecoverTicks = 0;
        boatPlaceTick = Integer.MIN_VALUE;
        boatPlaceDrop = 0.0;
        boatPlaceSpeed = 0.0;
        boatBoardAttempts = 0;
        boatSneakBlocks = 0;
        cushionBreaker.stop(ctx);
        cushionPos = null;
        cushionBlock = null;
        cushionTicks = 0;
        cushionRecoverTicks = 0;
        status = "watching";
    }

    @Override
    public void onStop(BotContext ctx) {
        onControlReleased(ctx);
    }

    private void stopRecovery(BotContext ctx) {
        if (recovery != null) {
            recovery.stop(ctx);
            recovery = null;
        }
    }
}
