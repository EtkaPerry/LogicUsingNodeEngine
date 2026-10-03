package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Uses the player's current gear to reach and kill the Ender Dragon.
 */
public final class CompleteGameTask implements Task {

    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    private enum State {
        CHECK, TRAVEL, DIG, PORTAL, ENTER, FIGHT, EXIT, DONE
    }

    private State state = State.CHECK;
    private Task current;
    private Task suspended;
    private BlockPos strongholdPos;
    private final StatusText status = new StatusText();

    @Override
    public String name() {
        return Lang.get("lune.task.complete_game.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName(Lang.get("lune.task.complete_game.name"));
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        state = State.CHECK;
        current = null;
        suspended = null;
        strongholdPos = null;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (state == State.CHECK) {
            return check(ctx);
        }
        if (state == State.DONE) {
            status.set("lune.status.complete_game.ender_dragon_killed");
            return TaskStatus.SUCCESS;
        }

        if (current == null) {
            current = createTask(ctx);
            if (current == null) {
                status.set("lune.status.hunt_mob.failed", describe());
                return TaskStatus.FAILED;
            }
            current.start(ctx);
        }

        if (suspended == null && !(current instanceof EatTask) && ctx.player.getFoodData().getFoodLevel() <= 8) {
            suspended = current;
            current = new EatTask();
            current.start(ctx);
        }

        TaskStatus result = current.tick(ctx);
        status.set("lune.status.detail", describe(), current.statusLine());

        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }

        if (current instanceof EatTask) {
            if (result == TaskStatus.SUCCESS) {
                current.stop(ctx);
                current = suspended;
                suspended = null;
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.complete_game.sustenance_failed", current.statusLine());
            return TaskStatus.FAILED;
        }

        Task finished = current;
        current.stop(ctx);
        current = null;

        if (result == TaskStatus.FAILED) {
            status.set("lune.status.speedrun.failed", describe(), finished.statusLine());
            return TaskStatus.FAILED;
        }

        return finishPhase(ctx, finished);
    }

    @Override
    public void onStop(BotContext ctx) {
        if (current != null) {
            current.stop(ctx);
            current = null;
        }
        if (suspended != null) {
            suspended.stop(ctx);
            suspended = null;
        }
        ctx.input.reset();
    }

    private TaskStatus check(BotContext ctx) {
        List<String> missing = new ArrayList<>();
        if (!InventoryHelper.has(ctx.player, Items.ENDER_EYE, 12)) {
            missing.add(Lang.get("lune.status.complete_game.12_eyes_ender"));
        }
        if (!hasWeapon(ctx)) {
            missing.add(Lang.get("lune.status.complete_game.weapon"));
        }
        // A speedrun kit commonly has a shield but no armour. Keep the prepared-world task safe
        // without forcing a full armour detour that does not belong in the route.
        if (!hasArmor(ctx) && !hasShield(ctx)) {
            missing.add(Lang.get("lune.status.complete_game.armor_or_shield"));
        }
        if (!hasFood(ctx)) {
            missing.add("food");
        }

        if (missing.isEmpty()) {
            state = State.TRAVEL;
            return TaskStatus.RUNNING;
        }

        status.set("lune.status.complete_game.missing", String.join(", ", missing));
        return TaskStatus.FAILED;
    }

    private boolean hasWeapon(BotContext ctx) {
        return InventoryHelper.anyMatch(ctx.player,
                stack -> stack.has(DataComponents.WEAPON) || stack.is(Items.BOW));
    }

    private boolean hasArmor(BotContext ctx) {
        return InventoryHelper.anyMatch(ctx.player, stack -> {
            if (!stack.has(DataComponents.EQUIPPABLE)) {
                return false;
            }
            EquipmentSlot slot = stack.get(DataComponents.EQUIPPABLE).slot();
            return slot.isArmor();
        });
    }

    private boolean hasFood(BotContext ctx) {
        return InventoryHelper.count(ctx.player, stack -> stack.has(DataComponents.FOOD)) >= 4;
    }

    private boolean hasShield(BotContext ctx) {
        return InventoryHelper.has(ctx.player, Items.SHIELD, 1);
    }

    private Task createTask(BotContext ctx) {
        return switch (state) {
            case TRAVEL -> new EnderEyeTask();
            // The same two cards a player wires up themselves: read the eye, then go down and
            // look. Digging straight down until a frame showed up within 64 blocks was an X-ray.
            case DIG -> new PortalRoomTask(strongholdPos);
            case PORTAL -> new FillPortalTask();
            case ENTER -> new EnterEndTask();
            case FIGHT -> new FightDragonTask();
            case EXIT -> new ExitEndTask();
            default -> null;
        };
    }

    private TaskStatus finishPhase(BotContext ctx, Task finished) {
        switch (state) {
            case TRAVEL -> {
                // Were this ever null, the portal room card falls back on the remembered one.
                strongholdPos = ((EnderEyeTask) finished).strongholdPos();
                state = State.DIG;
            }
            case DIG -> state = State.PORTAL;
            case PORTAL -> state = State.ENTER;
            case ENTER -> state = State.FIGHT;
            case FIGHT -> state = State.EXIT;
            case EXIT -> state = State.DONE;
        }
        return TaskStatus.RUNNING;
    }

    private String describe() {
        return switch (state) {
            case CHECK -> Lang.get("lune.status.complete_game.checking_inventory");
            case TRAVEL -> Lang.get("lune.status.complete_game.finding_stronghold");
            case DIG -> Lang.get("lune.status.complete_game.digging_stronghold");
            case PORTAL -> Lang.get("lune.status.complete_game.filling_portal");
            case ENTER -> Lang.get("lune.status.complete_game.entering_end");
            case FIGHT -> Lang.get("lune.status.complete_game.fighting_dragon");
            case EXIT -> Lang.get("lune.status.complete_game.leaving_end");
            case DONE -> "done";
        };
    }

    /**
     * Puts an eye in every empty frame, and only ever in one it has seen.
     *
     * <p>A frame hidden behind the platform is walked toward until it is in sight, never clicked
     * at from where it cannot be: the click would be refused, and a step that keeps asking stands
     * there for good. Done is no empty frame left, which is also the moment the portal lights.</p>
     */
    private static final class FillPortalTask implements Task {

        private static final double FILL_REACH = 4.5;
        /** The second stand, when the first could not see the frame or click it: beside it. */
        private static final double CLOSE_REACH = 2.5;
        private static final int SEARCH_RADIUS = 64;
        private static final int MAX_FAILURES = 4;
        private static final SightSearch.Stand STAND = (pos, closer) ->
                new Goals.Adjacent(pos, closer ? CLOSE_REACH : FILL_REACH - 0.5);

        private final SightSearch sight = new SightSearch(Set.of(Blocks.END_PORTAL_FRAME),
                FillPortalTask::empty, SEARCH_RADIUS, STAND);
        private GotoTask approach;
        /** The frame being filled: always one the eyes found. */
        private BlockPos frame;
        /** Whether the walk to it is the second, closer one. */
        private boolean closer;
        private int useCooldown;
        private int failCount;
        private final StatusText status = new StatusText();

        private static boolean empty(BlockState state) {
            return state.is(Blocks.END_PORTAL_FRAME) && !state.getValue(EndPortalFrameBlock.HAS_EYE);
        }

        @Override
        public String name() { return Lang.get("lune.task.nested.fill_end_portal"); }

        @Override
        public String learningId() { return Task.learningName("Fill End Portal"); }

        @Override
        public StatusText statusLine() {
        return status;
    }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            if (useCooldown > 0) {
                useCooldown--;
            }

            if (frame != null && !empty(ctx.level.getBlockState(frame))) {
                // The eye is in, or the frame is gone. Either way it is done with.
                frame = null;
                closer = false;
                stopApproach(ctx);
            }

            if (frame == null) {
                frame = sight.seen(ctx);
                if (frame == null) {
                    return lookForFrames(ctx);
                }
            }

            // In sight. Now near enough to click it, with nothing in the way of the click.
            if (!BlockPlacer.hasLineOfSight(ctx, frame)) {
                return walkToFrame(ctx);
            }
            stopApproach(ctx);

            if (InventoryHelper.equip(ctx, stack -> stack.is(Items.ENDER_EYE)) < 0) {
                status.set("lune.status.ender_eye.out_eyes_ender");
                return TaskStatus.FAILED;
            }

            if (useCooldown == 0 && BlockPlacer.use(ctx, frame)) {
                useCooldown = 5;
            }

            status.set("lune.status.complete_game.filling_portal_frame");
            return TaskStatus.RUNNING;
        }

        /** No empty frame in sight: turn, or go round the portal, until one is. */
        private TaskStatus lookForFrames(BotContext ctx) {
            if (!sight.anyLeft(ctx)) {
                status.set("lune.status.complete_game.portal_filled");
                return TaskStatus.SUCCESS;
            }
            if (sight.goSee(ctx) == TaskStatus.FAILED) {
                status.set("lune.status.complete_game.cannot_reach_portal_frames");
                return TaskStatus.FAILED;
            }
            status.set(sight.statusLine());
            return TaskStatus.RUNNING;
        }

        private TaskStatus walkToFrame(BotContext ctx) {
            if (approach == null) {
                approach = new GotoTask(STAND.toSee(frame, closer), true, false);
                approach.start(ctx);
            }
            TaskStatus r = approach.tick(ctx);
            if (r == TaskStatus.RUNNING) {
                status.set("lune.status.complete_game.walking_frame");
                return TaskStatus.RUNNING;
            }
            stopApproach(ctx);
            if (r == TaskStatus.SUCCESS && !closer) {
                // Where the route stopped, something is still in the way of the click: beside it.
                closer = true;
                return TaskStatus.RUNNING;
            }
            sight.giveUp(frame);
            frame = null;
            closer = false;
            if (++failCount > MAX_FAILURES) {
                status.set("lune.status.complete_game.cannot_reach_portal_frames");
                return TaskStatus.FAILED;
            }
            return TaskStatus.RUNNING;
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
            sight.stop(ctx);
            ctx.input.reset();
        }
    }

    /** Steps into the portal the frames just lit, walking only at a block of it the eyes found. */
    private static final class EnterEndTask implements Task {

        private static final int TIMEOUT = 300;
        private static final int SEARCH_RADIUS = 64;

        private final SightSearch sight = new SightSearch(Set.of(Blocks.END_PORTAL), state -> true,
                SEARCH_RADIUS, (pos, closer) -> new Goals.Near(pos, closer ? 1 : 3));
        private GotoTask approach;
        /** The portal block being walked into: always one the eyes found. */
        private BlockPos portal;
        private boolean lookedAgain;
        private int ticks;
        private final StatusText status = new StatusText();

        @Override
        public String name() {
            return Lang.get("lune.status.complete_game.enter_end");
        }

        @Override
        public StatusText statusLine() {
        return status;
    }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            if (ctx.level.dimension() == Level.END) {
                status.set("lune.status.complete_game.end");
                return TaskStatus.SUCCESS;
            }

            if (portal == null) {
                portal = sight.seen(ctx);
                if (portal == null) {
                    return lookForPortal(ctx);
                }
                approach = new GotoTask(new Goals.Near(portal, 3), true, false);
                approach.start(ctx);
            }

            if (approach != null) {
                TaskStatus r = approach.tick(ctx);
                status.set("lune.status.speedrun.walking_portal");
                if (r == TaskStatus.RUNNING) {
                    return TaskStatus.RUNNING;
                }
                approach.stop(ctx);
                approach = null;
                if (r == TaskStatus.FAILED) {
                    status.set("lune.status.complete_game.cannot_reach_portal");
                    return TaskStatus.FAILED;
                }
            }

            Vec3 centre = Vec3.atCenterOf(portal);
            ctx.look.lookAt(ctx.player, centre);
            ctx.input.forward = true;

            ticks++;
            if (ticks > TIMEOUT) {
                status.set("lune.status.complete_game.did_not_enter_portal");
                return TaskStatus.FAILED;
            }

            status.set("lune.status.complete_game.entering_portal");
            return TaskStatus.RUNNING;
        }

        /** Lit, but not in sight: turn, or go round the frames, until it is. */
        private TaskStatus lookForPortal(BotContext ctx) {
            if (!sight.anyLeft(ctx)) {
                if (!lookedAgain) {
                    // It lit a moment ago, perhaps after the index was taken: take it once more.
                    lookedAgain = true;
                    sight.reset(ctx);
                    return TaskStatus.RUNNING;
                }
                status.set("lune.status.complete_game.no_end_portal_found");
                return TaskStatus.FAILED;
            }
            if (sight.goSee(ctx) == TaskStatus.FAILED) {
                status.set("lune.status.complete_game.cannot_reach_portal");
                return TaskStatus.FAILED;
            }
            status.set(sight.statusLine());
            return TaskStatus.RUNNING;
        }

        @Override
        public void onStop(BotContext ctx) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
            sight.stop(ctx);
            ctx.input.reset();
        }
    }

    private static final class FightDragonTask implements Task {

        private static final double CRYSTAL_RADIUS = 96.0;
        private static final double DRAGON_RADIUS = 128.0;
        private static final double MELEE_REACH = 5.5;
        private static final double CRYSTAL_REACH = 3.0;
        private static final int BOW_CHARGE = 20;
        private static final int NO_TARGET_TIMEOUT = 200;

        private Entity target;
        private GotoTask approach;
        private BlockPos approachAim;
        private int bowTicks;
        private int noTargetTicks;
        private final StatusText status = new StatusText();

        @Override
        public String name() { return Lang.get("lune.task.nested.fight_dragon"); }

        @Override
        public String learningId() { return Task.learningName("Fight Dragon"); }

        @Override
        public StatusText statusLine() {
        return status;
    }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            if (ctx.level.dimension() != Level.END) {
                status.set("lune.status.complete_game.not_end");
                return TaskStatus.FAILED;
            }

            if (target != null && !target.isAlive()) {
                if (target instanceof EnderDragon) {
                    status.set("lune.status.complete_game.ender_dragon_killed");
                    return TaskStatus.SUCCESS;
                }
                clearApproach(ctx);
                target = null;
                bowTicks = 0;
            }

            if (target == null) {
                target = findTarget(ctx);
                if (target == null) {
                    noTargetTicks++;
                    if (noTargetTicks > NO_TARGET_TIMEOUT) {
                        status.set("lune.status.complete_game.no_dragon_or_crystals");
                        return TaskStatus.FAILED;
                    }
                    status.set("lune.status.complete_game.looking_target");
                    return TaskStatus.RUNNING;
                }
                noTargetTicks = 0;
                approach = null;
                approachAim = null;
            }

            if (hasBowAndArrows(ctx)) {
                return attackWithBow(ctx);
            }
            return attackMelee(ctx);
        }

        @Override
        public void onStop(BotContext ctx) {
            clearApproach(ctx);
            ctx.input.reset();
        }

        private Entity findTarget(BotContext ctx) {
            AABB crystalBox = new AABB(ctx.player.blockPosition()).inflate(CRYSTAL_RADIUS);
            List<EndCrystal> crystals = ctx.level.getEntities(EntityTypeTest.forClass(EndCrystal.class), crystalBox, Entity::isAlive);
            if (!crystals.isEmpty()) {
                return crystals.stream()
                        .min(Comparator.comparingDouble(c -> c.distanceToSqr(ctx.player)))
                        .orElse(null);
            }

            AABB dragonBox = new AABB(ctx.player.blockPosition()).inflate(DRAGON_RADIUS);
            List<EnderDragon> dragons = ctx.level.getEntities(EntityTypeTest.forClass(EnderDragon.class), dragonBox, Entity::isAlive);
            if (!dragons.isEmpty()) {
                return dragons.get(0);
            }

            return null;
        }

        private boolean hasBowAndArrows(BotContext ctx) {
            if (!InventoryHelper.anyMatch(ctx.player, stack -> stack.is(Items.BOW))) {
                return false;
            }
            return InventoryHelper.count(ctx.player, stack ->
                    stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW)) > 0;
        }

        private TaskStatus attackWithBow(BotContext ctx) {
            if (InventoryHelper.equip(ctx, stack -> stack.is(Items.BOW)) < 0) {
                return attackMelee(ctx);
            }

            ctx.look.lookAt(ctx.player, target.getEyePosition());

            if (bowTicks == 0) {
                ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
                if (!ctx.player.isUsingItem()) {
                    return attackMelee(ctx);
                }
                bowTicks = 1;
                status.set("lune.status.complete_game.drawing_bow");
                return TaskStatus.RUNNING;
            }

            bowTicks++;
            if (bowTicks >= BOW_CHARGE
                    && ctx.look.isLookingAt(ctx.player, target.getEyePosition(), 15.0F)) {
                ctx.gameMode.releaseUsingItem(ctx.player);
                bowTicks = 0;
                status.set("lune.status.kill.fired", target.getType().getDescription().getString());
                return TaskStatus.RUNNING;
            }

            if (bowTicks > BOW_CHARGE + 30) {
                ctx.gameMode.releaseUsingItem(ctx.player);
                bowTicks = 0;
            }

            status.set("lune.status.complete_game.charging_bow");
            return TaskStatus.RUNNING;
        }

        private TaskStatus attackMelee(BotContext ctx) {
            InventoryHelper.equipCombatWeapon(ctx);

            double reach = target instanceof EnderDragon ? MELEE_REACH : CRYSTAL_REACH;

            if (ctx.player.distanceTo(target) > reach) {
                BlockPos where = target.blockPosition();
                if (approach == null || approachAim == null || !where.equals(approachAim)) {
                    clearApproach(ctx);
                    approachAim = where;
                    approach = new GotoTask(new Goals.Near(where, 2), true, false);
                    approach.start(ctx);
                }

                TaskStatus r = approach.tick(ctx);
                if (r == TaskStatus.FAILED) {
                    clearApproach(ctx);
                    target = null;
                    status.set("lune.status.complete_game.cannot_reach_target");
                    return TaskStatus.RUNNING;
                }
                status.set("lune.status.kill.chasing", target.getType().getDescription().getString());
                return TaskStatus.RUNNING;
            }

            clearApproach(ctx);
            ctx.look.lookAt(ctx.player, target.getEyePosition());

            boolean ready = target instanceof EndCrystal
                    || ctx.player.getAttackStrengthScale(0.0F) >= 1.0F;
            if (ready && ctx.look.isLookingAt(ctx.player, target.getEyePosition(), 20.0F)) {
                ctx.gameMode.attack(ctx.player, target);
                ctx.gameMode.swing(InteractionHand.MAIN_HAND);
            }

            status.set("lune.status.complete_game.melee", target.getType().getDescription().getString());
            return TaskStatus.RUNNING;
        }

        private void clearApproach(BotContext ctx) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
                approachAim = null;
            }
        }
    }

    /**
     * Leaves the End by its exit portal, found the way a player finds it: by walking to the middle
     * of the island, where the game always builds it, and looking.
     */
    private static final class ExitEndTask implements Task {

        /** The portal is built round the world's origin; this close, its bowl can be looked into. */
        private static final int CENTRE_RADIUS = 8;
        private static final int SEARCH_RADIUS = 32;
        private static final int TIMEOUT = 400;
        /**
         * Looks round the middle before admitting there is no portal there. It only opens once the
         * dragon's death is over, ten seconds after the fight has ended, so a first look can be early.
         */
        private static final int MAX_EMPTY_LOOKS = 3;

        private final SightSearch sight = new SightSearch(Set.of(Blocks.END_PORTAL), state -> true,
                SEARCH_RADIUS, (pos, closer) -> new Goals.Near(pos, closer ? 1 : 2));
        private GotoTask approach;
        /** The portal block being walked into: always one the eyes found. */
        private BlockPos portal;
        private boolean atCentre;
        private int emptyLooks;
        private int ticks;
        private final StatusText status = new StatusText();

        @Override
        public String name() {
            return Lang.get("lune.status.complete_game.exit_end");
        }

        @Override
        public StatusText statusLine() {
        return status;
    }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            if (ctx.level.dimension() != Level.END) {
                status.set("lune.status.complete_game.returned_from_end");
                return TaskStatus.SUCCESS;
            }

            if (portal == null) {
                portal = sight.seen(ctx);
                if (portal == null) {
                    return lookForPortal(ctx);
                }
                // Seen on the way to the middle, or from it: straight there.
                stopApproach(ctx);
                approach = new GotoTask(new Goals.Near(portal, 2), true, false);
                approach.start(ctx);
            }

            if (approach != null) {
                TaskStatus r = approach.tick(ctx);
                if (r == TaskStatus.RUNNING) {
                    status.set("lune.status.complete_game.walking_exit_portal");
                    return TaskStatus.RUNNING;
                }
                stopApproach(ctx);
                if (r == TaskStatus.FAILED) {
                    status.set("lune.status.complete_game.cannot_reach_exit_portal");
                    return TaskStatus.FAILED;
                }
            }

            Vec3 centre = Vec3.atCenterOf(portal);
            ctx.look.lookAt(ctx.player, centre);
            ctx.input.forward = true;

            ticks++;
            if (ticks > TIMEOUT) {
                status.set("lune.status.complete_game.did_not_leave_end");
                return TaskStatus.FAILED;
            }

            status.set("lune.status.complete_game.jumping_into_exit_portal");
            return TaskStatus.RUNNING;
        }

        /** Not in sight: to the middle, looking all the way, then round from there. */
        private TaskStatus lookForPortal(BotContext ctx) {
            if (!atCentre) {
                if (approach == null) {
                    approach = new GotoTask(new Goals.NearXZ(0, 0, CENTRE_RADIUS), true, false);
                    approach.start(ctx);
                }
                TaskStatus r = approach.tick(ctx);
                if (r == TaskStatus.RUNNING) {
                    status.set("lune.status.complete_game.walking_center");
                    return TaskStatus.RUNNING;
                }
                stopApproach(ctx);
                if (r == TaskStatus.FAILED) {
                    status.set("lune.status.complete_game.cannot_reach_center");
                    return TaskStatus.FAILED;
                }
                atCentre = true;
                sight.reset(ctx);
            }
            if (!sight.isWalking()) {
                if (sight.turnToward(ctx)) {
                    status.set(sight.statusLine());
                    return TaskStatus.RUNNING;
                }
                if (sight.lookRound(ctx)) {
                    status.set("lune.status.complete_game.looking_round_center", sight.statusLine());
                    return TaskStatus.RUNNING;
                }
                if (!sight.anyLeft(ctx)) {
                    if (++emptyLooks >= MAX_EMPTY_LOOKS) {
                        status.set("lune.status.complete_game.no_exit_portal");
                        return TaskStatus.FAILED;
                    }
                    // Nothing lit round the fountain yet: take the index again and look again.
                    sight.reset(ctx);
                    return TaskStatus.RUNNING;
                }
            }
            // Lit, but down in the bowl where no look from here reaches: go to its rim.
            if (sight.goSee(ctx) == TaskStatus.FAILED) {
                status.set("lune.status.complete_game.cannot_reach_exit_portal");
                return TaskStatus.FAILED;
            }
            status.set(sight.statusLine());
            return TaskStatus.RUNNING;
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
            sight.stop(ctx);
            ctx.input.reset();
        }
    }
}
