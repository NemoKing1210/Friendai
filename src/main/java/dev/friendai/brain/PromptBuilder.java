package dev.friendai.brain;

import dev.friendai.agent.goal.GoalView;
import dev.friendai.agent.prompt.MissionText;
import dev.friendai.agent.prompt.PromptAssembler;
import dev.friendai.agent.prompt.TurnContext;
import dev.friendai.tool.Caller;
import java.util.List;

/** The system prompt entry point; the text itself lives in {@code agent.prompt} sections. */
public final class PromptBuilder {
    private PromptBuilder() {}

    public static String system(Persona p, String perception, Caller caller) {
        return system(p, perception, caller, "", TurnKind.PLAYER);
    }

    public static String system(Persona p, String perception, Caller caller, TurnKind turn) {
        return system(p, perception, caller, "", turn);
    }

    public static String system(Persona p, String perception, Caller caller, String memoryBlock) {
        return system(p, perception, caller, memoryBlock, TurnKind.PLAYER);
    }

    public static String system(
            Persona p, String perception, Caller caller, String memoryBlock, TurnKind turn) {
        return system(p, perception, caller, memoryBlock, turn, GoalView.EMPTY);
    }

    public static String system(
            Persona p, String perception, Caller caller, String memoryBlock, TurnKind turn, GoalView goal) {
        return system(p, perception, caller, memoryBlock, turn, goal, 0);
    }

    /** @param budget character budget of the prompt; zero or less means unlimited */
    public static String system(
            Persona p, String perception, Caller caller, String memoryBlock, TurnKind turn, GoalView goal,
            int budget) {
        return PromptAssembler.defaults(budget).assemble(new TurnContext(
                p, perception, caller, memoryBlock, turn, MissionText.render(goal), List.of()));
    }
}
