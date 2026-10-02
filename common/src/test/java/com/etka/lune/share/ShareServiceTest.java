package com.etka.lune.share;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lune's side of the share service, against a stand-in that answers the way the service's own
 * Worker does - so the client is tested without the network, and a change to either side's
 * routes or replies shows up here.
 */
class ShareServiceTest {

    private static final String ID = "abcDEF2345";
    private static final String KEY = "k".repeat(32);
    private static final String EDIT_KEY = "e".repeat(32);

    private HttpServer server;
    private ShareService service;
    private final AtomicReference<String> uploaded = new AtomicReference<>();
    /** What the stand-in answers an upload with; 201 is the service keeping it. */
    private volatile int uploadStatus = 201;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/share", this::answer);
        server.start();
        service = new ShareService("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (method.equals("POST") && path.equals("/api/share")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            uploaded.set(body);
            boolean editable = JsonParser.parseString(body).getAsJsonObject().get("editable").getAsBoolean();
            reply(exchange, uploadStatus, uploadStatus == 201
                    ? "{\"id\":\"" + ID + "\",\"url\":\"https://lunode.etka.co.uk/" + ID
                            + "\",\"deleteKey\":\"" + KEY + "\""
                            + (editable ? ",\"editKey\":\"" + EDIT_KEY + "\"" : "") + ",\"version\":1}"
                    : "{\"error\":\"rate_limited\"}");
        } else if (method.equals("GET") && path.equals("/api/share/" + ID)) {
            reply(exchange, 200, "{\"code\":\"v1:kept\",\"version\":3,\"editable\":true}");
        } else if (method.equals("DELETE") && path.equals("/api/share/" + ID)) {
            boolean right = ("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, right ? 204 : 403, right ? null : "{\"error\":\"wrong_key\"}");
        } else {
            reply(exchange, 404, "{\"error\":\"not_found\"}");
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Test
    void aViewOnlyUploadComesBackAsAShortLinkAndADeleteKey() throws Exception {
        ShareService.Shared shared = service.upload("v1:the-task", false).get(10, TimeUnit.SECONDS);
        JsonObject sent = JsonParser.parseString(uploaded.get()).getAsJsonObject();
        assertEquals("v1:the-task", sent.get("code").getAsString());
        assertFalse(sent.get("editable").getAsBoolean());
        assertEquals(ID, shared.id());
        assertEquals(KEY, shared.deleteKey());
        assertFalse(shared.editable());
        assertNull(shared.editKey());
        // Built from Lune's own address, not taken from the reply: a link always points at the
        // service Lune was talking to.
        assertEquals(service.base() + "/" + ID, shared.link());
    }

    @Test
    void anEditableUploadHandsOutTheLinkThatCarriesItsKey() throws Exception {
        ShareService.Shared shared = service.upload("v1:the-task", true).get(10, TimeUnit.SECONDS);
        assertTrue(JsonParser.parseString(uploaded.get()).getAsJsonObject().get("editable").getAsBoolean());
        assertTrue(shared.editable());
        assertEquals(EDIT_KEY, shared.editKey());
        // After the #, where a browser keeps it to itself when it loads the page.
        assertEquals(service.base() + "/" + ID + "#edit=" + EDIT_KEY, shared.link());
    }

    @Test
    void aRefusedUploadIsRefusedNotGone() {
        uploadStatus = 429;
        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.upload("v1:the-task", false).join());
        ShareService.Refused refused = assertInstanceOf(ShareService.Refused.class,
                ShareService.cause(failure));
        assertEquals(429, refused.status());
        assertFalse(ShareService.isGone(failure));
    }

    @Test
    void aKeptTaskIsFetchedAsItStandsAndAMissingOneIsGone() throws Exception {
        assertEquals("v1:kept", service.download(ID).get(10, TimeUnit.SECONDS));
        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.download("zzzzzzzzzz").join());
        assertTrue(ShareService.isGone(failure));
    }

    @Test
    void onlyTheKeyDeletes() throws Exception {
        assertNull(service.delete(ID, KEY).get(10, TimeUnit.SECONDS));
        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.delete(ID, "wrong").join());
        assertInstanceOf(ShareService.Refused.class, ShareService.cause(failure));
        // Deleting what is already gone is not a failure: the link is as deleted as it gets.
        assertNull(service.delete("zzzzzzzzzz", KEY).get(10, TimeUnit.SECONDS));
    }

    @Test
    void nobodyListeningIsAFailureNotAHang() {
        server.stop(0);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.upload("v1:the-task", true).join());
        assertInstanceOf(IOException.class, ShareService.cause(failure));
    }

    @Test
    void linksAreBuiltFromTheServiceAddress() {
        ShareService real = new ShareService("https://lunode.etka.co.uk");
        assertEquals("lunode.etka.co.uk", real.host());
        assertEquals("https://lunode.etka.co.uk/" + ID, real.linkTo(ID));
        assertEquals("https://lunode.etka.co.uk/#v1:abc", real.linkCarrying("v1:abc"));
        assertEquals("https://lunode.etka.co.uk/" + ID + "#edit=" + EDIT_KEY, real.editLinkTo(ID, EDIT_KEY));
        assertTrue(service.host().startsWith("127.0.0.1:"));
    }
}
