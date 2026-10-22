package dev.friedai.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friedai.llm.http.HttpTransport;
import dev.friedai.llm.model.LlmErrorKind;
import dev.friedai.llm.model.LlmException;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.provider.LlmProfile;
import dev.friedai.llm.provider.ProviderRegistry;
import dev.friedai.testutil.MockHttpServer;
import dev.friedai.text.UserMessage;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmClientTest {
    private MockHttpServer server;
    private LlmClient client;

    @BeforeEach
    void setUp() {
        server = new MockHttpServer();
        ProviderRegistry registry = ProviderRegistry.withBuiltins(
                new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), 0, Duration.ofMillis(1)));
        client = new LlmClient(registry, new LlmProfile("openai_compat", "m1", "k", server.baseUrl(), null, 0.7, 512));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void completeBuildsRequestFromProfile() throws Exception {
        server.enqueue(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}");
        assertEquals("hi", client.complete("SYS", List.of(Message.user("yo")), List.of()).get().text());
        JsonObject body = JsonParser.parseString(server.requests().get(0).body()).getAsJsonObject();
        assertEquals("m1", body.get("model").getAsString());
        assertEquals(512, body.get("max_tokens").getAsInt());
        assertEquals(0.7, body.get("temperature").getAsDouble(), 1e-9);
    }

    @Test
    void testConnectionReportsToolCalling() throws Exception {
        server.enqueue(200, """
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"c","type":"function","function":{"name":"ping","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""");
        assertEquals(UserMessage.of("friedai.config.test.ok_tools"), client.testConnection().get());
    }

    @Test
    void testConnectionReportsText() throws Exception {
        server.enqueue(200, "{\"choices\":[{\"message\":{\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}");
        assertEquals(UserMessage.of("friedai.config.test.ok_text"), client.testConnection().get());
    }

    @Test
    void testConnectionReportsError() {
        server.enqueue(401, "{}");
        ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class, () -> client.testConnection().get());
        assertEquals(LlmErrorKind.AUTH, ((LlmException) e.getCause()).kind());
    }
}
