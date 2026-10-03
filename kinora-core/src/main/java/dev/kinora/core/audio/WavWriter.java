package dev.kinora.core.audio;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes 32-bit float WAV files (lossless for the mix; FFmpeg and every editor read them). */
public final class WavWriter {
    private WavWriter() {}

    public static void writeFloat(Path file, float[] interleaved, int channels, int sampleRate) throws IOException {
        int dataBytes = interleaved.length * 4;
        ByteBuffer header = ByteBuffer.allocate(58).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(50 + dataBytes)
                .put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        // fmt chunk, WAVE_FORMAT_IEEE_FLOAT (3), with the extension size field float formats need.
        header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(18).putShort((short) 3).putShort((short) channels)
                .putInt(sampleRate).putInt(sampleRate * channels * 4).putShort((short) (channels * 4)).putShort((short) 32)
                .putShort((short) 0);
        // fact chunk: sample frames, required for non-PCM formats.
        header.put("fact".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(4).putInt(interleaved.length / channels);
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(dataBytes);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            out.write(header.array(), 0, header.position());
            ByteBuffer buf = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : interleaved) {
                if (buf.remaining() < 4) {
                    out.write(buf.array(), 0, buf.position());
                    buf.clear();
                }
                buf.putFloat(v);
            }
            out.write(buf.array(), 0, buf.position());
        }
    }
}
