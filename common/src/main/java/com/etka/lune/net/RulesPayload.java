package com.etka.lune.net;

import com.etka.lune.Constants;
import com.etka.lune.server.ServerRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's answer to one player: its rules, and where that player stands under them.
 *
 * <p>The verdicts are worked out on the server and sent ready-made, rather than left for the
 * client to work out from the rules. The server is the one that knows who its operators are and
 * who owns the world, and a client that did the sum itself would be one more place for the answer
 * to drift. A verdict that is missing reads as no.</p>
 *
 * @param version the server's Lune version, shown to the player
 * @param mayEdit whether this player may change the rules, which is also whether {@code players}
 *                is filled in: who else has Lune is the operators' business
 * @param players every player on the server whose Lune has said hello, for the operators
 */
public record RulesPayload(int protocol, String version, ServerRules rules, boolean mayRun,
                           boolean mayCheat, boolean mayEdit, List<LunePlayer> players)
        implements CustomPacketPayload {

    /** One player with Lune: their name, and the version they run. */
    public record LunePlayer(String name, String version) {}

    public static final Type<RulesPayload> TYPE = new Type<>(Constants.id("rules"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RulesPayload> CODEC =
            LuneNet.json(RulesPayload::toJson, RulesPayload::fromJson);

    /**
     * How many players the list carries at most. Past this it is a crowd rather than a list, and the
     * payload has a length limit to stay under.
     */
    public static final int MAX_PLAYERS = 64;

    public RulesPayload {
        version = version == null ? "" : version;
        rules = rules == null ? ServerRules.CLOSED : rules;
        players = players == null ? List.of() : List.copyOf(players.subList(0, Math.min(players.size(), MAX_PLAYERS)));
    }

    @Override
    public Type<RulesPayload> type() {
        return TYPE;
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("protocol", protocol);
        json.addProperty("version", version);
        rules.write(json);
        json.addProperty("mayRun", mayRun);
        json.addProperty("mayCheat", mayCheat);
        json.addProperty("mayEdit", mayEdit);
        JsonArray list = new JsonArray();
        for (LunePlayer player : players) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", player.name());
            entry.addProperty("version", player.version());
            list.add(entry);
        }
        json.add("players", list);
        return json;
    }

    /** Reads what {@link #toJson} wrote. Anything missing or unreadable answers no. */
    static RulesPayload fromJson(JsonObject json) {
        ServerRules rules = ServerRules.read(json, ServerRules.CLOSED, new ArrayList<>());
        List<LunePlayer> players = new ArrayList<>();
        JsonElement list = json.get("players");
        if (list != null && list.isJsonArray()) {
            for (JsonElement element : list.getAsJsonArray()) {
                if (element.isJsonObject() && players.size() < MAX_PLAYERS) {
                    JsonObject entry = element.getAsJsonObject();
                    players.add(new LunePlayer(LuneNet.string(entry, "name", "?"),
                            LuneNet.string(entry, "version", "?")));
                }
            }
        }
        return new RulesPayload(LuneNet.integer(json, "protocol", 0), LuneNet.string(json, "version", "?"),
                rules, LuneNet.yes(json, "mayRun"), LuneNet.yes(json, "mayCheat"),
                LuneNet.yes(json, "mayEdit"), players);
    }
}
