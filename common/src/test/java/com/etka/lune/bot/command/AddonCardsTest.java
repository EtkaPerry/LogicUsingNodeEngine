package com.etka.lune.bot.command;

import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A card a mod built on Lune brings for its own body - Lumi's Follow Me - is in the registry, so a
 * graph holding it opens and its settings are edited like any card's; it is never offered to the
 * player, and the player's run that reaches one fails saying whose card it is.
 */
class AddonCardsTest {

    private static final String ID = "test_follow";

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private CommandDef card;

    @BeforeEach
    void register() {
        card = CommandDef.forBody(ID, "test.command.follow",
                List.of(new Param.Ints("distance", 3, 2, 16)), "test.body.name");
        CommandRegistry.registerAddon("test", "Test", card);
    }

    @AfterEach
    void forget() {
        CommandRegistry.forgetAddon(ID);
    }

    @Test
    void itIsFoundButNeverOfferedToThePlayer() {
        assertSame(card, CommandRegistry.byId(ID), "a graph holding it opens and draws");
        assertTrue(CommandRegistry.isAddonCard(ID));
        assertFalse(CommandRegistry.isAvailable(ID), "the player's palette leaves it out");
        assertEquals("Test", CommandRegistry.categoryFor(ID));
        assertEquals(List.of(card), CommandRegistry.addonCards("test"));
    }

    @Test
    void itsWordsLiveInItsOwnModsLanguageFile() {
        assertEquals("test.command.follow", card.keyBase());
        assertEquals("test.command.follow.name", card.name(), "an unknown line reads as its key");
    }

    @Test
    void thePlayersRunThatReachesOneFailsSayingWhoseItIs() {
        Task built = card.buildWith(Map.of("distance", "4"));
        assertEquals(TaskStatus.FAILED, built.onTick(null));
        StatusText why = built.statusLine();
        assertEquals("lune.status.fail.addon_card", why.key());
        assertEquals("test.body.name", assertInstanceOf(StatusText.class, why.args()[0]).key());
    }

    @Test
    void registeringItAgainIsNothingAndTakingALuneCardsNameIsRefused() {
        CommandRegistry.registerAddon("test", "Test", card);
        assertEquals(1, CommandRegistry.addonCards("test").size());
        assertThrows(IllegalArgumentException.class, () -> CommandRegistry.registerAddon("test", "Test",
                CommandDef.forBody("mine", "test.command.mine", List.of(), "test.body.name")));
    }
}
