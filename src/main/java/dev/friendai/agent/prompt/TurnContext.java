package dev.friendai.agent.prompt;

import dev.friendai.brain.Persona;
import dev.friendai.brain.TurnKind;
import dev.friendai.tool.Caller;
import java.util.List;

/**
 * Everything a prompt section may read for one request.
 *
 * @param mission the rendered goal block, empty when there is no goal
 * @param toolHints the "when to use" lines of the tools visible this turn
 */
public record TurnContext(
        Persona persona,
        String perception,
        Caller caller,
        String memoryBlock,
        TurnKind turn,
        String mission,
        List<String> toolHints) {
    public TurnContext {
        memoryBlock = memoryBlock == null ? "" : memoryBlock;
        mission = mission == null ? "" : mission;
        toolHints = toolHints == null ? List.of() : List.copyOf(toolHints);
    }
}
