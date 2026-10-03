package dev.kinora.mc.render;

import dev.kinora.core.project.Project;
import dev.kinora.core.project.Shot;
import dev.kinora.core.render.RenderJob;
import dev.kinora.core.render.RenderSettings;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;
import dev.kinora.mc.util.KinoraPaths;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Making render jobs, and the queue of them in {@code kinora/queue}. */
public final class RenderJobs {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private RenderJobs() {}

    /** A job for the open replay's project: one shot, or the whole sequence when {@code shot} is null. */
    public static RenderJob create(Project project, @Nullable Shot shot, RenderSettings settings) {
        ReplaySession session = ReplayManager.INSTANCE.session();
        if (session == null) {
            throw new IllegalStateException("no replay is open");
        }
        Path replay = session.file().path();
        return RenderJob.create(project, shot, settings, replay, outputPath(project, shot, settings));
    }

    /** Where the output goes: the settings' folder (or Kinora's renders folder) and file name pattern. */
    public static Path outputPath(Project project, @Nullable Shot shot, RenderSettings s) {
        Path folder = s.outputFolder.isBlank() ? KinoraPaths.renders() : Path.of(s.outputFolder);
        String name = s.fileName.replace("{project}", project.name).replace("{shot}", shot == null ? "sequence" : shot.name)
                .replace("{date}", LocalDateTime.now().format(STAMP));
        name = KinoraPaths.sanitize(name, 120);
        if (s.output == RenderSettings.Output.VIDEO) {
            return unique(folder, name, "." + s.container);
        }
        return unique(folder, name, "");
    }

    private static Path unique(Path folder, String name, String extension) {
        Path p = folder.resolve(name + extension);
        for (int i = 2; Files.exists(p); i++) {
            p = folder.resolve(name + "_" + i + extension);
        }
        return p;
    }

    public static Path fileFor(RenderJob job) {
        return KinoraPaths.renderQueue().resolve(job.id + "." + RenderJob.EXTENSION);
    }

    /** Saves the job to the queue and starts it in the open replay. */
    public static boolean startNow(RenderJob job) {
        Path file = fileFor(job);
        try {
            job.save(file);
        } catch (IOException e) {
            KinoraMod.LOG.warn("Kinora could not save render job {}", file, e);
        }
        return RenderRunner.start(job, file);
    }

    /** All queued, paused, failed and finished jobs, newest first. */
    public static List<RenderJob> list() {
        List<RenderJob> jobs = new ArrayList<>();
        try (Stream<Path> files = Files.list(KinoraPaths.renderQueue())) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith("." + RenderJob.EXTENSION)).toList()) {
                try {
                    jobs.add(RenderJob.load(f));
                } catch (IOException | RuntimeException e) {
                    KinoraMod.LOG.warn("Kinora skipped unreadable render job {}", f, e);
                }
            }
        } catch (IOException e) {
            KinoraMod.LOG.warn("Kinora could not list the render queue", e);
        }
        jobs.sort(Comparator.comparingLong((RenderJob j) -> j.created).reversed());
        return jobs;
    }

    public static void delete(RenderJob job) {
        try {
            Files.deleteIfExists(fileFor(job));
        } catch (IOException e) {
            KinoraMod.LOG.warn("Kinora could not delete render job {}", job.id, e);
        }
    }
}
