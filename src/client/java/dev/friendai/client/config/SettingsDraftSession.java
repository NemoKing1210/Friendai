package dev.friendai.client.config;

import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.config.SettingsSession;
import dev.isxander.yacl3.api.Option;
import java.util.ArrayList;
import java.util.List;

/**
 * The open settings session and its unsaved draft. A rebuild reuses the same session and carries the widget
 * values into it; a fresh open closes the old one. The revision makes an answer started against an older
 * screen drop itself, the pending list is what a rebuild flushes into the draft, and nothing here is
 * persisted until Save.
 */
final class SettingsDraftSession {
    private SettingsSession session;
    private SettingsScreenLifecycle lifecycle;
    private long nextGeneration;
    private long screenRevision;
    private List<? extends Option<?>> pendingOptions = List.of();
    private boolean draftChanged;
    private boolean applying;
    private String lastSaveProblem;
    private final List<Option<?>> uiOptions = new ArrayList<>();

    SettingsSession session() {
        return session;
    }

    SettingsScreenLifecycle lifecycle() {
        return lifecycle;
    }

    /**
     * Closes whatever was open and starts a fresh session with its own generation, revision and empty draft.
     * The view preferences (search, filter, profile name, key reveal) belong to the screen, not to the draft.
     */
    SettingsSession open(ClientConfig client, ServerConfig server, boolean serverReadOnly) {
        if (session != null) session.close();
        pendingOptions = List.of();
        draftChanged = false;
        session = SettingsSession.open(++nextGeneration, client, server, serverReadOnly);
        lifecycle = new SettingsScreenLifecycle(session);
        return session;
    }

    long generation() {
        return session == null ? -1 : session.generation();
    }

    boolean current(long generation) {
        return session != null && session.current(generation);
    }

    long revision() {
        return screenRevision;
    }

    /** Every rebuild advances the revision, so an answer started against an older screen is dropped. */
    void nextRevision() {
        screenRevision++;
    }

    boolean draftChanged() {
        return draftChanged;
    }

    boolean applying() {
        return applying;
    }

    /** Registers a widget that only drives the screen: it neither arms Save nor marks a draft as edited. */
    void uiOption(Option<?> option) {
        uiOptions.add(option);
    }

    /** A fresh navigation tab replaces the previous tab's screen-only widgets. */
    void clearUiOptions() {
        uiOptions.clear();
    }

    /** The widgets of the screen just built, in the order a rebuild writes them back. */
    void collectPending(List<? extends Option<?>> options) {
        pendingOptions = options;
    }

    String lastSaveProblem() {
        return lastSaveProblem;
    }

    void lastSaveProblem(String problem) {
        this.lastSaveProblem = problem;
    }

    /** Writes every widget's pending value into the config, so a rebuild can read it back. */
    void applyPending() {
        boolean previous = applying;
        applying = true;
        try {
            for (Option<?> option : pendingOptions) {
                // Search, the Basic/Advanced switch and the profile/transfer fields only drive the screen:
                // their pending values are written back, but they never arm Save or mark a draft as edited.
                if (option.changed() && !uiOptions.contains(option)) draftChanged = true;
                option.applyValue();
            }
        } finally {
            applying = previous;
        }
    }
}
