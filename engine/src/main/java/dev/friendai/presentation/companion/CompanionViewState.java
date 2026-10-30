package dev.friendai.presentation.companion;

import dev.friendai.features.instructions.SkillView;
import dev.friendai.features.memory.MemoryView;
import dev.friendai.features.places.PlaceView;
import dev.friendai.features.trading.TradeView;
import java.util.Objects;
import java.util.Optional;

/** Immutable server views accumulated for one connection, with presentation selections owned alongside them. */
public final class CompanionViewState {
    public static final long STALE_AFTER_MS = 3_000;

    private final ClientSessionId sessionId;
    private final LogBuffer log = new LogBuffer(500);
    private final MemoryTabModel memoryTabModel = new MemoryTabModel();
    private final PlacesTabModel placesTabModel = new PlacesTabModel();

    private boolean active = true;
    private CompanionSnapshot snapshot;
    private long receivedAt;
    private String prompt = "";
    private MemoryView memoryView;
    private PlaceView placeView;
    private TradeView tradeView;
    private SkillView skillsView;
    private SkillView skillsResponse;

    public CompanionViewState(ClientSessionId sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    /** True only while this state is live and the response belongs to its connection. */
    public boolean accepts(ClientSessionId responseSession) {
        return active && sessionId.equals(responseSession);
    }

    public boolean onSnapshot(ClientSessionId source, CompanionSnapshot received, long receivedAtMillis) {
        if (!accepts(source)) return false;
        Objects.requireNonNull(received, "received");
        log.append(received.newLog());
        snapshot = received;
        receivedAt = receivedAtMillis;
        return true;
    }

    public boolean onPrompt(ClientSessionId source, String received) {
        if (!accepts(source)) return false;
        prompt = received == null ? "" : received;
        return true;
    }

    public boolean onMemoryView(ClientSessionId source, MemoryView received) {
        if (!accepts(source)) return false;
        memoryView = Objects.requireNonNull(received, "received");
        memoryTabModel.onView(received);
        return true;
    }

    public boolean onPlaceView(ClientSessionId source, PlaceView received) {
        if (!accepts(source)) return false;
        placeView = Objects.requireNonNull(received, "received");
        placesTabModel.onView(received);
        return true;
    }

    public boolean onTradeView(ClientSessionId source, TradeView received) {
        if (!accepts(source)) return false;
        tradeView = Objects.requireNonNull(received, "received");
        return true;
    }

    /** Records the latest correlated response and keeps stale/error data from replacing the last good skill view. */
    public boolean onSkillsView(ClientSessionId source, SkillView received) {
        if (!accepts(source)) return false;
        SkillView view = Objects.requireNonNull(received, "received");
        if (view.responseId() > 0 && (skillsResponse == null || view.responseId() >= skillsResponse.responseId())) {
            skillsResponse = view;
        }
        if (!view.ok()) return true;
        if (skillsView != null) {
            if (view.responseId() > 0 && skillsView.responseId() > 0 && view.responseId() < skillsView.responseId())
                return true;
            if (view.revision() < skillsView.revision()) return true;
        }
        if (skillsView != null && view.detail() != null && view.rows().isEmpty() && view.page() == skillsView.page()) {
            view = new SkillView(
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
        skillsView = view;
        return true;
    }

    public Optional<CompanionSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public String prompt() {
        return prompt;
    }

    public Optional<MemoryView> memoryView() {
        return Optional.ofNullable(memoryView);
    }

    public Optional<PlaceView> placeView() {
        return Optional.ofNullable(placeView);
    }

    public Optional<TradeView> tradeView() {
        return Optional.ofNullable(tradeView);
    }

    public Optional<SkillView> skillsView() {
        return Optional.ofNullable(skillsView);
    }

    public Optional<SkillView> skillsResponse() {
        return Optional.ofNullable(skillsResponse);
    }

    public LogBuffer log() {
        return log;
    }

    public MemoryTabModel memoryTabModel() {
        return memoryTabModel;
    }

    public PlacesTabModel placesTabModel() {
        return placesTabModel;
    }

    /** True when no current-session snapshot has arrived recently. */
    public boolean isStale(long nowMillis) {
        return snapshot == null || nowMillis - receivedAt > STALE_AFTER_MS;
    }

    public boolean isStale() {
        return isStale(System.currentTimeMillis());
    }

    /** Clears all view, log and tab selection state while keeping this connection active. */
    public void reset() {
        snapshot = null;
        receivedAt = 0;
        prompt = "";
        memoryView = null;
        placeView = null;
        tradeView = null;
        skillsView = null;
        skillsResponse = null;
        log.clear();
        memoryTabModel.reset();
        placesTabModel.reset();
    }

    /** Closes this connection's state and makes every later callback fail {@link #accepts(ClientSessionId)}. */
    public void close() {
        if (!active) return;
        active = false;
        reset();
    }
}
