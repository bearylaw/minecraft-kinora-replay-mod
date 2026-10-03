package dev.kinora.core.format;

/**
 * Record kinds of the replay stream. Values are stored in files: never renumber, only add.
 * Readers skip kinds they do not know.
 */
public final class RecordKind {
    /**
     * A clientbound network packet. Payload: {@code u8 phase} ({@link #PHASE_CONFIGURATION} or
     * {@link #PHASE_PLAY}), then the packet exactly as the protocol codec encodes it: VarInt id
     * followed by the body.
     */
    public static final int PACKET = 1;
    /** Client-side state the server never sends (the recording player's own movement, camera). kinora-mc defines the payload. */
    public static final int CLIENT_STATE = 2;
    /** A sound the client played, with its resolved file and parameters. kinora-mc defines the payload. */
    public static final int SOUND = 3;
    /** A named marker. Payload: see {@link Marker#encode}. */
    public static final int MARKER = 4;
    /** Data written by another mod through the API. Payload: VarInt track index (see MODT), u8 flags, then the mod's bytes. */
    public static final int MOD_DATA = 5;
    /** A connection-level event (disconnect, reconfiguration). Payload: u8 code, then code-specific bytes. */
    public static final int STREAM_EVENT = 6;

    public static final int PHASE_CONFIGURATION = 1;
    public static final int PHASE_PLAY = 2;

    /** MOD_DATA flag: the bytes are a state snapshot to restore, not an event to apply. */
    public static final int MOD_DATA_FLAG_STATE = 1;

    private RecordKind() {}

    public static String name(int kind) {
        return switch (kind) {
            case PACKET -> "PACKET";
            case CLIENT_STATE -> "CLIENT_STATE";
            case SOUND -> "SOUND";
            case MARKER -> "MARKER";
            case MOD_DATA -> "MOD_DATA";
            case STREAM_EVENT -> "STREAM_EVENT";
            default -> "KIND_" + kind;
        };
    }
}
