package dev.friendai.agent.llm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelListCacheTest {
    @Test
    void aFreshListIsServedWithoutRefetchingAndExpires() {
        ModelListCache cache = new ModelListCache(1_000);
        cache.put("openrouter|https://api", List.of("a", "b"), 10);
        assertEquals(List.of("a", "b"), cache.get("openrouter|https://api", 10));
        assertTrue(cache.isFresh("openrouter|https://api", 500));
        assertFalse(cache.isFresh("openrouter|https://api", 2_000));
    }

    @Test
    void anExpiredListStillFallsBackToTheLastGoodOne() {
        ModelListCache cache = new ModelListCache(1_000);
        cache.put("groq|", List.of("llama"), 10);
        assertEquals(List.of("llama"), cache.get("groq|", 5_000));
    }

    @Test
    void aRestoredListIsServedAsAStaleFallback() {
        ModelListCache cache = new ModelListCache(1_000);
        cache.restore(java.util.Map.of("groq|", List.of("llama")));
        assertEquals(List.of("llama"), cache.get("groq|", 5_000));
        assertFalse(cache.isFresh("groq|", 5_000));
    }

    @Test
    void anEmptyAnswerNeverErasesTheLastGoodList() {
        ModelListCache cache = new ModelListCache(1_000);
        cache.put("mistral|", List.of("m"), 10);
        cache.put("mistral|", List.of(), 20);
        assertEquals(List.of("m"), cache.get("mistral|", 30));
    }
}
