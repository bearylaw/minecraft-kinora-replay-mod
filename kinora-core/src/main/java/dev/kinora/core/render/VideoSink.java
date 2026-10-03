package dev.kinora.core.render;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pipes frames into FFmpeg. Each run of a render writes one NUT part; {@link #finish} joins
 * all parts (from this run and earlier, interrupted ones) into the output file without re-encoding.
 */
public final class VideoSink implements FrameSink {
    /** A finished part from this or an earlier run. */
    public record Part(Path file, int frames) {}

    private final Path ffmpeg;
    private final RenderSettings settings;
    private final Path output;
    private final List<Part> parts;
    private final Path partFile;
    private final Deque<String> errors = new ArrayDeque<>();
    private Process process;
    private OutputStream stdin;
    private Thread errorReader;
    private int framesThisPart;

    /**
     * @param previousParts parts finished by earlier runs of this render, in order
     */
    public VideoSink(Path ffmpeg, RenderSettings settings, Path output, List<Part> previousParts) {
        this.ffmpeg = ffmpeg;
        this.settings = settings;
        this.output = output;
        this.parts = new ArrayList<>(previousParts);
        String name = output.getFileName().toString();
        // NUT keeps exact frame timestamps (Matroska rounds them to milliseconds) and stays readable
        // if the game stops mid-render.
        this.partFile = output.resolveSibling(name + ".part" + parts.size() + ".nut");
    }

    /** All finished parts, including this run's once it has stopped. */
    public List<Part> parts() {
        return List.copyOf(parts);
    }

    @Override
    public void begin(int width, int height, int firstFrame) throws IOException {
        Files.createDirectories(partFile.getParent());
        List<String> cmd = Ffmpeg.encodeCommand(ffmpeg, settings, width, height, partFile);
        process = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        stdin = process.getOutputStream();
        errorReader = Thread.ofPlatform().daemon().name("Kinora FFmpeg log").start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (errors) {
                        errors.addLast(line);
                        if (errors.size() > 40) {
                            errors.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // The process ended.
            }
        });
    }

    @Override
    public void write(int index, byte[] rgba) throws IOException {
        if (settings.alpha()) {
            // Frames with a cut-out sky are premultiplied; video stores straight alpha.
            Pixels.unpremultiply8(rgba);
        }
        try {
            stdin.write(rgba);
        } catch (IOException e) {
            throw new IOException("FFmpeg stopped accepting frames: " + errorText(), e);
        }
        framesThisPart++;
    }

    @Override
    public void finish() throws IOException {
        endPart();
        List<Path> files = parts.stream().map(Part::file).toList();
        Path list = output.resolveSibling(output.getFileName() + ".parts.txt");
        Files.writeString(list, Ffmpeg.concatList(files));
        run(Ffmpeg.concatCommand(ffmpeg, list, output, settings));
        Files.deleteIfExists(list);
        for (Path f : files) {
            Files.deleteIfExists(f);
        }
        parts.clear();
    }

    /** Stops this run's part cleanly, so a later run can resume after it. */
    @Override
    public void close() {
        try {
            endPart();
        } catch (IOException ignored) {
            // The part is incomplete; it is left out of the list and re-rendered next time.
        }
    }

    private void endPart() throws IOException {
        if (process == null) {
            return;
        }
        Process p = process;
        process = null;
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("FFmpeg did not finish");
            }
            if (errorReader != null) {
                errorReader.join(2000);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while FFmpeg finished", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("FFmpeg failed (exit " + p.exitValue() + "): " + errorText());
        }
        if (framesThisPart > 0) {
            parts.add(new Part(partFile, framesThisPart));
        } else {
            Files.deleteIfExists(partFile);
        }
    }

    private void run(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (p.waitFor() != 0) {
                throw new IOException("FFmpeg could not join the video parts: " + out.strip());
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private String errorText() {
        synchronized (errors) {
            return errors.isEmpty() ? "(no output)" : String.join(" | ", errors);
        }
    }
}
