package dev.kinora.core.project;

import dev.kinora.core.format.KinoraFile;
import dev.kinora.core.format.KinoraFileWriter;
import dev.kinora.core.format.RecordBatch;
import dev.kinora.core.format.RecordKind;
import dev.kinora.core.format.ReplayMetadata;
import dev.kinora.core.format.StreamRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharePackageTest {
    @Test
    void packAndUnpack(@TempDir Path dir) throws IOException {
        Path replay = dir.resolve("mine.kinora");
        UUID id = UUID.randomUUID();
        try (KinoraFileWriter w = KinoraFileWriter.create(replay, id, 0)) {
            w.writeBatch(new RecordBatch(0, List.of(new StreamRecord(RecordKind.PACKET, 0, 0, new byte[] {1}))));
            w.finish(new ReplayMetadata(), List.of());
        }
        Project p = new Project();
        p.name = "Shared film";
        p.add(Shot.create("A", 0, 2));
        Path pack = dir.resolve("out").resolve("mine." + SharePackage.EXTENSION);
        SharePackage.export(replay, ProjectIO.toJson(p), Map.of("Before ../../evil", ProjectIO.toJson(p)), pack);

        Path friend = dir.resolve("friend");
        var imported = SharePackage.importPack(pack, friend.resolve("replays"), friend.resolve("projects"), friend.resolve("versions"));
        try (KinoraFile f = KinoraFile.open(imported.replay())) {
            assertEquals(id, f.header().fileId());
        }
        assertEquals(friend.resolve("projects").resolve(id + "." + ProjectIO.EXTENSION), imported.project());
        assertEquals("Shared film", ProjectIO.load(imported.project()).name);
        assertEquals(1, imported.versions());
        // Version names never escape their folder.
        assertTrue(Files.isDirectory(friend.resolve("versions").resolve(id.toString())));
        assertEquals(List.of("Before _evil"), SharePackage.versionNames(friend.resolve("versions"), id.toString()));
    }
}
