package dev.friendai.features.memory.retrieval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One owner's derived retrieval index, persisted inside that owner's memory record (format 5). It is <b>not a source
 * of truth</b>: every entry carries the id of the fact it was built from and a stable digest of that fact's text, and
 * the index carries the digest of every id/revision pair it covers. An add, edit or forget moves that digest, so the
 * index is stale and the next prompt rebuilds it instead of scoring a fact that no longer exists.
 *
 * <p>Only numbers are persisted — bucket weights, never fact text — and the vector was built from the redacted text,
 * so a secret that was masked before the index was built cannot be recovered from it.
 *
 * <p>A newer format stays readable by an older build (unknown JSON is ignored) but a newer build refuses an index of
 * an older {@link #FORMAT_VERSION}, which is a miss and a rebuild rather than a guess.
 */
public record RetrievalIndex(
        int version, String backend, int dimensions, long facts, long masked, List<Entry> entries) {
    /** Version of this derived shape; a change invalidates a stored index instead of reinterpreting it. */
    public static final int FORMAT_VERSION = 1;

    /** The most facts one index covers; a larger book simply leaves the oldest beyond the cap unretrieved. */
    public static final int MAX_ENTRIES = 192;

    /** One fact as the index builder sees it: the id and the exact stored text its revision comes from. */
    public record Source(int id, String text) {
        public Source {
            if (id < 0) {
                throw new IllegalArgumentException("id");
            }
            text = text == null ? "" : text;
        }
    }

    /** One fact's vector and the text digest it is valid for. */
    public record Entry(int id, long revision, float[] vector) {
        public Entry {
            if (id < 0) {
                throw new IllegalArgumentException("id");
            }
            vector = vector == null ? new float[0] : vector.clone();
        }

        @Override
        public float[] vector() {
            return vector.clone();
        }

        /** Value equality, because a record's default compares arrays by reference and a rebuilt vector is a new one. */
        @Override
        public boolean equals(Object other) {
            return other instanceof Entry entry
                    && id == entry.id
                    && revision == entry.revision
                    && Arrays.equals(vector, entry.vector);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, revision, Arrays.hashCode(vector));
        }
    }

    public RetrievalIndex {
        backend = backend == null ? "" : backend;
        dimensions = Math.max(0, dimensions);
        entries = copyEntries(entries, dimensions);
    }

    /** The stable text revision a vector is tied to; the same digest the cache key counts. */
    public static long revision(String text) {
        return RetrievalDigest.of(text);
    }

    /**
     * The digest of the covered ids and text revisions, ascending by id. It changes on add, edit and forget, which is
     * the only invalidation the index needs: no caller has to remember to drop anything.
     */
    public static long digest(List<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0L;
        }
        List<Source> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparingInt(Source::id));
        long[] mixed = new long[sorted.size() * 2];
        for (int i = 0; i < sorted.size(); i++) {
            mixed[i * 2] = sorted.get(i).id();
            mixed[i * 2 + 1] = revision(sorted.get(i).text());
        }
        return RetrievalDigest.of(mixed);
    }

    /**
     * Embeds one already-redacted text per source, oldest id first, up to {@link #MAX_ENTRIES}. The caller redacts
     * exactly once and passes the same list it used to compute the cache key, so the index and its key can never
     * disagree about which mask the vectors were built under.
     *
     * @param redactedTexts one text per source, in the same order; the redactor output for that source
     * @return the index, or null when the backend cannot answer — the caller then falls back to the ordinary renderer
     *     rather than showing a partial book
     */
    public static RetrievalIndex build(
            List<Source> sources, List<String> redactedTexts, String backend, int dimensions, Embedder embedder) {
        if (sources == null
                || sources.isEmpty()
                || redactedTexts == null
                || redactedTexts.size() != sources.size()
                || embedder == null
                || dimensions < 1) {
            return null;
        }
        List<Integer> order = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(index -> sources.get(index).id()));
        if (order.size() > MAX_ENTRIES) {
            order = order.subList(0, MAX_ENTRIES);
        }
        List<Source> covered = new ArrayList<>(order.size());
        List<Entry> entries = new ArrayList<>(order.size());
        List<String> coveredTexts = new ArrayList<>(order.size());
        for (int index : order) {
            Source source = sources.get(index);
            String text = redactedTexts.get(index) == null ? "" : redactedTexts.get(index);
            float[] vector;
            try {
                vector = embedder.embed(text);
            } catch (RuntimeException failed) {
                return null;
            }
            if (vector == null || vector.length != dimensions) {
                return null;
            }
            covered.add(source);
            coveredTexts.add(text);
            entries.add(new Entry(source.id(), revision(source.text()), vector));
        }
        if (entries.isEmpty()) {
            return null;
        }
        return new RetrievalIndex(
                FORMAT_VERSION,
                backend,
                dimensions,
                digest(covered),
                RetrievalDigest.of(coveredTexts),
                List.copyOf(entries));
    }

    /** Whether this index was built for exactly these facts, by this backend, at this width, under this mask. */
    public boolean matches(long facts, long masked, String backend, int dimensions) {
        return this.facts == facts
                && this.masked == masked
                && this.dimensions == dimensions
                && this.backend.equals(backend == null ? "" : backend)
                && isUsable();
    }

    /** Whether the index covers the facts whose digest it recorded. */
    public boolean matchesFacts(long facts) {
        return this.facts == facts && isUsable();
    }

    /** Whether the stored shape is internally sound; a corrupt or half-written index is a miss, not a crash. */
    public boolean isUsable() {
        return version == FORMAT_VERSION && dimensions > 0 && !entries.isEmpty();
    }

    /** Id to vector, for the ranker; copied so a caller cannot mutate the index. */
    public Map<Integer, float[]> vectors() {
        Map<Integer, float[]> vectors = new LinkedHashMap<>();
        for (Entry entry : entries) {
            vectors.put(entry.id(), entry.vector());
        }
        return vectors;
    }

    /** Drops entries that cannot belong to this index, keeping ascending id order and the entry cap. */
    private static List<Entry> copyEntries(List<Entry> entries, int dimensions) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<Entry> copied = new ArrayList<>(Math.min(entries.size(), MAX_ENTRIES));
        for (Entry entry : entries) {
            if (entry == null || entry.id() < 0 || entry.vector().length != dimensions) {
                continue;
            }
            copied.add(entry);
            if (copied.size() == MAX_ENTRIES) {
                break;
            }
        }
        copied.sort(Comparator.comparingInt(Entry::id));
        return Collections.unmodifiableList(copied);
    }
}
