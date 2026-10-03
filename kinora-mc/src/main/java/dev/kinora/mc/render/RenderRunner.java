package dev.kinora.mc.render;

import dev.kinora.core.camera.CameraState;
import dev.kinora.core.project.ShotEvaluator;
import dev.kinora.core.render.Downscale;
import dev.kinora.core.render.FrameSink;
import dev.kinora.core.render.FrameStore;
import dev.kinora.core.render.Ffmpeg;
import dev.kinora.core.render.ImageSequenceSink;
import dev.kinora.core.render.RenderEncoder;
import dev.kinora.core.render.RenderJob;
import dev.kinora.core.render.RenderPlan;
import dev.kinora.core.render.RenderSettings;
import dev.kinora.core.render.VideoSink;
import dev.kinora.mc.KinoraConfig;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.camera.CameraDirector;
import dev.kinora.mc.camera.LiveScene;
import dev.kinora.mc.hooks.RenderHooks;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;
import dev.kinora.mc.util.KinoraPaths;
import dev.kinora.mc.util.Notify;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs one offline render inside the open replay. Each game frame it sets replay time to the next
 * unit of the {@link RenderPlan}, points the camera at the shot's camera for that moment, lets the
 * frame render at the output size, and - once the world has finished building around the camera -
 * reads the image back and hands it to the encoder thread.
 *
 * <p>Wall-clock time plays no part: a frame is captured only when the replay is exactly at the
 * unit's tick and partial tick and the world has settled, however long that takes.
 */
public final class RenderRunner implements ReplaySession.TimeDriver, RenderHooks.Capture, CameraDirector.PathSource {
    /** Frames that may be read back but not yet handed to the encoder. */
    private static final int READBACK_BUFFERS = 3;
    /** Give up waiting for chunks after this long on one frame, and render what is there. */
    private static final long SETTLE_TIMEOUT_NANOS = 3_000_000_000L;
    /** Replay run before the first frame (3 s) so smoothed cameras start with a full history. */
    private static final int PREROLL_TICKS = 60;
    /** Frames to let the world build after the render starts or the replay rebuilt itself. */
    private static final int WARM_UP_FRAMES = 20;
    /** Frames the drawn section count must hold still before the first frame after a (re)load. */
    private static final int FRESH_STABLE_FRAMES = 10;

    private static @Nullable RenderRunner active;

    private final RenderJob job;
    private final Path jobFile;
    private final RenderPlan plan;
    private final List<RenderPlan.Unit> units;
    private final int width;
    private final int height;
    private final FrameCapture capture;
    private final RenderEncoder encoder;
    private final @Nullable VideoSink videoSink;
    private final @Nullable ImageSequenceSink imageSink;
    private final long startNanos = System.nanoTime();
    private final double savedFadeIn;
    private final boolean savedVsync;
    private final int firstFrame;

    private int next;
    private int warmUp = WARM_UP_FRAMES;
    private final TerrainReadiness terrain = new TerrainReadiness();
    /** Game frames by what they were waiting for, for the performance report. */
    private long framesLoading;
    private long framesSettling;
    private long framesEncoder;
    private long framesReadback;
    private long framesCaptured;
    private long framesOther;
    private boolean fresh = true;
    private boolean wasLoading;
    private int stableFrames;
    private int lastSections = -1;
    private boolean waited;
    private int idleFrames;
    private long unitSince = System.nanoTime();
    private int unsettledFrames;
    private boolean inputEnded;
    private boolean stopping;
    private long lastSave;
    private @Nullable CameraState camera;
    private dev.kinora.core.render.Grade grade = dev.kinora.core.render.Grade.NONE;
    /** Depth of field for the current unit (focus may be NaN: focus on the centre), or null for none. */
    private dev.kinora.core.render.@Nullable DepthOfField lens;
    private final DepthCopy depthCopy;
    /** Depth is read back with every image: for a transparent sky or a depth pass (depth of field reads it per shot). */
    private final boolean needDepth;
    private final dev.kinora.core.render.@Nullable DepthPassWriter depthPass;
    private com.mojang.blaze3d.textures.@Nullable GpuTexture preview;
    private final @Nullable RenderAudio audio;
    private @Nullable Thread audioThread;
    private volatile @Nullable Throwable audioError;
    private volatile boolean audioDone;

    private RenderRunner(RenderJob job, Path jobFile, int firstFrame, FrameSink sink, @Nullable VideoSink videoSink,
                         @Nullable ImageSequenceSink imageSink) {
        this.job = job;
        this.jobFile = jobFile;
        this.plan = job.plan();
        this.firstFrame = firstFrame;
        this.units = plan.renderOrderFrom(firstFrame);
        RenderSettings s = job.settings;
        int[] view = dev.kinora.core.render.Views.renderSize(s);
        this.width = view[0] * s.supersampling;
        this.height = view[1] * s.supersampling;
        this.capture = new FrameCapture(width, height, READBACK_BUFFERS);
        this.depthCopy = new DepthCopy(width, height);
        this.needDepth = s.alpha() || s.depthPass();
        this.depthPass = s.depthPass()
                ? new dev.kinora.core.render.DepthPassWriter(dev.kinora.core.render.DepthPassWriter.folderFor(Path.of(job.output)), baseName(job))
                : null;
        FrameStore store = new FrameStore(KinoraPaths.renderQueue().resolve(job.id + ".frames"), 1L << 30);
        this.encoder = new RenderEncoder(plan, s.width, s.height, firstFrame, sink, store, 8);
        this.videoSink = videoSink;
        this.imageSink = imageSink;
        RenderAudio a = null;
        if (job.settings.audio) {
            try {
                a = new RenderAudio(job);
            } catch (IOException e) {
                KinoraMod.LOG.warn("Kinora cannot record sound for this render; it will be silent", e);
            }
        }
        this.audio = a;
        this.savedFadeIn = Minecraft.getInstance().options.chunkSectionFadeInTime().get();
        this.savedVsync = Minecraft.getInstance().options.enableVsync().get();
    }

    public static @Nullable RenderRunner active() {
        return active;
    }

    public static boolean rendering() {
        return active != null;
    }

    private static RenderJob.@Nullable State lastResult;

    /** How the last render ended (DONE, PAUSED or FAILED), or null if none has run. */
    public static RenderJob.@Nullable State lastResult() {
        return lastResult;
    }

    /**
     * Starts rendering {@code job} in the open replay. Returns false (after telling the user why) if
     * it cannot start.
     */
    public static boolean start(RenderJob job, Path jobFile) {
        ReplayManager manager = ReplayManager.INSTANCE;
        ReplaySession session = manager.session();
        Minecraft mc = Minecraft.getInstance();
        if (active != null || session == null || mc.level == null) {
            return false;
        }
        RenderSettings s = job.settings;
        List<String> problems = s.validate();
        if (!problems.isEmpty()) {
            Notify.warn(Component.translatable("kinora.render.cannot_start"), Component.literal(problems.getFirst()));
            return false;
        }
        int maxSize = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
        if (s.tiles == 0 && s.projection == RenderSettings.Projection.NORMAL) {
            // Tiles when the frame is larger than the GPU allows, or than 16384 (graphics memory).
            int limit = Math.min(maxSize, 16384);
            int need = (int) Math.ceil(Math.max(s.width, s.height) * (double) s.supersampling / limit);
            if (need > 1) {
                s.tiles = need;
                KinoraMod.LOG.info("Kinora renders {} x {} in {} x {} tiles", s.width, s.height, need, need);
            }
        }
        int[] viewSize = dev.kinora.core.render.Views.renderSize(s);
        if (viewSize[0] * s.supersampling > maxSize || viewSize[1] * s.supersampling > maxSize) {
            Notify.warn(Component.translatable("kinora.render.cannot_start"), Component.translatable("kinora.render.too_large", maxSize));
            return false;
        }
        try {
            FrameSink sink;
            VideoSink video = null;
            ImageSequenceSink images = null;
            int firstFrame;
            Path output = Path.of(job.output);
            if (job.video()) {
                Path ffmpeg = Ffmpeg.locate(KinoraConfig.ffmpegPath(), KinoraPaths.tools());
                if (ffmpeg == null) {
                    Notify.warn(Component.translatable("kinora.render.cannot_start"), Component.translatable("kinora.render.no_ffmpeg"));
                    return false;
                }
                List<VideoSink.Part> parts = job.parts.stream().map(p -> new VideoSink.Part(Path.of(p.file), p.frames)).toList();
                firstFrame = parts.stream().mapToInt(VideoSink.Part::frames).sum();
                video = new VideoSink(ffmpeg, s, output, parts);
                sink = video;
            } else {
                images = new ImageSequenceSink(output, baseName(job), ImageSequenceSink.Format.of(s.output), s.alpha(), 1);
                firstFrame = images.completeFrames(job.frameCount);
                sink = images;
            }
            if (firstFrame >= job.frameCount && !job.video()) {
                job.state = RenderJob.State.DONE;
                job.save(jobFile);
                return false;
            }
            RenderRunner runner = new RenderRunner(job, jobFile, firstFrame, sink, video, images);
            runner.begin(session);
            return true;
        } catch (IOException | RuntimeException e) {
            KinoraMod.LOG.warn("Kinora could not start a render", e);
            Notify.warn(Component.translatable("kinora.render.cannot_start"), Component.literal(String.valueOf(e.getMessage())));
            return false;
        }
    }

    static String baseName(RenderJob job) {
        String name = Path.of(job.output).getFileName().toString();
        return name.isEmpty() ? "frame" : name;
    }

    private void begin(ReplaySession session) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        active = this;
        job.state = RenderJob.State.RENDERING;
        job.framesDone = firstFrame;
        job.error = "";
        job.save(jobFile);
        mc.gui.setScreen(null);
        // Section fade-in runs on the wall clock: off, or frames would depend on render speed.
        mc.options.chunkSectionFadeInTime().set(0.0);
        // Render as fast as the machine allows: no vsync wait, no frame cap (see RenderHooks.framerateLimit).
        mc.options.enableVsync().set(false);
        applyWindowSize();
        encoder.start();
        session.setPaused(true);
        session.setDriver(this);
        // Follow, orbit and look-at targets: record their movement from now, and start the replay a
        // little before the first frame, so camera smoothing has the history it needs on frame one.
        for (RenderPlan.Unit u : units) {
            scene().track(u.shot().rig.targetEntity);
            scene().track(u.shot().lookAt.targetEntity);
        }
        if (!units.isEmpty()) {
            session.seek(Math.max(session.startTick(), units.getFirst().replayTicks() - PREROLL_TICKS), true);
        }
        ReplayManager.INSTANCE.camera().path(this);
        RenderHooks.install(this);
        for (var l : dev.kinora.api.Kinora.listeners()) {
            try {
                l.onRenderStart();
            } catch (RuntimeException e) {
                KinoraMod.LOG.warn("A Kinora listener failed", e);
            }
        }
        KinoraMod.LOG.info("Rendering {} ({} frames from {}, {}x{})", job.title, job.frameCount, firstFrame, width, height);
    }

    // ------------------------------------------------------------------ per frame

    /** The replay time to show this frame: the current unit's. */
    @Override
    public double frame() {
        applyWindowSize();
        RenderPlan.Unit u = current();
        if (u != null) {
            try {
                announce(u);
            } catch (RuntimeException e) {
                // Telling other mods must never stop a render.
                KinoraMod.LOG.warn("Kinora could not notify render listeners", e);
            }
            // The shot's world tracks: time of day and weather for this moment (negative: as recorded).
            var values = ShotEvaluator.evaluate(u.shot(), u.shotTime(), scene()).values();
            double time = values.getOrDefault(dev.kinora.core.project.Tracks.WORLD_TIME, -1.0);
            double weather = values.getOrDefault(dev.kinora.core.project.Tracks.WORLD_WEATHER, -1.0);
            ReplayManager.INSTANCE.scene().setRenderWorld(time >= 0 ? time : Double.NaN, weather >= 0 ? weather : Double.NaN);
            // Shader packs animate on this clock (see the Iris compat mixins): output time, not wall time.
            double frameSeconds = (double) job.settings.fpsDenominator / job.settings.fpsNumerator;
            double seconds = RenderPlan.frameTime(u.frame(), job.settings) + u.subFrame() * frameSeconds / Math.max(1, job.settings.motionBlurSamples);
            RenderHooks.setShaderClock(seconds, frameSeconds, (long) u.frame() * Math.max(1, job.settings.motionBlurSamples) + u.subFrame());
        }
        return u == null ? Double.NaN : u.replayTicks();
    }

    @Override
    public void afterTick(long completedTicks) {
        scene().afterTick(completedTicks);
        if (audio != null && !stopping) {
            audio.tick(completedTicks);
        }
    }

    /** The camera for this frame: the current unit's shot, evaluated at its shot time. */
    @Override
    public @Nullable CameraState camera(float partialTick) {
        RenderPlan.Unit u = current();
        if (u == null) {
            return camera;
        }
        ShotEvaluator.Frame frame = ShotEvaluator.evaluate(u.shot(), u.shotTime(), scene());
        grade = dev.kinora.core.render.Grade.of(frame.values());
        CameraState c = frame.camera();
        lens = lensFor(u, frame, c);
        if (c != null && u.whipYaw() != 0) {
            c = c.withRotation(c.yaw() + u.whipYaw(), c.pitch(), c.roll());
        }
        // The shot camera; a 360° or stereo render turns or offsets it per view.
        camera = c;
        RenderHooks.setTile(dev.kinora.core.render.Views.tile(u.view(), job.settings));
        return c == null ? null : dev.kinora.core.render.Views.camera(c, u.view(), job.settings);
    }

    private RenderPlan.@Nullable Unit current() {
        return next < units.size() ? units.get(next) : null;
    }

    private static LiveScene scene() {
        return ReplayManager.INSTANCE.editor().scene();
    }

    /** Called after the world is drawn, before the GUI: capture if this frame is the one we need. */
    @Override
    public void worldRendered(RenderTarget mainTarget) {
        RenderPlan.Unit shown = current();
        try {
            captureStep(mainTarget);
        } finally {
            steadyPreview(mainTarget, shown);
        }
    }

    /**
     * A 360° or stereo render draws six or two directions in turn; the window would flicker between
     * them. The window keeps showing the last front view (view 0) instead. The capture has already
     * been queued, so this only changes what is presented.
     */
    private void steadyPreview(RenderTarget mainTarget, RenderPlan.@Nullable Unit shown) {
        var color = mainTarget.getColorTexture();
        if (shown == null || stopping || color == null || dev.kinora.core.render.Views.count(job.settings) == 1
                || mainTarget.width != width || mainTarget.height != height) {
            return;
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (shown.view() == 0) {
            if (preview == null) {
                // COPY_DST | COPY_SRC | TEXTURE_BINDING
                preview = RenderSystem.getDevice().createTexture(() -> "Kinora preview", 1 | 2 | 4, color.getFormat(), width, height, 1, 1);
            }
            encoder.copyTextureToTexture(color, preview, 0, 0, 0, 0, 0, width, height);
        } else if (preview != null) {
            encoder.copyTextureToTexture(preview, color, 0, 0, 0, 0, 0, width, height);
        }
    }

    private void captureStep(RenderTarget mainTarget) {
        if (stopping) {
            return;
        }
        checkEncoder();
        RenderPlan.Unit u = current();
        if (u == null) {
            finishWhenDrained();
            return;
        }
        ReplaySession session = ReplayManager.INSTANCE.session();
        if (session == null) {
            fail("the replay closed");
            return;
        }
        boolean loading = session.phase() == ReplaySession.Phase.LOADING;
        if (loading && !wasLoading && audio != null) {
            // The world is being rebuilt: sounds followed so far belonged to the old one.
            audio.endLoops();
        }
        wasLoading = loading;
        if (session.phase() != ReplaySession.Phase.PLAYING || session.fastForwarding()) {
            warmUp = WARM_UP_FRAMES;
            fresh = true;
            framesLoading++;
            return;
        }
        long whole = (long) Math.floor(Math.max(session.startTick(), Math.min(u.replayTicks(), session.endTick())));
        if (session.completedTicks() != whole || mainTarget.width != width || mainTarget.height != height) {
            framesOther++;
            return;
        }
        if (!settled()) {
            framesSettling++;
            return;
        }
        if (!encoder.hasRoom()) {
            framesEncoder++;
            return;
        }
        if (!capture.canCapture()) {
            framesReadback++;
            return;
        }
        framesCaptured++;
        RenderPlan.Unit unit = u;
        if (audio != null && camera != null && u.layer() == 0 && u.view() == 0
                && u.subFrame() == Math.max(1, job.settings.motionBlurSamples) / 2) {
            audio.listener(u.frame(), camera.x(), camera.y(), camera.z(), camera.yaw());
        }
        if (dev.kinora.mc.dev.DevScript.enabled() && Boolean.getBoolean("kinora.dev.renderTrace")) {
            Minecraft mc = Minecraft.getInstance();
            var cam = mc.gameRenderer.mainCamera().position();
            KinoraMod.LOG.info("[trace] frame {} tick {} partial {} gameTime {} cloudColor {} cam {},{},{}", u.frame(), session.completedTicks(),
                    mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), mc.level.getGameTime(),
                    Integer.toHexString(mc.gameRenderer.gameRenderState().levelRenderState.cloudColor), cam.x, cam.y, cam.z);
            if (u.frame() % 50 == 0) {
                for (var e : mc.level.entitiesForRendering()) {
                    if (e instanceof net.minecraft.world.entity.Mob mob) {
                        KinoraMod.LOG.info("[trace]   mob {} {} age {} pos {},{},{} body {} head {} walk {} {} attack {} swing {}", mob.getId(),
                                mob.getType().toShortString(), mob.tickCount, mob.getX(), mob.getY(), mob.getZ(), mob.yBodyRot, mob.yHeadRot,
                                mob.walkAnimation.position(), mob.walkAnimation.speed(), mob.attackAnim, mob.swingTime);
                    }
                }
            }
        }
        dev.kinora.core.render.Grade look = grade;
        dev.kinora.core.render.DepthOfField dof = lens;
        if (dof == null && !needDepth) {
            // Alpha is made from depth (Pixels.cutSky); the world's own alpha means nothing.
            capture.capture(mainTarget.getColorTexture(), false, pixels -> submit(unit, pixels, look, null, null, 0, 0));
        } else {
            // Colour and depth arrive separately; submit once both are in.
            var m = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
            double m22 = m.m22();
            double m32 = m.m32();
            var withMatrix = dof == null ? null : new dev.kinora.core.render.DepthOfField(dof.focus(), dof.fNumber(), dof.fov(), m22, m32);
            byte[][] color = new byte[1][];
            float[][] depth = new float[1][];
            boolean[] arrived = new boolean[2];
            Runnable maybeSubmit = () -> {
                if (arrived[0] && arrived[1]) {
                    submit(unit, color[0], look, withMatrix, depth[0], m22, m32);
                }
            };
            capture.capture(mainTarget.getColorTexture(), false, pixels -> {
                color[0] = pixels;
                arrived[0] = true;
                maybeSubmit.run();
            });
            depthCopy.read(d -> {
                depth[0] = d;
                arrived[1] = true;
                maybeSubmit.run();
            });
        }
        if (u.subFrame() == Math.max(1, job.settings.motionBlurSamples) - 1 && u.layer() == 0
                && u.view() == dev.kinora.core.render.Views.count(job.settings) - 1) {
            var clock = clock();
            for (var l : dev.kinora.api.Kinora.listeners()) {
                try {
                    l.onFrameEnd(clock);
                } catch (RuntimeException e) {
                    KinoraMod.LOG.warn("A Kinora listener failed", e);
                }
            }
        }
        next++;
        unitSince = System.nanoTime();
        waited = false;
        idleFrames = 0;
    }

    /**
     * The lens for a unit when its shot keys {@code dof.focus} or {@code dof.aperture}. Without a
     * focus key the lens focuses on the look-at or rig target, else on the middle of the frame.
     */
    private dev.kinora.core.render.@Nullable DepthOfField lensFor(RenderPlan.Unit u, dev.kinora.core.project.ShotEvaluator.Frame frame,
                                                                  @Nullable CameraState c) {
        var shot = u.shot();
        boolean keyed = shot.existing(dev.kinora.core.project.Tracks.DOF_FOCUS) != null
                || shot.existing(dev.kinora.core.project.Tracks.DOF_APERTURE) != null;
        if (!keyed || c == null) {
            return null;
        }
        double focus = Double.NaN;
        if (shot.existing(dev.kinora.core.project.Tracks.DOF_FOCUS) != null) {
            focus = frame.value(dev.kinora.core.project.Tracks.DOF_FOCUS);
        } else {
            dev.kinora.core.camera.Vec3d target = null;
            if (shot.lookAt.enabled && shot.lookAt.targetEntity != Integer.MIN_VALUE) {
                target = scene().entityPosition(shot.lookAt.targetEntity, u.replayTicks());
            } else if (shot.lookAt.enabled && shot.lookAt.point != null) {
                target = shot.lookAt.point;
            } else if (shot.rig.targetEntity != Integer.MIN_VALUE && shot.rig.mode != dev.kinora.core.project.Shot.RigMode.PATH) {
                target = scene().entityPosition(shot.rig.targetEntity, u.replayTicks());
            }
            if (target != null) {
                // Distance along the view axis, to the target's middle.
                var aim = target.add(0, 1, 0).sub(c.position());
                var forward = c.forward();
                focus = Math.max(0.1, aim.x() * forward.x() + aim.y() * forward.y() + aim.z() * forward.z());
            }
        }
        return new dev.kinora.core.render.DepthOfField(focus, Math.max(0.7, frame.value(dev.kinora.core.project.Tracks.DOF_APERTURE)),
                c.fov(), 0, 0);
    }

    /** The world has been drawn (before the hand pass clears depth): keep its depth if the lens needs it. */
    public void afterLevel(RenderTarget mainTarget) {
        if ((lens != null || needDepth) && !stopping) {
            depthCopy.copy(mainTarget);
        }
    }

    /**
     * True when the world around the camera is fully built: no section waiting to compile and none
     * compiling (every compile buffer is back in its pool). If this frame had to wait, one more
     * frame is rendered so the last compiled sections are uploaded and drawn.
     *
     * <p>Missing chunks are not waited for: a replay only holds the chunks its player had loaded,
     * so the camera may expect chunks that never arrive.
     */
    private boolean settled() {
        Minecraft mc = Minecraft.getInstance();
        if (warmUp > 0) {
            warmUp--;
            return false;
        }
        boolean idle = terrain.idle();
        if (fresh) {
            // After a (re)load the visibility graph keeps finding sections for a while, between
            // which the builders look idle: wait until the drawn section count holds still.
            int sections = terrain.visibleSections();
            stableFrames = idle && sections == lastSections ? stableFrames + 1 : 0;
            lastSections = sections;
            if (stableFrames < FRESH_STABLE_FRAMES && System.nanoTime() - unitSince < SETTLE_TIMEOUT_NANOS) {
                return false;
            }
            fresh = false;
            return true;
        }
        if (!idle) {
            waited = true;
            idleFrames = 0;
            if (System.nanoTime() - unitSince > SETTLE_TIMEOUT_NANOS) {
                unsettledFrames++;
                if (unsettledFrames <= 5) {
                    KinoraMod.LOG.warn("Kinora rendered frame {} while chunks were still building", current().frame());
                }
                return true;
            }
            return false;
        }
        idleFrames++;
        return !waited || idleFrames >= 2;
    }

    private void submit(RenderPlan.Unit unit, byte[] pixels, dev.kinora.core.render.Grade look,
                        dev.kinora.core.render.@Nullable DepthOfField dof, float @Nullable [] depth, double m22, double m32) {
        if (stopping) {
            return;
        }
        int ss = job.settings.supersampling;
        int w = width;
        int h = height;
        boolean alpha = job.settings.alpha() && depth != null;
        if (depthPass != null && depth != null && representative(unit)) {
            try {
                depthPass.write(unit.frame(), depth, w, h, m22, m32, ss);
            } catch (IOException e) {
                fail("the depth pass could not be written: " + e.getMessage());
                return;
            }
        }
        // Sky cut-out and lens blur at the rendered size, then scaling down: all on the encoder thread.
        java.util.function.UnaryOperator<byte[]> prepare = dof == null && ss <= 1 && !alpha ? null : img -> {
            if (alpha) {
                dev.kinora.core.render.Pixels.cutSky(img, depth);
            }
            if (dof != null && depth != null) {
                dof.apply(img, depth, w, h);
            }
            return ss > 1 ? Downscale.box(img, w, h, ss) : img;
        };
        java.util.function.Consumer<byte[]> titles = null;
        var overlays = unit.shot().overlays;
        if (overlays != null && !overlays.isEmpty() && job.settings.projection == RenderSettings.Projection.NORMAL
                && dev.kinora.core.render.Overlays.any(overlays, unit.shotTime())) {
            var list = List.copyOf(overlays);
            double t = unit.shotTime();
            int ow = job.settings.width;
            int oh = job.settings.height;
            titles = img -> dev.kinora.core.render.Overlays.draw(img, ow, oh, list, t, KinoraPaths.projects());
        }
        try {
            encoder.submit(unit, pixels, look, prepare, titles);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The image a frame's depth comes from: the main layer's middle motion-blur sample. */
    private boolean representative(RenderPlan.Unit u) {
        return u.layer() == 0 && u.view() == 0 && u.subFrame() == Math.max(1, job.settings.motionBlurSamples) / 2;
    }

    private void checkEncoder() {
        if (encoder.state() == RenderEncoder.State.FAILED) {
            Throwable e = encoder.error();
            fail(e == null ? "the encoder failed" : String.valueOf(e.getMessage()));
            return;
        }
        long now = System.nanoTime();
        if (now - lastSave > 2_000_000_000L) {
            lastSave = now;
            job.framesDone = encoder.framesWritten();
            saveQuietly();
            if (audio != null) {
                audio.flush();
            }
        }
    }

    private void finishWhenDrained() {
        if (capture.inFlight() > 0) {
            return;
        }
        if (!inputEnded) {
            inputEnded = true;
            try {
                encoder.endOfInput();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        switch (encoder.state()) {
            case FINISHED -> {
                if (depthPass != null && !depthPassDone) {
                    depthPassDone = true;
                    try {
                        depthPass.finish();
                    } catch (IOException e) {
                        fail("the depth pass could not be written: " + e.getMessage());
                        return;
                    }
                }
                if (audio != null && !audioDone) {
                    if (audioThread == null) {
                        // Mixing and muxing take a few seconds: off the game thread.
                        audioThread = Thread.ofPlatform().daemon().name("Kinora audio").start(() -> {
                            try {
                                audio.finish();
                            } catch (Throwable t) {
                                audioError = t;
                            } finally {
                                audioDone = true;
                            }
                        });
                    }
                    return;
                }
                if (audio != null) {
                    audio.close();
                    if (audioError != null) {
                        KinoraMod.LOG.warn("Kinora could not add sound to {}", job.output, audioError);
                        Notify.warn(Component.translatable("kinora.render.no_audio"), Component.literal(String.valueOf(audioError.getMessage())));
                    }
                }
                RenderSettings rs = job.settings;
                if (job.video() && rs.projection == RenderSettings.Projection.EQUIRECTANGULAR
                        && (rs.container.equals("mp4") || rs.container.equals("mov"))) {
                    // 360° players and YouTube recognise the video by this metadata.
                    try {
                        dev.kinora.core.render.SphericalMetadata.markEquirectangular(Path.of(job.output));
                    } catch (IOException e) {
                        KinoraMod.LOG.warn("Kinora could not mark {} as 360° video", job.output, e);
                    }
                }
                if (job.frameCount == 1 && imageSink != null) {
                    // A photo: one file, not a folder holding one frame.
                    asSingleFile();
                }
                job.state = RenderJob.State.DONE;
                job.framesDone = job.frameCount;
                job.parts.clear();
                job.finished = System.currentTimeMillis();
                saveQuietly();
                end();
                double seconds = (System.nanoTime() - startNanos) / 1e9;
                KinoraMod.LOG.info("Rendered {} in {} s ({} frames)", job.title, Math.round(seconds), job.frameCount);
                job.report = report(seconds);
                saveQuietly();
                KinoraMod.LOG.info("Kinora render report: {}", job.report.replace(System.lineSeparator(), " "));
                Notify.info(Component.translatable("kinora.render.done"), Component.literal(Path.of(job.output).getFileName().toString()));
            }
            case FAILED -> fail(String.valueOf(encoder.error() == null ? "the encoder failed" : encoder.error().getMessage()));
            default -> {
                // Still writing.
            }
        }
    }

    /** A sound the replay is playing (Kinora silences it); recorded for the render's audio. */
    public void onSound(net.minecraft.client.resources.sounds.SoundInstance sound) {
        ReplaySession session = ReplayManager.INSTANCE.session();
        if (audio == null || stopping || session == null) {
            return;
        }
        // Continuous sounds start when their entity appears, which happens while a rebuilt world
        // loads: follow those whenever they start. One-off sounds only count while playing.
        boolean continuous = sound instanceof net.minecraft.client.resources.sounds.TickableSoundInstance;
        if (!continuous && (session.phase() != ReplaySession.Phase.PLAYING || session.fastForwarding())) {
            return;
        }
        audio.heard(sound, session.completedTicks());
    }

    /** True once all frames are written and the sound is being mixed. */
    public boolean mixingAudio() {
        return audioThread != null && !audioDone;
    }

    private boolean depthPassDone;

    private void asSingleFile() {
        try {
            Path folder = Path.of(job.output);
            Path frame = imageSink.file(0);
            String name = frame.getFileName().toString();
            Path single = folder.resolveSibling(folder.getFileName() + name.substring(name.lastIndexOf('.')));
            java.nio.file.Files.move(frame, single, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (var rest = java.nio.file.Files.list(folder)) {
                if (rest.findAny().isEmpty()) {
                    java.nio.file.Files.delete(folder);
                }
            }
            job.output = single.toString();
        } catch (IOException e) {
            KinoraMod.LOG.warn("Kinora could not move the photo out of {}", job.output, e);
        }
    }

    /**
     * What the render spent its time on, and the most likely way to make it faster. Every game frame
     * either captured an image or waited for something; the biggest share of waiting is the bottleneck.
     */
    private String report(double seconds) {
        long total = Math.max(1, framesLoading + framesSettling + framesEncoder + framesReadback + framesCaptured + framesOther);
        double fps = unitsTotal() / Math.max(1e-3, seconds);
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT,
                "%d images in %.0f s (%.1f per second) at %d x %d.%n", unitsTotal(), seconds, fps, width, height));
        sb.append(String.format(java.util.Locale.ROOT, "Game frames: %d%% captured, %d%% waiting for chunks, %d%% waiting for the encoder, "
                        + "%d%% waiting for the GPU copy, %d%% loading or seeking, %d%% other.%n", pct(framesCaptured, total),
                pct(framesSettling, total), pct(framesEncoder, total), pct(framesReadback, total), pct(framesLoading, total), pct(framesOther, total)));
        double[] st = encoder.stageSeconds();
        sb.append(String.format(java.util.Locale.ROOT, "Encoder thread: %.1f s effects, %.1f s grading, %.1f s titles, %.1f s mixing, %.1f s output.%n",
                st[0], st[1], st[2], st[3], st[4]));
        long worst = Math.max(Math.max(framesSettling, framesReadback), Math.max(framesEncoder, framesLoading));
        if (worst * 4 < total) {
            sb.append("Hint: the game's own drawing is the limit; lower render distance or graphics settings, or turn off shaders, to go faster.");
        } else if (worst == framesEncoder) {
            boolean output = st[4] > st[0] + st[1] + st[2] + st[3];
            boolean video = job.settings.output == dev.kinora.core.render.RenderSettings.Output.VIDEO;
            if (!output) {
                sb.append("Hint: the encoder thread is the limit; fewer effects (grading, depth of field) or less supersampling helps.");
            } else if (video && job.settings.videoCodec.startsWith("lib")) {
                sb.append("Hint: FFmpeg's encoder is the limit; a GPU format (\"on the NVIDIA / AMD / Intel GPU\") takes about a third of the time.");
            } else if (video) {
                sb.append("Hint: FFmpeg's encoder is the limit; a lighter codec or a lower quality setting helps.");
            } else {
                sb.append("Hint: writing the image files is the limit (compression and disk); a video format is faster.");
            }
        } else if (worst == framesReadback) {
            sb.append("Hint: copying images from the GPU is the limit; a smaller size or less supersampling helps.");
        } else if (worst == framesSettling) {
            sb.append("Hint: building chunks is the limit; a slower camera, a lower render distance or Sodium helps.");
        } else {
            sb.append("Hint: seeking is the limit; avoid time remaps that jump far back, and shots far apart in the replay.");
        }
        return sb.toString();
    }

    private static int pct(long part, long total) {
        return (int) Math.round(100.0 * part / total);
    }

    // ------------------------------------------------------------------ stopping

    /** Stops the render; it can be resumed from the queue. */
    public void stop() {
        if (stopping || audioThread != null) {
            // Once the sound is being mixed every frame is written: let it finish.
            return;
        }
        stopping = true;
        KinoraMod.LOG.info("Kinora render stopped at frame {}", encoder.framesWritten());
        encoder.cancel();
        job.state = RenderJob.State.PAUSED;
        if (audio != null) {
            audio.close();
        }
        recordProgress();
        saveQuietly();
        end();
        Notify.info(Component.translatable("kinora.render.stopped"), Component.translatable("kinora.render.stopped_hint"));
    }

    private void fail(String message) {
        if (stopping) {
            return;
        }
        stopping = true;
        encoder.cancel();
        job.state = RenderJob.State.FAILED;
        job.error = message;
        if (audio != null) {
            audio.close();
        }
        recordProgress();
        saveQuietly();
        end();
        KinoraMod.LOG.warn("Kinora render failed: {}", message);
        Notify.warn(Component.translatable("kinora.render.failed"), Component.literal(message));
    }

    private void recordProgress() {
        if (videoSink != null) {
            job.parts.clear();
            for (VideoSink.Part p : videoSink.parts()) {
                job.parts.add(new RenderJob.Part(p.file().toString(), p.frames()));
            }
            job.framesDone = job.parts.stream().mapToInt(p -> p.frames).sum();
        } else if (imageSink != null) {
            job.framesDone = imageSink.completeFrames(job.frameCount);
        }
    }

    private void end() {
        stopping = true;
        Minecraft mc = Minecraft.getInstance();
        RenderHooks.install(null);
        RenderHooks.setTile(null);
        capture.close();
        depthCopy.close();
        if (depthPass != null) {
            depthPass.close();
        }
        if (preview != null) {
            preview.close();
            preview = null;
        }
        encoder.close();
        mc.options.chunkSectionFadeInTime().set(savedFadeIn);
        ReplayManager.INSTANCE.scene().setRenderWorld(Double.NaN, Double.NaN);
        mc.options.enableVsync().set(savedVsync);
        restoreWindowSize();
        ReplayManager manager = ReplayManager.INSTANCE;
        ReplaySession session = manager.session();
        if (session != null) {
            manager.restoreDriver();
        }
        manager.camera().free();
        manager.updateCameraMode();
        active = null;
        lastResult = job.state;
        boolean completed = job.state == RenderJob.State.DONE;
        for (var l : dev.kinora.api.Kinora.listeners()) {
            try {
                l.onRenderFinish(completed);
            } catch (RuntimeException e) {
                KinoraMod.LOG.warn("A Kinora listener failed", e);
            }
        }
    }

    private void saveQuietly() {
        try {
            job.save(jobFile);
        } catch (IOException e) {
            KinoraMod.LOG.warn("Kinora could not save render progress to {}", jobFile, e);
        }
    }

    // ------------------------------------------------------------------ window size

    /**
     * Makes the game render at the output size, the way vanilla's panorama screenshot does: the
     * framebuffer size the renderer reads is overridden, the real window is untouched and shows the
     * frames scaled. Re-applied every frame because a real resize event resets it.
     */
    private void applyWindowSize() {
        Window window = Minecraft.getInstance().getWindow();
        if (window.getWidth() != width || window.getHeight() != height) {
            window.setWidth(width);
            window.setHeight(height);
        }
    }

    private static void restoreWindowSize() {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        int[] w = new int[1];
        int[] h = new int[1];
        GLFW.glfwGetFramebufferSize(window.handle(), w, h);
        window.setWidth(Math.max(1, w[0]));
        window.setHeight(Math.max(1, h[0]));
        mc.resizeGui();
    }

    // ------------------------------------------------------------------ progress

    public RenderJob job() {
        return job;
    }

    /** The API's render clock for the unit being rendered now. */
    public dev.kinora.api.RenderClock clock() {
        RenderPlan.Unit u = current();
        int samples = Math.max(1, job.settings.motionBlurSamples);
        double fps = job.settings.fps();
        if (u == null) {
            return new dev.kinora.api.RenderClock(job.frameCount, 0, samples, job.frameCount / fps, fps);
        }
        double seconds = RenderPlan.frameTime(u.frame(), job.settings) + (samples > 1 ? (u.subFrame() + 0.5) / samples / fps : 0);
        return new dev.kinora.api.RenderClock(u.frame(), u.subFrame(), samples, seconds, fps);
    }

    /** Tells API listeners a new image is about to be rendered (once per unit, in render order). */
    private void announce(RenderPlan.Unit u) {
        if (u == announced) {
            return;
        }
        announced = u;
        if (dev.kinora.api.Kinora.listeners().isEmpty()) {
            return;
        }
        var clock = clock();
        long whole = (long) Math.floor(u.replayTicks());
        // A fraction just below 1 can round to 1.0f, which ReplayTime rightly refuses.
        float partial = Math.min(Math.nextDown(1f), (float) (u.replayTicks() - whole));
        var time = new dev.kinora.api.ReplayTime(whole, Math.max(0f, partial));
        for (var l : dev.kinora.api.Kinora.listeners()) {
            try {
                if (u.subFrame() == 0 && u.layer() == 0 && u.view() == 0) {
                    l.onFrameBegin(clock);
                }
                l.onSubFrame(clock, time);
            } catch (RuntimeException e) {
                KinoraMod.LOG.warn("A Kinora listener failed", e);
            }
        }
    }

    private RenderPlan.@Nullable Unit announced;

    public int framesWritten() {
        return encoder.framesWritten();
    }

    public int unitsRendered() {
        return next;
    }

    public int unitsTotal() {
        return units.size();
    }

    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    public int unsettledFrames() {
        return unsettledFrames;
    }
}
