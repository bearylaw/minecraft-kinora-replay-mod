package dev.kinora.core.render;

import java.util.stream.IntStream;

/**
 * Pixel conversions between the stages of a render. Images are RGBA, top row first: 8-bit (4 bytes
 * per pixel) as the game renders them, or 16-bit big-endian (8 bytes per pixel) for deep outputs.
 *
 * <p>With a transparent sky, images are premultiplied from {@link #cutSky} on, so supersampling,
 * motion blur and dissolves mix edges correctly; outputs other than EXR undo it at the end.
 */
public final class Pixels {
    private Pixels() {}

    /** sRGB-encoded 16-bit value to linear light. */
    private static final float[] LINEAR16 = new float[65536];

    static {
        for (int i = 0; i < LINEAR16.length; i++) {
            double c = i / 65535.0;
            LINEAR16[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /**
     * Makes the sky transparent: where nothing was drawn (stored depth 0, the far end of reversed Z)
     * the pixel becomes fully transparent black; everything else becomes opaque. The result is
     * premultiplied.
     *
     * @param depth stored depth per pixel, same size as the image
     */
    public static void cutSky(byte[] rgba, float[] depth) {
        IntStream.range(0, depth.length / 4096 + 1).parallel().forEach(b -> {
            int end = Math.min(depth.length, (b + 1) * 4096);
            for (int i = b * 4096; i < end; i++) {
                int o = i * 4;
                if (depth[i] <= 0f) {
                    rgba[o] = 0;
                    rgba[o + 1] = 0;
                    rgba[o + 2] = 0;
                    rgba[o + 3] = 0;
                } else {
                    rgba[o + 3] = (byte) 255;
                }
            }
        });
    }

    /** 8-bit to 16-bit (x * 257 maps 255 to 65535 exactly). */
    public static byte[] widen(byte[] rgba8) {
        byte[] out = new byte[rgba8.length * 2];
        for (int i = 0; i < rgba8.length; i++) {
            out[i * 2] = rgba8[i];
            out[i * 2 + 1] = rgba8[i];
        }
        return out;
    }

    /** Premultiplied to straight alpha, in place, 8-bit. */
    public static void unpremultiply8(byte[] rgba) {
        IntStream.range(0, rgba.length / 4 / 4096 + 1).parallel().forEach(b -> {
            int end = Math.min(rgba.length / 4, (b + 1) * 4096);
            for (int p = b * 4096; p < end; p++) {
                int o = p * 4;
                int a = rgba[o + 3] & 0xFF;
                if (a == 255) {
                    continue;
                }
                for (int c = 0; c < 3; c++) {
                    rgba[o + c] = a == 0 ? 0 : (byte) Math.min(255, ((rgba[o + c] & 0xFF) * 255 + a / 2) / a);
                }
            }
        });
    }

    /** Premultiplied to straight alpha, in place, 16-bit big-endian. */
    public static void unpremultiply16(byte[] rgba) {
        IntStream.range(0, rgba.length / 8 / 4096 + 1).parallel().forEach(b -> {
            int end = Math.min(rgba.length / 8, (b + 1) * 4096);
            for (int p = b * 4096; p < end; p++) {
                int o = p * 8;
                int a = get16(rgba, o + 6);
                if (a == 65535) {
                    continue;
                }
                for (int c = 0; c < 3; c++) {
                    long v = a == 0 ? 0 : Math.min(65535L, ((long) get16(rgba, o + c * 2) * 65535 + a / 2) / a);
                    put16(rgba, o + c * 2, (int) v);
                }
            }
        });
    }

    /**
     * 16-bit premultiplied sRGB to linear floats (premultiplied, as EXR expects): {@code 4} floats
     * per pixel with alpha, else 3.
     */
    public static float[] toLinear(byte[] rgba16, boolean alpha) {
        int pixels = rgba16.length / 8;
        int stride = alpha ? 4 : 3;
        float[] out = new float[pixels * stride];
        IntStream.range(0, pixels / 4096 + 1).parallel().forEach(b -> {
            int end = Math.min(pixels, (b + 1) * 4096);
            for (int p = b * 4096; p < end; p++) {
                int o = p * 8;
                int a = get16(rgba16, o + 6);
                float af = a / 65535f;
                for (int c = 0; c < 3; c++) {
                    int v = get16(rgba16, o + c * 2);
                    float linear;
                    if (!alpha || a == 65535) {
                        linear = LINEAR16[v];
                    } else if (a == 0) {
                        linear = 0;
                    } else {
                        // Decode the straight colour, then premultiply in linear light.
                        linear = LINEAR16[(int) Math.min(65535L, ((long) v * 65535 + a / 2) / a)] * af;
                    }
                    out[p * stride + c] = linear;
                }
                if (alpha) {
                    out[p * stride + 3] = af;
                }
            }
        });
        return out;
    }

    /** Drops alpha: 16-bit RGBA to 16-bit RGB. */
    public static byte[] rgb16(byte[] rgba16) {
        byte[] out = new byte[rgba16.length / 8 * 6];
        for (int s = 0, d = 0; s < rgba16.length; s += 8, d += 6) {
            System.arraycopy(rgba16, s, out, d, 6);
        }
        return out;
    }

    static int get16(byte[] b, int o) {
        return (b[o] & 0xFF) << 8 | b[o + 1] & 0xFF;
    }

    static void put16(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
    }
}
