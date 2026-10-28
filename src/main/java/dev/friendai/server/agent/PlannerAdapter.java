package dev.friendai.server.agent;

import dev.friendai.agent.plan.LlmPlanner;
import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.plan.Planner;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.debug.DebugService;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.server.configuration.ServerSettings;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Adapts the selected planner profile and keeps planner diagnostics on the server thread. */
public final class PlannerAdapter {
    private final ServerBrainScheduler scheduler;
    private final DebugService debug;
    private final AgentGatewayFactory gateways;
    private final Supplier<ServerConfig> config;
    private final Supplier<ServerSettings> settings;

    public PlannerAdapter(
            ServerBrainScheduler scheduler,
            DebugService debug,
            AgentGatewayFactory gateways,
            Supplier<ServerConfig> config,
            Supplier<ServerSettings> settings) {
        this.scheduler = scheduler;
        this.debug = debug;
        this.gateways = gateways;
        this.config = config;
        this.settings = settings;
    }

    public CompletableFuture<PlanProposal> propose(CompanionSession session, PlannerInput input) {
        return plannerFor(session).propose(input);
    }

    public String model() {
        return gateways.plannerModel();
    }

    private Planner plannerFor(CompanionSession session) {
        Planner override = session.plannerOverride();
        if (override != null) return override;

        String selectedProfile = String.valueOf(gateways.plannerProfileName());
        String selectedModel = String.valueOf(gateways.plannerModel());
        LlmGateway selectedGateway = gateways.planner(session);
        ServerConfig cfg = config.get();
        int attempts = Math.max(1, Math.min(4, cfg.agent.plannerRetries));
        return new LlmPlanner(
                () -> selectedGateway,
                gateways.redactor(),
                cfg.agent.plannerTimeoutSec,
                Math.min(12, settings.get().tasks().queueMaxStepsPerAdd()),
                8192,
                49152,
                attempts,
                event -> scheduler.execute(() -> {
                    String failure = event.failure() == null
                            ? ""
                            : " failure=" + event.failure().code() + ": "
                                    + gateways.redactor().apply(event.failure().detail());
                    String reply = gateways.redactor().apply(event.reply() == null ? "" : event.reply());
                    if (reply.length() > 1200) {
                        reply = reply.substring(0, 1200) + "…";
                    }
                    debug.logOf(session.owner())
                            .add(
                                    LogKind.PLANNER,
                                    "planner profile=" + gateways.redactor().apply(selectedProfile) + " model="
                                            + gateways.redactor().apply(selectedModel)
                                            + " attempt=" + event.attempt() + " disposition=" + event.disposition()
                                            + failure + " reply=" + reply);
                }));
    }
}
