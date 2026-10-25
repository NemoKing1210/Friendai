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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The companion menu: control and status for everyone, log and debug tools for operators.
 * It subscribes to the server's snapshot stream while it is open.
 */
public final class CompanionControlScreen extends Screen implements MenuTab.Host {
    private static final int MAX_WIDTH = 480;
    private static final int TAB_HEIGHT = 20;
    private static final int PANEL_PAD = 10;

    private record Entry(String titleKey, MenuTab tab) {}

    private final Screen parent;
    private final Entry control = new Entry("friendai.menu.tab.control", new ControlTab());
    private final Entry tasks = new Entry("friendai.menu.tab.tasks", new TasksTab());
    private final Entry memory = new Entry("friendai.menu.tab.memory", new MemoryTab());
    private final Entry log = new Entry("friendai.menu.tab.log", new LogTab());
    private final Entry debug = new Entry("friendai.menu.tab.debug", new DebugTab());

    private Entry selected = control;
    private String builtFor = "";
    private int left;
    private int top;
    private int contentWidth;
    private int contentHeight;
    private int tabBarLeft;
    private int tabWidth;
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
        if (selected == memory) {
            CompanionRequests.send(RequestType.MEMORY_CLOSE);
        }
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
        List<Entry> tabs = new ArrayList<>(List.of(control, tasks, memory));
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
        selected = entry;
        if (entry == memory) {
            CompanionRequests.send(RequestType.MEMORY_OPEN);
        }
        rebuildWidgets();
    }

    @Override
    protected void init() {
        builtFor = layoutKey();
        shownTabs = visibleTabs();
        if (!shownTabs.contains(selected)) {
            selected = control;
        }
        contentWidth = Math.min(width - 2 * (PANEL_PAD + 4), MAX_WIDTH);
        left = (width - contentWidth) / 2;
        top = 24 + TAB_HEIGHT + 10;
        contentHeight = height - top - 38;

        // Five tabs no longer fit at small GUI scales, so they share the width they have.
        tabWidth = Math.min(contentWidth / shownTabs.size(), 96);
        tabBarLeft = left;
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            Entry hit = tabAt(event.x(), event.y());
            if (hit != null) {
                select(hit);
                return true;
            }
            if (selected.tab().mouseClicked(event.x(), event.y())) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return selected.tab().scroll(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private Entry tabAt(double x, double y) {
        if (y < 24 || y >= 24 + TAB_HEIGHT || x < tabBarLeft || x >= tabBarLeft + tabWidth * shownTabs.size()) {
            return null;
        }
        return shownTabs.get((int) ((x - tabBarLeft) / tabWidth));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int panelLeft = left - PANEL_PAD;
        int panelTop = 6;
        Ui.panel(g, panelLeft, panelTop, contentWidth + 2 * PANEL_PAD, height - 36 - panelTop);
        g.text(font, title, left, 12, Ui.TEXT);
        drawConnection(g);
        drawTabs(g, mouseX, mouseY);
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

    private void drawTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int y = 24;
        g.fill(tabBarLeft, y + TAB_HEIGHT - 1, left + contentWidth, y + TAB_HEIGHT, Ui.BORDER);
        for (int i = 0; i < shownTabs.size(); i++) {
            Entry entry = shownTabs.get(i);
            int x = tabBarLeft + i * tabWidth;
            boolean current = entry == selected;
            boolean hot = tabAt(mouseX, mouseY) == entry;
            if (current) {
                g.fill(x, y, x + tabWidth, y + TAB_HEIGHT - 1, Ui.alpha(Ui.ACCENT, 0x28));
            } else if (hot) {
                g.fill(x, y, x + tabWidth, y + TAB_HEIGHT - 1, Ui.alpha(0xFFFFFF, 0x10));
            }
            Component label = Component.translatable(entry.titleKey());
            String text = Ui.clip(font, label.getString(), tabWidth - 6);
            g.text(font, text, x + (tabWidth - font.width(text)) / 2, y + 6, current ? Ui.TEXT : Ui.DIM);
            if (current) {
                g.fill(x, y + TAB_HEIGHT - 2, x + tabWidth, y + TAB_HEIGHT, Ui.ACCENT);
            }
        }
    }
}
