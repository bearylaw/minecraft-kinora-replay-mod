import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the Kinora Replay icon: an old hand-cranked movie camera in side profile, flat and simple,
 * on a dark tile. Colours are the UI theme's (Theme.java): dark panels, amber accent, blue glass.
 *
 * <pre>java tools/branding/KinoraIcon.java kinora-mc/src/main/resources/kinora.png 512</pre>
 */
public class KinoraIcon {
    static final Color BG_TOP = new Color(0x2A2A3C), BG_BOTTOM = new Color(0x111116);
    static final Color AMBER = new Color(0xE0A030), AMBER_LIGHT = new Color(0xF2C35A), AMBER_DARK = new Color(0xB07420);
    static final Color BLUE = new Color(0x56B4E9), INK = new Color(0x15151B);

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "kinora.png";
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        // Drawn on a 512 grid at 4x and scaled down, for clean edges at any output size.
        int ss = size * 4;
        BufferedImage big = new BufferedImage(ss, ss, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(ss / 512.0, ss / 512.0);
        draw(g);
        g.dispose();

        // Halve in steps so every source pixel contributes.
        BufferedImage step = big;
        while (step.getWidth() / 2 >= size) {
            int w = step.getWidth() / 2;
            BufferedImage half = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            Graphics2D h = half.createGraphics();
            h.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            h.drawImage(step, 0, 0, w, w, null);
            h.dispose();
            step = half;
        }
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D d = img.createGraphics();
        d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        d.drawImage(step, 0, 0, size, size, null);
        d.dispose();
        ImageIO.write(img, "png", new File(out));
    }

    static void draw(Graphics2D g) {
        // Background tile with a soft glow behind the camera.
        Shape tile = new RoundRectangle2D.Double(16, 16, 480, 480, 112, 112);
        g.setPaint(new GradientPaint(0, 16, BG_TOP, 0, 496, BG_BOTTOM));
        g.fill(tile);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(256, 250), 250,
                new float[]{0f, 1f}, new Color[]{new Color(0x30E0A030, true), new Color(0, 0, 0, 0)}));
        g.fill(tile);

        // The camera is drawn on its own grid, nudged to sit optically centred.
        g.translate(-14, 8);

        // Tripod, behind everything else.
        g.setColor(AMBER_DARK);
        g.setStroke(new BasicStroke(16, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(236, 392, 160, 458));
        g.draw(new Line2D.Double(236, 392, 312, 458));
        g.draw(new Line2D.Double(236, 392, 236, 462));
        g.fill(new RoundRectangle2D.Double(204, 370, 64, 30, 10, 10));

        // Film reels: the back one smaller and darker.
        g.setColor(AMBER_DARK);
        g.fill(reel(162, 166, 68));
        g.setColor(AMBER);
        g.fill(reel(306, 150, 80));

        // Body.
        RoundRectangle2D body = new RoundRectangle2D.Double(110, 236, 250, 142, 26, 26);
        g.setPaint(new GradientPaint(0, 236, AMBER_LIGHT, 0, 378, AMBER));
        g.fill(body);
        // A seam and a viewfinder on top.
        g.setColor(AMBER_DARK);
        g.fill(new RoundRectangle2D.Double(118, 222, 70, 22, 10, 10));
        g.setColor(new Color(0x40000000, true));
        g.fill(new Rectangle2D.Double(110, 340, 250, 8));

        // Hand crank: hub, arm and grip.
        g.setColor(INK);
        g.fill(circle(198, 300, 26));
        g.setColor(AMBER_LIGHT);
        g.fill(circle(198, 300, 11));
        g.setColor(INK);
        g.setStroke(new BasicStroke(12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(198, 300, 150, 324));
        g.fill(new RoundRectangle2D.Double(130, 314, 26, 40, 12, 12));

        // Lens: a short barrel, then the flared hood, then the glass.
        g.setColor(AMBER_DARK);
        g.fill(new Rectangle2D.Double(356, 278, 30, 66));
        Path2D hood = new Path2D.Double();
        hood.moveTo(384, 270);
        hood.lineTo(438, 240);
        hood.lineTo(438, 382);
        hood.lineTo(384, 352);
        hood.closePath();
        g.setPaint(new GradientPaint(384, 0, AMBER, 438, 0, AMBER_LIGHT));
        g.fill(hood);
        g.setColor(INK);
        g.fill(new RoundRectangle2D.Double(430, 236, 22, 150, 14, 14));
        g.setColor(BLUE);
        g.fill(new Ellipse2D.Double(436, 258, 10, 106));
    }

    /** A film reel: a disc with a hub hole and five windows cut out, so the tile shows through. */
    static Area reel(double cx, double cy, double r) {
        Area a = new Area(circle(cx, cy, r));
        a.subtract(new Area(circle(cx, cy, r * 0.13)));
        for (int i = 0; i < 5; i++) {
            double ang = Math.toRadians(-90 + i * 72);
            a.subtract(new Area(circle(cx + Math.cos(ang) * r * 0.55, cy + Math.sin(ang) * r * 0.55, r * 0.25)));
        }
        return a;
    }

    static Shape circle(double cx, double cy, double r) {
        return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
    }
}
