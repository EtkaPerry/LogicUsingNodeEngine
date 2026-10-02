package com.etka.lune.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code config/lune-server.json}: made when a server first starts, read, watched, and never trampled. */
class ServerRulesFileTest {

    @TempDir
    Path config;

    private Path file() {
        return config.resolve(ServerRulesFile.NAME);
    }

    /** Moves the file's clock on, so a rewrite within the same second still reads as an edit. */
    private void touchLater() throws IOException {
        Files.setLastModifiedTime(file(), FileTime.fromMillis(Files.getLastModifiedTime(file()).toMillis() + 5_000));
    }

    /** A dedicated server writes its defaults out, so its owner has a file to find and edit. */
    @Test
    void aDedicatedServerWritesItsDefaultsOut() throws IOException {
        ServerRulesFile rules = new ServerRulesFile(file());
        assertEquals(ServerRules.DEDICATED, rules.load(ServerRules.DEDICATED, true));
        String written = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(written.contains("\"run\": \"operators\""), written);
        assertTrue(written.contains("\"cheats\": \"operators\""), written);
    }

    /** A world opened to LAN only gets a file once its host changes something. */
    @Test
    void anOwnWorldLeavesNoFileBehindUntilSomethingChanges() {
        ServerRulesFile rules = new ServerRulesFile(file());
        assertEquals(ServerRules.OWN_WORLD, rules.load(ServerRules.OWN_WORLD, false));
        assertFalse(Files.exists(file()));
    }

    @Test
    void whatIsWrittenIsReadBack() {
        ServerRules wanted = new ServerRules(ServerRules.Who.EVERYONE, ServerRules.Who.NOBODY);
        assertTrue(new ServerRulesFile(file()).save(wanted));
        assertEquals(wanted, new ServerRulesFile(file()).load(ServerRules.DEDICATED, true));
    }

    /** An edit by hand is noticed; the file's own writes, and an edit that says the same thing, are not. */
    @Test
    void anEditByHandIsNoticedAndNothingElseIs() throws IOException {
        ServerRulesFile rules = new ServerRulesFile(file());
        ServerRules current = rules.load(ServerRules.DEDICATED, true);
        assertNull(rules.changedSince(current), "its own write is no news");

        Files.writeString(file(), "{ \"run\": \"everyone\", \"cheats\": \"operators\" }", StandardCharsets.UTF_8);
        touchLater();
        ServerRules edited = rules.changedSince(current);
        assertEquals(new ServerRules(ServerRules.Who.EVERYONE, ServerRules.Who.OPERATORS), edited);
        assertNull(rules.changedSince(edited), "read once, then quiet until the next edit");
    }

    /**
     * A file that does not parse is somebody's half-finished edit: the rules in force stay, and the
     * file is left exactly as it is for them to finish.
     */
    @Test
    void aHalfFinishedEditKeepsTheRulesAndTheFile() throws IOException {
        ServerRulesFile rules = new ServerRulesFile(file());
        ServerRules current = rules.load(ServerRules.DEDICATED, true);
        String broken = "{ \"run\": \"everyone\", ";
        Files.writeString(file(), broken, StandardCharsets.UTF_8);
        touchLater();

        assertNull(rules.changedSince(current));
        assertEquals(ServerRules.DEDICATED, new ServerRulesFile(file()).load(ServerRules.DEDICATED, true));
        assertEquals(broken, Files.readString(file(), StandardCharsets.UTF_8));
    }

    /** A misspelt rule keeps what was there; the other rule still takes effect. */
    @Test
    void aMisspeltRuleKeepsItsOldValueAndTheOtherStillCounts() throws IOException {
        ServerRulesFile rules = new ServerRulesFile(file());
        ServerRules current = rules.load(ServerRules.DEDICATED, true);
        Files.writeString(file(), "{ \"run\": \"evryone\", \"cheats\": \"nobody\" }", StandardCharsets.UTF_8);
        touchLater();
        assertEquals(new ServerRules(ServerRules.Who.OPERATORS, ServerRules.Who.NOBODY), rules.changedSince(current));
    }
}
