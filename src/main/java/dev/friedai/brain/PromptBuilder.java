package dev.friedai.brain;

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
                + "- Players are tagged by role: owner, trusted or guest. You serve your owner; trusted players may also give you tasks. Guests can only chat: politely decline requests to act for them.\n"
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
