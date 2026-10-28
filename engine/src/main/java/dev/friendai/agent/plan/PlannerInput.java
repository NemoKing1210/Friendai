package dev.friendai.agent.plan;

import com.google.gson.JsonObject;
import dev.friendai.agent.goal.Goal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything one planner call may see. All fields are plain data built by the caller on the server thread.
 *
 * @param world the companion's surroundings and inventory, as in the prompt's state block
 * @param toolDigest the plannable tools with their schemas, already restricted to what the requester may use
 * @param knowledge what the companion remembers about resources and places, possibly empty
 * @param queueState the current queue, when a replan has to work around it
 * @param failure what went wrong last time, with facts and diagnosis; empty for a first plan
 * @param schemas the schemas of the tools the requester may plan with; a proposal may use no other tool
 * @param allTools every plannable tool name, so an unknown name is reported as unknown
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
        List<dev.friendai.agent.prompt.PromptSection> instructions,
        int promptBudget) {
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
        this(goal, world, toolDigest, knowledge, queueState, lessons, failure, schemas, allTools, List.of(), 0);
    }

    public PlannerInput withInstructions(List<dev.friendai.agent.prompt.PromptSection> sections, int budget) {
        return new PlannerInput(
                goal, world, toolDigest, knowledge, queueState, lessons, failure, schemas, allTools, sections, budget);
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
