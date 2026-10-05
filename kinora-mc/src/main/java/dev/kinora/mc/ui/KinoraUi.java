package dev.kinora.mc.ui;

import dev.kinora.mc.playback.ReplayManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

    /** Shows or hides the name tags over players and named mobs, in the replay and in renders. */
    public static void toggleNames() {
        if (!ReplayManager.INSTANCE.active()) {
            return;
        }
        var scene = ReplayManager.INSTANCE.scene();
        scene.toggleNametags();
        dev.kinora.mc.util.Notify.info(Component.translatable("kinora.names.title"),
                Component.translatable(scene.hideNametags() ? "kinora.names.hidden" : "kinora.names.shown", ReplayKeys.keyName(ReplayKeys.Action.NAMES)));
    }
}
