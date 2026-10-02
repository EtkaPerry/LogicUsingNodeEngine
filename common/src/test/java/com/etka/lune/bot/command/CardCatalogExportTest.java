package com.etka.lune.bot.command;

import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every card's settings, written out for the share page at lunode.etka.co.uk.
 *
 * <p>The page draws a shared task and lets a player change its settings, and for that it needs to
 * know what each card takes: a number and its range, a switch, a choice and its options. It must
 * never keep its own list - a card added here would be missing there until somebody remembered -
 * so the list is read out of {@link CommandRegistry}, the same place the palette reads it.</p>
 *
 * <p>Names are not in it. The page reads them from Lune's own language files, under the same keys
 * {@link Param#label()} builds, so a translation reaches the page with no second copy.</p>
 *
 * <p>Written only when {@code LUNE_CARD_CATALOG} names a file, because a value here is the card's
 * <em>live</em> value - the palette's editing state - and any test that ran earlier in the same
 * JVM may have changed it. The share site's instructions run this class on its own for that reason.
 * Without the variable it still builds the catalog, so a card the export cannot describe fails
 * the suite rather than the next deploy.</p>
 */
class CardCatalogExportTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyCardDescribesItsSettings() throws IOException {
        Map<String, Object> cards = new LinkedHashMap<>();
        for (CommandDef def : CommandRegistry.all()) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (Param<?> param : def.params()) {
                params.add(describe(param));
            }
            Map<String, Object> card = new LinkedHashMap<>();
            // The palette folder, by the English name the palette groups on; the page draws it
            // through lune.gui.section the same way.
            card.put("folder", CommandRegistry.categoryFor(def.id()));
            if (CommandRegistry.modCards().contains(def.id())) {
                card.put("needsMod", true);
            }
            card.put("params", params);
            cards.put(def.id(), card);
        }
        assertFalse(cards.isEmpty(), "the registry offered no cards");

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("format", 1);
        catalog.put("cards", cards);
        String json = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
                .toJson(catalog);
        assertTrue(json.length() > 2);

        String target = System.getenv("LUNE_CARD_CATALOG");
        if (target != null && !target.isBlank()) {
            Path path = Path.of(target).toAbsolutePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, json + "\n", StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> describe(Param<?> param) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", param.id());
        out.put("type", switch (param.dataType()) {
            case NUMBER -> "number";
            case BOOLEAN -> "switch";
            case CHOICE -> "choice";
            case TEXT -> "text";
            case ITEM -> "item";
            case BLOCK_SET -> "blocks";
            case ENTITY_SET -> "mobs";
            case POSITION -> "position";
            case RECIPE -> "recipe";
        });
        out.put("default", param.serialize());
        if (param.parentSwitch() != null) {
            out.put("under", param.parentSwitch());
        }
        if (param instanceof Param.Ints ints) {
            out.put("min", ints.min());
            out.put("max", ints.max());
        } else if (param instanceof Param.Text text) {
            out.put("maxLength", text.maxLength());
        } else if (param instanceof Param.Choice choice) {
            if (choice.pickerId() != null) {
                // A registry the page cannot list, like every sound in the game: shown as its id.
                out.put("picker", choice.pickerId());
                return out;
            }
            if (choice.labelsItsOwnOptions()) {
                // Labels with no lune.choice line behind them: the player's own waypoint and task
                // names, which exist only in their game, or values that name themselves.
                out.put("ownLabels", true);
            }
            out.put("options", optionsOf(choice));
        }
        return out;
    }

    /**
     * The options as this game offers them; none for a list that lives in the player's files.
     *
     * <p>The saved tasks and waypoints are read through the loader's config folder, and no loader
     * runs in a unit test, so asking for them fails in the class initialiser - an error rather
     * than an exception, and every ask after the first fails the same way.</p>
     */
    private static List<String> optionsOf(Param.Choice choice) {
        try {
            return List.copyOf(choice.options());
        } catch (RuntimeException | LinkageError noPlayerFilesHere) {
            return List.of();
        }
    }
}
