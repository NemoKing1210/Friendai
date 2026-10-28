package dev.friendai.features.instructions;
/** Persisted rolling allowance, bounded outcome usage, and monotonic effective wall clock. */
public final class TeachingLedger {
    private final SkillBook book;

    public TeachingLedger(SkillBook book) {
        this.book = book;
    }

    public boolean available(String outcomeId, long now, int hourly, int perOutcome) {
        long time = Math.max(book.clockHighWater, Math.max(0, now));
        long live = book.writes.stream().filter(t -> t > time - 3_600_000L).count();
        return live < hourly && book.outcomes.getOrDefault(outcomeId, 0) < perOutcome;
    }

    public void recordSuccessfulWrite(String outcomeId, long now) {
        long time = Math.max(book.clockHighWater, Math.max(0, now));
        book.clockHighWater = time;
        book.writes.removeIf(t -> t <= time - 3_600_000L);
        book.writes.add(time);
        book.outcomes.merge(outcomeId, 1, Integer::sum);
        while (book.outcomes.size() > 128)
            book.outcomes.remove(book.outcomes.keySet().iterator().next());
        book.revision++;
    }
}
