package dev.friendai.features.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Picks a subject to raise from a bounded, already-authorized list.
 *
 * <p>The selector is pure and does no I/O: the caller supplies candidates in the order it wants them tried, and the
 * first one that the ring has not raised, the banned filter allows and the question policy permits wins. A candidate
 * that is blank, over {@link TopicBank#MAX_LENGTH} or unanswerable under the current policy is skipped rather than
 * trimmed or rewritten, so a line never states more than its source does. An empty result is a valid answer: the
 * companion stays silent.
 */
public final class TopicCandidates {
    private TopicCandidates() {}

    /** Every candidate that passes the ring, the banned filter, the length bound and the question policy, in order. */
    public static List<TopicCandidate> allowed(
            List<TopicCandidate> candidates, TopicRing ring, String language, boolean allowQuestion) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<TopicCandidate> allowed = new ArrayList<>(candidates.size());
        for (TopicCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String text = candidate.text();
            if (text.isEmpty() || text.length() > TopicBank.MAX_LENGTH) {
                continue;
            }
            if (!ring.allows(candidate.id())) {
                continue;
            }
            if (BannedTopics.banned(text, language)) {
                continue;
            }
            if (!allowQuestion && TopicBank.asks(text)) {
                continue;
            }
            allowed.add(candidate);
        }
        return allowed;
    }

    /** The first candidate {@link #allowed} would keep, or empty when every candidate is refused. */
    public static Optional<TopicCandidate> select(
            List<TopicCandidate> candidates, TopicRing ring, String language, boolean allowQuestion) {
        List<TopicCandidate> allowed = allowed(candidates, ring, language, allowQuestion);
        return allowed.isEmpty() ? Optional.empty() : Optional.of(allowed.getFirst());
    }
}
