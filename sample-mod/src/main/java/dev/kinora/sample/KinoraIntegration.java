package dev.kinora.sample;

import dev.kinora.api.Kinora;
import dev.kinora.api.RenderClock;
import dev.kinora.api.ReplayTime;
import dev.kinora.api.event.KinoraListener;
import dev.kinora.api.track.DataTrack;
import dev.kinora.api.track.TrackHandler;

import java.nio.ByteBuffer;

/**
 * Everything that touches Kinora. Only loaded when Kinora is installed (see {@link SampleMod}), so
 * the API classes are never needed otherwise.
 */
final class KinoraIntegration {
    static final String COUNTER = SampleMod.MOD_ID + ":counter";

    private static DataTrack track;

    private KinoraIntegration() {}

    static void setUp() {
        // Version 1 of our data: three doubles and an int per burst. Bump it if the layout changes;
        // the version a replay was recorded with is passed back to onData.
        track = Kinora.registerTrack(SampleMod.MOD_ID + ":sparkles", 1, new TrackHandler() {
            @Override
            public void onData(byte[] data, int version, ReplayTime time) {
                ByteBuffer in = ByteBuffer.wrap(data);
                double x = in.getDouble();
                double y = in.getDouble();
                double z = in.getDouble();
                int amount = in.getInt();
                // Seeded from the replay and its tick: identical in every render of this moment.
                SampleMod.burst(x, y, z, amount, Kinora.random(Double.doubleToLongBits(x * 31 + z)));
            }

            @Override
            public byte[] captureState() {
                // Kinora asks for this at every snapshot, so a seek can put the counter right.
                return ByteBuffer.allocate(4).putInt(SampleMod.count).array();
            }

            @Override
            public void restoreState(byte[] state, int version) {
                SampleMod.count = ByteBuffer.wrap(state).getInt();
            }

            @Override
            public void reset() {
                SampleMod.count = 0;
            }
        });
        // Users can hide the counter in replays and renders (Esc menu, "Mod elements...").
        Kinora.registerHideable(COUNTER, "kinora_sample.hideable.counter");
        Kinora.addListener(new KinoraListener() {
            @Override
            public void onReplayStart() {
                SampleMod.count = 0;
            }

            @Override
            public void onRenderStart() {
                org.slf4j.LoggerFactory.getLogger("Kinora Sample").info("A Kinora render started; sparkles will follow the render clock");
            }

            @Override
            public void onFrameBegin(RenderClock clock) {
                // Animations should use clock.outputSeconds() here instead of the wall clock.
            }
        });
    }

    static void recordBurst(double x, double y, double z, int amount) {
        track.write(ByteBuffer.allocate(28).putDouble(x).putDouble(y).putDouble(z).putInt(amount).array());
    }

    static boolean replaying() {
        return Kinora.isReplaying();
    }

    static boolean counterHidden() {
        return Kinora.isHidden(COUNTER);
    }
}
