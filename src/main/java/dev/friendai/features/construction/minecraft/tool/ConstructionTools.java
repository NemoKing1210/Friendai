package dev.friendai.features.construction.minecraft.tool;

import dev.friendai.features.construction.minecraft.task.BuildTask;
import dev.friendai.features.placements.minecraft.PlacedService;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.Objects;

/** Construction skills: placement, site preparation, blueprints and special structures. */
public final class ConstructionTools implements FeatureToolContributor {
    private final BuildTask.ObtainFactory obtainFactory;
    private final PlacedService placed;

    public ConstructionTools(BuildTask.ObtainFactory obtainFactory) {
        this(obtainFactory, PlacedService.unavailable());
    }

    public ConstructionTools(BuildTask.ObtainFactory obtainFactory, PlacedService placed) {
        this.obtainFactory = Objects.requireNonNull(obtainFactory);
        this.placed = Objects.requireNonNull(placed);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        BuildingTools.register(registry);
        BuildTools.register(registry, obtainFactory, placed);
        StructureTools.register(registry, obtainFactory);
    }
}
