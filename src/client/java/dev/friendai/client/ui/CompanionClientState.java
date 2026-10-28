package dev.friendai.client.ui;

import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.LogBuffer;
import dev.friendai.features.memory.MemoryView;
import dev.friendai.features.places.PlaceView;
import dev.friendai.features.trading.TradeView;
import java.util.Optional;

/** What the companion menu knows: the latest snapshot, the accumulated log and the last fetched prompt. */
public final class CompanionClientState {
    private static final long STALE_AFTER_MS = 3000;
    private static final LogBuffer LOG = new LogBuffer(500);

    private static volatile CompanionSnapshot snapshot;
    private static volatile long receivedAt;
    private static volatile String prompt = "";
    private static volatile MemoryView memoryView;
    private static volatile PlaceView placeView;
    private static volatile TradeView tradeView;

    private CompanionClientState() {}

    public static void onSnapshot(CompanionSnapshot received) {
        LOG.append(received.newLog());
        snapshot = received;
        receivedAt = System.currentTimeMillis();
    }

    public static void onPrompt(String received) {
        prompt = received;
    }

    public static void onMemoryView(MemoryView received) {
        memoryView = received;
    }

    public static void onPlaceView(PlaceView received) {
        placeView = received;
    }

    public static void onTradeView(TradeView received) {
        tradeView = received;
    }

    public static Optional<TradeView> tradeView() {
        return Optional.ofNullable(tradeView);
    }

    public static void clearTradeView() {
        tradeView = null;
    }

    public static Optional<CompanionSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public static String prompt() {
        return prompt;
    }

    public static Optional<MemoryView> memoryView() {
        return Optional.ofNullable(memoryView);
    }

    public static Optional<PlaceView> placeView() {
        return Optional.ofNullable(placeView);
    }

    public static LogBuffer log() {
        return LOG;
    }

    /** True when nothing has arrived recently, e.g. the server stopped answering. */
    public static boolean isStale() {
        return snapshot == null || System.currentTimeMillis() - receivedAt > STALE_AFTER_MS;
    }

    private static dev.friendai.features.instructions.SkillView skillsView;
    private static dev.friendai.features.instructions.SkillView skillsResponse;

    public static Optional<dev.friendai.features.instructions.SkillView> skillsResponse() {
        return Optional.ofNullable(skillsResponse);
    }

    public static Optional<dev.friendai.features.instructions.SkillView> skillsView() {
        return Optional.ofNullable(skillsView);
    }

    public static void onSkillsView(dev.friendai.features.instructions.SkillView view) {
        if (view.responseId() > 0 && (skillsResponse == null || view.responseId() >= skillsResponse.responseId()))
            skillsResponse = view;
        if (skillsView != null && view.detail() != null && view.rows().isEmpty() && view.page() == skillsView.page()) {
            view = new dev.friendai.features.instructions.SkillView(
                    view.enabled(),
                    view.revision(),
                    view.page(),
                    view.total(),
                    skillsView.rows(),
                    view.detail(),
                    view.description(),
                    view.ownerProfile(),
                    view.statusKey(),
                    view.ok(),
                    view.exportText(),
                    view.responseId());
        }
        if (skillsView == null || view.revision() >= skillsView.revision()) skillsView = view;
    }

    public static void reset() {
        skillsView = null;
        skillsResponse = null;
        snapshot = null;
        prompt = "";
        memoryView = null;
        placeView = null;
        tradeView = null;
        LOG.clear();
    }
}
