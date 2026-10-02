package com.etka.lune.net;

import com.etka.lune.server.ServerRules;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three payloads on the wire: they arrive as they were sent, and whatever else arrives - an
 * older or newer Lune's fields, or none at all - is read without throwing and answered no.
 */
class LunePayloadsTest {

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buffer = buffer();
        codec.encode(buffer, value);
        T read = codec.decode(buffer);
        assertEquals(0, buffer.readableBytes(), "every byte written is read: a leftover is a disconnect");
        return read;
    }

    /** What a Lune the test pretends is another version put on the wire. */
    private static <T> T decodeRaw(StreamCodec<RegistryFriendlyByteBuf, T> codec, String json) {
        RegistryFriendlyByteBuf buffer = buffer();
        buffer.writeUtf(json);
        T read = codec.decode(buffer);
        assertEquals(0, buffer.readableBytes());
        return read;
    }

    @Test
    void helloArrivesAsSent() {
        HelloPayload hello = new HelloPayload(1, "0.7.2");
        assertEquals(hello, roundTrip(HelloPayload.CODEC, hello));
    }

    @Test
    void theRulesArriveAsSent() {
        RulesPayload rules = new RulesPayload(1, "0.7.2",
                new ServerRules(ServerRules.Who.NOBODY, ServerRules.Who.EVERYONE), false, true, true,
                List.of(new RulesPayload.LunePlayer("Steve", "0.7.2"), new RulesPayload.LunePlayer("Alex", "0.8")));
        assertEquals(rules, roundTrip(RulesPayload.CODEC, rules));
    }

    @Test
    void anEditArrivesAsSentWithTheUntouchedRuleLeftOut() {
        EditRulesPayload edit = new EditRulesPayload(ServerRules.Who.EVERYONE, null);
        EditRulesPayload read = roundTrip(EditRulesPayload.CODEC, edit);
        assertEquals(edit, read);
        assertNull(read.cheats());
        assertEquals(new ServerRules(ServerRules.Who.EVERYONE, ServerRules.Who.NOBODY),
                read.applyTo(ServerRules.CLOSED));
    }

    /**
     * Rules that cannot be read allow nothing. A client that went the other way would let every
     * player through the moment a server said something it did not understand.
     */
    @Test
    void rulesThatCannotBeReadAllowNothing() {
        for (String garbage : List.of("", "not json", "[]", "{}", "{\"mayRun\":\"yes\"}")) {
            RulesPayload read = decodeRaw(RulesPayload.CODEC, garbage);
            assertFalse(read.mayRun(), garbage);
            assertFalse(read.mayCheat(), garbage);
            assertFalse(read.mayEdit(), garbage);
            assertEquals(ServerRules.CLOSED, read.rules(), garbage);
        }
    }

    /** A newer Lune's extra fields are skipped, not refused: an optional mod never disconnects anyone. */
    @Test
    void fieldsFromANewerLuneAreSkipped() {
        RulesPayload read = decodeRaw(RulesPayload.CODEC,
                "{\"protocol\":7,\"version\":\"2.0\",\"run\":\"everyone\",\"cheats\":\"operators\","
                        + "\"mayRun\":true,\"mayCheat\":false,\"mayEdit\":false,\"somethingNew\":{\"a\":1}}");
        assertTrue(read.mayRun());
        assertEquals(7, read.protocol());
        assertEquals(new ServerRules(ServerRules.Who.EVERYONE, ServerRules.Who.OPERATORS), read.rules());
    }

    /** The list is capped where it stops being a list, so a crowded server still fits in one payload. */
    @Test
    void aCrowdedServersListIsCapped() {
        List<RulesPayload.LunePlayer> crowd = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            crowd.add(new RulesPayload.LunePlayer("Player_" + i + "_withalongname", "0.7.2-some-long-suffix"));
        }
        RulesPayload rules = new RulesPayload(1, "0.7.2", ServerRules.DEDICATED, true, true, true, crowd);
        assertEquals(RulesPayload.MAX_PLAYERS, rules.players().size());
        assertEquals(rules, roundTrip(RulesPayload.CODEC, rules));
    }

    @Test
    void anEditNamingNoRuleChangesNothing() {
        EditRulesPayload read = decodeRaw(EditRulesPayload.CODEC, "{\"run\":\"all of them\"}");
        assertNull(read.run());
        assertEquals(ServerRules.DEDICATED, read.applyTo(ServerRules.DEDICATED));
    }
}
