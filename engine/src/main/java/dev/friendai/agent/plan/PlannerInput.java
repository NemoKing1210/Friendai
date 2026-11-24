package dev.friendai.agent.plan;

import com.google.gson.JsonObject;
import dev.friendai.agent.goal.Goal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Everything one planner call may see. The data fields are built by the caller on the server thread; the normalizers
 * are the one part that runs later, inside the planner's completion callback off that thread.
 *
 * @param world the companion's surroundings and inventory, as in the prompt's state block
 * @param toolDigest the plannable tools with their schemas, already restricted to what the requester may use
 * @param knowledge what the companion remembers about resources and places, possibly empty
 * @param queueState the current queue, when a replan has to work around it
 * @param failure what went wrong last time, with facts and diagnosis; empty for a first plan
 * @param schemas the schemas of the tools the requester may plan with; a proposal may use no other tool
 * @param allTools every plannable tool name the requester may use before this occasion's scoping, so a real tool
 *     outside the scope is reported as known but unusable while only a genuinely unknown name is reported as unknown
 * @param normalizers the documented argument aliases of those tools, keyed by tool name; a missing name means none.
 *     A normalizer executes in the planner's completion callback, off the server thread, so it must be pure and
 *     thread-safe.
 * @param dialogue the delivered lines of the conversation with this requester, oldest first, each prefixed with its
 *     speaker, so a follow-up such as "the second one" can be resolved; never a tool call or a tool result
 * @param remembered the durable-memory block this requester may read, as the reply prompt builds it; empty when
 *     memory is off, nothing is relevant, or the caller may see nothing
 */
public record PlannerInput(
        Goal goal,
        String world,
        String toolDigest,
        String knowledge,
        Optional<String> queueState,
        List<String> lessons,
        String failure,
        Map<String, JsonObject> schemas,
        Set<String> allTools,
        List<String> verified,
        List<dev.friendai.agent.prompt.PromptSection> instructions,
        int promptBudget,
        long targetPlanId,
        String targetFingerprint,
        String pendingNodePath,
        Map<String, UnaryOperator<JsonObject>> normalizers,
        List<String> dialogue,
        String remembered) {
    /** The shape without dialogue or memory evidence, preserving callers added before them. */
    public PlannerInput(
            Goal goal,
            String world,
            String toolDigest,
            String knowledge,
            Optional<String> queueState,
            List<String> lessons,
            String failure,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            List<String> verified,
            List<dev.friendai.agent.prompt.PromptSection> instructions,
            int promptBudget,
            long targetPlanId,
            String targetFingerprint,
            String pendingNodePath,
            Map<String, UnaryOperator<JsonObject>> normalizers) {
        this(
                goal,
                world,
                toolDigest,
                knowledge,
                queueState,
                lessons,
                failure,
                schemas,
                allTools,
                verified,
                instructions,
                promptBudget,
                targetPlanId,
                targetFingerprint,
                pendingNodePath,
                normalizers,
                List.of(),
                "");
    }

    /** The shape without per-tool argument aliases, preserving callers added before them. */
    public PlannerInput(
            Goal goal,
            String world,
            String toolDigest,
            String knowledge,
            Optional<String> queueState,
            List<String> lessons,
            String failure,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            List<String> verified,
            List<dev.friendai.agent.prompt.PromptSection> instructions,
            int promptBudget,
            long targetPlanId,
            String targetFingerprint,
            String pendingNodePath) {
        this(
                goal,
                world,
                toolDigest,
                knowledge,
                queueState,
                lessons,
                failure,
                schemas,
                allTools,
                verified,
                instructions,
                promptBudget,
                targetPlanId,
                targetFingerprint,
                pendingNodePath,
                Map.of());
    }

    /** The target-plan shape without a pending path, preserving callers added before it. */
    public PlannerInput(
            Goal goal,
            String world,
            String toolDigest,
            String knowledge,
            Optional<String> queueState,
            List<String> lessons,
            String failure,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            List<String> verified,
            List<dev.friendai.agent.prompt.PromptSection> instructions,
            int promptBudget,
            long targetPlanId,
            String targetFingerprint) {
        this(
                goal,
                world,
                toolDigest,
                knowledge,
                queueState,
                lessons,
                failure,
                schemas,
                allTools,
                verified,
                instructions,
                promptBudget,
                targetPlanId,
                targetFingerprint,
                "");
    }

    /** The pre-target-plan shape, with no arbitrary-plan identity. */
    public PlannerInput(
            Goal goal,
            String world,
            String toolDigest,
            String knowledge,
            Optional<String> queueState,
            List<String> lessons,
            String failure,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            List<String> verified,
            List<dev.friendai.agent.prompt.PromptSection> instructions,
            int promptBudget) {
        this(
                goal,
                world,
                toolDigest,
                knowledge,
                queueState,
                lessons,
                failure,
                schemas,
                allTools,
                verified,
                instructions,
                promptBudget,
                0,
                "",
                "");
    }

    public PlannerInput(
            Goal goal,
            String world,
            String toolDigest,
            String knowledge,
            Optional<String> queueState,
            List<String> lessons,
            String failure,
            Map<String, JsonObject> schemas,
            Set<String> allTools) {
        this(
                goal,
                world,
                toolDigest,
                knowledge,
                queueState,
                lessons,
                failure,
                schemas,
                allTools,
                List.of(),
                List.of(),
                0,
                0,
                "");
    }

    public PlannerInput(
            Goal goal,
            String world,
            String toolDigest,
            String knowledge,
            Optional<String> queueState,
            List<String> lessons,
            String failure,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            List<String> verified) {
        this(
                goal,
                world,
                toolDigest,
                knowledge,
                queueState,
                lessons,
                failure,
                schemas,
                allTools,
                verified,
                List.of(),
                0,
                0,
                "");
    }

    public PlannerInput withInstructions(List<dev.friendai.agent.prompt.PromptSection> sections, int budget) {
        return new PlannerInput(
                goal,
                world,
                toolDigest,
                knowledge,
                queueState,
                lessons,
                failure,
                schemas,
                allTools,
                verified,
                sections,
                budget,
                targetPlanId,
                targetFingerprint,
                pendingNodePath,
                normalizers,
                dialogue,
                remembered);
    }

    public PlannerInput {
        instructions = instructions == null ? List.of() : List.copyOf(instructions);
        world = world == null ? "" : world;
        toolDigest = toolDigest == null ? "" : toolDigest;
        knowledge = knowledge == null ? "" : knowledge;
        queueState = queueState == null ? Optional.empty() : queueState;
        lessons = lessons == null ? List.of() : List.copyOf(lessons);
        failure = failure == null ? "" : failure;
        schemas = schemas == null ? Map.of() : Map.copyOf(schemas);
        allTools = allTools == null ? Set.of() : Set.copyOf(allTools);
        verified = verified == null ? List.of() : List.copyOf(verified);
        normalizers = normalizers == null ? Map.of() : Map.copyOf(normalizers);
        dialogue = dialogue == null ? List.of() : List.copyOf(dialogue);
        remembered = remembered == null ? "" : remembered;
    }

    /** The same input with the conversation and durable-memory blocks the planner may ground a reference in. */
    public PlannerInput withEvidence(List<String> dialogue, String remembered) {
        return new PlannerInput(
                goal,
                world,
                toolDigest,
                knowledge,
                queueState,
                lessons,
                failure,
                schemas,
                allTools,
                verified,
                instructions,
                promptBudget,
                targetPlanId,
                targetFingerprint,
                pendingNodePath,
                normalizers,
                dialogue,
                remembered);
    }

    /** A prompt-only input, for tests and callers that never parse a reply. */
    public static PlannerInput promptOnly(
            Goal goal,
            String world,
            String toolDigest,
            String knowledge,
            Optional<String> queueState,
            List<String> lessons,
            String failure) {
        return new PlannerInput(goal, world, toolDigest, knowledge, queueState, lessons, failure, Map.of(), Set.of());
    }
}
