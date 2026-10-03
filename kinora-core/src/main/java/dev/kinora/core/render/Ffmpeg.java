package dev.kinora.core.render;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finding FFmpeg and building its command lines. Kinora does not ship FFmpeg; it uses the one the
 * user points to, one in Kinora's tools folder, or one on the PATH.
 */
public final class Ffmpeg {
    private Ffmpeg() {}

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * The FFmpeg executable to use, or null if none is found.
     *
     * @param configured a path from the settings; empty to search
     * @param toolsDir   Kinora's tools folder, searched before the PATH
     */
    public static Path locate(String configured, Path toolsDir) {
        String exe = windows() ? "ffmpeg.exe" : "ffmpeg";
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.strip());
            candidates.add(Files.isDirectory(p) ? p.resolve(exe) : p);
        }
        if (toolsDir != null) {
            candidates.add(toolsDir.resolve(exe));
            candidates.add(toolsDir.resolve("bin").resolve(exe));
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    try {
                        candidates.add(Path.of(dir.strip()).resolve(exe));
                    } catch (RuntimeException ignored) {
                        // A malformed PATH entry.
                    }
                }
            }
        }
        for (Path c : candidates) {
            if (Files.isRegularFile(c) && Files.isExecutable(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Encodes raw RGBA frames from standard input into {@code output}.
     *
     * <p>Kinora writes parts as NUT, which keeps exact timestamps and stays readable if the game stops; the
     * final file is made by {@link #concatCommand}.
     */
    public static List<String> encodeCommand(Path ffmpeg, RenderSettings s, int width, int height, Path output) {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg.toString(), "-y", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height,
                "-framerate", s.fpsNumerator + "/" + s.fpsDenominator, "-i", "-"));
        cmd.add("-c:v");
        cmd.add(s.videoCodec);
        cmd.addAll(qualityArgs(s));
        if (!s.pixelFormat.isBlank()) {
            cmd.add("-pix_fmt");
            cmd.add(s.pixelFormat);
        }
        // Tag the colour space the game renders in, so players do not guess.
        cmd.addAll(List.of("-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709"));
        cmd.addAll(s.extraArgs);
        cmd.add(output.toString());
        return cmd;
    }

    /** Joins parts (or rewraps a single one) into the final container without re-encoding. */
    public static List<String> concatCommand(Path ffmpeg, Path listFile, Path output, RenderSettings s) {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg.toString(), "-y", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-f", "concat", "-safe", "0", "-i", listFile.toString(), "-c", "copy"));
        if (s.container.equals("mp4") || s.container.equals("mov")) {
            // A timescale of the frame rate's numerator gives every frame an exact timestamp.
            cmd.addAll(List.of("-video_track_timescale", Integer.toString(s.fpsNumerator), "-movflags", "+faststart"));
        }
        cmd.add(output.toString());
        return cmd;
    }

    /** Video encoder names this FFmpeg build has ({@code ffmpeg -encoders}); empty if it cannot be run. */
    public static java.util.Set<String> encoders(Path ffmpeg) {
        java.util.Set<String> names = new java.util.HashSet<>();
        try {
            Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-encoders").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            names.addAll(parseEncoders(out));
        } catch (java.io.IOException e) {
            // Not runnable: treated as having no encoders.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return names;
    }

    /** GPU video encoders, in the order they are offered. */
    public static final List<String> HARDWARE_CODECS = List.of("h264_nvenc", "hevc_nvenc", "h264_amf", "hevc_amf", "h264_qsv", "hevc_qsv");

    /**
     * True if the codec can actually encode here. FFmpeg builds list GPU encoders whether or not the
     * machine has that GPU, so this encodes one small frame to find out.
     */
    public static boolean canEncode(Path ffmpeg, String codec) {
        try {
            Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi",
                    "-i", "color=c=gray:s=256x256:d=0.1", "-frames:v", "1", "-c:v", codec, "-f", "null", "-")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (java.io.IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Video encoder names from {@code ffmpeg -encoders} output (lines like " V....D libx264  ..."). */
    static java.util.Set<String> parseEncoders(String output) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String line : output.split("\\R")) {
            String[] cols = line.strip().split("\\s+");
            if (cols.length >= 2 && cols[0].length() == 6 && cols[0].charAt(0) == 'V' && !cols[1].equals("=")) {
                names.add(cols[1]);
            }
        }
        return names;
    }

    /**
     * Adds the game audio (and optional music) to a finished video without re-encoding the picture.
     *
     * @param music a music file of any format FFmpeg reads, or null
     */
    public static List<String> muxAudioCommand(Path ffmpeg, Path video, Path gameAudio, Path music, RenderSettings s, Path output) {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg.toString(), "-y", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-i", video.toString(), "-i", gameAudio.toString()));
        if (music != null) {
            cmd.addAll(List.of("-i", music.toString(), "-filter_complex",
                    "[2:a]volume=" + num(s.musicVolume) + ",apad[m];[1:a][m]amix=inputs=2:duration=first:normalize=0[a]",
                    "-map", "0:v", "-map", "[a]"));
        } else {
            cmd.addAll(List.of("-map", "0:v", "-map", "1:a"));
        }
        cmd.addAll(List.of("-c:v", "copy"));
        cmd.addAll(audioCodecArgs(s));
        if (s.container.equals("mp4") || s.container.equals("mov")) {
            cmd.addAll(List.of("-video_track_timescale", Integer.toString(s.fpsNumerator), "-movflags", "+faststart"));
        }
        cmd.add(output.toString());
        return cmd;
    }

    /** An audio codec that suits the container: AAC for MP4, Opus for WebM, PCM for ProRes MOV, FLAC for MKV. */
    static List<String> audioCodecArgs(RenderSettings s) {
        String kbps = Math.max(64, s.audioBitrateKbps) + "k";
        return switch (s.container) {
            case "webm" -> List.of("-c:a", "libopus", "-b:a", kbps);
            case "mkv" -> List.of("-c:a", "flac");
            case "mov" -> s.videoCodec.equals("prores_ks") || s.videoCodec.equals("dnxhd") ? List.of("-c:a", "pcm_s24le")
                    : List.of("-c:a", "aac", "-b:a", kbps);
            default -> List.of("-c:a", "aac", "-b:a", kbps);
        };
    }

    private static String num(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    /** The concat demuxer's list file for these parts. */
    public static String concatList(List<Path> parts) {
        StringBuilder sb = new StringBuilder();
        for (Path p : parts) {
            // The concat demuxer quotes with single quotes; a quote inside is written '\''.
            sb.append("file '").append(p.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''")).append("'\n");
        }
        return sb.toString();
    }

    /** What {@link RenderSettings#quality} means for each codec. */
    static List<String> qualityArgs(RenderSettings s) {
        String q = Integer.toString(s.quality);
        return switch (s.videoCodec) {
            // "medium": a good size/speed balance; "slow" made renders encoder-bound. extraArgs can override.
            case "libx264", "libx265" -> List.of("-crf", q, "-preset", "medium");
            case "libsvtav1" -> List.of("-crf", q, "-preset", "6");
            case "libaom-av1" -> List.of("-crf", q, "-b:v", "0", "-cpu-used", "4");
            case "libvpx-vp9" -> List.of("-crf", q, "-b:v", "0", "-row-mt", "1");
            // p5 + hq + spatial AQ: ~0.996 SSIM against x264 CRF 18, in a third of the render time.
            case "h264_nvenc", "hevc_nvenc", "av1_nvenc" -> List.of("-rc", "vbr", "-cq", q, "-b:v", "0", "-preset", "p5", "-tune", "hq",
                    "-spatial-aq", "1");
            case "h264_amf", "hevc_amf" -> List.of("-rc", "cqp", "-qp_i", q, "-qp_p", q);
            case "h264_qsv", "hevc_qsv" -> List.of("-global_quality", q);
            // 0 proxy, 1 LT, 2 standard, 3 HQ, 4 4444, 5 4444 XQ.
            case "prores_ks" -> List.of("-profile:v", Integer.toString(Math.max(0, Math.min(5, s.quality))), "-vendor", "apl0");
            case "dnxhd" -> List.of("-profile:v", "dnxhr_hq");
            case "ffv1" -> List.of("-level", "3");
            default -> List.of();
        };
    }
}
