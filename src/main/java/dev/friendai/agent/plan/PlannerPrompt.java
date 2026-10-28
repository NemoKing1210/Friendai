package dev.friendai.agent.plan;

import dev.friendai.agent.goal.Criterion;
import java.util.function.UnaryOperator;

/** The two prompts of a planner call. English, LLM-facing, never shown to the player. */
public final class PlannerPrompt {
    private PlannerPrompt() {}

    public static String system() {
        return "You plan work for a Minecraft companion. Answer with one JSON object and nothing else, no markdown.\n"
                + "Shape: {\"rationale\": \"one sentence\", \"expected_outcome\": \"what will be true when it works\", "
                + "\"steps\": [ {\"tool\": \"name\", \"args\": \"{\\\"key\\\":\\\"value\\\"}\", \"when\": [guard], "
                + "\"after\": [earlier step numbers], \"on_failure\": \"ask|skip|stop_plan\", \"max_attempts\": 1} ]}.\n"
                + "Rules:\n"
                + "- Use only the tools listed under Tools, and give every required argument. args is a JSON object written as a string.\n"
                + "- Keep the plan short: at most 12 steps, each one concrete and checkable. Prefer obtain_item over hand-written gathering chains.\n"
                + "- Stone and ores need the right pickaxe; obtain_item plans that tool chain. Never plan mine_blocks for a hard block before the companion has a suitable pickaxe.\n"
                + "- Put a guard in when for a condition the world can check (has_item, inventory_space, health_at_least, owner_within, block_within, dimension).\n"
                + "- Lessons list what already failed or was tried: do not repeat an approach that failed; choose a different one.\n"
                + "- If a failure is described, plan only the work that remains and fix its cause first.\n"
                + "- Remembered resource locations are historical evidence, not guaranteed supply.\n"
                + "- Never invent tools, items or coordinates that the inputs do not support.";
    }

    public static String system(PlannerInput input) {
        var protocol = new dev.friendai.skill.SkillPromptSection("planner-protocol",system(),Integer.MAX_VALUE);
        var context = new dev.friendai.agent.prompt.TurnContext(new dev.friendai.brain.Persona("","","auto"),"",null,"",
                dev.friendai.brain.TurnKind.EVENT,"",java.util.List.of(),"","","",input.instructions());
        return new dev.friendai.agent.prompt.PromptAssembler(java.util.List.of(protocol),input.promptBudget()).assemble(context);
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
        in.queueState().ifPresent(queue -> out.append("Current queue:\n").append(queue).append('\n'));
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
