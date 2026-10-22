package dev.friedai.entity;

import com.mojang.serialization.Codec;
import dev.friedai.task.Task;
import dev.friedai.task.TaskFactory;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskSettings;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** The player's AI companion: a player-looking mob that never despawns and carries its own inventory. */
public class CompanionEntity extends PathfinderMob {
    private static final Codec<UUID> UUID_CODEC = UUIDUtil.CODEC;

    private UUID ownerUuid;
    private final SimpleContainer inventory = new SimpleContainer(27);
    private final TaskRunner taskRunner =
            new TaskRunner(this, () -> TaskSettings.taskTimeoutSec, () -> TaskSettings.defaultFollow);

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
    }

    public TaskRunner taskRunner() {
        return taskRunner;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && isAlive()) {
            taskRunner.tick();
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public void setOwner(UUID owner) {
        this.ownerUuid = owner;
    }

    public Optional<ServerPlayer> owner() {
        if (ownerUuid == null || !(level() instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        return Optional.ofNullable(serverLevel.getServer().getPlayerList().getPlayer(ownerUuid));
    }

    public SimpleContainer inventory() {
        return inventory;
    }

    /** Moves dropped item stacks within {@code radius} into the inventory. Returns the number of items moved. */
    public int collectNearbyItems(double radius) {
        int moved = 0;
        List<ItemEntity> items = level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(radius));
        for (ItemEntity entity : items) {
            if (entity.isRemoved()) {
                continue;
            }
            ItemStack stack = entity.getItem().copy();
            int before = stack.getCount();
            ItemStack leftover = inventory.addItem(stack);
            moved += before - leftover.getCount();
            if (leftover.isEmpty()) {
                entity.discard();
            } else {
                entity.setItem(leftover);
            }
        }
        return moved;
    }

    public void dropInventory() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                spawnAtLocation(serverLevel, stack.copy());
            }
        }
        inventory.clearContent();
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
        super.dropCustomDeathLoot(level, source, killedByPlayer);
        dropInventory();
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide()) {
            owner().ifPresent(p -> p.sendSystemMessage(Component.translatable("friedai.companion.died", getName())));
        }
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean removeWhenFarAway(double distanceSqr) {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (ownerUuid != null) {
            output.store("Owner", UUID_CODEC, ownerUuid);
        }
        inventory.storeAsItemList(output.list("Inventory", ItemStack.CODEC));
        taskRunner.current().map(Task::saveData).ifPresent(saved -> output.putString("SavedTask", saved));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read("Owner", UUID_CODEC).ifPresent(id -> this.ownerUuid = id);
        input.list("Inventory", ItemStack.CODEC).ifPresent(inventory::fromItemList);
        if (level() instanceof ServerLevel) {
            input.getString("SavedTask").flatMap(TaskFactory::restore).ifPresent(taskRunner::restore);
        }
    }
}
