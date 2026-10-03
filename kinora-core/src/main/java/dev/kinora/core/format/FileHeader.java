package dev.kinora.core.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;

/**
 * The 48 bytes at the start of every {@code .kinora} file.
 *
 * <pre>
 *  0  8  magic 89 4B 4E 52 0D 0A 1A 0A
 *  8  2  format major
 * 10  2  format minor
 * 12  4  flags, reserved, 0
 * 16 16  file id: UUID most significant then least significant half, each little-endian
 * 32  8  creation time, milliseconds since the Unix epoch
 * 40  4  reserved, 0
 * 44  4  CRC32C of bytes 0..43
 * </pre>
 */
public record FileHeader(int major, int minor, int flags, UUID fileId, long createdMillis) {

    public static FileHeader create(UUID fileId, long createdMillis) {
        return new FileHeader(KinoraFormat.FORMAT_MAJOR, KinoraFormat.FORMAT_MINOR, 0, fileId, createdMillis);
    }

    public byte[] toBytes() {
        ByteBuffer out = ByteBuffer.allocate(KinoraFormat.FILE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        out.put(KinoraFormat.MAGIC);
        out.putShort((short) major);
        out.putShort((short) minor);
        out.putInt(flags);
        out.putLong(fileId.getMostSignificantBits());
        out.putLong(fileId.getLeastSignificantBits());
        out.putLong(createdMillis);
        out.putInt(0);
        out.putInt(ChunkHeader.crc(out, 0, 44));
        return out.array();
    }

    /** Thrown for files that are not {@code .kinora} at all, or come from an incompatible future. */
    public static final class InvalidHeaderException extends java.io.IOException {
        public InvalidHeaderException(String message) {
            super(message);
        }
    }

    public static FileHeader read(byte[] bytes) throws InvalidHeaderException {
        if (bytes.length < 8 || !Arrays.equals(bytes, 0, 8, KinoraFormat.MAGIC, 0, 8)) {
            throw new InvalidHeaderException("not a Kinora replay (wrong magic)");
        }
        if (bytes.length < KinoraFormat.FILE_HEADER_SIZE) {
            throw new InvalidHeaderException("Kinora header is truncated");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes, 0, KinoraFormat.FILE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getInt(44) != ChunkHeader.crc(in, 0, 44)) {
            throw new InvalidHeaderException("Kinora header is damaged (checksum mismatch)");
        }
        int major = in.getShort(8) & 0xFFFF;
        int minor = in.getShort(10) & 0xFFFF;
        if (major != KinoraFormat.FORMAT_MAJOR) {
            throw new InvalidHeaderException("format version " + major + "." + minor
                    + " is not readable by this Kinora (reads " + KinoraFormat.FORMAT_MAJOR + ".x)");
        }
        int flags = in.getInt(12);
        UUID id = new UUID(in.getLong(16), in.getLong(24));
        long created = in.getLong(32);
        return new FileHeader(major, minor, flags, id, created);
    }
}
