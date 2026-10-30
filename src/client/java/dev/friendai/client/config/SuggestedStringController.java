package dev.friendai.client.config;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.dropdown.DropdownStringController;
import dev.isxander.yacl3.gui.controllers.dropdown.DropdownStringControllerElement;
import dev.isxander.yacl3.gui.controllers.dropdown.DropdownWidget;
import java.util.List;
import net.minecraft.client.input.MouseButtonEvent;

/** Allows custom model names while committing an explicitly clicked suggestion. */
final class SuggestedStringController extends DropdownStringController {
    SuggestedStringController(Option<String> option, List<String> suggestions) {
        super(option, suggestions, true, false);
    }

    @Override
    public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> dimension) {
        return new DropdownStringControllerElement(this, screen, dimension) {
            @Override
            public void createDropdownWidget() {
                dropdownVisible = true;
                var element = this;
                dropdownWidget = new DropdownWidget<>(SuggestedStringController.this, screen, getDimension(), this) {
                    @Override
                    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                        if (event.button() == 0 && isMouseOver(event.x(), event.y())) {
                            mouseMoved(event.x(), event.y());
                            int selected = selectedIndex();
                            if (selected >= 0 && selected < matchingValues.size()) {
                                // YACL's default unfocus only accepts suggestions for invalid input.
                                // With free text enabled, even a partial prefix is valid input.
                                option().requestSet(matchingValues.get(selected));
                            }
                            element.unfocus();
                            return true;
                        }
                        return super.mouseClicked(event, doubleClick);
                    }
                };
                screen.addPopupControllerWidget(dropdownWidget);
            }
        };
    }
}
