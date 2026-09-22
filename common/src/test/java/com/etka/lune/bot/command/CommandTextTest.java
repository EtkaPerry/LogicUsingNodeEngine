package com.etka.lune.bot.command;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every card in the palette, asked what it is called.
 *
 * <p>Card and parameter text is built from ids rather than written out, so no literal in Java
 * points at those keys and the checks that compare source against the language file cannot see
 * them. What is left when one goes missing is a button labelled
 * {@code lune.param.distance.label} - which is exactly what happened when a de-duplication pass
 * merged a shared parameter line away, and nothing failed until somebody opened the screen.</p>
 */
class CommandTextTest {

    @AfterEach
    void restoreLanguage() {
        Lang.select(LuneLanguages.GAME_DEFAULT);
    }

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyCardAndParameterHasRealText() {
        List<String> raw = new ArrayList<>();
        for (CommandDef def : CommandRegistry.all()) {
            check(raw, def.id() + " name", def.name());
            check(raw, def.id() + " description", def.description());
            for (Param<?> param : def.params()) {
                check(raw, def.id() + "." + param.id() + " label", param.label());
                check(raw, def.id() + "." + param.id() + " tooltip", param.tooltip());
            }
        }
        assertTrue(raw.isEmpty(),
                "palette text that renders as its own key:\n" + String.join("\n", raw));
    }

    private static void check(List<String> raw, String what, String text) {
        // Lang.get hands back the key when nothing answers to it, so a key-shaped result is the
        // signature of a line that is not there.
        if (text != null && text.startsWith("lune.")) {
            raw.add("  " + what + " -> " + text);
        }
    }

    @Test
    void everyBuiltInChoiceHasATranslationKey() {
        List<String> missing = new ArrayList<>();
        for (CommandDef def : CommandRegistry.all()) {
            for (Param<?> param : def.params()) {
                if (!(param instanceof Param.Choice choice) || choice.labelsItsOwnOptions()) {
                    continue;
                }
                for (String option : choice.options()) {
                    String key = "lune.choice." + option.toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
                    if (!Lang.has(key)) {
                        missing.add(def.id() + "." + param.id() + ": " + option);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "untranslated dropdown options: " + missing);
    }

    @Test
    void turkishDescriptionsTranslateArgumentsWithoutChangingSavedValues() {
        Lang.select("tr_tr");
        CommandDef step = CommandRegistry.byId("step");
        Map<String, String> saved = step.snapshot();
        try {
            step.apply(Map.of("side", "Right", "blocks", "3", "careful", "true"));
            String description = step.logicDescription();
            assertTrue(description.contains("sağ yönüne 3 blok"), description);
            assertFalse(description.contains("Right"), description);
            assertEquals("Right", step.snapshot().get("side"));
            assertEquals("Eşya Topla", CommandRegistry.byId("loot").name());
            assertEquals("Blaze Avla", CommandRegistry.byId("huntblazes").name());
            assertEquals("Sırada görev yok", Lang.get("lune.gui.main.nothing_queued"));
            assertEquals("1.5 blok", Lang.get("lune.gui.main.1f_blocks", 1.5));
            assertEquals("2.5 ms", Lang.get("lune.gui.main.1f_ms", 2.5));
            var hunt = new com.etka.lune.bot.task.HuntMobTask(
                    com.etka.lune.compat.Mobs.BLAZE, stack -> false,
                    "Blaze Rod", "Blazes", 1, com.etka.lune.bot.task.KillOptions.basic());
            assertEquals("Blaze avla", hunt.name());
            assertEquals("Hunt Blazes", hunt.learningId());
            var walk = new com.etka.lune.bot.task.DirectionalGotoTask(
                    "walk", "Walk", "North", 32, 1, false);
            assertEquals("Yürü: Kuzey, 32 blok", walk.name());
            assertEquals("Walk North N blocks", walk.learningId());
        } finally {
            step.apply(saved);
        }
    }

    /**
     * A directional walk is drawn; the row it measures into is not.
     *
     * <p>The English has to read exactly as it did when the title was built out of Java
     * literals, and the learner's id has to keep saying "Walk North N blocks" whatever the
     * player is reading - it is the key of rows that are already on disk.</p>
     */
    @Test
    void directionalTravelIsDrawnAndItsRowKeyIsNot() {
        var walk = new com.etka.lune.bot.task.DirectionalGotoTask(
                "walk", "Walk", "North", 32, 1, false);
        assertEquals("Walk North 32 blocks", walk.name());
        assertEquals("Walk North N blocks", walk.learningId());
        // One block is one block, which the old literal could not say.
        var run = new com.etka.lune.bot.task.DirectionalGotoTask(
                "run", "Run", "Facing", 1, 1, true);
        assertEquals("Run Facing 1 block", run.name());
        assertEquals("Run Facing N blocks", run.learningId());
    }

    @Test
    void customNamesAreNotMistakenForBuiltInOptions() {
        Lang.select("tr_tr");
        Param.Choice waypoint = new Param.Choice("waypoint", List.of("Right"), "Right");
        Param.Choice task = new Param.Choice("name", List.of("Small"), "Small");
        assertEquals("Right", waypoint.displayValue());
        assertEquals("Small", task.displayValue());
        assertEquals("Right", waypoint.serialize());
    }
}
