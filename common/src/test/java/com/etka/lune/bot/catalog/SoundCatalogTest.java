package com.etka.lune.bot.catalog;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundCatalogTest {

    private static final String BELL = "minecraft:block.bell.use";

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theListIsTheRegistryWithSilenceInFrontOfIt() {
        List<String> ids = SoundCatalog.ids();
        assertEquals(SoundCatalog.SILENT, ids.get(0), "silence has to be reachable, and first");
        assertTrue(ids.contains(BELL), "a vanilla sound is missing from the list");
        assertTrue(ids.size() > 100, "that is not a whole registry: " + ids.size());
    }

    @Test
    void anIdResolvesToTheSoundItNames() {
        assertNotNull(SoundCatalog.sound(BELL));
        assertTrue(SoundCatalog.exists(BELL));
    }

    /**
     * A task outlives the mod that was installed when it was written, and a sound that no longer
     * exists must come back as nothing rather than as an exception in the middle of a run.
     */
    @Test
    void anIdNothingAnswersToIsSilentRatherThanFatal() {
        assertNull(SoundCatalog.sound("somemod:a.sound.that.left"));
        assertNull(SoundCatalog.sound("not a resource location at all"));
        assertNull(SoundCatalog.sound(SoundCatalog.SILENT));
        assertNull(SoundCatalog.sound(""));
        assertNull(SoundCatalog.sound(null));
        assertFalse(SoundCatalog.exists("somemod:a.sound.that.left"));
    }

    /**
     * Names come from the game - its subtitle when it has one, the id read as words when it does
     * not - so the check is that a sound is named after what makes it, in either form.
     */
    @Test
    void aSoundIsNamedAfterWhatMakesIt() {
        String zombie = SoundCatalog.label("minecraft:entity.zombie.ambient");
        assertTrue(zombie.toLowerCase(Locale.ROOT).contains("zombie"), zombie);
        assertFalse(zombie.contains("minecraft:"), "the namespace is noise in a list: " + zombie);
        // Silence is a real choice on the list and needs a word of its own.
        assertFalse(SoundCatalog.label(SoundCatalog.SILENT).isBlank());
    }
}
