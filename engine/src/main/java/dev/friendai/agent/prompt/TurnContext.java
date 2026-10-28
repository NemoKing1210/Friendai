package dev.friendai.agent.prompt;

import dev.friendai.agent.turn.Persona;
import dev.friendai.agent.turn.TurnKind;
import dev.friendai.execution.tool.Caller;
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
        String placedBlock,
        String tradingSummary,
        List<PromptSection> instructionSections) {
    public TurnContext(
            Persona persona,
            String perception,
            Caller caller,
            String memoryBlock,
            TurnKind turn,
            String mission,
            List<String> toolHints,
            String places,
            String tradingSummary) {
        this(persona, perception, caller, memoryBlock, turn, mission, toolHints, places, "", tradingSummary, List.of());
    }

    public TurnContext(
            Persona persona,
            String perception,
            Caller caller,
            String memoryBlock,
            TurnKind turn,
            String mission,
            List<String> toolHints,
            String places,
            String placedBlock,
            String tradingSummary) {
        this(
                persona,
                perception,
                caller,
                memoryBlock,
                turn,
                mission,
                toolHints,
                places,
                placedBlock,
                tradingSummary,
                List.of());
    }

    public TurnContext(
            Persona persona,
            String perception,
            Caller caller,
            String memoryBlock,
            TurnKind turn,
            String mission,
            List<String> toolHints) {
        this(persona, perception, caller, memoryBlock, turn, mission, toolHints, "", "", "");
    }

    public TurnContext(
            Persona persona,
            String perception,
            Caller caller,
            String memoryBlock,
            TurnKind turn,
            String mission,
            List<String> toolHints,
            String places) {
        this(persona, perception, caller, memoryBlock, turn, mission, toolHints, places, "", "");
    }

    public TurnContext {
        instructionSections = instructionSections == null ? List.of() : List.copyOf(instructionSections);
        memoryBlock = memoryBlock == null ? "" : memoryBlock;
        mission = mission == null ? "" : mission;
        toolHints = toolHints == null ? List.of() : List.copyOf(toolHints);
        places = places == null ? "" : places;
        placedBlock = placedBlock == null ? "" : placedBlock;
        tradingSummary = tradingSummary == null ? "" : tradingSummary;
    }
}
