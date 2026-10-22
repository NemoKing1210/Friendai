package dev.friedai.client.ui;

import dev.friedai.debug.CompanionSnapshot;
import dev.friedai.debug.RequestType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The companion menu: control and status for everyone, log and debug tools for operators.
 * It subscribes to the server's snapshot stream while it is open.
 */
public final class CompanionControlScreen extends Screen implements MenuTab.Host {
    private static final int MAX_WIDTH = 440;
    private static final int TAB_WIDTH = 84;

    private record Entry(String titleKey, MenuTab tab) {}

    private final Screen parent;
    private final Entry control = new Entry("friedai.menu.tab.control", new ControlTab());
    private final Entry tasks = new Entry("friedai.menu.tab.tasks", new TasksTab());
    private final Entry log = new Entry("friedai.menu.tab.log", new LogTab());
    private final Entry debug = new Entry("friedai.menu.tab.debug", new DebugTab());

    private Entry selected = control;
    private String builtFor = "";
    private int left;
    private int top;
    private int contentWidth;
    private int contentHeight;

    public CompanionControlScreen(Screen parent) {
        super(Component.translatable("friedai.menu.title"));
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
        List<Entry> tabs = new ArrayList<>(List.of(control, tasks));
        if (operator()) {
            tabs.add(log);
            tabs.add(debug);
        }
        return tabs;
    }

    @Override
    protected void init() {
        builtFor = layoutKey();
        List<Entry> tabs = visibleTabs();
        if (!tabs.contains(selected)) {
            selected = control;
        }
        contentWidth = Math.min(width - 20, MAX_WIDTH);
        left = (width - contentWidth) / 2;
        top = 56;
        contentHeight = height - top - 34;

        int barLeft = (width - tabs.size() * TAB_WIDTH) / 2;
        for (int i = 0; i < tabs.size(); i++) {
            Entry entry = tabs.get(i);
            Button button = addRenderableWidget(Button.builder(Component.translatable(entry.titleKey()), b -> {
                selected = entry;
                rebuildWidgets();
            }).bounds(barLeft + i * TAB_WIDTH, 24, TAB_WIDTH, 20).build());
            button.active = entry != selected;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 100, height - 26, 200, 20).build());
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
        return selected.tab().scroll(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        if (!CompanionRequests.serverSupported() || CompanionClientState.isStale()) {
            g.centeredText(font, Component.translatable("friedai.menu.no_data"), width / 2, 46, 0xFFFF5555);
        }
        selected.tab().render(g, font, left, top, contentWidth, contentHeight);
    }
}
