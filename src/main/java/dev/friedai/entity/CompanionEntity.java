package dev.friedai.entity;

import com.mojang.serialization.Codec;
import dev.friedai.task.Task;
import dev.friedai.task.TaskFactory;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskSettings;
import dev.friedai.task.combat.CombatMode;
import dev.friedai.task.cooking.FurnaceJobs;
import dev.friedai.task.interact.CompanionHands;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
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
    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    /** Ticks without damage before natural healing starts, and between each healed point. */
    private static final int REGEN_DELAY_TICKS = 200;
    private static final int REGEN_INTERVAL_TICKS = 200;

    private UUID ownerUuid;
    private final SimpleContainer inventory = new SimpleContainer(27);
    private final TaskRunner taskRunner =
            new TaskRunner(this, () -> TaskSettings.taskTimeoutSec, () -> TaskSettings.defaultFollow);
    private final FurnaceJobs furnaceJobs = new FurnaceJobs();
    private transient CompanionHands hands;
    private int furnaceWatchTicks;
    private CombatMode combatMode = CombatMode.DEFENSIVE;
    private int lastDamageTick;
    private int regenTicks;

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
    }

    public TaskRunner taskRunner() {
        return taskRunner;
    }

    /** The furnaces this companion loaded; not saved, the results stay in the world instead. */
    public FurnaceJobs furnaceJobs() {
        return furnaceJobs;
    }

    /** The fake player that performs placements and right-clicks. Not saved. */
    public CompanionHands hands() {
        if (hands == null) {
            hands = new CompanionHands(this);
        }
        return hands;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && isAlive()) {
            if (getHealth() < getMaxHealth()) {
                int sinceDamage = tickCount - lastDamageTick;
                int regenStart = REGEN_DELAY_TICKS + REGEN_INTERVAL_TICKS;
                if (sinceDamage >= regenStart && (sinceDamage - regenStart) % REGEN_INTERVAL_TICKS == 0) {
                    heal(1.0F);
                }
            }
            taskRunner.tick();
            if (++furnaceWatchTicks % 20 == 0) {
                furnaceJobs.tick(this);
            }
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) {
            lastDamageTick = tickCount;
        }
        return hurt;
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        super.setItemSlot(slot, stack);
        if (!stack.isEmpty()) {
            setGuaranteedDrop(slot);
        }
    }

    /** The armor, hands and combat mode, as one LLM-facing line. */
    public String equipmentText() {
        StringBuilder text = new StringBuilder("Wearing: ");
        boolean anyArmor = false;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = getItemBySlot(slot);
            if (!stack.isEmpty()) {
                if (anyArmor) {
                    text.append(", ");
                }
                text.append(itemName(stack));
                anyArmor = true;
            }
        }
        if (!anyArmor) {
            text.append("nothing");
        }
        text.append("; holding: ");
        ItemStack mainHand = getMainHandItem();
        text.append(mainHand.isEmpty() ? "nothing" : itemName(mainHand));
        ItemStack offHand = getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offHand.isEmpty()) {
            text.append(" (offhand: ").append(itemName(offHand)).append(')');
        }
        return text.append("; combat mode: ").append(combatMode.label()).toString();
    }

    private static String itemName(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.ATTACK_SPEED, 4.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public CombatMode combatMode() {
        return combatMode;
    }

    public void setCombatMode(CombatMode mode) {
        this.combatMode = mode == null ? CombatMode.DEFENSIVE : mode;
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

    /**
     * True when the companion may pick the item up: it is still there, and the companion did not throw it itself.
     * A drop the companion made is for the player, so it never takes it back, not even after the pickup delay expires.
     */
    public boolean canCollect(ItemEntity entity) {
        return !entity.isRemoved() && entity.getOwner() != this;
    }

    /** Moves dropped item stacks within {@code radius} into the inventory. Returns the number of items moved. */
    public int collectNearbyItems(double radius) {
        int moved = 0;
        List<ItemEntity> items = level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(radius));
        for (ItemEntity entity : items) {
            if (!canCollect(entity)) {
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
        output.putString("CombatMode", combatMode.label());
        taskRunner.current().map(Task::saveData).ifPresent(saved -> output.putString("SavedTask", saved));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read("Owner", UUID_CODEC).ifPresent(id -> this.ownerUuid = id);
        input.list("Inventory", ItemStack.CODEC).ifPresent(inventory::fromItemList);
        this.combatMode = CombatMode.parse(input.getString("CombatMode").orElse(null));
        if (level() instanceof ServerLevel) {
            input.getString("SavedTask").flatMap(TaskFactory::restore).ifPresent(taskRunner::restore);
        }
    }
}
