package dev.friendai.server.agent;

import dev.friendai.FriendaiMod;
import dev.friendai.agent.llm.provider.ProviderRegistry;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.config.SecretResolver;
import dev.friendai.config.ServerConfig;
import dev.friendai.foundation.diagnostics.ConfigException;
import dev.friendai.foundation.diagnostics.SecretRedactor;
import dev.friendai.llm.LlmClient;
import dev.friendai.llm.http.HttpTransport;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/** Builds the configured model gateways and keeps their redacted display metadata. */
public final class AgentGatewayFactory {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final UnaryOperator<String> redactor = SecretRedactor.of(this::configuredSecrets);
    private ServerConfig config;
    private LlmGateway current;
    private LlmGateway planner;
    private String activeModel = "";
    private String activeProvider = "";
    private String plannerProfileName = "";
    private String plannerModel = "";

    /** Rebuilds both clients from the live config; broken profiles become reportable gateways. */
    public void reload(ServerConfig cfg) {
        this.config = cfg;
        ServerConfig.ProfileConfig profile = cfg.profiles.get(cfg.activeProfile);
        activeProvider = profile == null ? "?" : profile.provider;
        activeModel = profile == null ? "?" : profile.model;
        HttpTransport transport = new HttpTransport(
                httpClient, Duration.ofSeconds(cfg.brain.requestTimeoutSec), 2, Duration.ofMillis(500));
        try {
            current = new LlmClient(ProviderRegistry.withBuiltins(transport), cfg.toLlmProfile(System::getenv));
        } catch (ConfigException e) {
            current = (system, messages, toolSpecs) -> CompletableFuture.failedFuture(e);
        }
        planner = null;
        plannerProfileName = cfg.activeProfile;
        plannerModel = profile == null ? "?" : profile.model;
        String selectedProfile = cfg.agent.planner;
        if (selectedProfile != null && !selectedProfile.isBlank()) {
            if (cfg.profiles.containsKey(selectedProfile)) {
                try {
                    planner = new LlmClient(
                            ProviderRegistry.withBuiltins(transport),
                            cfg.toLlmProfile(selectedProfile, System::getenv));
                    plannerProfileName = selectedProfile;
                    plannerModel = cfg.profiles.get(selectedProfile).model;
                } catch (ConfigException e) {
                    FriendaiMod.LOGGER.warn("The planner profile is unusable; the main profile plans instead");
                }
            } else {
                FriendaiMod.LOGGER.warn("agent.planner names an unknown profile; the main profile plans instead");
            }
        }
        FriendaiMod.LOGGER.info(
                "Planner profile: {} (model {})",
                redactor.apply(String.valueOf(plannerProfileName)),
                redactor.apply(String.valueOf(plannerModel)));
    }

    LlmGateway main(CompanionSession session) {
        return Optional.ofNullable(session.gatewayOverride()).orElse(current);
    }

    LlmGateway planner(CompanionSession session) {
        return planner == null ? main(session) : planner;
    }

    UnaryOperator<String> redactor() {
        return redactor;
    }

    public String activeProvider() {
        return activeProvider;
    }

    public String activeModel() {
        return activeModel;
    }

    public String plannerProfileName() {
        return plannerProfileName;
    }

    public String plannerModel() {
        return plannerModel;
    }

    private List<String> configuredSecrets() {
        List<String> secrets = new ArrayList<>();
        if (config != null) {
            for (ServerConfig.ProfileConfig profile : config.profiles.values()) {
                secrets.add(SecretResolver.resolve(profile.apiKey, System::getenv));
            }
        }
        return secrets;
    }
}
