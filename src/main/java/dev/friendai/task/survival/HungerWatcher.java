package dev.friendai.task.survival;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.builtin.GetFoodTask;
import dev.friendai.task.combat.Food;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;

/**
 * Watches the companion's hunger. When the food level drops into a lower stage and there is nothing safe to
 * eat, it says so once (naming how far the owner is); at stage 3 it also starts a food quest of its own when
 * nothing happened after a delay. Ticked by the entity every 20 ticks, on the server, with the entity's own
 * tick counter as the clock.
 */
public final class HungerWatcher {
    /** How long the companion waits for someone else to act before going for food itself. */
    public static final int DEFAULT_AUTO_DELAY_TICKS = 600;
    /** How long after an own food quest ended before another one may start. */
    public static final int DEFAULT_COOL_DOWN_TICKS = 1_200;
    /** What the brain hears when the companion decides to feed itself. */
    public static final String AUTO_QUEST_NOTICE = "starving: going to get food myself";
    public static final String GET_FOOD_TOOL = "get_food";

    private static final int STAGE_ONE = 10;
    private static final int STAGE_TWO = 6;
    private static final int STAGE_THREE = 3;
    private static final int NOTICE_REACH = 16;

    private final CompanionEntity companion;
    private int autoDelayTicks;
    private int coolDownTicks;

    /** The deepest stage announced so far: 0 above 10, 3 at 3 or less. */
    private int lastStage;
    private boolean pending;
    private int waitStartTick;
    private int waitTaskId;
    private int readyAtTick;
    private boolean questRunning;

    public HungerWatcher(CompanionEntity companion, int autoDelayTicks, int coolDownTicks) {
        this.companion = companion;
        this.autoDelayTicks = Math.max(0, autoDelayTicks);
        this.coolDownTicks = Math.max(0, coolDownTicks);
    }

    public void setAutoDelayTicks(int ticks) {
        this.autoDelayTicks = Math.max(0, ticks);
    }

    public void setCoolDownTicks(int ticks) {
        this.coolDownTicks = Math.max(0, ticks);
    }

    /** One look at the hunger; the entity calls this every 20 server ticks. */
    public void tick() {
        TaskRunner runner = companion.taskRunner();
        int now = companion.tickCount;
        int food = companion.hunger().food();
        int stage = stageOf(food);
        boolean hasFood = hasSafeFood();

        if (questRunning
                && !runner.current().map(task -> task.type().equals(GET_FOOD_TOOL)).orElse(false)) {
            // The quest is over: wait out the cool-down, or a new stage entry, before another.
            questRunning = false;
            pending = true;
            waitStartTick = now;
            waitTaskId = runner.currentId();
            readyAtTick = now + coolDownTicks;
        }

        if (stage < lastStage) {
            lastStage = stage;
        }
        if (stage > lastStage) {
            lastStage = stage;
            if (!hasFood) {
                waitStartTick = now;
                waitTaskId = runner.currentId();
                readyAtTick = 0;
                pending = stage >= STAGE_THREE;
                runner.notice(hungerNotice(food));
            }
        }

        if (hasFood) {
            pending = false;
        }
        if (!pending) {
            return;
        }
        if (runner.currentId() != 0 && runner.currentId() != waitTaskId) {
            // Someone started a task of their own: the companion is being looked after.
            pending = false;
            return;
        }
        if (!questRunning && now - waitStartTick >= autoDelayTicks && now >= readyAtTick && !peaceful()) {
            pending = false;
            questRunning = true;
            runner.stop("starving");
            runner.start(new GetFoodTask(2), GET_FOOD_TOOL);
            runner.notice(AUTO_QUEST_NOTICE);
        }
    }

    private boolean hasSafeFood() {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (Food.isSafe(stack, false)) {
                return true;
            }
        }
        return Food.isSafe(companion.getMainHandItem(), false);
    }

    private boolean peaceful() {
        return companion.level() instanceof ServerLevel level && level.getDifficulty() == Difficulty.PEACEFUL;
    }

    private static int stageOf(int food) {
        if (food <= STAGE_THREE) {
            return 3;
        }
        if (food <= STAGE_TWO) {
            return 2;
        }
        if (food <= STAGE_ONE) {
            return 1;
        }
        return 0;
    }

    /** "I'm hungry (6/20) and have no food; <owner> is 5 blocks away", with who the owner is and how far. */
    private String hungerNotice(int food) {
        String prefix = "I'm hungry (" + food + "/20) and have no food; ";
        Optional<ServerPlayer> online = companion.owner();
        if (online.isEmpty()) {
            return prefix + "my owner is offline";
        }
        ServerPlayer owner = online.get();
        String name = owner.getName().getString();
        boolean sameLevel = owner.level() == companion.level();
        double distance = Math.sqrt(companion.distanceToSqr(owner));
        if (sameLevel && distance <= NOTICE_REACH) {
            return prefix + name + " is " + Math.round(distance) + " blocks away";
        }
        return prefix + name + " is far away";
    }
}
