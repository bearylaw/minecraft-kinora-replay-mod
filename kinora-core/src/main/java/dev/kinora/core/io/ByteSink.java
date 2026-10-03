package dev.kinora.core.io;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A growable little-endian byte buffer with variable-length integer encoding.
 *
 * <p>Every multi-byte field in the {@code .kinora} format is little-endian; variable-length
 * integers are unsigned LEB128 ({@link #writeVarLong}) or zig-zag LEB128 for signed values
 * ({@link #writeSignedVarLong}). Not thread-safe.
 */
public final class ByteSink {
    private byte[] data;
    private int size;

    public ByteSink() {
        this(256);
    }

    public ByteSink(int initialCapacity) {
        data = new byte[Math.max(16, initialCapacity)];
    }

    private void ensure(int extra) {
        int needed = size + extra;
        if (needed < 0) {
            throw new IllegalStateException("ByteSink exceeds 2 GiB");
        }
        if (needed > data.length) {
            int grown = Math.max(needed, data.length + (data.length >> 1));
            if (grown < 0) {
                grown = Integer.MAX_VALUE - 8;
            }
            data = Arrays.copyOf(data, grown);
        }
    }

    public ByteSink writeByte(int value) {
        ensure(1);
        data[size++] = (byte) value;
        return this;
    }

    public ByteSink writeBoolean(boolean value) {
        return writeByte(value ? 1 : 0);
    }

    public ByteSink writeShort(int value) {
        ensure(2);
        data[size++] = (byte) value;
        data[size++] = (byte) (value >>> 8);
        return this;
    }

    public ByteSink writeInt(int value) {
        ensure(4);
        data[size++] = (byte) value;
        data[size++] = (byte) (value >>> 8);
        data[size++] = (byte) (value >>> 16);
        data[size++] = (byte) (value >>> 24);
        return this;
    }

    public ByteSink writeLong(long value) {
        ensure(8);
        for (int i = 0; i < 8; i++) {
            data[size++] = (byte) (value >>> (8 * i));
        }
        return this;
    }

    public ByteSink writeFloat(float value) {
        return writeInt(Float.floatToRawIntBits(value));
    }

    public ByteSink writeDouble(double value) {
        return writeLong(Double.doubleToRawLongBits(value));
    }

    /** Unsigned LEB128. Negative values take ten bytes; use {@link #writeSignedVarLong} for those. */
    public ByteSink writeVarLong(long value) {
        ensure(10);
        while ((value & ~0x7FL) != 0) {
            data[size++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        data[size++] = (byte) value;
        return this;
    }

    public ByteSink writeVarInt(int value) {
        return writeVarLong(value & 0xFFFFFFFFL);
    }

    /** Zig-zag encoded LEB128, so small negative numbers stay small. */
    public ByteSink writeSignedVarLong(long value) {
        return writeVarLong((value << 1) ^ (value >> 63));
    }

    public ByteSink writeBytes(byte[] bytes) {
        return writeBytes(bytes, 0, bytes.length);
    }

    public ByteSink writeBytes(byte[] bytes, int offset, int length) {
        ensure(length);
        System.arraycopy(bytes, offset, data, size, length);
        size += length;
        return this;
    }

    /** A length-prefixed (varint) byte array. */
    public ByteSink writeByteArray(byte[] bytes) {
        writeVarInt(bytes.length);
        return writeBytes(bytes);
    }

    /** A length-prefixed (varint) UTF-8 string. */
    public ByteSink writeString(String value) {
        return writeByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    public int size() {
        return size;
    }

    public void reset() {
        size = 0;
    }

    /** Overwrites four bytes at {@code position}, which must already have been written. */
    public void setInt(int position, int value) {
        if (position < 0 || position + 4 > size) {
            throw new IndexOutOfBoundsException(position);
        }
        data[position] = (byte) value;
        data[position + 1] = (byte) (value >>> 8);
        data[position + 2] = (byte) (value >>> 16);
        data[position + 3] = (byte) (value >>> 24);
    }

    /** The backing array; only the first {@link #size()} bytes are meaningful. */
    public byte[] array() {
        return data;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(data, size);
    }
}
