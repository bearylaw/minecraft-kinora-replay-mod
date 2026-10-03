package dev.kinora.mc.ui;

import dev.kinora.mc.KinoraRuntimeImpl;
import dev.kinora.mc.ui.kit.Theme;
import dev.kinora.mc.ui.kit.Ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;

/** HUD elements and effects other mods declared through Kinora's API; each can be hidden in replays and renders. */
public final class HideablesScreen extends Screen {
    private final @Nullable Screen parent;

    public HideablesScreen(@Nullable Screen parent) {
        super(Component.translatable("kinora.hideables.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = 36;
        for (var e : KinoraRuntimeImpl.INSTANCE.hideables().entrySet()) {
            String id = e.getKey();
            boolean hidden = KinoraRuntimeImpl.INSTANCE.hiddenByUser(id);
            addRenderableWidget(Button.builder(Component.translatable(hidden ? "kinora.hideables.hidden" : "kinora.hideables.shown",
                    Component.translatable(e.getValue())), b -> {
                KinoraRuntimeImpl.INSTANCE.setHidden(id, !KinoraRuntimeImpl.INSTANCE.hiddenByUser(id));
                clearWidgets();
                init();
            }).bounds(this.width / 2 - 120, y, 240, 20).build());
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(this.width / 2 - 60, y + 8, 120, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.panel(g, this.width / 2 - 130, 8, this.width / 2 + 130, this.height - 8);
        g.centeredText(this.font, this.title, this.width / 2, 16, Theme.text());
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
