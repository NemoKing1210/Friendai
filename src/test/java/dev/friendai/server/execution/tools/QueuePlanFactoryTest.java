package dev.friendai.server.execution.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.task.TaskContext;
import dev.friendai.task.plan.PlanStepFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueuePlanFactoryTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test
    void everyPlannableToolHasAFactoryWhateverItsRequesterMayRun() {
        TaskToolDefinition wait = task("wait", true, AccessLevel.TRUSTED);
        TaskToolDefinition attackPlayer = task("attack_player", true, AccessLevel.OWNER);
        TaskToolDefinition hidden = task("hidden", false, AccessLevel.TRUSTED);

        Map<String, PlanStepFactory> factories = QueueTools.planFactories(
                QueuePlanFactoryTest::caller,
                Map.of(wait.name(), wait, attackPlayer.name(), attackPlayer, hidden.name(), hidden));

        assertEquals(
                Set.of(wait.name(), attackPlayer.name()),
                factories.keySet(),
                "a plan carries a factory for every tool an editor could add, not only for its requester's subset");
    }

    @Test
    void anEmittedActionIsStillAuthorizedAgainstItsOwnRequester() {
        TaskToolDefinition attackPlayer = task("attack_player", true, AccessLevel.OWNER);
        PlanStepFactory factory = QueueTools.planFactories(
                        QueuePlanFactoryTest::caller, Map.of(attackPlayer.name(), attackPlayer))
                .get(attackPlayer.name());

        assertTrue(
                factory.authorized(context(), step(attackPlayer.name(), OWNER)),
                "a factory covering every tool still runs only when the step's own requester may use it");
        assertFalse(
                factory.authorized(context(), step(attackPlayer.name(), FRIEND)),
                "covering the tool must not hand a trusted step the owner's authority");
    }

    private static Caller caller(dev.friendai.entity.CompanionEntity companion, UUID principal) {
        return new Caller(principal, "", principal.equals(OWNER) ? AccessLevel.OWNER : AccessLevel.TRUSTED);
    }

    private static TaskContext context() {
        return new TaskContext(null, null, null, null, null);
    }

    private static QueuedStep step(String tool, UUID principal) {
        return new QueuedStep(
                tool, new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of(), principal);
    }

    private static TaskToolDefinition task(String name, boolean plannable, AccessLevel minAccess) {
        return new TaskToolDefinition(
                name,
                name,
                new JsonObject(),
                (companion, args) -> null,
                null,
                plannable,
                minAccess,
                TaskToolDefinition.QueueTraits.DEFAULT,
                true,
                false,
                new ToolMeta(name, ToolGroup.CORE, false, "", List.of(), Cost.LOW));
    }
}
