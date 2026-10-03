package dev.kinora.mc.render;

import dev.kinora.mc.ui.kit.Theme;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Progress drawn over the frame while rendering. The GUI is drawn after the frame is captured, so
 * none of this ends up in the video.
 */
public final class RenderOverlay {
    private RenderOverlay() {}

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        RenderRunner r = RenderRunner.active();
        if (r == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int total = r.unitsTotal();
        int done = r.unitsRendered();
        double fraction = total == 0 ? 1 : done / (double) total;
        double elapsed = r.elapsedNanos() / 1e9;
        String eta = done > 0 && done < total ? clock(elapsed / done * (total - done)) : "-";
        Component line1 = Component.translatable("kinora.render.progress.title", r.job().title);
        Component line2 = Component.translatable("kinora.render.progress.frames", r.framesWritten(), r.job().frameCount,
                String.format(Locale.ROOT, "%.0f%%", fraction * 100), clock(elapsed), eta);
        Component line3 = Component.translatable(r.mixingAudio() ? "kinora.render.progress.audio" : "kinora.render.progress.stop");
        int width = Math.max(mc.font.width(line1), Math.max(mc.font.width(line2), mc.font.width(line3))) + 16;
        // The GUI is laid out for the output size while rendering; small outputs (cube faces) get a smaller box.
        float scale = Math.min(1f, (g.guiWidth() - 16) / (float) width);
        g.pose().pushMatrix();
        g.pose().translate(8, 8);
        g.pose().scale(scale, scale);
        int x = 0;
        int y = 0;
        g.fill(x, y, x + width, y + 48, 0xC0101418);
        g.text(mc.font, line1, x + 8, y + 6, Theme.text(), false);
        g.text(mc.font, line2, x + 8, y + 18, Theme.textDim(), false);
        g.text(mc.font, line3, x + 8, y + 30, Theme.textDim(), false);
        int barY = y + 44;
        g.fill(x, barY, x + width, barY + 4, 0xFF303840);
        g.fill(x, barY, x + (int) Math.round(width * fraction), barY + 4, Theme.accent());
        g.pose().popMatrix();
    }

    private static String clock(double seconds) {
        long s = Math.round(seconds);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
