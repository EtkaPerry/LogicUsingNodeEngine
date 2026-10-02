package com.etka.lune.net;

import com.etka.lune.Constants;
import com.etka.lune.platform.BuildInfo;
import com.google.gson.JsonObject;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A player's Lune announcing itself to a server that listens for it: which version, speaking which
 * protocol. It is how the server knows who has Lune, and the only thing the player's side sends
 * unasked.
 */
public record HelloPayload(int protocol, String version) implements CustomPacketPayload {

    public static final Type<HelloPayload> TYPE = new Type<>(Constants.id("hello"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> CODEC =
            LuneNet.json(HelloPayload::toJson, HelloPayload::fromJson);

    public HelloPayload {
        version = version == null ? "" : version;
    }

    /** This jar's hello. */
    public static HelloPayload current() {
        return new HelloPayload(LuneNet.PROTOCOL, BuildInfo.version());
    }

    @Override
    public Type<HelloPayload> type() {
        return TYPE;
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("protocol", protocol);
        json.addProperty("version", version);
        return json;
    }

    static HelloPayload fromJson(JsonObject json) {
        return new HelloPayload(LuneNet.integer(json, "protocol", 0), LuneNet.string(json, "version", "?"));
    }
}
