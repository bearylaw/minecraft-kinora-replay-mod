package dev.kinora.mc;

import dev.kinora.mc.capture.RecordingManager;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** The small "REC" indicator shown while recording live. */
public final class KinoraHud {
    private KinoraHud() {}

    static void renderRecording(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        RecordingManager recording = RecordingManager.INSTANCE;
        if (!recording.isRecording()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        long seconds = recording.recordingTicks() / 20;
        String time = String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        Component label = Component.translatable("kinora.hud.recording", time);
        int width = mc.font.width(label) + 14;
        int x = graphics.guiWidth() - width - 4;
        int y = 4;
        graphics.fill(x, y, x + width, y + 13, 0x90000000);
        boolean blink = (System.currentTimeMillis() / 600) % 2 == 0;
        graphics.fill(x + 4, y + 4, x + 9, y + 9, blink ? 0xFFE53935 : 0xFF7A1F1D);
        graphics.text(mc.font, label, x + 12, y + 3, 0xFFFFFFFF, false);
    }
}
