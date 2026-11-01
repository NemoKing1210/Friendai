package dev.friendai.server.agent.social;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.entity.CompanionCarryData;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.social.ConversationCadence;
import dev.friendai.features.social.TopicBank;
import dev.friendai.features.social.TopicRing;
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
 * free. The pace is {@link ConversationCadence} — a per-scene gap with jitter, a quiet window after the owner speaks
 * and a backoff when a line drew nothing — so an ignored question is not repeated. One bounded
 * {@link BrainEvent.SocialOpening} rides the normal turn, which carries no tools.
 *
 * <p>A happening always wins: when there is none to tell and the pace allows a line, the companion may instead open a
 * subject of its own, taken from {@link TopicBank} in the owner's learned language and never one it already raised
 * ({@link TopicRing}). That line is {@code ORDINARY} and carries an empty happenings list, so a topic and a fact are
 * never spoken together.
 */
public final class SocialDirector {
    /** One look per second is plenty for a line that happens at most every couple of minutes. */
    private static final int LOOK_EVERY_TICKS = 20;
    /** A line nobody could hear waits this long, then is dropped once and never repeated. */
    private static final long WAIT_TICKS = 6_000;

    /** The happening keys already spoken about, per owner. */
    private final Map<UUID, java.util.Set<String>> announced = new HashMap<>();
    /** A line waiting for the owner to be free, with the tick it was first held. */
    private final Map<UUID, Held> held = new HashMap<>();
    /** The pace of unprompted lines, per owner. */
    private final Map<UUID, ConversationCadence> cadence = new HashMap<>();
    /** The subjects already raised, per owner, so an opener is not repeated. Kept for the session, never cleared. */
    private final Map<UUID, TopicRing> topics = new HashMap<>();

    private record Held(String text, long since) {}

    /** The owner said something: no unprompted line for the cadence's quiet window. */
    public void ownerSpoke(UUID owner, long gameTime) {
        cadence.computeIfAbsent(owner, ignored -> new ConversationCadence()).ownerSpoke((int) gameTime);
    }

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
            String text = nextFact(runtime, owner);
            ConversationCadence.Scene scene = scene(companion, player, hearing);
            if (scene == ConversationCadence.Scene.AWAY || !free(player, companion)) {
                if (text != null) {
                    hold(owner, text, now);
                }
                continue;
            }
            ConversationCadence pace = cadence.computeIfAbsent(owner, ignored -> new ConversationCadence());
            if (!pace.maySpeak(scene, (int) now)) {
                continue;
            }
            held.remove(owner);
            if (text != null) {
                // A happening is news: it takes the line alone and a topic is not considered at all.
                runtime.sessions()
                        .post(
                                owner,
                                new BrainEvent.SocialOpening(
                                        BrainEvent.SocialOpening.Urgency.HIGH, List.of(text), "", false));
                // A statement, not a question: nothing waits for an answer.
                pace.recordLine(scene, (int) now, false, false);
                continue;
            }
            // Nothing to report: the companion may open a subject of its own, in the owner's own language.
            String topic = nextTopic(runtime, owner);
            if (topic == null) {
                continue;
            }
            boolean asks = TopicBank.asks(topic) && pace.mayAskQuestion();
            runtime.sessions()
                    .post(
                            owner,
                            new BrainEvent.SocialOpening(
                                    BrainEvent.SocialOpening.Urgency.ORDINARY, List.of(), topic, asks));
            // A question waits for the owner to speak; an ignored one backs the next line off.
            pace.recordLine(scene, (int) now, asks, false);
        }
    }

    /** The held line if it is still fresh, otherwise the newest unspoken fact: a journal entry, or a death. */
    private String nextFact(ServerRuntime runtime, UUID owner) {
        long now = runtime.server().overworld().getGameTime();
        Held waiting = held.get(owner);
        if (waiting != null) {
            if (now - waiting.since() <= WAIT_TICKS) {
                return waiting.text();
            }
            held.remove(owner);
        }
        java.util.Set<String> spoken = announced.computeIfAbsent(owner, ignored -> new java.util.HashSet<>());
        for (var happening : runtime.memory().book(owner).journal(owner).recent(6)) {
            if (spoken.add(happening.key())) {
                return happening.text();
            }
        }
        // No journal yet (an older world, or the feed was not registered): fall back to the recorded death.
        Optional<DeathRecord> death = CompanionCarryData.get(runtime.server()).lastDeath(owner);
        if (death.isEmpty()) {
            return null;
        }
        String key = "record:" + death.get().tick();
        if (!spoken.add(key)) {
            return null;
        }
        return "I died not long ago at " + death.get().describe() + ".";
    }

    /**
     * The first opener of the bank, in the language the owner actually writes in, that the ring still allows; null
     * when every opener was already raised. The opener is remembered before it is returned, so a line that never
     * reaches the brain still counts as raised.
     */
    private String nextTopic(ServerRuntime runtime, UUID owner) {
        String language = runtime.memory().book(owner).ownerLanguage(owner).orElse(null);
        TopicRing ring = topics.computeIfAbsent(owner, ignored -> new TopicRing());
        for (String opener : TopicBank.forLanguage(language)) {
            if (ring.allows(opener)) {
                ring.remember(opener);
                return opener;
            }
        }
        return null;
    }

    private void hold(UUID owner, String text, long now) {
        held.computeIfAbsent(owner, ignored -> new Held(text, now));
    }

    private static ConversationCadence.Scene scene(CompanionEntity companion, ServerPlayer player, int hearingRadius) {
        if (companion.getTarget() != null) {
            return ConversationCadence.Scene.COMBAT;
        }
        if (player.level() != companion.level() || player.distanceTo(companion) > hearingRadius) {
            return ConversationCadence.Scene.AWAY;
        }
        return companion.taskExecution().currentToolName().isEmpty()
                ? ConversationCadence.Scene.FREE
                : ConversationCadence.Scene.BUSY;
    }

    private static boolean free(ServerPlayer player, CompanionEntity companion) {
        return companion.getTarget() == null && player.hurtTime == 0 && player.containerMenu == player.inventoryMenu;
    }
}
