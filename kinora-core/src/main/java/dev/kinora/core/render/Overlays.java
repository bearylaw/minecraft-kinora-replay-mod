package dev.kinora.core.render;

import dev.kinora.core.project.Shot;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

/**
 * Titles, captions and logos drawn over rendered frames: a shot's {@link Shot.Overlay} list, each
 * visible from {@code start} to {@code end} shot seconds with fades. Text uses the platform's
 * sans-serif (or a named font installed on the system), sized relative to the frame height so it
 * looks the same at any resolution.
 */
public final class Overlays {
    private static final Map<String, BufferedImage> IMAGES = new ConcurrentHashMap<>();

    private Overlays() {}

    /** True when any overlay shows at shot time {@code t}. */
    public static boolean any(List<Shot.Overlay> overlays, double t) {
        for (Shot.Overlay o : overlays) {
            if (t >= o.start && t <= o.end) {
                return true;
            }
        }
        return false;
    }

    /**
     * Draws the overlays visible at shot time {@code t} into an RGBA frame (top row first).
     *
     * @param folder where image overlays' relative paths start (the project folder)
     */
    public static void draw(byte[] rgba, int width, int height, List<Shot.Overlay> overlays, double t, Path folder) {
        for (Shot.Overlay o : overlays) {
            if (t < o.start || t > o.end) {
                continue;
            }
            double alpha = 1;
            if (o.fadeIn > 0) {
                alpha = Math.min(alpha, (t - o.start) / o.fadeIn);
            }
            if (o.fadeOut > 0) {
                alpha = Math.min(alpha, (o.end - t) / o.fadeOut);
            }
            alpha = Math.max(0, Math.min(1, alpha)) * ((o.color >>> 24) / 255.0);
            if (alpha <= 0.002) {
                continue;
            }
            BufferedImage layer = o.image != null && !o.image.isBlank() ? image(o, height, folder) : text(o, height);
            if (layer != null) {
                composite(rgba, width, height, layer, (int) Math.round(o.x * width - layer.getWidth() / 2.0),
                        (int) Math.round(o.y * height - layer.getHeight() / 2.0), alpha);
            }
        }
    }

    private static BufferedImage text(Shot.Overlay o, int frameHeight) {
        if (o.text == null || o.text.isBlank()) {
            return null;
        }
        int size = Math.max(8, (int) Math.round(frameHeight / 18.0 * o.scale));
        String family = o.font == null || o.font.isBlank() || o.font.equals("default") ? Font.SANS_SERIF : o.font;
        Font font = new Font(family, Font.BOLD, size);
        String[] lines = o.text.split("\n");
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        FontMetrics fm = pg.getFontMetrics(font);
        pg.dispose();
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, fm.stringWidth(line));
        }
        int pad = Math.max(2, size / 6);
        int lineHeight = fm.getHeight();
        BufferedImage img = new BufferedImage(textWidth + pad * 2, lineHeight * lines.length + pad * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(font);
        for (int i = 0; i < lines.length; i++) {
            int lx = pad + (textWidth - fm.stringWidth(lines[i])) / 2;
            int ly = pad + fm.getAscent() + i * lineHeight;
            if (o.shadow) {
                g.setColor(new Color(0, 0, 0, 150));
                g.drawString(lines[i], lx + Math.max(1, size / 16), ly + Math.max(1, size / 16));
            }
            g.setColor(new Color(o.color | 0xFF000000, true));
            g.drawString(lines[i], lx, ly);
        }
        g.dispose();
        return img;
    }

    private static BufferedImage image(Shot.Overlay o, int frameHeight, Path folder) {
        BufferedImage source = IMAGES.computeIfAbsent(folder.resolve(o.image).toAbsolutePath().toString(), path -> {
            try {
                return ImageIO.read(Path.of(path).toFile());
            } catch (IOException e) {
                return null;
            }
        });
        if (source == null) {
            return null;
        }
        // scale 1: a quarter of the frame height tall.
        int h = Math.max(1, (int) Math.round(frameHeight / 4.0 * o.scale));
        int w = Math.max(1, source.getWidth() * h / Math.max(1, source.getHeight()));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setComposite(AlphaComposite.Src);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return img;
    }

    /** Alpha-blends {@code layer} into the frame at (x0, y0), clipped to the frame. */
    private static void composite(byte[] rgba, int width, int height, BufferedImage layer, int x0, int y0, double alpha) {
        int lw = layer.getWidth();
        int lh = layer.getHeight();
        int[] argb = layer.getRGB(0, 0, lw, lh, null, 0, lw);
        for (int y = Math.max(0, -y0); y < lh && y0 + y < height; y++) {
            for (int x = Math.max(0, -x0); x < lw && x0 + x < width; x++) {
                int p = argb[y * lw + x];
                double a = ((p >>> 24) / 255.0) * alpha;
                if (a <= 0) {
                    continue;
                }
                int i = ((y0 + y) * width + x0 + x) * 4;
                rgba[i] = blend(rgba[i], (p >> 16) & 0xFF, a);
                rgba[i + 1] = blend(rgba[i + 1], (p >> 8) & 0xFF, a);
                rgba[i + 2] = blend(rgba[i + 2], p & 0xFF, a);
            }
        }
    }

    private static byte blend(byte dst, int src, double a) {
        return (byte) Math.round((dst & 0xFF) * (1 - a) + src * a);
    }
}
