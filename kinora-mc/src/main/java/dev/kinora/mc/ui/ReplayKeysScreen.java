package dev.kinora.mc.ui;

import dev.kinora.mc.ui.kit.Theme;
import dev.kinora.mc.ui.kit.Ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Click an action, then press the key for it. Esc while waiting cancels; Backspace clears to the default. */
public final class ReplayKeysScreen extends Screen {
    private final @Nullable Screen parent;
    private ReplayKeys.@Nullable Action waiting;

    public ReplayKeysScreen(@Nullable Screen parent) {
        super(Component.translatable("kinora.keys.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ReplayKeys.Action[] actions = ReplayKeys.Action.values();
        int columns = 2;
        int colWidth = 190;
        int x0 = this.width / 2 - colWidth - 4;
        int y0 = 30;
        int rows = (actions.length + 1) / columns;
        for (int i = 0; i < actions.length; i++) {
            ReplayKeys.Action a = actions[i];
            int x = x0 + (i / rows) * (colWidth + 8);
            int y = y0 + (i % rows) * 20;
            Component key = waiting == a ? Component.literal("> ? <") : ReplayKeys.keyName(a);
            addRenderableWidget(Button.builder(Component.empty().append(a.label()).append(": ").append(key), b -> {
                waiting = a;
                rebuild();
            }).bounds(x, y, colWidth, 18).build());
        }
        int by = y0 + rows * 20 + 10;
        addRenderableWidget(Button.builder(Component.translatable("kinora.keys.reset"), b -> {
            ReplayKeys.reset();
            waiting = null;
            rebuild();
        }).bounds(this.width / 2 - 154, by, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(this.width / 2 + 4, by, 150, 20).build());
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (waiting != null) {
            if (event.key() != GLFW.GLFW_KEY_ESCAPE) {
                ReplayKeys.bind(waiting, event.key());
            }
            waiting = null;
            rebuild();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.panel(g, this.width / 2 - 200, 6, this.width / 2 + 200, this.height - 6);
        g.centeredText(this.font, this.title, this.width / 2, 14, Theme.text());
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (waiting != null) {
            g.centeredText(this.font, Component.translatable("kinora.keys.press", waiting.label()), this.width / 2, this.height - 22, Theme.accent());
        }
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
