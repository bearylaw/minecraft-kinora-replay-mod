package dev.kinora.core.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * A small, dependency-free PNG encoder for 8- and 16-bit RGB/RGBA images. Output is deterministic:
 * the same pixels always give the same bytes.
 *
 * <p>Rows use the "up"/"sub"/"paeth" filter that compresses best per row (the standard heuristic of
 * minimum sum of absolute differences).
 */
public final class PngWriter {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngWriter() {}

    /**
     * Encodes 8-bit samples.
     *
     * @param pixels   row-major, top row first, {@code channels} bytes per pixel
     * @param channels 3 (RGB) or 4 (RGBA)
     */
    public static byte[] encode8(int width, int height, int channels, byte[] pixels, int level) {
        check(width, height, channels, pixels.length, width * height * channels);
        return encode(width, height, channels, 8, pixels, level);
    }

    /**
     * Encodes 16-bit samples, big-endian as PNG requires.
     *
     * @param samples row-major, top row first, {@code channels} samples per pixel, each 0..65535
     */
    public static byte[] encode16(int width, int height, int channels, short[] samples, int level) {
        check(width, height, channels, samples.length, width * height * channels);
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[2 * i] = (byte) (samples[i] >>> 8);
            bytes[2 * i + 1] = (byte) samples[i];
        }
        return encode(width, height, channels, 16, bytes, level);
    }

    /** Encodes 16-bit samples already stored big-endian, two bytes each. */
    public static byte[] encode16(int width, int height, int channels, byte[] bigEndian, int level) {
        check(width, height, channels, bigEndian.length / 2, width * height * channels);
        return encode(width, height, channels, 16, bigEndian, level);
    }

    /** Encodes packed 0xAARRGGBB pixels as 8-bit RGB (alpha dropped) or RGBA. */
    public static byte[] encodeArgb(int width, int height, int[] argb, boolean alpha, int level) {
        int channels = alpha ? 4 : 3;
        byte[] pixels = new byte[width * height * channels];
        int o = 0;
        for (int p : argb) {
            pixels[o++] = (byte) (p >>> 16);
            pixels[o++] = (byte) (p >>> 8);
            pixels[o++] = (byte) p;
            if (alpha) {
                pixels[o++] = (byte) (p >>> 24);
            }
        }
        return encode8(width, height, channels, pixels, level);
    }

    private static void check(int width, int height, int channels, int have, int want) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("empty image " + width + "x" + height);
        }
        if (channels != 3 && channels != 4) {
            throw new IllegalArgumentException("channels must be 3 or 4");
        }
        if (have != want) {
            throw new IllegalArgumentException("expected " + want + " samples, got " + have);
        }
    }

    private static byte[] encode(int width, int height, int channels, int bitDepth, byte[] data, int level) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 1024);
            out.write(SIGNATURE);
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream(13);
            writeInt(ihdr, width);
            writeInt(ihdr, height);
            ihdr.write(bitDepth);
            ihdr.write(channels == 4 ? 6 : 2);
            ihdr.write(0);
            ihdr.write(0);
            ihdr.write(0);
            chunk(out, "IHDR", ihdr.toByteArray());

            int bpp = channels * bitDepth / 8;
            int stride = width * bpp;
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(data.length / 2 + 1024);
            Deflater deflater = new Deflater(level);
            try (DeflaterOutputStream z = new DeflaterOutputStream(compressed, deflater, 1 << 16)) {
                byte[] filtered = new byte[stride + 1];
                byte[] best = new byte[stride + 1];
                for (int y = 0; y < height; y++) {
                    int row = y * stride;
                    long bestScore = Long.MAX_VALUE;
                    for (int filter = 0; filter <= 4; filter++) {
                        filtered[0] = (byte) filter;
                        long score = 0;
                        for (int i = 0; i < stride; i++) {
                            int x = data[row + i] & 0xFF;
                            int a = i >= bpp ? data[row + i - bpp] & 0xFF : 0;
                            int b = y > 0 ? data[row - stride + i] & 0xFF : 0;
                            int c = i >= bpp && y > 0 ? data[row - stride + i - bpp] & 0xFF : 0;
                            int v = switch (filter) {
                                case 0 -> x;
                                case 1 -> x - a;
                                case 2 -> x - b;
                                case 3 -> x - ((a + b) >>> 1);
                                default -> x - paeth(a, b, c);
                            };
                            filtered[i + 1] = (byte) v;
                            score += Math.abs((byte) v);
                        }
                        if (score < bestScore) {
                            bestScore = score;
                            System.arraycopy(filtered, 0, best, 0, filtered.length);
                        }
                    }
                    z.write(best);
                }
            } finally {
                deflater.end();
            }
            chunk(out, "IDAT", compressed.toByteArray());
            chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory PNG encoding failed", e);
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(out, data.length);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(OutputStream out, int v) throws IOException {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }
}
