package dev.friendai.features.construction.minecraft.tool;

import dev.friendai.features.construction.minecraft.task.BuildTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.Objects;

/** Construction skills: placement, site preparation, blueprints and special structures. */
public final class ConstructionTools implements FeatureToolContributor {
    private final BuildTask.ObtainFactory obtainFactory;

    public ConstructionTools(BuildTask.ObtainFactory obtainFactory) {
        this.obtainFactory = Objects.requireNonNull(obtainFactory);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        BuildingTools.register(registry);
        BuildTools.register(registry, obtainFactory);
        StructureTools.register(registry, obtainFactory);
    }
}
