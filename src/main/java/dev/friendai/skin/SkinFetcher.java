package dev.friendai.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Downloads a skin by Minecraft username or by URL. The calls block, so the client runs them on a worker thread;
 * every failure is a {@link UserFacingException}. Redirects are never followed, bodies are size-capped, and a
 * texture named by a profile is fetched only from a host the {@code textureAllowed} predicate accepts.
 */
public final class SkinFetcher {
    public static final int MAX_BODY = 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String ERROR = "friendai.skin.error.";

    private final HttpClient client;
    private final Duration timeout;
    private final URI apiBase;
    private final URI sessionBase;
    private final Predicate<URI> textureAllowed;
    private final Predicate<URI> urlAllowed;

    public SkinFetcher(
            HttpClient client,
            Duration timeout,
            URI apiBase,
            URI sessionBase,
            Predicate<URI> textureAllowed,
            Predicate<URI> urlAllowed) {
        this.client = client;
        this.timeout = timeout;
        this.apiBase = apiBase;
        this.sessionBase = sessionBase;
        this.textureAllowed = textureAllowed;
        this.urlAllowed = urlAllowed;
    }

    /** The real Mojang endpoints: textures only from {@code textures.minecraft.net}, user URLs only over https. */
    public static SkinFetcher mojang(HttpClient client) {
        return new SkinFetcher(
                client,
                Duration.ofSeconds(10),
                URI.create("https://api.mojang.com"),
                URI.create("https://sessionserver.mojang.com"),
                uri -> "https".equals(uri.getScheme()) && "textures.minecraft.net".equals(uri.getHost()),
                uri -> "https".equals(uri.getScheme()) && uri.getHost() != null);
    }

    public static HttpClient newClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public FetchedSkin byUsername(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw error("bad_name");
        }
        JsonObject account = getJson(apiBase.resolve("/users/profiles/minecraft/" + name), "unknown_user");
        String id = string(account, "id");
        if (id == null) {
            throw error("unknown_user");
        }
        JsonObject profile = getJson(sessionBase.resolve("/session/minecraft/profile/" + id), "unknown_user");
        JsonObject skin = skinEntry(profile);
        String url = skin == null ? null : string(skin, "url");
        if (url == null) {
            throw error("no_skin");
        }
        URI texture = parse(url);
        if (texture == null || !textureAllowed.test(texture)) {
            throw error("host");
        }
        boolean slim = skin.has("metadata")
                && skin.get("metadata").isJsonObject()
                && "slim".equals(string(skin.getAsJsonObject("metadata"), "model"));
        return new FetchedSkin(get(texture), slim);
    }

    public byte[] byUrl(String url) {
        URI uri = url == null ? null : parse(url.strip());
        if (uri == null || !urlAllowed.test(uri)) {
            throw error("not_https");
        }
        return get(uri);
    }

    private static JsonObject skinEntry(JsonObject profile) {
        JsonElement properties = profile.get("properties");
        if (properties == null || !properties.isJsonArray()) {
            return null;
        }
        JsonArray array = properties.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject() || !"textures".equals(string(element.getAsJsonObject(), "name"))) {
                continue;
            }
            String value = string(element.getAsJsonObject(), "value");
            if (value == null) {
                return null;
            }
            try {
                String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                JsonObject textures = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures");
                return textures == null ? null : textures.getAsJsonObject("SKIN");
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private JsonObject getJson(URI uri, String notFoundKey) {
        byte[] body = request(uri, notFoundKey);
        try {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw error("network");
        }
    }

    private byte[] get(URI uri) {
        return request(uri, "network");
    }

    /** One GET with a hard body cap. A 404 or 204 maps to {@code notFoundKey}; any other non-200 is a network error. */
    private byte[] request(URI uri, String notFoundKey) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                int status = response.statusCode();
                if (status == 404 || status == 204) {
                    throw error(notFoundKey);
                }
                if (status != 200) {
                    throw error("network");
                }
                byte[] body = in.readNBytes(MAX_BODY + 1);
                if (body.length > MAX_BODY) {
                    throw error("too_large");
                }
                return body;
            }
        } catch (IOException e) {
            throw error("network");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error("network");
        }
    }

    private static URI parse(String text) {
        try {
            URI uri = URI.create(text);
            return uri.getScheme() == null || uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static UserFacingException error(String suffix) {
        return new UserFacingException(UserMessage.of(ERROR + suffix));
    }
}
