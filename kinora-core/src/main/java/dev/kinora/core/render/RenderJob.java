package dev.kinora.core.render;

import dev.kinora.core.project.Project;
import dev.kinora.core.project.ProjectIO;
import dev.kinora.core.project.Shot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One render: what to render (a frozen copy of the project, so later edits do not change a queued
 * job), with which settings, where to, and how far it got. Saved as JSON in Kinora's queue folder
 * and updated while rendering, so an interrupted render resumes where it stopped.
 */
public final class RenderJob {
    public static final String EXTENSION = "kinorajob";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public enum State { QUEUED, RENDERING, PAUSED, DONE, FAILED }

    /** A finished part of a video, for resuming. */
    public static final class Part {
        public String file;
        public int frames;

        public Part() {}

        public Part(String file, int frames) {
            this.file = file;
            this.frames = frames;
        }
    }

    public String id = UUID.randomUUID().toString();
    public long created = System.currentTimeMillis();
    public String title = "";
    /** The replay file, absolute. */
    public String replayFile = "";
    /** The project as JSON when the job was made. */
    public String projectJson = "";
    /** The shot to render, or empty for the whole sequence. */
    public String shotId = "";
    public RenderSettings settings = RenderPresets.defaultSettings();
    /** The video file, or the folder of an image sequence. */
    public String output = "";
    public State state = State.QUEUED;
    public int frameCount;
    /** Frames safely written, counted from frame 0. */
    public int framesDone;
    public List<Part> parts = new ArrayList<>();
    public String error = "";
    /** After a render: where the time went, and a hint for going faster. */
    public String report = "";
    public long finished;

    public static RenderJob create(Project project, Shot shotOrNull, RenderSettings settings, Path replayFile, Path output) {
        RenderJob job = new RenderJob();
        job.projectJson = ProjectIO.toJson(project);
        job.shotId = shotOrNull == null ? "" : shotOrNull.id;
        job.title = project.name + (shotOrNull == null ? "" : " - " + shotOrNull.name);
        job.settings = settings.copy();
        job.replayFile = replayFile.toAbsolutePath().normalize().toString();
        job.output = output.toAbsolutePath().normalize().toString();
        job.frameCount = job.plan().frameCount();
        return job;
    }

    public Project project() {
        return ProjectIO.fromJson(projectJson);
    }

    /** The plan for this job; the shot or sequence comes from the frozen project. */
    public RenderPlan plan() {
        Project project = project();
        if (!shotId.isEmpty()) {
            Shot shot = project.shot(shotId);
            if (shot == null) {
                throw new IllegalStateException("the shot of this render is not in its project");
            }
            return RenderPlan.shot(shot, settings);
        }
        return RenderPlan.sequence(project, settings);
    }

    public boolean video() {
        return settings.output == RenderSettings.Output.VIDEO;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static RenderJob fromJson(String json) {
        RenderJob job = GSON.fromJson(json, RenderJob.class);
        if (job.parts == null) {
            job.parts = new ArrayList<>();
        }
        if (job.settings == null) {
            job.settings = RenderPresets.defaultSettings();
        }
        return job;
    }

    public static RenderJob load(Path file) throws IOException {
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Writes atomically, so a crash never leaves a half-written job. */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, toJson(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
