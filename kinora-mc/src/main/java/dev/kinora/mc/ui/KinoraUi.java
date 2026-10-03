package dev.kinora.mc.ui;

import dev.kinora.mc.playback.ReplayManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import org.jspecify.annotations.Nullable;

/** Opens Kinora's screens. */
public final class KinoraUi {
    private KinoraUi() {}

    public static void openBrowser(@Nullable Screen parent) {
        Minecraft.getInstance().gui.setScreen(new ReplayBrowserScreen(parent));
    }

    /** The camera and timeline editor, for the open replay. */
    public static void openEditor() {
        if (ReplayManager.INSTANCE.active()) {
            Minecraft.getInstance().gui.setScreen(new EditorScreen());
        }
    }
}
