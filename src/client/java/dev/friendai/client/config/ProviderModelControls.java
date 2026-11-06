package dev.friendai.client.config;

import dev.friendai.agent.llm.provider.AuthStyle;
import dev.friendai.agent.llm.provider.ProviderRegistry;
import dev.friendai.agent.turn.ErrorMessages;
import dev.friendai.common.Components;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.llm.LlmClient;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The provider screen's asynchronous work: fetching the model list for the pending credentials, the automatic
 * first fetch, the connection test and the text-only verdict it leaves next to the model. Every answer is
 * dropped unless the screen that asked for it is still the open one.
 */
final class ProviderModelControls {
    /** Providers whose model list was already fetched automatically this session. */
    private static final Set<String> AUTO_FETCHED = ConcurrentHashMap.newKeySet();

    /**
     * Models the connectivity test saw answering with text instead of calling the tool, by
     * "provider|model". Only a real request can tell, so the verdict is recorded when the player
     * tests the connection and stays next to the model option for the rest of the session.
     */
    private static final Map<String, UserMessage> TEXT_ONLY_MODELS = new ConcurrentHashMap<>();

    /** What the screen must supply for an answer to be delivered: identity, a toast and a rebuild. */
    interface Callbacks {
        /** True when the screen that started the request is still open at the same revision. */
        boolean current(long generation, long revision);

        void toast(Component message);

        /** Rebuilds the screen from the same session with the pending widgets' values. */
        void rebuilt();
    }

    private ProviderModelControls() {}

    /** Fetches the model list for the pending settings, then rebuilds the screen keeping them. */
    static void loadModels(LlmClient client, String provider, long generation, long revision, Callbacks callbacks) {
        client.listModels()
                .whenComplete((models, error) -> Minecraft.getInstance().execute(() -> {
                    if (!callbacks.current(generation, revision)) return;
                    if (error != null) {
                        callbacks.toast(Components.of(ErrorMessages.forUser(error)));
                        return;
                    }
                    ModelCache.put(provider, models);
                    callbacks.toast(Component.translatable("friendai.config.models_loaded", models.size()));
                    callbacks.rebuilt();
                }));
    }

    /**
     * Fetches the list once for a provider that already has a key and no cached list, so the model field is
     * not empty on first open. Switching to a cached provider never triggers it, and a provider without a key
     * is left alone.
     */
    static void autoFetchIfNeeded(
            LlmClient client,
            String provider,
            String baseUrl,
            String apiKey,
            ProviderRegistry registry,
            long generation,
            long revision,
            Callbacks callbacks) {
        String autoKey = provider + "|" + (baseUrl == null ? "" : baseUrl) + "|"
                + Integer.toHexString(String.valueOf(apiKey).hashCode());
        if ("openai_compat".equals(provider)
                || !ModelCache.get(provider).isEmpty()
                || apiKey == null
                || apiKey.isBlank()
                || registry.preset(provider)
                        .filter(preset -> preset.authStyle() != AuthStyle.NONE)
                        .isEmpty()
                || !AUTO_FETCHED.add(autoKey)) {
            return;
        }
        client.listModels()
                .whenComplete((models, error) -> Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        return;
                    }
                    ModelCache.put(provider, models);
                    if (callbacks.current(generation, revision)) {
                        callbacks.rebuilt();
                    }
                }));
    }

    /**
     * Runs the runtime's own connectivity check against the pending credentials. Its verdict decides whether
     * the model can call a tool; it is kept so the model option carries the reason after the toast is gone.
     */
    static void testConnection(
            LlmClient client, String provider, String model, long generation, long revision, Callbacks callbacks) {
        client.testConnection()
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    if (!callbacks.current(generation, revision)) return;
                    if (error != null) {
                        callbacks.toast(Components.of(ErrorMessages.forUser(error)));
                        return;
                    }
                    rememberVerdict(provider, model, result);
                    callbacks.toast(Components.of(result));
                    callbacks.rebuilt();
                }));
    }

    /** The text-only warning for this provider and model, or null when the turn offers no tools at all. */
    static UserMessage notice(String provider, String model, boolean toolsEnabled) {
        String key = toolsEnabled ? verdictKey(provider, model) : null;
        return key == null ? null : TEXT_ONLY_MODELS.get(key);
    }

    private static void rememberVerdict(String provider, String model, UserMessage verdict) {
        String key = verdictKey(provider, model);
        if (key == null) return;
        if ("friendai.config.test.ok_text".equals(verdict.key())) {
            TEXT_ONLY_MODELS.put(key, verdict);
        } else {
            TEXT_ONLY_MODELS.remove(key);
        }
    }

    private static String verdictKey(String provider, String model) {
        if (provider == null || model == null || model.isBlank()) return null;
        return provider.strip() + "|" + model.strip();
    }
}
