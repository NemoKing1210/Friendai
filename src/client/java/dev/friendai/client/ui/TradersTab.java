package dev.friendai.client.ui;

import dev.friendai.features.trading.TradeOffer;
import dev.friendai.features.trading.TraderRecord;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Owner's bounded market notebook and explicit partner inspection controls. */
final class TradersTab extends StatefulMenuTab {
    private static final int PAD = 8;
    private static final int GAP = 4;
    private static final int ROW = 18;
    private static final int ROW_STEP = ROW + 2;
    private static final int ORDER_WIDTH = 64;
    private static final int SEARCH_WIDTH = 70;
    private static final int DIRECTION_WIDTH = 80;
    /** Room the page number needs beside the paging pair before all five bottom buttons share one row. */
    private static final int PAGE_LABEL_ROOM = 60;

    private String queryId = UUID.randomUUID().toString();
    private UUID selectedTrader;
    private int page;
    private EditBox itemFilter;
    private EditBox orderJson;
    private int direction;
    private int listLeft;
    private int listTop;
    private int listBottom;
    private int pageRowY;
    private int pageLabelCenter;
    private boolean splitPageRow;

    void open() {
        queryId = UUID.randomUUID().toString();
        page = 0;
        CompanionRequests.trade(TradeAction.SUBSCRIBE, queryId, "", "{}", direction, page, "");
    }

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        int content = Math.max(1, width - 2 * PAD);
        int right = left + width - PAD;

        // The filter row holds the field and Search only: at the smallest window a third button would sit on top of
        // the field, so the buy/sell toggle moves down to the order row.
        int searchWidth = Math.min(SEARCH_WIDTH, Math.max(44, content - GAP - 60));
        int filterWidth = Math.max(40, content - GAP - searchWidth);
        itemFilter = host.add(new EditBox(
                host.font(),
                left + PAD,
                top + 2,
                filterWidth,
                ROW,
                Component.translatable("friendai.menu.traders.filter")));
        itemFilter.setMaxLength(128);
        host.add(new FlatButton(
                right - searchWidth,
                top + 2,
                searchWidth,
                ROW,
                Component.translatable("friendai.menu.traders.search"),
                Ui.ACCENT,
                this::query));

        int orderY = top + 2 + ROW_STEP;
        host.add(new FlatButton(
                left + PAD,
                orderY,
                DIRECTION_WIDTH,
                ROW,
                Component.translatable(direction == 0 ? "friendai.menu.traders.buy" : "friendai.menu.traders.sell"),
                Ui.ACCENT,
                () -> {
                    direction = 1 - direction;
                    query();
                }));
        int jsonX = left + PAD + DIRECTION_WIDTH + GAP;
        orderJson = host.add(new EditBox(
                host.font(),
                jsonX,
                orderY,
                Math.max(40, right - jsonX),
                ROW,
                Component.translatable("friendai.menu.traders.order_json")));
        orderJson.setMaxLength(8000);
        orderJson.setValue("{}");

        // The order actions: four to a row when the column holds them, two rows of two when it does not.
        int columns = content >= 4 * ORDER_WIDTH + 3 * GAP ? 4 : 2;
        int actionsY = orderY + ROW_STEP;
        host.add(new FlatButton(
                gridX(left, 0, columns),
                gridY(actionsY, 0, columns),
                ORDER_WIDTH,
                ROW,
                Component.translatable("friendai.menu.traders.preview"),
                Ui.ACCENT,
                () -> orderAction(TradeAction.PREVIEW)));
        host.add(new FlatButton(
                gridX(left, 1, columns),
                gridY(actionsY, 1, columns),
                ORDER_WIDTH,
                ROW,
                Component.translatable("friendai.menu.traders.submit"),
                Ui.GOOD,
                () -> orderAction(TradeAction.SUBMIT)));
        host.add(new FlatButton(
                gridX(left, 2, columns),
                gridY(actionsY, 2, columns),
                ORDER_WIDTH,
                ROW,
                Component.translatable("friendai.menu.traders.wait"),
                Ui.ACCENT,
                () -> orderAction(TradeAction.WAIT)));
        host.add(new FlatButton(
                gridX(left, 3, columns),
                gridY(actionsY, 3, columns),
                ORDER_WIDTH,
                ROW,
                Component.translatable("friendai.menu.traders.barter"),
                Ui.ACCENT,
                () -> orderAction(TradeAction.BARTER)));
        int orderRows = (4 + columns - 1) / columns;
        listLeft = left;
        listTop = actionsY + orderRows * ROW_STEP + 2;

        // The bottom actions: one row of five when the column holds them plus the page number; otherwise paging and
        // the number above, and Inspect / Refresh / Forget below.
        int pageWidth = width < 360 ? 54 : 56;
        int rowGap = width < 360 ? 4 : 6;
        pageRowY = top + height - ROW - 2;
        splitPageRow = 5 * pageWidth + 4 * rowGap + PAGE_LABEL_ROOM > content;
        int actionsRowY = splitPageRow ? pageRowY - ROW_STEP : pageRowY;
        int previousX = left + PAD;
        int nextX = previousX + pageWidth + rowGap;
        int actionWidth = splitPageRow ? (content - 2 * rowGap) / 3 : pageWidth;
        int inspectX = splitPageRow ? left + PAD : right - 3 * pageWidth - 2 * rowGap;
        int refreshX = inspectX + actionWidth + rowGap;
        int forgetX = refreshX + actionWidth + rowGap;
        pageLabelCenter = (nextX + pageWidth + inspectX) / 2;
        listBottom = Math.max(listTop + 1, actionsRowY - 2);
        host.add(new HitArea(
                listLeft, listTop, content, Math.max(1, listBottom - listTop), (x, y) -> selectRow((int) y - listTop)));
        host.add(new FlatButton(
                previousX,
                pageRowY,
                pageWidth,
                ROW,
                Component.translatable("friendai.menu.traders.previous"),
                Ui.ACCENT,
                () -> turnPage(-1)));
        host.add(new FlatButton(
                nextX,
                pageRowY,
                pageWidth,
                ROW,
                Component.translatable("friendai.menu.traders.next"),
                Ui.ACCENT,
                () -> turnPage(1)));
        host.add(new FlatButton(
                inspectX,
                actionsRowY,
                actionWidth,
                ROW,
                Component.translatable("friendai.menu.traders.inspect"),
                Ui.ACCENT,
                () -> action(TradeAction.INSPECT)));
        host.add(new FlatButton(
                refreshX,
                actionsRowY,
                actionWidth,
                ROW,
                Component.translatable("friendai.menu.traders.refresh"),
                Ui.ACCENT,
                () -> action(TradeAction.REFRESH)));
        host.add(new FlatButton(
                forgetX,
                actionsRowY,
                actionWidth,
                ROW,
                Component.translatable("friendai.menu.traders.forget"),
                Ui.BAD,
                () -> action(TradeAction.FORGET)));
    }

    private static int gridX(int left, int index, int columns) {
        return left + PAD + (index % columns) * (ORDER_WIDTH + GAP);
    }

    private static int gridY(int top, int index, int columns) {
        return top + (index / columns) * ROW_STEP;
    }

    private void query() {
        String item = itemFilter == null ? "" : itemFilter.getValue().strip();
        page = 0;
        CompanionRequests.trade(TradeAction.QUERY, queryId, item, "{}", direction, page, "");
    }

    private void turnPage(int delta) {
        var view = state.tradeView().orElse(null);
        if (view == null || !view.queryId().toString().equals(queryId)) return;
        page = Math.max(0, Math.min(view.totalPages() - 1, page + delta));
        query();
    }

    private void action(TradeAction action) {
        if (selectedTrader == null) return;
        CompanionRequests.trade(action, queryId, "", "{}", direction, page, selectedTrader.toString());
    }

    private void orderAction(TradeAction action) {
        if (orderJson == null || orderJson.getValue().length() > 8000) return;
        CompanionRequests.trade(
                action,
                queryId,
                "",
                orderJson.getValue(),
                direction,
                page,
                selectedTrader == null ? "" : selectedTrader.toString());
    }

    private void selectRow(int relativeY) {
        var view = state.tradeView().orElse(null);
        if (view == null || !view.queryId().toString().equals(queryId)) return;
        int y = 0;
        for (TraderRecord trader : view.traders()) {
            if (relativeY >= y && relativeY < y + 12) {
                selectedTrader = trader.id();
                return;
            }
            y += 15 + trader.offers().size() * 11;
        }
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        var view = state.tradeView().orElse(null);
        // The notebook is clipped to its own rectangle: a long trader line can never paint over the rail or the
        // buttons under the list.
        g.enableScissor(listLeft, listTop, left + width, listBottom);
        int y = listTop;
        if (view == null || !view.queryId().toString().equals(queryId)) {
            Ui.text(g, font, Component.translatable("friendai.menu.traders.loading"), left + 10, y, Ui.DIM);
            g.disableScissor();
            return;
        }
        if (view.traders().isEmpty()) {
            Ui.text(g, font, Component.translatable("friendai.menu.traders.empty"), left + 10, y, Ui.DIM);
            g.disableScissor();
            return;
        }
        long now = net.minecraft.client.Minecraft.getInstance().level == null
                ? view.serverTick()
                : net.minecraft.client.Minecraft.getInstance().level.getGameTime();
        if (selectedTrader == null
                || view.traders().stream().noneMatch(row -> row.id().equals(selectedTrader)))
            selectedTrader = view.traders().getFirst().id();
        int limit = listBottom - font.lineHeight;
        for (TraderRecord trader : view.traders()) {
            String label = trader.label().isBlank() ? trader.profession() : trader.label();
            long traderAge = Math.max(0, now - trader.lastSeen());
            Ui.text(
                    g,
                    font,
                    Component.literal(label + "  " + trader.dimension() + " " + trader.x() + ", "
                            + trader.y() + ", " + trader.z() + "  L" + trader.level() + "  " + trader.status()
                            + "  seen " + traderAge + "t ago"),
                    left + 10,
                    y,
                    trader.id().equals(selectedTrader) ? Ui.ACCENT : Ui.TEXT);
            y += 12;
            for (TradeOffer offer : trader.offers()) {
                if (!view.offers().isEmpty()
                        && view.offers().stream().noneMatch(value -> value.ref().equals(offer.ref()))) continue;
                String inputs = offer.effectiveInputs().stream()
                        .map(c -> c.count() + "× " + c.itemId())
                        .reduce((a, b) -> a + " + " + b)
                        .orElse("?");
                String line = inputs + "  →  " + offer.output().count() + "× "
                        + offer.output().itemId()
                        + "  " + offer.uses() + "/" + offer.maxUses()
                        + "  "
                        + (Math.max(0, now - offer.observedTick()) <= view.quoteFreshTicks() ? "current" : "stale")
                        + (offer.output().componentsJson().equals("{}")
                                ? ""
                                : "  " + offer.output().componentsJson());
                Ui.text(g, font, Component.literal(line), left + 18, y, Ui.DIM);
                y += 11;
                if (y > limit) break;
            }
            y += 3;
            if (y > limit) break;
        }
        g.disableScissor();
        String pageLabel = (view.page() + 1) + " / " + view.totalPages();
        int labelY = pageRowY + (ROW - font.lineHeight) / 2 + 1;
        int labelX =
                splitPageRow ? left + width - PAD - font.width(pageLabel) : pageLabelCenter - font.width(pageLabel) / 2;
        Ui.text(g, font, Component.literal(pageLabel), labelX, labelY, Ui.DIM);
    }
}
