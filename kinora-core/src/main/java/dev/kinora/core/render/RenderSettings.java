package dev.kinora.core.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything that decides what a render produces. Stored in projects and render jobs as JSON, so
 * field names are part of the file format.
 */
public final class RenderSettings {
    public enum Projection { NORMAL, EQUIRECTANGULAR, CUBEMAP, STEREO_SIDE_BY_SIDE, STEREO_TOP_BOTTOM }

    public enum Output { VIDEO, PNG_SEQUENCE, PNG16_SEQUENCE, EXR_SEQUENCE }

    public enum Pass { COLOR, DEPTH, ENTITY_ID, NORMAL, ALPHA }

    public enum PitchMode {
        /** Slow motion lowers pitch, like slowing a tape. */
        FOLLOW,
        /** Slow motion keeps pitch (time-stretched). */
        PRESERVE,
        /** No game audio during time-remapped sections. */
        MUTE
    }

    public String name = "Custom";
    public int width = 1920;
    public int height = 1080;
    /** Frame rate as a fraction: 24000/1001 is 23.976 fps. */
    public int fpsNumerator = 60;
    public int fpsDenominator = 1;
    /** Sub-frames per frame for motion blur; 1 disables it. */
    public int motionBlurSamples = 1;
    /** Exposure as a fraction of the frame interval in degrees: 180 is half the interval. */
    public double shutterAngle = 180;
    /** Supersampling factor per axis (1, 2, 3, 4). */
    public int supersampling = 1;
    /**
     * Tile grid per axis: the frame is rendered as {@code tiles x tiles} pieces and joined, for frames
     * larger than the GPU allows. 0 chooses automatically (only when needed), 1 is off. Normal view only.
     */
    public int tiles = 0;
    public Projection projection = Projection.NORMAL;
    /** Eye distance in blocks for stereo. */
    public double stereoEyeDistance = 0.064;
    public Output output = Output.VIDEO;
    /** FFmpeg video codec name, e.g. libx264, libx265, libsvtav1, prores_ks, dnxhd. */
    public String videoCodec = "libx264";
    /** Codec quality: CRF for x264/x265/AV1, profile for ProRes; meaning depends on the codec. */
    public int quality = 18;
    public String container = "mp4";
    public String pixelFormat = "yuv420p";
    /** Extra FFmpeg output arguments, appended verbatim. */
    public List<String> extraArgs = new ArrayList<>();
    public boolean audio = true;
    public int audioBitrateKbps = 320;
    public String musicPath = "";
    public double musicVolume = 1.0;
    public double gameVolume = 1.0;
    public PitchMode pitchMode = PitchMode.FOLLOW;
    public List<Pass> passes = new ArrayList<>(List.of(Pass.COLOR));
    /** Render the sky and fog transparent (needs an output with alpha). */
    public boolean transparentSky;
    /** Chroma-key background colour 0xRRGGBB when not transparent, or -1. */
    public int chromaKey = -1;
    public boolean hideHud = true;
    public boolean dither = true;
    /** Output folder; empty uses Kinora's renders folder. */
    public String outputFolder = "";
    /** File name without extension; {@code {project}}, {@code {shot}} and {@code {date}} are replaced. */
    public String fileName = "{project}_{shot}";

    /** Tiles per axis actually used (1 when not tiled). */
    public int tileCount() {
        return projection == Projection.NORMAL && tiles > 1 ? tiles : 1;
    }

    /** True when the sky is cut out into transparency (needs an output with alpha). */
    public boolean alpha() {
        return transparentSky || passes.contains(Pass.ALPHA);
    }

    /** True when a depth pass is written next to the output. */
    public boolean depthPass() {
        return passes.contains(Pass.DEPTH);
    }

    /** True for outputs that keep 16 bits per channel through mixing (motion blur, dissolves). */
    public boolean deep() {
        return output == Output.PNG16_SEQUENCE || output == Output.EXR_SEQUENCE;
    }

    /** True if the video pixel format stores alpha (yuva*, rgba, gbrap, ...). */
    public boolean pixelFormatHasAlpha() {
        return pixelFormat.matches("^(yuva|gbrap|rgba|bgra|argb|abgr|ya).*");
    }

    public double fps() {
        return fpsNumerator / (double) fpsDenominator;
    }

    public RenderSettings copy() {
        RenderSettings r = new RenderSettings();
        r.name = name;
        r.width = width;
        r.height = height;
        r.fpsNumerator = fpsNumerator;
        r.fpsDenominator = fpsDenominator;
        r.motionBlurSamples = motionBlurSamples;
        r.shutterAngle = shutterAngle;
        r.supersampling = supersampling;
        r.tiles = tiles;
        r.projection = projection;
        r.stereoEyeDistance = stereoEyeDistance;
        r.output = output;
        r.videoCodec = videoCodec;
        r.quality = quality;
        r.container = container;
        r.pixelFormat = pixelFormat;
        r.extraArgs = new ArrayList<>(extraArgs);
        r.audio = audio;
        r.audioBitrateKbps = audioBitrateKbps;
        r.musicPath = musicPath;
        r.musicVolume = musicVolume;
        r.gameVolume = gameVolume;
        r.pitchMode = pitchMode;
        r.passes = new ArrayList<>(passes);
        r.transparentSky = transparentSky;
        r.chromaKey = chromaKey;
        r.hideHud = hideHud;
        r.dither = dither;
        r.outputFolder = outputFolder;
        r.fileName = fileName;
        return r;
    }

    /** Problems that make these settings unusable, in plain words; empty if fine. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (width < 16 || height < 16) {
            problems.add("The frame must be at least 16 x 16 pixels.");
        }
        if (width > 65536 || height > 65536) {
            problems.add("Frames larger than 65536 pixels on a side are not supported.");
        }
        if (fpsNumerator <= 0 || fpsDenominator <= 0) {
            problems.add("The frame rate must be positive.");
        }
        if (motionBlurSamples < 1 || motionBlurSamples > 256) {
            problems.add("Motion blur samples must be between 1 and 256.");
        }
        if (shutterAngle <= 0 || shutterAngle > 360) {
            problems.add("The shutter angle must be between 0 and 360 degrees.");
        }
        if (supersampling < 1 || supersampling > 4) {
            problems.add("Supersampling must be 1x to 4x.");
        }
        if (passes.contains(Pass.ENTITY_ID) || passes.contains(Pass.NORMAL)) {
            problems.add("Entity id and normal passes are not available yet; depth and alpha are.");
        }
        if ((long) width * height > 500_000_000L) {
            problems.add("Frames above 500 million pixels (about 22000 x 22000) are not supported.");
        }
        if (tiles > 8) {
            problems.add("At most 8 x 8 tiles.");
        }
        if (depthPass() && tileCount() > 1) {
            problems.add("The depth pass does not work with tiled renders.");
        }
        if (depthPass() && projection != Projection.NORMAL) {
            problems.add("The depth pass works with the normal view only, not 360 or stereo.");
        }
        if (alpha() && output == Output.VIDEO && !pixelFormatHasAlpha()) {
            problems.add("A transparent sky needs a format with alpha: an image sequence, or video as ProRes 4444 (yuva444p10le), "
                    + "FFV1 (rgba) or VP9 (yuva420p).");
        }
        if (output == Output.VIDEO && (width % 2 != 0 || height % 2 != 0) && pixelFormat.startsWith("yuv420")) {
            problems.add("4:2:0 video needs an even width and height.");
        }
        return problems;
    }
}
