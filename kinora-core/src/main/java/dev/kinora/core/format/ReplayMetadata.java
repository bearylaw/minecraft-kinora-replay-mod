package dev.kinora.core.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Descriptive data about a replay, stored as UTF-8 JSON in META chunks. A file may hold several
 * META chunks (one at the start, one when it is finished); the last one wins.
 *
 * <p>Keys this version does not know are kept and written back unchanged, so a newer Kinora's
 * metadata survives being recovered or edited by an older one.
 */
public final class ReplayMetadata {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** What produced the file. */
    public enum Kind { RECORDING, BUFFER, CLIP }

    public record ModInfo(String id, String version) {}

    public record PlayerInfo(String name, String uuid) {}

    public Kind kind = Kind.RECORDING;
    public String kinoraVersion = "";
    public String minecraftVersion = "";
    public int protocolVersion;
    public String loader = "";
    public String loaderVersion = "";
    public List<ModInfo> mods = new ArrayList<>();
    public boolean singleplayer;
    /** Server list name, or null. Cleared when the user asks for addresses to be masked. */
    public String serverName;
    public String serverAddress;
    public String worldName;
    /** Singleplayer: the save's folder name under {@code saves/} (can differ from its name). */
    public String worldFolder;
    public String startDimension;
    public PlayerInfo player;
    public List<String> resourcePacks = new ArrayList<>();
    /** Wall-clock start, milliseconds since the epoch. */
    public long startedAtMillis;
    /** First tick that is part of the viewable timeline; earlier records are pre-roll. */
    public long startTick;
    /** Tick of the last record, once known. */
    public long endTick;
    public long durationNanos;
    /** True once the recorder wrote the closing index; false while recording or after a crash. */
    public boolean complete;
    /** True if the file was rebuilt by crash recovery. */
    public boolean recovered;
    /** True if server address and player names were masked for sharing. */
    public boolean masked;
    /** The replay this one was cut or buffered from, as its file id; null for originals. */
    public String sourceFileId;
    /** Ticks per second the replay was recorded at (the client's nominal rate). */
    public double ticksPerSecond = 20.0;

    private transient JsonObject unknown = new JsonObject();

    public long durationTicks() {
        return Math.max(0, endTick - startTick);
    }

    public ReplayMetadata copy() {
        return fromJson(toJson());
    }

    public String toJson() {
        JsonObject object = GSON.toJsonTree(this).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : unknown.entrySet()) {
            if (!object.has(entry.getKey())) {
                object.add(entry.getKey(), entry.getValue());
            }
        }
        return GSON.toJson(object);
    }

    public byte[] toBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    public static ReplayMetadata fromJson(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        ReplayMetadata metadata = GSON.fromJson(object, ReplayMetadata.class);
        if (metadata.mods == null) {
            metadata.mods = new ArrayList<>();
        }
        if (metadata.resourcePacks == null) {
            metadata.resourcePacks = new ArrayList<>();
        }
        if (metadata.kind == null) {
            metadata.kind = Kind.RECORDING;
        }
        JsonObject known = GSON.toJsonTree(new ReplayMetadata()).getAsJsonObject();
        metadata.unknown = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!known.has(entry.getKey()) && !isKnownNullable(entry.getKey())) {
                metadata.unknown.add(entry.getKey(), entry.getValue());
            }
        }
        return metadata;
    }

    private static boolean isKnownNullable(String key) {
        return switch (key) {
            case "serverName", "serverAddress", "worldName", "startDimension", "player", "sourceFileId" -> true;
            default -> false;
        };
    }

    public static ReplayMetadata fromBytes(byte[] bytes) {
        return fromJson(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Removes what identifies a server or player, for sharing. The recording player's name is
     * replaced, not removed, so the replay still reads naturally.
     */
    public void mask() {
        serverName = null;
        serverAddress = null;
        if (player != null) {
            player = new PlayerInfo("Player", "00000000-0000-0000-0000-000000000000");
        }
        masked = true;
    }
}
