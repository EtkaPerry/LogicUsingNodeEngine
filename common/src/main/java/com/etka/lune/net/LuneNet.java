package com.etka.lune.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.function.Function;

/**
 * How the two halves of Lune talk: three small payloads, each one JSON object in a string.
 *
 * <p>The player's Lune says hello ({@link HelloPayload}), the server's Lune answers with its rules
 * and where that player stands under them ({@link RulesPayload}), and an operator's Lune may ask for
 * the rules to change ({@link EditRulesPayload}). That is the whole conversation, and every one of
 * its payloads is optional in both directions: a server without Lune never hears a hello, because
 * nothing is sent to a server that has not said it listens, and a player without Lune is never
 * sent anything.</p>
 *
 * <p>JSON rather than fixed fields because the two ends are rarely the same release. A reader takes
 * the fields it knows, skips the ones it does not, and fills a missing one with its most cautious
 * value, so neither end ever has to refuse the other's bytes. The decoder never throws either: a
 * payload that fails to decode is a disconnect on every loader, and a player must never be thrown
 * off a server for having a different version of an optional mod.</p>
 *
 * <p>Lives on both sides, so it touches nothing client-only.</p>
 */
public final class LuneNet {

    /**
     * What the fields mean. Bumped only when an existing field's meaning changes; adding a field is
     * not a change. A server that learns a rule an older client cannot honour should tell that
     * client it may not run, which is what this number lets it recognise.
     */
    public static final int PROTOCOL = 1;

    /**
     * The version the loaders compare when a connection is made. It stays "1" for good: the loaders
     * refuse a connection whose versions disagree, and an optional mod must never be the reason a
     * player cannot join. What changes is {@link #PROTOCOL}, inside the payloads.
     */
    public static final String CHANNEL_VERSION = "1";

    /** The longest string a payload carries; vanilla's own limit for one. */
    static final int MAX_LENGTH = 32767;

    private LuneNet() {}

    /** A codec that carries one JSON object, written and read by the payload's own two methods. */
    static <T> StreamCodec<RegistryFriendlyByteBuf, T> json(Function<T, JsonObject> write,
                                                             Function<JsonObject, T> read) {
        return StreamCodec.of(
                (buffer, value) -> buffer.writeUtf(write.apply(value).toString(), MAX_LENGTH),
                buffer -> read.apply(parse(buffer.readUtf(MAX_LENGTH))));
    }

    /** The object in {@code text}, or an empty one when there is none to be had. */
    static JsonObject parse(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException unreadable) {
            return new JsonObject();
        }
    }

    static int integer(JsonObject json, String field, int fallback) {
        try {
            JsonElement value = json.get(field);
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    static String string(JsonObject json, String field, String fallback) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    /** True only when the field is there and says true: a missing answer is never a yes. */
    static boolean yes(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }
}
