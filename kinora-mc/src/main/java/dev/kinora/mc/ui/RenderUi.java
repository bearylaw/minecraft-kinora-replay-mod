package dev.kinora.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Entry points to the render dialog and the render queue. */
public final class RenderUi {
    private RenderUi() {}

    public static void open(Screen parent) {
        Minecraft.getInstance().gui.setScreen(new RenderScreen(parent));
    }

    public static void openQueue(Screen parent) {
        Minecraft.getInstance().gui.setScreen(new RenderQueueScreen(parent));
    }
}
