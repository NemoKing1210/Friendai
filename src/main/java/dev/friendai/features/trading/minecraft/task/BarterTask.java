package dev.friendai.features.trading.minecraft.task;

import dev.friendai.features.combat.minecraft.Threats;
import dev.friendai.features.navigation.minecraft.nav.Navigator;
import dev.friendai.features.trading.BarterOrder;
import dev.friendai.features.trading.BarterProgress;
import dev.friendai.features.trading.TradeItem;
import dev.friendai.features.trading.minecraft.trading.BarterDropTracker;
import dev.friendai.features.trading.minecraft.trading.PiglinBarterAdapter;
import dev.friendai.features.trading.minecraft.trading.TradeItemCodec;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.DropCollector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;

/** One owner-authorized random barter order: locate, pay one, attribute, collect, then decide whether to continue. */
public final class BarterTask implements Task {
    private enum Phase {
        READY,
        WAITING,
        COLLECTING
    }

    private final BarterOrder order;
    private UUID ownerAtStart;
    private String dimensionAtStart = "";
    private Navigator navigator;
    private DropCollector collector;
    private UUID attempt;
    private long attemptStarted;
    private long notAdmiringSince = -1;
    private int goldAccepted;
    private final Set<UUID> emitted = new HashSet<>();
    private final List<TradeItem> collected = new ArrayList<>();
    private Phase phase = Phase.READY;
    private BarterProgress.State state = BarterProgress.State.AUTHORIZED;
    private TaskStatus terminal;
    private String reason = "";

    public BarterTask(BarterOrder order) {
        this.order = java.util.Objects.requireNonNull(order);
    }

    @Override
    public String type() {
        return "barter";
    }

    @Override
    public void start(TaskContext context) {
        ownerAtStart = context.companion().ownerUuid();
        dimensionAtStart = context.level().dimension().identifier().toString();
        navigator = TraderTaskNavigation.navigator(context);
        collector = null;
        attempt = null;
        attemptStarted = 0;
        notAdmiringSince = -1;
        goldAccepted = 0;
        emitted.clear();
        collected.clear();
        phase = Phase.READY;
        state = BarterProgress.State.AUTHORIZED;
        terminal = null;
        reason = "";
        record(context);
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        if (terminal != null) return terminal;
        if (!context.tradingEnabled()) return finish(context, TaskStatus.FAILED, "trading was disabled");
        String invalid = TraderTaskNavigation.invalidContext(context, ownerAtStart, dimensionAtStart);
        if (invalid != null) return finish(context, TaskStatus.FAILED, invalid);
        if (!order.owner().equals(ownerAtStart))
            return finish(context, TaskStatus.FAILED, "barter order belongs to another owner");
        long now = context.level().getServer().overworld().getGameTime();
        if (now >= order.deadlineTick())
            return finish(
                    context,
                    phase == Phase.WAITING ? BarterProgress.State.UNCERTAIN : incompleteState(),
                    "barter deadline expired");
        if (context.companion()
                .combatController()
                .target()
                .filter(Entity::isAlive)
                .isPresent()) {
            return finish(context, incompleteState(), "combat interrupted barter before another payment");
        }

        Entity entity = context.level().getEntity(order.piglin());
        if (!(entity instanceof Piglin piglin) || !piglin.isAlive() || piglin.isBaby()) {
            return finish(context, incompleteState(), "selected adult piglin is missing or no longer valid");
        }
        if (!TraderTaskNavigation.inBoundedArea(context.companion(), piglin)) {
            return finish(context, incompleteState(), "selected piglin is outside the 64-block route bound");
        }
        if (!TraderTaskNavigation.inActionReach(context.companion(), piglin)) {
            TraderTaskNavigation.aimAt(navigator, piglin, context.companion());
            if (navigator.tick(context) == Navigator.State.UNREACHABLE) {
                return finish(context, incompleteState(), "no safe path to the selected piglin");
            }
            return TaskStatus.RUNNING;
        }
        TraderTaskNavigation.stop(navigator, context);
        if (!Threats.hostilesNear(
                                (net.minecraft.server.level.ServerLevel) context.level(),
                                context.companion().position(),
                                8.0)
                        .stream()
                        .anyMatch(hostile -> hostile != piglin)
                && phase == Phase.READY) {
            return stepReady(context, piglin, context.piglinBarterAdapter(), context.barterDropTracker(), now);
        }
        if (phase == Phase.WAITING) return stepWaiting(context, piglin, context.barterDropTracker(), now);
        if (phase == Phase.COLLECTING) return stepCollecting(context, piglin, context.barterDropTracker(), now);
        return finish(context, TaskStatus.FAILED, "nearby hostile interrupted barter safety check");
    }

    private TaskStatus stepReady(
            TaskContext context, Piglin piglin, PiglinBarterAdapter adapter, BarterDropTracker tracker, long now) {
        if (piglin.getTarget() != null
                || piglin.isDancing()
                || piglin.getMainHandItem().is(net.minecraft.world.item.Items.GOLD_INGOT)) {
            return finish(context, incompleteState(), "piglin is hostile or already busy bartering");
        }
        if (goldAccepted >= order.maxIngots()) return complete(context, "maximum gold budget reached");
        if (order.earlyStopTarget().isPresent()
                && collectedCount(order.earlyStopTarget().get()) >= order.targetCount()) {
            return complete(context, "collected early-stop target");
        }
        if (freeSlots(context) < 4)
            return finish(context, incompleteState(), "fewer than four inventory slots remain for random output");
        if (!adapter.canStart(context.companion(), piglin.getUUID())) {
            return finish(context, incompleteState(), "piglin is unwilling, busy or unsafe to approach");
        }
        var authority = context.tradeAuthorityFor(order.owner());
        if (!authority.reserveBarter(order, 1))
            return finish(context, incompleteState(), "shared gold budget is exhausted");
        attempt = UUID.randomUUID();
        attemptStarted = now;
        notAdmiringSince = -1;
        tracker.begin(attempt, piglin.getUUID(), now);
        if (!adapter.payOne(context.companion(), piglin.getUUID(), attempt)) {
            tracker.finish(attempt);
            authority.releaseBarterReservation(order, 1);
            attempt = null;
            return finish(context, incompleteState(), "vanilla piglin interaction did not accept one plain gold ingot");
        }
        goldAccepted = Math.addExact(goldAccepted, 1);
        state = BarterProgress.State.GOLD_ACCEPTED;
        phase = Phase.WAITING;
        record(context);
        return TaskStatus.RUNNING;
    }

    private TaskStatus stepWaiting(TaskContext context, Piglin piglin, BarterDropTracker tracker, long now) {
        List<UUID> outputIds = tracker.outputs(attempt);
        if (tracker.overflowed(attempt)) {
            return finish(
                    context,
                    BarterProgress.State.UNCERTAIN,
                    "barter output exceeded the attribution bound; no further payment will be made");
        }
        if (now - attemptStarted > BarterDropTracker.MAX_ATTEMPT_TICKS) {
            return finish(context, BarterProgress.State.UNCERTAIN, "vanilla barter attribution window expired");
        }
        if (!piglin.isAlive())
            return finish(
                    context,
                    BarterProgress.State.UNCERTAIN,
                    "piglin died after accepting gold; pending output evidence retained");
        if (outputIds.isEmpty()) {
            if (piglin.getMainHandItem().is(net.minecraft.world.item.Items.GOLD_INGOT)) {
                notAdmiringSince = -1;
                return TaskStatus.RUNNING;
            }
            if (notAdmiringSince < 0) notAdmiringSince = now;
            if (now - notAdmiringSince >= 20) {
                // Wait briefly for the vanilla item-entity load event; never pay again for a missing roll.
                return finish(
                        context,
                        BarterProgress.State.LOST,
                        "gold was accepted but no attributable output was observed");
            }
            return TaskStatus.RUNNING;
        }
        if (piglin.getMainHandItem().is(net.minecraft.world.item.Items.GOLD_INGOT)) return TaskStatus.RUNNING;
        emitted.addAll(outputIds);
        state = BarterProgress.State.OUTPUT_EMITTED;
        phase = Phase.COLLECTING;
        collector = new DropCollector(context.companion().position(), 16, null, Set.copyOf(outputIds));
        record(context);
        return TaskStatus.RUNNING;
    }

    private TaskStatus stepCollecting(TaskContext context, Piglin piglin, BarterDropTracker tracker, long now) {
        if (collector.tick(context) == DropCollector.State.COLLECTING) return TaskStatus.RUNNING;
        for (ItemStack stack : collector.collectedStacks()) {
            if (!stack.isEmpty())
                collected.add(TradeItemCodec.encode(stack, context.level().registryAccess()));
        }
        boolean pending = emitted.stream().anyMatch(id -> context.level().getEntity(id) instanceof ItemEntity);
        tracker.finish(attempt);
        attempt = null;
        notAdmiringSince = -1;
        collector = null;
        if (pending) {
            return finish(
                    context,
                    collected.isEmpty() ? BarterProgress.State.LOST : BarterProgress.State.PARTIAL,
                    "some attributable output remains in the world or could not fit in inventory");
        }
        state = BarterProgress.State.RUNNING;
        phase = Phase.READY;
        record(context);
        if (order.earlyStopTarget().isPresent()
                && collectedCount(order.earlyStopTarget().get()) >= order.targetCount()) {
            return complete(context, "collected early-stop target");
        }
        if (goldAccepted >= order.maxIngots()) return complete(context, "maximum gold budget reached");
        return TaskStatus.RUNNING;
    }

    private int collectedCount(dev.friendai.features.trading.TradeQuery query) {
        return progress().collectedCount(query);
    }

    private int freeSlots(TaskContext context) {
        int free = 0;
        for (int slot = 0; slot < context.companion().inventory().getContainerSize(); slot++) {
            if (context.companion().inventory().getItem(slot).isEmpty()) free++;
        }
        return free;
    }

    private BarterProgress progress() {
        return new BarterProgress(order.orderId(), goldAccepted, emitted, collected, state, reason);
    }

    private BarterProgress.State incompleteState() {
        if (goldAccepted == 0) return BarterProgress.State.INTERRUPTED;
        if (attempt != null && phase == Phase.WAITING) return BarterProgress.State.UNCERTAIN;
        return collected.isEmpty() ? BarterProgress.State.LOST : BarterProgress.State.PARTIAL;
    }

    private TaskStatus complete(TaskContext context, String message) {
        return finish(
                context, collected.isEmpty() ? BarterProgress.State.PARTIAL : BarterProgress.State.COLLECTED, message);
    }

    private TaskStatus finish(TaskContext context, TaskStatus status, String message) {
        return finish(
                context, status == TaskStatus.SUCCESS ? BarterProgress.State.COLLECTED : incompleteState(), message);
    }

    private TaskStatus finish(TaskContext context, BarterProgress.State finalState, String message) {
        if (terminal != null) return terminal;
        reason = message == null ? "" : message;
        state = finalState;
        terminal = finalState == BarterProgress.State.COLLECTED ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        captureCollector(context);
        collector = null;
        if (navigator != null) TraderTaskNavigation.stop(navigator, context);
        navigator = null;
        if (attempt != null
                && finalState != BarterProgress.State.INTERRUPTED
                && finalState != BarterProgress.State.UNCERTAIN) {
            context.barterDropTracker().finish(attempt);
        }
        record(context);
        return terminal;
    }

    private void record(TaskContext context) {
        context.tradeAuthorityFor(order.owner()).recordBarter(progress());
        try {
            context.tradeBook(order.owner()).recordBarter(progress());
        } catch (RuntimeException ignored) {
            // Runtime authority is authoritative if bounded informational history cannot be updated.
        }
    }

    @Override
    public void stop(TaskContext context) {
        if (terminal != null) return;
        reason = "barter stopped; no further gold will be paid";
        state = attempt != null ? BarterProgress.State.INTERRUPTED : incompleteState();
        terminal = TaskStatus.FAILED;
        captureCollector(context);
        if (navigator != null) TraderTaskNavigation.stop(navigator, context);
        navigator = null;
        // A pending attempt stays in the tracker briefly so a late vanilla output can still be recovered by UUID.
        record(context);
    }

    private void captureCollector(TaskContext context) {
        if (collector == null) return;
        for (ItemStack stack : collector.collectedStacks()) {
            if (!stack.isEmpty())
                collected.add(TradeItemCodec.encode(stack, context.level().registryAccess()));
        }
        collector.stop(context);
        collector = null;
    }

    @Override
    public String describe() {
        return "bounded piglin barter with " + order.piglin();
    }

    @Override
    public String resultSummary() {
        String text = "order=" + order.orderId() + "; state=" + state.name().toLowerCase(java.util.Locale.ROOT)
                + "; gold_accepted=" + goldAccepted + "/" + order.maxIngots() + "; emitted=" + emitted.size()
                + "; collected=" + collected + (reason.isBlank() ? "" : "; reason=" + reason);
        return text.length() <= 600 ? text : text.substring(0, 597) + "...";
    }

    @Override
    public String saveData() {
        return null;
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }
}
