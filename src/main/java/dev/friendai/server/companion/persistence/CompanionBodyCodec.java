package dev.friendai.server.companion.persistence;

import com.mojang.serialization.Codec;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.combat.CombatSettings;
import dev.friendai.features.navigation.movement.PaceMode;
import dev.friendai.features.survival.HungerModel;
import dev.friendai.server.execution.persistence.TaskPersistenceCoordinator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Encodes the persistent non-equipment body state shared by entity saves and death carry. */
public final class CompanionBodyCodec {
    private static final float STARTING_SATURATION = 5.0f;

    private CompanionBodyCodec() {}

    public static void write(CompanionEntity entity, ValueOutput output, TaskPersistenceCoordinator persistence) {
        entity.inventory().storeAsItemList(output.list("Inventory", ItemStack.CODEC));
        output.putString("CombatSettings", entity.combatSettings().encode());
        output.putString("PaceMode", entity.paceMode().label());
        output.putInt("Food", entity.hunger().food());
        output.putFloat("Saturation", entity.hunger().saturation());
        output.putFloat("Exhaustion", entity.hunger().exhaustion());
        output.putInt("XpPoints", entity.experience().points());
        if (persistence != null) {
            persistence
                    .capture(entity.executionCoordinator())
                    .ifPresent(saved -> output.store(
                            "SavedExecution", Codec.STRING.listOf(), persistence.split(persistence.encode(saved))));
        }
    }

    public static void read(CompanionEntity entity, ValueInput input, TaskPersistenceCoordinator persistence) {
        input.list("Inventory", ItemStack.CODEC).ifPresent(entity.inventory()::fromItemList);
        entity.setCombatSettings(input.getString("CombatSettings")
                .map(CombatSettings::decodeOrDefault)
                .orElseGet(CombatSettings::defaults));
        entity.setPaceMode(PaceMode.parse(input.getString("PaceMode").orElse(null)));
        entity.hunger()
                .load(
                        input.getInt("Food").orElse(HungerModel.MAX_FOOD),
                        input.getFloatOr("Saturation", STARTING_SATURATION),
                        input.getFloatOr("Exhaustion", 0.0f));
        entity.experience().load(input.getInt("XpPoints").orElse(0));
        entity.syncExperience();
        if (persistence != null) {
            input.read("SavedExecution", Codec.STRING.listOf())
                    .ifPresentOrElse(
                            parts -> persistence.restoreEncoded(entity, parts),
                            () -> persistence.reconcileFreeTimePlan(entity));
        }
    }
}
