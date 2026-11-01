package dev.friendai.server.agent.social;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.entity.CompanionCarryData;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.survival.DeathRecord;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Decides when the companion may speak first, and about what.
 *
 * <p>Only facts start a line: the newest death it remembers, said once, when the owner is near enough to hear it and
 * not busy. There is no small talk and no second request — one bounded {@link BrainEvent.SocialOpening} rides the
 * normal turn, which carries no tools. The owner's switch, the hearing radius and a minimum gap keep it from nagging.
 */
public final class SocialDirector {
    /** One look per second is plenty for a line that happens at most every couple of minutes. */
    private static final int LOOK_EVERY_TICKS = 20;
    /** Two lines never come closer than this, whatever happened. */
    private static final long QUIET_TICKS = 1_200;
    /** A line nobody could hear waits this long, then is dropped once and never repeated. */
    private static final long WAIT_TICKS = 6_000;

    /** The death tick already spoken about, per owner. */
    private final Map<UUID, Long> announced = new HashMap<>();
    /** A line waiting for the owner to be free, with the tick it was first held. */
    private final Map<UUID, Held> held = new HashMap<>();
    /** The last line posted, per owner, for the quiet window. */
    private final Map<UUID, Long> lastLine = new HashMap<>();

    private record Held(String text, long since) {}

    public void tick(ServerRuntime runtime) {
        if (!runtime.config().chat.ambientSpeech) {
            return;
        }
        if (runtime.server().getTickCount() % LOOK_EVERY_TICKS != 0) {
            return;
        }
        long now = runtime.server().overworld().getGameTime();
        int hearing = runtime.config().chat.hearingRadius;
        for (CompanionEntity companion : runtime.companions().all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null) {
                continue;
            }
            ServerPlayer player = companion.owner().orElse(null);
            if (player == null) {
                continue;
            }
            Long last = lastLine.get(owner);
            if (last != null && now - last < QUIET_TICKS) {
                continue;
            }
            String text = nextFact(runtime, owner);
            if (text == null) {
                continue;
            }
            if (!canHear(companion, player, hearing) || !free(player, companion)) {
                hold(owner, text, now);
                continue;
            }
            held.remove(owner);
            runtime.sessions()
                    .post(
                            owner,
                            new BrainEvent.SocialOpening(
                                    BrainEvent.SocialOpening.Urgency.HIGH, List.of(text), "", false));
            lastLine.put(owner, now);
        }
    }

    /** The held line if it is still fresh, otherwise the newest death nobody has spoken about yet. */
    private String nextFact(ServerRuntime runtime, UUID owner) {
        long now = runtime.server().overworld().getGameTime();
        Held waiting = held.get(owner);
        if (waiting != null) {
            if (now - waiting.since() <= WAIT_TICKS) {
                return waiting.text();
            }
            held.remove(owner);
        }
        Optional<DeathRecord> death = CompanionCarryData.get(runtime.server()).lastDeath(owner);
        if (death.isEmpty()) {
            return null;
        }
        long tick = death.get().tick();
        if (announced.getOrDefault(owner, Long.MIN_VALUE) == tick) {
            return null;
        }
        announced.put(owner, tick);
        return "I died not long ago at " + death.get().describe() + ".";
    }

    private void hold(UUID owner, String text, long now) {
        held.computeIfAbsent(owner, ignored -> new Held(text, now));
    }

    private static boolean canHear(CompanionEntity companion, ServerPlayer player, int hearingRadius) {
        return player.level() == companion.level() && player.distanceTo(companion) <= hearingRadius;
    }

    private static boolean free(ServerPlayer player, CompanionEntity companion) {
        return companion.getTarget() == null && player.hurtTime == 0 && player.containerMenu == player.inventoryMenu;
    }
}
