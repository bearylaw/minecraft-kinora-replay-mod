package dev.kinora.mc;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Client settings, stored in {@code config/kinora-client.toml} and edited in Kinora's settings
 * screen. Read through the accessors, which fall back to the defaults before the file is loaded.
 */
public final class KinoraConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public enum AutoRecord { OFF, SINGLEPLAYER, MULTIPLAYER, ALWAYS }

    // ------------------------------------------------------------------ recording

    static {
        BUILDER.comment("Recording").push("recording");
    }

    public static final ModConfigSpec.EnumValue<AutoRecord> AUTO_RECORD = BUILDER
            .comment("Start recording automatically when joining a world or server.")
            .translation("kinora.config.autoRecord")
            .defineEnum("autoRecord", AutoRecord.OFF);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> AUTO_RECORD_EXCLUDED_SERVERS = BUILDER
            .comment("Server addresses never recorded automatically, even when autoRecord includes multiplayer.")
            .translation("kinora.config.autoRecordExcludedServers")
            .defineList("autoRecordExcludedServers", List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue REPLAY_BUFFER = BUILDER
            .comment("Keep the last minutes of play in memory so they can be saved after the fact ('clip that').")
            .translation("kinora.config.replayBuffer")
            .define("replayBuffer", false);

    public static final ModConfigSpec.IntValue REPLAY_BUFFER_SECONDS = BUILDER
            .comment("How much the replay buffer keeps, in seconds.")
            .translation("kinora.config.replayBufferSeconds")
            .defineInRange("replayBufferSeconds", 120, 10, 1800);

    public static final ModConfigSpec.IntValue SNAPSHOT_INTERVAL_SECONDS = BUILDER
            .comment("Seconds between seek snapshots. Smaller seeks faster and costs a little disk.")
            .translation("kinora.config.snapshotIntervalSeconds")
            .defineInRange("snapshotIntervalSeconds", 30, 5, 600);

    public static final ModConfigSpec.BooleanValue RECORD_SERVER_ADDRESS = BUILDER
            .comment("Store the server address in recordings. Off masks it at the source.")
            .translation("kinora.config.recordServerAddress")
            .define("recordServerAddress", true);

    public static final ModConfigSpec.IntValue MIN_FREE_DISK_MB = BUILDER
            .comment("Stop recording when free disk space falls below this many MiB.")
            .translation("kinora.config.minFreeDiskMb")
            .defineInRange("minFreeDiskMb", 1024, 64, 1024 * 1024);

    public static final ModConfigSpec.IntValue STORAGE_LIMIT_GB = BUILDER
            .comment("When replays take more than this many GiB, the oldest non-favourite ones are moved to the recycle folder. 0 disables.")
            .translation("kinora.config.storageLimitGb")
            .defineInRange("storageLimitGb", 0, 0, 100_000);

    static {
        BUILDER.pop();
        BUILDER.comment("Playback").push("playback");
    }

    public static final ModConfigSpec.DoubleValue FREECAM_SPEED = BUILDER
            .comment("Free camera speed in blocks per second.")
            .translation("kinora.config.freecamSpeed")
            .defineInRange("freecamSpeed", 10.0, 0.1, 500.0);

    public static final ModConfigSpec.DoubleValue MOUSE_SMOOTHING = BUILDER
            .comment("Cinematic mouse smoothing for the free camera, 0 (off) to 1 (heavy).")
            .translation("kinora.config.mouseSmoothing")
            .defineInRange("mouseSmoothing", 0.35, 0.0, 0.95);

    public static final ModConfigSpec.BooleanValue REDUCE_MOTION = BUILDER
            .comment("Turn off UI animations.")
            .translation("kinora.config.reduceMotion")
            .define("reduceMotion", false);

    public static final ModConfigSpec.BooleanValue HIGH_CONTRAST = BUILDER
            .comment("High-contrast UI theme.")
            .translation("kinora.config.highContrast")
            .define("highContrast", false);

    static {
        BUILDER.pop();
        BUILDER.comment("Rendering").push("rendering");
    }

    public static final ModConfigSpec.ConfigValue<String> FFMPEG_PATH = BUILDER
            .comment("Path to the ffmpeg executable. Empty searches PATH and Kinora's tools folder.")
            .translation("kinora.config.ffmpegPath")
            .define("ffmpegPath", "");

    public static final ModConfigSpec.BooleanValue NOTIFY_WHEN_DONE = BUILDER
            .comment("Show a notification and play a sound when a render finishes.")
            .translation("kinora.config.notifyWhenDone")
            .define("notifyWhenDone", true);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private KinoraConfig() {}

    private static <T> T get(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    public static AutoRecord autoRecord() {
        return get(AUTO_RECORD);
    }

    public static List<? extends String> autoRecordExcludedServers() {
        return get(AUTO_RECORD_EXCLUDED_SERVERS);
    }

    public static boolean replayBuffer() {
        return get(REPLAY_BUFFER);
    }

    public static int replayBufferSeconds() {
        return get(REPLAY_BUFFER_SECONDS);
    }

    public static int snapshotIntervalSeconds() {
        return get(SNAPSHOT_INTERVAL_SECONDS);
    }

    public static boolean recordServerAddress() {
        return get(RECORD_SERVER_ADDRESS);
    }

    public static long minFreeDiskBytes() {
        return get(MIN_FREE_DISK_MB) * 1024L * 1024L;
    }

    public static int storageLimitGb() {
        return get(STORAGE_LIMIT_GB);
    }

    public static double freecamSpeed() {
        return get(FREECAM_SPEED);
    }

    public static double mouseSmoothing() {
        return get(MOUSE_SMOOTHING);
    }

    public static boolean reduceMotion() {
        return get(REDUCE_MOTION);
    }

    public static boolean highContrast() {
        return get(HIGH_CONTRAST);
    }

    public static String ffmpegPath() {
        return get(FFMPEG_PATH);
    }

    public static boolean notifyWhenDone() {
        return get(NOTIFY_WHEN_DONE);
    }
}
