package dev.kinora.core.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Marks an MP4 or MOV as 360° video: adds Spherical Video V2 boxes ({@code st3d}, {@code sv3d} with an
 * equirectangular projection) to the video track's sample description, as YouTube and 360° players
 * expect. Updates the sizes of the boxes around them and, when the movie header comes before the
 * media data, the chunk offsets. The file is rewritten in place (through a temporary file).
 */
public final class SphericalMetadata {
    private SphericalMetadata() {}

    private static final List<String> CONTAINERS = List.of("moov", "trak", "mdia", "minf", "stbl");

    /** Adds equirectangular mono metadata to the first video track. */
    public static void markEquirectangular(Path file) throws IOException {
        List<long[]> top = topLevelBoxes(file);
        long[] moov = null;
        long mdatStart = -1;
        for (long[] b : top) {
            String type = typeAt(file, b[0]);
            if (type.equals("moov")) {
                moov = b;
            } else if (type.equals("mdat") && mdatStart < 0) {
                mdatStart = b[0];
            }
        }
        if (moov == null) {
            throw new IOException("no movie header in " + file.getFileName());
        }
        if (moov[1] > Integer.MAX_VALUE) {
            throw new IOException("movie header too large");
        }
        byte[] moovBytes = new byte[(int) moov[1]];
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ch.read(ByteBuffer.wrap(moovBytes), moov[0]);
        }
        byte[] extra = boxes();
        byte[] patched = insertIntoVideoEntry(moovBytes, extra);
        int delta = patched.length - moovBytes.length;
        if (mdatStart > moov[0]) {
            // The media data moves back by what the header grew.
            shiftChunkOffsets(patched, delta);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".sv3d");
        try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(temp)) {
            copy(in, out, moov[0]);
            in.skipNBytes(moov[1]);
            out.write(patched);
            in.transferTo(out);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** {@code st3d} (mono) and {@code sv3d} (equirectangular, no cropping, no pose). */
    static byte[] boxes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(fullBox("st3d", new byte[] {0}));
        byte[] source = "Kinora\0".getBytes(StandardCharsets.US_ASCII);
        byte[] svhd = fullBox("svhd", source);
        byte[] prhd = fullBox("prhd", new byte[12]);
        byte[] equi = fullBox("equi", new byte[16]);
        byte[] proj = box("proj", concat(prhd, equi));
        out.writeBytes(box("sv3d", concat(svhd, proj)));
        return out.toByteArray();
    }

    /**
     * The moov box with {@code extra} appended to the first video sample entry; every box on the way
     * grows by its length.
     */
    static byte[] insertIntoVideoEntry(byte[] moov, byte[] extra) throws IOException {
        List<Integer> path = new ArrayList<>();
        int entry = findVideoEntry(moov, 0, moov.length, path);
        if (entry < 0) {
            throw new IOException("no video track");
        }
        int entryEnd = entry + ByteBuffer.wrap(moov).getInt(entry);
        byte[] out = new byte[moov.length + extra.length];
        System.arraycopy(moov, 0, out, 0, entryEnd);
        System.arraycopy(extra, 0, out, entryEnd, extra.length);
        System.arraycopy(moov, entryEnd, out, entryEnd + extra.length, moov.length - entryEnd);
        ByteBuffer b = ByteBuffer.wrap(out);
        path.add(entry);
        for (int at : path) {
            b.putInt(at, b.getInt(at) + extra.length);
        }
        return out;
    }

    /**
     * Offset of the first video sample entry (inside stsd of a track whose handler is 'vide'), or -1.
     * {@code path} collects the offsets of the boxes that contain it.
     */
    private static int findVideoEntry(byte[] data, int start, int end, List<Integer> path) {
        ByteBuffer b = ByteBuffer.wrap(data);
        int at = start;
        while (at + 8 <= end) {
            int size = b.getInt(at);
            if (size < 8 || at + size > end) {
                return -1;
            }
            String type = new String(data, at + 4, 4, StandardCharsets.US_ASCII);
            if (CONTAINERS.contains(type)) {
                if (type.equals("trak") && !isVideoTrack(data, at + 8, at + size)) {
                    at += size;
                    continue;
                }
                path.add(at);
                int found = findVideoEntry(data, at + 8, at + size, path);
                if (found >= 0) {
                    return found;
                }
                path.removeLast();
            } else if (type.equals("stsd")) {
                // FullBox header (4) and entry count (4), then the entries.
                path.add(at);
                return at + 16 <= at + size ? at + 16 : -1;
            }
            at += size;
        }
        return -1;
    }

    private static boolean isVideoTrack(byte[] data, int start, int end) {
        ByteBuffer b = ByteBuffer.wrap(data);
        int at = start;
        while (at + 8 <= end) {
            int size = b.getInt(at);
            if (size < 8 || at + size > end) {
                return false;
            }
            String type = new String(data, at + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("mdia")) {
                return isVideoTrack(data, at + 8, at + size);
            }
            if (type.equals("hdlr")) {
                // FullBox (4), pre_defined (4), handler_type.
                return new String(data, at + 16, 4, StandardCharsets.US_ASCII).equals("vide");
            }
            at += size;
        }
        return false;
    }

    /** Adds {@code delta} to every chunk offset (stco, co64) in the moov box. */
    static void shiftChunkOffsets(byte[] moov, int delta) {
        ByteBuffer b = ByteBuffer.wrap(moov);
        shift(moov, b, 8, moov.length, delta);
    }

    private static void shift(byte[] data, ByteBuffer b, int start, int end, int delta) {
        int at = start;
        while (at + 8 <= end) {
            int size = b.getInt(at);
            if (size < 8 || at + size > end) {
                return;
            }
            String type = new String(data, at + 4, 4, StandardCharsets.US_ASCII);
            if (CONTAINERS.contains(type)) {
                shift(data, b, at + 8, at + size, delta);
            } else if (type.equals("stco")) {
                int count = b.getInt(at + 12);
                for (int i = 0; i < count; i++) {
                    int p = at + 16 + i * 4;
                    b.putInt(p, (int) ((b.getInt(p) & 0xFFFFFFFFL) + delta));
                }
            } else if (type.equals("co64")) {
                int count = b.getInt(at + 12);
                for (int i = 0; i < count; i++) {
                    int p = at + 16 + i * 8;
                    b.putLong(p, b.getLong(p) + delta);
                }
            }
            at += size;
        }
    }

    /** [offset, size] of each top-level box. */
    private static List<long[]> topLevelBoxes(Path file) throws IOException {
        List<long[]> boxes = new ArrayList<>();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long length = ch.size();
            long at = 0;
            ByteBuffer head = ByteBuffer.allocate(16);
            while (at + 8 <= length) {
                head.clear();
                ch.read(head, at);
                long size = head.getInt(0) & 0xFFFFFFFFL;
                if (size == 1) {
                    size = head.getLong(8);
                } else if (size == 0) {
                    size = length - at;
                }
                if (size < 8) {
                    throw new IOException("broken box at " + at);
                }
                boxes.add(new long[] {at, size});
                at += size;
            }
        }
        return boxes;
    }

    private static String typeAt(Path file, long offset) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer t = ByteBuffer.allocate(4);
            ch.read(t, offset + 4);
            return new String(t.array(), StandardCharsets.US_ASCII);
        }
    }

    private static void copy(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[1 << 16];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) {
                throw new IOException("file ended early");
            }
            out.write(buf, 0, r);
            n -= r;
        }
    }

    private static byte[] box(String type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length);
        b.putInt(8 + payload.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(payload);
        return b.array();
    }

    private static byte[] fullBox(String type, byte[] payload) {
        return box(type, concat(new byte[4], payload));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
