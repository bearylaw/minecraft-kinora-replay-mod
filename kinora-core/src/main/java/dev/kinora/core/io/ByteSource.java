package dev.kinora.core.io;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads what {@link ByteSink} writes. Every read is bounds-checked and throws
 * {@link MalformedDataException} rather than an index exception, so a corrupt file surfaces as a
 * format error the caller can recover from.
 */
public final class ByteSource {
    private final byte[] data;
    private final int end;
    private int position;

    public ByteSource(byte[] data) {
        this(data, 0, data.length);
    }

    public ByteSource(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("offset " + offset + " length " + length + " of " + data.length);
        }
        this.data = data;
        this.position = offset;
        this.end = offset + length;
    }

    private void need(int count) {
        if (count < 0 || position + count > end) {
            throw new MalformedDataException("unexpected end of data: need " + count + " bytes, have " + (end - position));
        }
    }

    public int readUnsignedByte() {
        need(1);
        return data[position++] & 0xFF;
    }

    public byte readByte() {
        need(1);
        return data[position++];
    }

    public boolean readBoolean() {
        int value = readUnsignedByte();
        if (value > 1) {
            throw new MalformedDataException("boolean byte " + value);
        }
        return value == 1;
    }

    public int readUnsignedShort() {
        need(2);
        int value = (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8;
        position += 2;
        return value;
    }

    public int readInt() {
        need(4);
        int value = (data[position] & 0xFF)
                | (data[position + 1] & 0xFF) << 8
                | (data[position + 2] & 0xFF) << 16
                | (data[position + 3] & 0xFF) << 24;
        position += 4;
        return value;
    }

    public long readLong() {
        need(8);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= (data[position + i] & 0xFFL) << (8 * i);
        }
        position += 8;
        return value;
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public long readVarLong() {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int b = readUnsignedByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new MalformedDataException("varlong longer than 10 bytes");
    }

    public int readVarInt() {
        long value = readVarLong();
        if ((value & ~0xFFFFFFFFL) != 0) {
            throw new MalformedDataException("varint out of range: " + value);
        }
        return (int) value;
    }

    /** A varint that must lie in {@code [0, max]}; guards allocations against corrupt lengths. */
    public int readVarIntBounded(int max) {
        int value = readVarInt();
        if (value < 0 || value > max) {
            throw new MalformedDataException("value " + Integer.toUnsignedString(value) + " exceeds limit " + max);
        }
        return value;
    }

    public long readSignedVarLong() {
        long raw = readVarLong();
        return (raw >>> 1) ^ -(raw & 1);
    }

    public byte[] readBytes(int count) {
        need(count);
        byte[] out = Arrays.copyOfRange(data, position, position + count);
        position += count;
        return out;
    }

    public byte[] readByteArray() {
        return readBytes(readVarIntBounded(remaining()));
    }

    public String readString() {
        int length = readVarIntBounded(remaining());
        need(length);
        String value = new String(data, position, length, StandardCharsets.UTF_8);
        position += length;
        return value;
    }

    public void skip(int count) {
        need(count);
        position += count;
    }

    public int position() {
        return position;
    }

    public int remaining() {
        return end - position;
    }

    public boolean hasRemaining() {
        return position < end;
    }

    /** The backing array, for zero-copy slicing together with {@link #position()}. */
    public byte[] array() {
        return data;
    }
}
