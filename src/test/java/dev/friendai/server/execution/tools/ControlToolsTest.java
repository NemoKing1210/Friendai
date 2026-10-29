package dev.friendai.server.execution.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.features.memory.CompanionProfile;
import dev.friendai.features.memory.CompanionProfileRepository;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ControlToolsTest {
    private static final Set<String> CONTROL_TOOLS = Set.of("show_inventory", "set_name", "set_follow_mode", "stop");

    @Test
    void registersLegacyControlsWithTheirExistingAccessAndCatalogMetadata() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("control tools do not register tasks");
        });

        new ControlTools(new CompanionProfileRepository() {
                    @Override
                    public CompanionProfile profile(UUID owner) {
                        return CompanionProfile.EMPTY;
                    }

                    @Override
                    public void save(UUID owner, CompanionProfile profile) {}

                    @Override
                    public long revision(UUID owner) {
                        return 0;
                    }
                })
                .register(contributions);

        assertEquals(CONTROL_TOOLS, names(tools.specs()));
        assertEquals(CONTROL_TOOLS, names(tools.specs(AccessLevel.OWNER)));
        assertEquals(Set.of("stop"), names(tools.specs(AccessLevel.TRUSTED)));
        assertEquals(Set.of(), names(tools.specs(AccessLevel.GUEST)));
        assertEquals(
                CONTROL_TOOLS,
                contributions.catalogMetadata().stream()
                        .map(metadata -> metadata.name())
                        .collect(Collectors.toSet()));
        contributions
                .catalogMetadata()
                .forEach(metadata -> assertEquals(
                        ToolCatalog.defaults().meta(metadata.name()).orElseThrow(), metadata));
    }

    private static Set<String> names(java.util.List<dev.friendai.agent.llm.model.ToolSpec> specs) {
        return specs.stream().map(spec -> spec.name()).collect(Collectors.toSet());
    }
}
