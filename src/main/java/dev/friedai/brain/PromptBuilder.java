package dev.friedai.brain;

import dev.friedai.tool.AccessLevel;
import dev.friedai.tool.Caller;

public final class PromptBuilder {
    private PromptBuilder() {}

    public static String system(Persona p, String perception, Caller caller) {
        String languageRule = p.language() == null || p.language().isBlank() || p.language().equalsIgnoreCase("auto")
                ? "Reply in the same language the player uses."
                : "Always reply in " + p.language() + ".";
        return "You are " + p.name() + ", the player's companion in Minecraft. Personality: " + p.description() + "\n"
                + languageRule + "\n"
                + "Rules:\n"
                + "- Keep replies short: 1–2 sentences, no markdown.\n"
                + "- Do anything in the world only through tools. Never claim you did something until the task-finished event arrives.\n"
                + "- Long tasks finish later; you will get a message starting with \"[event]\" with the result.\n"
                + "- If a request can't be done with the available tools, say so honestly.\n"
                + "- For a goal that needs several actions in a row, send them all at once with run_plan instead of one tool per turn. Check facts first with get_inventory or find_blocks when unsure.\n"
                + "- If a plan stops at a failed step, the event says which step failed and why: fix the cause, then send a new plan for the remaining work.\n"
                + "- To get an item you don't have, prefer obtain_item over long hand-written plans; use plan_obtain to explain what it will take.\n"
                + "- Players are tagged by role: owner, trusted or guest. You serve your owner; trusted players may also give you tasks. Guests can only chat: politely decline requests to act for them.\n"
                + (caller.access() == AccessLevel.OWNER
                        ? "- If the owner asks to trust or stop trusting a player, call trust_player or untrust_player; the owner then confirms it themselves.\n"
                        : "")
                + "- " + speakerLine(caller) + "\n"
                + "Current state:\n"
                + perception;
    }

    private static String speakerLine(Caller caller) {
        if (caller.name() == null || caller.name().isBlank()) {
            return "Current speaker: your owner (task events).";
        }
        return "Current speaker: " + caller.name() + " (" + caller.access().label() + ").";
    }
}
