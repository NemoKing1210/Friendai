package dev.friendai.skin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.testutil.MockHttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkinFetcherTest {
    private static final Predicate<URI> LOCAL = uri -> "127.0.0.1".equals(uri.getHost());
    private static final byte[] TEXTURE = {10, 20, 30, 40};

    private MockHttpServer server;
    private SkinFetcher fetcher;

    @BeforeEach
    void start() {
        server = new MockHttpServer();
        fetcher = new SkinFetcher(
                SkinFetcher.newClient(), Duration.ofSeconds(5), server.baseUri(), server.baseUri(), LOCAL, LOCAL);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String textureUrl(String path) {
        return server.baseUrl() + path;
    }

    private static String profile(String textureUrl, String model) {
        String metadata = model == null ? "" : ",\"metadata\":{\"model\":\"" + model + "\"}";
        String textures = "{\"textures\":{\"SKIN\":{\"url\":\"" + textureUrl + "\"" + metadata + "}}}";
        String value = Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8));
        return "{\"id\":\"abc\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + value + "\"}]}";
    }

    private String keyOf(Runnable action) {
        return assertThrows(UserFacingException.class, action::run)
                .userMessage()
                .key();
    }

    @Test
    void usernameResolvesToSkinAndSlimFlag() {
        server.enqueue(200, "{\"id\":\"abc\",\"name\":\"Alex\"}");
        server.enqueue(200, profile(textureUrl("/texture/x"), "slim"));
        server.enqueue(200, TEXTURE);

        FetchedSkin skin = fetcher.byUsername("Alex");

        assertTrue(skin.slim());
        assertArrayEquals(TEXTURE, skin.png());
        assertEquals("/users/profiles/minecraft/Alex", server.requests().get(0).path());
        assertEquals("/session/minecraft/profile/abc", server.requests().get(1).path());
        assertEquals("/texture/x", server.requests().get(2).path());
    }

    @Test
    void missingModelMetadataMeansWide() {
        server.enqueue(200, "{\"id\":\"abc\",\"name\":\"Steve\"}");
        server.enqueue(200, profile(textureUrl("/texture/y"), null));
        server.enqueue(200, TEXTURE);

        assertFalse(fetcher.byUsername("Steve").slim());
    }

    @Test
    void unknownUsernameFails() {
        server.enqueue(404, "{\"errorMessage\":\"not found\"}");

        assertEquals(SkinFetcher.UNKNOWN_USER, keyOf(() -> fetcher.byUsername("Nobody")));
    }

    @Test
    void invalidNameNeverCallsTheServer() {
        for (String name : new String[] {"", " ", "a b", "seventeen_chars_xx", "a/b", "Ünï", null}) {
            assertEquals("friendai.skin.error.bad_name", keyOf(() -> fetcher.byUsername(name)), String.valueOf(name));
        }
        assertEquals(0, server.requests().size());
    }

    @Test
    void foreignTextureHostIsRejectedBeforeItIsFetched() {
        server.enqueue(200, "{\"id\":\"abc\",\"name\":\"Alex\"}");
        server.enqueue(200, profile("https://evil.example.com/skin.png", null));

        assertEquals("friendai.skin.error.host", keyOf(() -> fetcher.byUsername("Alex")));
        assertEquals(2, server.requests().size());
    }

    @Test
    void profileWithoutSkinFails() {
        server.enqueue(200, "{\"id\":\"abc\",\"name\":\"Alex\"}");
        server.enqueue(200, "{\"id\":\"abc\",\"properties\":[]}");

        assertEquals("friendai.skin.error.no_skin", keyOf(() -> fetcher.byUsername("Alex")));
    }

    @Test
    void urlMustBeHttps() {
        SkinFetcher strict = new SkinFetcher(
                SkinFetcher.newClient(),
                Duration.ofSeconds(5),
                server.baseUri(),
                server.baseUri(),
                LOCAL,
                uri -> "https".equals(uri.getScheme()));

        assertEquals("friendai.skin.error.not_https", keyOf(() -> strict.byUrl(textureUrl("/a.png"))));
        assertEquals("friendai.skin.error.not_https", keyOf(() -> strict.byUrl("not a url")));
        assertEquals(0, server.requests().size());
    }

    @Test
    void urlReturnsTheBody() {
        server.enqueue(200, TEXTURE);

        assertArrayEquals(TEXTURE, fetcher.byUrl(textureUrl("/a.png")));
    }

    @Test
    void bodyOverTheLimitIsRejected() {
        server.enqueue(200, new byte[SkinFetcher.MAX_BODY + 1]);

        assertEquals("friendai.skin.error.too_large", keyOf(() -> fetcher.byUrl(textureUrl("/big.png"))));
    }

    @Test
    void errorStatusIsANetworkError() {
        server.enqueue(500, "boom");

        assertEquals("friendai.skin.error.network", keyOf(() -> fetcher.byUrl(textureUrl("/a.png"))));
    }

    @Test
    void theSharedClientNeverFollowsRedirects() {
        assertEquals(HttpClient.Redirect.NEVER, SkinFetcher.newClient().followRedirects());
    }

    @Test
    void mojangHttpTextureUrlIsUpgradedToHttps() {
        assertEquals(
                URI.create("https://textures.minecraft.net/texture/abc"),
                SkinFetcher.upgrade(URI.create("http://textures.minecraft.net/texture/abc")));
        assertEquals(URI.create("http://127.0.0.1/x"), SkinFetcher.upgrade(URI.create("http://127.0.0.1/x")));
    }
}
