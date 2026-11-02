package dev.friendai.server.agent.social;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.verify.LoopMetrics;
import dev.friendai.entity.CompanionCarryData;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.memory.Subject;
import dev.friendai.features.social.ConversationCadence;
import dev.friendai.features.social.SocialActivity;
import dev.friendai.features.social.SocialMemoryRepository;
import dev.friendai.features.social.SocialMemorySave;
import dev.friendai.features.social.SocialTopics;
import dev.friendai.features.social.TopicBank;
import dev.friendai.features.social.TopicCandidate;
import dev.friendai.features.social.TopicCandidates;
import dev.friendai.features.social.TopicRing;
import dev.friendai.features.social.TopicTaste;
import dev.friendai.features.survival.DeathRecord;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayList;
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
 * never spoken together. The raised subjects and the taste learned from answers are {@link SocialMemorySave}d by their
 * own owner record, loaded on first use and saved whenever they change, so a restart does not repeat a subject or
 * forget a preference.
 *
 * <p>Every twenty minutes each owner's {@link SocialActivity} counters — spoken, held, dropped lines, owner messages
 * and happenings seen — are written as one line to that owner's debug log and then cleared. The same window writes
 * the owner's {@link LoopMetrics} line, which counts verified and refused goals, duplicate refusals and interrupts.
 * This changes nothing about when a line is spoken: it only makes the companion's silence or noise measurable on a
 * running server.
 */
public final class SocialDirector {
    /** One look per second is plenty for a line that happens at most every couple of minutes. */
    private static final int LOOK_EVERY_TICKS = 20;
    /** An answer to a raised subject arrives within this window; after it, the silence counts against the subject. */
    private static final long ANSWER_WINDOW_TICKS = 1_800;
    /** A line nobody could hear waits this long, then is dropped once and never repeated. */
    private static final long WAIT_TICKS = 6_000;
    /** How often the per-owner counters reach the debug log: twenty minutes of server ticks. */
    private static final int REPORT_EVERY_TICKS = 24_000;

    /** The happening keys already spoken about, per owner. */
    private final Map<UUID, java.util.Set<String>> announced = new HashMap<>();
    /** A line waiting for the owner to be free, with the tick it was first held. */
    private final Map<UUID, Held> held = new HashMap<>();
    /** The pace of unprompted lines, per owner. */
    private final Map<UUID, ConversationCadence> cadence = new HashMap<>();
    /** The subjects already raised, per owner, so an opener is not repeated. Loaded from the record on first use. */
    private final Map<UUID, TopicRing> topics = new HashMap<>();
    /** What the owner seems to like talking about, learned only from whether they answered. */
    private final Map<UUID, TopicTaste> taste = new HashMap<>();
    /** The owner record that carries the ring and the taste across a restart. */
    private final SocialMemoryRepository memory;
    /** The subject raised last, and when, so an answer or a silence can be counted once. */
    private final Map<UUID, PendingTopic> pendingTopic = new HashMap<>();
    /** What happened in the current window, per owner: logged and then cleared, never player-facing. */
    private final Map<UUID, SocialActivity> activity = new HashMap<>();

    public SocialDirector(SocialMemoryRepository memory) {
        this.memory = memory;
    }

    private record PendingTopic(String topic, long raisedAt) {}

    private record Held(String text, long since) {}

    /** The owner said something: no unprompted line for the cadence's quiet window. */
    public void ownerSpoke(UUID owner, long gameTime) {
        activityOf(owner).ownerSpoke();
        cadence.computeIfAbsent(owner, ignored -> new ConversationCadence()).ownerSpoke((int) gameTime);
        PendingTopic pending = pendingTopic.remove(owner);
        if (pending != null && gameTime - pending.raisedAt() <= ANSWER_WINDOW_TICKS) {
            // The owner came back to the subject we raised: that is what a preference is made of.
            tasteOf(owner).answered(pending.topic());
            persist(owner);
        }
    }

    public void tick(ServerRuntime runtime) {
        long ticks = runtime.server().getTickCount();
        if (ticks > 0 && ticks % REPORT_EVERY_TICKS == 0) {
            reportLoops(runtime);
        }
        if (!runtime.config().chat.ambientSpeech) {
            return;
        }
        if (ticks % LOOK_EVERY_TICKS != 0) {
            return;
        }
        if (ticks > 0 && ticks % REPORT_EVERY_TICKS == 0) {
            reportActivity(runtime);
        }
        long now = runtime.server().overworld().getGameTime();
        expireAnswers(now);
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
                activityOf(owner).line(true);
                continue;
            }
            // Nothing to report: the companion may open a subject of its own, in the owner's own language.
            boolean mayAsk = pace.mayAskQuestion();
            TopicCandidate topic = nextTopic(runtime, owner, mayAsk);
            if (topic == null) {
                continue;
            }
            boolean asks = TopicBank.asks(topic.text()) && mayAsk;
            // Commit the identity only now, when the event is actually posted: choosing alone remembers nothing.
            TopicRing ring = topicRing(owner);
            ring.remember(topic.id());
            persist(owner);
            runtime.sessions()
                    .post(
                            owner,
                            new BrainEvent.SocialOpening(
                                    BrainEvent.SocialOpening.Urgency.ORDINARY, List.of(), topic.text(), asks));
            // A question waits for the owner to speak; an ignored one backs the next line off.
            pace.recordLine(scene, (int) now, asks, false);
            activityOf(owner).line(false);
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
            activityOf(owner).dropped();
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

    /** A raised subject nobody answered stops counting as liked once its window closes. */
    private void expireAnswers(long now) {
        for (var entry : new java.util.ArrayList<>(pendingTopic.entrySet())) {
            if (now - entry.getValue().raisedAt() > ANSWER_WINDOW_TICKS) {
                pendingTopic.remove(entry.getKey());
                tasteOf(entry.getKey()).ignored(entry.getValue().topic());
                persist(entry.getKey());
            }
        }
    }

    /**
     * The next subject to raise: a grounded fact the ring has not used, else a neutral question from the bank. The
     * identity is committed by the caller only when the event is actually posted; choosing alone changes nothing.
     */
    private TopicCandidate nextTopic(ServerRuntime runtime, UUID owner, boolean allowQuestion) {
        String language = runtime.memory().book(owner).ownerLanguage(owner).orElse(null);
        TopicRing ring = topicRing(owner);
        TopicTaste learned = tasteOf(owner);
        List<TopicCandidate> grounded =
                TopicCandidates.allowed(groundedTopics(runtime, owner), ring, language, allowQuestion);
        if (!grounded.isEmpty()) {
            return preferTaste(grounded, learned);
        }
        List<TopicCandidate> bank = new ArrayList<>();
        for (String opener : TopicBank.forLanguage(language)) {
            bank.add(TopicCandidate.ofText(opener));
        }
        List<TopicCandidate> neutral = TopicCandidates.allowed(bank, ring, language, allowQuestion);
        return neutral.isEmpty() ? null : preferTaste(neutral, learned);
    }

    /** The owner's own durable statements about themselves, each rendered with its source named. */
    private List<TopicCandidate> groundedTopics(ServerRuntime runtime, UUID owner) {
        return SocialTopics.fromFacts(runtime.memory().book(owner).facts(new Subject.Player(owner)), owner);
    }

    /** The candidate the owner answered before, if one is among the allowed ones; otherwise the first. */
    private static TopicCandidate preferTaste(List<TopicCandidate> allowed, TopicTaste learned) {
        List<String> texts = new ArrayList<>(allowed.size());
        for (TopicCandidate candidate : allowed) {
            texts.add(candidate.text());
        }
        String best = learned.best(texts);
        if (best != null) {
            for (TopicCandidate candidate : allowed) {
                if (candidate.text().equals(best)) {
                    return candidate;
                }
            }
        }
        return allowed.getFirst();
    }

    /** The owner's ring, loaded from their record the first time it is needed; the map stays the fast path. */
    private TopicRing topicRing(UUID owner) {
        TopicRing saved = topics.get(owner);
        if (saved == null) {
            saved = TopicRing.of(memory.state(owner).raised());
            topics.put(owner, saved);
        }
        return saved;
    }

    /** The owner's taste, loaded from their record the first time it is needed. */
    private TopicTaste tasteOf(UUID owner) {
        TopicTaste saved = taste.get(owner);
        if (saved == null) {
            saved = TopicTaste.of(memory.state(owner).taste());
            taste.put(owner, saved);
        }
        return saved;
    }

    /** Hands the current ring and taste to the owner record; the shared flush writes them on its next tick. */
    private void persist(UUID owner) {
        memory.save(
                owner,
                new SocialMemorySave(topicRing(owner).entries(), tasteOf(owner).weights()));
    }

    private void hold(UUID owner, String text, long now) {
        if (held.containsKey(owner)) {
            return;
        }
        held.put(owner, new Held(text, now));
        activityOf(owner).held();
    }

    /**
     * The counters of one owner's window; made on first use and kept for the session like the rest of the map. Also
     * the seam {@link HappeningFeed} writes its event count through, so one summary covers lines and happenings.
     */
    public SocialActivity activityOf(UUID owner) {
        return activity.computeIfAbsent(owner, ignored -> new SocialActivity());
    }

    /**
     * Writes each loaded owner's {@link LoopMetrics#summary()} into that owner's debug log and starts a fresh window.
     * Shares {@link #reportActivity}'s twenty-minute cadence but runs whether or not ambient speech is on: the loop
     * works either way, and these counters are about refusals and interruptions, not about lines.
     */
    private void reportLoops(ServerRuntime runtime) {
        for (CompanionEntity companion : runtime.companions().all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null) {
                continue;
            }
            LoopMetrics loops = companion.executionCoordinator().loopMetrics();
            runtime.sessions().debug().logOf(owner).add(LogKind.MANUAL, loops.summary());
            loops.reset();
        }
    }

    /**
     * Writes one counter line into the debug log of each owner whose companion is loaded, then starts a fresh window.
     * Runs once per {@link #REPORT_EVERY_TICKS}, so the summary string is built twenty minutes apart, never per tick.
     */
    private void reportActivity(ServerRuntime runtime) {
        for (CompanionEntity companion : runtime.companions().all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null) {
                continue;
            }
            SocialActivity record = activity.get(owner);
            if (record == null) {
                continue;
            }
            runtime.sessions().debug().logOf(owner).add(LogKind.MANUAL, record.summary());
            record.reset();
        }
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
