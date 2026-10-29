package dev.friendai.features.trading.minecraft.task;

import dev.friendai.features.trading.TradeOffer;
import dev.friendai.features.trading.TraderRecord;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;

/** Waits for an observed vanilla villager restock; it never changes offer stock itself. */
public final class WaitForRestockTask implements Task {
    public static final int POLL_INTERVAL_TICKS = 200;

    private final UUID trader;
    private final String offerRef;
    private final long deadlineTick;
    private UUID ownerAtStart;
    private String dimensionAtStart = "";
    private TradeOffer before;
    private InspectTraderTask inspection;
    private long nextInspectionTick;
    private long ticks;
    private TaskStatus terminal;
    private String reason = "";

    public WaitForRestockTask(UUID trader, String offerRef, long deadlineTick) {
        if (trader == null || offerRef == null || offerRef.isBlank() || offerRef.length() > 160 || deadlineTick < 0) {
            throw new IllegalArgumentException("restock wait needs a trader, offer reference and deadline");
        }
        this.trader = trader;
        this.offerRef = offerRef;
        this.deadlineTick = deadlineTick;
    }

    @Override
    public String type() {
        return "wait_for_restock";
    }

    @Override
    public void start(TaskContext context) {
        ownerAtStart = context.companion().ownerUuid();
        dimensionAtStart = context.level().dimension().identifier().toString();
        terminal = null;
        reason = "";
        inspection = null;
        ticks = 0;
        nextInspectionTick = context.level().getGameTime();
        if (ownerAtStart == null) {
            fail("companion has no owner");
            return;
        }
        TraderRecord known = book(context).trader(trader).orElse(null);
        if (known == null) {
            fail("trader is not in the owner's trade book");
            return;
        }
        if ("minecraft:wandering_trader".equals(known.entityType())) {
            fail("wandering traders do not restock offers");
            return;
        }
        before = known.offers().stream()
                .filter(value -> value.ref().equals(offerRef))
                .findFirst()
                .orElse(null);
        if (before == null) {
            fail("offer reference is no longer known; inspect the trader again");
            return;
        }
        if (!before.exhausted()) {
            finish(TaskStatus.SUCCESS, "offer already has stock");
            return;
        }
        Entity entity = context.level().getEntity(trader);
        if (entity instanceof Villager villager && hasNoWorkstation(villager)) {
            fail("villager has no observed workstation and cannot restock here");
        }
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        if (terminal != null) return terminal;
        long now = context.level().getGameTime();
        if (now >= deadlineTick)
            return finish(TaskStatus.FAILED, "deadline expired before a vanilla restock was observed");
        String invalid = TraderTaskNavigation.invalidContext(context, ownerAtStart, dimensionAtStart);
        if (invalid != null) return finish(TaskStatus.FAILED, invalid);
        Entity target = context.level().getEntity(trader);
        if (target instanceof WanderingTrader)
            return finish(TaskStatus.FAILED, "wandering traders do not restock offers");
        if (target != null && !target.isAlive()) return finish(TaskStatus.FAILED, "selected villager is dead");
        if (target != null && !(target instanceof Villager)) {
            return finish(TaskStatus.FAILED, "selected trader is not a restocking villager");
        }
        if (target instanceof Villager villager && hasNoWorkstation(villager)) {
            return finish(TaskStatus.FAILED, "villager has no observed workstation and cannot restock here");
        }
        if (inspection == null) {
            if (now < nextInspectionTick) return TaskStatus.RUNNING;
            inspection = new InspectTraderTask(trader);
            inspection.start(context);
        }
        TaskStatus result = inspection.tick(context);
        if (result == TaskStatus.RUNNING) return result;
        if (result == TaskStatus.FAILED) {
            reason = inspection.resultSummary();
            inspection = null;
            nextInspectionTick = now + POLL_INTERVAL_TICKS;
            return TaskStatus.RUNNING;
        }
        TraderRecord current = book(context).trader(trader).orElse(null);
        TradeOffer refreshed = current == null
                ? null
                : current.offers().stream()
                        .filter(value -> value.ref().equals(offerRef))
                        .findFirst()
                        .orElse(null);
        if (refreshed != null && rested(before, refreshed)) {
            return finish(TaskStatus.SUCCESS, "vanilla restock observed at tick " + refreshed.observedTick());
        }
        inspection = null;
        nextInspectionTick = now + POLL_INTERVAL_TICKS;
        ticks++;
        return TaskStatus.RUNNING;
    }

    private static boolean hasNoWorkstation(Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty();
    }

    private static boolean rested(TradeOffer previous, TradeOffer current) {
        return current.observedTick() > previous.observedTick() && current.uses() < previous.uses();
    }

    private dev.friendai.features.trading.TradeBook book(TaskContext context) {
        return context.tradeBook(ownerAtStart);
    }

    private void fail(String message) {
        finish(TaskStatus.FAILED, message);
    }

    private TaskStatus finish(TaskStatus status, String message) {
        terminal = status;
        reason = message;
        return status;
    }

    @Override
    public void stop(TaskContext context) {
        if (inspection != null) inspection.stop(context);
        inspection = null;
        if (terminal == null) finish(TaskStatus.FAILED, "restock wait stopped");
    }

    @Override
    public String describe() {
        return "wait for villager " + trader + " to restock offer " + offerRef;
    }

    @Override
    public String resultSummary() {
        String result = "trader=" + trader + "; offer=" + offerRef + "; polls=" + ticks
                + (reason.isBlank() ? "" : "; result=" + reason);
        return result.length() <= 600 ? result : result.substring(0, 597) + "...";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }
}
