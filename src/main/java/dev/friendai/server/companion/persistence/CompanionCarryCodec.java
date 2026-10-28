package dev.friendai.server.companion.persistence;

import com.mojang.serialization.Codec;
import dev.friendai.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Encodes the extra equipment captured for death respawn. */
public final class CompanionCarryCodec {
    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD,
        EquipmentSlot.MAINHAND,
        EquipmentSlot.OFFHAND
    };
    private static final Codec<List<ItemStack>> EQUIPMENT = ItemStack.OPTIONAL_CODEC.listOf();

    private CompanionCarryCodec() {}

    public static void write(CompanionEntity entity, ValueOutput output) {
        CompanionBodyCodec.write(entity, output);
        List<ItemStack> equipment = new ArrayList<>(SLOTS.length);
        for (EquipmentSlot slot : SLOTS) equipment.add(entity.getItemBySlot(slot));
        output.store("CarryEquipment", EQUIPMENT, equipment);
    }

    public static void read(CompanionEntity entity, ValueInput input) {
        CompanionBodyCodec.read(entity, input);
        input.read("CarryEquipment", EQUIPMENT).ifPresent(equipment -> {
            for (int i = 0; i < SLOTS.length && i < equipment.size(); i++)
                entity.setItemSlot(SLOTS[i], equipment.get(i));
        });
    }
}
