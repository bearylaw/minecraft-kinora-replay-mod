package dev.kinora.core.audio;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a render heard and where its camera was: one JSON object per line, appended while
 * rendering, so a stopped render resumes with the sounds of the part already rendered.
 *
 * <p>Lines are {@code {"cue": SoundCue}}, {@code {"loop": LoopSample}} or {@code {"frame": n, "x", "y", "z", "yaw"}}.
 */
public final class AudioLog implements AutoCloseable {
    private static final Gson GSON = new Gson();

    private final Path file;
    private final Set<String> seen = new HashSet<>();
    private final List<SoundCue> cues = new ArrayList<>();
    private final Set<String> seenLoops = new HashSet<>();
    private final List<LoopSample> loops = new ArrayList<>();
    private final TreeMap<Integer, double[]> listener = new TreeMap<>();
    private BufferedWriter writer;

    private AudioLog(Path file) {
        this.file = file;
    }

    /** Opens a log, reading what an earlier run of the same render wrote. */
    public static AudioLog open(Path file) throws IOException {
        AudioLog log = new AudioLog(file);
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    if (o.has("cue")) {
                        SoundCue cue = GSON.fromJson(o.get("cue"), SoundCue.class);
                        if (log.seen.add(key(cue))) {
                            log.cues.add(cue);
                        }
                    } else if (o.has("loop")) {
                        LoopSample s = GSON.fromJson(o.get("loop"), LoopSample.class);
                        if (log.seenLoops.add(key(s))) {
                            log.loops.add(s);
                        }
                    } else if (o.has("frame")) {
                        log.listener.put(o.get("frame").getAsInt(), new double[] {o.get("x").getAsDouble(), o.get("y").getAsDouble(),
                                o.get("z").getAsDouble(), o.get("yaw").getAsDouble()});
                    }
                } catch (RuntimeException e) {
                    // A line cut short by a crash: skip it.
                }
            }
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        log.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return log;
    }

    /** Same tick, file and place: the same sound (a resumed render replays some ticks). */
    private static String key(SoundCue c) {
        return Math.round(c.replayTicks() * 1000) + "|" + c.sound() + "|" + Math.round(c.x() * 100) + "|" + Math.round(c.y() * 100) + "|"
                + Math.round(c.z() * 100);
    }

    /** Same tick, file and place: the same sound heard again (a resumed render, or a rebuilt world). */
    private static String key(LoopSample s) {
        return Math.round(s.replayTicks() * 1000) + "|" + s.sound() + "|" + Math.round(s.x() * 100) + "|" + Math.round(s.y() * 100) + "|"
                + Math.round(s.z() * 100);
    }

    public synchronized void loop(LoopSample sample) {
        if (!seenLoops.add(key(sample))) {
            return;
        }
        loops.add(sample);
        JsonObject o = new JsonObject();
        o.add("loop", GSON.toJsonTree(sample));
        write(o);
    }

    public synchronized List<LoopSample> loops() {
        return List.copyOf(loops);
    }

    public synchronized void cue(SoundCue cue) {
        if (!seen.add(key(cue))) {
            return;
        }
        cues.add(cue);
        JsonObject o = new JsonObject();
        o.add("cue", GSON.toJsonTree(cue));
        write(o);
    }

    public synchronized void frame(int frame, double x, double y, double z, double yaw) {
        if (listener.containsKey(frame)) {
            return;
        }
        listener.put(frame, new double[] {x, y, z, yaw});
        JsonObject o = new JsonObject();
        o.addProperty("frame", frame);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("yaw", yaw);
        write(o);
    }

    private void write(JsonObject o) {
        try {
            writer.write(GSON.toJson(o));
            writer.newLine();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public synchronized void flush() {
        try {
            writer.flush();
        } catch (IOException ignored) {
        }
    }

    public synchronized List<SoundCue> cues() {
        return List.copyOf(cues);
    }

    /** The camera at an output time, interpolated between frames (position) or held (yaw). */
    public synchronized AudioMixer.Listener listener(double frameSeconds) {
        TreeMap<Integer, double[]> copy = new TreeMap<>(listener);
        return t -> {
            if (copy.isEmpty()) {
                return new double[] {0, 0, 0, 0};
            }
            double f = t / frameSeconds;
            var below = copy.floorEntry((int) Math.floor(f));
            var above = copy.ceilingEntry((int) Math.ceil(f));
            if (below == null) {
                return above.getValue();
            }
            if (above == null || above.getKey().equals(below.getKey())) {
                return below.getValue();
            }
            double s = (f - below.getKey()) / (above.getKey() - below.getKey());
            double[] a = below.getValue();
            double[] b = above.getValue();
            return new double[] {a[0] + (b[0] - a[0]) * s, a[1] + (b[1] - a[1]) * s, a[2] + (b[2] - a[2]) * s, a[3]};
        };
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }
}
