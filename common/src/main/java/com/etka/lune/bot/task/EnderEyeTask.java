package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.knowledge.StrongholdKnowledge;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.Vision;
import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.Discovery;
import com.etka.lune.waypoint.DiscoveryStore;
import com.etka.lune.waypoint.Waypoint;
import com.etka.lune.waypoint.WaypointStore;
import com.etka.lune.waypoint.external.ExternalWaypointSources;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the nearest stronghold with one Eye of Ender, by reading its flight rather than chasing it.
 *
 * <p>The old way was a speedrunner's first attempt: throw, run after the eye, throw again every
 * hundred blocks until one dives. That spends an eye a throw one time in five and does not know
 * where it is going until it is standing on it. {@link EnderEyePolicy} reads the whole answer out
 * of one flight instead - the eye flies a straight line at a chunk corner on a known ring - and
 * this card throws, watches, picks the eye back up and has somewhere to walk to.</p>
 *
 * <p>When the line passes close to more than one corner, which from near spawn is about one throw
 * in ten, walking to the nearest settles it: every candidate is on the same line, so nothing is
 * walked twice, and within twelve blocks of the stronghold a second eye simply dives into it. The
 * answer is remembered in {@link DiscoveryStore} under the stronghold's own id, the same entry
 * Find Structure writes, so neither card asks twice.</p>
 *
 * <p>The flight is read by watching it. A position counts only while the eye is in sight - at
 * arm's length as it leaves the hand, and after that in view with nothing between, by the rule
 * every living thing is seen by - and the head stays on the eye for the whole flight. An eye that
 * went behind a tree is a direction and nothing more: the card picks it up along that line and
 * steps round the tree to a spot the next flight can be watched from, as a player does.</p>
 *
 * <p>The eye has to be in the inventory. This card throws what it is given; getting eyes is a
 * craft card's job, in front of it.</p>
 */
public final class EnderEyeTask implements Task {

    /** The id a stronghold is remembered under, the same one an Explorer's Compass answers to. */
    public static final String STRONGHOLD = "minecraft:stronghold";

    /** Ticks with the eye in hand and the feet still before throwing: the throw starts at the feet. */
    private static final int SETTLE_TICKS = 3;
    /** Standing still is waited for, not insisted on; a player on ice still gets to throw. */
    private static final int MAX_SETTLE_TICKS = 40;
    private static final float THROW_PITCH = -20.0F;
    /** An eye that has not appeared by now was never launched. */
    private static final int LAUNCH_WAIT_TICKS = 40;
    /** Vanilla's eye lives 80 ticks; a little longer covers the last report arriving. */
    private static final int WATCH_TICKS = 95;
    /** A spent eye becomes an item where it died, in the same moment; this lets it arrive. */
    private static final int DROP_WAIT_TICKS = 6;
    private static final int PICKUP_RADIUS = 24;
    private static final int PICKUP_PATIENCE = 200;
    /** How long to spend digging or climbing to an eye the ordinary pick-up could not reach. */
    private static final int DIG_FOR_EYE_TICKS = 300;
    /** How near a candidate to walk before throwing again to be sure of it. */
    private static final int CHECK_DISTANCE = 6;
    /** How far along the line to walk before throwing again, when no corner fits it. */
    private static final double FOLLOW_DISTANCE = 256.0;
    /** Within this of the hand the eye is where it is known to be: it has only just left. */
    private static final double ARMS_LENGTH = 1.5;
    /** How far along its line the eye has to have been seen before the line is worth stepping by. */
    private static final double BEARING_AFTER = 0.75;
    /** An eye lost to sight is looked for about this much further along its line each tick. */
    private static final double LOST_EYE_SPEED = 0.3;
    /** An eye lying higher above the bot than half this is not dug or climbed to. */
    private static final int REACH_CLIMB = 6;
    /** Clearings stepped to for one card, so a forest cannot keep it stepping for good. */
    private static final int MAX_CLEARINGS = 4;
    /** A step to a clearing is a few blocks; longer than this and it is not that. */
    private static final int CLEARING_TICKS = 200;
    private static final int MAX_THROWS = 8;

    private enum Phase { START, READY, THROWN, WATCHING, PICKING_UP, CLEARING, DECIDE, WALKING, DONE }

    private final String then;
    private final int tolerance;
    private final boolean fresh;
    private final StatusText status = new StatusText();

    private Phase phase = Phase.START;
    private int ticks;
    private int throwsMade;
    private Set<Integer> eyesBefore = Set.of();
    private int eyeId = -1;
    private double originX;
    private double originY;
    private double originZ;
    private final List<Vec3> path = new ArrayList<>();
    private Vec3 lastHeard;
    private EnderEyePolicy.Reading reading;
    private List<EnderEyePolicy.Candidate> candidates = List.of();
    /** The chunk corner the eye points at, once read or guessed. */
    private BlockPos stronghold;
    private boolean confirmed;
    private int walkX;
    private int walkZ;
    /** Throw again on arriving: the walk was to check a candidate, or to follow a line. */
    private boolean throwOnArrival;
    /** No corner fitted the line, so the walk is along the line itself. */
    private boolean following;
    /** There is nothing left to check a guess with, so the guess is what gets walked to. */
    private boolean settled;
    /** Already written down, by this run or an earlier one. */
    private boolean remembered;
    private LootTask pickUp;
    /** An eye the pick-up gave up on, being dug or climbed to once. */
    private GotoTask digForEye;
    private int digTicks;
    private FarWalkTask walk;
    /** Where the eye was last seen, and the furthest along its line it was seen. */
    private Vec3 lastSeen;
    private Vec3 furthestSeen;
    /** Ticks of this flight the eye was out of sight for. */
    private int unseenTicks;
    /** Where the spent eye came down, once it is lying there. */
    private Vec3 dropAt;
    /** The last flight went unread because the eye went out of sight. */
    private boolean lostSight;
    private int clearings;
    /** A few blocks' walk to where the next flight can be watched from. */
    private GotoTask clearingWalk;
    private int clearingTicks;

    /** The speedrun's use: find it and walk there, trusting a stronghold already found. */
    public EnderEyeTask() {
        this(CompassFindTask.WALK_THERE, 4, false);
    }

    public EnderEyeTask(String then, int tolerance, boolean fresh) {
        this.then = then == null || then.isBlank() ? CompassFindTask.WALK_THERE : then;
        this.tolerance = Math.max(1, tolerance);
        this.fresh = fresh;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.ender_eye.find_stronghold");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Find Stronghold");
    }

    /** Throwing an eye is not a skill; the walk and the pick-up inside it learn for themselves. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    /** The chunk corner the eye pointed at, or null before one is known. */
    public BlockPos strongholdPos() {
        return stronghold;
    }

    @Override
    public void onStart(BotContext ctx) {
        phase = Phase.START;
        ticks = 0;
        throwsMade = 0;
        eyeId = -1;
        forgetFlight();
        reading = null;
        candidates = List.of();
        stronghold = null;
        confirmed = false;
        throwOnArrival = false;
        following = false;
        settled = false;
        remembered = false;
        clearings = 0;
    }

    /** Forgets one flight: what was seen of it, and where it fell. */
    private void forgetFlight() {
        path.clear();
        lastHeard = null;
        lastSeen = null;
        furthestSeen = null;
        unseenTicks = 0;
        dropAt = null;
        lostSight = false;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        return switch (phase) {
            case START -> begin(ctx);
            case READY -> aim(ctx);
            case THROWN -> launched(ctx);
            case WATCHING -> watch(ctx);
            case PICKING_UP -> pickUp(ctx);
            case CLEARING -> clearing(ctx);
            case DECIDE -> decide(ctx);
            case WALKING -> walk(ctx);
            case DONE -> TaskStatus.SUCCESS;
        };
    }

    private String dimension(BotContext ctx) {
        return ctx.level.dimension().identifier().toString();
    }

    private static String eyeName() {
        return Lang.get(Items.ENDER_EYE.getDescriptionId());
    }

    private TaskStatus begin(BotContext ctx) {
        if (!fresh) {
            Optional<Discovery> known = DiscoveryStore.get()
                    .find(Discovery.STRUCTURE, STRONGHOLD, dimension(ctx));
            if (known.isPresent()) {
                stronghold = new BlockPos(known.get().x(), 0, known.get().z());
                confirmed = true;
                remembered = true;
                status.set("lune.status.ender_eye.known", stronghold.getX(), stronghold.getZ());
                phase = Phase.DECIDE;
                return TaskStatus.RUNNING;
            }
        }
        return ready(ctx);
    }

    private TaskStatus ready(BotContext ctx) {
        return ready(ctx, null);
    }

    /**
     * Takes an eye in hand to throw, or settles for what is already known when there is none.
     *
     * @param why what to say while aiming, when this is not the first throw
     */
    private TaskStatus ready(BotContext ctx, String why) {
        boolean spent = throwsMade >= MAX_THROWS;
        if (spent || !InventoryHelper.has(ctx.player, Items.ENDER_EYE, 1)) {
            if (stronghold != null) {
                // A candidate was being checked and there is nothing left to check it with. The
                // best fit is still far better than nothing, and the status says it is a guess.
                settled = true;
                status.set("lune.status.ender_eye.best_guess", stronghold.getX(), stronghold.getZ(),
                        Math.max(1, candidates.size()));
                phase = Phase.DECIDE;
                return TaskStatus.RUNNING;
            }
            return spent ? fail("lune.status.ender_eye.too_many_throws", MAX_THROWS)
                    : fail("lune.status.ender_eye.no_eyes_ender");
        }
        if (InventoryHelper.equip(ctx, stack -> stack.is(Items.ENDER_EYE)) < 0) {
            return fail("lune.status.ender_eye.no_eyes_ender");
        }
        ctx.input.reset();
        ticks = 0;
        phase = Phase.READY;
        if (why == null) {
            status.set("lune.status.compass.holding", eyeName());
        } else {
            status.set(why);
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus aim(BotContext ctx) {
        ctx.input.reset();
        // A player throws an eye up and away, never at their feet - and never at a portal frame,
        // which would take the eye instead of sending it off.
        ctx.look.lookAtRotation(ctx.player, ctx.player.getYRot(), THROW_PITCH);
        ticks++;
        boolean still = ctx.player.onGround()
                && ctx.player.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4;
        if (ticks < SETTLE_TICKS || (!still && ticks < MAX_SETTLE_TICKS)) {
            return TaskStatus.RUNNING;
        }
        if (!ctx.player.getMainHandItem().is(Items.ENDER_EYE)) {
            return ready(ctx);
        }
        eyesBefore = new HashSet<>();
        for (EyeOfEnder eye : nearbyEyes(ctx)) {
            eyesBefore.add(eye.getId());
        }
        // The eye starts where the server has the player standing, halfway up. Standing still
        // means that is here, to the last digit; the eye's own first report confirms it below.
        originX = ctx.player.getX();
        originY = ctx.player.getY(0.5);
        originZ = ctx.player.getZ();
        ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
        ctx.gameMode.swing(InteractionHand.MAIN_HAND);
        throwsMade++;
        ticks = 0;
        eyeId = -1;
        forgetFlight();
        phase = Phase.THROWN;
        status.set("lune.status.ender_eye.threw_eye");
        return TaskStatus.RUNNING;
    }

    private List<EyeOfEnder> nearbyEyes(BotContext ctx) {
        AABB box = ctx.player.getBoundingBox().inflate(16.0);
        return ctx.level.getEntities(EntityTypeTest.forClass(EyeOfEnder.class), box, Entity::isAlive);
    }

    private TaskStatus launched(BotContext ctx) {
        for (EyeOfEnder eye : nearbyEyes(ctx)) {
            if (eyesBefore.contains(eye.getId())) {
                continue;
            }
            Vec3 first = eye.getPositionCodec().getBase();
            if (Math.hypot(first.x - originX, first.z - originZ) > 1.5) {
                continue; // somebody else's eye
            }
            eyeId = eye.getId();
            ticks = 0;
            phase = Phase.WATCHING;
            status.set("lune.status.ender_eye.tracking_eye");
            return watch(ctx);
        }
        if (++ticks >= LAUNCH_WAIT_TICKS) {
            // The server launches nothing where no stronghold can be found - the Nether, the End,
            // a world generated without them - and keeps the eye.
            return fail("lune.status.ender_eye.eye_would_not_fly");
        }
        status.set("lune.status.ender_eye.waiting_eye", ticks);
        return TaskStatus.RUNNING;
    }

    /**
     * Watches the flight, and reads only what is watched.
     *
     * <p>The eye is a glowing thing in the air and a player keeps their eyes on it, so the head
     * stays on it for the whole flight; the card used to run for where it would come down and
     * read it with its back turned. A position counts only while the eye is in sight: at arm's
     * length as it leaves the hand, which is known, and after that in view with nothing between
     * it and the eyes - the rule every living thing is seen by. Out of sight, the head follows
     * where it should be along the line it was last seen on, and nothing is written down.</p>
     */
    private TaskStatus watch(BotContext ctx) {
        Entity entity = ctx.level.getEntity(eyeId);
        if (entity instanceof EyeOfEnder eye && eye.isAlive() && ticks++ < WATCH_TICKS) {
            // What the server last said, not where the client has since guessed it drifted: the
            // guess is extrapolated from a rounded speed and bends the line.
            Vec3 heard = eye.getPositionCodec().getBase();
            double fromHand = Math.hypot(heard.x - originX, heard.z - originZ);
            if (lastHeard == null && fromHand < 0.05) {
                // The launch position straight from the server, exactly: where the hand was.
                originX = heard.x;
                originY = heard.y;
                originZ = heard.z;
            }
            boolean seen = fromHand <= ARMS_LENGTH || Vision.isEntityVisible(ctx, eye);
            if (seen) {
                if (!heard.equals(lastHeard)) {
                    path.add(heard);
                }
                lastSeen = eye.position();
                if (fromHand >= BEARING_AFTER && (furthestSeen == null
                        || fromHand > Math.hypot(furthestSeen.x - originX, furthestSeen.z - originZ))) {
                    furthestSeen = heard;
                }
                ctx.look.lookAt(ctx.player, eye.position());
                status.set("lune.status.ender_eye.tracking_eye");
            } else {
                unseenTicks++;
                lostSight = true;
                Vec3 expected = expectedEyePosition();
                if (expected != null) {
                    ctx.look.lookAt(ctx.player, expected);
                }
                status.set("lune.status.ender_eye.eye_out_of_sight");
            }
            lastHeard = heard;
            return TaskStatus.RUNNING;
        }
        interpret(ctx);
        ticks = 0;
        phase = Phase.PICKING_UP;
        return TaskStatus.RUNNING;
    }

    /**
     * Where an eye that is out of sight should be by now: on the line it was last seen on, a
     * little further along each tick. That is where a player who lost it looks, and all the card
     * knows of it until it is seen again.
     */
    private Vec3 expectedEyePosition() {
        if (lastSeen == null) {
            return null;
        }
        double dx = lastSeen.x - originX;
        double dz = lastSeen.z - originZ;
        double along = Math.hypot(dx, dz);
        if (along < 0.05) {
            return lastSeen;
        }
        double ahead = Math.min(EnderEyePolicy.STEER, along + LOST_EYE_SPEED * unseenTicks);
        return new Vec3(originX + dx / along * ahead, lastSeen.y, originZ + dz / along * ahead);
    }

    private void interpret(BotContext ctx) {
        reading = EnderEyePolicy.read(originX, originY, originZ, path);
        ctx.debug.decide("eye reading " + reading.kind() + " from " + path.size() + " reports seen, "
                + unseenTicks + " ticks out of sight");
        following = false;
        switch (reading.kind()) {
            case NEAR -> {
                stronghold = new BlockPos(reading.nearX(), 0, reading.nearZ());
                confirmed = true;
                candidates = List.of();
                status.set("lune.status.ender_eye.eye_dove", stronghold.getX(), stronghold.getZ());
            }
            case FAR -> {
                double furthest = StrongholdKnowledge.nearestAtMost(originX, originZ) + 16.0;
                candidates = EnderEyePolicy.candidates(reading, furthest);
                if (candidates.isEmpty()) {
                    // Nothing on the line is where vanilla would put a stronghold - a datapack
                    // moved them, or a mod aims the eye elsewhere. The line itself is still right.
                    double[] ahead = reading.pointAlong(FOLLOW_DISTANCE);
                    walkX = (int) Math.floor(ahead[0]);
                    walkZ = (int) Math.floor(ahead[1]);
                    following = true;
                    stronghold = null;
                    status.set("lune.status.ender_eye.following_eye");
                } else if (candidates.size() == 1) {
                    EnderEyePolicy.Candidate only = candidates.get(0);
                    stronghold = new BlockPos(only.x(), 0, only.z());
                    confirmed = true;
                    status.set("lune.status.ender_eye.read_one", only.x(), only.z(),
                            (int) Math.round(only.distance()));
                } else {
                    EnderEyePolicy.Candidate best = EnderEyePolicy.bestGuess(candidates);
                    stronghold = new BlockPos(best.x(), 0, best.z());
                    confirmed = false;
                    status.set("lune.status.ender_eye.read_several", candidates.size());
                }
            }
            case UNREADABLE -> {
                double[] way = lostSight ? bearing() : null;
                if (way == null) {
                    status.set("lune.status.ender_eye.unreadable");
                } else {
                    status.set("lune.status.ender_eye.lost_sight",
                            (int) Math.floor(originX + way[0] * EnderEyePolicy.STEER),
                            (int) Math.floor(originZ + way[1] * EnderEyePolicy.STEER));
                }
            }
        }
    }

    /** Picks the thrown eye back up - most survive the flight and land a few blocks along it. */
    private TaskStatus pickUp(BotContext ctx) {
        if (ticks++ < DROP_WAIT_TICKS) {
            return TaskStatus.RUNNING;
        }
        if (digForEye != null) {
            return digForEye(ctx);
        }
        if (pickUp == null) {
            // Where it fell is along its line, and a player who lost sight of the eye still sees
            // the glint of it on the ground: the way it went, when nothing else says.
            ItemEntity fallen = nearestEye(ctx, false);
            if (fallen != null) {
                dropAt = fallen.position();
            }
            pickUp = new LootTask(PICKUP_RADIUS, PICKUP_PATIENCE, stack -> stack.is(Items.ENDER_EYE));
            pickUp.start(ctx);
        }
        TaskStatus result = pickUp.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            status.set("lune.status.ender_eye.picking_up_eye", pickUp.statusLine());
            return TaskStatus.RUNNING;
        }
        pickUp.stop(ctx);
        pickUp = null;
        ItemEntity left = eyeLeftBehind(ctx);
        if (left != null && worthDiggingFor(ctx, left)) {
            // The ordinary pick-up only walks. An eye up a bank or behind a block needs a block
            // climbed or broken, which is what a player does for something this dear.
            digForEye = new GotoTask(new Goals.Near(left.blockPosition(), 1), false, true);
            digForEye.start(ctx);
            digTicks = 0;
            return TaskStatus.RUNNING;
        }
        return afterPickUp(ctx);
    }

    private TaskStatus digForEye(BotContext ctx) {
        TaskStatus result = digForEye.tick(ctx);
        boolean still = eyeLeftBehind(ctx) != null;
        if (result == TaskStatus.RUNNING && still && ++digTicks < DIG_FOR_EYE_TICKS) {
            status.set("lune.status.ender_eye.picking_up_eye", digForEye.statusLine());
            return TaskStatus.RUNNING;
        }
        if (result == TaskStatus.SUCCESS && still && ++digTicks < DIG_FOR_EYE_TICKS) {
            // Beside it: the pick-up box needs a moment, and the eye may still be settling.
            return TaskStatus.RUNNING;
        }
        digForEye.stop(ctx);
        digForEye = null;
        return afterPickUp(ctx);
    }

    /**
     * Whether an eye the pick-up gave up on is one to dig or climb to. Not one in the treetops,
     * and not one well above the bot: a mangrove canopy took a run up to its leaves after an eye
     * and the walk never found its way back down.
     */
    private boolean worthDiggingFor(BotContext ctx, ItemEntity eye) {
        return eye.getY() - ctx.player.getY() <= REACH_CLIMB / 2.0
                && !ctx.level.getBlockState(eye.blockPosition().below()).is(BlockTags.LEAVES)
                && !ctx.level.getBlockState(eye.blockPosition()).is(BlockTags.LEAVES);
    }

    /** A thrown eye still lying within reach on dry land, or null. */
    private ItemEntity eyeLeftBehind(BotContext ctx) {
        return nearestEye(ctx, true);
    }

    /** The nearest spent eye lying within reach - on dry land only, when asked - or null. */
    private ItemEntity nearestEye(BotContext ctx, boolean onLand) {
        AABB box = ctx.player.getBoundingBox().inflate(PICKUP_RADIUS);
        return ctx.level.getEntities(EntityTypeTest.forClass(ItemEntity.class), box,
                        item -> item.isAlive() && item.getItem().is(Items.ENDER_EYE)
                                && (!onLand || !MovementHelper.isLiquid(ctx.level, item.blockPosition())))
                .stream()
                .min(java.util.Comparator.comparingDouble(item -> item.distanceToSqr(ctx.player)))
                .orElse(null);
    }

    private TaskStatus afterPickUp(BotContext ctx) {
        if (reading == null || reading.kind() == EnderEyePolicy.Kind.UNREADABLE) {
            if (lostSight && clearings < MAX_CLEARINGS) {
                return startClearing(ctx);
            }
            return ready(ctx, "lune.status.ender_eye.unreadable");
        }
        phase = Phase.DECIDE;
        return TaskStatus.RUNNING;
    }

    /**
     * The way the last eye went, as a unit vector: from the furthest it was seen along its line,
     * or failing that from where it fell. Null when neither says.
     */
    private double[] bearing() {
        Vec3 known = furthestSeen != null ? furthestSeen : dropAt;
        if (known == null) {
            return null;
        }
        double dx = known.x - originX;
        double dz = known.z - originZ;
        double length = Math.hypot(dx, dz);
        return length < BEARING_AFTER ? null : new double[]{dx / length, dz / length};
    }

    /**
     * Steps a few blocks to where the next flight can be watched from, before throwing again.
     *
     * <p>The eye went behind something. Which way it went is known from what was seen of it, or
     * from where it fell, and a player who lost an eye to a tree steps round the tree before the
     * next throw rather than throwing into it again. {@link EnderEyeClearing} picks the step; when
     * nowhere nearby sees more of that way than here does, the throw is made from here.</p>
     */
    private TaskStatus startClearing(BotContext ctx) {
        clearings++;
        double[] way = bearing();
        EnderEyeClearing.Spot spot = way == null ? null
                : EnderEyeClearing.choose(MovementHelper.feetPosition(ctx.player), way[0], way[1],
                        (x, y, z) -> standingNear(ctx, x, y, z),
                        (from, to) -> Vision.firstSeen(ctx, from, to) == null);
        if (spot == null) {
            return ready(ctx, "lune.status.ender_eye.no_clearing");
        }
        ctx.debug.decide("stepping " + spot.distance() + " blocks to " + spot.feet().toShortString()
                + " to watch the next flight");
        clearingWalk = new GotoTask(new Goals.Near(spot.feet(), 1), false, false);
        clearingWalk.start(ctx);
        clearingTicks = 0;
        phase = Phase.CLEARING;
        status.set("lune.status.ender_eye.moving_to_clearing", spot.distance());
        return TaskStatus.RUNNING;
    }

    private TaskStatus clearing(BotContext ctx) {
        TaskStatus result = clearingWalk.tick(ctx);
        if (result == TaskStatus.RUNNING && ++clearingTicks < CLEARING_TICKS) {
            return TaskStatus.RUNNING;
        }
        clearingWalk.stop(ctx);
        clearingWalk = null;
        // Arrived or not, this is the spot to throw from: a step that failed is still a step.
        return ready(ctx, "lune.status.ender_eye.throwing_from_clearing");
    }

    /** Ground to stand on at about the bot's own height in a column, or null for none. */
    private static BlockPos standingNear(BotContext ctx, int x, int y, int z) {
        for (int dy = 2; dy >= -4; dy--) {
            BlockPos feet = new BlockPos(x, y + dy, z);
            if (ctx.level.hasChunkAt(feet) && MovementHelper.canStandAt(ctx.level, feet, false)) {
                return feet;
            }
        }
        return null;
    }

    private TaskStatus decide(BotContext ctx) {
        if (following) {
            throwOnArrival = true;
            return startWalk(ctx, walkX, walkZ, CHECK_DISTANCE);
        }
        if (stronghold == null) {
            return fail("lune.status.ender_eye.unreadable");
        }
        int centreX = stronghold.getX() + StrongholdKnowledge.STAIRCASE_OFFSET;
        int centreZ = stronghold.getZ() + StrongholdKnowledge.STAIRCASE_OFFSET;
        if (CompassFindTask.REMEMBER_ONLY.equalsIgnoreCase(then)) {
            remember(ctx);
            if (confirmed) {
                status.set("lune.status.ender_eye.remembered", stronghold.getX(), stronghold.getZ());
            } else {
                status.set("lune.status.ender_eye.best_guess", stronghold.getX(), stronghold.getZ(),
                        candidates.size());
            }
            return finish();
        }
        if (CompassFindTask.SAVE_AS_WAYPOINT.equalsIgnoreCase(then)) {
            remember(ctx);
            WaypointStore store = WaypointStore.get();
            String wanted = Lang.get("lune.task.ender_eye.waypoint_name");
            String name = ExternalWaypointSources.uniqueName(wanted, store.names());
            BlockPos pos = ExternalWaypointSources.groundPos(centreX, centreZ, ctx.player.blockPosition().getY());
            store.put(Waypoint.of(name, pos, dimension(ctx)));
            status.set("lune.status.compass.saved_waypoint", wanted, name);
            return finish();
        }
        if (!confirmed && !settled && !candidates.isEmpty()) {
            // Every candidate is on the line just read, so going to the nearest first walks
            // nothing twice; a throw there either dives into it or points on to the next.
            EnderEyePolicy.Candidate nearest = candidates.get(0);
            throwOnArrival = true;
            return startWalk(ctx, nearest.x() + StrongholdKnowledge.STAIRCASE_OFFSET,
                    nearest.z() + StrongholdKnowledge.STAIRCASE_OFFSET, CHECK_DISTANCE);
        }
        remember(ctx);
        throwOnArrival = false;
        return startWalk(ctx, centreX, centreZ, tolerance);
    }

    private void remember(BotContext ctx) {
        if (remembered) {
            return;
        }
        remembered = true;
        DiscoveryStore.get().remember(new Discovery(Discovery.STRUCTURE, STRONGHOLD, dimension(ctx),
                stronghold.getX(), stronghold.getZ(), System.currentTimeMillis()));
    }

    private TaskStatus startWalk(BotContext ctx, int x, int z, int within) {
        walkX = x;
        walkZ = z;
        walk = new FarWalkTask(new Goals.NearXZ(x, z, within), true);
        walk.start(ctx);
        phase = Phase.WALKING;
        status.set("lune.status.ender_eye.walking_to", x, z);
        return TaskStatus.RUNNING;
    }

    private TaskStatus walk(BotContext ctx) {
        TaskStatus result = walk.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            status.set("lune.status.ender_eye.walking_to", walkX, walkZ);
            return TaskStatus.RUNNING;
        }
        walk.stop(ctx);
        walk = null;
        if (result == TaskStatus.FAILED) {
            return fail("lune.status.ender_eye.cannot_reach_stronghold", walkX, walkZ);
        }
        if (throwOnArrival) {
            throwOnArrival = false;
            return ready(ctx, following ? "lune.status.ender_eye.following_eye"
                    : "lune.status.ender_eye.checking");
        }
        status.set("lune.status.ender_eye.stronghold_near", stronghold.getX(), stronghold.getZ());
        return finish();
    }

    private TaskStatus finish() {
        phase = Phase.DONE;
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String key, Object... args) {
        status.set(key, args);
        return TaskStatus.FAILED;
    }

    @Override
    public void onStop(BotContext ctx) {
        if (clearingWalk != null) {
            clearingWalk.stop(ctx);
            clearingWalk = null;
        }
        if (digForEye != null) {
            digForEye.stop(ctx);
            digForEye = null;
        }
        if (pickUp != null) {
            pickUp.stop(ctx);
            pickUp = null;
        }
        if (walk != null) {
            walk.stop(ctx);
            walk = null;
        }
        ctx.input.reset();
    }
}
