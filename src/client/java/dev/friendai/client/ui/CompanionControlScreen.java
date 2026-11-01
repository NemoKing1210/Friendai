package dev.friendai.client.ui;

import dev.friendai.client.companion.session.CompanionClientSession;
import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.presentation.companion.CompanionViewState;
import dev.friendai.presentation.layout.MenuTheme;
import dev.friendai.presentation.layout.SideNavLayout;
import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.memory.MemoryRequest;
import dev.friendai.protocol.places.PlaceRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The companion menu: a window with a navigation rail of tabs down its left side, painted with the game's own
 * window, widget sprites and text colours ({@link MenuTheme}). Its status and actions are for everyone, the log
 * and debug tools are operator-only and last on the rail. It subscribes to the server's snapshot stream while it
 * is open.
 */
public final class CompanionControlScreen extends Screen implements MenuTab.Host {
    private static final int RAIL_STEP = 18;
    private static final int DONE_WIDTH = 120;
    private static final int DONE_HEIGHT = 20;

    private record Entry(String titleKey, MenuTab tab) {}

    private final Screen parent;
    private final CompanionClientSession session;
    private final CompanionViewState viewState;
    private final Entry overview = new Entry("friendai.menu.tab.overview", new ControlTab());
    private final Entry feeling = new Entry("friendai.menu.tab.feeling", new FeelingTab());
    private final Entry settings = new Entry("friendai.menu.tab.settings", new SettingsTab());
    private final Entry tasks = new Entry("friendai.menu.tab.tasks", new TasksTab());
    private final Entry memory = new Entry("friendai.menu.tab.memory", new MemoryTab());
    private final Entry skills = new Entry("friendai.menu.tab.skills", new SkillsTab());
    private final Entry places = new Entry("friendai.menu.tab.places", new PlacesTab());
    private final Entry appearance = new Entry("friendai.menu.tab.appearance", new AppearanceTab());
    private final Entry traders = new Entry("friendai.menu.tab.traders", new TradersTab());
    private final Entry log = new Entry("friendai.menu.tab.log", new LogTab());
    private final Entry debug = new Entry("friendai.menu.tab.debug", new DebugTab());

    private final List<Entry> ownerTabs =
            List.of(overview, feeling, tasks, memory, skills, places, appearance, traders);
    private final List<Entry> operatorTabs = List.of(log, debug);
    private final List<Entry> lastTabs = List.of(settings);
    private final List<FlatButton> railButtons = new ArrayList<>();

    private Entry selected = overview;
    private String builtFor = "";
    private SideNavLayout.Layout layout;
    private List<Entry> shownTabs = List.of();
    private int railScroll;

    public CompanionControlScreen(Screen parent, CompanionClientSession session) {
        super(Component.translatable("friendai.menu.title"));
        this.parent = parent;
        this.session = Objects.requireNonNull(session, "session");
        this.viewState = session.viewState();
        for (Entry entry : ownerTabs) ((StatefulMenuTab) entry.tab()).bind(viewState);
        for (Entry entry : operatorTabs) ((StatefulMenuTab) entry.tab()).bind(viewState);
        for (Entry entry : lastTabs) ((StatefulMenuTab) entry.tab()).bind(viewState);
    }

    @Override
    public void added() {
        viewState.reset();
        CompanionRequests.send(new ControlRequest.Subscribe());
        if (selected == memory) CompanionRequests.send(new MemoryRequest.Open());
        if (selected == skills || selected == memory) SkillsTab.open();
    }

    @Override
    public void removed() {
        CompanionRequests.send(new ControlRequest.Unsubscribe());
        CompanionRequests.skills("close", "{}");
        CompanionRequests.trade(
                TradeAction.CLOSE,
                viewState
                        .tradeView()
                        .map(view -> view.queryId().toString())
                        .orElse("00000000-0000-0000-0000-000000000000"),
                "",
                "{}",
                0,
                0,
                "");
        if (selected == memory) {
            CompanionRequests.send(new MemoryRequest.Close());
        }
        if (selected == places) {
            CompanionRequests.send(new PlaceRequest.Close());
        }
    }

    /**
     * The menu is live: the server only sends snapshots and answers requests while it ticks, and a pausing screen
     * freezes the integrated server, which left every tab empty and every button dead. The companion keeps working
     * while the menu is open.
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
        return viewState.snapshot().map(CompanionSnapshot::operator).orElse(false);
    }

    /** What the built widgets depend on; a change means the layout has to be rebuilt. */
    private String layoutKey() {
        return operator() + "|"
                + viewState.snapshot().map(s -> String.valueOf(s.toolNames())).orElse("");
    }

    private List<Entry> visibleTabs() {
        List<Entry> tabs = new ArrayList<>(ownerTabs);
        if (operator()) {
            tabs.addAll(operatorTabs);
        }
        tabs.addAll(lastTabs);
        return tabs;
    }

    /** The first operator entry's index on the rail, or -1 when the operator group is hidden. */
    private int operatorStart() {
        return operator() ? ownerTabs.size() : -1;
    }

    private void select(Entry entry) {
        if (entry == selected) {
            return;
        }
        if (selected == memory) {
            CompanionRequests.send(new MemoryRequest.Close());
        }
        if (selected == places) {
            CompanionRequests.send(new PlaceRequest.Close());
        }
        if (selected == traders)
            CompanionRequests.trade(
                    TradeAction.CLOSE,
                    viewState
                            .tradeView()
                            .map(view -> view.queryId().toString())
                            .orElse("00000000-0000-0000-0000-000000000000"),
                    "",
                    "{}",
                    0,
                    0,
                    "");
        if (selected == skills) CompanionRequests.skills("close", "{}");
        selected = entry;
        if (entry == skills) SkillsTab.open();
        if (entry == memory) {
            SkillsTab.open();
            CompanionRequests.send(new MemoryRequest.Open());
        }
        if (entry == places) {
            CompanionRequests.send(new PlaceRequest.Open());
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
        int operatorStart = operatorStart();
        layout = SideNavLayout.arrange(width, height, shownTabs.size(), operatorStart);
        SideNavLayout.Rail rail = layout.rail();

        railButtons.clear();
        for (int i = 0; i < shownTabs.size(); i++) {
            Entry entry = shownTabs.get(i);
            boolean operatorEntry = operatorStart >= 0 && i >= operatorStart && operatorTabs.contains(entry);
            Component label = Component.translatable(entry.titleKey());
            FlatButton button = new FlatButton(
                            0,
                            0,
                            rail.box().width(),
                            rail.entryHeight(),
                            label,
                            operatorEntry ? Ui.PURPLE : 0,
                            () -> select(entry))
                    .asTab(entry == selected);
            button.setTooltip(Tooltip.create(label));
            railButtons.add(addRenderableWidget(button));
        }
        railScroll = Math.max(0, Math.min(railScroll, rail.maxScroll()));
        positionRail();

        SideNavLayout.Rect content = layout.content();
        selected.tab().init(this, content.x(), content.y(), content.width(), content.height());
        SideNavLayout.Rect window = layout.window();
        addRenderableWidget(new FlatButton(
                window.x() + window.width() / 2 - DONE_WIDTH / 2,
                height - DONE_HEIGHT - 8,
                DONE_WIDTH,
                DONE_HEIGHT,
                CommonComponents.GUI_DONE,
                0,
                this::onClose));
    }

    /**
     * Puts every rail entry at its scrolled position. Entries outside the viewport stay visible and focusable, so
     * Tab can reach them and {@link #followRailFocus()} scrolls them in; they are simply not hit by the pointer.
     */
    private void positionRail() {
        if (layout == null) {
            return;
        }
        SideNavLayout.Rail rail = layout.rail();
        railScroll = Math.max(0, Math.min(railScroll, rail.maxScroll()));
        for (int i = 0; i < railButtons.size(); i++) {
            FlatButton button = railButtons.get(i);
            SideNavLayout.Slot slot = rail.slots().get(i);
            button.setX(slot.x());
            int y = slot.y() - railScroll;
            button.setY(y);
            button.setHitTest(
                    y >= rail.box().y() && y + button.getHeight() <= rail.box().bottom());
        }
    }

    /** Keeps a rail entry reached with the keyboard in view when the rail is scrolled. */
    private void followRailFocus() {
        if (layout == null || layout.rail().maxScroll() == 0) {
            return;
        }
        int index = railButtons.indexOf(getFocused());
        if (index < 0) {
            return;
        }
        SideNavLayout.Slot slot = layout.rail().slots().get(index);
        int top = layout.rail().box().y();
        int bottom = layout.rail().box().bottom();
        int y = slot.y() - railScroll;
        if (y < top) {
            railScroll = slot.y() - top;
        } else if (y + slot.height() > bottom) {
            railScroll = slot.y() + slot.height() - bottom;
        } else {
            return;
        }
        positionRail();
    }

    @Override
    public void tick() {
        if (!builtFor.equals(layoutKey())) {
            rebuildWidgets();
            return;
        }
        followRailFocus();
        selected.tab().tick();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (layout != null
                && layout.rail().maxScroll() > 0
                && inside(layout.rail().box(), mouseX, mouseY)) {
            setFocused(null);
            railScroll -= (int) Math.round(scrollY * RAIL_STEP);
            positionRail();
            return true;
        }
        return selected.tab().scroll(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static boolean inside(SideNavLayout.Rect box, double x, double y) {
        return x >= box.x() && x < box.right() && y >= box.y() && y < box.bottom();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        SideNavLayout.Rect window = layout.window();
        Ui.panel(g, window.x(), window.y(), window.width(), window.height());
        Ui.text(g, font, title, window.x(), 8, MenuTheme.TITLE);
        drawConnection(g, window);

        SideNavLayout.Rect content = layout.content();
        int dividerX = content.x() - SideNavLayout.GAP / 2;
        g.fill(dividerX, content.y(), dividerX + 1, content.y() + content.height(), Ui.BORDER);

        SideNavLayout.Rail rail = layout.rail();
        int separatorY = rail.separatorY() - railScroll;
        if (separatorY >= rail.box().y() && separatorY < rail.box().bottom()) {
            g.fill(rail.box().x(), separatorY, rail.box().right(), separatorY + 1, Ui.BORDER);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Only the rail is clipped to its column; the tab's widgets and Done draw outside it, unclipped.
        SideNavLayout.Rail rail = layout.rail();
        for (GuiEventListener child : children()) {
            if (!(child instanceof Renderable renderable)) {
                continue;
            }
            if (railButtons.contains(renderable)) {
                g.enableScissor(
                        rail.box().x(),
                        rail.box().y(),
                        rail.box().right(),
                        rail.box().bottom());
                renderable.extractRenderState(g, mouseX, mouseY, partialTick);
                g.disableScissor();
            } else {
                renderable.extractRenderState(g, mouseX, mouseY, partialTick);
            }
        }
        SideNavLayout.Rect content = layout.content();
        selected.tab().render(g, font, content.x(), content.y(), content.width(), content.height(), mouseX, mouseY);
    }

    private void drawConnection(GuiGraphicsExtractor g, SideNavLayout.Rect window) {
        boolean connected = CompanionRequests.serverSupported() && !session.isClosed() && !viewState.isStale();
        Component label = Component.translatable(connected ? "friendai.menu.connected" : "friendai.menu.no_data");
        int color = connected ? Ui.GOOD : Ui.BAD;
        int x = window.right() - Ui.badgeWidth(font, label.getString());
        Ui.badge(g, font, label.getString(), x, 6, color);
    }
}
