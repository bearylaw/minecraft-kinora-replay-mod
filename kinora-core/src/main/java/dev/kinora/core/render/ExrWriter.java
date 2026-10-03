package dev.kinora.core.render;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;

/**
 * A small OpenEXR encoder: scanline images with ZIP compression (16 lines per block), half or full
 * float channels. Enough for colour (linear RGB(A), premultiplied as EXR expects) and depth.
 * Output is deterministic.
 */
public final class ExrWriter {
    private ExrWriter() {}

    /** EXR pixel types. */
    public enum Type {
        HALF(1, 2), FLOAT(2, 4);

        final int code;
        final int bytes;

        Type(int code, int bytes) {
            this.code = code;
            this.bytes = bytes;
        }
    }

    /** One channel: its name and where its values come from ({@code index} into each pixel of {@code stride} floats). */
    public record Channel(String name, Type type, int index) {}

    private static final int LINES_PER_BLOCK = 16;
    private static final int ZIP_COMPRESSION = 3;

    /**
     * Linear colour, premultiplied by alpha if there is alpha.
     *
     * @param pixels {@code stride} floats per pixel (R, G, B and, with alpha, A), top row first
     */
    public static byte[] encodeColor(int width, int height, float[] pixels, boolean alpha) {
        int stride = alpha ? 4 : 3;
        List<Channel> channels = alpha
                ? List.of(new Channel("A", Type.HALF, 3), new Channel("B", Type.HALF, 2), new Channel("G", Type.HALF, 1), new Channel("R", Type.HALF, 0))
                : List.of(new Channel("B", Type.HALF, 2), new Channel("G", Type.HALF, 1), new Channel("R", Type.HALF, 0));
        return encode(width, height, channels, pixels, stride);
    }

    /**
     * Depth: one full-float channel, in blocks (metres) along the view axis. It is named Y (grey), not
     * Z, so that every EXR reader shows it as an image; compositors read it as any other channel.
     */
    public static byte[] encodeDepth(int width, int height, float[] z) {
        return encode(width, height, List.of(new Channel("Y", Type.FLOAT, 0)), z, 1);
    }

    /**
     * @param channels in alphabetical order by name, as EXR requires
     * @param pixels   {@code stride} floats per pixel, top row first
     */
    public static byte[] encode(int width, int height, List<Channel> channels, float[] pixels, int stride) {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        le32(header, 20000630);
        le32(header, 2);
        ByteArrayOutputStream chlist = new ByteArrayOutputStream();
        for (Channel c : channels) {
            chlist.writeBytes(c.name().getBytes(StandardCharsets.US_ASCII));
            chlist.write(0);
            le32(chlist, c.type().code);
            // pLinear and three reserved bytes, then x and y sampling.
            chlist.writeBytes(new byte[4]);
            le32(chlist, 1);
            le32(chlist, 1);
        }
        chlist.write(0);
        attribute(header, "channels", "chlist", chlist.toByteArray());
        attribute(header, "compression", "compression", new byte[] {ZIP_COMPRESSION});
        byte[] box = box(width, height);
        attribute(header, "dataWindow", "box2i", box);
        attribute(header, "displayWindow", "box2i", box);
        attribute(header, "lineOrder", "lineOrder", new byte[] {0});
        attribute(header, "pixelAspectRatio", "float", le32f(1f));
        ByteBuffer centre = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0).putFloat(0);
        attribute(header, "screenWindowCenter", "v2f", centre.array());
        attribute(header, "screenWindowWidth", "float", le32f(1f));
        header.write(0);

        int blocks = (height + LINES_PER_BLOCK - 1) / LINES_PER_BLOCK;
        int lineBytes = 0;
        for (Channel c : channels) {
            lineBytes += width * c.type().bytes;
        }
        byte[][] chunks = new byte[blocks][];
        int finalLineBytes = lineBytes;
        java.util.stream.IntStream.range(0, blocks).parallel().forEach(b -> {
            int y0 = b * LINES_PER_BLOCK;
            int lines = Math.min(LINES_PER_BLOCK, height - y0);
            ByteBuffer raw = ByteBuffer.allocate(lines * finalLineBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = y0; y < y0 + lines; y++) {
                for (Channel c : channels) {
                    int base = y * width * stride + c.index();
                    for (int x = 0; x < width; x++) {
                        float v = pixels[base + x * stride];
                        if (c.type() == Type.HALF) {
                            raw.putShort(Float.floatToFloat16(v));
                        } else {
                            raw.putFloat(v);
                        }
                    }
                }
            }
            byte[] data = raw.array();
            byte[] packed = zip(data);
            byte[] stored = packed.length < data.length ? packed : data;
            ByteBuffer chunk = ByteBuffer.allocate(8 + stored.length).order(ByteOrder.LITTLE_ENDIAN);
            chunk.putInt(y0).putInt(stored.length).put(stored);
            chunks[b] = chunk.array();
        });

        byte[] head = header.toByteArray();
        long offset = head.length + 8L * blocks;
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE - 8, offset + (long) blocks * finalLineBytes));
        out.writeBytes(head);
        ByteBuffer table = ByteBuffer.allocate(8 * blocks).order(ByteOrder.LITTLE_ENDIAN);
        for (byte[] chunk : chunks) {
            table.putLong(offset);
            offset += chunk.length;
        }
        out.writeBytes(table.array());
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    /** OpenEXR's ZIP: bytes split into even and odd halves, delta-coded, then zlib. */
    static byte[] zip(byte[] data) {
        int n = data.length;
        byte[] t = new byte[n];
        int half = (n + 1) / 2;
        for (int i = 0, a = 0, b = half; i < n; i++) {
            if ((i & 1) == 0) {
                t[a++] = data[i];
            } else {
                t[b++] = data[i];
            }
        }
        int p = t[0] & 0xFF;
        for (int i = 1; i < n; i++) {
            int v = t[i] & 0xFF;
            t[i] = (byte) (v - p + 128 + 256);
            p = v;
        }
        Deflater deflater = new Deflater(4);
        try {
            deflater.setInput(t);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(n / 2 + 64);
            byte[] buf = new byte[1 << 16];
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] box(int width, int height) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0).putInt(width - 1).putInt(height - 1).array();
    }

    private static void attribute(ByteArrayOutputStream out, String name, String type, byte[] value) {
        out.writeBytes(name.getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        le32(out, value.length);
        out.writeBytes(value);
    }

    private static void le32(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >>> 8);
        out.write(v >>> 16);
        out.write(v >>> 24);
    }

    private static byte[] le32f(float v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array();
    }
}
