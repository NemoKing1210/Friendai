package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/** The companion's skills as tools the LLM can call, grouped by domain. */
public final class FriendaiTools {
    /**
     * A built registry plus the tools {@code run_plan} may use as steps, by name. The plan tools keep their
     * factories, so a saved plan can be rebuilt after a reload.
     */
    public record Builtins(ToolRegistry<CompanionEntity> registry, Map<String, TaskToolDef> plannable) {}

    private FriendaiTools() {}

    public static ToolRegistry<CompanionEntity> create(IntSupplier mineRadius) {
        return create(mineRadius, MemoryAccess.DISABLED);
    }

    /**
     * @param mineRadius search radius for {@code mine_blocks}, read on every call so config changes apply live
     * @param memory long-term memory; {@link MemoryAccess#DISABLED} leaves the memory tools out
     */
    public static ToolRegistry<CompanionEntity> create(IntSupplier mineRadius, MemoryAccess memory) {
        return build(mineRadius, memory).registry();
    }

    /** Builds the registry and the plan-step lookup together. */
    public static Builtins build(IntSupplier mineRadius, MemoryAccess memory) {
        ToolRegistry<CompanionEntity> registry = new ToolRegistry<>();
        List<TaskToolDef> defs = new ArrayList<>();
        MovementTools.register(registry, defs);
        GatheringTools.register(registry, defs, mineRadius);
        InventoryTools.register(registry, defs);
        BuildingTools.register(registry, defs);
        StorageTools.register(registry, defs);
        CraftingTools.register(registry, defs);
        CookingTools.register(registry, defs);
        CombatTools.register(registry, defs);
        FarmingTools.register(registry, defs);
        AnimalTools.register(registry, defs);
        ObtainTools.register(registry, defs);
        BuildTools.register(registry, defs);
        InfoTools.register(registry, defs, mineRadius);
        MemoryTools.register(registry, memory);
        TrustTools.register(registry);
        ControlTools.register(registry, defs);
        Map<String, TaskToolDef> plannable = new LinkedHashMap<>();
        for (TaskToolDef def : defs) {
            if (def.plannable()) {
                plannable.put(def.name(), def);
            }
        }
        return new Builtins(registry, Map.copyOf(plannable));
    }
}
