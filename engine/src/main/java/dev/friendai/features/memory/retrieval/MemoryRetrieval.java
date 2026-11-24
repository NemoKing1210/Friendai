package dev.friendai.features.memory.retrieval;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.memory.Fact;
import dev.friendai.features.memory.MemoryAudit;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.MemoryPromptRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The prompt-facing half of memory retrieval: it never sees a fact the caller may not read, never renders a fact the
 * current message did not select, and returns an empty string — "use the ordinary memory block" — whenever anything
 * is missing, stale, disabled or broken.
 *
 * <p>Order of decisions, which is the whole safety argument:
 *
 * <ol>
 *   <li>The caller's own visibility switch ({@link MemoryAudit#visible}) runs first, so a hidden fact is never
 *       embedded, cached or scored for that caller.
 *   <li>The query and every fact text pass through the memory redactor before the backend sees them.
 *   <li>The derived index is keyed by a digest of the owner, the visibility partition, the covered facts' revisions,
 *       the backend version and the width; a change in any of them is a miss and a rebuild.
 *   <li>A rebuild is published only when the facts it was built from are still the facts in the book and nothing
 *       invalidated the cache meanwhile; a forget or an owner switch during the build drops it.
 *   <li>Selection is bounded by top-k and by a character cap, and the block is rendered from the selected facts only.
 * </ol>
 */
public final class MemoryRetrieval {
    /** Why a prompt carries the memory block it does, so a caller can tell "nothing matches" from "no index". */
    public enum Status {
        /** Retrieval is disabled or has no settings; the caller renders the ordinary book. */
        OFF,
        /** The backend, index or cache could not answer; the caller renders the ordinary book. */
        UNAVAILABLE,
        /** The index answered and nothing reached the relevance floor. */
        NO_MATCH,
        /** Facts were selected and rendered. */
        SELECTED
    }

    /** One lookup: the status above and the rendered block (empty unless {@link Status#SELECTED}). */
    public record Result(Status status, String text) {
        public static final Result OFF = new Result(Status.OFF, "");
        public static final Result UNAVAILABLE = new Result(Status.UNAVAILABLE, "");
        public static final Result NO_MATCH = new Result(Status.NO_MATCH, "");

        public Result {
            status = status == null ? Status.UNAVAILABLE : status;
            text = text == null ? "" : text;
        }

        public boolean selected() {
            return status == Status.SELECTED;
        }
    }

    public static final int MIN_TOP_K = 1;
    public static final int MAX_TOP_K = 8;

    /** The only shipped backend. It runs in-process and needs no model file, network call or dependency. */
    private static final Embedder LEXICAL = new LexicalEmbedder();

    /**
     * What the prompt asks for.
     *
     * @param enabled whether retrieval replaces the full memory block
     * @param topK the most facts one block carries, 1..8
     * @param maxChars the most characters of fact text one block carries
     * @param limits the book limits used by the fullness line
     * @param compactAtPercent the fill share at which the fullness line marks a book full
     */
    public record Settings(boolean enabled, int topK, int maxChars, MemoryLimits limits, int compactAtPercent) {
        public Settings(boolean enabled, int topK, int maxChars) {
            this(enabled, topK, maxChars, MemoryLimits.DEFAULTS, 90);
        }

        public Settings {
            // ServerConfig.validate is the real gate; this is the last line of defence for a hand-built value.
            topK = Math.max(MIN_TOP_K, Math.min(MAX_TOP_K, topK));
            maxChars = Math.max(1, maxChars);
            limits = limits == null ? MemoryLimits.DEFAULTS : limits;
            compactAtPercent = Math.max(1, Math.min(100, compactAtPercent));
        }

        /** Nothing is retrieved, and the caller renders the ordinary book. */
        public static Settings off() {
            return new Settings(false, 5, 600);
        }
    }

    private MemoryRetrieval() {}

    /**
     * The memory block for one turn, or "" when the ordinary renderer should be used instead.
     *
     * @param query the player's text for this turn; private, so it is redacted before the backend sees it
     */
    public static String prompt(
            MemoryBook book,
            UUID owner,
            Caller caller,
            String query,
            Settings settings,
            RetrievalCache cache,
            UnaryOperator<String> redactor) {
        return lookup(book, owner, caller, query, settings, cache, redactor).text();
    }

    /** The same path as {@link #prompt}, reporting why a block is or is not present. */
    public static Result lookup(
            MemoryBook book,
            UUID owner,
            Caller caller,
            String query,
            Settings settings,
            RetrievalCache cache,
            UnaryOperator<String> redactor) {
        return lookup(book, owner, caller, query, settings, cache, redactor, LEXICAL);
    }

    /** The rendered block with an explicit backend, as {@link #prompt} without one. */
    static String prompt(
            MemoryBook book,
            UUID owner,
            Caller caller,
            String query,
            Settings settings,
            RetrievalCache cache,
            UnaryOperator<String> redactor,
            Embedder embedder) {
        return lookup(book, owner, caller, query, settings, cache, redactor, embedder)
                .text();
    }

    /** The same path with an explicit backend, so a test can make one fail or forget a fact mid-build. */
    static Result lookup(
            MemoryBook book,
            UUID owner,
            Caller caller,
            String query,
            Settings settings,
            RetrievalCache cache,
            UnaryOperator<String> redactor,
            Embedder embedder) {
        if (settings == null) {
            return Result.UNAVAILABLE;
        }
        if (!settings.enabled()) {
            return Result.OFF;
        }
        if (book == null || owner == null || caller == null || cache == null || embedder == null) {
            return Result.UNAVAILABLE;
        }
        try {
            UnaryOperator<String> clean = redactor == null ? text -> text : redactor;
            List<Fact> visible = MemoryAudit.visible(book, owner, caller);
            if (visible.isEmpty()) {
                return Result.NO_MATCH;
            }
            List<RetrievalIndex.Source> sources = sources(visible);
            List<String> redacted = redactedTexts(sources, clean);
            long facts = RetrievalIndex.digest(sources);
            long mask = RetrievalDigest.of(redacted);
            RetrievalCache.Key key = new RetrievalCache.Key(
                    owner.toString(), partition(caller), facts, mask, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS);
            cache.prune(owner.toString(), key.partition(), facts, mask);
            long epoch = cache.captureEpoch();
            boolean ownerScope = ownerScope(owner, caller);
            RetrievalIndex index = reuse(book, owner, cache, key, facts, mask, ownerScope);
            if (index == null) {
                // The exact texts the key's mask was computed from, so the index and the key cannot disagree about the
                // redaction rules its vectors were built under.
                index = RetrievalIndex.build(
                        sources, redacted, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS, embedder);
                if (index == null) {
                    return Result.UNAVAILABLE;
                }
                if (!stillCurrent(book, owner, caller, facts)) {
                    // A forget or an edit landed while the vectors were being built: the result is about facts that
                    // are gone, so it is dropped instead of published.
                    return Result.UNAVAILABLE;
                }
                if (!cache.publish(epoch, key, index)) {
                    // An owner switch (or an explicit invalidation) passed this build; never publish across it.
                    return Result.UNAVAILABLE;
                }
                if (ownerScope) {
                    book.putRetrievalIndex(owner, index);
                }
            }
            // Only the query loses its function words: the fact vectors keep every token, so the persisted index and
            // its cache key are untouched, and a message with nothing to look up selects nothing.
            String content = clean.apply(query == null ? "" : query);
            if (MemoryRetriever.contentTokens(content).isEmpty()) {
                // Nothing to look up: an event-only turn or a bare politeness line must not rank the whole book.
                return Result.NO_MATCH;
            }
            float[] queryVector;
            try {
                queryVector = embedder.embed(content);
            } catch (RuntimeException failed) {
                return Result.UNAVAILABLE;
            }
            if (queryVector == null || queryVector.length != index.dimensions()) {
                return Result.UNAVAILABLE;
            }
            List<MemoryRetriever.Candidate> selected = MemoryRetriever.rank(
                    candidates(visible),
                    content,
                    queryVector,
                    index.vectors(),
                    settings.topK(),
                    settings.maxChars(),
                    clean);
            if (selected.isEmpty()) {
                return Result.NO_MATCH;
            }
            List<Fact> selectedFacts = factsById(visible, selected);
            if (selectedFacts.isEmpty()) {
                return Result.NO_MATCH;
            }
            return new Result(
                    Status.SELECTED,
                    MemoryPromptRenderer.renderSelection(
                            book,
                            owner,
                            caller,
                            settings.limits(),
                            settings.compactAtPercent(),
                            selectedFacts,
                            settings.maxChars(),
                            clean));
        } catch (RuntimeException failed) {
            // A broken backend, a malformed index or an inconsistent book is never worth a failed turn.
            return Result.UNAVAILABLE;
        }
    }

    /** The cached or persisted index for this exact key, or null when one has to be built. */
    private static RetrievalIndex reuse(
            MemoryBook book,
            UUID owner,
            RetrievalCache cache,
            RetrievalCache.Key key,
            long facts,
            long mask,
            boolean ownerScope) {
        RetrievalIndex cached = cache.get(key).orElse(null);
        if (cached != null) {
            return cached;
        }
        if (!ownerScope) {
            // Only the owner's own full-book index is persisted; a guest's subset stays in this session's memory.
            return null;
        }
        RetrievalIndex persisted = book.retrievalIndex(owner).orElse(null);
        if (persisted == null || !persisted.matches(facts, mask, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS)) {
            return null;
        }
        if (!cache.publish(cache.captureEpoch(), key, persisted)) {
            return null;
        }
        return persisted;
    }

    /** Whether the caller's visible facts — and therefore the index's scope — are unchanged. */
    private static boolean stillCurrent(MemoryBook book, UUID owner, Caller caller, long facts) {
        return RetrievalIndex.digest(sources(MemoryAudit.visible(book, owner, caller))) == facts;
    }

    /** True only for the companion's own owner: the one principal whose index is worth persisting. */
    private static boolean ownerScope(UUID owner, Caller caller) {
        return caller.access() == AccessLevel.OWNER && Objects.equals(owner, caller.uuid());
    }

    /**
     * A name for the caller's visibility partition. It is part of the cache key, so two principals can never share a
     * slot even when they happen to read the same facts.
     */
    private static String partition(Caller caller) {
        return caller.access().name() + ":" + (caller.uuid() == null ? "" : caller.uuid());
    }

    private static List<RetrievalIndex.Source> sources(List<Fact> facts) {
        List<RetrievalIndex.Source> sources = new ArrayList<>(Math.min(facts.size(), RetrievalIndex.MAX_ENTRIES));
        for (Fact fact : facts) {
            if (sources.size() == RetrievalIndex.MAX_ENTRIES) {
                break;
            }
            sources.add(new RetrievalIndex.Source(fact.id(), fact.text()));
        }
        return sources;
    }

    /** The redactor's output for every source, in the same order, so one pass feeds both the key and the vectors. */
    private static List<String> redactedTexts(List<RetrievalIndex.Source> sources, UnaryOperator<String> redactor) {
        List<String> texts = new ArrayList<>(sources.size());
        for (RetrievalIndex.Source source : sources) {
            texts.add(redactor.apply(source.text()));
        }
        return texts;
    }

    private static List<MemoryRetriever.Candidate> candidates(List<Fact> facts) {
        List<MemoryRetriever.Candidate> candidates = new ArrayList<>(facts.size());
        for (Fact fact : facts) {
            candidates.add(new MemoryRetriever.Candidate(fact.id(), fact.text(), true));
        }
        return candidates;
    }

    private static List<Fact> factsById(List<Fact> facts, List<MemoryRetriever.Candidate> selected) {
        List<Fact> ordered = new ArrayList<>(selected.size());
        for (MemoryRetriever.Candidate candidate : selected) {
            for (Fact fact : facts) {
                if (fact.id() == candidate.id()) {
                    ordered.add(fact);
                    break;
                }
            }
        }
        return ordered;
    }
}
