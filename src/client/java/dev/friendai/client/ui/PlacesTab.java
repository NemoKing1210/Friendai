package dev.friendai.client.ui;

import dev.friendai.common.Components;
import dev.friendai.features.memory.MenuStatus;
import dev.friendai.features.places.PlaceLimits;
import dev.friendai.features.places.PlaceView;
import dev.friendai.presentation.companion.PlacesTabModel;
import dev.friendai.protocol.places.PlaceRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

/** The places tab: every place the companion remembers, with renaming and a guarded delete. */
final class PlacesTab extends StatefulMenuTab {
    private static final int ROW = 20;
    private static final int GAP = 4;
    private static final int LINE = 10;
    private static final int PAD = 8;
    private static final int PLACE_ROW = 30;

    private PlacesTabModel model;
    private final ScrollView placesView = new ScrollView(false);
    private PlaceView lastView;

    private FlatButton rename;
    private FlatButton delete;
    private EditBox renameField;
    private Host host;
    /** One real widget per place row, so Tab reaches every row and the narrator reads it. */
    private final List<FocusRow> placeRows = new ArrayList<>();

    private int headLeft;
    private int headWidth;
    private int listLeft;
    private int listTop;
    private int listWidth;
    private int listHeight;

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        model = state.placesTabModel();
        this.host = host;
        headLeft = left;
        headWidth = width;

        int statusY = top + height - LINE;
        int actionY = statusY - GAP - ROW;
        listLeft = left;
        listTop = top + ROW + GAP;
        listWidth = width;
        listHeight = Math.max(LINE, actionY - GAP - listTop);

        int deleteWidth = 64;
        int renameWidth = 64;
        int deleteX = left + width - deleteWidth;
        int renameX = deleteX - GAP - renameWidth;
        int fieldWidth = Math.max(40, renameX - GAP - left);

        placeRows.clear();
        renameField = host.add(new EditBox(
                host.font(),
                left,
                actionY,
                fieldWidth,
                ROW,
                Component.translatable("friendai.menu.places.rename_hint")));
        renameField.setHint(Component.translatable("friendai.menu.places.rename_hint"));
        renameField.setMaxLength(PlaceLimits.NAME_MAX);
        renameField.setResponder(model::setRenameDraft);
        rename = host.add(new FlatButton(
                renameX,
                actionY,
                renameWidth,
                ROW,
                Component.translatable("friendai.menu.places.rename"),
                Ui.ACCENT,
                this::clickRename));
        delete = host.add(new FlatButton(
                deleteX,
                actionY,
                deleteWidth,
                ROW,
                Component.translatable("friendai.menu.places.delete"),
                Ui.WARN,
                this::clickDelete));
        sync();
        ensureRows();
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        state.placeView().ifPresent(view -> {
            if (view != lastView) {
                lastView = view;
                model.onView(view);
            }
        });
        model.tick(now);
        sync();
        ensureRows();
    }

    private void sync() {
        boolean enabled = model.view().map(PlaceView::enabled).orElse(false);
        long selected = model.selected();
        boolean hasSelection = selected != -1;
        boolean armed = hasSelection && model.pendingDelete() == selected;

        renameField.visible = enabled;
        renameField.active = enabled && hasSelection;
        renameField.setEditable(enabled && hasSelection);
        rename.visible = enabled;
        rename.active = enabled && hasSelection;
        delete.visible = enabled;
        delete.active = enabled && hasSelection;
        delete.setMessage(
                Component.translatable(armed ? "friendai.menu.places.delete_confirm" : "friendai.menu.places.delete"));
        delete.setStatus(armed ? Ui.BAD : Ui.WARN);

        if (!renameField.isFocused() && !renameField.getValue().equals(model.renameDraft())) {
            renameField.setValue(model.renameDraft());
        }
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        if (model.view().isEmpty()) {
            return;
        }
        drawHeader(g, font);
        if (!model.view().get().enabled()) {
            Ui.text(
                    g,
                    font,
                    Component.translatable("friendai.menu.places.disabled"),
                    listLeft + 2,
                    listTop + 4,
                    Ui.DIM);
        } else {
            drawPlaces(g, font);
        }
        drawStatus(g, font, top, height);
    }

    private void drawHeader(GuiGraphicsExtractor g, Font font) {
        int y = topOfHeader(font);
        Ui.text(g, font, Component.translatable("friendai.menu.tab.places"), headLeft, y, Ui.TEXT);
        String count = String.valueOf(model.rows().size());
        Ui.text(g, font, count, headLeft + headWidth - font.width(count), y, Ui.DIM);
    }

    private int topOfHeader(Font font) {
        return listTop - GAP - ROW + (ROW - font.lineHeight) / 2;
    }

    private void drawPlaces(GuiGraphicsExtractor g, Font font) {
        List<PlaceView.Row> places = model.rows();
        if (places.isEmpty()) {
            Ui.text(g, font, Component.translatable("friendai.menu.places.empty"), listLeft + 2, listTop + 4, Ui.DIM);
            return;
        }
        int total = places.size() * (PLACE_ROW + GAP);
        reveal(placeRows, placesView, listTop, listTop + listHeight);
        placesView.begin(g, listLeft, listTop, listWidth, listHeight, total);
        int y = placesView.top();
        int cardWidth = listWidth - 6;
        long selected = model.selected();
        for (int i = 0; i < places.size(); i++) {
            PlaceView.Row place = places.get(i);
            if (placesView.visible(y, PLACE_ROW)) {
                boolean current = place.id() == selected;
                Ui.card(
                        g,
                        listLeft,
                        y,
                        cardWidth,
                        PLACE_ROW,
                        current ? Ui.CARD_HOVER : Ui.CARD,
                        current ? Ui.ACCENT : Ui.BORDER);
                g.fill(listLeft, y, listLeft + 3, y + PLACE_ROW, current ? Ui.ACCENT : Ui.GOOD);

                int x = listLeft + PAD;
                int badgeWidth = Ui.badge(
                        g, font, Component.translatable(place.kindKey()).getString(), x, y + 3, Ui.ACCENT);
                Ui.text(
                        g,
                        font,
                        Ui.clip(font, titleOf(place), Math.max(20, cardWidth - 2 * PAD - badgeWidth - 5)),
                        x + badgeWidth + 5,
                        y + 5,
                        Ui.TEXT);
                String meta = Component.translatable(
                                "friendai.menu.places.meta",
                                place.dimension(),
                                place.x(),
                                place.y(),
                                place.z(),
                                place.visits())
                        .getString();
                meta += distanceLabel(place);
                Ui.text(
                        g,
                        font,
                        Ui.clip(font, meta, Math.max(20, cardWidth - 2 * PAD)),
                        listLeft + PAD,
                        y + 5 + font.lineHeight + 1,
                        Ui.DIM);
            }
            placeRow(i, y);
            y += PLACE_ROW + GAP;
        }
        placesView.end(g);
    }

    /** The row's own text, as drawn and as read by the narrator. */
    private static String titleOf(PlaceView.Row place) {
        return place.label().isBlank() ? place.name() : place.name() + " — " + place.label();
    }

    /** The distance from the local player, but only while both are in the same dimension. */
    private static String distanceLabel(PlaceView.Row place) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || !minecraft.player.level().dimension().identifier().toString().equals(place.dimension())) {
            return "";
        }
        double distance = Math.sqrt(minecraft.player.distanceToSqr(place.x() + 0.5, place.y() + 0.5, place.z() + 0.5));
        return " · "
                + Component.translatable("friendai.menu.places.distance", Math.round(distance))
                        .getString();
    }

    private void drawStatus(GuiGraphicsExtractor g, Font font, int top, int height) {
        Optional<MenuStatus> status = model.status();
        if (status.isEmpty()) {
            return;
        }
        Component message = Components.of(status.get().message());
        int color = status.get().ok() ? Ui.GOOD : Ui.BAD;
        int y = top + height - LINE;
        g.enableScissor(headLeft, y, headLeft + headWidth, y + LINE + 2);
        Ui.text(g, font, message, headLeft, y + 1, color);
        g.disableScissor();
    }

    /**
     * Keeps a row reached with the keyboard inside the list, so Tab can walk every place without a wheel. The row
     * keeps its own top across scrolling; {@link FocusRow#place} clips only what the pointer and the ring see.
     */
    private void reveal(List<FocusRow> widgets, ScrollView view, int viewTop, int viewBottom) {
        if (host == null) {
            return;
        }
        // Only the keyboard pulls a focused row into view: a wheel turn after a click must not be undone.
        if (!Minecraft.getInstance().getLastInputType().isKeyboard()) {
            return;
        }
        GuiEventListener focused = host.focused();
        if (focused == null) {
            return;
        }
        int index = widgets.indexOf(focused);
        if (index < 0) {
            return;
        }
        FocusRow row = widgets.get(index);
        // A row taller than the viewport only scrolls its top in; chasing its bottom would fight the next frame.
        if (row.rowTop() < viewTop) {
            view.nudge(row.rowTop() - viewTop);
        } else if (row.rowHeight() <= viewBottom - viewTop && row.rowBottom() > viewBottom) {
            view.nudge(row.rowBottom() - viewBottom);
        }
    }

    private void placeRow(int index, int y) {
        if (index < placeRows.size()) {
            placeRows.get(index).place(listLeft, y, listWidth, PLACE_ROW, listTop, listTop + listHeight);
        }
    }

    /** Adds one focus widget the first time a place appears; the pool never shrinks, unused rows just go quiet. */
    private void ensureRows() {
        List<PlaceView.Row> places = model.rows();
        while (placeRows.size() < places.size()) {
            int index = placeRows.size();
            placeRows.add(host.add(new FocusRow(Component.empty(), () -> selectPlace(index))
                    .withRingWidth(Math.max(1, listWidth - 6))));
        }
        boolean enabled = model.view().map(PlaceView::enabled).orElse(false);
        for (int i = 0; i < placeRows.size(); i++) {
            FocusRow row = placeRows.get(i);
            boolean used = enabled && i < places.size();
            row.visible = used;
            row.active = used;
            if (used) {
                row.setMessage(Component.literal(titleOf(places.get(i))));
            }
        }
    }

    private void selectPlace(int index) {
        List<PlaceView.Row> places = model.rows();
        if (index < places.size()) {
            model.select(places.get(index).id());
            sync();
        }
    }

    private void clickRename() {
        model.selectedRow().ifPresent(place -> {
            String next = renameField.getValue().strip();
            if (next.isEmpty() || next.equals(place.name())) {
                return;
            }
            CompanionRequests.send(new PlaceRequest.Rename(place.name(), next));
        });
    }

    private void clickDelete() {
        model.selectedRow().ifPresent(place -> {
            if (model.requestDelete(place.id(), System.currentTimeMillis())) {
                CompanionRequests.send(new PlaceRequest.Forget(place.name()));
            }
            sync();
        });
    }

    @Override
    public boolean scroll(double x, double y, double amount) {
        return placesView.contains(x, y) && placesView.scroll(amount);
    }
}
