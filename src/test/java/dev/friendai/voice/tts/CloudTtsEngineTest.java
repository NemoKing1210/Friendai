package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.config.ClientConfig;
import dev.friendai.testutil.MockHttpServer;
import dev.friendai.text.UserFacingException;
import dev.friendai.voice.VoiceConfig;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudTtsEngineTest {
    private MockHttpServer server;

    @BeforeEach
    void setUp() {
        server = new MockHttpServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static ClientConfig.TtsSection openAiSection(String baseUrl, String key) {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        ClientConfig.TtsSection cfg = c.voice.tts;
        cfg.provider = "openai";
        ClientConfig.CloudSettings cloud = cfg.cloud.get("openai");
        cloud.baseUrl = baseUrl;
        cloud.apiKey = key;
        return cfg;
    }

    private CloudTtsEngine engine(ClientConfig.TtsSection cfg) {
        return new CloudTtsEngine(
                TtsSettings.from(VoiceConfig.resolveTts(cfg, "en_us", name -> ""), cfg.volume),
                new OpenAiSpeechProtocol(),
                HttpClient.newHttpClient());
    }

    @Test
    void synthesizesThroughACustomCompatibleEndpoint() {
        byte[] wav = WavCodec.encode(new TtsClip(new byte[] {1, 0, 2, 0}, 24000));
        server.enqueue(200, wav);
        TtsClip clip = engine(openAiSection(server.baseUrl() + "/v1", "")).synthesize("Hello").join();
        assertArrayEquals(new byte[] {1, 0, 2, 0}, clip.pcm16le());
        assertEquals(24000, clip.sampleRate());
        assertFalse(server.requests().get(0).headers().containsKey("authorization"));
    }

    @Test
    void keyTravelsInAHeader() {
        server.enqueue(200, WavCodec.encode(new TtsClip(new byte[] {1, 0}, 24000)));
        try (CloudTtsEngine engine = engine(openAiSection(server.baseUrl() + "/v1", "sk-secret"))) {
            engine.synthesize("Hi").join();
        }
        assertEquals("Bearer sk-secret", server.requests().get(0).header("authorization"));
    }

    @Test
    void withoutAKeyItIsNotReadyAndSaysWhy() throws Exception {
        CloudTtsEngine engine = engine(openAiSection(null, null));
        assertFalse(engine.isReady());
        ExecutionException error =
                assertThrows(ExecutionException.class, () -> engine.synthesize("Hi").get());
        assertEquals(
                "friendai.voice.tts.error.no_key",
                ((UserFacingException) error.getCause()).userMessage().key());
    }

    @Test
    void afterCloseItRefuses() throws Exception {
        CloudTtsEngine engine = engine(openAiSection(server.baseUrl() + "/v1", ""));
        engine.close();
        assertFalse(engine.isReady());
        ExecutionException error =
                assertThrows(ExecutionException.class, () -> engine.synthesize("Hi").get());
        assertEquals(
                "friendai.voice.tts.error.failed",
                ((UserFacingException) error.getCause()).userMessage().key());
    }
}
