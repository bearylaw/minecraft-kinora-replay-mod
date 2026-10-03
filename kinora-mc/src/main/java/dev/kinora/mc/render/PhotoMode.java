package dev.kinora.mc.render;

import dev.kinora.core.camera.CameraState;
import dev.kinora.core.project.Project;
import dev.kinora.core.project.Shot;
import dev.kinora.core.project.Tracks;
import dev.kinora.core.render.RenderJob;
import dev.kinora.core.render.RenderSettings;
import dev.kinora.core.timeline.Interpolation;
import dev.kinora.core.timeline.Keyframe;
import dev.kinora.mc.hooks.CameraHooks;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;
import dev.kinora.mc.util.Notify;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A still of the current view at a resolution far above the window's, through the same renderer as
 * videos (exact time, world built around the camera, grading if the selected shot has it). The
 * result is one PNG in {@code kinora/renders}.
 */
public final class PhotoMode {
    private PhotoMode() {}

    /** Takes a photo; {@code longSide} is the width for landscape (e.g. 3840 or 7680). */
    public static void take(int longSide) {
        ReplaySession session = ReplayManager.INSTANCE.session();
        CameraState c = CameraHooks.currentFrame();
        Minecraft mc = Minecraft.getInstance();
        if (session == null || c == null || RenderRunner.rendering()) {
            return;
        }
        // Beyond the GPU's limit the render is tiled (RenderRunner); the frame must fit in memory.
        double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
        int width = Math.min(longSide, 32768) & ~1;
        while ((long) width * Math.round(width / aspect) > 400_000_000L) {
            width -= 512;
        }
        int height = (int) Math.round(width / aspect) & ~1;

        Shot shot = Shot.create("Photo", session.clock().time(), 0.05);
        // Hold the moment: a still has no motion.
        shot.track(Tracks.TIME).keys.clear();
        shot.track(Tracks.TIME).put(new Keyframe(0, session.clock().time()).withInterpolation(Interpolation.HOLD));
        shot.track(Tracks.POSITION).put(new Keyframe(0, c.x(), c.y(), c.z()));
        shot.track(Tracks.ROTATION).put(new Keyframe(0, c.yaw(), c.pitch()));
        shot.track(Tracks.FOV).put(new Keyframe(0, c.fov()));
        if (c.roll() != 0) {
            shot.track(Tracks.ROLL).put(new Keyframe(0, c.roll()));
        }
        // The look of the selected shot, if it has one.
        Shot selected = ReplayManager.INSTANCE.editor().shot();
        if (selected != null) {
            for (String id : java.util.List.of(Tracks.EXPOSURE, Tracks.CONTRAST, Tracks.SATURATION, Tracks.TEMPERATURE, Tracks.TINT,
                    Tracks.VIGNETTE, Tracks.GRAIN, Tracks.CHROMATIC, Tracks.BLOOM, Tracks.LETTERBOX, Tracks.DOF_APERTURE, Tracks.DOF_FOCUS)) {
                if (selected.existing(id) != null) {
                    shot.track(id).put(new Keyframe(0, selected.value(id, ReplayManager.INSTANCE.editor().playhead())));
                }
            }
        }
        Project project = Project.single(shot);
        project.name = "Photo";
        RenderSettings s = new RenderSettings();
        s.width = width;
        s.height = height;
        s.fpsNumerator = 20;
        s.fpsDenominator = 1;
        s.output = RenderSettings.Output.PNG_SEQUENCE;
        s.audio = false;
        s.fileName = "photo_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        // Smoother edges; tiles keep each GPU render within its limit.
        s.supersampling = width <= 16384 ? 2 : 1;
        RenderJob job = RenderJobs.create(project, shot, s);
        Notify.info(Component.translatable("kinora.photo.taking"), Component.literal(width + " x " + height));
        RenderJobs.startNow(job);
    }
}
