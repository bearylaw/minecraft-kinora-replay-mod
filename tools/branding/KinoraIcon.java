import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the Kinora Replay icon: a camera aperture with a play button, and a keyframed camera path
 * sweeping past it. Colours are the UI theme's (Theme.java): dark panels, amber accent, blue path.
 *
 * <pre>java tools/branding/KinoraIcon.java kinora-mc/src/main/resources/kinora.png 512</pre>
 */
public class KinoraIcon {
    static final Color BG_TOP = new Color(0x2A2A3C), BG_BOTTOM = new Color(0x111116);
    static final Color AMBER = new Color(0xE0A030), AMBER_LIGHT = new Color(0xF2C35A), AMBER_DARK = new Color(0xA86A1C);
    static final Color BLUE = new Color(0x56B4E9), CREAM = new Color(0xFFF4DC), RING = new Color(0x1A1A22);

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

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D d = img.createGraphics();
        d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
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
        d.drawImage(step, 0, 0, size, size, null);
        d.dispose();
        ImageIO.write(img, "png", new File(out));
    }

    static void draw(Graphics2D g) {
        // Background tile.
        Shape tile = new RoundRectangle2D.Double(16, 16, 480, 480, 112, 112);
        g.setPaint(new GradientPaint(0, 16, BG_TOP, 0, 496, BG_BOTTOM));
        g.fill(tile);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(276, 290), 260,
                new float[]{0f, 1f}, new Color[]{new Color(0x56B4E9 | 0x22000000, true), new Color(0, 0, 0, 0)}));
        g.fill(tile);

        double cx = 280, cy = 292, r = 150;

        // Camera path, drawn first so the lens sits in front of it.
        CubicCurve2D path = new CubicCurve2D.Double(84, 412, 40, 160, 250, 52, 432, 108);
        g.setColor(new Color(BLUE.getRed(), BLUE.getGreen(), BLUE.getBlue(), 230));
        g.setStroke(new BasicStroke(13, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{0.1f, 27}, 0));
        g.draw(path);
        keyframe(g, point(path, 0.0), 26, BLUE);
        keyframe(g, point(path, 0.42), 26, BLUE);
        keyframe(g, point(path, 1.0), 30, AMBER_LIGHT);

        // Lens barrel.
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(circle(cx + 6, cy + 10, r + 34));
        g.setPaint(new GradientPaint(0, (float) (cy - r), new Color(0x3A3A48), 0, (float) (cy + r), new Color(0x16161C)));
        g.fill(circle(cx, cy, r + 30));
        g.setColor(RING);
        g.fill(circle(cx, cy, r + 8));

        // Aperture blades: the hexagonal opening's edges extended out to the rim.
        Shape disc = circle(cx, cy, r);
        double r0 = r * 0.5, rot = Math.toRadians(-90);
        Point2D[] v = new Point2D[6];
        for (int i = 0; i < 6; i++) {
            double a = rot + i * Math.PI / 3;
            v[i] = new Point2D.Double(cx + r0 * Math.cos(a), cy + r0 * Math.sin(a));
        }
        for (int i = 0; i < 6; i++) {
            Point2D a = v[(i + 1) % 6], b = v[(i + 2) % 6], p = v[i];
            Point2D farA = extend(p, a, 4 * r), farB = extend(a, b, 4 * r);
            Path2D blade = new Path2D.Double();
            blade.moveTo(a.getX(), a.getY());
            blade.lineTo(b.getX(), b.getY());
            blade.lineTo(farB.getX(), farB.getY());
            blade.lineTo(farA.getX(), farA.getY());
            blade.closePath();
            Area area = new Area(blade);
            area.intersect(new Area(disc));
            double mid = rot + (i + 1.5) * Math.PI / 3;
            g.setPaint(new GradientPaint(
                    (float) (cx + r0 * Math.cos(mid)), (float) (cy + r0 * Math.sin(mid)), i % 2 == 0 ? AMBER_LIGHT : AMBER,
                    (float) (cx + r * Math.cos(mid)), (float) (cy + r * Math.sin(mid)), AMBER_DARK));
            g.fill(area);
            g.setColor(new Color(0x5A3A10));
            g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            Shape old = g.getClip();
            g.clip(disc);
            g.draw(new Line2D.Double(a, farA));
            g.setClip(old);
        }

        // The opening, with the play button.
        Path2D hex = new Path2D.Double();
        hex.moveTo(v[0].getX(), v[0].getY());
        for (int i = 1; i < 6; i++) hex.lineTo(v[i].getX(), v[i].getY());
        hex.closePath();
        g.setColor(new Color(0x101014));
        g.fill(hex);
        double t = r0 * 0.62, tx = cx + t * 0.18;
        Path2D play = new Path2D.Double();
        play.moveTo(tx - t * 0.5, cy - t * 0.8);
        play.lineTo(tx + t * 0.9, cy);
        play.lineTo(tx - t * 0.5, cy + t * 0.8);
        play.closePath();
        g.setColor(CREAM);
        g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.fill(play);
        g.draw(play);

        // Rim highlight.
        g.setColor(new Color(255, 255, 255, 40));
        g.setStroke(new BasicStroke(4));
        g.draw(new Arc2D.Double(cx - r - 22, cy - r - 22, 2 * r + 44, 2 * r + 44, 100, 110, Arc2D.OPEN));
    }

    static void keyframe(Graphics2D g, Point2D p, double s, Color c) {
        Path2D d = new Path2D.Double();
        d.moveTo(p.getX(), p.getY() - s);
        d.lineTo(p.getX() + s, p.getY());
        d.lineTo(p.getX(), p.getY() + s);
        d.lineTo(p.getX() - s, p.getY());
        d.closePath();
        g.setColor(new Color(0x111116));
        g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(d);
        g.setColor(c);
        g.fill(d);
    }

    static Point2D point(CubicCurve2D c, double t) {
        double u = 1 - t;
        double x = u * u * u * c.getX1() + 3 * u * u * t * c.getCtrlX1() + 3 * u * t * t * c.getCtrlX2() + t * t * t * c.getX2();
        double y = u * u * u * c.getY1() + 3 * u * u * t * c.getCtrlY1() + 3 * u * t * t * c.getCtrlY2() + t * t * t * c.getY2();
        return new Point2D.Double(x, y);
    }

    static Point2D extend(Point2D from, Point2D through, double len) {
        double dx = through.getX() - from.getX(), dy = through.getY() - from.getY(), l = Math.hypot(dx, dy);
        return new Point2D.Double(through.getX() + dx / l * len, through.getY() + dy / l * len);
    }

    static Shape circle(double cx, double cy, double r) {
        return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
    }
}
