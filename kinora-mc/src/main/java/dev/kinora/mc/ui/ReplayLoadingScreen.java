package dev.kinora.mc.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown while a replay's world is being built (opening, long seeks). */
public final class ReplayLoadingScreen extends Screen {
    private static final String[] SPINNER = {"|", "/", "-", "\\"};
    private int ticks;

    public ReplayLoadingScreen(Component title) {
        super(title);
    }

    @Override
    public void tick() {
        ticks++;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 10, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal(SPINNER[(ticks / 3) % SPINNER.length]), this.width / 2, this.height / 2 + 6, 0xFFB0B0B0);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
