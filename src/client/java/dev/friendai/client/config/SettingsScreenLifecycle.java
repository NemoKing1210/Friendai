package dev.friendai.client.config;

import dev.friendai.infrastructure.config.SettingsSession;

/** Keeps an editing session alive across temporary YACL screens and explicit rebuilds. */
final class SettingsScreenLifecycle {
    private final SettingsSession session;
    private boolean replacing;

    SettingsScreenLifecycle(SettingsSession session) {
        this.session = session;
    }

    void removed(boolean popupVisible) {
        if (!replacing && !popupVisible) session.close();
    }

    void replace(Runnable showScreen) {
        boolean previous = replacing;
        replacing = true;
        try {
            showScreen.run();
        } finally {
            replacing = previous;
        }
    }
}
