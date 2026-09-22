package com.etka.lune.bot.command;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cards that drive another mod, and the gate that keeps them out of a palette where that mod
 * is not installed.
 *
 * <p>Whether a mod is installed is the loader's answer, and there is no loader here, so what is
 * checked is the wiring rather than the verdict: the gate names cards that exist, an ordinary card
 * never consults the loader at all, and a gated card is still registered so that a task built in a
 * modded pack opens intact anywhere.</p>
 */
class ModCardGateTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** The list the README promises: two compasses, two backpacks, nothing else. */
    @Test
    void fourCardsWaitOnAnotherMod() {
        assertEquals(Set.of("find_biome", "find_structure", "backpack_deposit", "backpack_take"),
                CommandRegistry.modCards());
    }

    @Test
    void everyGateNamesACardThatExists() {
        for (String id : CommandRegistry.modCards()) {
            assertNotNull(CommandRegistry.byId(id), id + " is gated on a mod but is not a card");
        }
    }

    /**
     * A gated card is hidden, never unregistered: the canvas looks a saved node up by id, so a
     * task shared out of a modded pack still draws and still explains itself here.
     */
    @Test
    void aGatedCardIsStillRegistered() {
        List<String> registered = CommandRegistry.all().stream().map(CommandDef::id).toList();
        assertTrue(registered.containsAll(CommandRegistry.modCards()), registered.toString());
    }

    /** An ordinary card answers without asking the loader anything, which is what lets this run. */
    @Test
    void anOrdinaryCardIsAlwaysOffered() {
        assertTrue(CommandRegistry.isAvailable("mine"));
        assertTrue(CommandRegistry.isAvailable("walk"));
        assertTrue(CommandRegistry.isAvailable("deposit"));
    }
}
