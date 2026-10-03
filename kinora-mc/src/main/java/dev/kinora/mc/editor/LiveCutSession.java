package dev.kinora.mc.editor;

import dev.kinora.core.camera.CameraState;
import dev.kinora.core.project.LiveCut;
import dev.kinora.core.project.Project;
import dev.kinora.core.project.Shot;
import dev.kinora.core.project.ShotEvaluator;
import dev.kinora.mc.camera.CameraDirector;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;
import dev.kinora.mc.util.Notify;

import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Live-cut directing: the replay plays and number keys 1-9 cut between the project's first nine
 * shots, used as cameras. Finishing turns the cuts into shots and makes them the edit (the previous
 * project is kept as a version, and the change can be undone).
 */
public final class LiveCutSession implements CameraDirector.PathSource {
    public static final int MAX_CAMERAS = 9;

    private static @Nullable LiveCutSession active;

    private final List<Shot> cameras;
    private final List<LiveCut.Cut> cuts = new ArrayList<>();
    private int camera = -1;
    /** The current camera, as the shot it becomes: its moves from the cut on, showing replay time as it plays. */
    private @Nullable Shot showing;
    private double from;

    private LiveCutSession(List<Shot> cameras) {
        this.cameras = cameras;
    }

    public static @Nullable LiveCutSession active() {
        return active;
    }

    /** Starts a live cut from the replay's current time, on the selected shot's camera (or the first). */
    public static void start() {
        ReplayManager manager = ReplayManager.INSTANCE;
        ReplaySession session = manager.session();
        EditorState editor = manager.editor();
        if (session == null || active != null) {
            return;
        }
        List<Shot> shots = editor.project().shots;
        if (shots.isEmpty()) {
            Notify.warn(Component.translatable("kinora.live.title"), Component.translatable("kinora.live.no_cameras"));
            return;
        }
        LiveCutSession s = new LiveCutSession(List.copyOf(shots.subList(0, Math.min(MAX_CAMERAS, shots.size()))));
        for (Shot c : s.cameras) {
            editor.scene().track(c.rig.targetEntity);
            editor.scene().track(c.lookAt.targetEntity);
        }
        int first = editor.shot() == null ? 0 : Math.max(0, s.cameras.indexOf(editor.shot()));
        active = s;
        s.cut(first);
        manager.camera().path(s);
        session.setPaused(false);
        Notify.info(Component.translatable("kinora.live.title"), Component.translatable("kinora.live.started", s.cameras.size()));
    }

    /** Cuts to camera {@code index} (0-based) now. */
    public void cut(int index) {
        ReplaySession session = ReplayManager.INSTANCE.session();
        if (session == null || index < 0 || index >= cameras.size() || index == camera) {
            return;
        }
        double now = session.clock().time();
        cuts.add(new LiveCut.Cut(now, index));
        camera = index;
        from = now;
        // An hour is plenty: the stretch ends at the next cut.
        showing = LiveCut.segment(cameras.get(index), now, now + 20 * 3600, cameras.get(index).name);
    }

    public int camera() {
        return camera;
    }

    public List<Shot> cameras() {
        return cameras;
    }

    public int cuts() {
        return cuts.size();
    }

    @Override
    public @Nullable CameraState camera(float partialTick) {
        ReplaySession session = ReplayManager.INSTANCE.session();
        Shot shot = showing;
        if (session == null || shot == null) {
            return null;
        }
        double t = Math.max(0, (session.clock().time() - from) / 20.0);
        return ShotEvaluator.evaluate(shot, t, ReplayManager.INSTANCE.editor().scene()).camera();
    }

    /** Ends the live cut and makes it the edit. */
    public static void finish() {
        LiveCutSession s = active;
        ReplayManager manager = ReplayManager.INSTANCE;
        ReplaySession session = manager.session();
        active = null;
        if (s == null || session == null) {
            return;
        }
        session.setPaused(true);
        manager.camera().free();
        List<Shot> shots = LiveCut.build(s.cameras, s.cuts, session.clock().time());
        if (shots.isEmpty()) {
            Notify.info(Component.translatable("kinora.live.title"), Component.translatable("kinora.live.empty"));
            return;
        }
        EditorState editor = manager.editor();
        try {
            editor.projects().saveVersion("Before live cut " + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")));
        } catch (IOException e) {
            // The change can still be undone.
        }
        editor.projects().edit(() -> {
            Project project = editor.project();
            project.sequence.clear();
            for (Shot shot : shots) {
                // Added at the end of the (now empty) edit.
                project.add(shot);
            }
        });
        editor.setView(EditorState.View.SEQUENCE);
        editor.setSequenceTime(0);
        Notify.info(Component.translatable("kinora.live.title"), Component.translatable("kinora.live.done", shots.size()));
    }

    /** Ends the live cut without changing anything. */
    public static void cancel() {
        if (active == null) {
            return;
        }
        active = null;
        ReplaySession session = ReplayManager.INSTANCE.session();
        if (session != null) {
            session.setPaused(true);
        }
        ReplayManager.INSTANCE.camera().free();
        Notify.info(Component.translatable("kinora.live.title"), Component.translatable("kinora.live.cancelled"));
    }
}
