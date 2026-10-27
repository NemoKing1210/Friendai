package dev.friendai.client.ui;

import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.RequestType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The companion menu: its status and actions for everyone, log and debug tools for operators.
 * It subscribes to the server's snapshot stream while it is open.
 */
public final class CompanionControlScreen extends Screen implements MenuTab.Host {
    private static final int MAX_WIDTH = 560;
    private static final int PANEL_PAD = 10;

    private record Entry(String titleKey, MenuTab tab) {}

    private final Screen parent;
    private final Entry overview = new Entry("friendai.menu.tab.overview", new ControlTab());
    private final Entry toggles = new Entry("friendai.menu.tab.toggles", new TogglesTab());
    private final Entry tasks = new Entry("friendai.menu.tab.tasks", new TasksTab());
    private final Entry memory = new Entry("friendai.menu.tab.memory", new MemoryTab());
    private final Entry places = new Entry("friendai.menu.tab.places", new PlacesTab());
    private final Entry appearance = new Entry("friendai.menu.tab.appearance", new AppearanceTab());
    private final Entry traders = new Entry("friendai.menu.tab.traders", new TradersTab());
    private final Entry log = new Entry("friendai.menu.tab.log", new LogTab());
    private final Entry debug = new Entry("friendai.menu.tab.debug", new DebugTab());

    private Entry selected = overview;
    private String builtFor = "";
    private int left;
    private int top;
    private int contentWidth;
    private int contentHeight;
    private int tabBarBottom;
    private List<Entry> shownTabs = List.of();

    public CompanionControlScreen(Screen parent) {
        super(Component.translatable("friendai.menu.title"));
        this.parent = parent;
    }

    @Override
    public void added() {
        CompanionClientState.reset();
        CompanionRequests.send(RequestType.SUBSCRIBE);
    }

    @Override
    public void removed() {
        CompanionRequests.send(RequestType.UNSUBSCRIBE);
        CompanionRequests.trade(dev.friendai.net.TradeRequestC2SPayload.Action.CLOSE,
                CompanionClientState.tradeView().map(view -> view.queryId().toString()).orElse("00000000-0000-0000-0000-000000000000"),
                "", "{}", 0, 0, "");
        if (selected == memory) {
            CompanionRequests.send(RequestType.MEMORY_CLOSE);
        }
        if (selected == places) {
            CompanionRequests.send(RequestType.PLACES_CLOSE);
        }
    }

    /**
     * The menu is live: the server only sends snapshots and answers requests while it ticks, and a pausing screen
     * freezes the integrated server, which left every tab empty and every button dead.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
        return addRenderableWidget(widget);
    }

    @Override
    public net.minecraft.client.gui.Font font() {
        return font;
    }

    private boolean operator() {
        return CompanionClientState.snapshot().map(CompanionSnapshot::operator).orElse(false);
    }

    /** What the built widgets depend on; a change means the layout has to be rebuilt. */
    private String layoutKey() {
        return operator() + "|" + CompanionClientState.snapshot().map(s -> String.valueOf(s.toolNames())).orElse("");
    }

    private List<Entry> visibleTabs() {
        List<Entry> tabs = new ArrayList<>(List.of(overview, toggles, tasks, memory, places, appearance, traders));
        if (operator()) {
            tabs.add(log);
            tabs.add(debug);
        }
        return tabs;
    }

    private void select(Entry entry) {
        if (entry == selected) {
            return;
        }
        if (selected == memory) {
            CompanionRequests.send(RequestType.MEMORY_CLOSE);
        }
        if (selected == places) {
            CompanionRequests.send(RequestType.PLACES_CLOSE);
        }
        if (selected == traders) CompanionRequests.trade(dev.friendai.net.TradeRequestC2SPayload.Action.CLOSE,
                CompanionClientState.tradeView().map(view -> view.queryId().toString()).orElse("00000000-0000-0000-0000-000000000000"),
                "", "{}", 0, 0, "");
        selected = entry;
        if (entry == memory) {
            CompanionRequests.send(RequestType.MEMORY_OPEN);
        }
        if (entry == places) {
            CompanionRequests.send(RequestType.PLACES_OPEN);
        }
        if (entry == traders) ((TradersTab) entry.tab()).open();
        rebuildWidgets();
    }

    @Override
    protected void init() {
        builtFor = layoutKey();
        shownTabs = visibleTabs();
        if (!shownTabs.contains(selected)) {
            selected = overview;
        }
        contentWidth = Math.min(width - 2 * (PANEL_PAD + 4), MAX_WIDTH);
        left = (width - contentWidth) / 2;

        // Nine tabs do not fit in one row at small GUI scales, so the bar wraps into as many rows as it needs.
        TabBarLayout.Bar bar = TabBarLayout.arrange(shownTabs.size(), contentWidth, left, 24);
        tabBarBottom = 24 + bar.height();
        for (int i = 0; i < shownTabs.size(); i++) {
            Entry entry = shownTabs.get(i);
            TabBarLayout.Slot slot = bar.slots().get(i);
            addRenderableWidget(new FlatButton(slot.x(), slot.y(), bar.tabWidth(), TabBarLayout.TAB_HEIGHT,
                    Component.translatable(entry.titleKey()), Ui.ACCENT, () -> select(entry))
                    .asTab(entry == selected));
        }
        top = tabBarBottom + 10;
        contentHeight = height - top - 36 - PANEL_PAD;

        addRenderableWidget(new FlatButton(width / 2 - 60, height - 28, 120, 20,
                CommonComponents.GUI_DONE, Ui.ACCENT, this::onClose));
        selected.tab().init(this, left, top, contentWidth, contentHeight);
    }

    @Override
    public void tick() {
        if (!builtFor.equals(layoutKey())) {
            rebuildWidgets();
            return;
        }
        selected.tab().tick();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return selected.tab().scroll(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int panelLeft = left - PANEL_PAD;
        int panelTop = 6;
        Ui.panel(g, panelLeft, panelTop, contentWidth + 2 * PANEL_PAD, height - 36 - panelTop);
        g.text(font, title, left, 12, Ui.TEXT);
        drawConnection(g);
        g.fill(left, tabBarBottom - 1, left + contentWidth, tabBarBottom, Ui.BORDER);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        selected.tab().render(g, font, left, top, contentWidth, contentHeight, mouseX, mouseY);
    }

    private void drawConnection(GuiGraphicsExtractor g) {
        boolean connected = CompanionRequests.serverSupported() && !CompanionClientState.isStale();
        Component label = Component.translatable(connected ? "friendai.menu.connected" : "friendai.menu.no_data");
        int color = connected ? Ui.GOOD : Ui.BAD;
        int x = left + contentWidth - font.width(label);
        g.text(font, label, x, 12, color);
        Ui.dot(g, x - 8, 14, 4, color);
    }
}
