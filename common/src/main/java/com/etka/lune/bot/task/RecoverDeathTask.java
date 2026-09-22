package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.util.Durations;
import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.Death;
import com.etka.lune.waypoint.DeathStore;

import java.util.List;
import java.util.Optional;

/**
 * Walks back to where the player died and picks up what is lying there.
 *
 * <p>The engine writes every death down in {@link DeathStore} as it happens, because by then no
 * task is running and nothing that needs a card on the canvas could catch it. This card is the
 * other half: it reads that back, walks to the spot, and runs the same pickup sweep the Loot card
 * does.</p>
 *
 * <p>Two things about it are deliberate:</p>
 * <ul>
 *   <li><b>The route may dig.</b> Deaths happen in caves and at the bottom of ravines far more
 *       often than they happen on a lawn, and a walk that refuses to break a block usually cannot
 *       get back to one. Breaking is expensive in the router's costs, so it still walks wherever
 *       walking is possible.</li>
 *   <li><b>A recovered death is forgotten.</b> Otherwise the card walks back to the same
 *       picked-clean patch of ground on every pass, and a graph that loops does nothing else for
 *       the rest of the night. A walk that never arrives keeps the record, so the Fail pin can
 *       lead somewhere that tries again.</li>
 * </ul>
 *
 * <p>It does not promise the items are still there - vanilla despawns drops after five minutes,
 * and this card cannot know how long the player took to come back. The status line says how old
 * the death is so the answer is at least visible.</p>
 */
public final class RecoverDeathTask implements Task {

    /** Pick the death closest to the bot, which is the one to clear first when there are two. */
    public static final String NEAREST = "Nearest";
    /** Pick the death that happened last, whose drops have had the least time to despawn. */
    public static final String MOST_RECENT = "Most recent";
    public static final List<String> WHICH_OPTIONS = List.of(NEAREST, MOST_RECENT);

    /** Near enough that the sweep's own navigation can take over. */
    private static final int ARRIVAL_TOLERANCE = 2;

    private enum Phase { START, WALKING, SWEEPING }

    private final String which;
    private final int radius;
    private final StatusText status = new StatusText();

    private Phase phase = Phase.START;
    private Death target;
    private GotoTask walk;
    private LootTask sweep;

    public RecoverDeathTask(String which, int radius) {
        this.which = which == null || which.isBlank() ? NEAREST : which;
        this.radius = Math.max(1, radius);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.recover_death.name");
    }

    /** The English this is, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Recover Death Drop");
    }

    /** The walk inside is learned by the walk, and the sweep by the sweep. */
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
            case WALKING -> walk(ctx);
            case SWEEPING -> sweep(ctx);
        };
    }

    private TaskStatus begin(BotContext ctx) {
        DeathStore store = DeathStore.get();
        if (store.all().isEmpty()) {
            return fail("lune.status.recover_death.none");
        }
        String dimension = ctx.level.dimension().identifier().toString();
        Optional<Death> found = MOST_RECENT.equalsIgnoreCase(which)
                ? store.latestIn(dimension)
                : store.nearestIn(dimension, ctx.player.blockPosition());
        if (found.isEmpty()) {
            // There are deaths, just none on this side of a portal. Say which, rather than
            // reporting the same "nothing to recover" as a world nobody has died in.
            return fail("lune.status.recover_death.other_dimension");
        }
        target = found.get();
        walk = new GotoTask(new Goals.Near(target.pos(), ARRIVAL_TOLERANCE), true, true);
        walk.start(ctx);
        phase = Phase.WALKING;
        say("lune.status.recover_death.walking");
        return TaskStatus.RUNNING;
    }

    private TaskStatus walk(BotContext ctx) {
        TaskStatus result = walk.tick(ctx);
        if (result == TaskStatus.FAILED) {
            walk.stop(ctx);
            walk = null;
            return fail("lune.status.recover_death.cannot_reach", target.x(), target.y(), target.z());
        }
        if (result != TaskStatus.SUCCESS) {
            say("lune.status.recover_death.walking");
            return TaskStatus.RUNNING;
        }
        walk.stop(ctx);
        walk = null;
        sweep = new LootTask(radius);
        sweep.start(ctx);
        phase = Phase.SWEEPING;
        status.set("lune.status.recover_death.collecting");
        return TaskStatus.RUNNING;
    }

    private TaskStatus sweep(BotContext ctx) {
        TaskStatus result = sweep.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            status.set("lune.status.recover_death.collecting");
            return TaskStatus.RUNNING;
        }
        // A sweep ends when there is nothing left in range, whether that is because it collected
        // everything or because there was never anything there. Either way the trip is over.
        int collected = sweep.collectedCount();
        sweep.stop(ctx);
        sweep = null;
        DeathStore.get().forget(target);
        if (collected > 0) {
            status.set("lune.status.recover_death.recovered", collected);
        } else {
            status.set("lune.status.recover_death.nothing_left");
        }
        return TaskStatus.SUCCESS;
    }

    /** The walking line, with how long the drops have been lying there. */
    private void say(String key) {
        status.set(key, target.x(), target.y(), target.z(),
                Durations.describe(target.ageMillis(System.currentTimeMillis()) / 1000L));
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
        if (sweep != null) {
            sweep.stop(ctx);
            sweep = null;
        }
        ctx.input.reset();
    }
}
