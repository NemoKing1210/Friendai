package dev.friendai.client.ui;

import dev.friendai.presentation.companion.CompanionViewState;
import java.util.Objects;

/** Base for tabs that read the current connection's presentation state. */
abstract class StatefulMenuTab implements MenuTab {
    protected CompanionViewState state;

    final void bind(CompanionViewState state) {
        this.state = Objects.requireNonNull(state, "state");
    }
}
