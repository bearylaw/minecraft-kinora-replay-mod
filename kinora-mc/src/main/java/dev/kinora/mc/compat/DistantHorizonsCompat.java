package dev.kinora.mc.compat;

import dev.kinora.core.format.ReplayMetadata;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.util.KinoraPaths;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;

import net.minecraft.client.Minecraft;

import net.neoforged.fml.ModList;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Distant Horizons' far terrain in replays recorded in singleplayer. A server replay finds the data
 * Distant Horizons saved for that server by going by the server's name; a singleplayer world keeps
 * its data inside the save ({@code saves/<world>/dimensions/<namespace>/<path>/data}), which a
 * replay, being a connection to a server, never looks at. Kinora tells Distant Horizons where it is
 * through Distant Horizons' own save-folder override, and hands it a copy (in
 * {@code kinora/cache/distanthorizons}), so nothing a replay loads is written back into the world.
 *
 * <p>Distant Horizons is optional and not a compile dependency: the override is a proxy of its API
 * interface, bound reflectively. Without Distant Horizons this does nothing.
 */
public final class DistantHorizonsCompat {
    private static final String API = "com.seibel.distanthorizons.api.";

    private DistantHorizonsCompat() {}

    public static void init() {
        if (!ModList.get().isLoaded("distanthorizons")) {
            return;
        }
        try {
            Class<?> saveStructure = Class.forName(API + "interfaces.override.levelHandling.IDhApiSaveStructure");
            Class<?> overrideable = Class.forName(API + "interfaces.override.IDhApiOverrideable");
            Object override = Proxy.newProxyInstance(DistantHorizonsCompat.class.getClassLoader(), new Class<?>[] {saveStructure},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "overrideFilePath" -> saveFolder(args[1]);
                        case "getPriority" -> 10;
                        case "getDelayedSetupComplete" -> true;
                        case "finishDelayedSetup" -> null;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "Kinora singleplayer replay save folder";
                        default -> null;
                    });
            Object overrides = Class.forName(API + "DhApi").getField("overrides").get(null);
            Method bind = overrides.getClass().getMethod("bind", Class.class, overrideable);
            bind.invoke(overrides, saveStructure, override);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            KinoraMod.LOG.warn("Kinora could not hook Distant Horizons; singleplayer replays will lack far terrain", e);
        }
    }

    /** The folder for a level of an open singleplayer replay; null leaves Distant Horizons' choice. */
    private static @Nullable File saveFolder(Object level) {
        ReplaySession session = ReplayManager.INSTANCE.session();
        if (session == null || !session.metadata().singleplayer) {
            return null;
        }
        try {
            String dimension = (String) level.getClass().getMethod("getDimensionName").invoke(level);
            Path source = worldFolder(session.metadata());
            if (source == null || dimension == null) {
                return null;
            }
            Path data = source.resolve("dimensions").resolve(dimension.replace(':', File.separatorChar)).resolve("data");
            if (!Files.isRegularFile(data.resolve("DistantHorizons.sqlite"))) {
                return null;
            }
            // One copy per world, refreshed when the world's own data is newer.
            Path copy = KinoraPaths.root().resolve("cache").resolve("distanthorizons").resolve(source.getFileName().toString())
                    .resolve(dimension.replace(':', '_'));
            copyIfNewer(data, copy);
            return copy.toFile();
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            KinoraMod.LOG.warn("Kinora could not give Distant Horizons the world's far terrain", e);
            return null;
        }
    }

    /** The recorded world's save folder in this game folder, by its folder name or else its name. */
    static @Nullable Path worldFolder(ReplayMetadata metadata) {
        Path saves = Minecraft.getInstance().gameDirectory.toPath().resolve("saves");
        for (String name : new String[] {metadata.worldFolder, metadata.worldName}) {
            if (name != null && !name.isBlank() && Files.isDirectory(saves.resolve(name))) {
                return saves.resolve(name);
            }
        }
        return null;
    }

    private static void copyIfNewer(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        Path db = from.resolve("DistantHorizons.sqlite");
        Path target = to.resolve("DistantHorizons.sqlite");
        if (Files.isRegularFile(target) && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(db)) >= 0) {
            return;
        }
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            Path f = from.resolve("DistantHorizons.sqlite" + suffix);
            Path t = to.resolve("DistantHorizons.sqlite" + suffix);
            if (Files.isRegularFile(f)) {
                Files.copy(f, t, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } else {
                Files.deleteIfExists(t);
            }
        }
    }
}
