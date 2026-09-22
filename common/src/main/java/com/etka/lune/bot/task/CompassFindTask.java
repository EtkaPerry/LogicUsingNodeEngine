package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.mods.CompassHook;
import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.Discovery;
import com.etka.lune.waypoint.DiscoveryStore;
import com.etka.lune.waypoint.Waypoint;
import com.etka.lune.waypoint.WaypointStore;
import com.etka.lune.waypoint.external.ExternalWaypointSources;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Finds a biome with a Nature's Compass, or a structure with an Explorer's Compass, and walks
 * there.
 *
 * <p>The bot's own scout only samples chunks it has loaded, on purpose - knowing what lies beyond
 * them would be the X-ray the Vision rules exist to prevent. A compass is different: it is an
 * item the player crafted, and asking it is exactly what a player does. So this card holds the
 * compass, sends the mod the same request its screen would, and waits for the answer to be
 * written back into the item.</p>
 *
 * <p>The answer is then remembered in {@link DiscoveryStore}, and that is the part that matters:
 * the next run of the same card, in the same world, walks straight there. The compass is needed
 * to learn a place, not to go back to it. <em>Search again</em> is the way to make it ask once
 * more, when the nearest jungle to where the bot is now is the one wanted.</p>
 *
 * <p>The card requires the compass to be in the inventory and fails cleanly when it is not. It
 * never crafts one: that is an Ensure Tool card's job, in front of this one.</p>
 */
public final class CompassFindTask implements Task {

    public static final String WALK_THERE = "Walk there";
    public static final String REMEMBER_ONLY = "Remember only";
    public static final String SAVE_AS_WAYPOINT = "Save as waypoint";
    public static final List<String> THEN_OPTIONS = List.of(WALK_THERE, REMEMBER_ONLY, SAVE_AS_WAYPOINT);

    /** Ticks between taking the compass in hand and asking: the hand change has to reach the server first. */
    private static final int SETTLE_TICKS = 3;
    /** A search samples the world on the server's own time; a large radius takes a while. */
    private static final int SEARCH_TIMEOUT_TICKS = 20 * 90;

    private enum Phase { START, SETTLING, WAITING, DECIDE, WALKING }

    private final CompassHook hook;
    private final String targetId;
    private final String then;
    private final int tolerance;
    private final boolean fresh;
    private final StatusText status = new StatusText();

    private Phase phase = Phase.START;
    private int waited;
    private Discovery known;
    private GotoTask walk;

    public CompassFindTask(CompassHook hook, String targetId, String then, int tolerance, boolean fresh) {
        this.hook = hook;
        this.targetId = targetId == null ? "" : targetId.strip();
        this.then = then == null || then.isBlank() ? WALK_THERE : then;
        this.tolerance = Math.max(1, tolerance);
        this.fresh = fresh;
    }

    private boolean biome() {
        return hook.kind() == CompassHook.Kind.BIOME;
    }

    private String kind() {
        return biome() ? Discovery.BIOME : Discovery.STRUCTURE;
    }

    private String targetName() {
        return DiscoveryStore.displayName(kind(), targetId);
    }

    @Override
    public String name() {
        return Lang.get(biome() ? "lune.task.find_biome.name" : "lune.task.find_structure.name", targetName());
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName(biome() ? "Find Biome" : "Find Structure");
    }

    /** Asking a compass is not a skill; the walk inside is learned by the walk. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        return switch (phase) {
            case START -> begin(ctx);
            case SETTLING -> ask(ctx);
            case WAITING -> await(ctx);
            case DECIDE -> decide(ctx);
            case WALKING -> walk(ctx);
        };
    }

    private String dimension(BotContext ctx) {
        return ctx.level.dimension().identifier().toString();
    }

    private TaskStatus begin(BotContext ctx) {
        if (!fresh) {
            Optional<Discovery> remembered = DiscoveryStore.get().find(kind(), targetId, dimension(ctx));
            if (remembered.isPresent()) {
                known = remembered.get();
                status.set("lune.status.compass.known", targetName(), known.x(), known.z());
                phase = Phase.DECIDE;
                return TaskStatus.RUNNING;
            }
        }
        if (!hook.ready()) {
            return fail("lune.status.compass.mod_missing", hook.label());
        }
        if (InventoryHelper.equip(ctx, hook::isCompass) < 0) {
            return fail("lune.status.compass.needs_item", hook.itemName());
        }
        waited = 0;
        phase = Phase.SETTLING;
        status.set("lune.status.compass.holding", hook.itemName());
        return TaskStatus.RUNNING;
    }

    private TaskStatus ask(BotContext ctx) {
        if (++waited < SETTLE_TICKS) {
            return TaskStatus.RUNNING;
        }
        ItemStack held = ctx.player.getMainHandItem();
        if (!hook.isCompass(held)) {
            return fail("lune.status.compass.needs_item", hook.itemName());
        }
        // A compass already pointing at this very target is an answer as good as a fresh one,
        // unless a fresh one is exactly what was asked for.
        if (!fresh && hook.answer(held) == CompassHook.Answer.FOUND && targetId.equals(hook.targetOf(held))) {
            CompassHook.Found found = hook.found(held);
            if (found != null) {
                return learned(ctx, found);
            }
        }
        if (!hook.search(ctx.mc, targetId, ctx.player.blockPosition())) {
            return fail("lune.status.compass.cannot_ask", hook.label());
        }
        waited = 0;
        phase = Phase.WAITING;
        status.set("lune.status.compass.searching", hook.itemName(), targetName());
        return TaskStatus.RUNNING;
    }

    private TaskStatus await(BotContext ctx) {
        ItemStack held = ctx.player.getMainHandItem();
        if (!hook.isCompass(held)) {
            // Something took it out of the hand mid-search; the answer still lands on the item.
            if (InventoryHelper.equip(ctx, hook::isCompass) < 0) {
                return fail("lune.status.compass.needs_item", hook.itemName());
            }
            return TaskStatus.RUNNING;
        }
        boolean ours = targetId.equals(hook.targetOf(held));
        CompassHook.Answer answer = hook.answer(held);
        if (ours && answer == CompassHook.Answer.FOUND) {
            CompassHook.Found found = hook.found(held);
            if (found != null) {
                return learned(ctx, found);
            }
        }
        if (ours && answer == CompassHook.Answer.NOT_FOUND) {
            return fail("lune.status.compass.not_found", hook.itemName(), targetName());
        }
        if (++waited > SEARCH_TIMEOUT_TICKS) {
            return fail("lune.status.compass.timed_out", hook.itemName());
        }
        status.set("lune.status.compass.searching", hook.itemName(), targetName());
        return TaskStatus.RUNNING;
    }

    private TaskStatus learned(BotContext ctx, CompassHook.Found found) {
        known = new Discovery(kind(), targetId, dimension(ctx), found.x(), found.z(), System.currentTimeMillis());
        DiscoveryStore.get().remember(known);
        status.set("lune.status.compass.found", targetName(), known.x(), known.z());
        phase = Phase.DECIDE;
        return TaskStatus.RUNNING;
    }

    private TaskStatus decide(BotContext ctx) {
        if (SAVE_AS_WAYPOINT.equalsIgnoreCase(then)) {
            WaypointStore store = WaypointStore.get();
            String name = ExternalWaypointSources.uniqueName(targetName(), store.names());
            BlockPos pos = ExternalWaypointSources.groundPos(known.x(), known.z(), ctx.player.blockPosition().getY());
            store.put(Waypoint.of(name, pos, known.dimension()));
            status.set("lune.status.compass.saved_waypoint", targetName(), name);
            return TaskStatus.SUCCESS;
        }
        if (REMEMBER_ONLY.equalsIgnoreCase(then)) {
            status.set("lune.status.compass.remembered", targetName(), known.x(), known.z());
            return TaskStatus.SUCCESS;
        }
        // A compass answers with a column, so the goal is one: arrive at any height there.
        walk = new GotoTask(new Goals.NearXZ(known.x(), known.z(), tolerance), true, false);
        walk.start(ctx);
        phase = Phase.WALKING;
        status.set("lune.status.find_map.heading_for", targetName());
        return TaskStatus.RUNNING;
    }

    private TaskStatus walk(BotContext ctx) {
        TaskStatus result = walk.tick(ctx);
        if (result == TaskStatus.SUCCESS) {
            walk.stop(ctx);
            walk = null;
            status.set("lune.status.compass.arrived", targetName());
            return TaskStatus.SUCCESS;
        }
        if (result == TaskStatus.FAILED) {
            walk.stop(ctx);
            walk = null;
            return fail("lune.status.compass.cannot_reach", targetName());
        }
        status.set("lune.status.find_map.heading_for", targetName());
        return TaskStatus.RUNNING;
    }

    private TaskStatus fail(String key, Object... args) {
        status.set(key, args);
        return TaskStatus.FAILED;
    }

    @Override
    public void onStop(BotContext ctx) {
        if (walk != null) {
            walk.stop(ctx);
            walk = null;
        }
        ctx.input.reset();
    }
}
