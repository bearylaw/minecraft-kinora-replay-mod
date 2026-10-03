package dev.kinora.core.format;

import dev.kinora.core.io.ByteSink;
import dev.kinora.core.io.ByteSource;

import java.util.ArrayList;
import java.util.List;

/**
 * A mod data track declared in a MODT chunk. MOD_DATA records carry the small {@link #index()}
 * instead of the id string.
 *
 * @param index   file-local number, assigned in order of registration
 * @param id      namespaced id chosen by the mod, e.g. {@code "examplemod:sparkles"}
 * @param version the mod's own schema version for the bytes it writes
 */
public record ModTrack(int index, String id, int version) {

    /** MODT payload: varint count, then per track varint index, string id, varint version. */
    public static byte[] encodeTable(List<ModTrack> tracks) {
        ByteSink out = new ByteSink();
        out.writeVarInt(tracks.size());
        for (ModTrack track : tracks) {
            out.writeVarInt(track.index);
            out.writeString(track.id);
            out.writeVarInt(track.version);
        }
        return out.toByteArray();
    }

    public static List<ModTrack> decodeTable(byte[] payload) {
        ByteSource in = new ByteSource(payload);
        int count = in.readVarIntBounded(in.remaining() / 3);
        List<ModTrack> tracks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tracks.add(new ModTrack(in.readVarInt(), in.readString(), in.readVarInt()));
        }
        return tracks;
    }
}
