package dev.friendai.agent.plan;

import dev.friendai.agent.goal.Criterion;
import java.util.function.UnaryOperator;

/** The two prompts of a planner call. English, LLM-facing, never shown to the player. */
public final class PlannerPrompt {
    private PlannerPrompt() {}

    public static String system() {
        return "You plan work for a Minecraft companion. Answer with one JSON object and nothing else, no markdown.\n"
                + "Shape: {\"rationale\": \"one sentence\", \"expected_outcome\": \"what will be true when it works\", \"steps\": [plan nodes]}.\n"
                + "Rules:\n"
                + "- A plan node is an action {tool,args}, a conditional {when: condition, then: [nodes], else: [nodes]}, or a repeat {repeat: {steps: [nodes], until: condition, max_iterations: 1..64}}. Omit else for an empty branch.\n"
                + "- Use only the tools listed under Tools, and give every required argument. args may be a JSON object or a JSON object string.\n"
                + "- Conditions support all_of/any_of with 1..8 conditions, not, existing guards, and block_in_front, biome, fluid_ahead, distance_travelled, time_elapsed. Nesting is limited to 8.\n"
                + "- Keep the worst-case action count within the supplied queue limit; normalized plans are limited to 49152 characters and each args object to 8192 characters. Every repeat needs a cap.\n"
                + "- Keep the plan short and concrete. Prefer obtain_item over hand-written gathering chains.\n"
                + "- Stone and ores need the right pickaxe; obtain_item plans that tool chain. Never plan mine_blocks for a hard block before the companion has a suitable pickaxe.\n"
                + "- Use a conditional for a world-checkable branch and repeat/until for bounded retry work; the server chooses branches deterministically.\n"
                + "- Lessons list what already failed or was tried: do not repeat an approach that failed; choose a different one.\n"
                + "- If a failure is described, plan only the work that remains and fix its cause first.\n"
                + "- Remembered resource locations are historical evidence, not guaranteed supply.\n"
                + "- Never invent tools, items or coordinates that the inputs do not support.";
    }

    public static String system(PlannerInput input) {
        var protocol =
                new dev.friendai.agent.prompt.SkillPromptSection("planner-protocol", system(), Integer.MAX_VALUE);
        var context = new dev.friendai.agent.prompt.TurnContext(
                new dev.friendai.agent.turn.Persona("", "", "auto"),
                "",
                null,
                "",
                dev.friendai.agent.turn.TurnKind.EVENT,
                "",
                java.util.List.of(),
                "",
                "",
                "",
                input.instructions());
        return new dev.friendai.agent.prompt.PromptAssembler(java.util.List.of(protocol), input.promptBudget())
                .assemble(context);
    }

    /** @param redact removes configured secrets from the text before it leaves the server */
    public static String user(PlannerInput in, UnaryOperator<String> redact) {
        StringBuilder out = new StringBuilder("Goal: ").append(in.goal().text()).append('\n');
        if (!in.goal().criteria().isEmpty()) {
            out.append("Done when:\n");
            for (Criterion c : in.goal().criteria()) {
                out.append("- ").append(c.text()).append('\n');
            }
        }
        out.append("World:\n").append(in.world()).append('\n');
        if (!in.knowledge().isBlank()) {
            out.append("Known places and resources:\n").append(in.knowledge()).append('\n');
        }
        in.queueState()
                .ifPresent(queue -> out.append("Current queue:\n").append(queue).append('\n'));
        if (!in.lessons().isEmpty()) {
            out.append("Lessons so far:\n");
            in.lessons().forEach(l -> out.append("- ").append(l).append('\n'));
        }
        if (!in.failure().isBlank()) {
            out.append("What just failed:\n").append(in.failure()).append('\n');
        }
        out.append("Tools:\n").append(in.toolDigest());
        return redact.apply(out.toString());
    }
}
