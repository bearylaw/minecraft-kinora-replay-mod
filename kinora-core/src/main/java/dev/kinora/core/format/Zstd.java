package dev.kinora.core.format;

import dev.kinora.core.io.MalformedDataException;

import io.airlift.compress.v3.MalformedInputException;
import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;

import java.util.Arrays;

/**
 * Zstandard through aircompressor's pure-Java implementation: no native library to extract, and
 * the same output on every platform. The instances are cheap but not thread-safe, so each thread
 * gets its own.
 */
public final class Zstd {
    private static final ThreadLocal<ZstdJavaCompressor> COMPRESSOR = ThreadLocal.withInitial(ZstdJavaCompressor::new);
    private static final ThreadLocal<ZstdJavaDecompressor> DECOMPRESSOR = ThreadLocal.withInitial(ZstdJavaDecompressor::new);

    private Zstd() {}

    public static byte[] compress(byte[] data, int offset, int length) {
        ZstdJavaCompressor compressor = COMPRESSOR.get();
        byte[] out = new byte[compressor.maxCompressedLength(length)];
        int written = compressor.compress(data, offset, length, out, 0, out.length);
        return Arrays.copyOf(out, written);
    }

    /**
     * Decompresses a frame whose decompressed size the caller knows from the chunk header. A size
     * mismatch is reported as corruption.
     */
    public static byte[] decompress(byte[] data, int offset, int length, int expectedSize) {
        if (expectedSize < 0 || expectedSize > KinoraFormat.MAX_CHUNK_PAYLOAD) {
            throw new MalformedDataException("implausible decompressed size " + expectedSize);
        }
        byte[] out = new byte[expectedSize];
        int written;
        try {
            written = DECOMPRESSOR.get().decompress(data, offset, length, out, 0, out.length);
        } catch (MalformedInputException | IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new MalformedDataException("zstd frame does not decode", e);
        }
        if (written != expectedSize) {
            throw new MalformedDataException("zstd frame decoded to " + written + " bytes, header says " + expectedSize);
        }
        return out;
    }
}
