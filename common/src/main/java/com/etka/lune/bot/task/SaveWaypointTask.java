package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.util.Coordinates;
import com.etka.lune.waypoint.Waypoint;
import com.etka.lune.waypoint.WaypointNames;
import com.etka.lune.waypoint.WaypointStore;

import java.util.List;
import java.util.Optional;

/**
 * Writes the waypoint list from inside a routine: save where the bot is standing, move a saved
 * place to where it is standing now, or forget one.
 * <p>
 * Waypoints were something only a person at the screen could create, which made them useless for
 * the thing a bot is actually for. A routine that walks out to find a village, or digs down to a
 * vein, or is about to cross an ocean, knows the one moment worth remembering - and it is not a
 * moment anybody is sitting there to catch.
 * <p>
 * Saving without a name is allowed, and picks one: see {@link WaypointNames}. Refusing would make
 * the commonest case - a routine that has no opinion about what to call this hole in the ground -
 * the one that fails.
 * <p>
 * One tick, like a condition: it either wrote something or it says why it could not.
 */
public final class SaveWaypointTask implements Task {

    /** What the card does to the waypoint list. */
    public enum Action {
        ADD("Save here"),
        MOVE("Move here"),
        REMOVE("Remove");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static List<String> labels() {
            return java.util.Arrays.stream(values()).map(Action::label).toList();
        }

        public static Action fromLabel(String label) {
            for (Action action : values()) {
                if (action.label.equalsIgnoreCase(label)) {
                    return action;
                }
            }
            return ADD;
        }
    }

    private final Action action;
    private final String name;
    private final StatusText status = new StatusText();

    public SaveWaypointTask(Action action, String name) {
        this.action = action == null ? Action.ADD : action;
        this.name = name == null ? "" : name.strip();
    }

    /** Bookkeeping, not a skill: there is nothing here for the learner to get better at. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    @Override
    public String name() {
        return switch (action) {
            case ADD -> name.isEmpty() ? Lang.get("lune.command.save_waypoint.name") : Lang.get("lune.task.save_waypoint.save", name);
            case MOVE -> Lang.get("lune.task.save_waypoint.move", name);
            case REMOVE -> Lang.get("lune.task.save_waypoint.remove", name);
        };
    }

    @Override
    public String learningId() {
        return Task.learningName(switch (action) {
            case ADD -> name.isEmpty() ? "Save waypoint" : "Save waypoint " + name;
            case MOVE -> "Move waypoint " + name;
            case REMOVE -> "Remove waypoint " + name;
        });
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        WaypointStore store = WaypointStore.get();
        if (name.isEmpty() && action != Action.ADD) {
            status.set("lune.status.save_waypoint.choose_which_waypoint", com.etka.lune.bot.command.Param.Choice.optionLabel(action.label()));
            return TaskStatus.FAILED;
        }

        Optional<Waypoint> existing = store.byName(name);
        String here = Coordinates.format(ctx.player.blockPosition());

        return switch (action) {
            case ADD -> {
                boolean named = !name.isEmpty();
                String chosen = named ? name : WaypointNames.suggest(store.names());
                store.captureHere(chosen);
                // The store declines to write when no world scope is active, and says so in the
                // log rather than to the routine. Reading it back is how this card knows.
                yield store.byName(chosen)
                        .map(saved -> {
                            status.set("lune.status.save_waypoint.saved", saved.describe(), (named ? "" : Lang.get("lune.status.save_waypoint.random_name")));
                            return TaskStatus.SUCCESS;
                        })
                        .orElseGet(() -> {
                            status.set("lune.status.save_waypoint.could_not_save_waypoint_here");
                            return TaskStatus.FAILED;
                        });
            }
            case MOVE -> {
                if (existing.isEmpty()) {
                    status.set("lune.status.fail.no_waypoint_named", name);
                    yield TaskStatus.FAILED;
                }
                // Its own spelling, not the one typed on the card: a name matches regardless of
                // case, and moving a waypoint should not quietly rename it.
                store.captureHere(existing.get().name());
                status.set("lune.status.save_waypoint.moved", existing.get().name(), here);
                yield TaskStatus.SUCCESS;
            }
            case REMOVE -> {
                if (existing.isEmpty()) {
                    status.set("lune.status.fail.no_waypoint_named", name);
                    yield TaskStatus.FAILED;
                }
                store.remove(existing.get().name());
                status.set("lune.status.save_waypoint.removed", existing.get().name());
                yield TaskStatus.SUCCESS;
            }
        };
    }
}
