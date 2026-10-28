package dev.friendai.agent.turn;

import dev.friendai.agent.prompt.PromptSection;
import dev.friendai.execution.tool.ToolResult;
import java.util.List;

/** Turn-scoped instruction view supplied by the game-side instructions adapter. */
public interface SkillTurnSession {
    SkillTurnSession NONE = new SkillTurnSession() {
        @Override
        public List<PromptSection> sections() {
            return List.of();
        }

        @Override
        public ToolResult request(String id) {
            return ToolResult.error("Skill access denied.");
        }

        @Override
        public List<String> versionedIds(List<String> sectionIds) {
            return List.copyOf(sectionIds);
        }

        @Override
        public dev.friendai.agent.turn.Persona persona(dev.friendai.agent.turn.Persona base) {
            return base;
        }

        @Override
        public String ownerAddress() {
            return "";
        }

        @Override
        public void recordAssembly(List<String> keptSectionIds) {}
    };

    List<PromptSection> sections();

    ToolResult request(String id);

    List<String> versionedIds(List<String> sectionIds);

    Persona persona(Persona base);

    String ownerAddress();

    void recordAssembly(List<String> keptSectionIds);
}
