package dev.kinora.mc.util;

import dev.kinora.core.format.KinoraFormat;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Where Kinora keeps its files, all under {@code <game dir>/kinora/}. */
public final class KinoraPaths {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private KinoraPaths() {}

    public static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("kinora");
    }

    public static Path replays() {
        return ensure(root().resolve("replays"));
    }

    /** Deleted replays go here first, so a delete can be undone. */
    public static Path recycle() {
        return ensure(root().resolve("recycle"));
    }

    public static Path projects() {
        return ensure(root().resolve("projects"));
    }

    public static Path renders() {
        return ensure(root().resolve("renders"));
    }

    public static Path tools() {
        return ensure(root().resolve("tools"));
    }

    public static Path renderQueue() {
        return ensure(root().resolve("queue"));
    }

    private static Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot create " + dir, e);
        }
        return dir;
    }

    /** A new, unused replay file name: date, time and a sanitised label. */
    public static Path newReplayFile(String label) {
        String base = LocalDateTime.now().format(STAMP) + "_" + sanitize(label);
        Path dir = replays();
        Path candidate = dir.resolve(base + "." + KinoraFormat.EXTENSION);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = dir.resolve(base + "_" + i + "." + KinoraFormat.EXTENSION);
        }
        return candidate;
    }

    /** File-name-safe on every platform: letters, digits, dash, underscore, dot; at most 48 characters. */
    public static String sanitize(String label) {
        return sanitize(label, 48);
    }

    /** As {@link #sanitize(String)}, with a different length limit. */
    public static String sanitize(String label, int maxLength) {
        String cleaned = label.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_").replaceAll("^[._]+|[._]+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "replay";
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
