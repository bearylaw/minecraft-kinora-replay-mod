package dev.kinora.mc.ui;

import dev.kinora.mc.playback.ReplayManager;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/** What Esc opens inside a replay, in place of the game's pause menu. */
public final class ReplayMenuScreen extends Screen {
    public ReplayMenuScreen() {
        super(Component.translatable("kinora.menu.title"));
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 102;
        int y = this.height / 4 + 24;
        addRenderableWidget(Button.builder(Component.translatable("kinora.menu.resume"), b -> onClose()).bounds(x, y, 204, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("kinora.menu.editor"), b -> KinoraUi.openEditor())
                .bounds(x, y + 24, 204, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("kinora.menu.settings"), b -> ModList.get().getModContainerById("kinora")
                .ifPresent(c -> this.minecraft.gui.setScreen(new ConfigurationScreen(c, this)))).bounds(x, y + 48, 204, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("kinora.menu.keys"), b -> this.minecraft.gui.setScreen(new ReplayKeysScreen(this)))
                .bounds(x, y + 72, 204, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("kinora.menu.clip"), b -> this.minecraft.gui.setScreen(new ClipScreen(this)))
                .bounds(x, y + 96, 204, 20).build());
        if (!dev.kinora.mc.KinoraRuntimeImpl.INSTANCE.hideables().isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("kinora.menu.hideables"), b -> this.minecraft.gui.setScreen(new HideablesScreen(this)))
                    .bounds(x, y + 120, 204, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("kinora.menu.exit"), b -> ReplayManager.INSTANCE.close())
                .bounds(x, y + 156, 204, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 4, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
