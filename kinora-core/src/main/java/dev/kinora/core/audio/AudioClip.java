package dev.kinora.core.audio;

/**
 * Decoded audio: interleaved float samples in -1..1.
 *
 * @param samples    {@code frames * channels} values, channel-interleaved
 * @param channels   1 or 2
 * @param sampleRate samples per second per channel
 */
public record AudioClip(float[] samples, int channels, int sampleRate) {
    public int frames() {
        return samples.length / channels;
    }

    /** Sample of one channel at a fractional frame position, linearly interpolated; 0 outside the clip. */
    public float sample(double frame, int channel) {
        int i = (int) Math.floor(frame);
        if (i < 0 || i >= frames()) {
            return 0;
        }
        int c = Math.min(channel, channels - 1);
        float a = samples[i * channels + c];
        float b = i + 1 < frames() ? samples[(i + 1) * channels + c] : 0;
        float f = (float) (frame - i);
        return a + (b - a) * f;
    }

    /** As {@link #sample}, for a sound that repeats: positions wrap around, and so does interpolation. */
    public float sampleLooped(double frame, int channel) {
        int n = frames();
        if (n == 0) {
            return 0;
        }
        double wrapped = frame % n;
        if (wrapped < 0) {
            wrapped += n;
        }
        int i = (int) Math.floor(wrapped);
        int c = Math.min(channel, channels - 1);
        float a = samples[i * channels + c];
        float b = samples[((i + 1) % n) * channels + c];
        float f = (float) (wrapped - i);
        return a + (b - a) * f;
    }
}
