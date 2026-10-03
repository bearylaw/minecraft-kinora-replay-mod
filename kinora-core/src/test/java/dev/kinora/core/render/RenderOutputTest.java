package dev.kinora.core.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderOutputTest {
    @Test
    void exrZipRoundTrips() throws Exception {
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7 + i / 13);
        }
        assertArrayEquals(data, unzip(ExrWriter.zip(data), data.length));
    }

    @Test
    void exrHeaderAndPixels() throws Exception {
        int w = 5;
        int h = 20;
        float[] z = new float[w * h];
        for (int i = 0; i < z.length; i++) {
            z[i] = i * 0.25f;
        }
        byte[] exr = ExrWriter.encodeDepth(w, h, z);
        ByteBuffer b = ByteBuffer.wrap(exr).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(20000630, b.getInt());
        assertEquals(2, b.getInt());
        // Skip the attributes to the offset table: two blocks of 16 and 4 lines.
        int pos = 8;
        while (exr[pos] != 0) {
            while (exr[pos++] != 0) {
                // name
            }
            while (exr[pos++] != 0) {
                // type
            }
            int size = b.getInt(pos);
            pos += 4 + size;
        }
        pos++;
        long first = b.getLong(pos);
        long second = b.getLong(pos + 8);
        assertEquals(pos + 16, first);
        assertEquals(0, b.getInt((int) first));
        assertEquals(16, b.getInt((int) second));
        int size = b.getInt((int) second + 4);
        byte[] packed = new byte[size];
        System.arraycopy(exr, (int) second + 8, packed, 0, size);
        int raw = 4 * w * 4;
        byte[] lines = size < raw ? unzip(packed, raw) : packed;
        ByteBuffer px = ByteBuffer.wrap(lines).order(ByteOrder.LITTLE_ENDIAN);
        // Line 16, pixel 3.
        assertEquals(z[16 * w + 3], px.getFloat(3 * 4), 0);
    }

    @Test
    void unpremultiplyRestoresStraightColour() {
        byte[] px = {64, 32, 0, (byte) 128, 10, 20, 30, 0, 1, 2, 3, (byte) 255};
        Pixels.unpremultiply8(px);
        assertEquals(128, px[0] & 0xFF);
        assertEquals(64, px[1] & 0xFF);
        assertEquals(0, px[4]);
        assertEquals(3, px[10]);
    }

    @Test
    void cutSkyIsPremultipliedTransparent() {
        byte[] px = {(byte) 200, (byte) 200, (byte) 255, 7, 50, 60, 70, 9};
        Pixels.cutSky(px, new float[] {0f, 0.5f});
        assertArrayEquals(new byte[] {0, 0, 0, 0, 50, 60, 70, (byte) 255}, px);
    }

    @Test
    void linearKeepsWhiteAndPremultiplies() {
        byte[] white = Pixels.widen(new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255});
        float[] lin = Pixels.toLinear(white, true);
        assertEquals(1f, lin[0], 1e-6);
        assertEquals(1f, lin[3], 1e-6);
        // Half-covered white: straight white, alpha 0.5 -> premultiplied 0.5.
        byte[] half = new byte[8];
        Pixels.put16(half, 0, 32768);
        Pixels.put16(half, 2, 32768);
        Pixels.put16(half, 4, 32768);
        Pixels.put16(half, 6, 32768);
        float[] h = Pixels.toLinear(half, true);
        assertEquals(0.5f, h[0], 1e-3);
        assertEquals(0.5f, h[3], 1e-3);
    }

    @Test
    void deepMixKeepsPrecision() {
        RenderSettings s = new RenderSettings();
        var units = List.of(unit(1), unit(1));
        byte[] a = {10, 10, 10, (byte) 255};
        byte[] b = {11, 11, 11, (byte) 255};
        byte[] eight = FrameAssembler.mix(units, List.of(a, b), 0);
        byte[] deep = FrameAssembler.mix(units, List.of(a, b), 0, true);
        assertEquals(4, eight.length);
        assertEquals(8, deep.length);
        // 10.5 * 257 = 2698.5: between the 8-bit steps 2570 and 2827.
        int v = Pixels.get16(deep, 0);
        assertTrue(v > 2570 && v < 2827, "got " + v);
        assertEquals(65535, Pixels.get16(deep, 6));
        assertTrue(s.validate().isEmpty());
    }

    @Test
    void depthDistanceAndSky() {
        // Reversed-Z with an infinite far plane: depth = near / distance (m22 = 0, m32 = near).
        assertEquals(10f, DepthPassWriter.distance(0.005f, 0, 0.05), 1e-4);
        assertEquals(DepthPassWriter.SKY, DepthPassWriter.distance(0f, 0, 0.05));
        // Supersampled: each 2x2 block keeps its nearest point.
        float[] depth = {0.1f, 0.2f, 0f, 0f, 0.05f, 0.01f, 0f, 0f};
        float[] out = DepthPassWriter.distances(depth, 4, 2, 1, 2, 0, 0.05);
        assertEquals(0.25f, out[0], 1e-5);
        assertEquals(DepthPassWriter.SKY, out[1]);
    }

    @Test
    void alphaNeedsAnAlphaFormat() {
        RenderSettings s = new RenderSettings();
        s.transparentSky = true;
        assertEquals(1, s.validate().size());
        s.pixelFormat = "yuva444p10le";
        assertTrue(s.validate().isEmpty());
        s.output = RenderSettings.Output.EXR_SEQUENCE;
        s.pixelFormat = "yuv420p";
        assertTrue(s.validate().isEmpty());
        s.passes.add(RenderSettings.Pass.NORMAL);
        assertEquals(1, s.validate().size());
    }

    private static RenderPlan.Unit unit(double weight) {
        return new RenderPlan.Unit(0, 0, 0, 0, null, 0, 0, 0, weight);
    }

    private static byte[] unzip(byte[] packed, int size) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(packed);
        byte[] t = new byte[size];
        int n = 0;
        while (n < size) {
            n += inflater.inflate(t, n, size - n);
        }
        inflater.end();
        for (int i = 1; i < size; i++) {
            t[i] = (byte) ((t[i - 1] & 0xFF) + (t[i] & 0xFF) - 128);
        }
        byte[] out = new byte[size];
        int half = (size + 1) / 2;
        for (int i = 0, a = 0, b = half; i < size; i++) {
            out[i] = (i & 1) == 0 ? t[a++] : t[b++];
        }
        return out;
    }
}
