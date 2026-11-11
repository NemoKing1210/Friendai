package dev.friendai.agent.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.provider.ProviderRegistry;
import dev.friendai.agent.perception.PerceptionSnapshot;
import dev.friendai.agent.route.RouteDecision;
import dev.friendai.agent.turn.AccessResolver;
import dev.friendai.agent.turn.AgentSettings;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.BrainObserver;
import dev.friendai.agent.turn.BrainScheduler;
import dev.friendai.agent.turn.BrainSettings;
import dev.friendai.agent.turn.CompanionBrain;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.MemorySource;
import dev.friendai.agent.turn.Persona;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.ArgValidator;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.llm.HttpStreamingTransport;
import dev.friendai.llm.LlmClient;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.server.agent.tools.FriendaiTools;
import dev.friendai.testutil.RecordingSpeaker;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Replays a scripted conversation against the configured provider with the real tool registry, catalog and routing,
 * and writes every request, tool call (with its raw arguments) and tool result to {@code build/eval/probe-*.log}.
 *
 * <p>Opt in with {@code -Dfriendai.live=true -Dfriendai.live.config=<server config>} and the script in the
 * {@code FRIENDAI_PROBE_SCRIPT} environment variable: one player line per text line, {@code #} starts a comment and
 * {@code !event <text>} posts a world notice. Tools are validated against their real schemas but never touch a world:
 * a valid call answers with a canned success line.
 */
class LiveBrainProbe {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @EnabledIfSystemProperty(named = "friendai.live", matches = "true")
    void replayScript() throws Exception {
        String configPath = System.getProperty("friendai.live.config");
        String scriptPath = System.getenv("FRIENDAI_PROBE_SCRIPT");
        assertTrue(configPath != null && !configPath.isBlank(), "pass -Dfriendai.live.config=<server config>");
        assertTrue(scriptPath != null && !scriptPath.isBlank(), "set FRIENDAI_PROBE_SCRIPT to a script file");
        ServerConfig config =
                new Gson().fromJson(Files.readString(Path.of(configPath), StandardCharsets.UTF_8), ServerConfig.class);
        config.validate();
        HttpClient http = HttpClient.newHttpClient();
        HttpTransport transport =
                new HttpTransport(http, Duration.ofSeconds(config.brain.requestTimeoutSec), 2, Duration.ofMillis(500));
        LlmGateway live = new LlmClient(
                ProviderRegistry.withBuiltins(transport),
                config.toLlmProfile(System::getenv),
                new HttpStreamingTransport(http));

        FriendaiTools.Builtins builtins = FriendaiTools.build(() -> 8, MemoryAccess.DISABLED);
        Map<String, ToolSpec> byName = new LinkedHashMap<>();
        for (ToolSpec spec : builtins.registry().specs()) {
            byName.put(spec.name(), spec);
        }
        List<String> log = new ArrayList<>();
        AtomicInteger taskIds = new AtomicInteger(1);
        ToolExecutor tools = new ToolExecutor() {
            @Override
            public List<ToolSpec> specs(AccessLevel access) {
                return builtins.registry().specs(access);
            }

            @Override
            public ToolResult execute(ToolCall call, Caller caller) {
                ToolSpec spec = byName.get(call.name());
                if (spec == null) {
                    return ToolResult.error("Unknown tool: " + call.name());
                }
                if (call.arguments() == null) {
                    return ToolResult.error(
                            "Tool " + call.name() + " got invalid JSON arguments: " + call.rawArguments());
                }
                List<String> problems = ArgValidator.validate(spec.parametersSchema(), call.arguments());
                if (!problems.isEmpty()) {
                    return ToolResult.error(
                            "Invalid arguments for " + call.name() + ": " + String.join("; ", problems));
                }
                return ToolResult.ok("task #" + taskIds.incrementAndGet() + " started: " + call.name());
            }
        };

        Loop loop = new Loop();
        RecordingSpeaker speaker = new RecordingSpeaker();
        CompanionBrain brain = new CompanionBrain(
                () -> new Persona(config.companion.name, config.companion.description, config.companion.language),
                () -> new BrainSettings(config.brain.maxToolStepsPerTurn, config.brain.historyMessages, 0, 0),
                () -> live,
                tools,
                LiveBrainProbe::perception,
                speaker,
                loop,
                AccessResolver.of(OWNER, player -> false),
                MemorySource.NONE,
                builtins.catalog());
        brain.setAgentSettings(() -> AgentSettings.DEFAULTS);
        brain.setObserver(new BrainObserver() {
            @Override
            public void onRouteDecision(RouteDecision decision) {
                log.add("  route: " + decision.mode() + " groups="
                        + decision.groups().size() + " (" + decision.reason() + ")");
            }

            @Override
            public void onRequest(String system, List<Message> messages, List<ToolSpec> sent) {
                log.add("  request: system=" + system.length() + " chars, history=" + messages.size() + ", tools="
                        + sent.size() + " ["
                        + String.join(",", sent.stream().map(ToolSpec::name).toList()) + "]");
            }

            @Override
            public void onResponse(LlmResponse response) {
                log.add("  response: stop=" + response.stopReason() + " usage=" + response.usage());
            }

            @Override
            public void onAssistantText(String text) {
                log.add("  assistant: " + text);
            }

            @Override
            public void onToolCall(ToolCall call) {
                log.add("  CALL " + call.name() + " raw=" + call.rawArguments());
            }

            @Override
            public void onToolResult(ToolCall call, ToolResult result) {
                log.add("  RESULT " + call.name() + (result.error() ? " ERROR: " : " ok: ") + result.content());
            }

            @Override
            public void onError(Throwable error) {
                log.add("  ERROR " + error);
            }

            @Override
            public void onTurnEnd(long turn, String outcome) {
                log.add("  turn " + turn + " ended: " + outcome);
            }
        });

        for (String raw : Files.readAllLines(Path.of(scriptPath), StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            BrainEvent event = line.startsWith("!event ")
                    ? new BrainEvent.WorldNotice(line.substring("!event ".length()))
                    : new BrainEvent.PlayerSaid(OWNER, "Player", line, BrainEvent.Source.VOICE);
            log.add("");
            log.add(">>> " + line);
            int spokenBefore = speaker.said.size();
            loop.run(() -> brain.post(event));
            long deadline = System.currentTimeMillis() + 180_000;
            while (loop.call(brain::hasPendingWork) && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            for (String said : loop.call(() -> List.copyOf(speaker.said.subList(spokenBefore, speaker.said.size())))) {
                log.add("  SPOKEN: " + said);
            }
        }
        loop.shutdown();
        Path out = Path.of("build/eval");
        Files.createDirectories(out);
        Files.write(
                out.resolve("probe-"
                        + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".log"),
                log);
    }

    /** A badlands scene like the one the owner reported: dead bushes on red sand, the owner three blocks away. */
    private static String perception() {
        Map<String, Integer> blocks = new LinkedHashMap<>();
        blocks.put("red_sand", 96);
        blocks.put("terracotta", 40);
        blocks.put("dead_bush", 6);
        blocks.put("water", 12);
        return new PerceptionSnapshot(
                        "152 71 -40",
                        "overworld",
                        20f,
                        20f,
                        20,
                        "day",
                        "badlands",
                        "clear",
                        3.0,
                        blocks,
                        Map.of("player", 1),
                        Map.of(),
                        "Wearing: nothing",
                        "follow (running; no result yet)",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of())
                .toPromptText();
    }

    /** One thread owns the brain, as the server thread does in game. */
    private static final class Loop implements BrainScheduler {
        private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor();

        @Override
        public void execute(Runnable r) {
            thread.execute(r);
        }

        @Override
        public void schedule(Runnable r, long delayMs) {
            thread.schedule(r, delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        void run(Runnable r) throws Exception {
            thread.submit(r).get();
        }

        <T> T call(java.util.concurrent.Callable<T> c) throws Exception {
            return thread.submit(c).get();
        }

        void shutdown() {
            thread.shutdownNow();
        }
    }
}
