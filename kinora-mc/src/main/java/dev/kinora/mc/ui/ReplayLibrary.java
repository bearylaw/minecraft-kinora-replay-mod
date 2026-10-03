package dev.kinora.mc.ui;

import dev.kinora.core.format.KinoraFile;
import dev.kinora.core.format.KinoraFormat;
import dev.kinora.core.format.ReplayMetadata;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.util.KinoraPaths;

import net.minecraft.SharedConstants;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The replays on disk, with what the browser shows about each. Metadata is cached by path and
 * modification time, so reopening the browser does not re-read every file.
 */
public final class ReplayLibrary {
    /** Whether a replay can be played here, in plain words. */
    public enum Compatibility { OK, UNFINISHED, OTHER_VERSION, MISSING_MODS, UNREADABLE }

    public record Entry(Path path, String name, long size, FileTime modified, @Nullable ReplayMetadata metadata,
                        long durationTicks, Compatibility compatibility, String detail, byte @Nullable [] thumbnail) {}

    private record Cached(FileTime modified, long size, Entry entry) {}

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private ReplayLibrary() {}

    public static List<Entry> scan() {
        List<Entry> entries = new ArrayList<>();
        Path dir = KinoraPaths.replays();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*." + KinoraFormat.EXTENSION)) {
            for (Path path : files) {
                entries.add(load(path));
            }
        } catch (IOException e) {
            KinoraMod.LOG.warn("Could not list {}", dir, e);
        }
        entries.sort(Comparator.comparing(Entry::modified).reversed());
        return entries;
    }

    public static Entry load(Path path) {
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            long size = Files.size(path);
            Cached cached = CACHE.get(path);
            if (cached != null && cached.modified().equals(modified) && cached.size() == size) {
                return cached.entry();
            }
            Entry entry = read(path, modified, size);
            CACHE.put(path, new Cached(modified, size, entry));
            return entry;
        } catch (IOException e) {
            return new Entry(path, baseName(path), 0, FileTime.fromMillis(0), null, 0, Compatibility.UNREADABLE, String.valueOf(e.getMessage()), null);
        }
    }

    private static Entry read(Path path, FileTime modified, long size) {
        try (KinoraFile file = KinoraFile.open(path)) {
            ReplayMetadata metadata = file.metadata();
            long duration = Math.max(metadata.durationTicks(), file.lastTick() - metadata.startTick);
            KinoraFile.Thumbnail thumbnail = file.thumbnail();
            Compatibility compatibility;
            String detail;
            if (!file.finished()) {
                compatibility = Compatibility.UNFINISHED;
                detail = "interrupted recording; it will be recovered when opened";
            } else if (!SharedConstants.getCurrentVersion().name().equals(metadata.minecraftVersion)) {
                compatibility = Compatibility.OTHER_VERSION;
                detail = "recorded with Minecraft " + metadata.minecraftVersion;
            } else {
                List<String> missing = missingMods(metadata);
                compatibility = missing.isEmpty() ? Compatibility.OK : Compatibility.MISSING_MODS;
                detail = missing.isEmpty() ? "" : "needs " + String.join(", ", missing);
            }
            return new Entry(path, baseName(path), size, modified, metadata, duration, compatibility, detail,
                    thumbnail == null ? null : thumbnail.png());
        } catch (IOException | RuntimeException e) {
            return new Entry(path, baseName(path), size, modified, null, 0, Compatibility.UNREADABLE, String.valueOf(e.getMessage()), null);
        }
    }

    /** Mods present when recording that are not loaded now (ignoring Minecraft, NeoForge and Kinora). */
    public static List<String> missingMods(ReplayMetadata metadata) {
        List<String> missing = new ArrayList<>();
        for (ReplayMetadata.ModInfo mod : metadata.mods) {
            String id = mod.id();
            if (id.equals("minecraft") || id.equals("neoforge") || id.equals("kinora")) {
                continue;
            }
            if (!net.neoforged.fml.ModList.get().isLoaded(id)) {
                missing.add(id);
            }
        }
        return missing;
    }

    /** Moves a replay to the recycle folder (a delete that can be undone by moving it back). */
    public static void recycle(Path path) throws IOException {
        Path target = KinoraPaths.recycle().resolve(path.getFileName());
        for (int i = 2; Files.exists(target); i++) {
            target = KinoraPaths.recycle().resolve(baseName(path) + "_" + i + "." + KinoraFormat.EXTENSION);
        }
        Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
        CACHE.remove(path);
    }

    public static String baseName(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith("." + KinoraFormat.EXTENSION) ? name.substring(0, name.length() - KinoraFormat.EXTENSION.length() - 1) : name;
    }
}
