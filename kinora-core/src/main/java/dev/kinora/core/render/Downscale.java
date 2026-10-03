package dev.kinora.core.render;

/** Supersampling: averages each {@code factor x factor} block of an RGBA image into one pixel. */
public final class Downscale {
    private Downscale() {}

    public static byte[] box(byte[] rgba, int width, int height, int factor) {
        int ow = width / factor;
        int oh = height / factor;
        byte[] out = new byte[ow * oh * 4];
        int n = factor * factor;
        int[] sum = new int[4];
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                sum[0] = sum[1] = sum[2] = sum[3] = 0;
                for (int dy = 0; dy < factor; dy++) {
                    int row = ((y * factor + dy) * width + x * factor) * 4;
                    for (int dx = 0; dx < factor * 4; dx += 4) {
                        sum[0] += rgba[row + dx] & 0xFF;
                        sum[1] += rgba[row + dx + 1] & 0xFF;
                        sum[2] += rgba[row + dx + 2] & 0xFF;
                        sum[3] += rgba[row + dx + 3] & 0xFF;
                    }
                }
                int o = (y * ow + x) * 4;
                for (int c = 0; c < 4; c++) {
                    out[o + c] = (byte) ((sum[c] + n / 2) / n);
                }
            }
        }
        return out;
    }
}
