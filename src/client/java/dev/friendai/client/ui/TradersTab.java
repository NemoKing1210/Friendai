package dev.friendai.client.ui;

import dev.friendai.net.TradeRequestC2SPayload;
import dev.friendai.trading.TradeOffer;
import dev.friendai.trading.TraderRecord;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Owner's bounded market notebook and explicit partner inspection controls. */
final class TradersTab implements MenuTab {
    private String queryId = UUID.randomUUID().toString();
    private UUID selectedTrader;
    private int page;
    private EditBox itemFilter;
    private EditBox orderJson;
    private int direction;

    void open() {
        queryId = UUID.randomUUID().toString();
        page = 0;
        CompanionRequests.trade(TradeRequestC2SPayload.Action.SUBSCRIBE, queryId, "", "{}", direction, page, "");
    }

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        itemFilter = host.add(new EditBox(
                host.font(),
                left + 8,
                top + 2,
                Math.max(110, width - 190),
                18,
                Component.translatable("friendai.menu.traders.filter")));
        itemFilter.setMaxLength(128);
        host.add(new FlatButton(
                left + width - 78,
                top + 2,
                70,
                18,
                Component.translatable("friendai.menu.traders.search"),
                Ui.ACCENT,
                this::query));
        host.add(new FlatButton(
                left + width - 164,
                top + 2,
                80,
                18,
                Component.translatable(direction == 0 ? "friendai.menu.traders.buy" : "friendai.menu.traders.sell"),
                Ui.ACCENT,
                () -> {
                    direction = 1 - direction;
                    query();
                }));
        orderJson = host.add(new EditBox(
                host.font(),
                left + 8,
                top + 24,
                Math.max(100, width - 8),
                18,
                Component.translatable("friendai.menu.traders.order_json")));
        orderJson.setMaxLength(8000);
        orderJson.setValue("{}");
        host.add(new FlatButton(
                left + 8,
                top + 45,
                64,
                18,
                Component.translatable("friendai.menu.traders.preview"),
                Ui.ACCENT,
                () -> orderAction(TradeRequestC2SPayload.Action.PREVIEW)));
        host.add(new FlatButton(
                left + 76,
                top + 45,
                64,
                18,
                Component.translatable("friendai.menu.traders.submit"),
                Ui.GOOD,
                () -> orderAction(TradeRequestC2SPayload.Action.SUBMIT)));
        host.add(new FlatButton(
                left + 144,
                top + 45,
                64,
                18,
                Component.translatable("friendai.menu.traders.wait"),
                Ui.ACCENT,
                () -> orderAction(TradeRequestC2SPayload.Action.WAIT)));
        host.add(new FlatButton(
                left + 212,
                top + 45,
                64,
                18,
                Component.translatable("friendai.menu.traders.barter"),
                Ui.ACCENT,
                () -> orderAction(TradeRequestC2SPayload.Action.BARTER)));
        host.add(new HitArea(
                left + 8, top + 68, width - 16, Math.max(1, height - 96), (x, y) -> selectRow((int) y - top - 68)));
        // Five buttons on one row: paging on the left, the trade actions on the right, compressed when narrow.
        int rowY = top + height - 22;
        int pageWidth = width < 360 ? 54 : 56;
        int rowGap = width < 360 ? 4 : 6;
        int previousX = left + 8;
        int nextX = previousX + pageWidth + rowGap;
        int forgetX = left + width - 8 - pageWidth;
        int refreshX = forgetX - rowGap - pageWidth;
        int inspectX = refreshX - rowGap - pageWidth;
        host.add(new FlatButton(
                previousX,
                rowY,
                pageWidth,
                18,
                Component.translatable("friendai.menu.traders.previous"),
                Ui.ACCENT,
                () -> turnPage(-1)));
        host.add(new FlatButton(
                nextX,
                rowY,
                pageWidth,
                18,
                Component.translatable("friendai.menu.traders.next"),
                Ui.ACCENT,
                () -> turnPage(1)));
        host.add(new FlatButton(
                inspectX,
                rowY,
                pageWidth,
                18,
                Component.translatable("friendai.menu.traders.inspect"),
                Ui.ACCENT,
                () -> action(TradeRequestC2SPayload.Action.INSPECT)));
        host.add(new FlatButton(
                refreshX,
                rowY,
                pageWidth,
                18,
                Component.translatable("friendai.menu.traders.refresh"),
                Ui.ACCENT,
                () -> action(TradeRequestC2SPayload.Action.REFRESH)));
        host.add(new FlatButton(
                forgetX,
                rowY,
                pageWidth,
                18,
                Component.translatable("friendai.menu.traders.forget"),
                Ui.BAD,
                () -> action(TradeRequestC2SPayload.Action.FORGET)));
    }

    private void query() {
        String item = itemFilter == null ? "" : itemFilter.getValue().strip();
        page = 0;
        CompanionRequests.trade(TradeRequestC2SPayload.Action.QUERY, queryId, item, "{}", direction, page, "");
    }

    private void turnPage(int delta) {
        var view = CompanionClientState.tradeView().orElse(null);
        if (view == null || !view.queryId().toString().equals(queryId)) return;
        page = Math.max(0, Math.min(view.totalPages() - 1, page + delta));
        query();
    }

    private void action(TradeRequestC2SPayload.Action action) {
        if (selectedTrader == null) return;
        CompanionRequests.trade(action, queryId, "", "{}", direction, page, selectedTrader.toString());
    }

    private void orderAction(TradeRequestC2SPayload.Action action) {
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
        var view = CompanionClientState.tradeView().orElse(null);
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
        var view = CompanionClientState.tradeView().orElse(null);
        int y = top + 68;
        if (view == null || !view.queryId().toString().equals(queryId)) {
            Ui.text(g, font, Component.translatable("friendai.menu.traders.loading"), left + 10, y, Ui.DIM);
            return;
        }
        if (view.traders().isEmpty()) {
            Ui.text(g, font, Component.translatable("friendai.menu.traders.empty"), left + 10, y, Ui.DIM);
            return;
        }
        long now = net.minecraft.client.Minecraft.getInstance().level == null
                ? view.serverTick()
                : net.minecraft.client.Minecraft.getInstance().level.getGameTime();
        if (selectedTrader == null
                || view.traders().stream().noneMatch(row -> row.id().equals(selectedTrader)))
            selectedTrader = view.traders().getFirst().id();
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
                if (y > top + height - 30) break;
            }
            y += 3;
            if (y > top + height - 30) break;
        }
        Ui.text(
                g,
                font,
                Component.literal((view.page() + 1) + " / " + view.totalPages()),
                left + width / 2 - 10,
                top + height - 18,
                Ui.DIM);
    }
}
