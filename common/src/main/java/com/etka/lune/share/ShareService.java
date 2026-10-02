package com.etka.lune.share;

import com.etka.lune.Links;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Lune's side of the share service at {@link Links#SHARE}: hand it a task code and get a link back,
 * fetch the code behind a link, delete a link with the key it came with.
 *
 * <p>Asynchronous all the way, because every call here waits on somebody else's computer and the
 * game must not. Results arrive on the HTTP client's threads; callers hop back to the game's
 * thread before touching anything of the game's.</p>
 *
 * <p>Nothing reaches the service until the player asks: Share sends one task when its button is
 * pressed, and Import fetches one link when it is pasted. There is no background traffic.</p>
 */
public final class ShareService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String USER_AGENT = "Lune (+" + Links.REPOSITORY + ")";

    /**
     * A task the service now keeps.
     *
     * @param link the link to hand out: the view-and-edit one when the share is editable
     * @param editKey null for a view-only share, which nobody can change
     */
    public record Shared(String id, String link, String deleteKey, String editKey) {
        public boolean editable() {
            return editKey != null;
        }
    }

    /** The service answered that no task is kept under that id: expired or deleted. */
    public static final class Gone extends IOException {
        public Gone() {
            super("no task is kept under that link");
        }
    }

    /** The service answered, and said no: too many uploads, too large, not a task. */
    public static final class Refused extends IOException {
        private final int status;

        public Refused(int status) {
            super("the share service refused with " + status);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static ShareService standard;

    private final String base;
    private final HttpClient client;

    public ShareService(String base) {
        this.base = base.replaceAll("/+$", "");
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * The one the game uses. {@code -Dlune.share.url} points it at a local copy of the service
     * instead, for working on the share site itself.
     */
    public static synchronized ShareService standard() {
        if (standard == null) {
            String own = System.getProperty("lune.share.url");
            standard = new ShareService(own == null || own.isBlank() ? Links.SHARE : own.strip());
        }
        return standard;
    }

    public String base() {
        return base;
    }

    /** The service's address as a person would write it, for lines that name it. */
    public String host() {
        URI uri = URI.create(base);
        return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    /** The short link to a kept task. */
    public String linkTo(String id) {
        return base + "/" + id;
    }

    /**
     * The same, carrying the key that lets whoever opens it change the task. The key sits after
     * the {@code #}, which a browser never sends to the server when it loads the page.
     */
    public String editLinkTo(String id, String editKey) {
        return linkTo(id) + "#edit=" + editKey;
    }

    /** A link with the whole task inside it, which needs nothing kept anywhere to open. */
    public String linkCarrying(String code) {
        return base + "/#" + code;
    }

    /**
     * Hands a task to the service.
     *
     * @param editable whether whoever has the link may change the task behind it; a view-only
     *                 share has no edit key at all, so nobody can
     */
    public CompletableFuture<Shared> upload(String code, boolean editable) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("editable", editable);
        HttpRequest request = request("/api/share")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 201) {
                        throw new CompletionException(failure(response.statusCode()));
                    }
                    JsonObject made = JsonParser.parseString(response.body()).getAsJsonObject();
                    String id = made.get("id").getAsString();
                    String editKey = made.has("editKey") && !made.get("editKey").isJsonNull()
                            ? made.get("editKey").getAsString() : null;
                    return new Shared(id, editKey == null ? linkTo(id) : editLinkTo(id, editKey),
                            made.get("deleteKey").getAsString(), editKey);
                });
    }

    /**
     * The code kept under a link's id, as it stands now: a view-and-edit link may have been
     * changed on the website since it was made.
     */
    public CompletableFuture<String> download(String id) {
        HttpRequest request = request("/api/share/" + id).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new CompletionException(failure(response.statusCode()));
                    }
                    return JsonParser.parseString(response.body()).getAsJsonObject()
                            .get("code").getAsString();
                });
    }

    public CompletableFuture<Void> delete(String id, String deleteKey) {
        HttpRequest request = request("/api/share/" + id)
                .header("Authorization", "Bearer " + deleteKey)
                .DELETE()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> {
                    // Already gone is as deleted as it gets.
                    if (response.statusCode() != 204 && response.statusCode() != 404) {
                        throw new CompletionException(failure(response.statusCode()));
                    }
                    return null;
                });
    }

    /** Whether a failure from any of the calls above means the link is gone, not unreachable. */
    public static boolean isGone(Throwable failure) {
        return cause(failure) instanceof Gone;
    }

    /** The failure under the wrappers a CompletableFuture puts round it. */
    public static Throwable cause(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT);
    }

    private static IOException failure(int status) {
        return status == 404 ? new Gone() : status >= 400 && status < 500
                ? new Refused(status) : new IOException("the share service answered " + status);
    }
}
