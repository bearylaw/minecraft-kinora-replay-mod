package dev.kinora.core.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/**
 * The 40-byte header in front of every chunk.
 *
 * <pre>
 *  0  4  sync "KCHK"
 *  4  4  type FourCC
 *  8  2  flags (FLAG_ZSTD, FLAG_CRITICAL)
 * 10  2  reserved, 0
 * 12  8  sequence number, 0-based and contiguous within a file
 * 20  4  stored payload length
 * 24  4  raw payload length (equal to stored when not compressed)
 * 28  4  CRC32C of the stored payload
 * 32  4  reserved, 0
 * 36  4  CRC32C of bytes 0..35
 * </pre>
 */
public record ChunkHeader(int type, int flags, long sequence, int storedLength, int rawLength, int payloadCrc) {

    public boolean compressed() {
        return (flags & KinoraFormat.FLAG_ZSTD) != 0;
    }

    public boolean critical() {
        return (flags & KinoraFormat.FLAG_CRITICAL) != 0;
    }

    public String typeName() {
        return KinoraFormat.fourCCName(type);
    }

    public void write(ByteBuffer out) {
        int start = out.position();
        ByteBuffer le = out.order(ByteOrder.LITTLE_ENDIAN);
        le.putInt(KinoraFormat.CHUNK_SYNC);
        le.putInt(type);
        le.putShort((short) flags);
        le.putShort((short) 0);
        le.putLong(sequence);
        le.putInt(storedLength);
        le.putInt(rawLength);
        le.putInt(payloadCrc);
        le.putInt(0);
        le.putInt(crc(out, start, 36));
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(KinoraFormat.CHUNK_HEADER_SIZE);
        write(buffer);
        return buffer.array();
    }

    /**
     * Parses a header from exactly {@link KinoraFormat#CHUNK_HEADER_SIZE} bytes at the buffer's
     * position, or returns null if the sync word or header checksum is wrong. Advances the
     * position only on success.
     */
    public static ChunkHeader read(ByteBuffer in) {
        if (in.remaining() < KinoraFormat.CHUNK_HEADER_SIZE) {
            return null;
        }
        int start = in.position();
        ByteBuffer le = in.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (le.getInt(start) != KinoraFormat.CHUNK_SYNC) {
            return null;
        }
        if (le.getInt(start + 36) != crc(in, start, 36)) {
            return null;
        }
        int type = le.getInt(start + 4);
        int flags = le.getShort(start + 8) & 0xFFFF;
        long sequence = le.getLong(start + 12);
        int stored = le.getInt(start + 20);
        int raw = le.getInt(start + 24);
        int payloadCrc = le.getInt(start + 28);
        if (stored < 0 || stored > KinoraFormat.MAX_CHUNK_PAYLOAD || raw < 0 || raw > KinoraFormat.MAX_CHUNK_PAYLOAD || sequence < 0) {
            return null;
        }
        if ((flags & KinoraFormat.FLAG_ZSTD) == 0 && stored != raw) {
            return null;
        }
        in.position(start + KinoraFormat.CHUNK_HEADER_SIZE);
        return new ChunkHeader(type, flags, sequence, stored, raw, payloadCrc);
    }

    static int crc(ByteBuffer buffer, int offset, int length) {
        CRC32C crc = new CRC32C();
        ByteBuffer slice = buffer.duplicate();
        slice.limit(offset + length).position(offset);
        crc.update(slice);
        return (int) crc.getValue();
    }

    public static int payloadCrc(byte[] data, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }
}
