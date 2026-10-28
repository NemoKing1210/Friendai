package dev.friendai.brain;

import dev.friendai.agent.goal.GoalView;
import dev.friendai.agent.prompt.MissionText;
import dev.friendai.agent.prompt.PromptAssembler;
import dev.friendai.agent.prompt.TurnContext;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.tool.Caller;
import java.util.EnumSet;
import java.util.List;

/** The system prompt entry point; the text itself lives in {@code agent.prompt} sections. */
public final class PromptBuilder {
    /** Guidance for every tool, used whenever the caller does not say which tools are visible. */
    private static final List<String> ALL_TOOL_HINTS =
            ToolCatalog.defaults().hints(EnumSet.allOf(ToolGroup.class));

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
        return system(p, perception, caller, memoryBlock, turn, goal, budget, ALL_TOOL_HINTS);
    }

    /** @param toolHints the guidance lines of the tools visible this turn */
    public static String system(
            Persona p, String perception, Caller caller, String memoryBlock, TurnKind turn, GoalView goal,
            int budget, List<String> toolHints) {
        return system(p, perception, caller, memoryBlock, turn, goal, budget, toolHints, "");
    }

    /** @param places the rendered places block, or {@code ""} when the caller sees none */
    public static String system(
            Persona p, String perception, Caller caller, String memoryBlock, TurnKind turn, GoalView goal,
            int budget, List<String> toolHints, String places) {
        return system(p, perception, caller, memoryBlock, turn, goal, budget, toolHints, places, "", "");
    }

    /** Compatibility overload for an owner-private trading context without a planner note. */
    public static String system(
            Persona p, String perception, Caller caller, String memoryBlock, TurnKind turn, GoalView goal,
            int budget, List<String> toolHints, String places, String tradingSummary) {
        return system(p, perception, caller, memoryBlock, turn, goal, budget, toolHints, places, "", tradingSummary);
    }

    /** @param plannerNote what the planner just did for this turn, shown once in the Mission block */
    public static String system(
            Persona p, String perception, Caller caller, String memoryBlock, TurnKind turn, GoalView goal,
            int budget, List<String> toolHints, String places, String plannerNote, String tradingSummary) {
        return system(p,perception,caller,memoryBlock,turn,goal,budget,toolHints,places,plannerNote,tradingSummary,List.of());
    }

    public static String system(Persona p,String perception,Caller caller,String memoryBlock,TurnKind turn,GoalView goal,
            int budget,List<String> toolHints,String places,String plannerNote,String tradingSummary,
            List<dev.friendai.agent.prompt.PromptSection> instructions) {
        return system(p,perception,caller,memoryBlock,turn,goal,budget,toolHints,places,plannerNote,tradingSummary,instructions,a -> {});
    }

    public static String system(Persona p,String perception,Caller caller,String memoryBlock,TurnKind turn,GoalView goal,
            int budget,List<String> toolHints,String places,String plannerNote,String tradingSummary,
            List<dev.friendai.agent.prompt.PromptSection> instructions,
            java.util.function.Consumer<PromptAssembler.Assembly> diagnostics) {
        return system(p, perception, caller, memoryBlock, turn, goal, budget, toolHints, places, "", plannerNote,
                tradingSummary, instructions, diagnostics);
    }

    /** @param placedBlock the rendered blocks-the-companion-placed block, or {@code ""} when the caller sees none */
    public static String system(Persona p,String perception,Caller caller,String memoryBlock,TurnKind turn,GoalView goal,
            int budget,List<String> toolHints,String places,String placedBlock,String plannerNote,String tradingSummary,
            List<dev.friendai.agent.prompt.PromptSection> instructions,
            java.util.function.Consumer<PromptAssembler.Assembly> diagnostics) {
        var result = PromptAssembler.defaults(budget).assembleDetailed(new TurnContext(
                p, perception, caller, memoryBlock, turn, MissionText.render(goal, plannerNote), toolHints,
                places, placedBlock, tradingSummary, instructions));
        diagnostics.accept(result);
        return result.text();
    }
}
