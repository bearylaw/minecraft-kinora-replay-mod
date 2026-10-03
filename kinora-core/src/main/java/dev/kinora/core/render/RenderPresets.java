package dev.kinora.core.render;

import java.util.ArrayList;
import java.util.List;

/** Ready-made render settings, shown first in the render dialog. */
public final class RenderPresets {
    private RenderPresets() {}

    public static RenderSettings defaultSettings() {
        return youtube(1920, 1080, 60, "YouTube 1080p60");
    }

    public static List<RenderSettings> all() {
        List<RenderSettings> list = new ArrayList<>();
        list.add(youtube(1920, 1080, 60, "YouTube 1080p60 (H.264)"));
        list.add(youtube(2560, 1440, 60, "YouTube 1440p60 (H.264)"));
        RenderSettings uhd = youtube(3840, 2160, 60, "YouTube 4K60 (H.265)");
        uhd.videoCodec = "libx265";
        uhd.quality = 20;
        uhd.container = "mp4";
        uhd.extraArgs = new ArrayList<>(List.of("-tag:v", "hvc1"));
        list.add(uhd);
        RenderSettings av1 = youtube(3840, 2160, 60, "4K60 (AV1)");
        av1.videoCodec = "libsvtav1";
        av1.quality = 28;
        list.add(av1);
        RenderSettings film = youtube(1920, 1080, 24, "Cinematic 1080p24, motion blur");
        film.motionBlurSamples = 16;
        film.shutterAngle = 180;
        list.add(film);
        RenderSettings prores = youtube(3840, 2160, 24, "Master 4K24 (ProRes 422 HQ)");
        prores.videoCodec = "prores_ks";
        prores.quality = 3;
        prores.container = "mov";
        prores.pixelFormat = "yuv422p10le";
        prores.motionBlurSamples = 16;
        list.add(prores);
        RenderSettings dnx = youtube(3840, 2160, 24, "Master 4K24 (DNxHR HQX)");
        dnx.videoCodec = "dnxhd";
        dnx.extraArgs = new ArrayList<>(List.of("-profile:v", "dnxhr_hqx"));
        dnx.container = "mov";
        dnx.pixelFormat = "yuv422p10le";
        list.add(dnx);
        RenderSettings lossless = youtube(1920, 1080, 60, "Lossless (FFV1)");
        lossless.videoCodec = "ffv1";
        lossless.container = "mkv";
        lossless.pixelFormat = "rgb24";
        list.add(lossless);
        RenderSettings small = youtube(1280, 720, 30, "Small web 720p30");
        small.quality = 26;
        small.audioBitrateKbps = 128;
        list.add(small);
        RenderSettings vertical = youtube(1080, 1920, 60, "Vertical 1080x1920 (Shorts, TikTok)");
        list.add(vertical);
        RenderSettings square = youtube(1080, 1080, 30, "Square 1080");
        list.add(square);
        RenderSettings gif = youtube(640, 360, 15, "GIF loop");
        gif.videoCodec = "gif";
        gif.container = "gif";
        gif.pixelFormat = "rgb8";
        gif.audio = false;
        list.add(gif);
        RenderSettings webm = youtube(1280, 720, 30, "WebM loop (VP9)");
        webm.videoCodec = "libvpx-vp9";
        webm.quality = 32;
        webm.container = "webm";
        webm.audio = false;
        list.add(webm);
        RenderSettings png = youtube(1920, 1080, 60, "PNG sequence");
        png.output = RenderSettings.Output.PNG_SEQUENCE;
        png.audio = false;
        list.add(png);
        RenderSettings png16 = youtube(1920, 1080, 60, "PNG 16-bit sequence");
        png16.output = RenderSettings.Output.PNG16_SEQUENCE;
        png16.audio = false;
        list.add(png16);
        RenderSettings exr = youtube(1920, 1080, 60, "OpenEXR sequence (linear float)");
        exr.output = RenderSettings.Output.EXR_SEQUENCE;
        exr.audio = false;
        list.add(exr);
        return list;
    }

    private static RenderSettings youtube(int width, int height, int fps, String name) {
        RenderSettings s = new RenderSettings();
        s.name = name;
        s.width = width;
        s.height = height;
        s.fpsNumerator = fps;
        s.fpsDenominator = 1;
        s.videoCodec = "libx264";
        s.quality = 18;
        s.container = "mp4";
        s.pixelFormat = "yuv420p";
        return s;
    }
}
