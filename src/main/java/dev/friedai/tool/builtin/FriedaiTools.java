package dev.friedai.tool.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/** The companion's skills as tools the LLM can call, grouped by domain. */
public final class FriedaiTools {
    private FriedaiTools() {}

    /** @param mineRadius search radius for {@code mine_blocks}, read on every call so config changes apply live */
    public static ToolRegistry<CompanionEntity> create(IntSupplier mineRadius) {
        ToolRegistry<CompanionEntity> registry = new ToolRegistry<>();
        List<TaskToolDef> defs = new ArrayList<>();
        MovementTools.register(registry, defs);
        GatheringTools.register(registry, defs, mineRadius);
        InventoryTools.register(registry, defs);
        InfoTools.register(registry, defs, mineRadius);
        ControlTools.register(registry, defs);
        return registry;
    }
}
