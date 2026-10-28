package dev.friendai.debug;

import dev.friendai.features.memory.MenuStatus;
import dev.friendai.features.places.PlaceView;
import java.util.List;
import java.util.Optional;

/**
 * The places tab's state: the selected place, the rename draft and the Delete confirmation. Pure Java, so the
 * awkward transitions are unit-tested without a game.
 */
public final class PlacesTabModel {
    public static final long DELETE_CONFIRM_MILLIS = 3000;

    private PlaceView view;
    private Long selectedId;
    private String selectedName = "";
    private String renameDraft = "";
    private long deleteArmedId;
    private long deleteArmedAt = -1;
    private MenuStatus lastStatus;

    /** Takes a fresh view: keeps the selection while its place remains, drops it when the place vanished. */
    public void onView(PlaceView next) {
        view = next;
        if (selectedId != null) {
            Optional<PlaceView.Row> selected = row(selectedId);
            if (selected.isEmpty()) {
                clearSelection();
            } else {
                PlaceView.Row row = selected.get();
                if (!row.name().equals(selectedName)) {
                    if (renameDraft.equals(selectedName)) {
                        renameDraft = row.name();
                    }
                    selectedName = row.name();
                }
            }
        }
        if (deleteArmedId != 0 && row(deleteArmedId).isEmpty()) {
            clearDelete();
        }
        if (next.status().isPresent()) {
            lastStatus = next.status().get();
        }
    }

    public Optional<PlaceView> view() {
        return Optional.ofNullable(view);
    }

    /** The places to list, newest first, exactly as the server sent them. */
    public List<PlaceView.Row> rows() {
        return view == null ? List.of() : view.places();
    }

    /** Selects a place by id; an unknown id is ignored. The rename draft starts at the place's name. */
    public void select(long id) {
        Optional<PlaceView.Row> row = row(id);
        if (row.isEmpty()) {
            return;
        }
        if (selectedId == null || selectedId != id) {
            selectedId = id;
            selectedName = row.get().name();
            renameDraft = selectedName;
        }
        clearDelete();
    }

    /** The selected place's id, or {@code -1} when nothing is selected. */
    public long selected() {
        return selectedId == null ? -1 : selectedId;
    }

    public Optional<PlaceView.Row> selectedRow() {
        return selectedId == null ? Optional.empty() : row(selectedId);
    }

    public String renameDraft() {
        return renameDraft;
    }

    public void setRenameDraft(String draft) {
        renameDraft = draft == null ? "" : draft;
    }

    /**
     * First click arms the confirmation, a second click on the same place within
     * {@link #DELETE_CONFIRM_MILLIS} confirms and returns true. A different place re-arms instead.
     */
    public boolean requestDelete(long id, long nowMs) {
        if (row(id).isEmpty()) {
            return false;
        }
        if (deleteArmedId == id && deleteArmed(nowMs)) {
            clearDelete();
            return true;
        }
        deleteArmedId = id;
        deleteArmedAt = nowMs;
        return false;
    }

    /** The place awaiting confirmation, or {@code 0} while the Delete button is unarmed. */
    public long pendingDelete() {
        return deleteArmedId;
    }

    /** Drops an expired confirmation; the arm window is inclusive at {@link #DELETE_CONFIRM_MILLIS}. */
    public void tick(long nowMs) {
        if (deleteArmedId != 0 && !deleteArmed(nowMs)) {
            clearDelete();
        }
    }

    /** The last action's result, kept across the periodic refreshes that carry no status. */
    public Optional<MenuStatus> status() {
        return Optional.ofNullable(lastStatus);
    }

    private boolean deleteArmed(long nowMs) {
        return deleteArmedAt >= 0 && nowMs - deleteArmedAt <= DELETE_CONFIRM_MILLIS;
    }

    private void clearDelete() {
        deleteArmedId = 0;
        deleteArmedAt = -1;
    }

    private void clearSelection() {
        selectedId = null;
        selectedName = "";
        renameDraft = "";
        clearDelete();
    }

    private Optional<PlaceView.Row> row(long id) {
        if (view == null) {
            return Optional.empty();
        }
        return view.places().stream().filter(p -> p.id() == id).findFirst();
    }
}
