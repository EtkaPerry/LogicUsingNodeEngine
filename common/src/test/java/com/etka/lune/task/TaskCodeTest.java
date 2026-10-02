package com.etka.lune.task;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The share code, which Lune, the share service and the share page all read.
 *
 * <p>The page's half is the share site's own JavaScript copy of the format. The fixed code below
 * was written in that format by something other than this class, so a change on either side that
 * the other cannot read fails here rather than in somebody's Import.</p>
 */
class TaskCodeTest {

    private static final String SHARE = "https://lunode.etka.co.uk";

    /** zlib and base64url, written outside Java: a start card wired to a Chop Wood card. */
    private static final String FROM_THE_PAGE = "v1:eJxdjb0KAjEQhF_l2DqFkUMlb2BhZeMhFmt28VLkhyRXSMi7u4oiWs7HzDcNAnoGAwckHq73oc48JLwxKAiRuIA5N3AkjaKF2eg9Btq_QMVchTG5GvMJzHr8hEnCRkEMx8VaLmIBq6Grt8r-q-wck6CEGb2UG2Qktzxn40pm3wu93f189Et_AEsXPK0";

    @Test
    void aTaskComesBackAsItLeft() {
        TaskGraph task = new TaskGraph("Round trip");
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        TaskNode chop = new TaskNode("chop");
        chop.params.put("radius", "40");
        chop.editorX = 178;
        chop.editorY = 26;
        start.onSuccess = chop.id;
        task.nodes.add(start);
        task.nodes.add(chop);
        TaskNote note = new TaskNote(10, 20);
        note.text = "Çok ağaç var - a note in two languages";
        task.notes.add(note);
        task.cableAnchors.put(TaskCableAnchor.key("success", start.id, chop.id),
                new TaskCableRoute(new TaskCableAnchor(150, 60)));

        String code = TaskCode.encode(task);

        assertTrue(code.startsWith(TaskCode.PREFIX));
        assertTrue(code.substring(TaskCode.PREFIX.length()).matches("[A-Za-z0-9_-]+"),
                "a code must survive a link and a chat message untouched: " + code);
        assertEquals(TaskStore.exportCompact(task), TaskCode.decode(code));
    }

    @Test
    void aCodeIsFarShorterThanTheJsonItCarries() {
        TaskGraph task = new TaskGraph("Many cards");
        for (int i = 0; i < 40; i++) {
            TaskNode node = new TaskNode("mine");
            node.params.put("radius", String.valueOf(16 + i));
            node.editorX = 24 + (i % 6) * 154;
            node.editorY = 26 + (i / 6) * 118;
            task.nodes.add(node);
        }
        // Against the JSON with no layout at all; Export's indented copy is longer still.
        int json = TaskStore.exportCompact(task).length();
        int code = TaskCode.encode(task).length();
        assertTrue(code * 4 < json, "code " + code + " characters against JSON " + json);
    }

    @Test
    void readsACodeThePageWrote() {
        JsonObject task = JsonParser.parseString(TaskCode.decode(FROM_THE_PAGE)).getAsJsonObject();
        assertEquals("Made by the page", task.get("name").getAsString());
        assertEquals("40", task.getAsJsonArray("nodes").get(1).getAsJsonObject()
                .getAsJsonObject("params").get("radius").getAsString());
    }

    @Test
    void refusesWhatIsNotACode() {
        assertThrows(IllegalArgumentException.class, () -> TaskCode.decode("hello"));
        assertThrows(IllegalArgumentException.class, () -> TaskCode.decode("v1:not*base64!"));
        assertThrows(IllegalArgumentException.class, () -> TaskCode.decode("v1:AAAA"));
        String whole = FROM_THE_PAGE;
        assertThrows(IllegalArgumentException.class,
                () -> TaskCode.decode(whole.substring(0, whole.length() / 2)));
    }

    @Test
    void aCodeBuiltToExpandForeverIsRefusedPartWay() {
        byte[] spaces = new byte[TaskCode.MAX_JSON_BYTES + 1024];
        java.util.Arrays.fill(spaces, (byte) ' ');
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(spaces);
        deflater.finish();
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            packed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        String bomb = TaskCode.PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(packed.toByteArray());
        assertTrue(bomb.length() < 10_000, "the point is a small code: " + bomb.length());
        assertThrows(IllegalArgumentException.class, () -> TaskCode.decode(bomb));
    }

    @Test
    void readsEveryWayATaskArrives() {
        assertInstanceOf(TaskCode.Json.class, read("  {\"name\":\"x\",\"nodes\":[]}  ").orElseThrow());
        assertEquals(new TaskCode.Code(FROM_THE_PAGE), read(FROM_THE_PAGE).orElseThrow());
        assertEquals(new TaskCode.Code(FROM_THE_PAGE), read(SHARE + "/#" + FROM_THE_PAGE).orElseThrow());
        assertEquals(new TaskCode.Link("JPWj2usTjV"), read(SHARE + "/JPWj2usTjV").orElseThrow());
        assertEquals(new TaskCode.Link("JPWj2usTjV"), read(SHARE + "/JPWj2usTjV/\n").orElseThrow());
        // A view-and-edit link is the same task: Import fetches it as it stands, key or no key.
        assertEquals(new TaskCode.Link("JPWj2usTjV"),
                read(SHARE + "/JPWj2usTjV#edit=" + "e".repeat(32)).orElseThrow());
    }

    @Test
    void fetchesFromNowhereButTheShareService() {
        assertEquals(Optional.empty(), read("https://example.com/JPWj2usTjV"));
        assertEquals(Optional.empty(), read("https://lunode.etka.co.uk.example.com/JPWj2usTjV"));
        assertEquals(Optional.empty(), read("https://lunode.etka.co.uk:8443/JPWj2usTjV"));
        assertEquals(Optional.empty(), read("file:///C:/JPWj2usTjV"));
        assertEquals(Optional.empty(), read(SHARE + "/abc"));
        assertEquals(Optional.empty(), read(SHARE + "/api/share/JPWj2usTjV"));
        assertEquals(Optional.empty(), read("just some words"));
        assertEquals(Optional.empty(), read(""));
        assertEquals(Optional.empty(), read(null));
        // A local copy of the service is its own address, port and all.
        assertEquals(Optional.of(new TaskCode.Link("JPWj2usTjV")),
                TaskCode.read("http://127.0.0.1:8787/JPWj2usTjV", "http://127.0.0.1:8787"));
    }

    @Test
    void aCodeInsideAnyLinkIsStillOnlyText() {
        // Reading it needs no network, so where the link points does not matter.
        assertEquals(new TaskCode.Code(FROM_THE_PAGE),
                read("https://example.com/#" + FROM_THE_PAGE).orElseThrow());
        assertEquals("Made by the page", JsonParser.parseString(TaskCode.decode(
                ((TaskCode.Code) read("https://example.com/#" + FROM_THE_PAGE).orElseThrow()).code()))
                .getAsJsonObject().get("name").getAsString());
    }

    private static Optional<TaskCode.Pasted> read(String text) {
        return TaskCode.read(text, SHARE);
    }
}
