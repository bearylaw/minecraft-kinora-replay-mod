package dev.kinora.mc.dev;

import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.util.KinoraPaths;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.fml.loading.FMLEnvironment;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Development-only test driver: runs a small command script inside the game, so tests never send
 * keystrokes or clicks to the desktop.
 *
 * <p>Scripts come from {@code <game>/kinora/dev/script.txt}, which is polled once a second and
 * deleted when picked up, or from the file named by the {@code kinora.dev.script} system property at
 * launch. Input goes through the window's own GLFW callbacks, so it takes exactly the path real input
 * takes (NeoForge input events, key mappings, the open screen), without touching the operating
 * system. Progress goes to {@code dev/script.log}; {@code dev/script.done} appears when a script ends
 * and holds {@code PASS} or {@code FAIL <count>}. Screenshots go to {@code dev/screenshots/}.
 *
 * <p>Commands, one per line ({@code #} starts a comment; coordinates are GUI-scaled, or a percentage
 * of the window such as {@code 30%}):
 * <pre>
 * open &lt;file&gt;                      open a replay (relative to kinora/replays)
 * waitfor &lt;condition as for expect&gt; [seconds]
 * wait &lt;seconds&gt;
 * key &lt;combo&gt;                      press and release, e.g. space, ctrl+z, shift+left
 * down &lt;key&gt; / up &lt;key&gt;            hold and release a key
 * mousedown &lt;x&gt; &lt;y&gt; [button] / mouseup [button]
 * type &lt;text&gt;                      type characters
 * move &lt;x&gt; &lt;y&gt;                     move the cursor
 * click &lt;x&gt; &lt;y&gt; [left|right|middle] click at a position
 * button &lt;label text&gt;              click the widget whose label contains the text
 * cycle &lt;label&gt; until &lt;text&gt;         click a cycling button until its label shows the text
 * drag &lt;x1&gt; &lt;y1&gt; &lt;x2&gt; &lt;y2&gt; [button] press, move in steps, release
 * scroll &lt;x&gt; &lt;y&gt; &lt;amount&gt;
 * screenshot &lt;name&gt;
 * renderset {json}                 override render settings for later renders, e.g. {"projection":"EQUIRECTANGULAR"}
 * render shot|sequence png|video [w h fps [blur [codec container]]]   start a render; then "waitfor rendered"
 * expect replay|screen &lt;Name&gt;|noscreen|playing|paused|moved &lt;blocks&gt;
 * mark                             remember the camera position (for "moved")
 * state                            log screen, replay time, editor and camera
 * clip &lt;start&gt; &lt;end&gt;               export seconds start..end of the open replay to kinora/replays/script_clip.kinora
 * photo [width]                    a still of the current view (default 3840 wide); then "waitfor rendered"
 * seek &lt;seconds&gt;                   move replay time (seconds from the replay's start)
 * entities [file]                  write the entities now in the world to dev/entities.json
 * project load|export &lt;file&gt;       replace the project with a .kinoraproj file, or save it (dev/ relative)
 * shot &lt;name&gt;                       select a shot by name
 * camera &lt;x y z yaw pitch&gt;          place the free camera (editor: with the camera on Free)
 * property &lt;name&gt; [value]           set a system property (dev switches such as kinora.dev.renderTrace)
 * log &lt;text&gt;
 * quit
 * </pre>
 */
public final class DevScript {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long POLL_MS = 1000;

    private static boolean started;
    private static long lastPoll;
    private static Deque<String> lines;
    private static int lineNumber;
    private static long waitUntil;
    private static Waiter waiter;
    private static int failures;
    private static Path logFile;
    /** Mouse buttons held by {@code drag}, released on a later frame so screens see the move first. */
    private static Runnable pending;
    /** Camera position saved by {@code mark}, for {@code expect moved}. */
    private static dev.kinora.core.camera.CameraState mark;
    /** Keys a script holds down; code that polls key state asks {@link #held} as well as GLFW. */
    private static final java.util.Set<String> INPUT = java.util.Set.of("key", "down", "up", "type", "move", "click", "button",
            "scroll", "mousedown", "mouseup", "open");
    /** Render settings set by {@code renderset}, applied over the project's on every {@code render}. */
    private static final com.google.gson.JsonObject RENDER_OVERRIDES = new com.google.gson.JsonObject();
    private static final java.util.Set<Integer> HELD = new java.util.HashSet<>();

    private interface Waiter {
        boolean done();
    }

    private DevScript() {}

    /** True while a script holds this key down (GLFW's own key state cannot see scripted input). */
    public static boolean held(int key) {
        return !HELD.isEmpty() && HELD.contains(key);
    }

    public static boolean enabled() {
        return !FMLEnvironment.isProduction();
    }

    /** Called after every frame (client ticks stop while a replay is paused). */
    public static void tick() {
        if (!enabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!started) {
            // Other mods' loading warnings (e.g. deprecated metadata) stop at a screen; a test run
            // continues past warnings, never past errors.
            if (mc.gui.screen() instanceof net.neoforged.neoforge.client.gui.LoadingErrorScreen warnings) {
                String proceed = net.neoforged.fml.i18n.FMLTranslations.parseMessage("fml.button.continue.launch");
                for (var child : warnings.children()) {
                    if (child instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(proceed)) {
                        LOGGER.info("[kinora-dev] continuing past mod loading warnings");
                        b.onPress(new net.minecraft.client.input.KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                        return;
                    }
                }
            }
            // Wait for the title screen (or a joined world), so resources and the window callbacks are in place.
            if (!(mc.gui.screen() instanceof TitleScreen) && (mc.level == null || mc.player == null || mc.gui.screen() != null)) {
                return;
            }
            started = true;
            String startup = System.getProperty("kinora.dev.script");
            if (startup != null) {
                load(Path.of(startup), false);
            }
        }
        if (lines == null) {
            long now = System.currentTimeMillis();
            if (now - lastPoll >= POLL_MS) {
                lastPoll = now;
                Path script = dir().resolve("script.txt");
                if (Files.isRegularFile(script)) {
                    load(script, true);
                }
            }
            return;
        }
        step(mc);
    }

    private static Path dir() {
        Path dir = KinoraPaths.root().resolve("dev");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    private static void load(Path script, boolean consume) {
        try {
            List<String> all = Files.readAllLines(script, StandardCharsets.UTF_8);
            if (consume) {
                Files.delete(script);
            }
            Files.deleteIfExists(dir().resolve("script.done"));
            logFile = dir().resolve("script.log");
            Files.writeString(logFile, "");
            lines = new ArrayDeque<>(all);
            lineNumber = 0;
            failures = 0;
            waitUntil = 0;
            waiter = null;
            pending = null;
            log("script " + script.getFileName() + " (" + all.size() + " lines)");
            // A script's keys are meant for the editor, not for closing the first-run guide.
            dev.kinora.mc.ui.UiState.setFlag("editorGuideSeen", true);
        } catch (IOException e) {
            LOGGER.warn("[kinora-dev] cannot read {}", script, e);
        }
    }

    private static void step(Minecraft mc) {
        if (pending != null) {
            Runnable r = pending;
            pending = null;
            r.run();
            return;
        }
        if (System.currentTimeMillis() < waitUntil) {
            return;
        }
        if (waiter != null) {
            if (!waiter.done()) {
                return;
            }
            waiter = null;
            waitUntil = 0;
        }
        // Run commands until one has to wait for later frames.
        while (lines != null && !lines.isEmpty() && waiter == null && pending == null && System.currentTimeMillis() >= waitUntil) {
            String raw = lines.poll();
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            log("> " + line);
            try {
                run(mc, line);
                if (INPUT.contains(line.split("\\s+")[0].toLowerCase(Locale.ROOT)) && pending == null) {
                    // Window callbacks queue their work for the next frame; let it run first.
                    pending = () -> {};
                }
            } catch (RuntimeException e) {
                fail("line " + lineNumber + ": " + e);
            }
        }
        if (lines != null && lines.isEmpty() && waiter == null && pending == null && System.currentTimeMillis() >= waitUntil) {
            finish();
        }
    }

    private static void finish() {
        String result = failures == 0 ? "PASS" : "FAIL " + failures;
        log("done: " + result);
        lines = null;
        HELD.clear();
        try {
            Files.writeString(dir().resolve("script.done"), result + "\n");
        } catch (IOException e) {
            LOGGER.warn("[kinora-dev] cannot write result", e);
        }
    }

    private static void run(Minecraft mc, String line) {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String rest = line.substring(parts[0].length()).strip();
        switch (cmd) {
            case "open" -> {
                Path file = Path.of(rest);
                if (rest.equals("latest")) {
                    // The newest recording, e.g. one a previous script just made.
                    try (var files = Files.list(KinoraPaths.replays())) {
                        file = files.filter(f -> f.getFileName().toString().endsWith(".kinora"))
                                .max(java.util.Comparator.comparingLong(f -> f.toFile().lastModified())).orElseThrow();
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                } else if (!file.isAbsolute()) {
                    file = KinoraPaths.replays().resolve(rest);
                }
                ReplayManager.INSTANCE.open(file, TitleScreen::new);
            }
            case "wait" -> waitUntil = System.currentTimeMillis() + (long) (Double.parseDouble(parts[1]) * 1000);
            case "waitfor" -> {
                Condition condition = condition(parts, 1);
                double seconds = parts.length > condition.argCount + 1 ? Double.parseDouble(parts[condition.argCount + 1]) : 60;
                long deadline = System.currentTimeMillis() + (long) (seconds * 1000);
                boolean renderWait = parts[1].equalsIgnoreCase("rendered");
                waiter = () -> {
                    if (condition.test.done()) {
                        if (renderWait && dev.kinora.mc.render.RenderRunner.lastResult() != null
                                && dev.kinora.mc.render.RenderRunner.lastResult() != dev.kinora.core.render.RenderJob.State.DONE
                                && dev.kinora.mc.render.RenderRunner.lastResult() != dev.kinora.core.render.RenderJob.State.PAUSED) {
                            fail("the render ended " + dev.kinora.mc.render.RenderRunner.lastResult());
                        }
                        return true;
                    }
                    if (System.currentTimeMillis() > deadline) {
                        fail("timed out waiting for " + rest);
                        lines.clear();
                        return true;
                    }
                    return false;
                };
            }
            case "expect" -> {
                Condition condition = condition(parts, 1);
                if (!condition.test.done()) {
                    fail("expected " + rest + "; screen is " + screenName(mc.gui.screen()));
                }
            }
            case "key" -> {
                Combo combo = combo(parts[1]);
                key(mc, combo.key, GLFW.GLFW_PRESS, combo.mods);
                key(mc, combo.key, GLFW.GLFW_RELEASE, combo.mods);
            }
            case "down" -> {
                Combo combo = combo(parts[1]);
                HELD.add(combo.key);
                key(mc, combo.key, GLFW.GLFW_PRESS, combo.mods);
            }
            case "up" -> {
                Combo combo = combo(parts[1]);
                HELD.remove(combo.key);
                key(mc, combo.key, GLFW.GLFW_RELEASE, combo.mods);
            }
            case "mousedown" -> {
                move(mc, coord(parts[1], true), coord(parts[2], false));
                mouse(mc, button(parts.length > 3 ? parts[3] : "left"), GLFW.GLFW_PRESS);
            }
            case "mouseup" -> mouse(mc, button(parts.length > 1 ? parts[1] : "left"), GLFW.GLFW_RELEASE);
            case "type" -> {
                GLFWCharCallback callback = GLFW.glfwSetCharCallback(handle(mc), null);
                GLFW.glfwSetCharCallback(handle(mc), callback);
                rest.codePoints().forEach(cp -> callback.invoke(handle(mc), cp));
            }
            case "move" -> move(mc, coord(parts[1], true), coord(parts[2], false));
            case "click" -> {
                double x = coord(parts[1], true);
                double y = coord(parts[2], false);
                int button = button(parts.length > 3 ? parts[3] : "left");
                move(mc, x, y);
                mouse(mc, button, GLFW.GLFW_PRESS);
                mouse(mc, button, GLFW.GLFW_RELEASE);
            }
            case "drag" -> {
                double x1 = coord(parts[1], true);
                double y1 = coord(parts[2], false);
                double x2 = coord(parts[3], true);
                double y2 = coord(parts[4], false);
                int button = button(parts.length > 5 ? parts[5] : "left");
                move(mc, x1, y1);
                mouse(mc, button, GLFW.GLFW_PRESS);
                dragSteps(mc, x1, y1, x2, y2, button, 1, 8);
            }
            case "scroll" -> {
                move(mc, coord(parts[1], true), coord(parts[2], false));
                GLFWScrollCallback callback = GLFW.glfwSetScrollCallback(handle(mc), null);
                GLFW.glfwSetScrollCallback(handle(mc), callback);
                callback.invoke(handle(mc), 0, Double.parseDouble(parts[3]));
            }
            case "screenshot" -> {
                AtomicBoolean written = new AtomicBoolean();
                String name = rest.endsWith(".png") ? rest : rest + ".png";
                Screenshot.grab(dir().toFile(), name, mc.gameRenderer.mainRenderTarget(), 1, message -> {
                    log("screenshot " + name + ": " + message.getString());
                    written.set(true);
                });
                long deadline = System.currentTimeMillis() + 10_000;
                waiter = () -> written.get() || System.currentTimeMillis() > deadline;
            }
            case "render" -> render(parts);
            case "photo" -> dev.kinora.mc.render.PhotoMode.take(parts.length > 1 ? Integer.parseInt(parts[1]) : 3840);
            case "clip" -> {
                // clip START END: seconds from the replay's start; the clip goes to kinora/replays.
                var session = ReplayManager.INSTANCE.session();
                long base = session.startTick();
                Path out = KinoraPaths.replays().resolve("script_clip.kinora");
                try {
                    var r = dev.kinora.core.format.ClipExporter.export(session.file(), base + Math.round(Double.parseDouble(parts[1]) * 20),
                            base + Math.round(Double.parseDouble(parts[2]) * 20), out);
                    log("# clip " + out.getFileName() + ": " + r.records() + " records, " + r.bytes() + " bytes");
                } catch (IOException e) {
                    fail("clip: " + e.getMessage());
                }
            }
            case "renderset" -> {
                // renderset {"projection": "EQUIRECTANGULAR", "audio": false}: applies to the following renders.
                var patch = com.google.gson.JsonParser.parseString(rest).getAsJsonObject();
                patch.entrySet().forEach(e -> RENDER_OVERRIDES.add(e.getKey(), e.getValue()));
                log("# render settings now override " + RENDER_OVERRIDES);
            }
            case "seek" -> {
                var session = ReplayManager.INSTANCE.session();
                if (session == null) {
                    fail("seek: no replay open");
                } else {
                    session.seek(session.startTick() + Double.parseDouble(parts[1]) * 20);
                }
            }
            case "entities" -> entities(mc, parts.length > 1 ? rest : "entities.json");
            case "project" -> project(parts, rest);
            case "shot" -> {
                var editor = ReplayManager.INSTANCE.editor();
                var shot = editor.project().shots.stream().filter(sh -> sh.name.equalsIgnoreCase(rest)).findFirst().orElse(null);
                if (shot == null) {
                    fail("no shot named " + rest);
                } else {
                    editor.select(shot);
                }
            }
            case "camera" -> {
                // Placing the camera means flying freely: the editor stops showing the shot's camera.
                ReplayManager.INSTANCE.editor().setPreviewCamera(false);
                placeCamera(parts);
            }
            case "property" -> System.setProperty(parts[1], parts.length > 2 ? parts[2] : "true");
            case "cycle" -> {
                // cycle <label text> until <text>: clicks a cycling button until its label shows the text.
                int until = rest.toLowerCase(Locale.ROOT).indexOf(" until ");
                String label = rest.substring(0, until).strip();
                String target = rest.substring(until + 7).strip().toLowerCase(Locale.ROOT);
                cycle(mc, label, target, 20);
            }
            case "button" -> {
                var widget = findButton(mc, rest);
                if (widget == null) {
                    fail("no button labelled \"" + rest + "\" on " + screenName(mc.gui.screen()));
                } else {
                    move(mc, widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
                    mouse(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS);
                    mouse(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE);
                }
            }
            case "log" -> log("# " + rest);
            case "samerenders" -> sameRenders(rest.isBlank() ? 0 : Integer.parseInt(rest.strip()));
            case "keymapping" -> {
                // keymapping <name> silent|active: after a key press, whether that mapping saw it.
                var mapping = java.util.Arrays.stream(mc.options.keyMappings).filter(k -> k.getName().equals(parts[1])).findFirst().orElse(null);
                if (mapping == null) {
                    fail("no key mapping " + parts[1]);
                } else {
                    boolean active = mapping.consumeClick() || mapping.isDown();
                    if (active != parts[2].equals("active")) {
                        fail(parts[1] + " was " + (active ? "active" : "silent") + ", expected " + parts[2]);
                    } else {
                        log("# " + parts[1] + " " + parts[2]);
                    }
                }
            }
            case "state" -> log("# " + state(mc));
            case "mark" -> mark = dev.kinora.mc.hooks.CameraHooks.currentFrame();
            case "quit" -> {
                finish();
                mc.stop();
            }
            default -> fail("unknown command: " + cmd);
        }
    }

    /** Moves in steps on successive frames, then releases, so the screen sees a real drag. */
    private static void dragSteps(Minecraft mc, double x1, double y1, double x2, double y2, int button, int step, int steps) {
        pending = () -> {
            double t = step / (double) steps;
            move(mc, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
            if (step < steps) {
                dragSteps(mc, x1, y1, x2, y2, button, step + 1, steps);
            } else {
                pending = () -> mouse(mc, button, GLFW.GLFW_RELEASE);
            }
        };
    }

    private record Condition(Waiter test, int argCount) {}

    private static Condition condition(String[] parts, int at) {
        ReplayManager manager = ReplayManager.INSTANCE;
        Minecraft mc = Minecraft.getInstance();
        return switch (parts[at].toLowerCase(Locale.ROOT)) {
            case "replay" -> new Condition(() -> manager.active() && manager.session() != null && mc.level != null
                    && mc.player != null && !(mc.gui.screen() instanceof dev.kinora.mc.ui.ReplayLoadingScreen), 1);
            case "screen" -> new Condition(() -> screenName(mc.gui.screen()).equals(parts[at + 1]), 2);
            case "noscreen" -> new Condition(() -> mc.gui.screen() == null, 1);
            case "rendered" -> new Condition(() -> !dev.kinora.mc.render.RenderRunner.rendering(), 1);
            case "moved" -> new Condition(() -> {
                var now = dev.kinora.mc.hooks.CameraHooks.currentFrame();
                return mark != null && now != null && Math.sqrt(Math.pow(now.x() - mark.x(), 2) + Math.pow(now.y() - mark.y(), 2)
                        + Math.pow(now.z() - mark.z(), 2)) >= Double.parseDouble(parts[at + 1]);
            }, 2);
            case "playing" -> new Condition(() -> manager.session() != null && !manager.session().paused()
                    || manager.editor() != null && manager.editor().playing(), 1);
            case "paused" -> new Condition(() -> manager.session() != null && manager.session().paused()
                    && (manager.editor() == null || !manager.editor().playing()), 1);
            default -> throw new IllegalArgumentException("unknown condition " + parts[at]);
        };
    }

    /**
     * {@code render shot|sequence png|png16|exr|video [width height fps [blur [codec container]]]}: renders the selected shot
     * (or the edit) of the open replay's project with the project's settings, overridden as given.
     */
    private static void render(String[] parts) {
        var editor = ReplayManager.INSTANCE.editor();
        var project = editor.project();
        var settings = project.render.copy();
        if (RENDER_OVERRIDES.size() > 0) {
            var gson = new com.google.gson.Gson();
            var json = gson.toJsonTree(settings).getAsJsonObject();
            RENDER_OVERRIDES.entrySet().forEach(e -> json.add(e.getKey(), e.getValue()));
            settings = gson.fromJson(json, dev.kinora.core.render.RenderSettings.class);
        }
        settings.output = switch (parts[2]) {
            case "video" -> dev.kinora.core.render.RenderSettings.Output.VIDEO;
            case "png16" -> dev.kinora.core.render.RenderSettings.Output.PNG16_SEQUENCE;
            case "exr" -> dev.kinora.core.render.RenderSettings.Output.EXR_SEQUENCE;
            default -> dev.kinora.core.render.RenderSettings.Output.PNG_SEQUENCE;
        };
        if (parts.length > 5) {
            settings.width = Integer.parseInt(parts[3]);
            settings.height = Integer.parseInt(parts[4]);
            settings.fpsNumerator = Integer.parseInt(parts[5]);
            settings.fpsDenominator = 1;
        }
        if (parts.length > 6) {
            settings.motionBlurSamples = Integer.parseInt(parts[6]);
        }
        if (parts.length > 8) {
            settings.videoCodec = parts[7];
            settings.container = parts[8];
            if (!RENDER_OVERRIDES.has("pixelFormat")) {
                settings.pixelFormat = parts[7].equals("prores_ks") ? "yuv422p10le" : "yuv420p";
            }
            if (!RENDER_OVERRIDES.has("quality")) {
                settings.quality = parts[7].equals("prores_ks") ? 3 : 20;
            }
        }
        var shot = parts[1].equals("sequence") ? null : editor.shot();
        if (shot == null && !parts[1].equals("sequence")) {
            fail("render: no shot selected");
            return;
        }
        var job = dev.kinora.mc.render.RenderJobs.create(project, shot, settings);
        RENDER_OUTPUTS.add(job.output);
        log("# render " + job.title + " -> " + job.output + " (" + job.frameCount + " frames)");
        if (!dev.kinora.mc.render.RenderJobs.startNow(job)) {
            fail("render did not start");
        }
    }

    /** Clicks one step per frame, so the screen rebuilds its widgets in between. */
    private static void cycle(Minecraft mc, String label, String target, int triesLeft) {
        var widget = findButton(mc, label);
        if (widget == null) {
            fail("no button labelled \"" + label + "\"");
            return;
        }
        if (widget.getMessage().getString().toLowerCase(Locale.ROOT).contains(target)) {
            return;
        }
        if (triesLeft == 0) {
            fail("\"" + label + "\" never showed \"" + target + "\"");
            return;
        }
        move(mc, widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
        mouse(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS);
        mouse(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE);
        pending = () -> cycle(mc, label, target, triesLeft - 1);
    }

    /** The first visible, active widget on the open screen whose label contains {@code text} (any case). */
    private static net.minecraft.client.gui.components.@org.jspecify.annotations.Nullable AbstractWidget findButton(Minecraft mc, String text) {
        Screen screen = mc.gui.screen();
        if (screen == null) {
            return null;
        }
        String wanted = text.toLowerCase(Locale.ROOT);
        // An exact label wins over one that merely contains the text ("Queue" vs "Add to queue").
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible && w.active
                    && w.getMessage().getString().equalsIgnoreCase(text)) {
                return w;
            }
        }
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible && w.active
                    && w.getMessage().getString().toLowerCase(Locale.ROOT).contains(wanted)) {
                return w;
            }
        }
        return null;
    }

    /**
     * Writes the entities at the current replay time to {@code dev/<file>}: id (what follow, orbit
     * and look-at targets use), type, name, position and yaw. The recorded player is marked.
     */
    private static void entities(Minecraft mc, String file) {
        var session = ReplayManager.INSTANCE.session();
        if (mc.level == null || session == null) {
            fail("entities: no replay open");
            return;
        }
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("replaySeconds", (session.clock().time() - session.startTick()) / 20.0);
        root.addProperty("replayTicks", session.clock().time());
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        for (var e : mc.level.entitiesForRendering()) {
            if (e == mc.player) {
                continue;
            }
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("id", e.getId());
            o.addProperty("type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            o.addProperty("name", e.getName().getString());
            if (e.getId() == session.recordedPlayerId() || session.puppet() == e) {
                o.addProperty("recordedPlayer", true);
            }
            o.addProperty("x", Math.round(e.getX() * 100) / 100.0);
            o.addProperty("y", Math.round(e.getY() * 100) / 100.0);
            o.addProperty("z", Math.round(e.getZ() * 100) / 100.0);
            o.addProperty("yaw", Math.round(e.getYRot() * 10) / 10.0);
            list.add(o);
        }
        root.add("entities", list);
        try {
            Files.writeString(dir().resolve(file), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));
            log("# " + list.size() + " entities -> dev/" + file);
        } catch (IOException e) {
            fail("entities: " + e.getMessage());
        }
    }

    /** {@code project load <file>} replaces the open replay's project; {@code project export <file>} writes it out. */
    private static void project(String[] parts, String rest) {
        var editor = ReplayManager.INSTANCE.editor();
        String path = rest.substring(parts[1].length()).strip();
        Path file = Path.of(path);
        if (!file.isAbsolute()) {
            file = dir().resolve(path);
        }
        try {
            switch (parts[1]) {
                case "load" -> {
                    editor.projects().replace(dev.kinora.core.project.ProjectIO.load(file));
                    var shots = editor.project().shots;
                    editor.select(shots.isEmpty() ? null : shots.getFirst());
                    log("# loaded " + shots.size() + " shot(s) from " + file.getFileName());
                }
                case "export" -> {
                    dev.kinora.core.project.ProjectIO.save(editor.project(), file);
                    log("# project -> " + file);
                }
                default -> fail("project load|export <file>");
            }
        } catch (IOException | RuntimeException e) {
            fail("project " + parts[1] + ": " + e.getMessage());
        }
    }

    private static void placeCamera(String[] parts) {
        ReplayManager.INSTANCE.camera().placeAt(new dev.kinora.core.camera.CameraState(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]), Double.parseDouble(parts[4]), Double.parseDouble(parts[5]), 0, Double.NaN));
    }

    /** One line of what a test usually wants to know: screen, replay time, editor and camera. */
    private static String state(Minecraft mc) {
        ReplayManager manager = ReplayManager.INSTANCE;
        StringBuilder sb = new StringBuilder("screen=").append(screenName(mc.gui.screen()));
        var session = manager.session();
        if (session != null) {
            sb.append(String.format(Locale.ROOT, " time=%.2f paused=%s", session.clock().time() / 20.0, session.paused()));
            var editor = manager.editor();
            var shot = editor.shot();
            sb.append(" shot=").append(shot == null ? "none" : shot.name)
                    .append(String.format(Locale.ROOT, " playhead=%.2f playing=%s preview=%s", editor.playhead(), editor.playing(),
                            editor.previewCamera()));
            sb.append(" camera=").append(manager.camera().mode());
            var c = dev.kinora.mc.hooks.CameraHooks.currentFrame();
            if (c != null) {
                sb.append(String.format(Locale.ROOT, " pos=%.2f,%.2f,%.2f yaw=%.1f pitch=%.1f", c.x(), c.y(), c.z(), c.yaw(), c.pitch()));
            }
        }
        return sb.toString();
    }

    private static String screenName(Screen screen) {
        return screen == null ? "none" : screen.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ input

    private static long handle(Minecraft mc) {
        return mc.getWindow().handle();
    }

    private static void key(Minecraft mc, int key, int action, int mods) {
        GLFWKeyCallback callback = GLFW.glfwSetKeyCallback(handle(mc), null);
        GLFW.glfwSetKeyCallback(handle(mc), callback);
        callback.invoke(handle(mc), key, GLFW.glfwGetKeyScancode(key), action, mods);
    }

    private static void mouse(Minecraft mc, int button, int action) {
        GLFWMouseButtonCallback callback = GLFW.glfwSetMouseButtonCallback(handle(mc), null);
        GLFW.glfwSetMouseButtonCallback(handle(mc), callback);
        callback.invoke(handle(mc), button, action, 0);
    }

    /** Moves the cursor to a GUI-scaled position. */
    private static void move(Minecraft mc, double guiX, double guiY) {
        var window = mc.getWindow();
        double x = guiX * window.getScreenWidth() / window.getGuiScaledWidth();
        double y = guiY * window.getScreenHeight() / window.getGuiScaledHeight();
        GLFWCursorPosCallback callback = GLFW.glfwSetCursorPosCallback(handle(mc), null);
        GLFW.glfwSetCursorPosCallback(handle(mc), callback);
        callback.invoke(handle(mc), x, y);
    }

    /** A GUI-scaled coordinate, or a percentage of the GUI width or height ("30%"). */
    private static double coord(String value, boolean horizontal) {
        if (value.endsWith("%")) {
            var window = Minecraft.getInstance().getWindow();
            double size = horizontal ? window.getGuiScaledWidth() : window.getGuiScaledHeight();
            return Double.parseDouble(value.substring(0, value.length() - 1)) / 100 * size;
        }
        return Double.parseDouble(value);
    }

    private static int button(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "left" -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
            case "right" -> GLFW.GLFW_MOUSE_BUTTON_RIGHT;
            case "middle" -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
            default -> throw new IllegalArgumentException("unknown mouse button " + name);
        };
    }

    private record Combo(int key, int mods) {}

    private static final Map<String, Integer> KEYS = Map.ofEntries(
            Map.entry("space", GLFW.GLFW_KEY_SPACE), Map.entry("enter", GLFW.GLFW_KEY_ENTER),
            Map.entry("escape", GLFW.GLFW_KEY_ESCAPE), Map.entry("esc", GLFW.GLFW_KEY_ESCAPE),
            Map.entry("tab", GLFW.GLFW_KEY_TAB), Map.entry("backspace", GLFW.GLFW_KEY_BACKSPACE),
            Map.entry("shift", GLFW.GLFW_KEY_LEFT_SHIFT), Map.entry("ctrl", GLFW.GLFW_KEY_LEFT_CONTROL),
            Map.entry("delete", GLFW.GLFW_KEY_DELETE), Map.entry("insert", GLFW.GLFW_KEY_INSERT),
            Map.entry("left", GLFW.GLFW_KEY_LEFT), Map.entry("right", GLFW.GLFW_KEY_RIGHT),
            Map.entry("up", GLFW.GLFW_KEY_UP), Map.entry("down", GLFW.GLFW_KEY_DOWN),
            Map.entry("home", GLFW.GLFW_KEY_HOME), Map.entry("end", GLFW.GLFW_KEY_END),
            Map.entry("pageup", GLFW.GLFW_KEY_PAGE_UP), Map.entry("pagedown", GLFW.GLFW_KEY_PAGE_DOWN),
            Map.entry("comma", GLFW.GLFW_KEY_COMMA), Map.entry("period", GLFW.GLFW_KEY_PERIOD),
            Map.entry("lbracket", GLFW.GLFW_KEY_LEFT_BRACKET), Map.entry("rbracket", GLFW.GLFW_KEY_RIGHT_BRACKET),
            Map.entry("backslash", GLFW.GLFW_KEY_BACKSLASH), Map.entry("slash", GLFW.GLFW_KEY_SLASH),
            Map.entry("minus", GLFW.GLFW_KEY_MINUS), Map.entry("equal", GLFW.GLFW_KEY_EQUAL),
            Map.entry("lshift", GLFW.GLFW_KEY_LEFT_SHIFT), Map.entry("lctrl", GLFW.GLFW_KEY_LEFT_CONTROL),
            Map.entry("lalt", GLFW.GLFW_KEY_LEFT_ALT));

    private static Combo combo(String spec) {
        String[] tokens = spec.toLowerCase(Locale.ROOT).split("\\+");
        int mods = 0;
        for (String mod : Arrays.copyOf(tokens, tokens.length - 1)) {
            mods |= switch (mod) {
                case "ctrl" -> GLFW.GLFW_MOD_CONTROL;
                case "shift" -> GLFW.GLFW_MOD_SHIFT;
                case "alt" -> GLFW.GLFW_MOD_ALT;
                default -> throw new IllegalArgumentException("unknown modifier " + mod);
            };
        }
        return new Combo(keyCode(tokens[tokens.length - 1]), mods);
    }

    private static int keyCode(String name) {
        Integer named = KEYS.get(name);
        if (named != null) {
            return named;
        }
        if (name.length() == 1) {
            char c = name.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return GLFW.GLFW_KEY_A + (c - 'a');
            }
            if (c >= '0' && c <= '9') {
                return GLFW.GLFW_KEY_0 + (c - '0');
            }
        }
        if (name.matches("f([1-9]|1[0-2])")) {
            return GLFW.GLFW_KEY_F1 + Integer.parseInt(name.substring(1)) - 1;
        }
        throw new IllegalArgumentException("unknown key " + name);
    }

    // ------------------------------------------------------------------ results

    /** Outputs of the {@code render} commands so far, for {@code samerenders}. */
    private static final java.util.List<String> RENDER_OUTPUTS = new java.util.ArrayList<>();

    /**
     * {@code samerenders [pixels]}: the last two renders must be the same, file by file. Image files
     * that differ are compared by pixel: up to {@code pixels} differing pixels per frame pass.
     */
    private static void sameRenders(int allowedPixels) {
        if (RENDER_OUTPUTS.size() < 2) {
            fail("samerenders: needs two renders");
            return;
        }
        Path a = Path.of(RENDER_OUTPUTS.get(RENDER_OUTPUTS.size() - 2));
        Path b = Path.of(RENDER_OUTPUTS.get(RENDER_OUTPUTS.size() - 1));
        try {
            java.util.List<Path> filesA;
            java.util.List<Path> filesB;
            if (Files.isDirectory(a)) {
                try (var sa = Files.list(a); var sb = Files.list(b)) {
                    filesA = sa.sorted().toList();
                    filesB = sb.sorted().toList();
                }
            } else {
                filesA = java.util.List.of(a);
                filesB = java.util.List.of(b);
            }
            if (filesA.size() != filesB.size()) {
                fail("samerenders: " + filesA.size() + " files against " + filesB.size());
                return;
            }
            int differ = 0;
            int worst = 0;
            String first = null;
            for (int i = 0; i < filesA.size(); i++) {
                if (Files.mismatch(filesA.get(i), filesB.get(i)) != -1) {
                    differ++;
                    if (first == null) {
                        first = filesA.get(i).getFileName().toString();
                    }
                    worst = Math.max(worst, differingPixels(filesA.get(i), filesB.get(i)));
                }
            }
            String what = differ + " of " + filesA.size() + " files differ, by at most " + worst + " pixels";
            if (differ == 0) {
                log("# samerenders: " + filesA.size() + " files identical");
            } else if (worst <= allowedPixels) {
                log("# samerenders: " + what + " (allowed: " + allowedPixels + "), first " + first);
            } else {
                fail("samerenders: " + what + ", first " + first);
            }
        } catch (java.io.IOException e) {
            fail("samerenders: " + e.getMessage());
        }
    }

    /** Pixels that differ between two images; files that are not images count as every pixel. */
    private static int differingPixels(Path a, Path b) throws java.io.IOException {
        var ia = javax.imageio.ImageIO.read(a.toFile());
        var ib = javax.imageio.ImageIO.read(b.toFile());
        if (ia == null || ib == null || ia.getWidth() != ib.getWidth() || ia.getHeight() != ib.getHeight()) {
            return Integer.MAX_VALUE;
        }
        int n = 0;
        for (int y = 0; y < ia.getHeight(); y++) {
            for (int x = 0; x < ia.getWidth(); x++) {
                if (ia.getRGB(x, y) != ib.getRGB(x, y)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void fail(String message) {
        failures++;
        log("FAIL " + message);
    }

    private static void log(String message) {
        LOGGER.info("[kinora-dev] {}", message);
        if (logFile != null) {
            try {
                Files.writeString(logFile, message + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }
}
