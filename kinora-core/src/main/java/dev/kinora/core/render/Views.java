package dev.kinora.core.render;

import dev.kinora.core.camera.CameraState;

import java.util.stream.IntStream;

/**
 * Images that need several renders: 360° video from the six faces of a cube, stereo 3D from two
 * eyes, and tiled frames (larger than the GPU allows) from a grid of pieces.
 *
 * <p>A tile shows part of the full frame's view: the same camera, with the projection scaled and
 * shifted so the tile covers its piece ({@link #tile}). Tiles are numbered row by row from the top
 * left. When the size does not divide evenly, the tiles cover a slightly larger frame (same centre,
 * same field of view per pixel) and the joined image is cropped back.
 *
 * <p>Cube faces are rendered square with a 90° field of view and a level horizon, turned from the
 * shot camera's heading: 0 front, 1 right, 2 back, 3 left, 4 up, 5 down. The camera's pitch and roll
 * are left out, as a 360° viewer chooses where to look.
 */
public final class Views {
    private Views() {}

    /** How many renders each output image needs. */
    public static int count(RenderSettings s) {
        int tiles = s.tileCount();
        return tiles > 1 ? tiles * tiles : count(s.projection);
    }

    /** How many renders each output image of this projection needs (without tiles). */
    public static int count(RenderSettings.Projection projection) {
        return switch (projection) {
            case NORMAL -> 1;
            case EQUIRECTANGULAR, CUBEMAP -> 6;
            case STEREO_SIDE_BY_SIDE, STEREO_TOP_BOTTOM -> 2;
        };
    }

    /** Size of one render (before supersampling) for the output size. */
    public static int[] renderSize(RenderSettings s) {
        return switch (s.projection) {
            case NORMAL -> s.tileCount() > 1 ? new int[] {ceilDiv(s.width, s.tileCount()), ceilDiv(s.height, s.tileCount())}
                    : new int[] {s.width, s.height};
            case STEREO_SIDE_BY_SIDE, STEREO_TOP_BOTTOM -> new int[] {s.width, s.height};
            // A face a quarter of the panorama's width keeps the equator's detail.
            case EQUIRECTANGULAR -> {
                int face = even(s.width / 4);
                yield new int[] {face, face};
            }
            case CUBEMAP -> {
                int face = even(s.width / 3);
                yield new int[] {face, face};
            }
        };
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * For a tiled frame, the projection change of one tile: {@code {scale, offsetX, offsetY}}, applied
     * to clip space as {@code x' = scale x + offsetX w}, {@code y' = scale y + offsetY w}. Null when
     * not tiled.
     */
    public static double[] tile(int view, RenderSettings s) {
        int n = s.tileCount();
        if (n <= 1) {
            return null;
        }
        int column = view % n;
        int row = view / n;
        // The crop (joinTiles) cannot always be centred to the pixel: shift the view by the rest, so
        // the centre of the output stays on the camera's axis.
        int tw = ceilDiv(s.width, n);
        int th = ceilDiv(s.height, n);
        double dx = n * tw / 2.0 - ((n * tw - s.width) / 2 + s.width / 2.0);
        double dy = n * th / 2.0 - ((n * th - s.height) / 2 + s.height / 2.0);
        return new double[] {n, n - 1 - 2 * column - 2 * dx / tw, -(n - 1 - 2 * row) + 2 * dy / th};
    }

    /** Vertical field of view of the tiles' whole frame, which can be a little taller than the output. */
    static double tiledFov(double fov, RenderSettings s) {
        int n = s.tileCount();
        double canvas = (double) ceilDiv(s.height, n) * n;
        return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(fov) / 2) * canvas / s.height));
    }

    private static int even(int v) {
        return Math.max(16, v & ~1);
    }

    /** The camera for one view of the shot camera. */
    public static CameraState camera(CameraState c, int view, RenderSettings s) {
        return switch (s.projection) {
            case NORMAL -> s.tileCount() > 1 ? new CameraState(c.x(), c.y(), c.z(), c.yaw(), c.pitch(), c.roll(), tiledFov(c.fov(), s)) : c;
            case EQUIRECTANGULAR, CUBEMAP -> switch (view) {
                case 0, 1, 2, 3 -> new CameraState(c.x(), c.y(), c.z(), c.yaw() + 90 * view, 0, 0, 90);
                case 4 -> new CameraState(c.x(), c.y(), c.z(), c.yaw(), -90, 0, 90);
                default -> new CameraState(c.x(), c.y(), c.z(), c.yaw(), 90, 0, 90);
            };
            case STEREO_SIDE_BY_SIDE, STEREO_TOP_BOTTOM -> {
                // View 0 is the left eye. The camera's right, facing south (yaw 0), is west (-X).
                double half = s.stereoEyeDistance / 2 * (view == 0 ? -1 : 1);
                double yaw = Math.toRadians(c.yaw());
                yield new CameraState(c.x() - Math.cos(yaw) * half, c.y(), c.z() - Math.sin(yaw) * half, c.yaw(), c.pitch(), c.roll(), c.fov());
            }
        };
    }

    /**
     * Joins the views of one image into the output picture.
     *
     * @param views RGBA images in view order, each {@link #renderSize} (after supersampling)
     */
    public static byte[] combine(byte[][] views, RenderSettings s) {
        int[] size = renderSize(s);
        return switch (s.projection) {
            case NORMAL -> s.tileCount() > 1 ? joinTiles(views, s.tileCount(), size[0], size[1], s.width, s.height) : views[0];
            case EQUIRECTANGULAR -> equirectangular(views, size[0], s.width, s.height);
            case CUBEMAP -> cubeGrid(views, size[0], s.width, s.height);
            case STEREO_SIDE_BY_SIDE -> sideBySide(views, s.width, s.height);
            case STEREO_TOP_BOTTOM -> topBottom(views, s.width, s.height);
        };
    }

    /** Puts the tiles in their grid and crops the centre {@code width x height}. */
    static byte[] joinTiles(byte[][] tiles, int n, int tw, int th, int width, int height) {
        int x0 = (n * tw - width) / 2;
        int y0 = (n * th - height) / 2;
        byte[] out = new byte[width * height * 4];
        IntStream.range(0, height).parallel().forEach(y -> {
            int cy = y + y0;
            int row = cy / th;
            int ty = cy % th;
            int x = 0;
            while (x < width) {
                int cx = x + x0;
                int column = cx / tw;
                int tx = cx % tw;
                int run = Math.min(width - x, tw - tx);
                System.arraycopy(tiles[row * n + column], (ty * tw + tx) * 4, out, (y * width + x) * 4, run * 4);
                x += run;
            }
        });
        return out;
    }

    // Face axes in camera space: x right, y up, z forward. forward, right, up per face.
    private static final double[][][] FACES = {
            {{0, 0, 1}, {1, 0, 0}, {0, 1, 0}},
            {{1, 0, 0}, {0, 0, -1}, {0, 1, 0}},
            {{0, 0, -1}, {-1, 0, 0}, {0, 1, 0}},
            {{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
            {{0, 1, 0}, {1, 0, 0}, {0, 0, -1}},
            {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}},
    };

    static byte[] equirectangular(byte[][] faces, int face, int width, int height) {
        byte[] out = new byte[width * height * 4];
        IntStream.range(0, height).parallel().forEach(y -> {
            double lat = Math.PI / 2 - (y + 0.5) / height * Math.PI;
            double cosLat = Math.cos(lat);
            double sinLat = Math.sin(lat);
            for (int x = 0; x < width; x++) {
                double lon = (x + 0.5) / width * 2 * Math.PI - Math.PI;
                double dx = Math.sin(lon) * cosLat;
                double dy = sinLat;
                double dz = Math.cos(lon) * cosLat;
                sampleCube(faces, face, dx, dy, dz, out, (y * width + x) * 4);
            }
        });
        return out;
    }

    /** Picks the face a direction falls on and samples it bilinearly. */
    private static void sampleCube(byte[][] faces, int size, double dx, double dy, double dz, byte[] out, int o) {
        int best = 0;
        double bestDot = -2;
        for (int f = 0; f < 6; f++) {
            double[] fw = FACES[f][0];
            double d = dx * fw[0] + dy * fw[1] + dz * fw[2];
            if (d > bestDot) {
                bestDot = d;
                best = f;
            }
        }
        double[] r = FACES[best][1];
        double[] u = FACES[best][2];
        double sx = (dx * r[0] + dy * r[1] + dz * r[2]) / bestDot;
        double sy = (dx * u[0] + dy * u[1] + dz * u[2]) / bestDot;
        double px = (sx + 1) / 2 * size - 0.5;
        double py = (1 - sy) / 2 * size - 0.5;
        bilinear(faces[best], size, size, px, py, out, o);
    }

    private static void bilinear(byte[] img, int w, int h, double px, double py, byte[] out, int o) {
        int x0 = (int) Math.floor(px);
        int y0 = (int) Math.floor(py);
        double fx = px - x0;
        double fy = py - y0;
        int ax = clamp(x0, w);
        int bx = clamp(x0 + 1, w);
        int ay = clamp(y0, h);
        int by = clamp(y0 + 1, h);
        for (int c = 0; c < 4; c++) {
            double top = (img[(ay * w + ax) * 4 + c] & 0xFF) * (1 - fx) + (img[(ay * w + bx) * 4 + c] & 0xFF) * fx;
            double bottom = (img[(by * w + ax) * 4 + c] & 0xFF) * (1 - fx) + (img[(by * w + bx) * 4 + c] & 0xFF) * fx;
            out[o + c] = (byte) Math.round(top * (1 - fy) + bottom * fy);
        }
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : v >= max ? max - 1 : v;
    }

    /** Faces in a 3 x 2 grid: front, right, back on top; left, up, down below. */
    static byte[] cubeGrid(byte[][] faces, int face, int width, int height) {
        byte[] out = new byte[width * height * 4];
        int[][] cells = {{0, 0}, {1, 0}, {2, 0}, {0, 1}, {1, 1}, {2, 1}};
        int[] order = {0, 1, 2, 3, 4, 5};
        for (int i = 0; i < 6; i++) {
            int ox = cells[i][0] * face;
            int oy = cells[i][1] * face;
            byte[] src = faces[order[i]];
            for (int y = 0; y < face && oy + y < height; y++) {
                int n = Math.min(face, width - ox) * 4;
                if (n > 0) {
                    System.arraycopy(src, y * face * 4, out, ((oy + y) * width + ox) * 4, n);
                }
            }
        }
        return out;
    }

    /** Half side-by-side: each eye squeezed to half width. */
    static byte[] sideBySide(byte[][] eyes, int width, int height) {
        byte[] out = new byte[width * height * 4];
        int half = width / 2;
        for (int e = 0; e < 2; e++) {
            byte[] src = eyes[e];
            int ox = e * half;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < half; x++) {
                    int a = (y * width + x * 2) * 4;
                    int b = (y * width + Math.min(width - 1, x * 2 + 1)) * 4;
                    int o = (y * width + ox + x) * 4;
                    for (int c = 0; c < 4; c++) {
                        out[o + c] = (byte) (((src[a + c] & 0xFF) + (src[b + c] & 0xFF) + 1) / 2);
                    }
                }
            }
        }
        return out;
    }

    /** Half top-bottom: each eye squeezed to half height, left eye on top. */
    static byte[] topBottom(byte[][] eyes, int width, int height) {
        byte[] out = new byte[width * height * 4];
        int half = height / 2;
        for (int e = 0; e < 2; e++) {
            byte[] src = eyes[e];
            for (int y = 0; y < half; y++) {
                int ra = y * 2 * width * 4;
                int rb = Math.min(height - 1, y * 2 + 1) * width * 4;
                int o = (e * half + y) * width * 4;
                for (int i = 0; i < width * 4; i++) {
                    out[o + i] = (byte) (((src[ra + i] & 0xFF) + (src[rb + i] & 0xFF) + 1) / 2);
                }
            }
        }
        return out;
    }
}
