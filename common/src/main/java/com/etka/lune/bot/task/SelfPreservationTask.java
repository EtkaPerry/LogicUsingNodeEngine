package com.etka.lune.bot.task;

import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.util.Lang;
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
import com.etka.lune.bot.util.FireballDeflect;
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
import net.minecraft.world.entity.projectile.Projectile;
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
    /**
     * Hard ceiling on the threat scan, not the scan itself - see {@link #threatScanRadius()}.
     *
     * <p>This used to be the scan radius, flat. Sixty-four blocks in every direction is a box of
     * two million cubic metres, and every hostile found anywhere in it was line-of-sight raycast
     * before the nearest was picked. Nothing in this class ever compares a distance larger than
     * about sixteen, so the other ninety-five percent of that volume was gathered, raycast and
     * thrown away, four times a second, on the client thread.</p>
     */
    private static final int MAX_THREAT_SCAN_RADIUS = 32;
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

    /**
     * How close the head has to be to the return line before the swing is worth taking, in degrees.
     *
     * <p>Tighter than the fifteen the ordinary melee check allows, because this aim is not "can I
     * hit the thing in front of me" - it is the whole trajectory of the shot being sent back. A
     * Ghast is four blocks across, so at thirty blocks it subtends about eight degrees; half of that
     * is the most the aim may be off and still arrive.
     */
    private static final float DEFLECT_AIM_TOLERANCE = 4.0F;
    /**
     * Ticks left before impact at which an unaimed bot stops trying to bat and starts stepping.
     *
     * <p>{@code ticksAway} over-estimates - the fireball accelerates the whole way in - so this is
     * deliberately generous: a sidestep needs the press to reach the player and then a tick or two
     * of walking to clear the blast line.
     */
    private static final double FIREBALL_DODGE_WINDOW = 6.0;
    /** Ticks one shot may occupy before the bot stops watching it and gets on with the job. */
    private static final int MAX_FIREBALL_TICKS = 120;
    /** Ticks on one sidestep heading before trying the other, when the first one is walled in. */
    private static final int FIREBALL_DODGE_SWITCH_TICKS = 8;
    /**
     * Ticks a batted shot is watched before the deflection is believed.
     *
     * <p>The client turns the fireball around the moment the attack is sent, accepted or not, and
     * the server only resends a projectile's position every tenth tick. Holding the watch open
     * across that gap is the difference between noticing a refused swing and standing in the
     * explosion wondering why.
     */
    private static final int DEFLECT_CONFIRM_TICKS = 12;
    /**
     * Swings one shot is worth. A deflection that worked stops the shot closing within a tick, so
     * a third swing means the server is refusing them and the step aside is overdue.
     */
    private static final int MAX_DEFLECT_SWINGS = 3;

    private enum Threat { NONE, AIR, LAVA, FALL, FIREBALL, MONSTER, HEALTH }

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
    /** Which of the answers below are switched on; see {@link SafetyOptions}. */
    private final SafetyOptions options;

    private Threat threat = Threat.NONE;
    private LivingEntity hostile;
    /**
     * The shot currently being answered, held across ticks.
     *
     * <p>Kept rather than re-chosen each tick so that a second fireball arriving mid-swing cannot
     * make the head switch targets and miss both. The scan still runs every tick - it is how the
     * first one is found, and how an exploded one is noticed - but whichever shot was picked stays
     * picked until it is gone.
     */
    private Projectile fireball;
    /** Swings already spent on {@link #fireball}; a shot that survives several is not going to work. */
    private int fireballSwings;
    private int fireballTicks;
    /** Ticks since the shot stopped closing, counted only once it has been swung at. */
    private int fireballSettleTicks;
    /** How many shots are on course this tick, which is what decides between batting and stepping. */
    private int fireballsInbound;
    private boolean fireballDodgeLeft;
    private int fireballDodgeTicks;
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
    private final StatusText status = new StatusText().set("lune.status.stay_near.watching");
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

    /** Keeps the twelve-argument form working for anything that only configures the thresholds. */
    public SelfPreservationTask(boolean protectAir, String airComparison, int airThreshold,
                                boolean protectLava, boolean protectMonsters,
                                String monsterComparison, int monsterDistance,
                                boolean protectHealth, String healthComparison,
                                int healthThreshold, boolean protectFall, int fallThreshold) {
        this(protectAir, airComparison, airThreshold, protectLava, protectMonsters,
                monsterComparison, monsterDistance, protectHealth, healthComparison,
                healthThreshold, protectFall, fallThreshold, SafetyOptions.all());
    }

    public SelfPreservationTask(boolean protectAir, String airComparison, int airThreshold,
                                boolean protectLava, boolean protectMonsters,
                                String monsterComparison, int monsterDistance,
                                boolean protectHealth, String healthComparison,
                                int healthThreshold, boolean protectFall, int fallThreshold,
                                SafetyOptions options) {
        this.options = options == null ? SafetyOptions.all() : options;
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
        return Lang.get("lune.task.self_preservation.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Self Preservation");
    }

    @Override
    public StatusText statusLine() {
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
            forgetFireball();
            status.set("lune.status.self_preservation.player_dead");
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
        // but handing control back there would leave the next task steering a hull halfway down
        // a ravine instead of walking, so the threat lasts until they have climbed out of it.
        if (protectFall && (ridingClutchBoat(ctx) || isFallingAndDeadly(ctx))) {
            threat = Threat.FALL;
            safeTicks = 0;
            confirmingSafe = false;
            return true;
        }

        // A fireball outranks the mob check below rather than arriving through it. A Ghast shoots
        // from up to sixty-four blocks, so by the distance this card is configured to notice hostiles
        // at - eight, by default - the shooter is not in the scan at all and the bot would stand
        // there being shelled by something it had never been told existed. The shot itself is the
        // evidence, the same way a hit from an unseen skeleton is.
        if (protectMonsters && options.answerFireballs() && trackFireball(ctx)) {
            threat = Threat.FIREBALL;
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
            // wall. Releasing the monitor at that exact moment lets the task walk back into
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
        // A resolved fireball has nothing left to hide behind, which is the whole reason the confirm
        // window exists - a freshly walled-off zombie is out of the raycast for a few ticks and
        // coming round the wall. A Ghast fires every sixty ticks, so spending thirty of them standing
        // still after each shot would hand the job half its time for no safety at all.
        if (threat != Threat.NONE && threat != Threat.FIREBALL
                && safeTicks++ < SAFE_CONFIRM_TICKS) {
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
        forgetFireball();
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
            countThreat(ctx);
            learningEpisode = createLearningEpisode(ctx);
            learningEpisode.start(ctx);
            ctx.debug.safetyEpisodes++;
        }
        TaskStatus result = learningEpisode.tick(ctx);
        if (result != TaskStatus.RUNNING) {
            finishLearningEpisode(ctx, result);
        }
        // This runs beside the work circuit rather than replacing it, so the journal's status
        // column keeps showing the job - "coming next to the tree" - while the bot is actually
        // swimming for the surface. Say so in a field of its own, or an emergency that was handled
        // and one that was never noticed read identically afterwards.
        ctx.debug.safety = status.text();
        return result;
    }

    /** Performs one tick of the currently selected emergency strategy. */
    private TaskStatus tickThreat(BotContext ctx) {
        if (!ctx.player.isAlive()) {
            ctx.input.reset();
            stopRecovery(ctx);
            status.set("lune.engine.player_died");
            return TaskStatus.FAILED;
        }
        if (confirmingSafe) {
            stopRecovery(ctx);
            if (threat == Threat.FALL
                    && (bucketPlaced || boatPlacements > 0 || boatBoarded || cushionPos != null)) {
                return recoverClutch(ctx);
            }
            status.set("lune.status.self_preservation.confirming_danger_gone");
            return TaskStatus.RUNNING;
        }
        return switch (threat) {
            case AIR -> escapeWater(ctx);
            case LAVA -> escapeLava(ctx);
            case FALL -> fallClutch(ctx);
            case FIREBALL -> answerFireball(ctx);
            case MONSTER -> escapeMonster(ctx);
            case HEALTH -> recoverHealth(ctx);
            case NONE -> TaskStatus.SUCCESS;
        };
    }

    /**
     * Counts each new emergency for the dashboard. A run that finished in ten minutes having
     * escaped lava twice and drowning once did something very different from one that never
     * looked up, and neither the task list nor the block counters can tell them apart.
     */
    private void countThreat(BotContext ctx) {
        String key = switch (threat) {
            case AIR -> "danger_drowning";
            case LAVA -> "danger_lava";
            case FALL -> "danger_fall";
            case FIREBALL -> "danger_fireball";
            case MONSTER -> "danger_monster";
            case HEALTH -> "danger_health";
            case NONE -> null;
        };
        if (key == null) {
            return;
        }
        ctx.debug.count("danger_responses");
        ctx.debug.count(key);
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
        } else if (episodeThreat == Threat.FIREBALL && fireball != null) {
            LivingEntity shooter = FireballDeflect.shooter(fireball);
            boolean canDeflect = FireballDeflect.swingPossible(ctx.player);
            boolean canDodge = safeEmergencyDirection(ctx, sidestep(ctx, fireball)) != null;
            actions = SelfPreservationPolicy.fireballActions(canDeflect, canDodge);
            phase = "threat=fireball;shooter=" + (shooter == null
                            ? "unknown" : shooter.getType().getDescriptionId())
                    + ";distance=" + SelfPreservationPolicy.distanceBucket(
                            ctx.player.distanceTo(fireball))
                    + ";health=" + SelfPreservationPolicy.healthBucket(ctx.player.getHealth())
                    + ";inbound=" + Math.min(fireballsInbound, 3)
                    + ";deflect=" + canDeflect + ";dodge=" + canDodge;
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
        status.set("lune.status.self_preservation.escaping_air_left", ctx.player.getAirSupply());
        return ctx.player.isUnderWater() ? TaskStatus.RUNNING : TaskStatus.SUCCESS;
    }

    private TaskStatus escapeLava(BotContext ctx) {
        if (!ctx.player.isInLava()) {
            status.set("lune.status.self_preservation.escaped_lava");
            return TaskStatus.SUCCESS;
        }
        ctx.input.jump = true;
        if (recovery == null) {
            BlockPos safe = nearestSafeGround(ctx);
            if (safe == null) {
                status.set("lune.status.self_preservation.no_safe_ground_near_lava");
                return ++escapeAttempts >= MAX_ESCAPE_ATTEMPTS ? TaskStatus.FAILED : TaskStatus.RUNNING;
            }
            recovery = new GotoTask(new Goals.Near(safe, 0), true, true);
            recovery.start(ctx);
        }
        return tickRecovery(ctx, "lune.status.self_preservation.escaping_lava");
    }

    /**
     * Picks up, keeps, or drops the shot this card is answering.
     *
     * <p>One shot is held across ticks rather than re-chosen from the scan each tick. A Ghast nest
     * can have three fireballs in the air at once, and a head that switches to whichever is nearest
     * arrives on target for none of them.
     *
     * @return true while there is a shot worth answering
     */
    private boolean trackFireball(BotContext ctx) {
        List<Projectile> shots = FireballDeflect.inbound(ctx);
        fireballsInbound = shots.size();
        if (fireball != null) {
            fireballTicks++;
            if (!fireball.isAlive() || fireballTicks > MAX_FIREBALL_TICKS
                    || !stillWorthWatching(ctx)) {
                forgetFireball();
            }
        }
        if (fireball == null && !shots.isEmpty()) {
            forgetFireball();
            fireball = shots.get(0);
        }
        return fireball != null;
    }

    /**
     * Whether the shot is still the bot's business.
     *
     * <p>One that has stopped closing has usually just been batted - but the client predicts the
     * deflection whether or not the server accepted the swing, so "it turned round" is not proof on
     * its own. After a swing the watch therefore stays open for {@link #DEFLECT_CONFIRM_TICKS},
     * which is long enough for the next motion update to give the real answer while there is still
     * time to swing again if the fireball never actually changed course.
     */
    private boolean stillWorthWatching(BotContext ctx) {
        return FireballDeflect.threatens(ctx.player, fireball)
                || (fireballSwings > 0 && fireballSettleTicks < DEFLECT_CONFIRM_TICKS);
    }

    private void forgetFireball() {
        // fireballsInbound is not reset here: it is this tick's scan rather than state about one
        // shot, and the crossfire check reads it after the tracked shot has been dropped.
        fireball = null;
        fireballSwings = 0;
        fireballTicks = 0;
        fireballSettleTicks = 0;
        fireballDodgeTicks = 0;
        fireballDodgeLeft = false;
    }

    /**
     * Sends a Ghast's fireball back to the Ghast, or gets out of its way.
     *
     * <p>Batting it is worth a great deal more than surviving it: vanilla treats a fireball that
     * belongs to a player as a thousand points of damage that ignores invulnerability, so one that
     * arrives back at its shooter kills it on contact. That is the only reason this branch is willing
     * to stand still in front of something that explodes - the shot was aimed where the bot is, and
     * standing there is what brings it inside arm's reach.
     */
    private TaskStatus answerFireball(BotContext ctx) {
        stopRecovery(ctx);
        if (fireball == null || !fireball.isAlive()) {
            status.set("lune.status.self_preservation.fireball_gone");
            return TaskStatus.SUCCESS;
        }

        if (!FireballDeflect.threatens(ctx.player, fireball)) {
            ctx.input.reset();
            if (fireballSwings == 0) {
                // It was never really ours - a shot aimed past us, or one that hit something else.
                status.set("lune.status.self_preservation.fireball_gone");
                return TaskStatus.SUCCESS;
            }
            LivingEntity shooter = FireballDeflect.shooter(fireball);
            if (shooter == null) {
                status.set("lune.status.self_preservation.fireball_sent_back");
            } else {
                status.set("lune.status.self_preservation.fireball_sent_back_at",
                        shooter.getName().getString());
            }
            return ++fireballSettleTicks >= DEFLECT_CONFIRM_TICKS
                    ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
        }
        fireballSettleTicks = 0;

        // Batting is a choice to stand still, and standing still is only affordable against one shot.
        // A Ghast's fireball is six points on contact before the blast is counted, so three arriving
        // together is the whole health bar: batting the nearest means choosing to eat the rest. Three
        // Ghasts in a measured arena killed the bot in 308 ticks doing exactly that, having deflected
        // several shots perfectly on the way down.
        boolean crossfire = fireballsInbound > 1;
        boolean stepAside = crossfire
                || SelfPreservationPolicy.DODGE_FIRST.equals(recoveryStrategy)
                || fireballSwings >= MAX_DEFLECT_SWINGS;
        if (!stepAside && !FireballDeflect.canSwing(ctx.player)) {
            // A spear is routed through a different interaction and a mace refuses an uncharged
            // swing, and in both cases the server drops the attack packet without saying so. Get
            // something in hand that it will accept before spending the window swinging at nothing.
            if (FireballDeflect.freeTheHand(ctx)) {
                ctx.input.reset();
                status.set("lune.status.self_preservation.freeing_hand_to_bat_fireball");
                return TaskStatus.RUNNING;
            }
            stepAside = true;
        }
        if (stepAside) {
            return dodgeFireball(ctx, crossfire
                    ? "lune.status.self_preservation.stepping_out_fireball_crossfire"
                    : "lune.status.self_preservation.stepping_out_fireball_line");
        }

        Vec3 aim = FireballDeflect.returnAim(ctx, fireball);
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, aim);
        boolean aimed = ctx.look.isLookingAt(ctx.player, aim, DEFLECT_AIM_TOLERANCE);

        if (aimed && FireballDeflect.inReach(ctx.player, fireball)) {
            ctx.input.reset();
            FireballDeflect.hit(ctx, fireball);
            fireballSwings++;
            ctx.debug.count("fireballs_batted");
            LivingEntity shooter = FireballDeflect.shooter(fireball);
            if (shooter == null) {
                status.set("lune.status.self_preservation.batting_fireball_back");
            } else {
                status.set("lune.status.self_preservation.batting_fireball_back_at",
                        shooter.getName().getString());
            }
            return TaskStatus.RUNNING;
        }

        if (!aimed && FireballDeflect.ticksAway(ctx.player, fireball) <= FIREBALL_DODGE_WINDOW) {
            // The head is not going to make it. A swing taken with the aim still coming round sends
            // the fireball into the floor, and the explosion is in the same place either way.
            return dodgeFireball(ctx, "lune.status.self_preservation.stepping_out_fireball_line");
        }

        ctx.input.reset();
        status.set(aimed
                ? "lune.status.self_preservation.waiting_fireball_reach"
                : "lune.status.self_preservation.lining_up_fireball_return");
        return TaskStatus.RUNNING;
    }

    /** Steps the body out of the shot's line without taking the camera off the shooter. */
    private TaskStatus dodgeFireball(BotContext ctx, String reasonKey) {
        if (++fireballDodgeTicks >= FIREBALL_DODGE_SWITCH_TICKS) {
            fireballDodgeTicks = 0;
            fireballDodgeLeft = !fireballDodgeLeft;
        }
        ctx.input.reset();
        Vec3 step = safeEmergencyDirection(ctx, sidestep(ctx, fireball));
        if (step == null) {
            status.set("lune.status.self_preservation.nowhere_to_step_from_fireball");
            return TaskStatus.RUNNING;
        }
        // The strafe keys, not the camera. Turning to face the way the body is going is how the
        // return aim is thrown away, and the next shot arrives down the same line as this one.
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, FireballDeflect.returnAim(ctx, fireball));
        ctx.input.steerToward(ctx.player, ctx.player.position().add(step));
        ctx.input.sprint = true;
        ctx.input.jump = needsEmergencyJump(ctx, step);
        status.set(reasonKey);
        return TaskStatus.RUNNING;
    }

    /**
     * A horizontal step that takes the body out of the shot's line.
     *
     * <p>Across the flight path rather than away from it. A fireball closes at up to 1.9 blocks a
     * tick, which nothing on foot outruns, and the blast reaches several blocks past where it lands -
     * so backing off changes how hard it hits and sideways changes whether it hits at all.
     */
    private Vec3 sidestep(BotContext ctx, Projectile shot) {
        Vec3 heading = shot.getDeltaMovement();
        heading = new Vec3(heading.x, 0.0, heading.z);
        if (heading.lengthSqr() < 1.0E-4) {
            heading = ctx.player.position().subtract(shot.position());
            heading = new Vec3(heading.x, 0.0, heading.z);
        }
        if (heading.lengthSqr() < 1.0E-4) {
            Vec3 across = Vec3.directionFromRotation(0.0F, ctx.player.getYRot() + 90.0F);
            return new Vec3(across.x, 0.0, across.z);
        }
        heading = heading.normalize();
        return fireballDodgeLeft
                ? new Vec3(-heading.z, 0.0, heading.x)
                : new Vec3(heading.z, 0.0, -heading.x);
    }

    private TaskStatus escapeMonster(BotContext ctx) {
        if (hostile == null || !hostile.isAlive()) {
            status.set("lune.status.self_preservation.threat_gone");
            return TaskStatus.SUCCESS;
        }
        if (hostile instanceof Creeper) {
            return escapeCreeper(ctx);
        }
        if (isEnderman(hostile)) {
            return escapeEnderman(ctx);
        }
        if (SelfPreservationPolicy.COVER_FIRST.equals(recoveryStrategy)) {
            return emergencyMonsterDefense(ctx, Lang.get("lune.reason.learned_cover_first"));
        }
        if (SelfPreservationPolicy.PILLAR_FIRST.equals(recoveryStrategy) && !pillarSecured) {
            EmergencyBuild pillar = buildUp(ctx, emergencyMaterial(ctx));
            if (pillar != EmergencyBuild.UNAVAILABLE) {
                pillarSecured = pillar == EmergencyBuild.SECURED;
                if (pillarSecured) {
                    status.set("lune.status.self_preservation.learned_pillar_response_secured_above", hostile.getName().getString());
                } else {
                    status.set("lune.status.self_preservation.trying_learned_pillar_response_against", hostile.getName().getString());
                }
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
            return emergencyMonsterDefense(ctx, Lang.get("lune.reason.retreat_timed_out"));
        }
        if (monsterEscapes >= MAX_ESCAPE_ATTEMPTS) {
            return emergencyMonsterDefense(ctx, Lang.get("lune.reason.retreat_limit_reached"));
        }
        if (recovery == null) {
            BlockPos away = retreatPoint(ctx, hostile);
            if (away == null) {
                return emergencyMonsterDefense(ctx, Lang.get("lune.reason.no_walking_route"));
            }
            recovery = new GotoTask(new Goals.Near(away, 2), true, false);
            recovery.start(ctx);
        }
        return tickRecovery(ctx, "lune.status.self_preservation.escaping_hostile",
                hostile.getName().getString());
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
                status.set("lune.status.self_preservation.equipping_weapon_fight", hostile.getName().getString());
                return TaskStatus.RUNNING;
            }
        }

        EmergencyBuild cover = buildCover(ctx, material);
        if (SelfPreservationPolicy.COVER_FIRST.equals(recoveryStrategy)
                && cover == EmergencyBuild.BUILDING) {
            status.set("lune.status.self_preservation.building_learned_cover_before_retreating");
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
            return emergencyMonsterDefense(ctx, Lang.get("lune.reason.direct_creeper_cover_handoff"));
        }

        // The direct sprint is intentionally cheap, but it cannot solve a one-block lip, a
        // water edge, or a partial route waypoint. Once the player has failed to change blocks for
        // a few ticks, hand the same hostile to the full emergency defense instead of waiting out
        // the fuse with W held against the obstruction.
        if (creeperStallTicks >= MAX_CREEPER_STALL_TICKS) {
            return emergencyMonsterDefense(ctx,
                    Lang.get("lune.reason.direct_creeper_retreat_blocked"));
        }

        // Placement aims at the support face, so movement is applied after it and wins for this
        // tick. Jumping lets the player clear a one-block lip instead of standing in the fuse.
        Vec3 safeAway = safeEmergencyDirection(ctx, away);
        if (safeAway == null) {
            return emergencyMonsterDefense(ctx, Lang.get("lune.reason.no_safe_emergency_step"));
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
            status.set("lune.status.self_preservation.behind_cover_from_creeper");
        } else if (cover == EmergencyBuild.BUILDING) {
            status.set("lune.status.self_preservation.sprinting_away_while_building_cover_from");
        } else if (cover == EmergencyBuild.SECURED) {
            status.set("lune.status.self_preservation.sprinting_away_behind_cover_from_creeper");
        } else {
            status.set("lune.status.self_preservation.sprinting_directly_away_from_creeper");
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus recoverHealth(BotContext ctx) {
        if (recovery == null && !hasEdibleFood(ctx)) {
            // No food is a resource shortage, not a reason to release control in a hostile
            // tunnel. Keep the monitor alive so a newly visible mob is handled on the next scan.
            ctx.input.reset();
            if (hostile != null && hostile.isAlive()) {
                status.set("lune.status.self_preservation.no_food_defending_from", hostile.getName().getString());
            } else {
                status.set("lune.status.self_preservation.no_food_staying_safe_until_health");
            }
            if (hostile != null && hostile.isAlive()) {
                threat = Threat.MONSTER;
                return emergencyMonsterDefense(ctx, Lang.get("lune.reason.no_food_for_recovery"));
            }
            return waitedLongEnoughForHealth();
        }
        if (recovery == null) {
            recovery = new EatTask(stack -> true, 20);
            recovery.start(ctx);
        }
        TaskStatus result = recovery.tick(ctx);
        status.set("lune.status.self_preservation.recovering_health", recovery.statusLine());
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
                return emergencyMonsterDefense(ctx, Lang.get("lune.reason.no_food_for_recovery"));
            }
            status.set("lune.status.self_preservation.no_food_staying_safe_until_health");
            return waitedLongEnoughForHealth();
        }
        if (result == TaskStatus.SUCCESS) {
            stopRecovery(ctx);
            status.set("lune.status.self_preservation.waiting_health_regeneration");
            if (++healthWaitTicks > MAX_HEALTH_WAIT_TICKS) {
                status.set("lune.status.self_preservation.health_did_not_recover");
                return TaskStatus.FAILED;
            }
        }
        return TaskStatus.RUNNING;
    }

    /**
     * Holds still for health, but not forever.
     *
     * <p>With nothing to eat there is nothing to wait for: vanilla only regenerates above 18 food,
     * so a hungry bot standing still is not healing, it is just not working. The cap already
     * existed and was only applied after a successful meal - the two no-food paths returned
     * RUNNING unconditionally. A plains chop run stood in the open for 115 seconds on "no food -
     * staying safe until health recovers", having taken seven falls and met a creeper, and died
     * there with a third of its budget unspent.</p>
     *
     * <p>Giving up hands control back to the job. That is the right answer when there is no threat
     * present: the danger has passed, the health has not come back, and standing in a field is no
     * safer than getting on with the work. A live hostile never reaches here - both callers divert
     * to {@code emergencyMonsterDefense} first.</p>
     */
    private TaskStatus waitedLongEnoughForHealth() {
        if (++healthWaitTicks > MAX_HEALTH_WAIT_TICKS) {
            status.set("lune.status.self_preservation.no_food_no_recovery_going_back_work");
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus tickRecovery(BotContext ctx, String actionKey, Object... args) {
        TaskStatus result = recovery.tick(ctx);
        if (recovery.statusLine().isBlank()) {
            status.set(actionKey, args);
        } else {
            status.set("lune.status.detail",
                    Lang.get(actionKey, args), recovery.statusLine());
        }
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
            return emergencyMonsterDefense(ctx, Lang.get("lune.reason.retreat_route_blocked"));
        }
        return ++escapeAttempts >= MAX_ESCAPE_ATTEMPTS ? TaskStatus.FAILED : TaskStatus.RUNNING;
    }

    /**
     * A route can be impossible in a one-wide mine shaft. A monster must not turn that into a
     * task failure: first build a two-high wall, then try a one-block pillar, then fight with
     * whatever the player is holding until there is a new opening.
     */
    private TaskStatus emergencyMonsterDefense(BotContext ctx, String reason) {
        stopRecovery(ctx);
        if (hostile == null || !hostile.isAlive()) {
            status.set("lune.status.self_preservation.threat_gone");
            return TaskStatus.SUCCESS;
        }

        // Swimming while a hostile is present is not a safe health-recovery state. The player can
        // be unable to place a wall because every adjacent face is water, and eating keeps the
        // monitor cycling through a stationary animation while drowned mobs close in. Surface
        // first; combat resumes once the eyes are in breathable air.
        if (ctx.player.isUnderWater()) {
            WaterEscape.tickToAir(ctx);
            status.set("lune.status.self_preservation.escaping_air_while_evading", hostile.getName().getString());
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
            status.set("lune.status.self_preservation.building_cover_from", hostile.getName().getString());
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
                return tickRecovery(ctx, "lune.status.self_preservation.retreating_behind_cover");
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
                if (pillar == EmergencyBuild.SECURED) {
                    status.set("lune.status.self_preservation.built_up_above_creeper");
                } else {
                    status.set("lune.status.self_preservation.building_upward_escape_creeper");
                }
                return TaskStatus.RUNNING;
            }
        }

        // No safe route and nothing useful to place. Equip a carried weapon before spending more
        // emergency blocks. A
        // route task can leave the sword in the main inventory after mining or collecting a drop;
        // swinging the currently selected block/tool in that state burns the few emergency ticks
        // available and lets a skeleton keep shooting. This monitor is shared by every task,
        // so the emergency handoff must repair the hand state here rather than rely on callers.
        ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!InventoryHelper.isCombatWeapon(held)) {
            if (equippedSomethingBetter(ctx, held)) {
                status.set("lune.status.self_preservation.equipping_weapon_fight", hostile.getName().getString());
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
            status.set("lune.status.self_preservation.fighting_from_emergency_cover", hostile.getName().getString());
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
            if (pillar == EmergencyBuild.SECURED) {
                status.set("lune.status.self_preservation.built_up_above", hostile.getName().getString());
            } else {
                status.set("lune.status.self_preservation.building_upward_escape", hostile.getName().getString());
            }
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
            if (equippedSomethingBetter(ctx, held)) {
                status.set("lune.status.self_preservation.equipping_weapon_while_retreating_from", hostile.getName().getString());
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
        String name = hostile.getName().getString();
        if (safeDirection == null) {
            status.set("lune.status.self_preservation.holding_ground_fighting", name);
        } else if (attacked) {
            status.set(meleeStallTicks >= 3
                    ? "lune.status.self_preservation.fighting_strafing"
                    : "lune.status.self_preservation.fighting_retreating", name);
        } else {
            status.set(meleeStallTicks >= 3
                    ? "lune.status.self_preservation.strafing_before_striking"
                    : "lune.status.self_preservation.retreating_before_striking", name);
        }
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
        if (concealed && distance >= CREEPER_SAFE_DISTANCE) {
            status.set("lune.status.self_preservation.behind_cover_from_creeper");
        } else {
            status.set("lune.status.self_preservation.retreating_from_creeper", reason);
        }
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
            status.set("lune.status.self_preservation.threat_gone");
            return TaskStatus.SUCCESS;
        }

        ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!InventoryHelper.isCombatWeapon(held)) {
            if (equippedSomethingBetter(ctx, held)) {
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
                if (pillar == EmergencyBuild.SECURED) {
                    status.set("lune.status.self_preservation.built_upward_while_evading_creeper");
                } else {
                    status.set("lune.status.self_preservation.building_upward_while_evading_creeper");
                }
                return TaskStatus.RUNNING;
            }
        }

        if (attacked) {
            status.set("lune.status.self_preservation.fighting_creeper_from_unsafe_ground", reason);
        } else {
            status.set("lune.status.self_preservation.no_safe_step_or_pillar_while_evading", reason);
        }
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
            status.set("lune.status.self_preservation.holding_safe_ground_while_evading", hostile.getName().getString());
            return TaskStatus.RUNNING;
        }
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, ctx.player.position().add(safeAway));
        ctx.input.forward = true;
        ctx.input.sprint = true;
        ctx.input.jump = needsEmergencyJump(ctx, safeAway);
        status.set("lune.status.self_preservation.moving_away_from_without_building", hostile.getName().getString());
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
        boolean unreachable = floatsOutOfReach(ctx, hostile);
        Vec3 desired = strafe;
        if (unreachable) {
            // Nothing to close on. Stay lateral - the fireball branch is what actually hurts a
            // Ghast, and it needs the bot alive and on the ground to take the swing.
            desired = strafe;
        } else if (distance > EMERGENCY_ATTACK_REACH + 0.4) {
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
            status.set("lune.status.self_preservation.holding_safe_ground_while_evading", hostile.getName().getString());
            return TaskStatus.RUNNING;
        }
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.urgent();
        ctx.look.lookAt(ctx.player, ctx.player.position().add(safeDirection));
        ctx.input.forward = true;
        ctx.input.sprint = true;
        ctx.input.jump = needsEmergencyJump(ctx, safeDirection);
        status.set(unreachable
                        ? "lune.status.self_preservation.strafing_out_line_of_fire_from"
                        : "lune.status.self_preservation.strafing_out_arrow_line_from",
                hostile.getName().getString());
        return TaskStatus.RUNNING;
    }

    /**
     * Whether a ranged hostile is floating somewhere a walk cannot get to.
     *
     * <p>{@link #evadeRangedThreat} closes the gap on purpose, and for a skeleton that is right: it
     * stops shooting once there is a sword in its face. A Ghast never comes down, and there is no
     * ground route to one - so the same code turns into a sprint across open Nether floor toward
     * something that cannot be reached, ending at whatever the terrain allows with the Ghast still
     * shooting. For anything hovering further above the eye than an arm can reach, the strafe stays
     * lateral and the fireball branch does the killing.
     */
    private static boolean floatsOutOfReach(BotContext ctx, LivingEntity hostile) {
        return hostile.getBoundingBox().minY - ctx.player.getEyeY() > EMERGENCY_ATTACK_REACH;
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
            status.set("lune.status.self_preservation.under_two_block_shelter_from_enderman");
            return TaskStatus.RUNNING;
        }
        return defendEnderman(ctx, Lang.get("lune.reason.no_shelter_yet"));
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
            status.set("lune.status.self_preservation.under_two_block_shelter_from_enderman");
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
                status.set("lune.status.self_preservation.escaping_enderman_without_eye_contact", reason);
            } else {
                lookAtSafeGround(ctx);
                status.set("lune.status.self_preservation.no_safe_enderman_shelter_or_step", reason);
            }
            return TaskStatus.RUNNING;
        }
        if (endermanShelterMaterial == null) {
            endermanShelterMaterial = BlockPlacer.findSolidMaterial(ctx);
            if (endermanShelterMaterial == null) {
                status.set("lune.status.self_preservation.no_solid_block_enderman_shelter");
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
                status.set("lune.status.self_preservation.building_enderman_shelter_roof");
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
            if (placement == BlockPlacer.PlacementResult.PLACED || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
                status.set("lune.status.kill.building_enderman_shelter_support");
            } else {
                status.set("lune.status.kill.placing_enderman_shelter_support");
            }
            return TaskStatus.RUNNING;
        }
        lookAtSafeGround(ctx);
        status.set("lune.status.self_preservation.avoiding_enderman_eye_contact_while");
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

    /**
     * The block an emergency is allowed to spend, or null when there is nothing to spend.
     *
     * <p>Also where the build toggle lands, because this is the one question every emergency
     * placement asks first - the wall, the pillar, and the cover the Creeper branch tries on its way
     * past. The Enderman shelter deliberately goes around it: a roof is the only answer to an
     * Enderman there is, and switching off walls should not leave the bot looking one in the eye.
     */
    private Block emergencyMaterial(BotContext ctx) {
        if (!options.buildCover()) {
            return null;
        }
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
        com.etka.lune.bot.LuneProfiler.push("threat scan");
        try {
            return scanForThreat(ctx);
        } finally {
            com.etka.lune.bot.LuneProfiler.pop();
        }
    }

    private LivingEntity scanForThreat(BotContext ctx) {
        AABB box = ctx.player.getBoundingBox().inflate(threatScanRadius());
        // Line of sight decides whether a mob that has done nothing yet is worth reacting to. It
        // must not decide whether one that is *currently shooting* counts, which is what ANDing it
        // over everything did: a skeleton firing from behind a rise was filtered out of the threat
        // list entirely, so the bot stood there being shot down to two hearts as though not seeing
        // the archer meant the archer could not see it. Being hit is knowing.
        //
        // Only the cheap tests run inside the query. The raycast is the expensive half and it used
        // to run on every candidate before the nearest was chosen, so a crowded night cost dozens
        // of full block traces to answer a question about one mob. Sorting first and tracing in
        // order gives the same answer - the nearest that is visible or has just hit us - and
        // usually stops at the first.
        List<Entity> found = ctx.level.getEntities(ctx.player, box, entity ->
                entity instanceof LivingEntity living && living.isAlive()
                        && (isDangerousHostile(entity)
                                || (isNeutralUntilProvoked(entity) && justAttackedThePlayer(ctx, living))
                                || isRecentPlayerAttacker(ctx, living)));
        cachedNearest = found.stream()
                .map(LivingEntity.class::cast)
                .sorted(Comparator.comparingDouble(entity -> entity.distanceToSqr(ctx.player)))
                .filter(living -> hasLineOfSight(ctx, living) || justAttackedThePlayer(ctx, living))
                .findFirst()
                .orElse(null);
        return cachedNearest;
    }

    /**
     * How far to look for threats: the furthest distance anything in this class actually asks
     * about, plus a little room to see one coming.
     *
     * <p>Scaled to the configured monster distance rather than fixed, because the cost is cubic in
     * the radius. At the default eight blocks this gathers a box roughly forty times smaller than
     * the old flat sixty-four.</p>
     */
    private int threatScanRadius() {
        double needed = Math.max(monsterDistance + 8.0, Math.max(CREEPER_ALERT_DISTANCE, 16.0));
        return (int) Math.min(MAX_THREAT_SCAN_RADIUS, Math.ceil(needed) + 4);
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
        double releaseDistance = Math.min(threatScanRadius(),
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
     * taking control of. Seizing the keys there would only mean the task loses them for the last
     * second of its life.
     */
    private boolean canClutch(BotContext ctx) {
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
        // The cushion belongs in this list, and used to be missing from it. Taking control needed a
        // bucket or a boat, so a bot carrying nothing but a slime block fell past every landing it
        // could have made. It matters more now the three can be switched off one at a time: without
        // it, turning the bucket and the boat off would quietly take the cushion with them.
        return canWaterClutch(ctx) || canBoatClutch(ctx) || !carriedCushions(ctx).isEmpty();
    }

    /**
     * Water is the better clutch wherever it works at all: one packet, no round trip, and it can
     * still be poured on the tick before impact. The Nether is where it stops working - a bucket
     * emptied there evaporates on the spot - and used to be where this monitor gave up entirely.
     */
    private boolean canWaterClutch(BotContext ctx) {
        return options.waterClutch() && hasWaterBucket(ctx.player)
                && !ctx.level.dimension().equals(Level.NETHER);
    }

    private boolean canBoatClutch(BotContext ctx) {
        return options.boatClutch() && BoatHelper.carrying(ctx.player);
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
            status.set("lune.status.self_preservation.landed_safely");
            return TaskStatus.SUCCESS;
        }
        if (bucketPlaced) {
            status.set("lune.status.self_preservation.falling_through_water");
            return TaskStatus.RUNNING;
        }
        if (clutchTicks++ > CLUTCH_TIMEOUT) {
            status.set("lune.status.self_preservation.could_not_clutch_time");
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
            status.set("lune.status.self_preservation.nothing_left_clutch_with");
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
            status.set("lune.status.self_preservation.nothing_soft_land");
            return TaskStatus.FAILED;
        }
        if (cushionPos != null) {
            status.set("lune.status.self_preservation.falling_onto", cushion.block().getName().getString());
            return TaskStatus.RUNNING;
        }

        ctx.look.urgent();
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);

        BlockHitResult landing = landingSurface(ctx, CLUTCH_SCAN_DEPTH, ClipContext.Fluid.NONE);
        if (landing == null) {
            ctx.look.lookAtRotation(ctx.player, ctx.player.getYRot(), STRAIGHT_DOWN);
            status.set("lune.status.self_preservation.nothing_below_soften_yet");
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
            status.set("lune.status.self_preservation.could_not_get_landing_down_time");
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
            status.set("lune.status.self_preservation.landing", cushion.block().getName().getString());
            return TaskStatus.RUNNING;
        }
        if (placement.isTransient()) {
            status.set("lune.status.self_preservation.putting_under_landing", cushion.block().getName().getString());
        } else {
            status.set("lune.status.self_preservation.cannot_place_down", cushion.block().getName().getString());
        }
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
        if (!options.cushionClutch()) {
            // One gate for the whole method rather than one per caller: everything that asks what to
            // land on - what is placeable, what is softest, whether a clutch is possible at all -
            // asks it through here.
            return carried;
        }
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
            status.set("lune.status.self_preservation.landing_already_gone");
            return TaskStatus.SUCCESS;
        }
        if (!ctx.player.onGround() && !ctx.player.isInWater()) {
            // A slime block throws the player back up, so "landed" can be a bounce in progress.
            // Swinging at a block from mid-air is how the recovery talks itself out of reach.
            status.set("lune.status.self_preservation.waiting_settle");
            return TaskStatus.RUNNING;
        }
        if (++cushionRecoverTicks > MAX_CUSHION_RECOVER_TICKS
                || cushionBreaker.isOutOfReach(ctx, cushionPos)) {
            cushionBreaker.stop(ctx);
            status.set("lune.status.self_preservation.left_landing_behind");
            return TaskStatus.SUCCESS;
        }
        BlockBreaker.Progress progress = cushionBreaker.tick(ctx, cushionPos);
        if (progress == BlockBreaker.Progress.NO_TOOL) {
            cushionBreaker.stop(ctx);
            status.set("lune.status.self_preservation.nothing_dig_landing_back_up_with");
            return TaskStatus.SUCCESS;
        }
        status.set("lune.status.self_preservation.picking_landing_back_up");
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
                status.set("lune.status.self_preservation.waiting_boat_appear");
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
            status.set("lune.status.self_preservation.falling_toward_boat");
            return TaskStatus.RUNNING;
        }
        if (ctx.player.isShiftKeyDown()) {
            // Sneaking cancels boarding, and the interaction packet carries the shift state, so
            // sending it now would only tell the server we meant to crouch. The bot's own keys are
            // cleared every tick, so this can only be one tick of a habit from another branch.
            boatSneakBlocks++;
            status.set("lune.status.self_preservation.standing_up_board_boat");
            return TaskStatus.RUNNING;
        }
        boatBoardAttempts++;
        BoatHelper.board(ctx, hull);
        status.set("lune.status.boat.getting_into_boat");
        return TaskStatus.RUNNING;
    }

    private TaskStatus placeClutchBoat(BotContext ctx, BlockHitResult landing) {
        if (boatPlacements >= MAX_BOAT_PLACEMENTS) {
            // Out of attempts, but not out of fall: keep the head down and the body over the
            // landing anyway. This is a hard landing, not a reason to stop the run - and the
            // ground may still turn into somewhere a hull fits before it arrives.
            status.set("lune.status.self_preservation.nowhere_put_another_boat");
            return TaskStatus.RUNNING;
        }
        if (BoatHelper.equip(ctx) < 0) {
            // Nothing left to put down. If one was already spent on this fall it is somewhere
            // below us and the aim above is still worth holding, so that is not a run-stopping
            // failure - only having had nothing from the start is.
            if (boatPlacements > 0) {
                status.set("lune.status.self_preservation.boat_already_spent_fall");
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.self_preservation.no_boat_clutch_with");
            return TaskStatus.FAILED;
        }
        if (!BoatHelper.inHand(ctx)) {
            status.set("lune.status.self_preservation.taking_out_boat");
            return TaskStatus.RUNNING;
        }
        if (landing == null) {
            // Nothing to aim at yet, but the head is already in position for when there is.
            status.set("lune.status.self_preservation.nothing_below_put_boat_yet");
            return TaskStatus.RUNNING;
        }

        Vec3 hullAt = BoatHelper.placementTarget(ctx);
        ctx.debug.target("boat clutch", landing.getBlockPos(), String.format(
                "feet=%.2f landing=%.2f hull=%s",
                ctx.player.getY(), landing.getLocation().y,
                hullAt == null ? "out-of-reach"
                        : String.format("%.1f/%.1f/%.1f", hullAt.x, hullAt.y, hullAt.z)));

        if (hullAt == null || hullAt.y > ctx.player.getY()) {
            status.set("lune.status.self_preservation.ground_blocks_below_boats_reach", ctx.player.getY() - landing.getLocation().y - reachBelowFeet(ctx));
            return TaskStatus.RUNNING;
        }
        if (!BoatHelper.fits(ctx, hullAt)) {
            // A hull is nearly a block and a half across and has to land flat. A shaft, a crevice
            // or a one-block ledge has nowhere to put one, and falling closer will not change that.
            status.set("lune.status.self_preservation.no_room_boat_down");
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
        status.set("lune.status.self_preservation.putting_boat_down_land");
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
        int landingTicks = ctx.player.tickCount - boatPlaceTick;
        String account = String.format(
                "hull placed %.1f blocks up at %.2f blocks/tick; %d ticks to landing, "
                        + "%d boarding packets sent, %d skipped for sneak",
                boatPlaceDrop, boatPlaceSpeed, landingTicks,
                boatBoardAttempts, boatSneakBlocks);
        boatPlaceTick = Integer.MIN_VALUE;
        String spoken = Lang.get("lune.chat.boat_clutch.account",
                String.format(java.util.Locale.ROOT, "%.1f", boatPlaceDrop),
                String.format(java.util.Locale.ROOT, "%.2f", boatPlaceSpeed),
                landingTicks, boatBoardAttempts, boatSneakBlocks);
        if (boatBoarded) {
            ctx.chat(Lang.get("lune.chat.boat_clutch.worked", spoken));
            return;
        }
        ctx.debug.recordFailure(name() + "/boat clutch", account);
        ctx.chat(Lang.get("lune.chat.boat_clutch.missed", spoken));
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
            status.set("lune.status.self_preservation.boat_fall_cancelled");
            return TaskStatus.RUNNING;
        }
        boolean settled = hull.onGround() || hull.isInWater() || !hull.isAlive();
        if (settled || boatRideTicks > MAX_BOAT_RIDE_TICKS) {
            // Sneak is how a passenger leaves a vehicle, and the input layer already owns it.
            ctx.input.sneak = true;
            status.set("lune.status.boat.getting_out_boat");
            return TaskStatus.RUNNING;
        }
        status.set("lune.status.self_preservation.riding_boat_down");
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
     * seated has left every task after it rowing instead of walking, and nothing downstream
     * knows to check. Breaking the hull afterwards is optional - it is five planks, and one of them
     * may be the next fall, so it is worth a few seconds and not worth a fight.
     */
    private TaskStatus recoverBoat(BotContext ctx) {
        if (!ctx.player.isAlive()) {
            return TaskStatus.FAILED;
        }
        if (BoatHelper.ridden(ctx) != null) {
            ctx.input.sneak = true;
            status.set("lune.status.boat.getting_out_boat");
            return TaskStatus.RUNNING;
        }
        if (!ctx.player.onGround() && !ctx.player.isInWater()) {
            status.set("lune.status.self_preservation.waiting_land");
            return TaskStatus.RUNNING;
        }
        AbstractBoat hull = BoatHelper.nearest(ctx, BOAT_SEARCH_RADIUS);
        if (hull == null) {
            status.set("lune.status.self_preservation.boat_gone");
            return TaskStatus.SUCCESS;
        }
        if (++boatRecoverTicks > MAX_BOAT_RECOVER_TICKS) {
            status.set("lune.status.self_preservation.left_boat_behind");
            return TaskStatus.SUCCESS;
        }
        Vec3 centre = hull.getBoundingBox().getCenter();
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.lookAt(ctx.player, centre);
        if (!ctx.look.isLookingAt(ctx.player, centre, CLUTCH_AIM_TOLERANCE)) {
            status.set("lune.status.self_preservation.looking_boat");
            return TaskStatus.RUNNING;
        }
        if (!BoatHelper.withinBoardingRange(ctx, hull)) {
            status.set("lune.status.self_preservation.boat_out_reach");
            return TaskStatus.SUCCESS;
        }
        BoatHelper.strike(ctx, hull);
        status.set("lune.status.boat.picking_boat_back_up");
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
            status.set("lune.status.self_preservation.no_water_bucket");
            return TaskStatus.FAILED;
        }

        int slot = InventoryHelper.equip(ctx, stack -> stack.is(Items.WATER_BUCKET));
        if (slot < 0) {
            status.set("lune.status.self_preservation.no_water_bucket_hotbar");
            return TaskStatus.FAILED;
        }

        ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!held.is(Items.WATER_BUCKET)) {
            status.set("lune.status.self_preservation.equipping_water_bucket");
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
            status.set("lune.status.self_preservation.nothing_below_land_yet");
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
            status.set("lune.status.self_preservation.crouching_pour_top");
            return TaskStatus.RUNNING;
        }

        BlockPos wouldLand = BucketHelper.placementTarget(ctx);
        ctx.debug.target("water clutch", ground, String.format(
                "feet=%.2f landing=%.2f ray=%s crouched=%s",
                ctx.player.getY(), landing.getLocation().y,
                wouldLand == null ? "out-of-reach" : wouldLand.toShortString(),
                ctx.player.isShiftKeyDown()));

        if (wouldLand == null || !catchesTheFall(ctx, wouldLand, landing.getLocation().y)) {
            if (wouldLand == null) {
                status.set("lune.status.self_preservation.ground_blocks_below_buckets_reach", ctx.player.getY() - landing.getLocation().y - reachBelowFeet(ctx));
            } else {
                status.set("lune.status.self_preservation.lining_up_over_landing");
            }
            return TaskStatus.RUNNING;
        }

        BucketHelper.use(ctx);

        held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.is(Items.BUCKET)) {
            bucketPlaced = true;
            clutchWater = wouldLand;
            status.set("lune.status.self_preservation.water_placed");
        } else {
            status.set("lune.status.self_preservation.placing_water");
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
            status.set("lune.status.self_preservation.water_gone");
            return TaskStatus.SUCCESS;
        }

        if (!ctx.player.onGround() && !ctx.player.isInWater()) {
            status.set("lune.status.self_preservation.waiting_land");
            return TaskStatus.RUNNING;
        }

        int slot = InventoryHelper.equip(ctx, stack -> stack.is(Items.BUCKET));
        if (slot < 0) {
            status.set("lune.status.self_preservation.no_empty_bucket");
            return TaskStatus.SUCCESS;
        }

        ItemStack held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.is(Items.WATER_BUCKET)) {
            status.set("lune.status.self_preservation.water_bucket_recovered");
            return TaskStatus.SUCCESS;
        }
        if (!held.is(Items.BUCKET)) {
            status.set("lune.status.self_preservation.bucket_not_hand");
            return TaskStatus.SUCCESS;
        }

        Vec3 target = Vec3.atCenterOf(waterPos);
        ctx.look.setMaxTurnPerTick(CLUTCH_TURN_SPEED);
        ctx.look.lookAt(ctx.player, target);
        if (!ctx.look.isLookingAt(ctx.player, target, CLUTCH_AIM_TOLERANCE)) {
            status.set("lune.status.self_preservation.looking_water");
            return TaskStatus.RUNNING;
        }

        if (!waterPos.equals(BucketHelper.pickupTarget(ctx))) {
            status.set("lune.status.self_preservation.water_out_reach");
            return TaskStatus.SUCCESS;
        }

        BucketHelper.use(ctx);

        held = ctx.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.is(Items.WATER_BUCKET)) {
            status.set("lune.status.self_preservation.picked_water_back_up");
            return TaskStatus.SUCCESS;
        }

        status.set("lune.status.self_preservation.collecting_water");
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
        ctx.debug.safety = "";
        threat = Threat.NONE;
        hostile = null;
        forgetFireball();
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
        status.set("lune.status.stay_near.watching");
    }

    @Override
    public void onStop(BotContext ctx) {
        onControlReleased(ctx);
    }

    /**
     * Puts the best carried weapon in hand, and says whether that changed anything.
     *
     * <p>The two halves of this used to disagree, and the disagreement was a loop.
     * {@code equipCombatWeapon} falls back to anything carrying the WEAPON component - a pickaxe
     * does - while {@code isCombatWeapon} accepts only a sword, axe or bow. A bot with no sword
     * therefore equipped its pickaxe, was told the pickaxe is not a weapon, and equipped it again,
     * every tick, for as long as the fight lasted. Measured: 644 snapshots of "equipping a weapon
     * to fight Drowned" while a trident killed it.</p>
     *
     * <p>Equipping the pickaxe is right - it hits harder than the dirt that route work leaves in
     * the hand. Re-equipping it forever is not. Returning false once the hand already holds the
     * best there is lets the caller get on with the fight, which is what its own comment says it
     * means to do.</p>
     */
    private boolean equippedSomethingBetter(BotContext ctx, ItemStack before) {
        if (InventoryHelper.equipCombatWeapon(ctx) < 0) {
            return false;
        }
        return ctx.player.getItemInHand(InteractionHand.MAIN_HAND).getItem() != before.getItem();
    }

    private void stopRecovery(BotContext ctx) {
        if (recovery != null) {
            recovery.stop(ctx);
            recovery = null;
        }
    }
}
