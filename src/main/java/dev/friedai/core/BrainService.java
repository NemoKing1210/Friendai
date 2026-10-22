package dev.friedai.core;

import dev.friedai.FriedaiMod;
import dev.friedai.brain.BrainEvent;
import dev.friedai.brain.BrainSettings;
import dev.friedai.brain.CompanionBrain;
import dev.friedai.brain.LlmGateway;
import dev.friedai.brain.Persona;
import dev.friedai.brain.Speaker;
import dev.friedai.chat.CompanionSpeaker;
import dev.friedai.config.ConfigException;
import dev.friedai.config.ServerConfig;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.CompanionManager;
import dev.friedai.llm.LlmClient;
import dev.friedai.llm.http.HttpTransport;
import dev.friedai.llm.provider.ProviderRegistry;
import dev.friedai.perception.PerceptionBuilder;
import dev.friedai.task.TaskListener;
import dev.friedai.task.TaskStatus;
import dev.friedai.tool.builtin.FriedaiTools;
import dev.friedai.tool.ToolRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import net.minecraft.server.MinecraftServer;

/** Owns one {@link CompanionBrain} per player and the LLM client they share. All methods run on the server thread. */
public final class BrainService {
    private record Entry(CompanionBrain brain, CompanionSpeaker speaker) {}

    private final MinecraftServer server;
    private final ServerBrainScheduler scheduler;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, LlmGateway> gatewayOverrides = new HashMap<>();
    private final Map<UUID, UnaryOperator<Speaker>> speakerWrappers = new HashMap<>();
    private final ToolRegistry<CompanionEntity> tools = FriedaiTools.create(() -> this.config.tasks.mineSearchRadius);

    private ServerConfig config;
    private LlmGateway current;
    private String activeModel = "";
    private String activeProvider = "";
    private int tickCounter;

    public BrainService(MinecraftServer server, ServerConfig initial) {
        this.server = server;
        this.scheduler = new ServerBrainScheduler(server);
        reload(initial);
    }

    /** Rebuilds the LLM client from the config. A broken profile gives a gateway that reports the problem. */
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
    }

    public String activeProvider() {
        return activeProvider;
    }

    public String activeModel() {
        return activeModel;
    }

    /** Test hook: use this gateway instead of the configured one for one owner. */
    public void setGatewayOverride(UUID owner, LlmGateway gateway) {
        gatewayOverrides.put(owner, gateway);
    }

    /** Test hook: wrap the speaker of one owner's brain. Must be set before the brain is created. */
    public void setSpeakerWrapper(UUID owner, UnaryOperator<Speaker> wrapper) {
        speakerWrappers.put(owner, wrapper);
    }

    public Optional<CompanionBrain> brainOf(UUID owner) {
        return Optional.ofNullable(entries.get(owner)).map(Entry::brain);
    }

    public void post(UUID owner, BrainEvent event) {
        Entry entry = entries.get(owner);
        if (entry == null) {
            Optional<CompanionEntity> companion = CompanionManager.get(server).find(owner);
            if (companion.isEmpty()) {
                return;
            }
            entry = create(owner, companion.get());
        }
        entry.brain().post(event);
    }

    public void cancel(UUID owner) {
        Entry entry = entries.get(owner);
        if (entry != null) {
            entry.brain().cancel();
        }
    }

    /** The owner's companion is gone (death, dismissal, unload): drop its brain. */
    public void onCompanionRemoved(UUID owner) {
        Entry entry = entries.remove(owner);
        if (entry != null) {
            entry.brain().cancel();
        }
    }

    /** The owner left the game. */
    public void onOwnerLeft(UUID owner) {
        cancel(owner);
        CompanionManager.get(server).find(owner).ifPresent(c -> c.taskRunner().stop("owner left"));
    }

    public void tick() {
        scheduler.tick();
        if (++tickCounter % 20 == 0) {
            for (Entry entry : entries.values()) {
                if (entry.brain().isThinking()) {
                    entry.speaker().showThinking();
                }
            }
        }
    }

    private Entry create(UUID owner, CompanionEntity companion) {
        CompanionSpeaker speaker = new CompanionSpeaker(
                server, owner, () -> config.companion.name, () -> config.chat.maxReplyChars);
        Speaker effective = speakerWrappers.getOrDefault(owner, s -> s).apply(speaker);
        CompanionBrain brain = new CompanionBrain(
                () -> new Persona(config.companion.name, config.companion.persona, config.companion.language),
                () -> new BrainSettings(config.brain.maxToolStepsPerTurn, config.brain.historyMessages, config.brain.cooldownMs),
                () -> gatewayOverrides.getOrDefault(owner, current),
                tools.bind(companion),
                () -> PerceptionBuilder.brief(companion).toPromptText(),
                effective,
                scheduler);
        companion.taskRunner().setListener(new TaskListener() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus status, String summary) {
                post(owner, new BrainEvent.TaskFinished(id, toolName, status == TaskStatus.SUCCESS, summary));
            }

            @Override
            public void onCancelled(int id, String toolName, String reason) {
                post(owner, new BrainEvent.TaskCancelled(id, toolName, reason));
            }
        });
        Entry entry = new Entry(brain, speaker);
        entries.put(owner, entry);
        FriedaiMod.LOGGER.debug("Created brain for {}", owner);
        return entry;
    }
}
