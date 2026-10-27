package dev.friendai.agent.prompt;

import dev.friendai.brain.Persona;
import dev.friendai.brain.TurnKind;
import dev.friendai.tool.Caller;
import java.util.List;

/** Everything a prompt section may read for one request. */
public record TurnContext(
        Persona persona,
        String perception,
        Caller caller,
        String memoryBlock,
        TurnKind turn,
        String mission,
        List<String> toolHints,
        String places,
        String tradingSummary) {
    public TurnContext(Persona persona, String perception, Caller caller, String memoryBlock, TurnKind turn,
                       String mission, List<String> toolHints) {
        this(persona, perception, caller, memoryBlock, turn, mission, toolHints, "", "");
    }

    public TurnContext(Persona persona, String perception, Caller caller, String memoryBlock, TurnKind turn,
                       String mission, List<String> toolHints, String places) {
        this(persona, perception, caller, memoryBlock, turn, mission, toolHints, places, "");
    }

    public TurnContext {
        memoryBlock = memoryBlock == null ? "" : memoryBlock;
        mission = mission == null ? "" : mission;
        toolHints = toolHints == null ? List.of() : List.copyOf(toolHints);
        places = places == null ? "" : places;
        tradingSummary = tradingSummary == null ? "" : tradingSummary;
    }
}
