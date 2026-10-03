package dev.kinora.core.project;

import dev.kinora.core.format.KinoraFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A replay and its project in one file ({@code .kinorapack}, a zip), so someone else can open the
 * replay and keep editing the shots. No server or account involved: send the file any way you like.
 *
 * <p>Layout: {@code replay.kinora}, {@code project.kinoraproj}, and {@code versions/<name>.kinoraproj}.
 * Nothing is executed on import; names inside the zip are never used as paths.
 */
public final class SharePackage {
    public static final String EXTENSION = "kinorapack";

    private SharePackage() {}

    /** What an import produced. */
    public record Imported(Path replay, Path project, int versions) {}

    /**
     * Packs a replay with its project.
     *
     * @param versions project versions to include (file name stem to JSON); may be empty
     */
    public static void export(Path replay, String projectJson, java.util.Map<String, String> versions, Path out) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        Path temp = out.resolveSibling(out.getFileName() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
            // The replay is already compressed: store it as it is.
            ZipEntry replayEntry = new ZipEntry("replay.kinora");
            zip.putNextEntry(replayEntry);
            Files.copy(replay, zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("project.kinoraproj"));
            zip.write(projectJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (var v : versions.entrySet()) {
                zip.putNextEntry(new ZipEntry("versions/" + safe(v.getKey()) + ".kinoraproj"));
                zip.write(v.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Unpacks into the replays and projects folders. The replay keeps its own id, so its project file
     * is found again when the replay is opened; an existing replay with the same name is not overwritten.
     */
    public static Imported importPack(Path pack, Path replaysDir, Path projectsDir, Path versionsDir) throws IOException {
        Files.createDirectories(replaysDir);
        Files.createDirectories(projectsDir);
        String stem = pack.getFileName().toString().replaceFirst("\\." + EXTENSION + "$", "");
        Path replay = unique(replaysDir, safe(stem), ".kinora");
        String project = null;
        java.util.Map<String, String> versions = new java.util.LinkedHashMap<>();
        boolean hasReplay = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (name.equals("replay.kinora")) {
                    copyLimited(zip, replay);
                    hasReplay = true;
                } else if (name.equals("project.kinoraproj")) {
                    project = new String(readLimited(zip, 64 << 20), StandardCharsets.UTF_8);
                } else if (name.startsWith("versions/") && name.endsWith(".kinoraproj")) {
                    String v = safe(name.substring("versions/".length(), name.length() - ".kinoraproj".length()));
                    versions.put(v, new String(readLimited(zip, 64 << 20), StandardCharsets.UTF_8));
                }
            }
        }
        if (!hasReplay) {
            throw new IOException("this package has no replay in it");
        }
        String fileId;
        try (KinoraFile f = KinoraFile.open(replay)) {
            fileId = f.header().fileId().toString();
        }
        Path projectFile = projectsDir.resolve(fileId + "." + ProjectIO.EXTENSION);
        if (project != null) {
            // Validates the JSON before writing it where the editor will load it.
            ProjectIO.save(ProjectIO.fromJson(project), projectFile);
        }
        Path vdir = versionsDir.resolve(fileId);
        for (var v : versions.entrySet()) {
            ProjectIO.save(ProjectIO.fromJson(v.getValue()), vdir.resolve(v.getKey() + "." + ProjectIO.EXTENSION));
        }
        return new Imported(replay, projectFile, versions.size());
    }

    /** Only letters, digits, space, dash and underscore; never a path. */
    static String safe(String name) {
        String s = name.replaceAll("[^A-Za-z0-9 _-]+", "_").strip();
        return s.isEmpty() ? "replay" : s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static Path unique(Path dir, String stem, String ext) {
        Path p = dir.resolve(stem + ext);
        for (int i = 2; Files.exists(p); i++) {
            p = dir.resolve(stem + "_" + i + ext);
        }
        return p;
    }

    private static void copyLimited(InputStream in, Path out) throws IOException {
        try (OutputStream o = Files.newOutputStream(out)) {
            in.transferTo(o);
        }
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        byte[] data = in.readNBytes(max + 1);
        if (data.length > max) {
            throw new IOException("a project in the package is unreasonably large");
        }
        return data;
    }

    /** Version names of a project, newest first, from its versions folder. */
    public static List<String> versionNames(Path versionsDir, String fileId) {
        Path dir = versionsDir.resolve(fileId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith("." + ProjectIO.EXTENSION))
                    .sorted(java.util.Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed())
                    .map(p -> p.getFileName().toString().replaceFirst("\\." + ProjectIO.EXTENSION + "$", "")).toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
