package com.etka.lune.net;

import com.etka.lune.Constants;
import com.etka.lune.server.AddonRules;
import com.etka.lune.server.ServerRules;
import com.google.gson.JsonObject;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An operator asking for the rules to change.
 *
 * <p>Each rule is optional, and a missing one means "leave it as it is". The Server tab sends only
 * the rule that was pressed, so two operators changing different rules at the same moment each get
 * what they asked for rather than one of them writing the other's old value back. The server
 * decides whether the sender may ask at all; nothing in here is trusted.</p>
 *
 * @param run    who may run tasks, or null to leave it
 * @param cheats who may use the omniscient modes, or null to leave it
 * @param addons add-on rules to change, by name, each as the rule keeps it; any left out are left
 */
public record EditRulesPayload(ServerRules.Who run, ServerRules.Who cheats, Map<String, String> addons)
        implements CustomPacketPayload {

    public static final Type<EditRulesPayload> TYPE = new Type<>(Constants.id("edit_rules"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EditRulesPayload> CODEC =
            LuneNet.json(EditRulesPayload::toJson, EditRulesPayload::fromJson);

    public EditRulesPayload {
        addons = addons == null ? Map.of() : Map.copyOf(addons);
    }

    public EditRulesPayload(ServerRules.Who run, ServerRules.Who cheats) {
        this(run, cheats, Map.of());
    }

    @Override
    public Type<EditRulesPayload> type() {
        return TYPE;
    }

    /**
     * {@code current}, with whatever this asks to change changed. An add-on rule this side does not
     * have, or a value that rule would not accept, changes nothing.
     */
    public ServerRules applyTo(ServerRules current) {
        Map<String, String> changed = new LinkedHashMap<>(current.addons());
        addons.forEach((name, value) -> {
            AddonRules.Rule rule = AddonRules.byId(name);
            String accepted = rule == null ? null : rule.accept(value);
            if (accepted != null) {
                changed.put(name, accepted);
            }
        });
        return new ServerRules(run == null ? current.run() : run, cheats == null ? current.cheats() : cheats,
                changed);
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("protocol", LuneNet.PROTOCOL);
        if (run != null) {
            json.addProperty(ServerRules.RUN, run.id());
        }
        if (cheats != null) {
            json.addProperty(ServerRules.CHEATS, cheats.id());
        }
        addons.forEach(json::addProperty);
        return json;
    }

    static EditRulesPayload fromJson(JsonObject json) {
        Map<String, String> addons = new LinkedHashMap<>();
        for (AddonRules.Rule rule : AddonRules.all()) {
            String value = LuneNet.string(json, rule.id(), null);
            if (value != null) {
                addons.put(rule.id(), value);
            }
        }
        return new EditRulesPayload(ServerRules.Who.byId(LuneNet.string(json, ServerRules.RUN, null)),
                ServerRules.Who.byId(LuneNet.string(json, ServerRules.CHEATS, null)), addons);
    }
}
