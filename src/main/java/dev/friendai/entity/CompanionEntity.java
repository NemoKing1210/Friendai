package dev.friendai.entity;

import com.mojang.serialization.Codec;
import dev.friendai.build.Blueprint;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.crafting.Station;
import dev.friendai.crafting.StationLease;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.movement.PaceMode;
import dev.friendai.survival.Difficulty;
import dev.friendai.survival.HungerModel;
import dev.friendai.task.Task;
import dev.friendai.task.TaskFactory;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.build.PreviewOutline;
import dev.friendai.task.builtin.CollectStationTask;
import dev.friendai.task.builtin.FollowTask;
import dev.friendai.task.combat.CombatController;
import dev.friendai.task.combat.CombatMode;
import dev.friendai.task.cooking.FurnaceJobs;
import dev.friendai.task.crafting.Stations;
import dev.friendai.task.interact.CompanionHands;
import dev.friendai.task.nav.Pace;
import dev.friendai.task.plan.PlanSave;
import dev.friendai.task.survival.HungerWatcher;
import dev.friendai.tool.InventoryAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/** The player's AI companion: a player-looking mob that never despawns and carries its own inventory. */
public class CompanionEntity extends PathfinderMob {
    private static final Codec<UUID> UUID_CODEC = UUIDUtil.CODEC;
    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    /** Vanilla's starting saturation, used when an old save has no hunger fields. */
    private static final float STARTING_SATURATION = 5.0f;
    /** Vanilla exhaustion per block broken, per attack, per hit taken and per jump. */
    public static final float EXHAUSTION_BLOCK = 0.005f;
    public static final float EXHAUSTION_ATTACK = 0.1f;
    private static final float EXHAUSTION_DAMAGE = 0.1f;
    private static final float EXHAUSTION_JUMP = 0.05f;
    private static final float EXHAUSTION_SWIM_PER_METER = 0.01f;
    /** How often, and how far, the companion takes items the owner threw: "here, take this". */
    private static final int OWNER_PICKUP_INTERVAL = 10;
    private static final double OWNER_PICKUP_RADIUS = 3.0;

    /** How often the companion looks for stations it left out, and how far it will walk back for one. */
    private static final int STATION_WATCH_INTERVAL = 20;
    private static final double STATION_RECOVERY_RADIUS = 32.0;

    private UUID ownerUuid;
    private final SimpleContainer inventory = new SimpleContainer(27);
    private final HungerModel hunger = new HungerModel();
    private final TaskRunner taskRunner =
            new TaskRunner(this, () -> TaskSettings.taskTimeoutSec, () -> TaskSettings.defaultFollow);
    private final CombatController combatController = new CombatController(this);
    private final FurnaceJobs furnaceJobs = new FurnaceJobs();
    private final HungerWatcher hungerWatcher = new HungerWatcher(
            this, HungerWatcher.DEFAULT_AUTO_DELAY_TICKS, HungerWatcher.DEFAULT_COOL_DOWN_TICKS);
    private final StationLease stationLease = new StationLease();
    private transient CompanionHands hands;
    private transient Blueprint lastPreview;
    private transient PreviewOutline previewOutline;
    private transient AABB pathBounds;
    /** Ticks left in which the path executor is known to be driving the companion. Not saved. */
    private transient int pathingTicks;
    private int furnaceWatchTicks;
    private int hungerWatchTicks;
    private int stationWatchTicks;
    private int ownerPickupTicks;
    /** How many players have the companion's inventory window open; the runner stands still while it is above zero. */
    private int inventoryViewers;
    private CombatMode combatMode = CombatMode.DEFENSIVE;
    private PaceMode paceMode = PaceMode.AUTO;
    private final Pace pace = new Pace(this);

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
    }

    public TaskRunner taskRunner() {
        return taskRunner;
    }

    public CombatController combatController() {
        return combatController;
    }

    /** The furnaces this companion loaded; not saved, the results stay in the world instead. */
    public FurnaceJobs furnaceJobs() {
        return furnaceJobs;
    }

    /**
     * The stations this companion placed and still owns: kept out between crafts so the next one reuses them, and
     * fetched back when nothing followed. Not saved; after a reload a station left out simply stays where it is.
     */
    public StationLease stationLease() {
        return stationLease;
    }

    /** Hunger stages and the self-starting food quest; ticked every 20 server ticks. Not saved. */
    public HungerWatcher hungerWatcher() {
        return hungerWatcher;
    }

    /** The fake player that performs placements and right-clicks. Not saved. */
    public CompanionHands hands() {
        if (hands == null) {
            hands = new CompanionHands(this);
        }
        return hands;
    }

    /** The blueprint {@code preview_structure} showed last, for {@code save_blueprint} without JSON. Not saved. */
    public Optional<Blueprint> lastPreview() {
        return Optional.ofNullable(lastPreview);
    }

    public void setLastPreview(Blueprint blueprint) {
        this.lastPreview = blueprint;
    }

    /**
     * Test hook: confines pathing to a box, so a GameTest cannot dig or build into a neighbour's structure. Not
     * saved; null means the whole loaded world is fair game.
     */
    public void setPathBounds(@Nullable AABB bounds) {
        this.pathBounds = bounds;
    }

    /** The box pathing is confined to, or null when it is not confined. */
    public @Nullable AABB pathBounds() {
        return pathBounds;
    }

    /**
     * Whether the path executor currently drives the movement. Vanilla navigation then stays idle, so it must not be
     * what decides the companion stopped moving.
     */
    public boolean pathing() {
        return pathingTicks > 0;
    }

    public void setPathing(boolean pathing) {
        this.pathingTicks = pathing ? 2 : 0;
    }

    /** Shows the owner where a structure will go; replaces any earlier outline. Not saved. */
    public void showOutline(PreviewOutline outline) {
        this.previewOutline = outline;
    }

    @Override
    public void tick() {
        super.tick();
        if (pathingTicks > 0) {
            // The executor refreshes this every tick while it drives the companion; a task that ends or is
            // cancelled does not stop it, so the flag must expire on its own.
            pathingTicks--;
        }
        if (!level().isClientSide() && isAlive()) {
            if (previewOutline != null && !previewOutline.tick((ServerLevel) level())) {
                previewOutline = null;
            }
            tickHunger((ServerLevel) level());
            combatController.tick();
            taskRunner.tick();
            if (isSprinting() && !pathing() && (getNavigation().isDone() || !canSprint())) {
                setSprinting(false);
            }
            if (++ownerPickupTicks % OWNER_PICKUP_INTERVAL == 0) {
                collectOwnerThrows();
            }
            if (++furnaceWatchTicks % 20 == 0) {
                furnaceJobs.tick(this);
            }
            if (++hungerWatchTicks % 20 == 0) {
                hungerWatcher.tick();
            }
            if (++stationWatchTicks % STATION_WATCH_INTERVAL == 0) {
                fetchBackLeftStations();
            }
        }
    }

    /**
     * Takes back a station the companion left out when no craft followed: the lease ran out, the block is still
     * there and close, and nothing else is going on. A station that is gone, out of walking distance or replaced by
     * something else is simply forgotten.
     */
    private void fetchBackLeftStations() {
        if (taskRunner.paused() || !idle()) {
            return;
        }
        long now = level().getGameTime();
        for (Optional<StationLease.Due> due = stationLease.due(now); due.isPresent(); due = stationLease.due(now)) {
            StationLease.Due entry = due.get();
            Station station = entry.station();
            BlockPos pos = BlockPos.of(entry.posKey());
            stationLease.release(station);
            Block block = Stations.block(station);
            if (block == null || !level().getBlockState(pos).is(block)) {
                continue;
            }
            if (distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                    > STATION_RECOVERY_RADIUS * STATION_RECOVERY_RADIUS) {
                continue;
            }
            taskRunner.start(new CollectStationTask(station, pos), "collect_station");
            return;
        }
    }

    /**
     * True when the default activity is all the runner is doing, so a quick errand may replace it and the companion
     * may glance at its owner. A real task owns the look, so the companion keeps its eyes on the job instead.
     */
    public boolean idle() {
        return taskRunner.current().map(task -> task instanceof FollowTask).orElse(true);
    }

    /** The companion's food, saturation and exhaustion; ticked every server tick by {@link #tick()}. */
    public HungerModel hunger() {
        return hunger;
    }

    /** Vanilla player hunger: movement, effects, then the model; heal and starve come back from it. */
    private void tickHunger(ServerLevel level) {
        if (isInWater()) {
            double dx = getX() - xo;
            double dz = getZ() - zo;
            hunger.addExhaustion(EXHAUSTION_SWIM_PER_METER * (float) Math.sqrt(dx * dx + dz * dz));
        }
        HungerModel.TickResult result = hunger.tick(
                survivalDifficulty(level.getDifficulty()),
                level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION),
                getHealth(),
                getMaxHealth());
        if (result.heal() > 0.0f) {
            heal(result.heal());
        }
        if (result.damage() > 0.0f) {
            hurtServer(level, damageSources().starve(), result.damage());
        }
        MobEffectInstance saturation = getEffect(MobEffects.SATURATION);
        if (saturation != null) {
            hunger.eat(saturation.getAmplifier() + 1, 1.0f);
        }
        MobEffectInstance hungerEffect = getEffect(MobEffects.HUNGER);
        if (hungerEffect != null) {
            hunger.addExhaustion(0.005f * (hungerEffect.getAmplifier() + 1));
        }
    }

    private static Difficulty survivalDifficulty(net.minecraft.world.Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> Difficulty.PEACEFUL;
            case EASY -> Difficulty.EASY;
            case NORMAL -> Difficulty.NORMAL;
            case HARD -> Difficulty.HARD;
        };
    }

    @Override
    public void jumpFromGround() {
        super.jumpFromGround();
        if (!level().isClientSide()) {
            hunger.addExhaustion(EXHAUSTION_JUMP);
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) {
            hunger.addExhaustion(EXHAUSTION_DAMAGE);
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
        text.append("; combat mode: ").append(combatMode.label());
        if (paceMode != PaceMode.AUTO) {
            text.append("; pace: ").append(paceMode.label());
        }
        return text.toString();
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
        // Not the vanilla player-look goal: it runs before the tasks do and would keep turning the companion away
        // from whatever it is working on toward a player standing nearby.
        this.goalSelector.addGoal(8, new LookAtOwnerGoal(this));
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

    public PaceMode paceMode() {
        return paceMode;
    }

    public void setPaceMode(PaceMode mode) {
        this.paceMode = mode == null ? PaceMode.AUTO : mode;
    }

    /** Decides between walking and running; the movement code calls it before it starts a path. */
    public Pace pace() {
        return pace;
    }

    /** Running is possible on dry land, not riding, and with enough food. */
    @Override
    public boolean canSprint() {
        return !isInWater() && !getInBlockState().is(Blocks.BUBBLE_COLUMN) && !isPassenger() && sprintFoodOk();
    }

    /** Whether the companion has the stamina to run; always true until hunger exists, then the food check goes here. */
    protected boolean sprintFoodOk() {
        return true;
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

    /** A window showing the inventory opened: the first one stops the companion so its items are not moving. */
    public void addInventoryViewer() {
        if (inventoryViewers++ == 0) {
            taskRunner.hold();
            getNavigation().stop();
        }
    }

    /** A window closed; the companion goes back to whatever it was doing after the last one. */
    public void removeInventoryViewer() {
        if (inventoryViewers > 0 && --inventoryViewers == 0) {
            taskRunner.release();
        }
    }

    /** Shift+right-click by the owner opens the inventory window; anyone else is told it is not theirs. */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!player.isSecondaryUseActive()) {
            return super.mobInteract(player, hand);
        }
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        InventoryAccess.Denial denial = FriendaiMenus.open(serverPlayer, this);
        if (denial == InventoryAccess.Denial.NONE) {
            return InteractionResult.CONSUME;
        }
        String key = denial == InventoryAccess.Denial.NOT_OWNER
                ? "friendai.inventory.not_owner"
                : "friendai.inventory.unavailable";
        serverPlayer.sendSystemMessage(Component.translatable(key), true);
        return InteractionResult.FAIL;
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
            moved += storeItem(entity);
        }
        return moved;
    }

    /**
     * Takes items the owner threw, any item, without a task: the owner handing something over
     * needs no pickup order. Items thrown by anyone else, and the companion's own drops, stay.
     */
    private void collectOwnerThrows() {
        if (ownerUuid == null) {
            return;
        }
        List<ItemEntity> items = level().getEntitiesOfClass(
                ItemEntity.class, getBoundingBox().inflate(OWNER_PICKUP_RADIUS));
        for (ItemEntity entity : items) {
            if (entity.isRemoved()) {
                continue;
            }
            if (entity.getOwner() instanceof ServerPlayer player && player.getUUID().equals(ownerUuid)) {
                storeItem(entity);
            }
        }
    }

    /** Moves one item entity's stack into the inventory; the leftover stays on the ground. */
    private int storeItem(ItemEntity entity) {
        ItemStack stack = entity.getItem().copy();
        int before = stack.getCount();
        ItemStack leftover = inventory.addItem(stack);
        int moved = before - leftover.getCount();
        if (leftover.isEmpty()) {
            entity.discard();
        } else {
            entity.setItem(leftover);
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
            owner().ifPresent(p -> p.sendSystemMessage(Component.translatable("friendai.companion.died", getName())));
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
        output.putString("PaceMode", paceMode.label());
        output.putInt("Food", hunger.food());
        output.putFloat("Saturation", hunger.saturation());
        output.putFloat("Exhaustion", hunger.exhaustion());
        taskRunner.current().map(Task::saveData).ifPresent(saved -> output.putString("SavedTask", saved));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read("Owner", UUID_CODEC).ifPresent(id -> this.ownerUuid = id);
        input.list("Inventory", ItemStack.CODEC).ifPresent(inventory::fromItemList);
        this.combatMode = CombatMode.parse(input.getString("CombatMode").orElse(null));
        this.paceMode = PaceMode.parse(input.getString("PaceMode").orElse(null));
        hunger.load(
                input.getInt("Food").orElse(HungerModel.MAX_FOOD),
                input.getFloatOr("Saturation", STARTING_SATURATION),
                input.getFloatOr("Exhaustion", 0.0f));
        if (level() instanceof ServerLevel serverLevel) {
            input.getString("SavedTask")
                    .flatMap(saved -> restoreTask(saved, serverLevel))
                    .ifPresent(taskRunner::restore);
        }
    }

    /** Plans are rebuilt with the server's tool set; every other task restores on its own. */
    private Optional<Task> restoreTask(String saved, ServerLevel level) {
        if (saved.startsWith(PlanSave.PREFIX)) {
            return FriendaiServerState.get(level.getServer()).brains().restorePlan(this, saved);
        }
        return TaskFactory.restore(saved, level);
    }
}
