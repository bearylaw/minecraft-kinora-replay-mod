package dev.kinora.core.render;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SphericalMetadataTest {
    @Test
    void growsTheBoxesAroundTheVideoEntryAndShiftsOffsets() throws Exception {
        byte[] entry = box("avc1", new byte[78]);
        byte[] stsd = box("stsd", concat(new byte[] {0, 0, 0, 0, 0, 0, 0, 1}, entry));
        byte[] stco = box("stco", new byte[] {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0x10, 0});
        byte[] stbl = box("stbl", concat(stsd, stco));
        byte[] minf = box("minf", stbl);
        byte[] hdlr = box("hdlr", concat(new byte[8], "vide".getBytes(StandardCharsets.US_ASCII)));
        byte[] mdia = box("mdia", concat(hdlr, minf));
        byte[] trak = box("trak", mdia);
        byte[] moov = box("moov", trak);
        byte[] extra = SphericalMetadata.boxes();

        byte[] out = SphericalMetadata.insertIntoVideoEntry(moov, extra);
        SphericalMetadata.shiftChunkOffsets(out, extra.length);
        ByteBuffer b = ByteBuffer.wrap(out);
        assertEquals(moov.length + extra.length, b.getInt(0));
        assertEquals(out.length, b.getInt(0));
        // trak at 8, mdia at 16, hdlr at 24.
        assertEquals(trak.length + extra.length, b.getInt(8));
        int entryAt = 8 + 8 + 8 + hdlr.length + 8 + 8 + 16;
        assertEquals("avc1", new String(out, entryAt + 4, 4, StandardCharsets.US_ASCII));
        assertEquals(entry.length + extra.length, b.getInt(entryAt));
        assertEquals("st3d", new String(out, entryAt + entry.length + 4, 4, StandardCharsets.US_ASCII));
        int stcoAt = entryAt + entry.length + extra.length;
        assertEquals("stco", new String(out, stcoAt + 4, 4, StandardCharsets.US_ASCII));
        assertEquals(0x1000 + extra.length, b.getInt(stcoAt + 16));
    }

    private static byte[] box(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(8).putInt(8 + payload.length).put(type.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(head.array());
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
