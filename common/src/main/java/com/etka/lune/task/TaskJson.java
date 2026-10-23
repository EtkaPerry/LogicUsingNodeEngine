package com.etka.lune.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.util.List;

/**
 * How a task is written as JSON and read back - the one set of rules for the player's task file,
 * Export, a share code, and a Lumi's graph on its way to the server.
 *
 * <p>Kept apart from {@link TaskStore}, which is the client's own file and nothing a server may
 * touch: a server that reads a graph needs the rules, never the store.</p>
 */
public final class TaskJson {

    /** Laid out for a person to read: the task file and Export. */
    public static final Gson PRETTY = builder().setPrettyPrinting().create();
    /** The same, on one line: what a share code compresses, where every byte is a longer link. */
    public static final Gson COMPACT = builder().create();

    private TaskJson() {}

    /** One task, on one line. */
    public static String compact(TaskGraph task) {
        return COMPACT.toJson(task);
    }

    /**
     * A task read back from JSON, exactly as written - not yet checked or repaired.
     *
     * @throws com.google.gson.JsonParseException for text that is not a task
     */
    public static TaskGraph read(String json) {
        return COMPACT.fromJson(json, TaskGraph.class);
    }

    private static GsonBuilder builder() {
        return new GsonBuilder()
                // Routes used to be serialised as one {x, y} anchor. Read that shape as a
                // one-point route while writing the new repeatable {points: [...]} shape.
                .registerTypeAdapter(TaskCableRoute.class,
                        (JsonDeserializer<TaskCableRoute>) TaskJson::deserializeCableRoute);
    }

    private static TaskCableRoute deserializeCableRoute(JsonElement json,
                                                        java.lang.reflect.Type type,
                                                        JsonDeserializationContext context) {
        TaskCableRoute route = new TaskCableRoute();
        if (!json.isJsonObject()) {
            return route;
        }
        JsonObject object = json.getAsJsonObject();
        if (object.has("points") && !object.get("points").isJsonNull()) {
            List<TaskCableAnchor> points = context.deserialize(object.get("points"),
                    new TypeToken<List<TaskCableAnchor>>() {}.getType());
            if (points != null) {
                route.points.addAll(points);
            }
        } else if (object.has("x") && object.has("y")) {
            TaskCableAnchor legacyPoint = context.deserialize(json, TaskCableAnchor.class);
            if (legacyPoint != null) {
                route.points.add(legacyPoint);
            }
        }
        return route;
    }
}
