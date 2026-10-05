package dev.kinora.mc.editor;

import dev.kinora.core.camera.CameraState;
import dev.kinora.core.project.Project;
import dev.kinora.core.project.SequenceTimeline;
import dev.kinora.core.project.Shot;
import dev.kinora.core.project.ShotEvaluator;
import dev.kinora.core.project.Tracks;
import dev.kinora.core.timeline.Interpolation;
import dev.kinora.core.timeline.Keyframe;
import dev.kinora.core.timeline.Track;
import dev.kinora.mc.camera.CameraDirector;
import dev.kinora.mc.camera.LiveScene;
import dev.kinora.mc.playback.ReplaySession;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the editor is working on: the selected shot, the playhead on its timeline, whether the
 * shot is previewing, and the keyframe selection. While a shot is selected, the replay follows
 * the shot's time remap, and the camera follows its path unless the user is flying to set up a
 * new keyframe.
 */
public final class EditorState implements CameraDirector.PathSource {
    /** What the editor's timeline shows. */
    public enum View { SHOT, SEQUENCE }

    private final ProjectManager projects = new ProjectManager();
    private final LiveScene scene = new LiveScene();
    private @Nullable String shotId;
    private double playhead;
    private boolean playing;
    private boolean loop = true;
    private boolean previewCamera = true;
    private View view = View.SHOT;
    private double sequenceTime;
    private long lastNanos = -1;
    private final Set<Keyframe> selection = new LinkedHashSet<>();
    private @Nullable String selectedTrack;
    private List<Keyframe> clipboard = List.of();
    private @Nullable String clipboardTrack;

    public ProjectManager projects() {
        return projects;
    }

    public Project project() {
        return projects.project();
    }

    public LiveScene scene() {
        return scene;
    }

    public @Nullable Shot shot() {
        return shotId == null ? null : project().shot(shotId);
    }

    public void select(@Nullable Shot shot) {
        shotId = shot == null ? null : shot.id;
        selection.clear();
        playhead = 0;
        playing = false;
        view = View.SHOT;
        if (shot != null) {
            scene.track(shot.rig.targetEntity);
            scene.track(shot.lookAt.targetEntity);
        }
    }

    public double playhead() {
        return playhead;
    }

    public void setPlayhead(double seconds) {
        Shot shot = shot();
        playhead = shot == null ? 0 : Math.max(0, Math.min(shot.duration, seconds));
    }

    public boolean playing() {
        return playing;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        lastNanos = -1;
        Shot shot = shot();
        if (playing && shot != null && playhead >= shot.duration - 1e-6) {
            playhead = 0;
        }
        if (playing) {
            previewCamera = true;
        }
    }

    public boolean loop() {
        return loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    public boolean previewCamera() {
        return previewCamera;
    }

    public void setPreviewCamera(boolean preview) {
        previewCamera = preview;
    }

    public View view() {
        return view;
    }

    public void setView(View view) {
        this.view = view;
        playing = false;
    }

    public double sequenceTime() {
        return sequenceTime;
    }

    public void setSequenceTime(double t) {
        sequenceTime = Math.max(0, t);
    }

    public Set<Keyframe> selection() {
        return selection;
    }

    public @Nullable String selectedTrack() {
        return selectedTrack;
    }

    public void selectTrack(@Nullable String trackId) {
        selectedTrack = trackId;
    }

    /**
     * Called at the start of every frame. Advances the playhead while playing and returns the
     * replay time the world must show, or NaN if the editor does not drive time.
     */
    public double frame() {
        long now = System.nanoTime();
        double dt = lastNanos < 0 ? 0 : Math.min(0.25, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (view == View.SEQUENCE) {
            SequenceTimeline timeline = new SequenceTimeline(project());
            if (playing) {
                sequenceTime += dt;
                if (sequenceTime >= timeline.duration()) {
                    sequenceTime = loop ? 0 : timeline.duration();
                    playing = loop;
                }
            }
            SequenceTimeline.Sample sample = timeline.sample(sequenceTime);
            return sample == null ? Double.NaN : sample.primary().replayTicks(sample.primaryTime());
        }
        Shot shot = shot();
        if (shot == null) {
            return Double.NaN;
        }
        if (playing) {
            playhead += dt;
            if (playhead >= shot.duration) {
                if (loop) {
                    playhead = 0;
                } else {
                    playhead = shot.duration;
                    playing = false;
                }
            }
        }
        return shot.replayTicks(playhead);
    }

    @Override
    public @Nullable CameraState camera(float partialTick) {
        if (!previewCamera) {
            return null;
        }
        if (view == View.SEQUENCE) {
            SequenceTimeline.Sample sample = new SequenceTimeline(project()).sample(sequenceTime);
            if (sample == null) {
                return null;
            }
            CameraState c = ShotEvaluator.evaluate(sample.primary(), sample.primaryTime(), scene).camera();
            return c == null ? null : c.withRotation(c.yaw() + sample.whipYaw(), c.pitch(), c.roll());
        }
        Shot shot = shot();
        return shot == null ? null : ShotEvaluator.evaluate(shot, playhead, scene).camera();
    }

    // ------------------------------------------------------------------ editing

    /** A new shot starting at the replay's current time, selected. */
    public Shot newShot(ReplaySession session, double seconds) {
        Shot shot = Shot.create("Shot " + (project().shots.size() + 1), session.clock().time(), seconds);
        projects.edit(() -> project().add(shot));
        select(shot);
        previewCamera = false;
        return shot;
    }

    /** Adds the shot as built elsewhere (templates). */
    public void addShot(Shot shot) {
        projects.edit(() -> project().add(shot));
        select(shot);
        previewCamera = true;
    }

    /** Keys the current camera into the shot at the playhead: position, direction, roll and FOV. */
    public void keyCamera(CameraState camera) {
        Shot shot = shot();
        if (shot == null || camera == null) {
            return;
        }
        projects.edit(() -> {
            if (shot.rig.mode == Shot.RigMode.PATH) {
                key(shot, Tracks.POSITION, camera.x(), camera.y(), camera.z());
                Track rotation = shot.track(Tracks.ROTATION);
                double yaw = camera.yaw();
                // Keep yaw continuous with the previous key so the shortest turn is kept in FREE mode too.
                Keyframe before = previousKey(rotation, playhead);
                if (before != null) {
                    yaw = before.value[0] + wrap(yaw - before.value[0]);
                }
                key(shot, Tracks.ROTATION, yaw, camera.pitch());
            }
            if (camera.roll() != 0 || shot.existing(Tracks.ROLL) != null) {
                key(shot, Tracks.ROLL, camera.roll());
            }
            if (Math.abs(camera.fov() - Tracks.defaultValue(Tracks.FOV)) > 1e-3 || shot.existing(Tracks.FOV) != null) {
                key(shot, Tracks.FOV, camera.fov());
            }
        });
        previewCamera = true;
    }

    private void key(Shot shot, String trackId, double... value) {
        Track track = shot.track(trackId);
        Keyframe existing = keyAt(track, playhead);
        Keyframe key = new Keyframe(playhead, value);
        if (existing != null) {
            key.interpolation = existing.interpolation;
            key.easing = existing.easing;
        } else {
            key.interpolation = trackId.equals(Tracks.POSITION) ? Interpolation.CENTRIPETAL : Interpolation.CATMULL_ROM;
        }
        track.put(key);
        selection.clear();
        selection.add(key);
    }

    private static @Nullable Keyframe keyAt(Track track, double time) {
        for (Keyframe k : track.keys) {
            if (Math.abs(k.time - time) < 1e-6) {
                return k;
            }
        }
        return null;
    }

    private static @Nullable Keyframe previousKey(Track track, double time) {
        Keyframe best = null;
        for (Keyframe k : track.keys) {
            if (k.time < time - 1e-6) {
                best = k;
            }
        }
        return best;
    }

    private static double wrap(double d) {
        double r = d % 360;
        if (r > 180) {
            r -= 360;
        }
        if (r < -180) {
            r += 360;
        }
        return r;
    }

    /** Deletes the selected keyframes. */
    public void deleteSelection() {
        Shot shot = shot();
        if (shot == null || selection.isEmpty()) {
            return;
        }
        projects.edit(() -> {
            for (Track track : shot.tracks.values()) {
                if (!track.locked) {
                    track.keys.removeIf(selection::contains);
                    track.changed();
                }
            }
        });
        selection.clear();
    }

    public void copySelection() {
        Shot shot = shot();
        if (shot == null || selection.isEmpty()) {
            return;
        }
        List<Keyframe> copied = new ArrayList<>();
        String track = null;
        for (Track t : shot.tracks.values()) {
            for (Keyframe k : t.keys) {
                if (selection.contains(k)) {
                    copied.add(k.copy());
                    track = t.id;
                }
            }
        }
        double first = copied.stream().mapToDouble(k -> k.time).min().orElse(0);
        copied.forEach(k -> k.time -= first);
        clipboard = copied;
        clipboardTrack = track;
    }

    /** Pastes copied keyframes at the playhead, into the track they came from. */
    public void paste() {
        Shot shot = shot();
        if (shot == null || clipboard.isEmpty() || clipboardTrack == null) {
            return;
        }
        Track track = shot.track(clipboardTrack);
        projects.edit(() -> {
            selection.clear();
            for (Keyframe k : clipboard) {
                if (k.value.length != track.dimension) {
                    continue;
                }
                Keyframe copy = k.copy();
                copy.time = Math.min(shot.duration, playhead + k.time);
                track.put(copy);
                selection.add(copy);
            }
        });
    }

    /** Sets the shot's playback speed: rewrites the time track as a straight line. */
    public void setSpeed(Shot shot, double speed) {
        projects.edit(() -> {
            double start = shot.replayTicks(0);
            Track time = shot.track(Tracks.TIME);
            time.keys.clear();
            time.put(new Keyframe(0, start).withInterpolation(Interpolation.LINEAR));
            time.put(new Keyframe(shot.duration, start + shot.duration * 20 * speed));
        });
    }

    /** Average speed of a shot (replay ticks per output second / 20). */
    public static double speedOf(Shot shot) {
        return shot.duration <= 0 ? 1 : (shot.replayTicks(shot.duration) - shot.replayTicks(0)) / (shot.duration * 20);
    }

    /** Changes a shot's length, keeping its speed. */
    public void setDuration(Shot shot, double seconds) {
        double speed = speedOf(shot);
        projects.edit(() -> {
            double old = shot.duration;
            shot.duration = Math.max(0.05, seconds);
            Track time = shot.existing(Tracks.TIME);
            if (time != null && time.keys.size() == 2 && time.keys.get(1).time == old) {
                time.keys.get(1).time = shot.duration;
                time.keys.get(1).value[0] = time.keys.get(0).value[0] + shot.duration * 20 * speed;
                time.changed();
            }
        });
        setPlayhead(playhead);
    }

    /**
     * Moves a shot to start at another moment of the replay (in replay ticks), keeping its length, its
     * speed and any speed changes keyed in it.
     */
    public void setReplayStart(Shot shot, double ticks) {
        projects.edit(() -> {
            Track time = shot.track(Tracks.TIME);
            if (time.keys.isEmpty()) {
                time.put(new Keyframe(0, 0).withInterpolation(Interpolation.LINEAR));
                time.put(new Keyframe(shot.duration, shot.duration * 20));
            }
            double delta = Math.max(0, ticks) - time.keys.getFirst().value[0];
            for (Keyframe k : time.keys) {
                k.value[0] += delta;
            }
            time.changed();
        });
        setPlayhead(playhead);
    }

    /**
     * Makes a shot end at another moment of the replay (in replay ticks). A shot at an even speed keeps
     * its speed and gets longer or shorter; a shot with keyed speed changes keeps its length and its
     * changes are stretched to the new end. An end at or before the start is ignored.
     */
    public void setReplayEnd(Shot shot, double ticks) {
        double start = shot.replayTicks(0);
        double end = shot.replayTicks(shot.duration);
        if (ticks <= start + 0.5 || end <= start) {
            return;
        }
        Track time = shot.existing(Tracks.TIME);
        if (time == null || time.keys.size() <= 2) {
            setDuration(shot, (ticks - start) / (20 * speedOf(shot)));
            return;
        }
        double factor = (ticks - start) / (end - start);
        projects.edit(() -> {
            for (Keyframe k : time.keys) {
                k.value[0] = start + (k.value[0] - start) * factor;
            }
            time.changed();
        });
        setPlayhead(playhead);
    }

    public void reset() {
        shotId = null;
        selection.clear();
        playhead = 0;
        playing = false;
        previewCamera = true;
        view = View.SHOT;
        scene.clear();
    }
}
