package dev.kinora.mc.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/** Drawing helpers shared by Kinora's screens. */
public final class Ui {
    private Ui() {}

    public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, Theme.panel());
        g.outline(x0, y0, x1 - x0, y1 - y0, Theme.border());
    }

    /** A keyframe marker: a diamond of half-size {@code r} centred on (cx, cy). */
    public static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    /** Text cut with an ellipsis to fit {@code maxWidth}. */
    public static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ellipsis))) + ellipsis;
    }

    /** Seconds as m:ss.cc (centiseconds). */
    public static String seconds(double s) {
        double v = Math.max(0, s);
        long whole = (long) v;
        int cs = (int) Math.round((v - whole) * 100);
        if (cs == 100) {
            whole++;
            cs = 0;
        }
        return String.format(Locale.ROOT, "%d:%02d.%02d", whole / 60, whole % 60, cs);
    }

    /** Seconds as HH:MM:SS:FF timecode at a frame rate. */
    public static String timecode(double s, double fps) {
        long frames = Math.round(Math.max(0, s) * fps);
        long f = frames % Math.max(1, Math.round(fps));
        long total = (long) Math.floor(frames / fps);
        return String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60, f);
    }

    public static boolean inside(double x, double y, int x0, int y0, int x1, int y1) {
        return x >= x0 && x < x1 && y >= y0 && y < y1;
    }

    /** A "nice" ruler step for {@code pixelsPerSecond}, so labels are at least ~50 px apart. */
    public static double rulerStep(double pixelsPerSecond) {
        double[] steps = {0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600};
        for (double s : steps) {
            if (s * pixelsPerSecond >= 50) {
                return s;
            }
        }
        return 1200;
    }
}
