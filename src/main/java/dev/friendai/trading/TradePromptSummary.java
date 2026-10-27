package dev.friendai.trading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bounded, owner-private market context for a single prompt; quotes remain evidence, never authority. */
public final class TradePromptSummary {
    public static final int MAX_OFFERS = 8;
    public static final int MAX_LINE_CHARS = 768;
    private static final Pattern WORDS = Pattern.compile("[^\\p{L}\\p{N}_.:-]+");
    private static final Set<String> GENERAL = Set.of(
            "a", "an", "the", "i", "me", "my", "you", "your", "we", "our", "can", "could", "would",
            "should", "do", "does", "is", "are", "buy", "buying", "purchase", "sell", "selling", "trade",
            "trades", "trading", "barter", "restock", "replenish", "price", "prices", "cost", "costs", "offer",
            "offers", "deal", "deals", "for", "from", "with", "to", "what", "where", "which", "how", "much",
            "worth", "this", "that", "these", "those", "have", "villager", "villagers", "trader", "traders", "merchant", "merchants", "житель", "жителя",
            "жители", "торговец", "торговца", "торговцы", "купить", "покупать", "продать", "продавать", "обмен",
            "бартер", "цена", "цену", "сколько", "стоит", "ли", "мне", "мой", "моя", "у", "есть", "какие", "что",
            "сделка", "сделки");
    private static final Set<String> GENERIC_TRADE_TERMS = Set.of(
            "buy", "buying", "purchase", "sell", "selling", "trade", "trades", "trading", "barter", "restock",
            "replenish", "price", "prices", "cost", "costs", "offer", "offers", "deal", "deals", "worth",
            "villager", "villagers", "trader", "traders", "merchant", "merchants", "житель", "жителя", "жители",
            "торговец", "торговца", "торговцы", "купить", "покупать", "продать", "продавать", "обмен", "бартер",
            "цена", "цену", "сколько", "стоит", "ли", "торговля", "сделка", "сделки");
    private static final Set<String> TRADER_TOPICS = Set.of(
            "villager", "villagers", "trader", "traders", "merchant", "merchants", "житель", "жителя", "жители",
            "торговец", "торговца", "торговцы");

    private TradePromptSummary() {}

    /** Returns at most eight offers matching the current request or trader topic. */
    public static String render(TradeBook book, UUID pricingActor, String request, long nowTick) {
        return render(book, pricingActor, request, nowTick, TradeBook.QUOTE_FRESH_TICKS, TradeBook.POSITION_FRESH_TICKS);
    }

    public static String render(TradeBook book, UUID pricingActor, String request, long nowTick,
                                long quoteFreshTicks, long locationFreshTicks) {
        if (book == null || pricingActor == null || request == null || request.isBlank() || nowTick < 0) return "";
        List<String> words = WORDS.splitAsStream(request.toLowerCase(Locale.ROOT))
                .filter(word -> !word.isBlank() && !GENERAL.contains(word)).toList();
        List<String> requestWords = WORDS.splitAsStream(request.toLowerCase(Locale.ROOT))
                .filter(word -> !word.isBlank()).toList();
        boolean broadTradingQuestion = words.isEmpty()
                && requestWords.stream().anyMatch(token -> GENERIC_TRADE_TERMS.contains(token)
                        || TRADER_TOPICS.contains(token));
        if (words.isEmpty() && !broadTradingQuestion) return "";
        var candidates = new ArrayList<Candidate>();
        for (var trader : book.snapshot().traders()) {
            for (var offer : trader.offers()) {
                Candidate candidate = new Candidate(trader, offer,
                        staleQuote(trader, offer, pricingActor, nowTick, quoteFreshTicks),
                        stalePosition(trader, nowTick, locationFreshTicks));
                if (broadTradingQuestion || matches(candidate, words)) candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparing(Candidate::quoteStale)
                .thenComparing(candidate -> candidate.offer().exhausted())
                .thenComparing(Comparator.comparingLong((Candidate candidate) -> candidate.offer().observedTick()).reversed())
                .thenComparing(candidate -> candidate.trader().id())
                .thenComparing(candidate -> candidate.offer().ref()));
        if (candidates.isEmpty()) return "";
        return candidates.stream().limit(MAX_OFFERS).map(TradePromptSummary::line)
                .map(TradePromptSummary::boundedLine)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static boolean matches(Candidate candidate, List<String> words) {
        StringBuilder haystack = new StringBuilder(candidate.trader().entityType()).append(' ')
                .append(candidate.trader().profession()).append(' ').append(candidate.trader().label()).append(' ')
                .append(candidate.offer().output().itemId()).append(' ')
                .append(candidate.offer().output().componentsJson());
        for (var input : candidate.offer().effectiveInputs()) {
            haystack.append(' ').append(input.itemId()).append(' ').append(input.predicateJson());
        }
        String searchable = haystack.toString().toLowerCase(Locale.ROOT).replace('_', ' ');
        return words.stream().anyMatch(searchable::contains);
    }

    private static boolean staleQuote(TraderRecord trader, TradeOffer offer, UUID actor, long nowTick, long freshTicks) {
        return trader.status() != TraderRecord.Status.VERIFIED || !actor.equals(offer.pricingActor())
                || offer.observedTick() < 0 || nowTick < offer.observedTick()
                || nowTick - offer.observedTick() >= freshTicks;
    }

    private static boolean stalePosition(TraderRecord trader, long nowTick, long freshTicks) {
        return nowTick < trader.lastSeen() || nowTick - trader.lastSeen() >= freshTicks;
    }

    private static String line(Candidate candidate) {
        var offer = candidate.offer();
        String inputs = offer.effectiveInputs().stream()
                .map(input -> input.count() + " " + input.itemId() + " predicate=" + input.predicateJson())
                .collect(java.util.stream.Collectors.joining(" + "));
        return (candidate.trader().label().isBlank() ? candidate.trader().entityType() : candidate.trader().label())
                + " [" + candidate.trader().id() + "] " + offer.ref() + ": " + inputs + " -> "
                + offer.output().count() + " " + offer.output().itemId() + " components="
                + offer.output().componentsJson() + "; quote=" + (candidate.quoteStale() ? "stale" : "fresh, live check required")
                + "; location=" + (candidate.positionStale() ? "stale last seen" : "last seen")
                + "; stock=" + (offer.exhausted() ? "exhausted" : (offer.maxUses() - offer.uses()) + " remaining");
    }

    private static String boundedLine(String line) {
        if (line.length() <= MAX_LINE_CHARS) return line;
        int end = line.offsetByCodePoints(0, MAX_LINE_CHARS - 1);
        return line.substring(0, end) + "…";
    }

    private record Candidate(TraderRecord trader, TradeOffer offer, boolean quoteStale, boolean positionStale) {}
}
