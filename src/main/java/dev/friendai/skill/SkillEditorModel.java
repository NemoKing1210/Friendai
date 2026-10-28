package dev.friendai.skill;

import java.util.Optional;

/** Pure editor state: failed saves and stale views cannot discard a draft. */
public final class SkillEditorModel {
    private SkillView view;
    private SkillDraft draft;
    private String selected = "";
    private long pendingResponse = -1;

    public void awaitResponse(long id) {
        pendingResponse = id;
    }

    public boolean acceptResponse(SkillView response) {
        if (pendingResponse < 1 || response.responseId() != pendingResponse) return false;
        pendingResponse = -1;
        if (response.ok()) draft = null;
        return true;
    }

    public void onView(SkillView incoming) {
        if (view != null && incoming.revision() < view.revision()) return;
        view = incoming;
        if (draft == null && incoming.detail() != null)
            selected = incoming.detail().id();
    }

    public Optional<SkillView> view() {
        return Optional.ofNullable(view);
    }

    public Optional<SkillDraft> draft() {
        return Optional.ofNullable(draft);
    }

    public boolean dirty() {
        return draft != null;
    }

    public void edit(SkillDraft value) {
        draft = value;
    }

    public void select(String id) {
        selected = id;
        draft = null;
    }

    public String selected() {
        return selected;
    }

    public void revert() {
        draft = null;
    }
}
