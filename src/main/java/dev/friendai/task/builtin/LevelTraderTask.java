package dev.friendai.task.builtin;

import dev.friendai.features.trading.TradeOrder;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

/** Executes only the explicit, pre-authorized offer list while checking the selected villager's real level. */
public final class LevelTraderTask implements Task {
    private final UUID trader;
    private final int targetLevel;
    private final List<TradeOrder> orders;
    private int index;
    private TradeTask current;
    private TaskStatus terminal;
    private String reason = "";

    public LevelTraderTask(UUID trader, int targetLevel, List<TradeOrder> authorizedOrders) {
        if (trader == null
                || targetLevel < 2
                || targetLevel > 5
                || authorizedOrders == null
                || authorizedOrders.isEmpty()
                || authorizedOrders.size() > 64
                || authorizedOrders.stream().anyMatch(order -> order == null || !trader.equals(order.trader()))
                || new HashSet<>(authorizedOrders.stream()
                                        .map(TradeOrder::orderId)
                                        .toList())
                                .size()
                        != authorizedOrders.size()) {
            throw new IllegalArgumentException(
                    "leveling needs a trader, target level 2..5 and 1..64 authorized offers");
        }
        this.trader = trader;
        this.targetLevel = targetLevel;
        this.orders = List.copyOf(authorizedOrders);
    }

    @Override
    public String type() {
        return "level_trader";
    }

    @Override
    public void start(TaskContext context) {
        index = 0;
        current = null;
        terminal = null;
        reason = "";
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        if (terminal != null) return terminal;
        Entity entity = context.level().getEntity(trader);
        if (!(entity instanceof Villager villager) || !villager.isAlive()) {
            return finish(TaskStatus.FAILED, "selected villager is missing, dead or no longer a villager");
        }
        if (villager.getVillagerData().level() >= targetLevel) {
            return finish(TaskStatus.SUCCESS, "target level reached; no filler trades were made");
        }
        if (current == null) {
            if (index >= orders.size())
                return finish(
                        TaskStatus.FAILED,
                        "allowed offers or shared spending budget ended before target level " + targetLevel);
            current = new TradeTask(orders.get(index));
            current.start(context);
        }
        TaskStatus status = current.tick(context);
        if (status == TaskStatus.RUNNING) return status;
        if (status != TaskStatus.SUCCESS) {
            reason = current.resultSummary();
            return finish(TaskStatus.FAILED, "selected offer did not complete: " + reason);
        }
        current = null;
        index++;
        return TaskStatus.RUNNING;
    }

    private TaskStatus finish(TaskStatus status, String message) {
        terminal = status;
        reason = message;
        return status;
    }

    @Override
    public void stop(TaskContext context) {
        if (current != null) current.stop(context);
        current = null;
        if (terminal == null) finish(TaskStatus.FAILED, "villager leveling stopped before completion");
    }

    @Override
    public String describe() {
        return "level selected villager " + trader + " to " + targetLevel;
    }

    @Override
    public String resultSummary() {
        String text = "trader=" + trader + "; target_level=" + targetLevel + "; authorized_offers=" + orders.size()
                + "; completed_offer_orders=" + index + (reason.isBlank() ? "" : "; result=" + reason);
        return text.length() <= 600 ? text : text.substring(0, 597) + "...";
    }
}
