package dev.friendai.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.FriendaiMod;
import dev.friendai.agent.autonomy.AutonomyFacts;
import dev.friendai.agent.autonomy.AutonomySettings;
import dev.friendai.agent.autonomy.AwaySummary;
import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.autonomy.IdleStartPolicy;
import dev.friendai.agent.autonomy.Need;
import dev.friendai.agent.autonomy.NeedKind;
import dev.friendai.agent.autonomy.ReflexPlans;
import dev.friendai.agent.autonomy.ReflexStep;
import dev.friendai.agent.autonomy.Shift;
import dev.friendai.agent.autonomy.ShiftBudget;
import dev.friendai.agent.autonomy.ShiftInputs;
import dev.friendai.agent.autonomy.ShiftPolicy;
import dev.friendai.brain.AgentSettings.Autonomy;
import dev.friendai.config.ServerConfig;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.queue.JobQueue;
import dev.friendai.text.UserMessage;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import dev.friendai.tool.builtin.TaskToolDef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;

/**
 * Runs free time on the server thread: starts it after an idle spell, queues reflexes and fallbacks, keeps the
 * shift in step with its queue plan, and ends it on stop. Ticked once a second from {@link BrainService}.
 */
public final class FreeTimeDriver {
    /** Tools free time never uses, whatever the director or a template asks for. */
    public static final Set<String> DENIED = Set.of(
            "attack_player", "trust_player", "untrust_player", "forget", "set_name", "allow_food", "forbid_food",
            "reset_food_rule", "set_combat_settings", "set_combat_mode", "set_goal", "update_goal", "finish_goal",
            "start_free_time", "end_free_time", "choose_activity");
    /** About one night: how long a shelter reflex stays home. */
    static final int SHELTER_MINUTES = 8;
    private static final Pattern PLAN_ID = Pattern.compile("plan #(\\d+)");

    private final MinecraftServer server;
    private final Supplier<ServerConfig> config;
    private final Map<String, TaskToolDef> taskTools;
    private final AutonomyFactsBuilder facts = new AutonomyFactsBuilder();
    private final Map<UUID, ShiftBudget> budgets = new HashMap<>();
    private final Map<UUID, Long> lastOwnerActivity = new HashMap<>();
    private final Map<UUID, Boolean> wasOnline = new HashMap<>();
    private final Set<UUID> seenOnline = new java.util.HashSet<>();
    /** Free-time plans cancelled on purpose; their end event is swallowed once. */
    private final Set<Long> released = new java.util.HashSet<>();

    FreeTimeDriver(MinecraftServer server, Supplier<ServerConfig> config, Map<String, TaskToolDef> taskTools) {
        this.server = server;
        this.config = config;
        this.taskTools = taskTools;
    }

    private FreeTimeService service() {
        return FriendaiServerState.get(server).freeTime();
    }

    public ShiftBudget budget(UUID owner) {
        return budgets.computeIfAbsent(owner, id -> new ShiftBudget());
    }

    /** The owner spoke or gave an order: new shifts wait for a quiet spell, and a spent backoff may try again. */
    public void ownerActivity(UUID owner, long nowMillis) {
        lastOwnerActivity.put(owner, nowMillis);
        budget(owner).ownerSpoke();
    }

    public FreeTime state(UUID owner) {
        return service().state(owner);
    }

    /** Turns free time on. A command start does not wait for the quiet spell the order itself began. */
    public FreeTime start(UUID owner, FreeTime.Origin origin, String wish) {
        long now = now();
        if (origin == FreeTime.Origin.COMMAND) {
            lastOwnerActivity.put(owner, now - config.get().autonomy.resumeAfterSec * 1000L);
        }
        facts.invalidate(owner);
        return service().start(owner, origin, wish, now);
    }

    /** Ends free time and cancels only free time's own plan. */
    public void stop(UUID owner, String reason) {
        FreeTime state = service().state(owner);
        if (!state.on()) {
            return;
        }
        Shift shift = state.shift();
        service().stop(owner, reason, now());
        CompanionManager.get(server).find(owner).ifPresent(companion -> {
            if (shift != null) {
                release(companion, shift.planId(), reason);
            }
        });
    }

    /** Cancels a free-time plan on purpose, so its end is not reported as news. */
    private void release(CompanionEntity companion, long planId, String reason) {
        if (planId == 0) {
            return;
        }
        companion.taskRunner().unmarkAutonomousPlan(planId);
        if (companion.taskRunner().queue().plan(planId).isPresent()) {
            released.add(planId);
            companion.taskRunner().cancelPlan(planId, reason);
        }
    }

    private boolean ownsPlan(UUID owner, long planId) {
        Shift shift = service().state(owner).shift();
        return planId != 0 && shift != null && shift.planId() == planId;
    }

    /**
     * A queue plan ended or got stuck. True when it was free time's: the shift is closed here and the model is not
     * told; false for any other plan.
     */
    public boolean onPlanEnded(UUID owner, CompanionEntity companion, long planId, boolean success, String summary) {
        if (released.remove(planId)) {
            return true;
        }
        if (!ownsPlan(owner, planId)) {
            return false;
        }
        if (companion.taskRunner().queue().plan(planId).isPresent()
                && companion.taskRunner().queue().planStatus(planId) != dev.friendai.queue.PlanStatus.ACTIVE) {
            // a stuck plan: free time does not wait for a decision, it gives up on the shift
            released.add(planId);
            companion.taskRunner().cancelPlan(planId, "free time gave up");
        }
        companion.taskRunner().unmarkAutonomousPlan(planId);
        long now = now();
        Shift shift = service().state(owner).shift();
        service().finishShift(owner, success, summary, now);
        facts.invalidate(owner);
        if (!success) {
            AutonomySettings s = config.get().autonomy.toSettings();
            service().cooldown(owner, shift.need(), now + s.failCooldownMin() * 60_000L);
            if (summary != null && summary.contains("storage is full")) {
                tell(owner, companion, UserMessage.of("friendai.freetime.storage_full"));
            }
        }
        return true;
    }

    /** One owner's free time, once a second. */
    void tick(UUID owner, CompanionEntity companion, boolean brainThinking, boolean ownerGoalActive, long nowMillis) {
        ServerConfig cfg = config.get();
        AutonomySettings s = cfg.autonomy.toSettings();
        Autonomy ceiling = Autonomy.parse(cfg.agent.autonomy);
        FreeTimeService service = service();
        FreeTime state = service.state(owner);
        var runner = companion.taskRunner();
        boolean online = companion.owner().isPresent();
        long lastOwner = lastOwnerActivity.computeIfAbsent(owner, id -> nowMillis);
        boolean combat = runner.paused() || runner.suspended() && online || companion.isSleeping()
                || companion.getTarget() != null;
        Boolean before = wasOnline.put(owner, online);
        if (Boolean.FALSE.equals(before) && online) {
            onOwnerBack(owner, companion);
        }
        if (!state.on()) {
            // An idle start means the owner left the companion without work: they must have been here this run.
            if (wasOnlineThisRun(owner, online) && IdleStartPolicy.shouldStart(state, s, ceiling, ownerGoalActive, !runner.hasWork(), combat, online,
                    nowMillis, lastOwner, service.lastEndMillis(owner))) {
                service.start(owner, FreeTime.Origin.IDLE, "", nowMillis);
                tell(owner, companion, UserMessage.of("friendai.freetime.idle_start"));
            }
            return;
        }
        long shiftPlan = state.shift() == null ? 0 : state.shift().planId();
        boolean ownerWork = runner.busyWithOther() || runner.queue().jobs().stream()
                .anyMatch(job -> !JobQueue.terminal(job) && (shiftPlan == 0 || job.planId() != shiftPlan));
        if (!online && s.whileOffline() && runner.suspended() && !ownerWork) {
            runner.resume(); // free time may work while the owner is away; owner work stays suspended
        }
        if (shiftPlan != 0) {
            runner.markAutonomousPlan(shiftPlan); // after a restart the runner forgot its marks
        }
        AutonomyFacts f = facts.build(companion, owner, nowMillis);
        boolean planExists = shiftPlan != 0 && runner.queue().plan(shiftPlan).isPresent();
        ShiftInputs in = new ShiftInputs(state, f, s, ceiling, ownerGoalActive, planExists,
                planningInFlight(owner), ownerWork, brainThinking, lastOwner);
        switch (ShiftPolicy.decide(in, budget(owner))) {
            case ShiftPolicy.CloseStale ignored -> service.finishShift(owner, false, "plan is gone", nowMillis);
            case ShiftPolicy.Reflex reflex -> {
                if (state.shift() != null) {
                    interrupt(owner, companion, state.shift(), reflex.need(), nowMillis);
                }
                queueTemplate(owner, companion, reflex.need(), ReflexPlans.plan(reflex.need(), f, s), s, nowMillis);
            }
            case ShiftPolicy.Fallback fallback ->
                    queueTemplate(owner, companion, fallback.need(), ReflexPlans.fallback(fallback.need(), f), s,
                            nowMillis);
            case ShiftPolicy.Director ignored -> askDirector(owner, companion, f, nowMillis);
            case ShiftPolicy.None ignored -> {}
            case ShiftPolicy.WaitBudget ignored -> {}
        }
    }

    private boolean wasOnlineThisRun(UUID owner, boolean online) {
        if (online) {
            seenOnline.add(owner);
        }
        return seenOnline.contains(owner);
    }

    /** A director request for this owner has not answered yet. */
    boolean planningInFlight(UUID owner) {
        return false;
    }

    void askDirector(UUID owner, CompanionEntity companion, AutonomyFacts f, long nowMillis) {}

    private void interrupt(UUID owner, CompanionEntity companion, Shift shift, Need need, long nowMillis) {
        service().finishShift(owner, false, "interrupted by " + need.kind().name().toLowerCase(java.util.Locale.ROOT),
                nowMillis);
        release(companion, shift.planId(), "interrupted by free time");
    }

    private void queueTemplate(UUID owner, CompanionEntity companion, Need need, Optional<List<ReflexStep>> steps,
                               AutonomySettings s, long nowMillis) {
        if (steps.isEmpty()) {
            service().cooldown(owner, need.kind(), nowMillis + s.failCooldownMin() * 60_000L);
            return;
        }
        boolean shelter = need.kind() == NeedKind.SHELTER || need.kind() == NeedKind.SURVIVE;
        int minutes = shelter ? SHELTER_MINUTES : s.shiftMinutes();
        Optional<Long> plan = queue(owner, companion, "free time: " + need.text(), steps.get(), minutes, taskTools);
        if (plan.isEmpty()) {
            service().cooldown(owner, need.kind(), nowMillis + s.failCooldownMin() * 60_000L);
            return;
        }
        service().beginShift(owner, need.text(), List.of(), "", need.kind(), true, nowMillis,
                nowMillis + minutes * 60_000L);
        service().linkPlan(owner, plan.get());
        facts.invalidate(owner);
    }

    /** Queues a free-time plan with the owner's authority; empty when the plan was refused. */
    Optional<Long> queue(UUID owner, CompanionEntity companion, String goal, List<ReflexStep> steps, int minutes,
                         Map<String, TaskToolDef> tools) {
        JsonObject args = new JsonObject();
        args.addProperty("goal", goal.length() > 200 ? goal.substring(0, 200) : goal);
        JsonArray array = new JsonArray();
        for (ReflexStep step : steps) {
            JsonObject o = new JsonObject();
            o.addProperty("tool", step.tool());
            o.addProperty("args", step.args().toString());
            array.add(o);
        }
        args.add("steps", array);
        args.addProperty("priority", -10);
        args.addProperty("on_failure", "stop_plan");
        args.addProperty("time_limit_min", minutes);
        ToolResult result = FriendaiTools.addPlan(companion, ownerCaller(owner), args, tools, false, DENIED);
        Matcher matcher = PLAN_ID.matcher(result.content());
        if (result.error() || !matcher.find()) {
            FriendaiMod.LOGGER.debug("Free-time plan refused: {}", result.content());
            return Optional.empty();
        }
        long planId = Long.parseLong(matcher.group(1));
        companion.taskRunner().markAutonomousPlan(planId);
        return Optional.of(planId);
    }

    static Caller ownerCaller(UUID owner) {
        return new Caller(owner, "", AccessLevel.OWNER);
    }

    /** The owner joined or came back online: say what happened while they were away. */
    void onOwnerBack(UUID owner, CompanionEntity companion) {
        AwaySummary.Taken taken = AwaySummary.take(service().state(owner));
        if (taken.text().isBlank()) {
            return;
        }
        service().update(owner, s -> taken.state());
        tell(owner, companion, UserMessage.of("friendai.freetime.away", taken.text()));
    }

    /** A fixed line to the owner when they can hear it, otherwise saved for when they come back. */
    void tell(UUID owner, CompanionEntity companion, UserMessage message) {
        var player = companion.owner();
        if (player.isPresent()) {
            player.get().sendSystemMessage(net.minecraft.network.chat.Component.literal("<")
                    .append(dev.friendai.chat.CompanionNames.styled(companion.getName().getString()))
                    .append(net.minecraft.network.chat.Component.literal("> "))
                    .append(Components.of(message)));
        } else {
            service().update(owner, s -> AwaySummary.add(s, Components.of(message).getString()));
        }
    }

    /** Wall-clock time: cooldowns and the idle spell are saved and must mean the same after a restart. */
    static long now() {
        return System.currentTimeMillis();
    }
}
