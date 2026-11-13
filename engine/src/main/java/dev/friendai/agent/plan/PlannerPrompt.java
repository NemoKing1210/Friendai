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
                + "- Enum values are closed: copy an exact allowed value and casing from that tool's schema; never invent synonyms. For equip_item, slot is mainhand, offhand, head, chest, legs or feet. For obtain_item, scope is local or expedition. For craft_item, station is only the optional keep-or-take policy for a station it places itself, not a station type.\n"
                + "- Conditions use a type field. Example: {\"type\":\"not\",\"condition\":{\"type\":\"has_item\",\"item\":\"minecraft:oak_log\",\"count\":16}}. all_of/any_of use {\"type\":\"all_of\",\"conditions\":[condition,...]} with 1..8 children.\n"
                + "- Other existing conditions include inventory_space, health_at_least, owner_within, block_within, dimension, block_in_front, biome, fluid_ahead, distance_travelled and time_elapsed. Nesting is limited to 8.\n"
                + "- Keep the worst-case action count within the supplied queue limit; normalized plans are limited to 49152 characters and each args object to 8192 characters. Every repeat needs a cap.\n"
                + "- Cover the whole objective in one plan. Each step is one tool call, so use a composite tool once (obtain_items, mine_blocks with a count, craft_item with a count) instead of one step per item. Do not split one materials run across steps.\n"
                + "- For an owner order with several finished items, use one obtain_items action for the batch; set each count to the owner share plus the number the companion must retain. Then queue one give_items action per exact item for the authenticated owner, with count, retain and strict=true.\n"
                + "- Goal criteria cover each requested item, quantity, recipient and retained count. A handover drop is not proof that the owner picked it up.\n"
                + "- Prefer obtain_item over hand-written gathering chains.\n"
                + "- For a standing tree, plan one fell_tree with the tree's log type; never plan mine_blocks or mine_block_at per log.\n"
                + "- fell_tree near is only me or place:<remembered name>; omit it to search around the companion. Never use a block or item id for near.\n"
                + "- Stone and ores need the right pickaxe; obtain_item plans that tool chain. Never plan mine_blocks for a hard block before the companion has a suitable pickaxe.\n"
                + "- Use a conditional for a world-checkable branch and repeat/until for bounded retry work; the server chooses branches deterministically.\n"
                + "- Lessons list what already failed or was tried: do not repeat an approach that failed; choose a different one.\n"
                + "- When an action needs a carried item, state it with a has_item condition or guard instead of inventing a gathering chain; a missing guarded item can be repaired once.\n"
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
        if (!in.pendingNodePath().isBlank()) {
            out.append("Pending action: ").append(in.pendingNodePath()).append('\n');
        }
        if (!in.goal().criteria().isEmpty()) {
            out.append("Done when:\n");
            for (Criterion c : in.goal().criteria()) {
                out.append("- ").append(c.text()).append('\n');
            }
        }
        out.append("World:\n").append(in.world()).append('\n');
        if (!in.verified().isEmpty()) {
            out.append("Already verified by earlier tasks:\n");
            for (int i = 0; i < Math.min(8, in.verified().size()); i++) {
                out.append("- ").append(in.verified().get(i)).append('\n');
            }
        }
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
