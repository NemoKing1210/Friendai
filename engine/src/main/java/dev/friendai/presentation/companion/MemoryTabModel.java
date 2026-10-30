package dev.friendai.presentation.companion;

import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.MemoryView;
import dev.friendai.features.memory.MenuStatus;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The memory tab's state: the selected group, the selected fact, the edit draft and the Clear
 * confirmation. Pure Java, so the awkward transitions are unit-tested without a game.
 */
public final class MemoryTabModel {
    public static final long CLEAR_CONFIRM_MILLIS = 3000;

    private MemoryView view;
    private String groupKey;
    private Integer selectedId;
    private Integer editingId;
    private Category draftCategory = Category.PROFILE;
    private String draftText = "";
    private boolean statusVisible;
    private MenuStatus lastStatus;
    private long clearArmedAt = -1;
    private boolean personaSelected;
    private boolean transcriptSelected;
    private long clearTranscriptArmedAt = -1;

    /** Takes a fresh view: keeps the group by key, drops a vanished selection or edit, shows any status. */
    public void onView(MemoryView next) {
        String previousGroup = groupKey;
        view = next;
        boolean known =
                groupKey != null && next.groups().stream().anyMatch(g -> g.key().equals(groupKey));
        if (!known) {
            groupKey = next.groups().isEmpty() ? null : next.groups().get(0).key();
        }
        if (previousGroup != null && !previousGroup.equals(groupKey)) {
            clearArmedAt = -1;
            statusVisible = false;
        }
        if (selectedId != null && next.facts().stream().noneMatch(f -> f.id() == selectedId)) {
            selectedId = null;
        }
        if (editingId != null && next.facts().stream().noneMatch(f -> f.id() == editingId)) {
            cancelEdit();
        }
        if (next.status().isPresent()) {
            lastStatus = next.status().get();
            statusVisible = true;
        }
    }

    public Optional<MemoryView> view() {
        return Optional.ofNullable(view);
    }

    /** The selected group, or empty while the personality or recent-chat pane is shown. */
    public Optional<MemoryView.Group> group() {
        if (view == null || groupKey == null || personaSelected || transcriptSelected) {
            return Optional.empty();
        }
        return view.groups().stream().filter(g -> g.key().equals(groupKey)).findFirst();
    }

    /** Shows the personality pane instead of a group: drops the fact selection, edit, Clear arming and status. */
    public void selectPersona() {
        personaSelected = true;
        transcriptSelected = false;
        selectedId = null;
        cancelEdit();
        clearArmedAt = -1;
        clearTranscriptArmedAt = -1;
        statusVisible = false;
    }

    public boolean personaSelected() {
        return personaSelected;
    }

    /** Shows the recent-chat pane: no fact is selected, edited or cleared. */
    public void selectTranscript() {
        transcriptSelected = true;
        personaSelected = false;
        selectedId = null;
        cancelEdit();
        clearArmedAt = -1;
        clearTranscriptArmedAt = -1;
        statusVisible = false;
    }

    public boolean transcriptSelected() {
        return transcriptSelected;
    }

    /** The recent chat lines the server sent, newest first. */
    public List<MemoryView.TranscriptRow> transcript() {
        return view == null ? List.of() : view.transcript();
    }

    /** First click arms the transcript clear, a second click within the window confirms. */
    public boolean clickClearTranscript(long nowMs) {
        if (!transcriptSelected) {
            return false;
        }
        if (clearTranscriptArmed(nowMs)) {
            clearTranscriptArmedAt = -1;
            return true;
        }
        clearTranscriptArmedAt = nowMs;
        return false;
    }

    public boolean clearTranscriptArmed(long nowMs) {
        return clearTranscriptArmedAt >= 0 && nowMs - clearTranscriptArmedAt <= CLEAR_CONFIRM_MILLIS;
    }

    /** The current group's facts, in id order; none while the personality pane is shown. */
    public List<MemoryView.FactRow> facts() {
        if (view == null || groupKey == null || personaSelected || transcriptSelected) {
            return List.of();
        }
        return view.facts().stream().filter(f -> f.groupKey().equals(groupKey)).toList();
    }

    public void next() {
        cycle(1);
    }

    public void previous() {
        cycle(-1);
    }

    private void cycle(int delta) {
        if (view == null || view.groups().isEmpty()) {
            return;
        }
        List<MemoryView.Group> groups = view.groups();
        int index = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).key().equals(groupKey)) {
                index = i;
                break;
            }
        }
        index = Math.floorMod(index + delta, groups.size());
        personaSelected = false;
        transcriptSelected = false;
        groupKey = groups.get(index).key();
        selectedId = null;
        cancelEdit();
        clearArmedAt = -1;
        statusVisible = false;
    }

    /** Switches to the group with this key; a no-op for the current or an unknown group. */
    public void selectGroup(String key) {
        if (view == null || view.groups().stream().noneMatch(g -> g.key().equals(key))) {
            return;
        }
        if (transcriptSelected) {
            transcriptSelected = false;
            groupKey = key;
            selectedId = null;
            clearArmedAt = -1;
            statusVisible = false;
            return;
        }
        if (personaSelected) {
            personaSelected = false;
            groupKey = key;
            selectedId = null;
            statusVisible = false;
            return;
        }
        if (key.equals(groupKey)) {
            return;
        }
        groupKey = key;
        selectedId = null;
        cancelEdit();
        clearArmedAt = -1;
        statusVisible = false;
    }

    public void select(int factId) {
        selectedId = factId;
    }

    public OptionalInt selected() {
        return selectedId == null ? OptionalInt.empty() : OptionalInt.of(selectedId);
    }

    /** Copies the selected fact into the draft. False when there is no selection. */
    public boolean startEdit() {
        if (view == null || selectedId == null) {
            return false;
        }
        Optional<MemoryView.FactRow> fact =
                view.facts().stream().filter(f -> f.id() == selectedId).findFirst();
        if (fact.isEmpty()) {
            return false;
        }
        editingId = selectedId;
        draftCategory = fact.get().category();
        draftText = fact.get().text();
        return true;
    }

    public void cancelEdit() {
        editingId = null;
        draftText = "";
    }

    public OptionalInt editing() {
        return editingId == null ? OptionalInt.empty() : OptionalInt.of(editingId);
    }

    public Category draftCategory() {
        return draftCategory;
    }

    public String draftText() {
        return draftText;
    }

    public void setDraft(Category category, String text) {
        draftCategory = category == null ? Category.PROFILE : category;
        draftText = text == null ? "" : text;
    }

    /** First click arms, a second click within {@link #CLEAR_CONFIRM_MILLIS} confirms; later clicks re-arm. */
    public boolean clickClear(long nowMs) {
        if (group().isEmpty()) {
            return false;
        }
        if (clearArmed(nowMs)) {
            clearArmedAt = -1;
            return true;
        }
        clearArmedAt = nowMs;
        return false;
    }

    public boolean clearArmed(long nowMs) {
        return clearArmedAt >= 0 && nowMs - clearArmedAt <= CLEAR_CONFIRM_MILLIS;
    }

    public boolean statusVisible() {
        return statusVisible;
    }

    /**
     * The status to render, or empty while the status line is hidden. The last status is kept across the
     * periodic refreshes that carry no status, so a message stays readable for its full lifetime.
     */
    /** Returns this model to its unbound state when the owning connection closes or resets. */
    public void reset() {
        view = null;
        groupKey = null;
        selectedId = null;
        editingId = null;
        draftCategory = Category.PROFILE;
        draftText = "";
        statusVisible = false;
        lastStatus = null;
        clearArmedAt = -1;
        personaSelected = false;
        transcriptSelected = false;
        clearTranscriptArmedAt = -1;
    }

    public Optional<MenuStatus> status() {
        return statusVisible ? Optional.ofNullable(lastStatus) : Optional.empty();
    }
}
