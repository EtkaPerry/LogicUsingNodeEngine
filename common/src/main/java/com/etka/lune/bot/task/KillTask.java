package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.catalog.ToolCatalog;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hunts the nearest matching mob and fights it with enemy-specific tactics.
 * <p>
 * Different mobs demand different behaviour: creepers are hit-and-run, skeletons
 * are strafed, and the bot backs off when its health is low. The best melee weapon
 * in the hotbar/inventory is equipped automatically.
 */
public final class KillTask implements Task {

    /** Combat preparation may happen far from the table that was placed during tool setup. */
    private static final int REMEMBERED_TABLE_SEARCH_RADIUS = 256;

    /** Which weapon to make when several are affordable, hardest-hitting first. */
    private static final List<ToolCatalog.Material> WEAPON_MATERIALS = List.of(
            ToolCatalog.Material.DIAMOND, ToolCatalog.Material.IRON, ToolCatalog.Material.GOLD,
            ToolCatalog.Material.STONE, ToolCatalog.Material.WOOD);

    /** Melee range. Slightly inside the real limit so a moving target doesn't slip out mid-swing. */
    private static final double REACH = 3.0;
    private static final double RETREAT_RANGE = 6.0;
    private static final float AIM_TOLERANCE = 15.0F;
    /** Rebuild the approach path once the target has wandered this far from where we aimed. */
    private static final double RETARGET_DISTANCE_SQR = 9.0;
    /** A kill's drop lands where the mob stood, which is right in front of the bot. */
    private static final int KILL_SWEEP_RADIUS = 6;
    /** Short: one stubborn drop must not hold up the rest of the hunt. */
    private static final int KILL_SWEEP_DEADLINE = 80;
    /** Vanilla gives a newly spawned item a few ticks before it may be picked up. */
    private static final int DROP_SETTLE_TICKS = 12;
    /** Ticks of chasing without the gap closing before the target is written off. */
    private static final int CHASE_GIVE_UP_TICKS = 120;
    /** Distance the gap must close by to count as progress, so jitter does not read as gaining. */
    private static final double CHASE_PROGRESS_EPSILON = 0.5;
    private static final float LOW_HEALTH_FRACTION = 0.45F;
    private static final int BACK_OFF_TICKS = 22;
    private static final int STRAFE_SWITCH_TICKS = 8;
    private static final int RETREAT_TICKS = 50;
    private static final int BOW_CHARGE_TICKS = 20;
    private static final double BOW_RANGE = 32.0;
    private static final int MAX_SHELTER_PLACE_TICKS = 30;

    private enum Tactic {
        MELEE, HIT_AND_RUN, STRAFE
    }

    private enum Phase {
        APPROACH, ATTACK, BACK_OFF, STRAFE, RETREAT
    }

    private final Set<EntityType<?>> targets;
    private final int radius;
    private final KillOptions options;

    private LivingEntity target;
    /** Targets the bot could never close on, so the next scan does not pick the same one again. */
    private final java.util.Set<Integer> givenUpOn;
    private double bestChaseDistance = Double.MAX_VALUE;
    private int noChaseProgressTicks;
    /** A kill just happened and its drop has not been collected yet. */
    private boolean sweepPending;
    /** Delay the first scan until a just-spawned item can leave its pickup-delay window. */
    private int dropSettleTicks;
    private LootTask sweeper;
    private Tactic tactic;
    private Phase phase = Phase.APPROACH;
    private GotoTask approach;
    private BlockPos approachAim;
    private int killed;
    private int backOffTicks;
    private int retreatTicks;
    private int strafeTicks;
    private boolean strafeLeft;
    /** Whether this target has already been hit once; only the opening swing sets up a critical. */
    private boolean openingHitDone;
    private int bowTicks;
    private Task preparation;
    private PreparationKind preparationKind;
    private boolean shieldPrepared;
    private boolean shieldCraftAttempted;
    private boolean weaponPrepared;
    private boolean weaponCraftAttempted;
    private boolean fireResistanceAttempted;
    private boolean drinkingFireResistance;
    private int fireResistanceTicks;
    private boolean endermanSafetyReady;
    private boolean boatAttempted;
    private int boatPlacementTicks;
    private BlockPos shelterBase;
    private BlockPos shelterSupport;
    private BlockPos shelterRoof;
    private Block shelterMaterial;
    private int shelterPlacementTicks;
    private String combatStrategy = CombatPolicy.DEFAULT;
    private final StatusText status = new StatusText();

    private enum PreparationKind {
        SHIELD, WEAPON
    }

    public KillTask(Set<EntityType<?>> targets, int radius) {
        this(targets, radius, KillOptions.basic());
    }

    public KillTask(Set<EntityType<?>> targets, int radius, KillOptions options) {
        this(targets, radius, options, new java.util.HashSet<>());
    }

    /**
     * @param givenUpOn shared record of mobs already written off as unreachable. A caller that
     *                  rebuilds this task - every hunt loop does, each time it cycles back to
     *                  hunting - must own this set and pass the same one in, or each rebuild starts
     *                  with a clean slate and walks straight back to the mob it just gave up on.
     */
    public KillTask(Set<EntityType<?>> targets, int radius, KillOptions options,
                    java.util.Set<Integer> givenUpOn) {
        this.targets = Set.copyOf(targets);
        this.radius = radius;
        this.options = options == null ? KillOptions.basic() : options;
        this.givenUpOn = givenUpOn;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.kill.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Kill");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    /**
     * Number of targets this task has actually killed. Callers that deliberately rebuild a hunt
     * loop can distinguish an empty, successful scan from useful combat progress without parsing
     * the human-facing status string.
     */
    public int killedCount() {
        return killed;
    }

    @Override
    public TaskProgress learningProgress() {
        return new TaskProgress(killed, Math.max(1, killed), Lang.get("lune.unit.kills"));
    }

    @Override
    public LearningContext learningContext(BotContext ctx) {
        String targetKey = targets.stream()
                .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())
                .sorted()
                .limit(4)
                .collect(Collectors.joining(","));
        String phase = "targets=" + (targetKey.isBlank() ? "none" : targetKey)
                + ";radius=" + (radius <= 16 ? "near" : radius <= 48 ? "medium" : "wide")
                + ";weapon=" + options.weapon().name().toLowerCase()
                + ";shield=" + options.useShield()
                + ";enderman=" + options.endermanSafety().name().toLowerCase();
        return new LearningContext("skill", "combat",
                ctx.level.dimension().identifier().toString(), phase);
    }

    @Override
    public List<String> learningActions(BotContext ctx) {
        return CombatPolicy.actions(options.weapon() != KillOptions.WeaponPreference.BOW);
    }

    @Override
    public void onLearningAction(BotContext ctx, String action) {
        combatStrategy = CombatPolicy.ACTIONS.contains(action) ? action : CombatPolicy.DEFAULT;
    }

    @Override
    public void onStart(BotContext ctx) {
        combatStrategy = CombatPolicy.DEFAULT;
        target = null;
        tactic = null;
        phase = Phase.APPROACH;
        approach = null;
        approachAim = null;
        sweeper = null;
        sweepPending = false;
        dropSettleTicks = 0;
        killed = 0;
        backOffTicks = 0;
        retreatTicks = 0;
        strafeTicks = 0;
        bowTicks = 0;
        preparation = null;
        preparationKind = null;
        shieldPrepared = false;
        shieldCraftAttempted = false;
        weaponPrepared = false;
        weaponCraftAttempted = false;
        fireResistanceAttempted = false;
        drinkingFireResistance = false;
        fireResistanceTicks = 0;
        resetEndermanSafety();
        shelterPlacementTicks = 0;
        status.clear();
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (targets.isEmpty()) {
            status.set("lune.status.find.no_mobs_selected");
            return TaskStatus.FAILED;
        }

        if (retreatTicks > 0) {
            retreatTicks--;
            return retreat(ctx);
        }

        if (isLowHealth(ctx) && (target != null || findNearest(ctx) != null)) {
            phase = Phase.RETREAT;
            retreatTicks = RETREAT_TICKS;
            return retreat(ctx);
        }

        if (target != null && (!target.isAlive() || target.distanceTo(ctx.player) > radius)) {
            if (!target.isAlive()) {
                killed++;
                ctx.debug.count("mobs_killed");
                sweepPending = true;
                dropSettleTicks = DROP_SETTLE_TICKS;
            }
            clearTarget(ctx);
        }

        // Pick up what just died before going after the next one. Kills are spread across the whole
        // search radius and the bot walks to each one, so a sweep saved until every mob is dead
        // happens somewhere else entirely - by then the first carcass is far behind, and the meat
        // the bot went hunting for is left on the ground.
        if (sweepPending) {
            if (dropSettleTicks > 0) {
                dropSettleTicks--;
                status.set("lune.status.kill.waiting_drop");
                return TaskStatus.RUNNING;
            }
            TaskStatus sweep = tickSweep(ctx);
            if (sweep == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            sweepPending = false;
        }

        if (target == null) {
            target = findNearest(ctx);
            openingHitDone = false;
            if (target == null) {
                status.set("lune.status.kill.killed_nothing_left_within_blocks", killed, radius);
                return TaskStatus.SUCCESS;
            }
            tactic = tacticFor(target);
            phase = Phase.APPROACH;
            backOffTicks = 0;
            strafeTicks = 0;
            resetEndermanSafety();
        }

        TaskStatus preparationResult = prepareForTarget(ctx);
        if (preparationResult != null) {
            return preparationResult;
        }

        if (isEnderman(target) && options.endermanSafety() != KillOptions.EndermanSafety.DIRECT
                && !endermanSafetyReady) {
            TaskStatus safetyResult = prepareEndermanSafety(ctx);
            if (safetyResult != TaskStatus.SUCCESS) {
                if (safetyResult == TaskStatus.FAILED) {
                    clearTarget(ctx);
                    status.set("lune.status.kill.could_not_make_enderman_safe_position");
                    return TaskStatus.RUNNING;
                }
                return safetyResult;
            }
        }

        if (shouldBackOffNow(ctx)) {
            phase = Phase.BACK_OFF;
            backOffTicks = BACK_OFF_TICKS;
        }

        if (phase == Phase.BACK_OFF) {
            return backOff(ctx);
        }

        equipWeapon(ctx);

        if ((isRangedThreat(target) || options.weapon() == KillOptions.WeaponPreference.BOW)
                && (options.weapon() == KillOptions.WeaponPreference.AUTO
                || options.weapon() == KillOptions.WeaponPreference.BOW)
                && hasBowAndArrows(ctx)
                && ctx.player.distanceTo(target) <= BOW_RANGE) {
            return attackWithBow(ctx);
        }

        if (isEnderman(target) && endermanSafetyReady
                && options.endermanSafety() != KillOptions.EndermanSafety.DIRECT
                && ctx.player.distanceTo(target) > REACH) {
            return lureEnderman(ctx);
        }

        if (ctx.player.distanceTo(target) <= REACH) {
            phase = Phase.ATTACK;
            return attack(ctx);
        }

        phase = (tactic == Tactic.STRAFE) ? Phase.STRAFE : Phase.APPROACH;
        return chase(ctx);
    }

    @Override
    public void onStop(BotContext ctx) {
        if (preparation != null) {
            preparation.stop(ctx);
            preparation = null;
        }
        if (sweeper != null) {
            sweeper.stop(ctx);
            sweeper = null;
        }
        sweepPending = false;
        if (drinkingFireResistance && ctx.player.isUsingItem()) {
            ctx.gameMode.releaseUsingItem(ctx.player);
        }
        if (ctx.player.isUsingItem() && ctx.player.getUseItem().is(Items.SHIELD)) {
            ctx.gameMode.releaseUsingItem(ctx.player);
        }
        if (ctx.player.isUsingItem() && ctx.player.getUseItem().is(Items.BOW)) {
            ctx.gameMode.releaseUsingItem(ctx.player);
        }
        clearTarget(ctx);
        ctx.input.reset();
    }

    /**
     * Whether a jump would actually produce a critical here.
     *
     * <p>Vanilla refuses the bonus while sprinting, in water, on a ladder or blinded, and a jump
     * with a ceiling overhead just bumps the head. Checking first keeps the opening pause from
     * being spent for nothing in a tunnel or a river.
     */
    private static boolean canCrit(BotContext ctx) {
        return !ctx.player.isSprinting()
                && !ctx.player.isInWater()
                && !ctx.player.onClimbable()
                && !ctx.player.isPassenger()
                && MovementHelper.isPassable(ctx.level, ctx.player.blockPosition().above(2));
    }

    private TaskStatus attack(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
            approachAim = null;
        }

        if (ctx.player.isUsingItem() && ctx.player.getUseItem().is(Items.SHIELD)) {
            ctx.gameMode.releaseUsingItem(ctx.player);
            status.set("lune.status.kill.lowering_shield_strike");
            return TaskStatus.RUNNING;
        }

        ctx.look.lookAt(ctx.player, target.getEyePosition());

        boolean charged = ctx.player.getAttackStrengthScale(0.0F) >= 1.0F;
        boolean aimed = ctx.look.isLookingAt(ctx.player, target.getEyePosition(), AIM_TOLERANCE);

        // Open with a critical: jump first, swing on the way down.
        //
        // Vanilla grants the 1.5x only while the attacker is falling, off the ground, not on a
        // ladder or in water, and not sprinting - so it cannot be produced at the moment of the
        // swing, it has to be set up a few ticks earlier. The opening hit is the one worth setting
        // up: the target is not yet closing, so the pause to jump costs nothing, and on a skeleton
        // or a zombie the extra damage is often a whole swing saved.
        //
        // Only the first hit. Jumping before every swing is the bunny-hop that reads as a bot, and
        // it drops the attack-strength meter to no purpose once a fight is already traded.
        if (charged && aimed && !openingHitDone && CombatPolicy.usesCritical(combatStrategy)
                && canCrit(ctx)) {
            if (ctx.player.onGround()) {
                ctx.input.jump = true;
                status.set("lune.status.kill.opening_with_jump_critical");
                return TaskStatus.RUNNING;
            }
            if (ctx.player.getDeltaMovement().y >= 0.0) {
                // Still rising: the hit only crits on the way down.
                status.set("lune.status.kill.waiting_top_jump");
                return TaskStatus.RUNNING;
            }
        }

        if (charged && aimed) {
            ctx.input.jump = false;
            openingHitDone = true;
            ctx.gameMode.attack(ctx.player, target);
            ctx.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

            if (tactic == Tactic.HIT_AND_RUN && target.isAlive()) {
                phase = Phase.BACK_OFF;
                backOffTicks = BACK_OFF_TICKS;
            }
        }

        status.set("lune.status.kill.fighting_killed", target.getType().getDescription().getString(), killed);
        return TaskStatus.RUNNING;
    }

    private TaskStatus attackWithBow(BotContext ctx) {
        if (ctx.player.isUsingItem() && ctx.player.getUseItem().is(Items.SHIELD)) {
            ctx.gameMode.releaseUsingItem(ctx.player);
            bowTicks = 0;
            status.set("lune.status.kill.lowering_shield_draw_bow");
            return TaskStatus.RUNNING;
        }
        if (InventoryHelper.equip(ctx, stack -> stack.is(Items.BOW)) < 0) {
            bowTicks = 0;
            return attack(ctx);
        }

        ctx.look.lookAt(ctx.player, target.getEyePosition());
        if (bowTicks == 0) {
            ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
            if (!ctx.player.isUsingItem()) {
                return attack(ctx);
            }
            bowTicks = 1;
            status.set("lune.status.kill.drawing_bow", target.getType().getDescription().getString());
            return TaskStatus.RUNNING;
        }

        bowTicks++;
        if (bowTicks >= BOW_CHARGE_TICKS
                && ctx.look.isLookingAt(ctx.player, target.getEyePosition(), AIM_TOLERANCE)) {
            ctx.gameMode.releaseUsingItem(ctx.player);
            bowTicks = 0;
            status.set("lune.status.kill.fired", target.getType().getDescription().getString());
            return TaskStatus.RUNNING;
        }
        if (bowTicks > BOW_CHARGE_TICKS + 30) {
            ctx.gameMode.releaseUsingItem(ctx.player);
            bowTicks = 0;
        }
        status.set("lune.status.kill.charging_bow", target.getType().getDescription().getString());
        return TaskStatus.RUNNING;
    }

    private boolean hasBowAndArrows(BotContext ctx) {
        return InventoryHelper.anyMatch(ctx.player, stack -> stack.is(Items.BOW))
                && InventoryHelper.count(ctx.player, stack -> stack.is(Items.ARROW)
                || stack.is(Items.SPECTRAL_ARROW)
                || stack.is(Items.TIPPED_ARROW)) > 0;
    }

    private TaskStatus chase(BotContext ctx) {
        raiseShieldIfUseful(ctx);

        BlockPos where = target.blockPosition();
        if (approach == null || approachAim == null || where.distSqr(approachAim) > RETARGET_DISTANCE_SQR) {
            if (approach != null) {
                approach.stop(ctx);
            }
            approachAim = where;
            approach = new GotoTask(new Goals.Near(where, 2), true, false);
            approach.start(ctx);
        }

        TaskStatus result = approach.tick(ctx);
        ctx.look.lookAt(ctx.player, target.getEyePosition());

        // A moving target means a fresh GotoTask every few blocks, and a fresh GotoTask has a fresh
        // failed-path counter - so a bot that is walled in never accumulates enough failures to
        // give up, and chases something it cannot reach forever. Judge the chase by whether the gap
        // is actually closing, which survives the route being rebuilt.
        double distance = ctx.player.distanceTo(target);
        if (distance < bestChaseDistance - CHASE_PROGRESS_EPSILON) {
            bestChaseDistance = distance;
            noChaseProgressTicks = 0;
        } else if (++noChaseProgressTicks > CHASE_GIVE_UP_TICKS) {
            givenUpOn.add(target.getId());
            clearTarget(ctx);
            status.set("lune.status.kill.cannot_get_any_closer_one_looking");
            return TaskStatus.RUNNING;
        }

        if (phase == Phase.STRAFE) {
            strafeTicks++;
            if (strafeTicks > STRAFE_SWITCH_TICKS) {
                strafeTicks = 0;
                strafeLeft = !strafeLeft;
            }
            ctx.input.left = strafeLeft;
            ctx.input.right = !strafeLeft;
        }

        if (result == TaskStatus.FAILED) {
            clearTarget(ctx);
            status.set("lune.status.kill.target_unreachable_looking_another");
            return TaskStatus.RUNNING;
        }
        if (result == TaskStatus.SUCCESS) {
            approach.stop(ctx);
            approach = null;
            approachAim = null;
            status.set("lune.status.kill.closing");
            return TaskStatus.RUNNING;
        }
        status.set("lune.status.kill.chasing", target.getType().getDescription().getString());
        return TaskStatus.RUNNING;
    }

    private TaskStatus backOff(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
            approachAim = null;
        }

        backOffTicks--;
        if (backOffTicks <= 0 || target == null || !target.isAlive()) {
            phase = Phase.APPROACH;
            return TaskStatus.RUNNING;
        }

        if (ctx.player.distanceTo(target) >= RETREAT_RANGE) {
            phase = Phase.APPROACH;
            return TaskStatus.RUNNING;
        }

        ctx.look.lookAt(ctx.player, target.getEyePosition());
        ctx.input.backward = true;
        raiseShieldIfUseful(ctx);

        status.set("lune.status.kill.backing_off_from", target.getType().getDescription().getString());
        return TaskStatus.RUNNING;
    }

    private TaskStatus retreat(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
            approachAim = null;
        }

        Vec3 away;
        if (target != null && target.isAlive()) {
            away = ctx.player.position().subtract(target.position()).normalize();
        } else {
            away = Vec3.directionFromRotation(0, ctx.player.getYRot());
        }
        Vec3 lookAt = ctx.player.position().add(away);
        ctx.look.lookAt(ctx.player, lookAt);

        ctx.input.forward = true;
        ctx.input.sprint = true;

        if (target != null) {
            status.set("lune.status.kill.retreating_from", target.getType().getDescription().getString());
        } else {
            status.set("lune.status.kill.retreating_recover");
        }
        return TaskStatus.RUNNING;
    }

    private boolean isLowHealth(BotContext ctx) {
        return ctx.player.getHealth() / ctx.player.getMaxHealth() <= LOW_HEALTH_FRACTION;
    }

    private boolean shouldBackOffNow(BotContext ctx) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (tactic == Tactic.HIT_AND_RUN && target instanceof Creeper creeper) {
            return creeper.getSwellDir() > 0 || creeper.isIgnited();
        }
        return false;
    }

    private void equipWeapon(BotContext ctx) {
        equipPreferredWeapon(ctx);
    }

    private Tactic tacticFor(LivingEntity entity) {
        if (entity instanceof Creeper || entity instanceof Witch || entity instanceof Spider) {
            return Tactic.HIT_AND_RUN;
        }
        if (entity instanceof Skeleton || entity instanceof Blaze || isRangedThreat(entity)) {
            return Tactic.STRAFE;
        }
        return Tactic.MELEE;
    }

    /** Runs one-time item preparation for the current target without making it mandatory. */
    private TaskStatus prepareForTarget(BotContext ctx) {
        if (preparation != null) {
            TaskStatus result = preparation.tick(ctx);
            String item = preparationKind == PreparationKind.WEAPON ? "weapon" : "shield";
            status.set("lune.status.kill.preparing", item, preparation.statusLine());
            if (result == TaskStatus.RUNNING) {
                return result;
            }
            preparation.stop(ctx);
            preparation = null;
            PreparationKind finished = preparationKind;
            preparationKind = null;
            if (finished == PreparationKind.WEAPON) {
                weaponCraftAttempted = true;
                weaponPrepared = true;
            } else {
                shieldCraftAttempted = true;
                shieldPrepared = true;
            }
            if (result == TaskStatus.FAILED) {
                status.set("lune.status.kill.craft_failed_continuing_without", item);
            } else {
                status.set("lune.status.kill.crafted", item);
            }
            return TaskStatus.RUNNING;
        }

        if (target instanceof Blaze && options.useFireResistance()) {
            if (drinkingFireResistance) {
                return tickFireResistance(ctx);
            }
            if (!fireResistanceAttempted) {
                fireResistanceAttempted = true;
                if (!ctx.player.hasEffect(MobEffects.FIRE_RESISTANCE)) {
                    int slot = InventoryHelper.equip(ctx, this::isFireResistancePotion);
                    if (slot >= 0) {
                        drinkingFireResistance = true;
                        fireResistanceTicks = 0;
                        ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
                        status.set("lune.status.kill.drinking_fire_resistance_blaze");
                        return TaskStatus.RUNNING;
                    }
                    status.set("lune.status.kill.no_fire_resistance_potion_fighting_blaze");
                }
            }
        }

        if ((options.useShield() || options.craftShield()) && !shieldPrepared) {
            if (InventoryHelper.equipOffhand(ctx, stack -> stack.is(Items.SHIELD))) {
                shieldPrepared = true;
                status.set("lune.status.kill.shield_ready");
                return TaskStatus.RUNNING;
            }
            if (options.craftShield() && !shieldCraftAttempted) {
                shieldCraftAttempted = true;
                preparation = CraftTask.of(Items.SHIELD, 1, true)
                        .withTableSearchRadius(REMEMBERED_TABLE_SEARCH_RADIUS);
                preparationKind = PreparationKind.SHIELD;
                preparation.start(ctx);
                status.set("lune.status.kill.crafting_shield");
                return TaskStatus.RUNNING;
            }
            shieldPrepared = true;
            if (options.useShield()) {
                status.set("lune.status.kill.no_shield_available");
            } else {
                status.set("lune.status.kill.shield_preparation_skipped");
            }
        }

        if (!weaponPrepared) {
            if (equipPreferredWeapon(ctx)) {
                weaponPrepared = true;
                status.set("lune.status.kill.weapon_ready");
                return TaskStatus.RUNNING;
            }
            if (options.craftWeapon() && !weaponCraftAttempted) {
                Item craft = findCraftableWeapon(ctx);
                weaponCraftAttempted = true;
                if (craft != null) {
                    preparation = CraftTask.of(craft, 1, true)
                            .withTableSearchRadius(REMEMBERED_TABLE_SEARCH_RADIUS);
                    preparationKind = PreparationKind.WEAPON;
                    preparation.start(ctx);
                    status.set("lune.status.kill.crafting", InventoryHelper.itemName(craft));
                    return TaskStatus.RUNNING;
                }
                status.set("lune.status.kill.no_materials_requested_weapon");
            }
            weaponPrepared = true;
        }

        return null;
    }

    private boolean equipPreferredWeapon(BotContext ctx) {
        boolean preferred = switch (options.weapon()) {
            case AUTO -> InventoryHelper.equipCombatWeapon(ctx) >= 0;
            case SWORD -> InventoryHelper.equip(ctx, stack -> isSword(stack.getItem())) >= 0;
            case AXE -> InventoryHelper.equip(ctx, stack -> isAxe(stack.getItem())) >= 0;
            case BOW -> InventoryHelper.equip(ctx, stack -> stack.is(Items.BOW)) >= 0;
        };
        if (preferred || options.weapon() == KillOptions.WeaponPreference.AUTO) {
            return preferred;
        }
        // A preference is a best effort. If the requested type is unavailable and crafting is
        // disabled or impossible, use another carried weapon rather than attacking with a potion
        // or an empty hand.
        return InventoryHelper.equipCombatWeapon(ctx) >= 0;
    }

    private Item findCraftableWeapon(BotContext ctx) {
        if (options.weapon() == KillOptions.WeaponPreference.BOW) {
            return hasCraftIngredients(ctx, Items.BOW) ? Items.BOW : null;
        }
        ToolCatalog.Kind kind = options.weapon() == KillOptions.WeaponPreference.AXE
                ? ToolCatalog.Kind.AXE : ToolCatalog.Kind.SWORD;
        for (ToolCatalog.Material material : WEAPON_MATERIALS) {
            Item candidate = ToolCatalog.item(kind, material);
            if (hasCraftIngredients(ctx, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Only checks what is already in the bag. A fight is not the moment to walk off and chop a
     * tree, so an unmakeable weapon means fighting with what there is - see {@code EnsureToolTask}
     * for the version that goes and gets the materials.
     */
    private boolean hasCraftIngredients(BotContext ctx, Item weapon) {
        int sticks = InventoryHelper.count(ctx.player, Items.STICK);
        if (weapon == Items.BOW) {
            return sticks >= 3 && InventoryHelper.count(ctx.player, Items.STRING) >= 3;
        }
        ToolCatalog.Kind kind = ToolCatalog.kindOf(weapon);
        ToolCatalog.Material material = ToolCatalog.materialOf(weapon);
        if (kind == null || material == null) {
            return false;
        }
        return InventoryHelper.count(ctx.player, ToolCatalog.material(material))
                >= ToolCatalog.materialCount(kind)
                && sticks >= ToolCatalog.sticks(kind);
    }

    private static boolean isSword(Item item) {
        return ToolCatalog.kindOf(item) == ToolCatalog.Kind.SWORD;
    }

    private static boolean isAxe(Item item) {
        return ToolCatalog.kindOf(item) == ToolCatalog.Kind.AXE;
    }

    private TaskStatus tickFireResistance(BotContext ctx) {
        if (ctx.player.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            drinkingFireResistance = false;
            if (ctx.player.isUsingItem()) {
                ctx.gameMode.releaseUsingItem(ctx.player);
            }
            status.set("lune.status.kill.fire_resistance_active");
            return TaskStatus.RUNNING;
        }

        fireResistanceTicks++;
        if (ctx.player.isUsingItem()) {
            if (fireResistanceTicks > 70) {
                ctx.gameMode.releaseUsingItem(ctx.player);
                drinkingFireResistance = false;
                status.set("lune.status.kill.fire_resistance_drink_timed_out");
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.kill.drinking_fire_resistance");
            return TaskStatus.RUNNING;
        }

        // A server may delay the first use acknowledgement by a tick or two. Retry briefly before
        // giving up; after that, continuing with the ordinary retreat logic is safer than hanging.
        if (fireResistanceTicks <= 3) {
            ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
            status.set("lune.status.kill.starting_fire_resistance_drink");
            return TaskStatus.RUNNING;
        }

        if (fireResistanceTicks > 70) {
            drinkingFireResistance = false;
            status.set("lune.status.kill.fire_resistance_not_applied");
            return TaskStatus.RUNNING;
        }
        status.set("lune.status.kill.waiting_fire_resistance");
        return TaskStatus.RUNNING;
    }

    private TaskStatus prepareEndermanSafety(BotContext ctx) {
        KillOptions.EndermanSafety mode = options.endermanSafety();
        if (mode == KillOptions.EndermanSafety.DIRECT) {
            endermanSafetyReady = true;
            return TaskStatus.SUCCESS;
        }

        if ((mode == KillOptions.EndermanSafety.AUTO || mode == KillOptions.EndermanSafety.BOAT)
                && (!boatAttempted || boatPlacementTicks > 0 && boatPlacementTicks < 30)) {
            boatAttempted = true;
            TaskStatus boatResult = prepareBoat(ctx);
            if (boatResult == TaskStatus.SUCCESS) {
                endermanSafetyReady = true;
                return boatResult;
            }
            if (boatResult == TaskStatus.RUNNING) {
                return boatResult;
            }
            // AUTO may fall back to a roof. Explicit BOAT reports the honest failure below.
            if (mode == KillOptions.EndermanSafety.BOAT) {
                return TaskStatus.FAILED;
            }
        }

        if (mode == KillOptions.EndermanSafety.AUTO || mode == KillOptions.EndermanSafety.TWO_BLOCK_SHELTER) {
            TaskStatus shelterResult = prepareShelter(ctx);
            if (shelterResult == TaskStatus.SUCCESS) {
                endermanSafetyReady = true;
            }
            return shelterResult;
        }

        return TaskStatus.FAILED;
    }

    /** Places a boat next to a nearby Enderman; the target is lured into range after setup. */
    private TaskStatus prepareBoat(BotContext ctx) {
        if (isNearBoat(ctx)) {
            status.set("lune.status.kill.enderman_boat_trap_ready");
            return TaskStatus.SUCCESS;
        }
        if (boatPlacementTicks >= 30) {
            status.set("lune.status.kill.could_not_place_enderman_boat");
            return TaskStatus.FAILED;
        }

        if (ctx.player.distanceTo(target) > 5.0) {
            status.set("lune.status.kill.enderman_too_far_away_boat_trap");
            return TaskStatus.FAILED;
        }
        if (InventoryHelper.equip(ctx, stack -> stack.getItem() instanceof BoatItem) < 0) {
            status.set("lune.status.kill.no_boat_available");
            return TaskStatus.FAILED;
        }

        BlockPos floor = findBoatFloor(ctx);
        if (floor == null) {
            boatPlacementTicks++;
            status.set("lune.status.kill.looking_place_trap_enderman");
            return TaskStatus.RUNNING;
        }

        Vec3 hit = Vec3.atCenterOf(floor).add(0.0, 0.5, 0.0);
        ctx.look.lookAt(ctx.player, hit);
        boatPlacementTicks++;
        if (!ctx.look.isLookingAt(ctx.player, hit, AIM_TOLERANCE)) {
            status.set("lune.status.kill.aiming_enderman_boat");
            return TaskStatus.RUNNING;
        }

        ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
        ctx.player.swing(InteractionHand.MAIN_HAND);
        status.set("lune.status.kill.placing_enderman_boat");
        return TaskStatus.RUNNING;
    }

    /** Builds a roof at Y+2, adding one side support first when the roof has no existing support. */
    private TaskStatus prepareShelter(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        if (shelterBase == null || !shelterBase.equals(feet)) {
            shelterBase = feet;
            shelterSupport = null;
            shelterRoof = feet.above(2);
            shelterMaterial = null;
            shelterPlacementTicks = 0;
        }

        if (hasTwoBlockShelter(ctx, feet)) {
            status.set("lune.status.kill.two_block_enderman_shelter_ready");
            return TaskStatus.SUCCESS;
        }
        if (shelterMaterial == null) {
            shelterMaterial = BlockPlacer.findSolidMaterial(ctx);
            if (shelterMaterial == null) {
                status.set("lune.status.kill.need_solid_block_enderman_shelter");
                return TaskStatus.FAILED;
            }
        }

        if (shelterRoof == null) {
            shelterRoof = feet.above(2);
        }
        if (!BlockPlacer.isReplaceable(ctx, shelterRoof)) {
            status.set("lune.status.kill.roof_space_occupied_but_not_safe");
            return TaskStatus.FAILED;
        }

        // A roof can be placed immediately when a wall or tree is already beside it.
        if (BlockPlacer.canPlaceAt(ctx, shelterRoof)) {
            BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(
                    ctx, shelterMaterial, shelterRoof);
            if (placement == BlockPlacer.PlacementResult.PLACED
                    || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
                shelterPlacementTicks = 0;
                status.set("lune.status.kill.built_two_block_enderman_shelter");
            } else if (!placement.isTransient()
                    || ++shelterPlacementTicks >= MAX_SHELTER_PLACE_TICKS) {
                status.set("lune.status.kill.could_not_place_enderman_shelter_roof", placement.displayName());
                return TaskStatus.FAILED;
            } else {
                status.set("lune.status.kill.placing_enderman_shelter_roof");
            }
            return TaskStatus.RUNNING;
        }

        if (shelterSupport == null) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = feet.relative(direction).above();
                if (BlockPlacer.canPlaceAt(ctx, candidate)) {
                    shelterSupport = candidate;
                    break;
                }
            }
        }
        if (shelterSupport == null) {
            status.set("lune.status.kill.no_support_enderman_shelter");
            return TaskStatus.FAILED;
        }

        if (MovementHelper.isPassable(ctx.level, shelterSupport)) {
            BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(
                    ctx, shelterMaterial, shelterSupport);
            if (placement == BlockPlacer.PlacementResult.PLACED
                    || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
                shelterPlacementTicks = 0;
                status.set("lune.status.kill.building_enderman_shelter_support");
            } else if (!placement.isTransient()
                    || ++shelterPlacementTicks >= MAX_SHELTER_PLACE_TICKS) {
                status.set("lune.status.kill.could_not_place_enderman_shelter_support", placement.displayName());
                return TaskStatus.FAILED;
            } else {
                status.set("lune.status.kill.placing_enderman_shelter_support");
            }
            return TaskStatus.RUNNING;
        }
        status.set("lune.status.kill.finishing_enderman_shelter_roof");
        return TaskStatus.RUNNING;
    }

    private TaskStatus lureEnderman(BotContext ctx) {
        // Eye contact makes the Enderman commit to the fight. The player stays under the roof or
        // beside the boat instead of pathing into open ground and losing the safety setup.
        ctx.look.lookAt(ctx.player, target.getEyePosition());
        status.set("lune.status.kill.luring_enderman_into_safety_blocks", String.format("%.1f", ctx.player.distanceTo(target)));
        return TaskStatus.RUNNING;
    }

    private boolean hasTwoBlockShelter(BotContext ctx, BlockPos feet) {
        return MovementHelper.isPassable(ctx.level, feet)
                && MovementHelper.isPassable(ctx.level, feet.above())
                && !MovementHelper.isPassable(ctx.level, feet.above(2));
    }

    private boolean isNearBoat(BotContext ctx) {
        if (target == null) {
            return false;
        }
        AABB area = target.getBoundingBox().inflate(2.5);
        return !ctx.level.getEntities(ctx.player, area,
                entity -> entity instanceof Boat boat && boat.isAlive()).isEmpty();
    }

    private BlockPos findBoatFloor(BotContext ctx) {
        BlockPos targetFeet = target.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos floor = targetFeet.below().offset(dx, 0, dz);
                if (!MovementHelper.isSolidFloor(ctx.level, floor)
                        || !BlockPlacer.isReplaceable(ctx, floor.above())) {
                    continue;
                }
                if (ctx.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(floor)) > 25.0) {
                    continue;
                }
                double distance = floor.distSqr(targetFeet);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = floor;
                }
            }
        }
        return best;
    }

    private void raiseShieldIfUseful(BotContext ctx) {
        if (!options.useShield() || target == null || isEnderman(target)
                || !isHostileShieldTarget(target)) {
            return;
        }
        if (!ctx.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).is(Items.SHIELD)) {
            return;
        }
        if (!ctx.player.isUsingItem()) {
            ctx.look.lookAt(ctx.player, target.getEyePosition());
            ctx.gameMode.useItem(ctx.player, InteractionHand.OFF_HAND);
        }
    }

    private boolean isHostileShieldTarget(LivingEntity entity) {
        return entity instanceof net.minecraft.world.entity.monster.Monster
                || isRangedThreat(entity)
                || entity instanceof Creeper
                || entity instanceof Witch;
    }

    private boolean isFireResistancePotion(net.minecraft.world.item.ItemStack stack) {
        if (!stack.is(Items.POTION)) {
            return false;
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null
                && (contents.is(Potions.FIRE_RESISTANCE) || contents.is(Potions.LONG_FIRE_RESISTANCE));
    }

    private static boolean isEnderman(LivingEntity entity) {
        return entity.getType() == EntityType.ENDERMAN;
    }

    private static boolean isRangedThreat(LivingEntity entity) {
        return entity.getType() == EntityType.SKELETON
                || entity.getType() == EntityType.STRAY
                || entity.getType() == EntityType.BOGGED
                || entity.getType() == EntityType.PILLAGER
                || entity.getType() == EntityType.BLAZE
                || entity.getType() == EntityType.GHAST
                || entity.getType() == EntityType.SHULKER
                || entity.getType() == EntityType.VEX
                || entity.getType() == EntityType.GUARDIAN
                || entity.getType() == EntityType.ELDER_GUARDIAN;
    }

    /**
     * A baby animal that would drop nothing.
     * <p>
     * Calves, lambs and piglets drop no meat, no wool and no leather, so killing one costs a chase
     * and returns an empty sweep - and removes the adult it was about to become. Baby <em>hostiles</em>
     * are deliberately excluded from this rule: a baby zombie is not a missed meal, it is faster
     * than the player and already attacking.
     */
    static boolean isWorthlessCalf(LivingEntity entity) {
        return entity.isBaby() && !(entity instanceof Enemy);
    }

    /**
     * Whether chasing this one would mean holding a breath the bot has not got.
     * <p>
     * Fish became targets and vision started passing through water, which together turned "there
     * is a cod" into a swim to the bottom of the sea. A recorded run chased cod and salmon for
     * 1,269 ticks and took drowning damage doing it. Half a lungful is the cut-off: the swim out
     * costs roughly what the swim in did, and a fish is two food.
     */
    private static boolean outOfBreathFor(BotContext ctx, LivingEntity candidate) {
        if (!candidate.isInWater()) {
            return false;
        }
        int air = ctx.player.getAirSupply();
        int max = Math.max(1, ctx.player.getMaxAirSupply());
        return air < max / 2;
    }

    private LivingEntity findNearest(BotContext ctx) {
        AABB box = ctx.player.getBoundingBox().inflate(radius);
        List<Entity> found = ctx.level.getEntities(ctx.player, box,
                entity -> entity instanceof LivingEntity living
                        && living.isAlive()
                        // The AABB is only the loaded-entity query. Its corners are farther
                        // than the configured hunt radius, so keep the actual Euclidean
                        // distance bound as well; otherwise a local food hunt can become a
                        // long diagonal chase into a ravine.
                        && living.distanceToSqr(ctx.player) <= (double) radius * radius
                        && !givenUpOn.contains(entity.getId())
                        && targets.contains(entity.getType())
                        && !isWorthlessCalf(living)
                        && !outOfBreathFor(ctx, living)
                        && Vision.isEntityVisible(ctx, living));
        return found.stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(ctx.player)))
                .map(LivingEntity.class::cast)
                .orElse(null);
    }

    /** Collects the drop from the kill that just happened, then hands control back. */
    private TaskStatus tickSweep(BotContext ctx) {
        if (sweeper == null) {
            sweeper = new LootTask(KILL_SWEEP_RADIUS, KILL_SWEEP_DEADLINE);
            sweeper.start(ctx);
        }
        TaskStatus result = sweeper.tick(ctx);
        status.set("lune.status.kill.collecting_drop", sweeper.statusLine());
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        sweeper.stop(ctx);
        sweeper = null;
        return result;
    }

    private void clearTarget(BotContext ctx) {
        openingHitDone = false;
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        if (preparation != null) {
            preparation.stop(ctx);
            preparation = null;
        }
        if (drinkingFireResistance && ctx.player.isUsingItem()) {
            ctx.gameMode.releaseUsingItem(ctx.player);
        }
        drinkingFireResistance = false;
        if (ctx.player.isUsingItem() && ctx.player.getUseItem().is(Items.BOW)) {
            ctx.gameMode.releaseUsingItem(ctx.player);
        }
        bowTicks = 0;
        approachAim = null;
        target = null;
        tactic = null;
        // The chase guard measures one pursuit; a new target starts its own.
        bestChaseDistance = Double.MAX_VALUE;
        noChaseProgressTicks = 0;
        phase = Phase.APPROACH;
        backOffTicks = 0;
        resetEndermanSafety();
    }

    private void resetEndermanSafety() {
        endermanSafetyReady = false;
        boatAttempted = false;
        boatPlacementTicks = 0;
        shelterBase = null;
        shelterSupport = null;
        shelterRoof = null;
        shelterMaterial = null;
    }
}
