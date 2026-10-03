package dev.kinora.core.project;

import dev.kinora.core.timeline.Easing;
import dev.kinora.core.timeline.Interpolation;
import dev.kinora.core.timeline.Keyframe;
import dev.kinora.core.timeline.Track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads and writes projects as JSON. Saving writes a temporary file and renames it over the old
 * one, so a crash mid-save never leaves a half-written project.
 */
public final class ProjectIO {
    public static final String EXTENSION = "kinoraproj";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
            .serializeSpecialFloatingPointValues().create();

    private ProjectIO() {}

    public static String toJson(Project project) {
        return GSON.toJson(project);
    }

    public static Project fromJson(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        int version = object.has("version") ? object.get("version").getAsInt() : 1;
        if (version > Project.FORMAT_VERSION) {
            throw new IllegalArgumentException("this project was saved by a newer Kinora (format " + version + ")");
        }
        fillInterpolationDefaults(object);
        Project project = GSON.fromJson(object, Project.class);
        for (Shot shot : project.shots) {
            // Hand-written projects may give only a track's keys: its kind and size follow from its id.
            shot.tracks.forEach((id, t) -> {
                Track template = Tracks.create(id);
                t.id = id;
                t.kind = template.kind;
                t.dimension = template.dimension;
                for (Keyframe k : t.keys) {
                    if (k.interpolation == null) {
                        k.interpolation = defaultInterpolation(id);
                    }
                    if (k.easing == null) {
                        k.easing = Easing.LINEAR;
                    }
                }
                t.keys.sort(java.util.Comparator.comparingDouble(k -> k.time));
            });
            shot.tracks.values().forEach(t -> t.changed());
            if (shot.id == null || shot.id.isBlank()) {
                shot.id = java.util.UUID.randomUUID().toString();
            }
            if (shot.overlays == null) {
                shot.overlays = new java.util.ArrayList<>();
            }
            if (shot.rig == null) {
                shot.rig = new Shot.Rig();
            }
            if (shot.lookAt == null) {
                shot.lookAt = new Shot.LookAt();
            }
            if (shot.shake == null) {
                shot.shake = new Shot.Shake();
            }
        }
        if (project.render == null) {
            project.render = dev.kinora.core.render.RenderPresets.defaultSettings();
        }
        return project;
    }

    /**
     * Interpolation for a key that does not say: smooth without overshoot for paths, rotation and
     * replay time (an overshooting time curve would briefly run the replay backwards), smooth for
     * other values.
     */
    static Interpolation defaultInterpolation(String trackId) {
        return trackId.equals(Tracks.POSITION) || trackId.equals(Tracks.ROTATION) || trackId.equals(Tracks.TIME)
                ? Interpolation.CENTRIPETAL : Interpolation.CATMULL_ROM;
    }

    /** Writes the default interpolation into keys of hand-written files that leave it out. */
    private static void fillInterpolationDefaults(JsonObject project) {
        if (!project.has("shots") || !project.get("shots").isJsonArray()) {
            return;
        }
        for (var shot : project.getAsJsonArray("shots")) {
            if (!shot.isJsonObject() || !shot.getAsJsonObject().has("tracks")) {
                continue;
            }
            for (var track : shot.getAsJsonObject().getAsJsonObject("tracks").entrySet()) {
                JsonObject t = track.getValue().getAsJsonObject();
                if (!t.has("keys")) {
                    continue;
                }
                for (var key : t.getAsJsonArray("keys")) {
                    JsonObject k = key.getAsJsonObject();
                    if (!k.has("interpolation")) {
                        k.addProperty("interpolation", defaultInterpolation(track.getKey()).name());
                    }
                }
            }
        }
    }

    public static Project load(Path path) throws IOException {
        return fromJson(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static void save(Project project, Path path) throws IOException {
        project.modifiedMillis = System.currentTimeMillis();
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, toJson(project), StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
