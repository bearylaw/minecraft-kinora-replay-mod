import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Makes the images for the CurseForge description (docs/curseforge/): a banner, a "how it works"
 * strip, the FFmpeg setup guide and cropped screenshots. Screenshots are taken from the dev client's
 * screenshot folder; the icon from the mod's resources.
 *
 * <pre>java tools/branding/CurseForgeImages.java [run/kinora/dev/screenshots] [docs/curseforge/images]</pre>
 */
public class CurseForgeImages {
    static final Color BG = new Color(0x16161C), PANEL = new Color(0x22222C), LINE = new Color(0x3A3A46);
    static final Color AMBER = new Color(0xE0A030), BLUE = new Color(0x56B4E9), GREEN = new Color(0x6CCB5F);
    static final Color TEXT = Color.WHITE, MUTED = new Color(0xA8A8B4);
    static final String FONT = "Segoe UI";

    static File shots, out;

    public static void main(String[] args) throws Exception {
        shots = new File(args.length > 0 ? args[0] : "run/kinora/dev/screenshots");
        out = new File(args.length > 1 ? args[1] : "docs/curseforge/images");
        out.mkdirs();
        banner();
        howItWorks();
        ffmpegGuide();
        // Gallery shots: 16:9 crops without the replay key hints (they show the dev machine's layout).
        jpg(crop(shot("c1785_nonames"), 0, 0, 1600, 760, 1280), "replay.jpg");
        jpg(crop(shot("cg0"), 0, 0, 1600, 900, 1280), "far-terrain.jpg");
        jpg(crop(shot("editor_fields"), 0, 0, 1600, 900, 1280), "editor.jpg");
        jpg(crop(shot("path-fixed"), 0, 0, 1600, 900, 1280), "camera-path.jpg");
        jpg(crop(shot("dialog-passes"), 0, 0, 1600, 900, 1280), "render-dialog.jpg");
    }

    // ---- Banner -------------------------------------------------------------------------------

    static void banner() throws Exception {
        int w = 1600, h = 520;
        BufferedImage img = canvas(w, h);
        Graphics2D g = gfx(img);
        cover(g, shot("cg0"), 0, 0, w, h, 0.5, 0.35);
        g.setPaint(new GradientPaint(0, 0, new Color(0x16161C), w * 0.78f, 0, new Color(0x2016161C, true)));
        g.fillRect(0, 0, w, h);
        g.setPaint(new GradientPaint(0, h - 140, new Color(0, 0, 0, 0), 0, h, new Color(0xA016161C, true)));
        g.fillRect(0, 0, w, h);

        BufferedImage icon = ImageIO.read(new File("kinora-mc/src/main/resources/kinora.png"));
        g.drawImage(icon, 70, 110, 300, 300, null);

        text(g, "Kinora Replay", 410, 228, 104, Font.BOLD, TEXT);
        text(g, "Record your gameplay. Film it like a director.", 414, 302, 44, Font.PLAIN, AMBER);
        text(g, "Minecraft 26.2  ·  NeoForge  ·  Client only", 416, 368, 32, Font.PLAIN, MUTED);
        g.dispose();
        png(img, "banner.png");
    }

    // ---- How it works -------------------------------------------------------------------------

    static void howItWorks() throws Exception {
        int w = 1600, h = 600;
        BufferedImage img = canvas(w, h);
        Graphics2D g = gfx(img);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        String[][] steps = {
                {"1", "Record", "Press F6 while you play.", "Press F6 again to stop."},
                {"2", "Film", "Open the replay, press E.", "Fly the camera, add keys."},
                {"3", "Render", "Click Render in the editor.", "Get a smooth video file."},
        };
        BufferedImage[] pics = {
                crop(shot("c605"), 280, 0, 1320, 742, 460),
                crop(shot("path-fixed"), 0, 0, 1600, 900, 460),
                crop(shot("dialog-passes"), 0, 0, 1600, 900, 460),
        };
        int cw = 460, gap = 70, x0 = (w - 3 * cw - 2 * gap) / 2, y0 = 40;
        for (int i = 0; i < 3; i++) {
            int x = x0 + i * (cw + gap);
            g.setColor(PANEL);
            g.fill(new RoundRectangle2D.Double(x, y0, cw, 520, 36, 36));
            Shape clip = new RoundRectangle2D.Double(x, y0, cw, 259, 36, 36);
            Shape old = g.getClip();
            g.clip(clip);
            g.drawImage(pics[i], x, y0, cw, 259, null);
            g.fillRect(0, 0, 0, 0);
            g.setClip(old);
            g.setColor(PANEL);
            g.fillRect(x, y0 + 240, cw, 20);

            g.setColor(AMBER);
            g.fill(new Ellipse2D.Double(x + 30, y0 + 222, 76, 76));
            g.setColor(PANEL);
            g.setStroke(new BasicStroke(6));
            g.draw(new Ellipse2D.Double(x + 30, y0 + 222, 76, 76));
            centered(g, steps[i][0], x + 68, y0 + 278, 46, Font.BOLD, new Color(0x16161C));

            text(g, steps[i][1], x + 34, y0 + 372, 52, Font.BOLD, TEXT);
            text(g, steps[i][2], x + 34, y0 + 430, 30, Font.PLAIN, MUTED);
            text(g, steps[i][3], x + 34, y0 + 472, 30, Font.PLAIN, MUTED);

            if (i < 2) arrow(g, x + cw + 14, y0 + 260, x + cw + gap - 14, y0 + 260);
        }
        g.dispose();
        png(img, "how-it-works.png");
    }

    // ---- FFmpeg guide -------------------------------------------------------------------------

    static void ffmpegGuide() throws Exception {
        int w = 1600, h = 1260;
        BufferedImage img = canvas(w, h);
        Graphics2D g = gfx(img);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        text(g, "Setting up FFmpeg for video", 60, 92, 56, Font.BOLD, TEXT);
        text(g, "A free program that turns Kinora's frames into an MP4. One-time setup, about 2 minutes.",
                62, 148, 30, Font.PLAIN, MUTED);

        int cw = 720, ch = 430, gx = 60, gy = 200, gap = 40;
        // 1. Download
        card(g, gx, gy, cw, ch, "1", "Download it", "gyan.dev/ffmpeg/builds", "Under \"release builds\", pick the essentials .zip");
        button(g, gx + 60, gy + 250, 600, 84, "ffmpeg-release-essentials.zip");

        // 2. Unzip
        int x2 = gx + cw + gap;
        card(g, x2, gy, cw, ch, "2", "Unzip it", "Right-click the .zip → Extract All", "Inside, open the bin folder");
        int ry = gy + 242;
        fileRow(g, x2 + 60, ry, "ffmpeg-...-essentials_build", false, false);
        fileRow(g, x2 + 110, ry + 54, "bin", false, false);
        fileRow(g, x2 + 160, ry + 108, "ffmpeg.exe", true, true);

        // 3. Open the profile folder
        int y3 = gy + ch + gap;
        card(g, gx, y3, cw, ch, "3", "Open your Minecraft folder", "CurseForge: right-click the profile", "→ Open Folder  (Prism: \"Folder\")");
        menu(g, gx + 60, y3 + 240, new String[]{"Play", "Open Folder", "Export Profile", "Delete"}, 1);

        // 4. Copy into kinora/tools
        card(g, x2, y3, cw, ch, "4", "Put it in kinora/tools", "Copy ffmpeg.exe into the kinora/tools folder.", "No such folder yet? Just create it.");
        int ty = y3 + 262;
        fileRow(g, x2 + 60, ty, "your profile folder", false, false);
        fileRow(g, x2 + 110, ty + 46, "kinora", false, false);
        fileRow(g, x2 + 160, ty + 92, "tools", false, false);
        fileRow(g, x2 + 210, ty + 138, "ffmpeg.exe", true, true);
        fileRow(g, x2 + 110 + 300, ty + 46, "mods", false, false);

        // Check.
        int by = y3 + ch + 36;
        g.setColor(new Color(0x1E3320));
        g.fill(new RoundRectangle2D.Double(gx, by, w - 2 * gx, 84, 28, 28));
        g.setColor(GREEN);
        g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D tick = new Path2D.Double();
        tick.moveTo(gx + 38, by + 44);
        tick.lineTo(gx + 54, by + 60);
        tick.lineTo(gx + 84, by + 26);
        g.draw(tick);
        text(g, "Done! The Render window now shows \"FFmpeg: ...kinora/tools/ffmpeg.exe\".", gx + 112, by + 54, 32, Font.BOLD, TEXT);
        g.dispose();
        png(img, "ffmpeg-setup.png");
    }

    static void card(Graphics2D g, int x, int y, int w, int h, String n, String title, String l1, String l2) {
        g.setColor(PANEL);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 32, 32));
        g.setColor(AMBER);
        g.fill(new Ellipse2D.Double(x + 34, y + 34, 64, 64));
        centered(g, n, x + 66, y + 81, 40, Font.BOLD, new Color(0x16161C));
        text(g, title, x + 120, y + 80, 40, Font.BOLD, TEXT);
        text(g, l1, x + 36, y + 150, 30, Font.PLAIN, BLUE);
        text(g, l2, x + 36, y + 194, 30, Font.PLAIN, MUTED);
    }

    static void button(Graphics2D g, int x, int y, int w, int h, String label) {
        g.setColor(new Color(0x2F6FA3));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 20, 20));
        g.setColor(TEXT);
        g.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int ax = x + 50, ay = y + h / 2;
        g.draw(new Line2D.Double(ax, ay - 22, ax, ay + 14));
        g.draw(new Line2D.Double(ax - 14, ay, ax, ay + 14));
        g.draw(new Line2D.Double(ax + 14, ay, ax, ay + 14));
        g.draw(new Line2D.Double(ax - 18, ay + 24, ax + 18, ay + 24));
        text(g, label, x + 96, y + h / 2 + 12, 32, Font.BOLD, TEXT);
    }

    static void menu(Graphics2D g, int x, int y, String[] items, int hl) {
        int w = 340, rh = 36;
        g.setColor(new Color(0x2C2C38));
        g.fill(new RoundRectangle2D.Double(x, y, w, items.length * rh + 16, 16, 16));
        g.setColor(LINE);
        g.setStroke(new BasicStroke(2));
        g.draw(new RoundRectangle2D.Double(x, y, w, items.length * rh + 16, 16, 16));
        for (int i = 0; i < items.length; i++) {
            int ry = y + 8 + i * rh;
            if (i == hl) {
                g.setColor(AMBER);
                g.fill(new RoundRectangle2D.Double(x + 8, ry, w - 16, rh, 10, 10));
            }
            text(g, items[i], x + 26, ry + 26, 24, i == hl ? Font.BOLD : Font.PLAIN, i == hl ? new Color(0x16161C) : MUTED);
        }
        // Mouse pointer on the highlighted item.
        Path2D p = new Path2D.Double();
        double px = x + w - 70, py = y + 8 + hl * rh + 14;
        p.moveTo(px, py);
        p.lineTo(px, py + 40);
        p.lineTo(px + 10, py + 30);
        p.lineTo(px + 18, py + 46);
        p.lineTo(px + 25, py + 43);
        p.lineTo(px + 17, py + 27);
        p.lineTo(px + 30, py + 27);
        p.closePath();
        g.setColor(TEXT);
        g.fill(p);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2.5f));
        g.draw(p);
    }

    static void fileRow(Graphics2D g, int x, int y, String name, boolean file, boolean highlight) {
        if (highlight) {
            g.setColor(new Color(0x40E0A030, true));
            g.fill(new RoundRectangle2D.Double(x - 12, y - 30, 330, 46, 12, 12));
        }
        if (file) {
            g.setColor(new Color(0xD8D8E0));
            Path2D f = new Path2D.Double();
            f.moveTo(x, y - 24);
            f.lineTo(x + 18, y - 24);
            f.lineTo(x + 26, y - 16);
            f.lineTo(x + 26, y + 8);
            f.lineTo(x, y + 8);
            f.closePath();
            g.fill(f);
            g.setColor(AMBER);
            g.fill(new Rectangle2D.Double(x + 5, y - 6, 16, 8));
        } else {
            g.setColor(new Color(0xE8B54A));
            g.fill(new RoundRectangle2D.Double(x, y - 26, 16, 10, 4, 4));
            g.fill(new RoundRectangle2D.Double(x, y - 20, 34, 28, 6, 6));
        }
        text(g, name, x + 46, y + 4, 28, highlight ? Font.BOLD : Font.PLAIN, highlight ? AMBER : TEXT);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    static void arrow(Graphics2D g, double x1, double y, double x2, double y2) {
        g.setColor(AMBER);
        g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(x1, y, x2, y2));
        g.draw(new Line2D.Double(x2 - 14, y2 - 14, x2, y2));
        g.draw(new Line2D.Double(x2 - 14, y2 + 14, x2, y2));
    }

    static void text(Graphics2D g, String s, double x, double y, float size, int style, Color c) {
        g.setFont(new Font(FONT, style, 1).deriveFont(size));
        g.setColor(c);
        g.drawString(s, (float) x, (float) y);
    }

    static void centered(Graphics2D g, String s, double cx, double y, float size, int style, Color c) {
        g.setFont(new Font(FONT, style, 1).deriveFont(size));
        double w = g.getFontMetrics().stringWidth(s);
        g.setColor(c);
        g.drawString(s, (float) (cx - w / 2), (float) y);
    }

    static void cover(Graphics2D g, BufferedImage src, int x, int y, int w, int h, double fx, double fy) {
        double s = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
        int sw = (int) Math.round(src.getWidth() * s), sh = (int) Math.round(src.getHeight() * s);
        g.drawImage(src, x - (int) ((sw - w) * fx), y - (int) ((sh - h) * fy), sw, sh, null);
    }

    static BufferedImage crop(BufferedImage src, int x, int y, int w, int h, int outW) {
        int outH = (int) Math.round(outW * 9.0 / 16);
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = gfx(img);
        cover(g, src.getSubimage(x, y, w, h), 0, 0, outW, outH, 0.5, 0.5);
        g.dispose();
        return img;
    }

    static BufferedImage shot(String name) throws Exception {
        return ImageIO.read(new File(shots, name + ".png"));
    }

    static BufferedImage canvas(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    static Graphics2D gfx(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    static void png(BufferedImage img, String name) throws Exception {
        ImageIO.write(img, "png", new File(out, name));
    }

    static void jpg(BufferedImage img, String name) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.9f);
        try (ImageOutputStream s = ImageIO.createImageOutputStream(new File(out, name))) {
            w.setOutput(s);
            w.write(null, new IIOImage(img, null, null), p);
        }
        w.dispose();
    }
}
