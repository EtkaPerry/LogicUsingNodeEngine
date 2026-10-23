package com.etka.lune.task.runner;

import com.etka.lune.bot.StatusText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A {@link StatusText} as JSON, so a status set where nobody reads it - a Lumi's run, on the server -
 * can be read in the language of whoever it reaches.
 *
 * <p>Arguments keep their kind: a whole number, a decimal, a word or a nested status. A line such as
 * {@code "%d blocks"} or {@code "%.0f%%"} formats the value it was given, and a number that came
 * back as text would throw in the middle of somebody's panel.</p>
 *
 * <p>Reading never throws. Anything that is not a status reads as an empty one: what arrives over
 * the network is never trusted to be well formed.</p>
 */
public final class StatusJson {

    /** How deep statuses may nest; anything below is left out rather than followed. */
    static final int MAX_DEPTH = 8;
    /** The most arguments one line is read with. */
    static final int MAX_ARGS = 16;
    /** The longest key or word kept. */
    static final int MAX_TEXT = 256;

    private StatusJson() {}

    public static JsonObject write(StatusText status) {
        return write(status, 0);
    }

    public static StatusText read(JsonElement json) {
        StatusText status = new StatusText();
        try {
            read(json, status, 0);
        } catch (RuntimeException malformed) {
            status.clear();
        }
        return status;
    }

    private static JsonObject write(StatusText status, int depth) {
        JsonObject out = new JsonObject();
        out.addProperty("k", status == null ? "" : status.key());
        JsonArray args = new JsonArray();
        if (status != null) {
            for (Object arg : status.args()) {
                args.add(writeArg(arg, depth));
            }
        }
        out.add("a", args);
        return out;
    }

    private static JsonObject writeArg(Object arg, int depth) {
        JsonObject out = new JsonObject();
        if (arg instanceof StatusText nested) {
            if (depth + 1 < MAX_DEPTH) {
                out.add("t", write(nested, depth + 1));
            } else {
                out.addProperty("s", "");
            }
        } else if (arg instanceof Integer || arg instanceof Long || arg instanceof Short || arg instanceof Byte) {
            out.addProperty("i", ((Number) arg).longValue());
        } else if (arg instanceof Float || arg instanceof Double) {
            out.addProperty("d", ((Number) arg).doubleValue());
        } else {
            out.addProperty("s", String.valueOf(arg));
        }
        return out;
    }

    private static void read(JsonElement json, StatusText into, int depth) {
        if (json == null || !json.isJsonObject() || depth >= MAX_DEPTH) {
            return;
        }
        JsonObject object = json.getAsJsonObject();
        JsonElement key = object.get("k");
        if (key == null || !key.isJsonPrimitive()) {
            return;
        }
        String text = clip(key.getAsString());
        JsonElement args = object.get("a");
        if (args == null || !args.isJsonArray()) {
            into.set(text);
            return;
        }
        JsonArray array = args.getAsJsonArray();
        Object[] values = new Object[Math.min(array.size(), MAX_ARGS)];
        for (int index = 0; index < values.length; index++) {
            values[index] = readArg(array.get(index), depth);
        }
        into.set(text, values);
    }

    private static Object readArg(JsonElement json, int depth) {
        if (json == null || !json.isJsonObject()) {
            return "";
        }
        JsonObject object = json.getAsJsonObject();
        if (object.has("t")) {
            StatusText nested = new StatusText();
            read(object.get("t"), nested, depth + 1);
            return nested;
        }
        if (object.has("i")) {
            long value = object.get("i").getAsLong();
            return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (Object) (int) value : (Object) value;
        }
        if (object.has("d")) {
            return object.get("d").getAsDouble();
        }
        JsonElement word = object.get("s");
        return word == null || !word.isJsonPrimitive() ? "" : clip(word.getAsString());
    }

    private static String clip(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }
}
