package dev.kinora.mc.ui;

import dev.kinora.core.project.Project;
import dev.kinora.core.project.SequenceTimeline;
import dev.kinora.core.project.Shot;
import dev.kinora.core.render.Ffmpeg;
import dev.kinora.core.render.RenderJob;
import dev.kinora.core.render.RenderPlan;
import dev.kinora.core.render.RenderPresets;
import dev.kinora.core.render.RenderSettings;
import dev.kinora.mc.KinoraConfig;
import dev.kinora.mc.editor.EditorState;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.render.RenderJobs;
import dev.kinora.mc.ui.kit.Theme;
import dev.kinora.mc.ui.kit.Ui;
import dev.kinora.mc.util.KinoraPaths;
import dev.kinora.mc.util.Notify;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The render dialog: what to render (the shot or the whole edit), at which size, frame rate and
 * format, and where. Settings are kept in the project, so the next render starts from them.
 */
public final class RenderScreen extends Screen {
    /** Output formats offered, in order. Codec settings follow FFmpeg names. */
    record Format(String key, RenderSettings.Output output, String codec, String container, String pixelFormat, int quality) {
        boolean video() {
            return output == RenderSettings.Output.VIDEO;
        }
    }

    static final List<Format> FORMATS = List.of(
            new Format("h264", RenderSettings.Output.VIDEO, "libx264", "mp4", "yuv420p", 18),
            new Format("h265", RenderSettings.Output.VIDEO, "libx265", "mp4", "yuv420p", 20),
            // GPU encoders: offered only when they work on this machine (see probeHardware).
            new Format("h264_nvenc", RenderSettings.Output.VIDEO, "h264_nvenc", "mp4", "yuv420p", 21),
            new Format("hevc_nvenc", RenderSettings.Output.VIDEO, "hevc_nvenc", "mp4", "yuv420p", 23),
            new Format("h264_amf", RenderSettings.Output.VIDEO, "h264_amf", "mp4", "yuv420p", 20),
            new Format("hevc_amf", RenderSettings.Output.VIDEO, "hevc_amf", "mp4", "yuv420p", 22),
            new Format("h264_qsv", RenderSettings.Output.VIDEO, "h264_qsv", "mp4", "yuv420p", 21),
            new Format("hevc_qsv", RenderSettings.Output.VIDEO, "hevc_qsv", "mp4", "yuv420p", 23),
            new Format("av1", RenderSettings.Output.VIDEO, "libsvtav1", "mp4", "yuv420p", 28),
            new Format("prores", RenderSettings.Output.VIDEO, "prores_ks", "mov", "yuv422p10le", 3),
            new Format("prores4444", RenderSettings.Output.VIDEO, "prores_ks", "mov", "yuva444p10le", 4),
            new Format("ffv1", RenderSettings.Output.VIDEO, "ffv1", "mkv", "rgb24", 0),
            new Format("vp9", RenderSettings.Output.VIDEO, "libvpx-vp9", "webm", "yuv420p", 32),
            new Format("png", RenderSettings.Output.PNG_SEQUENCE, "", "", "", 0),
            new Format("png16", RenderSettings.Output.PNG16_SEQUENCE, "", "", "", 0),
            new Format("exr", RenderSettings.Output.EXR_SEQUENCE, "", "", "", 0));
    private static final int[][] FRAME_RATES = {{24000, 1001}, {24, 1}, {25, 1}, {30000, 1001}, {30, 1}, {48, 1}, {50, 1}, {60, 1},
            {120, 1}};
    private static final int[] BLUR_SAMPLES = {1, 4, 8, 16, 32};
    private static final int PANEL_WIDTH = 320;

    private final @Nullable Screen parent;
    private final EditorState editor;
    private final RenderSettings settings;
    private boolean wholeSequence;
    private int presetIndex = -1;
    private EditBox widthBox;
    private EditBox heightBox;
    private EditBox qualityBox;
    private EditBox nameBox;
    private int summaryY;
    private final java.nio.file.@Nullable Path ffmpeg;

    public RenderScreen(@Nullable Screen parent) {
        super(Component.translatable("kinora.render.title"));
        this.parent = parent;
        this.editor = ReplayManager.INSTANCE.editor();
        this.settings = editor.project().render.copy();
        this.wholeSequence = editor.view() == EditorState.View.SEQUENCE || editor.shot() == null;
        this.ffmpeg = Ffmpeg.locate(KinoraConfig.ffmpegPath(), KinoraPaths.tools());
        this.encoders = ffmpeg == null ? java.util.Set.of() : encodersOf(ffmpeg);
        if (ffmpeg != null) {
            probeHardware(ffmpeg, encoders);
        }
    }

    private final java.util.Set<String> encoders;
    private static java.nio.file.@Nullable Path checkedFfmpeg;
    private static java.util.Set<String> checkedEncoders = java.util.Set.of();

    /** Asking FFmpeg takes a moment, so the answer is kept for as long as the path stays the same. */
    private static java.util.Set<String> encodersOf(java.nio.file.Path ffmpeg) {
        if (!ffmpeg.equals(checkedFfmpeg)) {
            checkedEncoders = Ffmpeg.encoders(ffmpeg);
            checkedFfmpeg = ffmpeg;
        }
        return checkedEncoders;
    }

    private boolean available(Format f) {
        if (Ffmpeg.HARDWARE_CODECS.contains(f.codec())) {
            return ffmpeg != null && ffmpeg.equals(hardwareFfmpeg) && hardware.contains(f.codec());
        }
        return !f.video() || ffmpeg != null && encoders.contains(f.codec());
    }

    /** GPU encoders that work here, found in the background (each is tried on one small frame). */
    private static volatile java.util.Set<String> hardware = java.util.Set.of();
    private static volatile java.nio.file.@Nullable Path hardwareFfmpeg;
    private static java.nio.file.@Nullable Path hardwareProbing;

    private static synchronized void probeHardware(java.nio.file.Path ffmpeg, java.util.Set<String> listed) {
        if (ffmpeg.equals(hardwareProbing)) {
            return;
        }
        hardwareProbing = ffmpeg;
        Thread.ofVirtual().name("Kinora GPU encoder check").start(() -> {
            java.util.Set<String> found = new java.util.HashSet<>();
            for (String codec : Ffmpeg.HARDWARE_CODECS) {
                if (listed.contains(codec) && Ffmpeg.canEncode(ffmpeg, codec)) {
                    found.add(codec);
                }
            }
            hardware = java.util.Set.copyOf(found);
            hardwareFfmpeg = ffmpeg;
        });
    }

    /** Formats the format button cycles through: GPU encoders only when they work here (or are already chosen). */
    private boolean offered(Format f) {
        return !Ffmpeg.HARDWARE_CODECS.contains(f.codec()) || available(f) || f == formatOf(settings);
    }

    private int left() {
        return (this.width - PANEL_WIDTH) / 2;
    }

    private int top() {
        return Math.max(8, this.height / 2 - 149);
    }

    @Override
    protected void init() {
        int x = left() + 8;
        int w = PANEL_WIDTH - 16;
        int y = top() + 22;
        int half = (w - 4) / 2;

        // Preset.
        String presetName = presetIndex >= 0 ? presets().get(presetIndex).name : Component.translatable("kinora.render.preset.custom").getString();
        button(Component.translatable("kinora.render.preset", presetName), b -> {
            List<RenderSettings> presets = presets();
            presetIndex = (presetIndex + (this.minecraft.hasShiftDown() ? presets.size() - 1 : 1)) % presets.size();
            RenderSettings p = presets.get(presetIndex);
            String fileName = settings.fileName;
            String folder = settings.outputFolder;
            copyInto(p, settings);
            settings.fileName = fileName;
            settings.outputFolder = folder;
            rebuild();
        }, x, y, w, "kinora.render.preset.tooltip");
        y += 22;

        // What.
        Shot shot = editor.shot();
        Component what = wholeSequence || shot == null ? Component.translatable("kinora.render.what.sequence")
                : Component.translatable("kinora.render.what.shot", shot.name);
        Button whatButton = button(what, b -> {
            wholeSequence = !wholeSequence;
            rebuild();
        }, x, y, w, "kinora.render.what.tooltip");
        whatButton.active = shot != null && !editor.project().sequence.isEmpty();
        y += 30;

        // Size and frame rate.
        widthBox = box(x, y, 50, Integer.toString(settings.width), "kinora.render.width");
        heightBox = box(x + 56, y, 50, Integer.toString(settings.height), "kinora.render.height");
        button(Component.translatable("kinora.render.fps", fpsLabel(settings)), b -> {
            int i = 0;
            while (i < FRAME_RATES.length && !(FRAME_RATES[i][0] == settings.fpsNumerator && FRAME_RATES[i][1] == settings.fpsDenominator)) {
                i++;
            }
            int n = (i + (this.minecraft.hasShiftDown() ? FRAME_RATES.length - 1 : 1)) % FRAME_RATES.length;
            settings.fpsNumerator = FRAME_RATES[n][0];
            settings.fpsDenominator = FRAME_RATES[n][1];
            custom();
        }, x + 112, y + 10, w - 112, "kinora.render.fps.tooltip");
        y += 34;

        // Format and quality.
        Format format = formatOf(settings);
        Component formatName = Component.translatable("kinora.render.format." + format.key());
        if (!available(format) && ffmpeg != null) {
            formatName = Component.translatable(Ffmpeg.HARDWARE_CODECS.contains(format.codec()) && !ffmpeg.equals(hardwareFfmpeg)
                    ? "kinora.render.format.checking" : "kinora.render.format.unavailable", formatName);
        }
        button(Component.translatable("kinora.render.format", formatName), b -> {
            int i = FORMATS.indexOf(formatOf(settings));
            int step = this.minecraft.hasShiftDown() ? FORMATS.size() - 1 : 1;
            Format f;
            do {
                i = (i + step) % FORMATS.size();
                f = FORMATS.get(i);
            } while (!offered(f));
            settings.output = f.output();
            if (f.video()) {
                settings.videoCodec = f.codec();
                settings.container = f.container();
                settings.pixelFormat = f.pixelFormat();
                settings.quality = f.quality();
                // The rebuild reads the box back into the settings.
                qualityBox.setValue(Integer.toString(f.quality()));
                // hvc1 tags H.265 in MP4 so Apple players accept it.
                boolean hevc = f.codec().equals("libx265") || f.codec().startsWith("hevc_");
                settings.extraArgs = new ArrayList<>(hevc ? List.of("-tag:v", "hvc1") : List.of());
            }
            custom();
        }, x, y, w - 60, "kinora.render.format.tooltip");
        qualityBox = box(x + w - 56, y - 10, 56, Integer.toString(settings.quality), "kinora.render.quality");
        qualityBox.setTooltip(Tooltip.create(Component.translatable("kinora.render.quality.tooltip." + format.key())));
        qualityBox.visible = format.video() && !format.key().equals("ffv1");
        y += 22;

        // Motion blur and supersampling.
        Component blur = settings.motionBlurSamples <= 1 ? Component.translatable("kinora.render.blur.off")
                : Component.translatable("kinora.render.blur.on", settings.motionBlurSamples);
        button(blur, b -> {
            int i = 0;
            while (i < BLUR_SAMPLES.length && BLUR_SAMPLES[i] != settings.motionBlurSamples) {
                i++;
            }
            settings.motionBlurSamples = BLUR_SAMPLES[(i + (this.minecraft.hasShiftDown() ? BLUR_SAMPLES.length - 1 : 1)) % BLUR_SAMPLES.length];
            custom();
        }, x, y, half, "kinora.render.blur.tooltip");
        button(Component.translatable("kinora.render.supersampling", settings.supersampling), b -> {
            settings.supersampling = settings.supersampling % 4 + 1;
            custom();
        }, x + half + 4, y, half, "kinora.render.supersampling.tooltip");
        y += 22;

        // Projection and sound.
        button(Component.translatable("kinora.render.projection." + settings.projection.name().toLowerCase(Locale.ROOT)), b -> {
            RenderSettings.Projection[] all = RenderSettings.Projection.values();
            settings.projection = all[(settings.projection.ordinal() + (this.minecraft.hasShiftDown() ? all.length - 1 : 1)) % all.length];
            if (settings.projection == RenderSettings.Projection.EQUIRECTANGULAR) {
                // 360° video is 2:1.
                settings.height = Math.max(16, settings.width / 2);
            }
            custom();
        }, x, y, half, "kinora.render.projection.tooltip");
        Component sound = !settings.audio ? Component.translatable("kinora.render.sound.off")
                : Component.translatable("kinora.render.sound." + settings.pitchMode.name().toLowerCase(Locale.ROOT));
        button(sound, b -> {
            // On with tape pitch, on keeping pitch, off.
            if (!settings.audio) {
                settings.audio = true;
                settings.pitchMode = RenderSettings.PitchMode.FOLLOW;
            } else if (settings.pitchMode == RenderSettings.PitchMode.FOLLOW) {
                settings.pitchMode = RenderSettings.PitchMode.PRESERVE;
            } else {
                settings.audio = false;
            }
            custom();
        }, x + half + 4, y, half, "kinora.render.sound.tooltip");
        y += 22;

        // Extra images: a depth pass beside the output, and a transparent sky.
        boolean depthPass = settings.depthPass();
        boolean sky = settings.alpha();
        String passes = depthPass && sky ? "both" : depthPass ? "depth" : sky ? "sky" : "none";
        button(Component.translatable("kinora.render.passes." + passes), b -> {
            // None, depth, sky, both.
            int state = ((depthPass ? 1 : 0) + (sky ? 2 : 0) + (this.minecraft.hasShiftDown() ? 3 : 1)) % 4;
            settings.passes = new ArrayList<>(List.of(RenderSettings.Pass.COLOR));
            if ((state & 1) != 0) {
                settings.passes.add(RenderSettings.Pass.DEPTH);
            }
            settings.transparentSky = (state & 2) != 0;
            custom();
        }, x, y, w, "kinora.render.passes.tooltip");
        y += 30;

        // File name.
        nameBox = box(x, y, w, settings.fileName, "kinora.render.file_name");
        nameBox.setMaxLength(200);
        nameBox.setTooltip(Tooltip.create(Component.translatable("kinora.render.file_name.tooltip")));
        // Room for the two summary lines drawn in extractRenderState.
        summaryY = y + 32;
        y += 62;

        // Actions.
        int bw = (w - 12) / 4;
        Button now = Button.builder(Component.translatable("kinora.render.start"), b -> start(false)).bounds(x, y, bw, 20).build();
        now.active = ReplayManager.INSTANCE.session() != null;
        addRenderableWidget(now);
        addRenderableWidget(Button.builder(Component.translatable("kinora.render.enqueue"), b -> start(true)).bounds(x + bw + 4, y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("kinora.render.queue"), b -> {
            commit();
            this.minecraft.gui.setScreen(new RenderQueueScreen(this));
        }).bounds(x + 2 * (bw + 4), y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x + 3 * (bw + 4), y, bw, 20).build());
    }

    private Button button(Component label, Button.OnPress press, int x, int y, int w, String tooltipKey) {
        Button b = Button.builder(label, press).bounds(x, y, w, 18).build();
        b.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        return addRenderableWidget(b);
    }

    private EditBox box(int x, int y, int w, String value, String labelKey) {
        EditBox box = new EditBox(this.font, x, y + 10, w, 16, Component.translatable(labelKey));
        box.setValue(value);
        addRenderableWidget(box);
        addRenderableOnly((g, mx, my, pt) -> {
            if (box.visible) {
                g.text(this.font, Component.translatable(labelKey), x, y, Theme.textDim(), false);
            }
        });
        return box;
    }

    /** A setting was changed by hand: the preset no longer applies. */
    private void custom() {
        presetIndex = -1;
        rebuild();
    }

    private void rebuild() {
        commit();
        clearWidgets();
        init();
    }

    /** Copies the text fields into the settings. */
    private void commit() {
        if (widthBox == null) {
            return;
        }
        settings.width = parse(widthBox.getValue(), settings.width);
        settings.height = parse(heightBox.getValue(), settings.height);
        settings.quality = parse(qualityBox.getValue(), settings.quality);
        String name = nameBox.getValue().strip();
        settings.fileName = name.isEmpty() ? "{project}_{shot}" : name;
        settings.name = presetIndex >= 0 ? presets().get(presetIndex).name : "Custom";
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void start(boolean queueOnly) {
        commit();
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            Notify.warn(Component.translatable("kinora.render.cannot_start"), Component.literal(problems.getFirst()));
            return;
        }
        if (settings.output == RenderSettings.Output.VIDEO && ffmpeg == null) {
            Notify.warn(Component.translatable("kinora.render.cannot_start"), Component.translatable("kinora.render.no_ffmpeg"));
            return;
        }
        if (!available(formatOf(settings))) {
            Notify.warn(Component.translatable("kinora.render.cannot_start"), Component.translatable("kinora.render.no_encoder", settings.videoCodec));
            return;
        }
        Project project = editor.project();
        RenderSettings chosen = settings.copy();
        editor.projects().edit(() -> project.render = chosen.copy());
        Shot shot = wholeSequence ? null : editor.shot();
        if (wholeSequence && project.sequence.isEmpty()) {
            return;
        }
        RenderJob job = RenderJobs.create(project, shot, chosen);
        if (queueOnly) {
            try {
                job.save(RenderJobs.fileFor(job));
                Notify.info(Component.translatable("kinora.render.queued"), Component.literal(job.title));
            } catch (java.io.IOException e) {
                Notify.warn(Component.translatable("kinora.render.cannot_start"), Component.literal(String.valueOf(e.getMessage())));
            }
            return;
        }
        RenderJobs.startNow(job);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x0 = left();
        int y0 = top();
        Ui.panel(g, x0, y0, x0 + PANEL_WIDTH, y0 + 298);
        g.centeredText(this.font, this.title, this.width / 2, y0 + 7, Theme.text());
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // Summary of what will be made.
        int y = summaryY;
        RenderSettings s = settings;
        double seconds = duration();
        int frames = RenderPlan.frameCount(seconds, s);
        String summary = String.format(Locale.ROOT, "%d x %d, %s fps, %s, %d frames", parse(widthBox.getValue(), s.width),
                parse(heightBox.getValue(), s.height), fpsLabel(s), Ui.seconds(seconds), frames);
        g.text(this.font, summary, x0 + 8, y, Theme.textDim(), false);
        Component tool = s.output != RenderSettings.Output.VIDEO ? Component.translatable("kinora.render.images_note")
                : ffmpeg != null ? Component.translatable("kinora.render.ffmpeg_found", ffmpeg.toString())
                : Component.translatable("kinora.render.ffmpeg_missing");
        g.text(this.font, Ui.fit(this.font, tool.getString(), PANEL_WIDTH - 16), x0 + 8, y + 12,
                ffmpeg == null && s.output == RenderSettings.Output.VIDEO ? Theme.danger() : Theme.textDim(), false);
    }

    private double duration() {
        Project project = editor.project();
        Shot shot = editor.shot();
        if (wholeSequence || shot == null) {
            return new SequenceTimeline(project).duration();
        }
        return shot.duration;
    }

    @Override
    public void onClose() {
        commit();
        RenderSettings chosen = settings.copy();
        editor.projects().edit(() -> editor.project().render = chosen);
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ helpers

    /** Presets this version can render. */
    static List<RenderSettings> presets() {
        return RenderPresets.all().stream().filter(p -> p.output != RenderSettings.Output.VIDEO || !p.videoCodec.equals("gif")).toList();
    }

    static Format formatOf(RenderSettings s) {
        if (s.output != RenderSettings.Output.VIDEO) {
            for (Format f : FORMATS) {
                if (f.output() == s.output) {
                    return f;
                }
            }
            return FORMATS.getFirst();
        }
        // The codec and pixel format (ProRes 422 or 4444), else the codec alone.
        for (Format f : FORMATS) {
            if (f.video() && f.codec().equals(s.videoCodec) && f.pixelFormat().equals(s.pixelFormat)) {
                return f;
            }
        }
        for (Format f : FORMATS) {
            if (f.video() && f.codec().equals(s.videoCodec)) {
                return f;
            }
        }
        return FORMATS.getFirst();
    }

    static String fpsLabel(RenderSettings s) {
        if (s.fpsDenominator == 1) {
            return Integer.toString(s.fpsNumerator);
        }
        return String.format(Locale.ROOT, "%.3f", s.fps());
    }

    private static void copyInto(RenderSettings from, RenderSettings to) {
        RenderSettings c = from.copy();
        to.name = c.name;
        to.width = c.width;
        to.height = c.height;
        to.fpsNumerator = c.fpsNumerator;
        to.fpsDenominator = c.fpsDenominator;
        to.motionBlurSamples = c.motionBlurSamples;
        to.shutterAngle = c.shutterAngle;
        to.supersampling = c.supersampling;
        to.output = c.output;
        to.videoCodec = c.videoCodec;
        to.quality = c.quality;
        to.container = c.container;
        to.pixelFormat = c.pixelFormat;
        to.extraArgs = c.extraArgs;
        to.audio = c.audio;
    }
}
