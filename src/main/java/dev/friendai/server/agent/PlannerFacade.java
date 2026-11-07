package dev.friendai.server.agent;

import com.google.gson.JsonObject;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.agent.turn.PlannerPort;
import dev.friendai.debug.DebugService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.persistence.places.PlaceStore;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.agent.planner.PlannerInputFactory;
import dev.friendai.server.execution.tools.QueueToolAccess;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.server.instructions.SkillService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/**
 * Planner consultation for one server: builds the input for an occasion, asks the owner's planner (or its test
 * override) and gives the brain the port it queues a proposal through. All calls are made on the server thread
 * except the model request itself, which completes on the gateway's thread.
 */
final class PlannerFacade {
    private final MinecraftServer server;
    private final Supplier<ServerConfig> config;
    private final SkillService skills;
    private final PlannerAdapter planners;
    private final DebugService debug;
    private final Supplier<PerceptionContext> perceptionContext;
    private final Map<String, TaskToolDefinition> taskTools;
    private final Map<String, TaskToolDefinition> planTools;
    private final PlaceStore places;
    private final GoalService goals;
    private final dev.friendai.agent.autonomy.FreeTimeService freeTimeService;
    private final ToolRegistry<CompanionEntity> tools;
    private final Function<UUID, CompanionSession> sessionFor;
    private final BiFunction<CompanionEntity, UUID, Caller> callerFor;
    private final QueueToolAccess queueAccess;

    PlannerFacade(
            MinecraftServer server,
            Supplier<ServerConfig> config,
            SkillService skills,
            PlannerAdapter planners,
            DebugService debug,
            Supplier<PerceptionContext> perceptionContext,
            Map<String, TaskToolDefinition> taskTools,
            Map<String, TaskToolDefinition> planTools,
            PlaceStore places,
            GoalService goals,
            dev.friendai.agent.autonomy.FreeTimeService freeTimeService,
            ToolRegistry<CompanionEntity> tools,
            Function<UUID, CompanionSession> sessionFor,
            BiFunction<CompanionEntity, UUID, Caller> callerFor,
            QueueToolAccess queueAccess) {
        this.server = server;
        this.config = config;
        this.skills = skills;
        this.planners = planners;
        this.debug = debug;
        this.perceptionContext = perceptionContext;
        this.taskTools = taskTools;
        this.planTools = planTools;
        this.places = places;
        this.goals = goals;
        this.freeTimeService = freeTimeService;
        this.tools = tools;
        this.sessionFor = sessionFor;
        this.callerFor = callerFor;
        this.queueAccess = queueAccess;
    }

    /** One planner input builder for this owner's live companion; both the brain and free time ask it. */
    PlannerInputFactory inputs(UUID owner, CompanionEntity companion) {
        CompanionSession session = sessionFor.apply(owner);
        return new PlannerInputFactory(
                companion,
                perceptionContext,
                taskTools,
                planTools,
                () -> places.book(owner).all(),
                () -> goals.board(owner),
                config,
                session::verifiedFacts,
                () -> freeTimeService);
    }

    /** Asks the owner's planner (or its test override) for a plan; may complete on any thread. */
    CompletableFuture<PlanProposal> propose(UUID owner, PlannerInput input) {
        return propose(owner, input, false, () -> false);
    }

    CompletableFuture<PlanProposal> propose(UUID owner, PlannerInput input, boolean autonomous) {
        return propose(owner, input, autonomous, () -> false);
    }

    CompletableFuture<PlanProposal> propose(UUID owner, PlannerInput input, BooleanSupplier cancelled) {
        return propose(owner, input, false, cancelled);
    }

    CompletableFuture<PlanProposal> propose(
            UUID owner, PlannerInput input, boolean autonomous, BooleanSupplier cancelled) {
        var companion = CompanionManager.get(server).find(owner);
        String task =
                companion.map(c -> c.executionCoordinator().currentToolName()).orElse("");
        boolean combat = companion
                .map(c -> c.combatController().state()
                        != dev.friendai.features.combat.minecraft.CombatController.State.IDLE)
                .orElse(false);
        var skillSession = skills.begin(
                owner,
                new Caller(owner, "", AccessLevel.OWNER),
                input.goal().text(),
                dev.friendai.agent.turn.TurnKind.EVENT,
                task,
                autonomous,
                true,
                combat);
        return planners.propose(
                sessionFor.apply(owner),
                input.withInstructions(skillSession.sections(), config.get().agent.promptBudget),
                cancelled);
    }

    /** Test hook: use this planner instead of the model-backed one for one owner. */
    void setOverride(UUID owner, dev.friendai.agent.plan.Planner planner) {
        sessionFor.apply(owner).plannerOverride(planner);
    }

    /** The effective model used for plan drafting, after unknown or unusable profiles fall back. */
    String model() {
        return planners.model();
    }

    /** Adds an operator-only planner lifecycle entry. Call on the server thread. */
    void debugLog(UUID owner, String text) {
        debug.logOf(owner).add(LogKind.PLANNER, text);
    }

    /** The port one brain consults: it owns what the plan is, asks for a proposal and applies the answer. */
    PlannerPort portFor(UUID owner, CompanionEntity companion, CompanionSession session) {
        return new GamePlannerPort(owner, companion, session);
    }

    /** Lets one companion's brain consult the planner and queue what it proposes. */
    private final class GamePlannerPort implements PlannerPort {
        private final UUID owner;
        private final CompanionEntity companion;
        /** The owner's session, whose runtime replan budget planless plans spend. */
        private final CompanionSession session;

        private final PlannerInputFactory factory;

        GamePlannerPort(UUID owner, CompanionEntity companion, CompanionSession session) {
            this.owner = owner;
            this.companion = companion;
            this.session = session;
            this.factory = inputs(owner, companion);
        }

        /**
         * A plan is owned when it is queued for this companion and its principal still holds every step's access.
         * A plan whose owner was revoked is cancelled at activation instead of being replanned.
         */
        @Override
        public boolean ownsPlan(long planId) {
            var queue = companion.executionCoordinator().queue();
            if (queue.plan(planId).isEmpty()) return false;
            List<dev.friendai.execution.queue.Job> jobs = queue.jobsOfPlan(planId);
            UUID principal = dev.friendai.agent.plan.PlanAuthority.principal(jobs, owner);
            Caller resolved = callerFor.apply(companion, principal);
            return dev.friendai.agent.plan.PlanAuthority.holds(jobs, resolved, this::requiredAccess);
        }

        private dev.friendai.execution.tool.AccessLevel requiredAccess(String tool) {
            var definition = planTools.get(tool);
            if (definition == null) definition = taskTools.get(tool);
            return definition == null ? null : definition.minAccess();
        }

        @Override
        public Optional<PlannerInput> inputFor(
                dev.friendai.agent.plan.PlanSubject subject, Caller caller, String failure) {
            if (subject instanceof dev.friendai.agent.plan.PlanSubject.TroubledPlan trouble) {
                // Preflight the targeted frame with the same rule the patch itself enforces: a proposal that could not
                // be applied must not cost a provider call or a replan attempt. The failure is still reported to the
                // model, which is where the owner's recovery line comes from.
                Optional<String> refusal = dev.friendai.execution.queue.PlanPatchPolicy.rejectPlan(
                        companion.executionCoordinator().queue(), trouble.planId());
                if (refusal.isPresent()) {
                    debugLog(owner, "planner skipped: " + refusal.get());
                    return Optional.empty();
                }
                // A plan that follows a goal spends the goal's own replan budget; a planless one spends a
                // runtime-only counter, so a plan cannot replan forever without a goal record to stop it.
                if (!hasLinkedGoal(trouble.planId())) {
                    if (session.planReplans().exhausted(trouble.planId(), config.get().agent.maxReplans)) {
                        return Optional.empty();
                    }
                    session.planReplans().attempt(trouble.planId());
                }
            }
            return factory.build(subject, consultCaller(subject, caller), failure);
        }

        /**
         * Who one consultation runs as. A troubled plan runs as its own principal, resolved from its jobs right now, so
         * a guest's failed plan is replanned with that guest's tools and never with the owner's; every other subject
         * runs as the caller the turn already fixed.
         */
        private Caller consultCaller(dev.friendai.agent.plan.PlanSubject subject, Caller turnCaller) {
            List<dev.friendai.execution.queue.Job> jobs =
                    subject instanceof dev.friendai.agent.plan.PlanSubject.TroubledPlan trouble
                            ? companion.executionCoordinator().queue().jobsOfPlan(trouble.planId())
                            : List.of();
            UUID principal = dev.friendai.agent.plan.PlanAuthority.consultAs(subject, jobs, owner, turnCaller.uuid());
            return callerFor.apply(companion, principal);
        }

        private boolean hasLinkedGoal(long planId) {
            return goals.board(owner).all().stream().anyMatch(goal -> goal.planId() == planId);
        }

        @Override
        public CompletableFuture<PlanProposal> propose(PlannerInput input) {
            return propose(input, () -> false);
        }

        @Override
        public CompletableFuture<PlanProposal> propose(PlannerInput input, BooleanSupplier cancelled) {
            return PlannerFacade.this
                    .propose(owner, input, cancelled)
                    .thenApply(proposal -> new PlanProposal(
                            proposal.plan(),
                            proposal.stepsJson(),
                            proposal.rationale(),
                            proposal.expectedOutcome(),
                            input.targetPlanId(),
                            input.targetFingerprint()));
        }

        @Override
        public ToolResult apply(Caller caller, dev.friendai.agent.plan.PlanSubject subject, PlanProposal proposal) {
            if (proposal.targetPlanId() > 0 && !proposal.targetFingerprint().isBlank()) {
                String current = dev.friendai.execution.queue.PlanFingerprint.of(
                        companion.executionCoordinator().queue(), proposal.targetPlanId());
                if (dev.friendai.agent.plan.PlannerStaleCheck.stale(proposal.targetFingerprint(), current)) {
                    return ToolResult.error("the plan changed since the proposal; re-plan it");
                }
            }
            if (proposal.targetPlanId() > 0) {
                long planId = proposal.targetPlanId();
                // The plan is repaired as its own principal: a guest's plan stays the guest's work.
                Caller actor = consultCaller(subject, caller);
                var plan = companion.executionCoordinator().queue().plan(planId).orElse(null);
                if (plan == null) return ToolResult.error("plan #" + planId + " is no longer queued");
                // Access or the tool set can change between propose and apply; re-check the whole plan now, so a
                // proposal whose steps the principal can no longer run is refused instead of half-applied.
                if (!ownsPlan(planId)) {
                    return ToolResult.error("access was revoked after the proposal");
                }
                if (plan.definition() != null
                        && companion
                                .executionCoordinator()
                                .queue()
                                .jobsOfPlan(planId)
                                .isEmpty()) {
                    var patch = dev.friendai.execution.queue.PlanPatch.apply(
                            companion.executionCoordinator().queue(),
                            planId,
                            proposal.targetFingerprint(),
                            List.of(),
                            0,
                            plan.definition().nodes().size(),
                            proposal.plan().nodes());
                    debugLog(
                            owner,
                            patch.ok() ? "planner disposition=tree patched" : "planner disposition=tree patch refused");
                    return patch.ok() ? ToolResult.ok(patch.text()) : ToolResult.error(patch.text());
                }
                var pending = companion.executionCoordinator().queue().jobsOfPlan(planId).stream()
                        .filter(job -> job.state() == dev.friendai.execution.queue.JobState.PENDING)
                        .findFirst()
                        .orElse(null);
                if (pending == null) return ToolResult.error("plan #" + planId + " has no pending step to repair");
                JsonObject edit = new JsonObject();
                edit.addProperty("action", "replace");
                edit.addProperty("job", pending.id());
                edit.addProperty("expected", proposal.targetFingerprint());
                edit.add("steps", proposal.stepsJson());
                ToolResult result = tools.bind(companion)
                        .execute(new ToolCall("planner", "queue_edit", edit, edit.toString(), null), actor);
                debugLog(owner, result.error() ? "planner disposition=patch refused" : "planner disposition=patched");
                return result;
            }
            // A subject without a target plan never edits a goal's plan: it queues a new one beside it. The plan
            // stays off the goal board, so a plan cannot manufacture a verified goal; the model links one itself.
            var board = goals.board(owner);
            String goalText = objectiveText(subject, board);
            if (goalText == null) {
                return ToolResult.error("this planner subject cannot be queued");
            }
            JsonObject add = new JsonObject();
            add.addProperty("goal", goalText);
            // Preserve the normalized tree; queue_add and plan_validate share the same parser and tool allowlist.
            add.add("steps", proposal.stepsJson());
            ToolResult result = dev.friendai.server.execution.tools.QueueTools.addPlan(
                    queueAccess, companion, caller, add, planTools, false, java.util.Set.of());
            debugLog(owner, result.error() ? "planner disposition=queued refused" : "planner disposition=queued");
            return result;
        }

        /** The bounded goal text of a new plan, or null for a subject only a shift may queue. */
        private String objectiveText(
                dev.friendai.agent.plan.PlanSubject subject, dev.friendai.agent.goal.GoalBoard board) {
            if (subject instanceof dev.friendai.agent.plan.PlanSubject.OwnerOrder order) {
                return dev.friendai.agent.plan.PlannerObjective.forOrder(order.text())
                        .text();
            }
            if (subject instanceof dev.friendai.agent.plan.PlanSubject.ModelGoal goal) {
                return goalText(board, goal.goalId());
            }
            if (subject instanceof dev.friendai.agent.plan.PlanSubject.ActiveGoal goal) {
                return goalText(board, goal.goalId());
            }
            return null;
        }

        private String goalText(dev.friendai.agent.goal.GoalBoard board, long goalId) {
            var found = goalId > 0 ? board.find(goalId) : board.primary();
            return found.filter(goal -> goal.status() == dev.friendai.agent.goal.GoalStatus.ACTIVE)
                    .map(dev.friendai.agent.goal.Goal::text)
                    .orElse(null);
        }
    }
}
