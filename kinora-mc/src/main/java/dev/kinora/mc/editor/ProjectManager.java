package dev.kinora.mc.editor;

import dev.kinora.core.project.Project;
import dev.kinora.core.project.ProjectIO;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.util.KinoraPaths;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The project of the open replay: loading it, undo and redo, saving, and crash-safe autosave.
 *
 * <p>Undo works on whole-project snapshots (JSON). Projects are small, so this costs little and
 * makes every edit undoable without each one having to know how to reverse itself. Callers
 * {@link #begin} an edit (which remembers the state before it) and {@link #commit} it.
 */
public final class ProjectManager {
    private static final long AUTOSAVE_INTERVAL_MS = 30_000;
    private static final long UNDO_MEMORY_BUDGET = 256L * 1024 * 1024;

    private Project project = new Project();
    private @Nullable Path path;
    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private long undoBytes;
    private @Nullable String pending;
    private boolean dirty;
    private long lastAutosave = System.currentTimeMillis();
    private int revision;

    public Project project() {
        return project;
    }

    /** Increments on every change; views use it to know when to refresh. */
    public int revision() {
        return revision;
    }

    /** Opens (or creates) the project for a replay, recovering a newer autosave if one exists. */
    public void openFor(String replayFileId, String replayFileName) {
        flush();
        undo.clear();
        redo.clear();
        undoBytes = 0;
        Path file = KinoraPaths.projects().resolve(replayFileId + "." + ProjectIO.EXTENSION);
        Path autosave = autosavePath(file);
        Project loaded = null;
        try {
            Path source = Files.exists(autosave) && (!Files.exists(file)
                    || Files.getLastModifiedTime(autosave).compareTo(Files.getLastModifiedTime(file)) > 0) ? autosave : file;
            if (Files.exists(source)) {
                loaded = ProjectIO.load(source);
                if (source == autosave) {
                    KinoraMod.LOG.info("Recovered unsaved project changes from {}", autosave.getFileName());
                }
            }
        } catch (IOException | RuntimeException e) {
            KinoraMod.LOG.warn("Could not load project {}; starting a new one", file, e);
        }
        if (loaded == null) {
            loaded = new Project();
            loaded.name = replayFileName.replaceFirst("\\.kinora$", "");
        }
        loaded.replayFileId = replayFileId;
        loaded.replayFileName = replayFileName;
        project = loaded;
        path = file;
        dirty = false;
        revision++;
    }

    private static Path autosavePath(Path file) {
        return file.resolveSibling(file.getFileName() + ".autosave");
    }

    /** Remembers the current state before an edit. Nested calls keep the outermost state. */
    public void begin() {
        if (pending == null) {
            pending = ProjectIO.toJson(project);
        }
    }

    /** Finishes an edit started with {@link #begin}: it becomes one undo step. */
    public void commit() {
        if (pending == null) {
            return;
        }
        String now = ProjectIO.toJson(project);
        if (!now.equals(pending)) {
            push(undo, pending);
            redo.clear();
            dirty = true;
            revision++;
        }
        pending = null;
    }

    /** Begin and commit around a single change. */
    public void edit(Runnable change) {
        begin();
        change.run();
        commit();
    }

    /** Marks a live change (a drag in progress) so views refresh, without making an undo step. */
    public void touched() {
        revision++;
    }

    private void push(Deque<String> stack, String state) {
        stack.push(state);
        undoBytes += state.length() * 2L;
        while (undoBytes > UNDO_MEMORY_BUDGET && stack.size() > 1) {
            undoBytes -= stack.removeLast().length() * 2L;
        }
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(ProjectIO.toJson(project));
        restore(undo.pop());
    }

    public void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(ProjectIO.toJson(project));
        restore(redo.pop());
    }

    private void restore(String json) {
        Project restored = ProjectIO.fromJson(json);
        restored.replayFileId = project.replayFileId;
        restored.replayFileName = project.replayFileName;
        project = restored;
        dirty = true;
        revision++;
    }

    /** Called every tick: autosaves when there are unsaved changes and the interval has passed. */
    public void tick() {
        if (dirty && path != null && System.currentTimeMillis() - lastAutosave > AUTOSAVE_INTERVAL_MS) {
            lastAutosave = System.currentTimeMillis();
            try {
                ProjectIO.save(project, autosavePath(path));
            } catch (IOException e) {
                KinoraMod.LOG.warn("Autosave failed", e);
            }
        }
    }

    /**
     * Replaces the whole project (an imported or hand-written project file), as one undoable step.
     * The replay link stays that of the open replay.
     */
    public void replace(Project imported) {
        begin();
        imported.replayFileId = project.replayFileId;
        imported.replayFileName = project.replayFileName;
        project = imported;
        commit();
        revision++;
    }

    /** Where named versions of this project live. */
    public static Path versionsDir() {
        return KinoraPaths.projects().resolve("versions");
    }

    /** Saves a named copy of the project as it is now (A/B comparisons, safe experiments). */
    public void saveVersion(String name) throws IOException {
        String safe = name.replaceAll("[^A-Za-z0-9 _-]+", "_").strip();
        ProjectIO.save(project, versionsDir().resolve(project.replayFileId).resolve(safe + "." + ProjectIO.EXTENSION));
    }

    /** Names of saved versions, newest first. */
    public java.util.List<String> versions() {
        return dev.kinora.core.project.SharePackage.versionNames(versionsDir(), project.replayFileId);
    }

    /** Loads a saved version as the project (one undoable step). */
    public void restoreVersion(String name) throws IOException {
        replace(ProjectIO.load(versionsDir().resolve(project.replayFileId).resolve(name + "." + ProjectIO.EXTENSION)));
    }

    /** Saves the project and removes the autosave. */
    public void save() throws IOException {
        if (path == null) {
            return;
        }
        ProjectIO.save(project, path);
        Files.deleteIfExists(autosavePath(path));
        dirty = false;
    }

    /** Saves if there are changes; used when leaving a replay. */
    public void flush() {
        if (dirty && path != null) {
            try {
                save();
            } catch (IOException e) {
                KinoraMod.LOG.error("Could not save the project", e);
            }
        }
    }

    public boolean dirty() {
        return dirty;
    }

    public @Nullable Path path() {
        return path;
    }
}
