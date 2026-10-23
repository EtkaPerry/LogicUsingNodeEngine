package com.etka.lune.task.runner;

import com.etka.lune.bot.StatusText;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A status read on another machine is the status that was set, arguments and all. */
class StatusJsonTest {

    @Test
    void aStatusComesBackWithItsArgumentsAndTheirKinds() {
        StatusText child = new StatusText().set("lune.status.task_runner.timer_waiting_s", 5);
        StatusText status = new StatusText().set("lune.status.task_runner.step_detail", 2, 7,
                new StatusText().set("lumi.command.follow_me.name"), child);
        StatusText back = StatusJson.read(JsonParser.parseString(StatusJson.write(status).toString()));

        assertEquals(status.key(), back.key());
        Object[] args = back.args();
        assertEquals(2, args[0]);
        assertEquals(7, args[1]);
        assertEquals("lumi.command.follow_me.name", assertInstanceOf(StatusText.class, args[2]).key());
        StatusText nested = assertInstanceOf(StatusText.class, args[3]);
        assertEquals(5, nested.args()[0]);
        assertEquals(status.text(), back.text(), "it reads the same, in whatever language is loaded");
    }

    @Test
    void decimalsStayDecimalsSoTheirFormatStillWorks() {
        StatusText status = new StatusText().set(
                "lune.status.boat.crossing_by_boat_blocks_left_heading_off", 12.5, 3.0);
        StatusText back = StatusJson.read(StatusJson.write(status));
        assertEquals(status.text(), back.text());
    }

    @Test
    void aMeaningTravelsWithTheStatus() {
        StatusText status = new StatusText().set("lune.status.task_runner.circuit",
                new StatusText().set("lune.engine.player_died"));
        assertEquals(status.signal(), StatusJson.read(StatusJson.write(status)).signal());
    }

    @Test
    void anythingElseReadsAsNothingAndNeverThrows() {
        assertTrue(StatusJson.read(null).isBlank());
        assertTrue(StatusJson.read(new JsonPrimitive("lune.status.task_runner.finished")).isBlank());
        assertTrue(StatusJson.read(JsonParser.parseString("{\"a\":[]}")).isBlank());
        assertTrue(StatusJson.read(JsonParser.parseString("{\"k\":\"x\",\"a\":[{\"i\":\"many\"}]}")).isBlank());
        StatusText odd = StatusJson.read(JsonParser.parseString("{\"k\":\"x\",\"a\":[5,{\"q\":1}]}"));
        assertEquals("x", odd.key());
        assertEquals("", odd.args()[0]);
        assertEquals("", odd.args()[1]);
    }

    @Test
    void nestingStopsAtItsDepth() {
        StatusText deepest = new StatusText().set("lune.status.task_runner.finished");
        for (int level = 0; level < 20; level++) {
            deepest = new StatusText().set("lune.status.detail", "x", deepest);
        }
        JsonObject written = StatusJson.write(deepest);
        int depth = 0;
        StatusText read = StatusJson.read(written);
        while (read.args().length > 1 && read.args()[1] instanceof StatusText inner) {
            read = inner;
            depth++;
        }
        assertTrue(depth < StatusJson.MAX_DEPTH, "nested " + depth + " deep");
    }
}
