package dev.kinora.mc.ui;

import com.mojang.blaze3d.platform.InputConstants;

import dev.kinora.core.camera.CameraState;
import dev.kinora.core.camera.Quat;
import dev.kinora.core.camera.Vec3d;
import dev.kinora.core.format.Marker;
import dev.kinora.core.project.Project;
import dev.kinora.core.project.SequenceTimeline;
import dev.kinora.core.project.Shot;
import dev.kinora.core.project.ShotTemplates;
import dev.kinora.core.project.Tracks;
import dev.kinora.core.timeline.Easing;
import dev.kinora.core.timeline.Interpolation;
import dev.kinora.core.timeline.Keyframe;
import dev.kinora.core.timeline.Track;
import dev.kinora.mc.camera.CameraDirector;
import dev.kinora.mc.editor.EditorState;
import dev.kinora.mc.hooks.CameraHooks;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;
import dev.kinora.mc.ui.kit.Theme;
import dev.kinora.mc.ui.kit.Ui;
import dev.kinora.mc.util.Notify;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The camera editor. The world stays visible; the timeline of the selected shot sits at the
 * bottom with one row per track; the inspector on the right edits the shot or the selected keys.
 *
 * <p>Hold the right mouse button over the world to fly (mouse looks, WASD/Space/Shift move,
 * Ctrl is faster), then press I to keyframe the view at the playhead. Space previews the shot.
 */
public class EditorScreen extends Screen {
    private static final int TOP = 20;
    private static final int STRIP = 16;
    private static final int TRANSPORT = 18;
    private static final int RULER = 12;
    private static final int ROW = 14;
    private static final int LABEL_WIDTH = 64;
    private static final int INSPECTOR_WIDTH = 156;
    private static final List<String> ROW_TRACKS = List.of(Tracks.POSITION, Tracks.FOV, Tracks.ROLL, Tracks.TIME,
            Tracks.LOOK_AT_WEIGHT, Tracks.SHAKE, Tracks.ORBIT_ANGLE, Tracks.ORBIT_RADIUS, Tracks.ORBIT_HEIGHT, Tracks.DOF_APERTURE,
            Tracks.DOF_FOCUS, Tracks.EXPOSURE, Tracks.CONTRAST, Tracks.SATURATION, Tracks.TEMPERATURE, Tracks.TINT, Tracks.VIGNETTE,
            Tracks.GRAIN, Tracks.CHROMATIC, Tracks.BLOOM, Tracks.LETTERBOX, Tracks.WORLD_TIME, Tracks.WORLD_WEATHER);
    /** Tracks the "Add track" list offers, with a useful first value for each. */
    private static final java.util.LinkedHashMap<String, Double> ADDABLE = new java.util.LinkedHashMap<>();

    static {
        ADDABLE.put(Tracks.FOV, 70.0);
        ADDABLE.put(Tracks.ROLL, 0.0);
        ADDABLE.put(Tracks.DOF_APERTURE, 2.8);
        ADDABLE.put(Tracks.DOF_FOCUS, 10.0);
        ADDABLE.put(Tracks.EXPOSURE, 0.0);
        ADDABLE.put(Tracks.CONTRAST, 1.1);
        ADDABLE.put(Tracks.SATURATION, 1.1);
        ADDABLE.put(Tracks.TEMPERATURE, 0.2);
        ADDABLE.put(Tracks.TINT, 0.0);
        ADDABLE.put(Tracks.VIGNETTE, 0.4);
        ADDABLE.put(Tracks.GRAIN, 0.25);
        ADDABLE.put(Tracks.CHROMATIC, 2.0);
        ADDABLE.put(Tracks.BLOOM, 0.3);
        ADDABLE.put(Tracks.LETTERBOX, 2.39);
        ADDABLE.put(Tracks.WORLD_TIME, 6000.0);
        ADDABLE.put(Tracks.WORLD_WEATHER, 1.0);
        ADDABLE.put(Tracks.LOOK_AT_WEIGHT, 1.0);
        ADDABLE.put(Tracks.SHAKE, 1.0);
        ADDABLE.put(Tracks.ORBIT_RADIUS, 6.0);
        ADDABLE.put(Tracks.ORBIT_HEIGHT, 2.0);
    }

    private boolean addTrackOpen;
    private @Nullable CommandPalette palette;
    /** The first-run guide; shown once automatically, then from the ? button. */
    private boolean guideOpen = !UiState.flag("editorGuideSeen");
    private final List<Button> addTrackButtons = new ArrayList<>();

    private final ReplayManager manager = ReplayManager.INSTANCE;
    private final EditorState editor = manager.editor();

    private enum Drag { NONE, PLAYHEAD, KEYS, FLY, BOX }

    private Drag drag = Drag.NONE;
    private double dragStartX;
    private double dragStartY;
    private final List<double[]> dragOriginalTimes = new ArrayList<>();
    private boolean templatesOpen;
    private final List<Button> templateButtons = new ArrayList<>();
    private int seenRevision = -1;
    private final List<EditBox> fields = new ArrayList<>();
    private final List<Runnable> fieldCommits = new ArrayList<>();
    private @Nullable String pickMode;

    public EditorScreen() {
        super(Component.translatable("kinora.editor.title"));
    }

    // ------------------------------------------------------------------ layout

    private int timelineTop() {
        return this.height - (TRANSPORT + RULER + ROW * visibleRows().size() + 4);
    }

    private int laneLeft() {
        return LABEL_WIDTH;
    }

    private int laneRight() {
        return this.width - 8;
    }

    private int inspectorLeft() {
        return this.width - INSPECTOR_WIDTH - 4;
    }

    /** Rows shown at once: the timeline never takes more than about half the screen. */
    private int maxRows() {
        return Math.max(3, (this.height / 2 - TRANSPORT - RULER) / ROW);
    }

    private int rowScroll;

    /** The track rows on screen: all of them, or a scrolled window when there are many. */
    private List<String> visibleRows() {
        List<String> all = allRows();
        int max = maxRows();
        if (all.size() <= max) {
            rowScroll = 0;
            return all;
        }
        rowScroll = Math.max(0, Math.min(rowScroll, all.size() - max));
        return all.subList(rowScroll, rowScroll + max);
    }

    private List<String> allRows() {
        Shot shot = editor.shot();
        List<String> rows = new ArrayList<>();
        if (shot == null || editor.view() == EditorState.View.SEQUENCE) {
            return rows;
        }
        for (String id : ROW_TRACKS) {
            boolean always = id.equals(Tracks.POSITION) || id.equals(Tracks.FOV) || id.equals(Tracks.TIME);
            boolean orbit = id.equals(Tracks.ORBIT_ANGLE) && shot.rig.mode == Shot.RigMode.ORBIT;
            if (always || orbit || shot.existing(id) != null) {
                rows.add(id);
            }
        }
        return rows;
    }

    @Override
    protected void init() {
        if (editor.shot() == null && !editor.project().shots.isEmpty() && editor.view() == EditorState.View.SHOT) {
            // Open on a shot rather than an empty timeline when the project has any.
            editor.select(editor.project().shots.getFirst());
        }
        manager.setEditorActive(true);
        rebuild();
    }

    /** Right edge of the transport buttons, where the time readout starts. */
    private int transportEnd = 160;

    private void rebuild() {
        clearWidgets();
        fields.clear();
        fieldCommits.clear();
        templateButtons.clear();
        addTrackButtons.clear();
        seenRevision = editor.projects().revision();
        Project project = editor.project();
        Shot shot = editor.shot();

        // Top bar.
        int x = 4;
        addRenderableWidget(small(Component.translatable("kinora.editor.fly"), b -> onClose(), x, 2, 60, "kinora.editor.fly.tooltip"));
        x += 64;
        // Text labels: the font has no undo/redo/loop symbols that read at GUI size.
        Component undoLabel = Component.translatable("kinora.editor.undo.label");
        int undoWidth = this.font.width(undoLabel) + 10;
        Button undo = addRenderableWidget(small(undoLabel, b -> {
            editor.projects().undo();
            afterProjectChange();
        }, x, 2, undoWidth, "kinora.editor.undo"));
        undo.active = editor.projects().canUndo();
        x += undoWidth + 2;
        Component redoLabel = Component.translatable("kinora.editor.redo.label");
        int redoWidth = this.font.width(redoLabel) + 10;
        Button redo = addRenderableWidget(small(redoLabel, b -> {
            editor.projects().redo();
            afterProjectChange();
        }, x, 2, redoWidth, "kinora.editor.redo"));
        redo.active = editor.projects().canRedo();
        x += redoWidth + 6;
        addRenderableWidget(small(Component.translatable("kinora.editor.new_shot"), b -> newShot(), x, 2, 64, "kinora.editor.new_shot.tooltip"));
        x += 66;
        addRenderableWidget(small(Component.translatable("kinora.editor.templates"), b -> {
            templatesOpen = !templatesOpen;
            templateButtons.forEach(t -> t.visible = templatesOpen);
        }, x, 2, 64, "kinora.editor.templates.tooltip"));
        int ty = 22;
        for (ShotTemplates.Template template : ShotTemplates.Template.values()) {
            Button tb = addRenderableWidget(Button.builder(Component.literal(ShotTemplates.name(template)), b -> insertTemplate(template))
                    .bounds(x, ty, 90, 16).build());
            tb.visible = templatesOpen;
            templateButtons.add(tb);
            ty += 16;
        }
        x += 70;
        Component viewLabel = Component.translatable(editor.view() == EditorState.View.SHOT ? "kinora.editor.view.shot" : "kinora.editor.view.sequence");
        addRenderableWidget(small(viewLabel, b -> {
            editor.setView(editor.view() == EditorState.View.SHOT ? EditorState.View.SEQUENCE : EditorState.View.SHOT);
            manager.updateCameraMode();
            rebuild();
        }, x, 2, 70, "kinora.editor.view.tooltip"));
        x += 74;
        addRenderableWidget(small(Component.translatable("kinora.editor.save"), b -> save(), x, 2, 40, "kinora.editor.save.tooltip"));
        addRenderableWidget(small(Component.literal("?"), b -> guideOpen = true, this.width - 86, 2, 18, "kinora.editor.guide.tooltip"));
        addRenderableWidget(small(Component.translatable("kinora.editor.render"), b -> RenderUi.open(this), this.width - 64, 2, 60,
                "kinora.editor.render.tooltip"));

        // Shot strip.
        int sx = 4;
        for (Shot s : project.shots) {
            String label = Ui.fit(this.font, s.name, 70);
            Button b = addRenderableWidget(Button.builder(Component.literal(label), btn -> {
                editor.select(s);
                editor.setView(EditorState.View.SHOT);
                manager.updateCameraMode();
                rebuild();
            }).bounds(sx, TOP + 1, Math.max(30, this.font.width(label) + 10), STRIP - 2).build());
            if (s == shot) {
                // The selected shot gets an accent outline (a disabled-looking button read backwards).
                int bx = sx;
                int bw = b.getWidth();
                addRenderableOnly((g, mx, my, pt) -> {
                    int c = Theme.accent();
                    g.fill(bx - 1, TOP, bx + bw + 1, TOP + 1, c);
                    g.fill(bx - 1, TOP + STRIP - 1, bx + bw + 1, TOP + STRIP, c);
                    g.fill(bx - 1, TOP, bx, TOP + STRIP, c);
                    g.fill(bx + bw, TOP, bx + bw + 1, TOP + STRIP, c);
                });
            }
            sx += b.getWidth() + 2;
            if (sx > inspectorLeft() - 40) {
                break;
            }
        }

        // Transport.
        int ty2 = timelineTop();
        int tx = 4;
        Component playLabel = Component.translatable(editor.playing() ? "kinora.editor.pause.label" : "kinora.editor.play.label");
        int playWidth = Math.max(this.font.width(Component.translatable("kinora.editor.pause.label")),
                this.font.width(Component.translatable("kinora.editor.play.label"))) + 10;
        addRenderableWidget(small(playLabel, b -> togglePlay(), tx, ty2 + 1, playWidth, "kinora.editor.play"));
        tx += playWidth + 2;
        Component loopLabel = Component.translatable(editor.loop() ? "kinora.editor.loop.on" : "kinora.editor.loop.off");
        int loopWidth = Math.max(this.font.width(Component.translatable("kinora.editor.loop.on")),
                this.font.width(Component.translatable("kinora.editor.loop.off"))) + 10;
        addRenderableWidget(small(loopLabel, b -> {
            editor.setLoop(!editor.loop());
            rebuild();
        }, tx, ty2 + 1, loopWidth, "kinora.editor.loop"));
        tx += loopWidth + 2;
        if (shot != null && editor.view() == EditorState.View.SHOT) {
            addRenderableWidget(small(Component.translatable("kinora.editor.key"), b -> keyCamera(), tx, ty2 + 1, 40, "kinora.editor.key.tooltip"));
            tx += 42;
            Component camLabel = Component.translatable(editor.previewCamera() ? "kinora.editor.camera.preview" : "kinora.editor.camera.free");
            addRenderableWidget(small(camLabel, b -> {
                editor.setPreviewCamera(!editor.previewCamera());
                rebuild();
            }, tx, ty2 + 1, 70, "kinora.editor.camera.tooltip"));
            tx += 70;
        }

        transportEnd = tx;

        if (shot != null && editor.view() == EditorState.View.SHOT) {
            int before = children().size();
            buildInspector(shot);
            clipInspector(before);
        }
    }

    private Button small(Component label, Button.OnPress press, int x, int y, int w, String tooltipKey) {
        Button b = Button.builder(label, press).bounds(x, y, w, 16).build();
        b.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        return b;
    }

    /** How far the inspector is scrolled (mouse wheel over it), in GUI pixels. */
    private int inspectorScroll;

    private int inspectorTop() {
        return TOP + STRIP + 16;
    }

    private int inspectorBottom() {
        return timelineTop() - 4;
    }

    /** Hides inspector widgets scrolled out of its panel (the titles list beside it stays). */
    private void clipInspector(int firstChild) {
        int left = inspectorLeft();
        for (int i = firstChild; i < children().size(); i++) {
            if (children().get(i) instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getX() >= left
                    && (w.getY() < inspectorTop() || w.getY() + w.getHeight() > inspectorBottom())) {
                w.visible = false;
            }
        }
    }

    private void buildInspector(Shot shot) {
        int x = inspectorLeft() + 4;
        int w = INSPECTOR_WIDTH - 8;
        int y = TOP + STRIP + 18 - inspectorScroll;
        List<Keyframe> keys = selectedKeys(shot);
        if (!keys.isEmpty()) {
            Keyframe first = keys.getFirst();
            Track owner = trackOf(shot, first);
            if (owner != null) {
                y = valueFields(shot, owner, first, keys, x, y, w);
            }
            field(x, y, w, "kinora.inspector.key_time", format(first.time), v -> {
                double t = parse(v, first.time);
                editor.projects().edit(() -> {
                    double delta = t - first.time;
                    for (Keyframe k : keys) {
                        k.time = Math.max(0, Math.min(shot.duration, k.time + delta));
                    }
                    shot.tracks.values().forEach(Track::changed);
                });
            });
            y += 28;
            addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.interp", Component.translatable(
                            "kinora.interp." + first.interpolation.name().toLowerCase(Locale.ROOT))), b -> {
                Interpolation next = Interpolation.values()[(first.interpolation.ordinal() + 1) % Interpolation.values().length];
                editor.projects().edit(() -> keys.forEach(k -> k.interpolation = next));
                rebuild();
            }).bounds(x, y, w, 16).build());
            y += 18;
            addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.easing", Component.translatable(
                            "kinora.easing." + first.easing.name().toLowerCase(Locale.ROOT))), b -> {
                Easing next = Easing.values()[(first.easing.ordinal() + 1) % (Easing.values().length - 1)];
                editor.projects().edit(() -> keys.forEach(k -> k.easing = next));
                rebuild();
            }).bounds(x, y, w, 16).build());
            y += 18;
            if (first.interpolation == Interpolation.BEZIER && owner != null) {
                y = handleFields(owner, first, keys, x, y, w);
            }
            addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.goto_key"), b -> gotoKey(first)).bounds(x, y, w / 2 - 1, 16).build());
            addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.delete_key"), b -> {
                editor.deleteSelection();
                rebuild();
            }).bounds(x + w / 2 + 1, y, w / 2 - 1, 16).build());
            return;
        }
        field(x, y, w, "kinora.inspector.name", shot.name, v -> editor.projects().edit(() -> shot.name = v.isBlank() ? shot.name : v.trim()));
        y += 28;
        field(x, y, w / 2 - 2, "kinora.inspector.duration", format(shot.duration), v -> editor.setDuration(shot, parse(v, shot.duration)));
        field(x + w / 2 + 2, y, w / 2 - 2, "kinora.inspector.speed", format(EditorState.speedOf(shot)), v -> {
            double speed = parse(v, EditorState.speedOf(shot));
            if (speed > 0) {
                editor.setSpeed(shot, Math.min(100, speed));
            }
        });
        y += 30;
        addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.rig", Component.translatable(
                "kinora.rig." + shot.rig.mode.name().toLowerCase(Locale.ROOT))), b -> {
            Shot.RigMode next = Shot.RigMode.values()[(shot.rig.mode.ordinal() + 1) % Shot.RigMode.values().length];
            editor.projects().edit(() -> shot.rig.mode = next);
            rebuild();
        }).bounds(x, y, w, 16).build());
        y += 18;
        if (shot.rig.mode != Shot.RigMode.PATH) {
            addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.rig_target", entityName(shot.rig.targetEntity)),
                    b -> pickMode = "rig").bounds(x, y, w, 16).tooltip(Tooltip.create(Component.translatable("kinora.inspector.pick_hint"))).build());
            y += 18;
        }
        addRenderableWidget(Button.builder(Component.translatable(shot.lookAt.enabled ? "kinora.inspector.lookat_on" : "kinora.inspector.lookat_off",
                lookTargetName(shot)), b -> {
            if (!shot.lookAt.enabled) {
                pickMode = "look";
            } else {
                editor.projects().edit(() -> shot.lookAt.enabled = false);
                rebuild();
            }
        }).bounds(x, y, w, 16).tooltip(Tooltip.create(Component.translatable("kinora.inspector.pick_hint"))).build());
        y += 18;
        addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.shake", Component.translatable(
                "kinora.shake." + shot.shake.preset.name().toLowerCase(Locale.ROOT))), b -> {
            Shot.ShakePreset next = Shot.ShakePreset.values()[(shot.shake.preset.ordinal() + 1) % (Shot.ShakePreset.values().length - 1)];
            editor.projects().edit(() -> shot.shake.preset = next);
            rebuild();
        }).bounds(x, y, w, 16).build());
        y += 18;
        Track rotation = shot.track(Tracks.ROTATION);
        addRenderableWidget(Button.builder(Component.translatable(rotation.rotationMode == Track.RotationMode.SHORTEST
                ? "kinora.inspector.turns_short" : "kinora.inspector.turns_free"), b -> {
            editor.projects().edit(() -> rotation.rotationMode = rotation.rotationMode == Track.RotationMode.SHORTEST
                    ? Track.RotationMode.FREE : Track.RotationMode.SHORTEST);
            rebuild();
        }).bounds(x, y, w / 2 - 1, 16).tooltip(Tooltip.create(Component.translatable(rotation.rotationMode == Track.RotationMode.SHORTEST
                ? "kinora.inspector.rotation_shortest" : "kinora.inspector.rotation_free"))).build());
        Track position = shot.track(Tracks.POSITION);
        addRenderableWidget(Button.builder(Component.translatable(position.constantSpeed ? "kinora.inspector.speed_even"
                : "kinora.inspector.speed_keyed"), b -> {
            editor.projects().edit(() -> {
                position.constantSpeed = !position.constantSpeed;
                position.changed();
            });
            rebuild();
        }).bounds(x + w / 2 + 1, y, w / 2 - 1, 16).tooltip(Tooltip.create(Component.translatable(position.constantSpeed
                ? "kinora.inspector.constant_speed_on" : "kinora.inspector.constant_speed_off"))).build());
        y += 20;
        addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.add_track"), b -> {
            addTrackOpen = !addTrackOpen;
            titlesOpen = false;
            rebuild();
        }).bounds(x, y, w / 2 - 1, 16).tooltip(Tooltip.create(Component.translatable("kinora.inspector.add_track.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.titles", shot.overlays.size()), b -> {
            titlesOpen = !titlesOpen;
            addTrackOpen = false;
            rebuild();
        }).bounds(x + w / 2 + 1, y, w / 2 - 1, 16).tooltip(Tooltip.create(Component.translatable("kinora.inspector.titles.tooltip"))).build());
        if (titlesOpen) {
            buildTitles(shot, x - 214, y);
        }
        // The list opens to the left of the inspector, over the world.
        int ly = y;
        int lx = x - 112;
        for (var entry : ADDABLE.entrySet()) {
            if (shot.existing(entry.getKey()) != null) {
                continue;
            }
            String id = entry.getKey();
            double value = entry.getValue();
            Button tb = addRenderableWidget(Button.builder(Component.translatable("kinora.track." + id), b -> addTrack(shot, id, value))
                    .bounds(lx, ly, 108, 14).build());
            tb.visible = addTrackOpen;
            addTrackButtons.add(tb);
            ly += 14;
            if (ly > this.height - 20) {
                ly = y;
                lx -= 110;
            }
        }
        y += 18;
        y += 22;
        addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.duplicate"), b -> {
            Shot copy = shot.copy();
            copy.name = shot.name + " copy";
            editor.addShot(copy);
            rebuild();
        }).bounds(x, y, w / 2 - 1, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.delete_shot"), b -> {
            editor.projects().edit(() -> editor.project().remove(shot));
            editor.select(null);
            manager.updateCameraMode();
            rebuild();
        }).bounds(x + w / 2 + 1, y, w / 2 - 1, 16).build());
    }

    /** A shot whose look was copied ("Look: copy"), for pasting into others. */
    private static @Nullable Shot copiedLook;

    /** Everything the palette can do, named as a user would look for it. */
    private List<CommandPalette.Action> paletteActions() {
        List<CommandPalette.Action> a = new ArrayList<>();
        Shot shot = editor.shot();
        a.add(act("kinora.palette.new_shot", "Ctrl+N", this::newShot));
        a.add(act("kinora.palette.key_camera", "I", this::keyCamera));
        a.add(act("kinora.palette.play", "Space", this::togglePlay));
        a.add(act("kinora.palette.loop", "", () -> editor.setLoop(!editor.loop())));
        a.add(act("kinora.palette.camera_toggle", "F", () -> editor.setPreviewCamera(!editor.previewCamera())));
        a.add(act("kinora.palette.path_toggle", "H", () -> PathGizmos.setVisible(!PathGizmos.visible())));
        a.add(act("kinora.palette.start", "Home", () -> editor.setPlayhead(0)));
        if (shot != null) {
            a.add(act("kinora.palette.end", "End", () -> editor.setPlayhead(shot.duration)));
        }
        a.add(act("kinora.palette.undo", "Ctrl+Z", () -> {
            editor.projects().undo();
            afterProjectChange();
        }));
        a.add(act("kinora.palette.redo", "Ctrl+Y", () -> {
            editor.projects().redo();
            afterProjectChange();
        }));
        a.add(act("kinora.palette.save", "Ctrl+S", this::save));
        a.add(act("kinora.palette.live_cut", ReplayKeys.keyName(ReplayKeys.Action.LIVE_CUT).getString(), () -> {
            this.minecraft.gui.setScreen(null);
            dev.kinora.mc.editor.LiveCutSession.start();
        }));
        if (shot != null) {
            a.add(act("kinora.palette.add_title", "", () -> addTitle(shot)));
        }
        if (shot != null) {
            a.add(act("kinora.palette.look_copy", "", () -> {
                copiedLook = shot.copy();
                Notify.info(Component.translatable("kinora.look.title"), Component.translatable("kinora.look.copied", shot.name));
            }));
            if (copiedLook != null) {
                Shot look = copiedLook;
                a.add(act("kinora.palette.look_paste", "", () -> {
                    editor.projects().edit(() -> dev.kinora.core.project.ShotLook.apply(look, shot));
                    afterProjectChange();
                }));
            }
            a.add(act("kinora.palette.look_all", "", () -> {
                editor.projects().edit(() -> editor.project().shots.forEach(other -> dev.kinora.core.project.ShotLook.apply(shot, other)));
                Notify.info(Component.translatable("kinora.look.title"),
                        Component.translatable("kinora.look.applied", editor.project().shots.size() - 1, shot.name));
                afterProjectChange();
            }));
        }
        if (shot != null && shot.rig.mode == Shot.RigMode.PATH && shot.existing(dev.kinora.core.project.Tracks.POSITION) != null) {
            a.add(act("kinora.palette.clear_path", "", () -> {
                int[] added = new int[1];
                editor.projects().edit(() -> added[0] = dev.kinora.core.project.PathFixer.fix(shot, editor.scene()));
                int left = dev.kinora.core.project.PathFixer.blocked(shot, editor.scene()).size();
                Notify.info(Component.translatable("kinora.path.title"), left == 0
                        ? Component.translatable(added[0] == 0 ? "kinora.path.clear" : "kinora.path.fixed", added[0])
                        : Component.translatable("kinora.path.left", added[0], left));
                afterProjectChange();
            }));
        }
        a.add(act("kinora.palette.save_version", "", () -> {
            String name = "Version " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"));
            try {
                editor.projects().saveVersion(name);
                Notify.info(Component.translatable("kinora.version.saved"), Component.literal(name));
            } catch (IOException e) {
                Notify.warn(Component.translatable("kinora.version.failed"), Component.literal(String.valueOf(e.getMessage())));
            }
        }));
        for (String version : editor.projects().versions()) {
            a.add(new CommandPalette.Action(Component.translatable("kinora.palette.restore_version", version).getString(), "", () -> {
                try {
                    editor.projects().restoreVersion(version);
                    editor.select(editor.project().shots.isEmpty() ? null : editor.project().shots.getFirst());
                    manager.updateCameraMode();
                } catch (IOException e) {
                    Notify.warn(Component.translatable("kinora.version.failed"), Component.literal(String.valueOf(e.getMessage())));
                }
            }));
        }
        a.add(act("kinora.palette.share", "", this::sharePackage));
        a.add(act("kinora.palette.render", "", () -> RenderUi.open(this)));
        a.add(act("kinora.palette.photo4k", "", () -> {
            onClose();
            dev.kinora.mc.render.PhotoMode.take(3840);
        }));
        a.add(act("kinora.palette.photo8k", "", () -> {
            onClose();
            dev.kinora.mc.render.PhotoMode.take(7680);
        }));
        a.add(act("kinora.palette.clip", "", () -> this.minecraft.gui.setScreen(new ClipScreen(this))));
        a.add(act("kinora.palette.queue", "", () -> RenderUi.openQueue(this)));
        a.add(act(editor.view() == EditorState.View.SHOT ? "kinora.palette.view_sequence" : "kinora.palette.view_shot", "", () -> {
            editor.setView(editor.view() == EditorState.View.SHOT ? EditorState.View.SEQUENCE : EditorState.View.SHOT);
            manager.updateCameraMode();
        }));
        for (Shot s : editor.project().shots) {
            a.add(new CommandPalette.Action(Component.translatable("kinora.palette.select_shot", s.name).getString(), "", () -> {
                editor.select(s);
                editor.setView(EditorState.View.SHOT);
                manager.updateCameraMode();
            }));
        }
        if (shot != null) {
            a.add(act("kinora.palette.duplicate", "", () -> {
                Shot copy = shot.copy();
                copy.name = shot.name + " copy";
                editor.addShot(copy);
            }));
            a.add(act("kinora.palette.delete_shot", "", () -> {
                editor.projects().edit(() -> editor.project().remove(shot));
                editor.select(null);
                manager.updateCameraMode();
            }));
            for (Shot.RigMode mode : Shot.RigMode.values()) {
                a.add(new CommandPalette.Action(Component.translatable("kinora.palette.rig",
                        Component.translatable("kinora.rig." + mode.name().toLowerCase(Locale.ROOT))).getString(), "",
                        () -> editor.projects().edit(() -> shot.rig.mode = mode)));
            }
            for (Shot.ShakePreset preset : Shot.ShakePreset.values()) {
                if (preset == Shot.ShakePreset.CUSTOM) {
                    continue;
                }
                a.add(new CommandPalette.Action(Component.translatable("kinora.palette.shake",
                        Component.translatable("kinora.shake." + preset.name().toLowerCase(Locale.ROOT))).getString(), "",
                        () -> editor.projects().edit(() -> shot.shake.preset = preset)));
            }
            a.add(act("kinora.palette.pick_target", "", () -> pickMode = "rig"));
            a.add(act("kinora.palette.pick_look", "", () -> pickMode = "look"));
            a.add(act("kinora.palette.look_off", "", () -> editor.projects().edit(() -> shot.lookAt.enabled = false)));
            for (var entry : ADDABLE.entrySet()) {
                String id = entry.getKey();
                double value = entry.getValue();
                a.add(new CommandPalette.Action(Component.translatable("kinora.palette.add_track",
                        Component.translatable("kinora.track." + id)).getString(), "", () -> addTrack(shot, id, value)));
            }
        }
        a.add(act("kinora.palette.auto_director", "", this::autoDirect));
        for (ShotTemplates.Template template : ShotTemplates.Template.values()) {
            a.add(new CommandPalette.Action(Component.translatable("kinora.palette.template", ShotTemplates.name(template)).getString(), "",
                    () -> insertTemplate(template)));
        }
        a.add(act("kinora.palette.fly", "Tab", this::onClose));
        a.add(act("kinora.palette.exit_replay", "", () -> manager.close()));
        return a;
    }

    private static CommandPalette.Action act(String key, String hint, Runnable run) {
        return new CommandPalette.Action(Component.translatable(key).getString(), hint, run);
    }

    /** The track a key belongs to. */
    private static @Nullable Track trackOf(Shot shot, Keyframe key) {
        for (Track t : shot.tracks.values()) {
            if (t.keys.contains(key)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Bezier handles of the selected keys: a preset (automatic, ease, linear) and, for one-value
     * tracks, the handles as numbers (seconds from the key, and the change of value at the handle).
     */
    private int handleFields(Track track, Keyframe first, List<Keyframe> keys, int x, int y, int w) {
        var preset = dev.kinora.core.timeline.Handles.of(track, first);
        addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.handles", Component.translatable(
                "kinora.handles." + preset.name().toLowerCase(Locale.ROOT))), b -> {
            var all = dev.kinora.core.timeline.Handles.Preset.values();
            // Cycles auto, ease, linear (custom is only reached by typing numbers).
            var next = all[(Math.min(preset.ordinal(), 2) + 1) % 3];
            editor.projects().edit(() -> keys.stream().filter(track.keys::contains)
                    .forEach(k -> dev.kinora.core.timeline.Handles.apply(track, k, next)));
            rebuild();
        }).bounds(x, y, w, 16).build());
        y += 18;
        if (track.kind != Track.Kind.VALUE) {
            return y;
        }
        int fw = (w - 6) / 4;
        double[] numbers = {first.inTime, first.inValue == null ? Double.NaN : first.inValue[0], first.outTime,
                first.outValue == null ? Double.NaN : first.outValue[0]};
        String[] labels = {"kinora.inspector.in_time", "kinora.inspector.in_value", "kinora.inspector.out_time", "kinora.inspector.out_value"};
        for (int i = 0; i < 4; i++) {
            int which = i;
            field(x + i * (fw + 2), y, fw, labels[i], Double.isNaN(numbers[i]) ? "" : format(numbers[i]), v -> {
                double value = parse(v, Double.NaN);
                if (Double.isNaN(value)) {
                    return;
                }
                editor.projects().edit(() -> {
                    switch (which) {
                        case 0 -> first.inTime = Math.max(0, value);
                        case 1 -> first.inValue = new double[] {value};
                        case 2 -> first.outTime = Math.max(0, value);
                        default -> first.outValue = new double[] {value};
                    }
                    track.changed();
                });
            });
        }
        return y + 28;
    }

    /**
     * Fields for the value of the selected key: one number, x/y/z for the camera path, yaw/pitch for
     * the direction. A value typed into a field applies to every selected key of that track.
     */
    private int valueFields(Shot shot, Track track, Keyframe first, List<Keyframe> keys, int x, int y, int w) {
        String[] labels = switch (track.kind) {
            case PATH -> new String[] {"kinora.inspector.x", "kinora.inspector.y", "kinora.inspector.z"};
            case ROTATION -> new String[] {"kinora.inspector.yaw", "kinora.inspector.pitch"};
            case VALUE -> new String[] {"kinora.inspector.value"};
        };
        int n = labels.length;
        int fw = (w - 2 * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            int component = i;
            field(x + i * (fw + 2), y, fw, labels[i], format(first.value[component]), v -> {
                double value = parse(v, first.value[component]);
                editor.projects().edit(() -> {
                    for (Keyframe k : keys) {
                        if (track.keys.contains(k)) {
                            k.value[component] = value;
                        }
                    }
                    track.changed();
                });
            });
        }
        return y + 28;
    }

    /**
     * Auto-director: three suggested shots of the subject (the rig target of the selected shot, or the
     * recorded player) at the current replay moment.
     */
    private void autoDirect() {
        ReplaySession session = manager.session();
        if (session == null) {
            return;
        }
        Shot current = editor.shot();
        int subject = current != null && current.rig.targetEntity != Integer.MIN_VALUE ? current.rig.targetEntity : session.recordedPlayerId();
        Entity entity = this.minecraft.level == null ? null : this.minecraft.level.getEntity(subject);
        String name = entity == null ? "?" : entity.getName().getString();
        editor.scene().track(subject);
        var shots = dev.kinora.mc.editor.AutoDirector.suggest(editor.scene(), subject, name, session.clock().time());
        if (shots.isEmpty()) {
            Notify.warn(Component.translatable("kinora.auto.none"), Component.literal(name));
            return;
        }
        for (Shot s : shots) {
            editor.addShot(s);
        }
        editor.select(shots.getFirst());
        manager.updateCameraMode();
        Notify.info(Component.translatable("kinora.auto.added", shots.size()), Component.literal(name));
    }

    /** Packs the replay, the project and its versions into one file to send to someone. */
    private void sharePackage() {
        ReplaySession session = manager.session();
        if (session == null) {
            return;
        }
        try {
            editor.projects().save();
            var versions = new java.util.LinkedHashMap<String, String>();
            for (String v : editor.projects().versions()) {
                versions.put(v, java.nio.file.Files.readString(dev.kinora.mc.editor.ProjectManager.versionsDir()
                        .resolve(editor.project().replayFileId).resolve(v + "." + dev.kinora.core.project.ProjectIO.EXTENSION)));
            }
            String name = session.file().path().getFileName().toString().replaceFirst("\\.kinora$", "");
            java.nio.file.Path out = dev.kinora.mc.util.KinoraPaths.root().resolve("shared")
                    .resolve(name + "." + dev.kinora.core.project.SharePackage.EXTENSION);
            dev.kinora.core.project.SharePackage.export(session.file().path(), dev.kinora.core.project.ProjectIO.toJson(editor.project()),
                    versions, out);
            Notify.info(Component.translatable("kinora.share.done"), Component.literal(out.getFileName().toString()));
            net.minecraft.util.Util.getPlatform().openPath(out.getParent());
        } catch (IOException e) {
            Notify.warn(Component.translatable("kinora.share.failed"), Component.literal(String.valueOf(e.getMessage())));
        }
    }

    private boolean titlesOpen;

    /** The titles of a shot, in a list beside the inspector: text, where on screen, delete; and "+ title". */
    private void buildTitles(Shot shot, int x, int y) {
        int w = 210;
        int height = shot.overlays.size() * 16 + 16;
        // Above the timeline, and drawn behind its rows.
        int top = Math.max(TOP + STRIP + 4, Math.min(y, timelineTop() - 4 - height));
        int bottom = top + height;
        addRenderableOnly((g, mx, my, pt) -> g.fill(x - 3, top - 3, x + w + 3, bottom + 1, 0xD0101418));
        int ry = top;
        for (Shot.Overlay o : shot.overlays) {
            int fw = w - 52;
            EditBox box = new EditBox(this.font, x, ry, fw, 14, Component.translatable("kinora.inspector.title_text"));
            box.setMaxLength(200);
            box.setValue(o.text);
            addRenderableWidget(box);
            fields.add(box);
            fieldCommits.add(() -> {
                editor.projects().edit(() -> o.text = box.getValue());
                seenRevision = -1;
            });
            String where = o.y > 0.66 ? "bottom" : o.y < 0.34 ? "top" : "middle";
            addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.title_" + where), b -> {
                editor.projects().edit(() -> o.y = o.y > 0.66 ? 0.15 : o.y < 0.34 ? 0.5 : 0.85);
                rebuild();
            }).bounds(x + fw + 2, ry, 30, 14).tooltip(Tooltip.create(Component.translatable("kinora.inspector.title_where"))).build());
            addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                editor.projects().edit(() -> shot.overlays.remove(o));
                rebuild();
            }).bounds(x + fw + 34, ry, 18, 14).build());
            ry += 16;
        }
        addRenderableWidget(Button.builder(Component.translatable("kinora.inspector.title_add"), b -> addTitle(shot))
                .bounds(x, ry, w, 14).tooltip(Tooltip.create(Component.translatable("kinora.inspector.title_add.tooltip"))).build());
    }

    /** A title at the playhead, three seconds long, low on the screen; its text is edited in the inspector. */
    private void addTitle(Shot shot) {
        Shot.Overlay o = new Shot.Overlay();
        o.text = Component.translatable("kinora.inspector.title_default").getString();
        o.start = Math.max(0, Math.min(shot.duration, editor.playhead()));
        o.end = Math.min(shot.duration, o.start + 3);
        editor.projects().edit(() -> shot.overlays.add(o));
        editor.selection().clear();
        titlesOpen = true;
        addTrackOpen = false;
        rebuild();
    }

    /** Keys a new track at the playhead with a useful first value, and selects that key for editing. */
    private void addTrack(Shot shot, String trackId, double value) {
        double t = Math.max(0, Math.min(shot.duration, editor.playhead()));
        double start = trackId.equals(Tracks.FOV) && CameraHooks.currentFrame() != null ? CameraHooks.currentFrame().fov() : value;
        Keyframe[] created = new Keyframe[1];
        editor.projects().edit(() -> created[0] = shot.track(trackId).put(new Keyframe(t, start)));
        editor.selection().clear();
        editor.selection().add(created[0]);
        addTrackOpen = false;
        rebuild();
    }

    private void field(int x, int y, int w, String labelKey, String value, java.util.function.Consumer<String> commit) {
        EditBox box = new EditBox(this.font, x, y + 10, w, 14, Component.translatable(labelKey));
        box.setValue(value);
        box.setMaxLength(64);
        addRenderableWidget(box);
        fields.add(box);
        fieldCommits.add(() -> {
            commit.accept(box.getValue());
            seenRevision = -1;
        });
        addRenderableOnly((g, mx, my, pt) -> {
            if (x < inspectorLeft() || y >= inspectorTop() && y + 24 <= inspectorBottom()) {
                g.text(this.font, Component.translatable(labelKey), x, y, Theme.textDim(), false);
            }
        });
    }

    private void commitFields() {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).isFocused()) {
                fieldCommits.get(i).run();
            }
        }
    }

    private static String format(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double parse(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Component entityName(int id) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null || id == Integer.MIN_VALUE ? null : mc.level.getEntity(id);
        return e == null ? Component.translatable("kinora.inspector.none") : e.getName();
    }

    private Component lookTargetName(Shot shot) {
        if (shot.lookAt.targetEntity != Integer.MIN_VALUE) {
            return entityName(shot.lookAt.targetEntity);
        }
        if (shot.lookAt.point != null) {
            return Component.literal(String.format(Locale.ROOT, "%.0f %.0f %.0f", shot.lookAt.point.x(), shot.lookAt.point.y(), shot.lookAt.point.z()));
        }
        return Component.translatable("kinora.inspector.none");
    }

    // ------------------------------------------------------------------ actions

    private void afterProjectChange() {
        if (editor.shot() == null && !editor.project().shots.isEmpty() && editor.view() == EditorState.View.SHOT) {
            editor.select(editor.project().shots.getLast());
        }
        manager.updateCameraMode();
        rebuild();
    }

    private void newShot() {
        ReplaySession session = manager.session();
        if (session == null) {
            return;
        }
        CameraState current = CameraHooks.currentFrame();
        editor.newShot(session, 5);
        if (current != null) {
            manager.camera().placeAt(current);
        }
        manager.updateCameraMode();
        rebuild();
    }

    private void insertTemplate(ShotTemplates.Template template) {
        templatesOpen = false;
        ReplaySession session = manager.session();
        CameraState current = CameraHooks.currentFrame();
        if (session == null || current == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Entity target = pickAt(this.width / 2.0, this.height / 2.0);
        Vec3d point = target != null ? new Vec3d(target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ()) : lookedAtBlock(mc, current);
        Shot shot = ShotTemplates.create(template, current, point, target != null ? target.getId() : Integer.MIN_VALUE, session.clock().time(),
                template == ShotTemplates.Template.WHIP_PAN ? 0.6 : 6);
        editor.addShot(shot);
        manager.updateCameraMode();
        rebuild();
    }

    private static @Nullable Vec3d lookedAtBlock(Minecraft mc, CameraState camera) {
        if (mc.level == null) {
            return null;
        }
        Vec3d f = camera.forward();
        Vec3 from = new Vec3(camera.x(), camera.y(), camera.z());
        Vec3 to = from.add(f.x() * 128, f.y() * 128, f.z() * 128);
        var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? null
                : new Vec3d(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z);
    }

    private void keyCamera() {
        CameraState camera = CameraHooks.currentFrame();
        if (camera != null) {
            editor.keyCamera(camera);
            rebuild();
        }
    }

    private void gotoKey(Keyframe key) {
        editor.setPlayhead(key.time);
        editor.setPreviewCamera(true);
        rebuild();
    }

    private void togglePlay() {
        editor.setPlaying(!editor.playing());
        if (editor.playing()) {
            manager.updateCameraMode();
        }
        rebuild();
    }

    private void save() {
        try {
            editor.projects().save();
            Notify.info(Component.translatable("kinora.editor.saved"), Component.literal(editor.project().name));
        } catch (IOException e) {
            Notify.warn(Component.translatable("kinora.editor.save_failed"), Component.literal(String.valueOf(e.getMessage())));
        }
    }

    private List<Keyframe> selectedKeys(Shot shot) {
        List<Keyframe> keys = new ArrayList<>();
        for (Track t : shot.tracks.values()) {
            for (Keyframe k : t.keys) {
                if (editor.selection().contains(k)) {
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void tick() {
        if (manager.session() == null) {
            onClose();
            return;
        }
        if (editor.projects().revision() != seenRevision && fields.stream().noneMatch(EditBox::isFocused)) {
            rebuild();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The world is the background.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (drag == Drag.FLY) {
            flyInput();
        }
        Ui.panel(graphics, 0, 0, this.width, TOP);
        graphics.fill(0, TOP, this.width, TOP + STRIP, Theme.panelLight());
        Shot shot = editor.shot();
        if (shot != null && editor.view() == EditorState.View.SHOT) {
            Ui.panel(graphics, inspectorLeft(), TOP + STRIP + 2, this.width - 4, timelineTop() - 4);
            Component header;
            if (editor.selection().isEmpty()) {
                header = Component.translatable("kinora.inspector.shot");
            } else {
                Track owner = trackOf(shot, editor.selection().iterator().next());
                header = owner == null ? Component.translatable("kinora.inspector.keys", editor.selection().size())
                        : Component.translatable("kinora.inspector.track_keys", Component.translatable("kinora.track." + owner.id),
                        editor.selection().size());
            }
            graphics.text(this.font, header, inspectorLeft() + 4, TOP + STRIP + 6, Theme.accent(), false);
        }
        renderTimeline(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (pickMode != null) {
            graphics.centeredText(this.font, Component.translatable("kinora.editor.pick_prompt"), this.width / 2, TOP + STRIP + 8, Theme.accent());
            Entity hovered = pickAt(mouseX, mouseY);
            if (hovered != null) {
                graphics.text(this.font, hovered.getName(), mouseX + 10, mouseY, Theme.text(), true);
            }
        } else if (shot == null && editor.view() == EditorState.View.SHOT) {
            graphics.centeredText(this.font, Component.translatable("kinora.editor.no_shot"), this.width / 2, this.height / 2 - 20, Theme.text());
            graphics.centeredText(this.font, Component.translatable("kinora.editor.fly_hint"), this.width / 2, this.height / 2 - 8, Theme.textDim());
        }
        if (palette != null) {
            palette.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
        }
        if (guideOpen) {
            renderGuide(graphics);
        }
    }

    private static final String[] GUIDE_STEPS = {"fly", "shot", "key", "preview", "palette", "render"};

    private void renderGuide(GuiGraphicsExtractor g) {
        int w = Math.min(360, this.width - 40);
        int lineHeight = 10;
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        for (int i = 0; i < GUIDE_STEPS.length; i++) {
            lines.addAll(this.font.split(Component.translatable("kinora.guide." + GUIDE_STEPS[i], i + 1), w - 20));
            lines.add(net.minecraft.util.FormattedCharSequence.EMPTY);
        }
        int h = 34 + lines.size() * lineHeight + 14;
        int x0 = (this.width - w) / 2;
        int y0 = Math.max(TOP + STRIP + 4, (this.height - h) / 2);
        g.fill(0, 0, this.width, this.height, 0x70000000);
        Ui.panel(g, x0, y0, x0 + w, y0 + h);
        g.text(this.font, Component.translatable("kinora.guide.title"), x0 + 10, y0 + 10, Theme.accent(), false);
        int y = y0 + 28;
        for (var line : lines) {
            g.text(this.font, line, x0 + 10, y, Theme.text(), false);
            y += lineHeight;
        }
        g.text(this.font, Component.translatable("kinora.guide.close"), x0 + 10, y0 + h - 14, Theme.textDim(), false);
    }

    private void closeGuide() {
        guideOpen = false;
        UiState.setFlag("editorGuideSeen", true);
    }

    private void renderTimeline(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = timelineTop();
        Ui.panel(g, 0, top, this.width, this.height);
        ReplaySession session = manager.session();
        if (session == null) {
            return;
        }
        Shot shot = editor.shot();
        int x0 = laneLeft();
        int x1 = laneRight();
        int rulerY = top + TRANSPORT;
        if (editor.view() == EditorState.View.SEQUENCE) {
            renderSequence(g, top, x0, x1, rulerY);
            return;
        }
        if (shot == null) {
            renderReplayBar(g, session, x0, x1, rulerY, mouseX);
            return;
        }
        double duration = Math.max(0.05, shot.duration);
        // Time readout.
        String time = Ui.seconds(editor.playhead()) + " / " + Ui.seconds(duration) + "   "
                + Component.translatable("kinora.editor.replay_time", ReplayHud.clock(shot.replayTicks(editor.playhead()) - session.startTick())).getString();
        g.text(this.font, time, transportEnd + 6, top + 5, Theme.text(), false);
        // Ruler.
        double pps = (x1 - x0) / duration;
        double step = Ui.rulerStep(pps);
        for (double t = 0; t <= duration + 1e-9; t += step) {
            int x = x0 + (int) Math.round(t * pps);
            g.fill(x, rulerY, x + 1, rulerY + 4, Theme.textDim());
            g.text(this.font, Ui.seconds(t), x + 2, rulerY + 2, Theme.textDim(), false);
        }
        // Recorded markers that fall inside the shot.
        for (Marker m : session.markers()) {
            double shotTime = timeForReplay(shot, m.tick());
            if (!Double.isNaN(shotTime)) {
                int mx = x0 + (int) Math.round(shotTime * pps);
                g.fill(mx, rulerY, mx + 1, rulerY + RULER, m.color() | 0xFF000000);
            }
        }
        // Rows.
        List<String> rows = visibleRows();
        int y = rulerY + RULER;
        for (String id : rows) {
            boolean selectedRow = id.equals(editor.selectedTrack());
            g.fill(0, y, this.width, y + ROW, selectedRow ? 0x30FFFFFF : ((rows.indexOf(id) % 2 == 0) ? 0x10FFFFFF : 0));
            g.text(this.font, Component.translatable("kinora.track." + id), 4, y + 3, 0xFF000000 | Tracks.colorOf(id), false);
            Track track = shot.tracks.get(id);
            if (track != null) {
                drawKeys(g, track, id.equals(Tracks.POSITION) ? shot.tracks.get(Tracks.ROTATION) : null, x0, pps, y + ROW / 2);
            }
            y += ROW;
        }
        // Playhead.
        int px = x0 + (int) Math.round(editor.playhead() * pps);
        g.fill(px, rulerY, px + 1, this.height, Theme.playhead());
        Ui.diamond(g, px, rulerY, 3, Theme.playhead());
        if (drag == Drag.BOX) {
            int bx0 = (int) Math.min(dragStartX, mouseX);
            int bx1 = (int) Math.max(dragStartX, mouseX);
            int by0 = (int) Math.min(dragStartY, mouseY);
            int by1 = (int) Math.max(dragStartY, mouseY);
            g.fill(bx0, by0, bx1, by1, 0x3056B4E9);
            g.outline(bx0, by0, bx1 - bx0, by1 - by0, Theme.selection());
        }
    }

    private void drawKeys(GuiGraphicsExtractor g, Track track, @Nullable Track companion, int x0, double pps, int cy) {
        List<Keyframe> all = new ArrayList<>(track.keys);
        if (companion != null) {
            all.addAll(companion.keys);
        }
        for (Keyframe k : all) {
            int kx = x0 + (int) Math.round(k.time * pps);
            boolean selected = editor.selection().contains(k);
            int color = selected ? Theme.selection() : (0xFF000000 | track.color);
            if (k.interpolation == Interpolation.HOLD) {
                g.fill(kx - 3, cy - 3, kx + 4, cy + 4, color);
            } else {
                Ui.diamond(g, kx, cy, 4, color);
            }
        }
        // One-value tracks show their curve inside the row; others a line between the keys.
        if (track.keys.size() > 1 && track.kind == Track.Kind.VALUE && !track.id.equals(Tracks.TIME)) {
            drawCurve(g, track, x0, pps, cy);
        } else if (track.keys.size() > 1) {
            int a = x0 + (int) Math.round(track.keys.getFirst().time * pps);
            int b = x0 + (int) Math.round(track.keys.getLast().time * pps);
            g.fill(a, cy, b, cy + 1, 0x60000000 | track.color);
        }
    }

    /** The track's values between its first and last key, scaled to the row's height. */
    private void drawCurve(GuiGraphicsExtractor g, Track track, int x0, double pps, int cy) {
        double t0 = track.keys.getFirst().time;
        double t1 = track.keys.getLast().time;
        int a = x0 + (int) Math.round(t0 * pps);
        int b = x0 + (int) Math.round(t1 * pps);
        if (b - a < 2) {
            return;
        }
        double[] values = new double[b - a + 1];
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            values[i] = track.evaluate(t0 + (t1 - t0) * i / (values.length - 1))[0];
            lo = Math.min(lo, values[i]);
            hi = Math.max(hi, values[i]);
        }
        int half = ROW / 2 - 2;
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i < values.length; i++) {
            double s = hi - lo < 1e-9 ? 0.5 : (values[i] - lo) / (hi - lo);
            int y = cy + half - (int) Math.round(s * 2 * half);
            int y0 = previous == Integer.MIN_VALUE ? y : Math.min(previous, y);
            int y1 = previous == Integer.MIN_VALUE ? y : Math.max(previous, y);
            g.fill(a + i, y0, a + i + 1, y1 + 1, 0xA0000000 | track.color);
            previous = y;
        }
    }

    private void renderReplayBar(GuiGraphicsExtractor g, ReplaySession session, int x0, int x1, int rulerY, int mouseX) {
        double start = session.startTick();
        double end = Math.max(start + 1, session.endTick());
        String time = ReplayHud.clock(session.clock().time() - start) + " / " + ReplayHud.clock(end - start);
        g.text(this.font, time, 160, rulerY - TRANSPORT + 5, Theme.text(), false);
        int y = rulerY + 6;
        g.fill(x0, y, x1, y + 3, 0xFF404048);
        for (Marker m : session.markers()) {
            int mx = x0 + (int) Math.round((x1 - x0) * (m.tick() - start) / (end - start));
            g.fill(mx, y - 4, mx + 1, y + 7, m.color() | 0xFF000000);
        }
        for (Shot s : editor.project().shots) {
            double[] range = s.replayRange();
            int a = x0 + (int) Math.round((x1 - x0) * (range[0] - start) / (end - start));
            int b = x0 + (int) Math.round((x1 - x0) * (range[1] - start) / (end - start));
            g.fill(a, y + 6, Math.max(a + 2, b), y + 10, 0xA00072B2);
        }
        int head = x0 + (int) Math.round((x1 - x0) * (session.clock().time() - start) / (end - start));
        g.fill(head, rulerY, head + 1, this.height, Theme.playhead());
    }

    private void renderSequence(GuiGraphicsExtractor g, int top, int x0, int x1, int rulerY) {
        SequenceTimeline timeline = new SequenceTimeline(editor.project());
        double duration = Math.max(0.05, timeline.duration());
        double pps = (x1 - x0) / duration;
        g.text(this.font, Ui.seconds(editor.sequenceTime()) + " / " + Ui.seconds(duration), 160, top + 5, Theme.text(), false);
        int y = rulerY + RULER;
        for (SequenceTimeline.Placement p : timeline.placements()) {
            int a = x0 + (int) Math.round(p.start() * pps);
            int b = x0 + (int) Math.round(p.end() * pps);
            g.fill(a, y, b, y + ROW * 2, 0xC0205080);
            g.outline(a, y, b - a, ROW * 2, Theme.border());
            g.text(this.font, Ui.fit(this.font, p.shot().name, b - a - 4), a + 3, y + 3, Theme.text(), false);
            if (p.inOverlap() > 0) {
                g.fill(a, y + ROW, a + (int) (p.inOverlap() * pps), y + ROW * 2, 0x60FFFFFF);
            }
        }
        int px = x0 + (int) Math.round(editor.sequenceTime() * pps);
        g.fill(px, rulerY, px + 1, this.height, Theme.playhead());
    }

    /** Shot time at which a replay tick is shown, assuming the remap is increasing; NaN if outside. */
    private static double timeForReplay(Shot shot, double tick) {
        double lo = 0;
        double hi = shot.duration;
        double a = shot.replayTicks(lo);
        double b = shot.replayTicks(hi);
        if (tick < Math.min(a, b) || tick > Math.max(a, b) || a == b) {
            return Double.NaN;
        }
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if ((shot.replayTicks(mid) < tick) == (a < b)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (guideOpen) {
            closeGuide();
            return true;
        }
        if (palette != null) {
            palette = null;
            return true;
        }
        double mx = event.x();
        double my = event.y();
        if (pickMode != null) {
            if (event.button() == 0) {
                applyPick(mx, my);
            }
            pickMode = null;
            rebuild();
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (templatesOpen) {
            templatesOpen = false;
            templateButtons.forEach(t -> t.visible = false);
        }
        int top = timelineTop();
        Shot shot = editor.shot();
        if (event.button() == 1 && my < top && my > TOP + STRIP) {
            startFly();
            return true;
        }
        if (my >= top + TRANSPORT && event.button() == 0) {
            ReplaySession session = manager.session();
            if (editor.view() == EditorState.View.SEQUENCE) {
                drag = Drag.PLAYHEAD;
                scrub(mx);
                return true;
            }
            if (shot == null) {
                drag = Drag.PLAYHEAD;
                if (session != null) {
                    scrubReplay(session, mx);
                }
                return true;
            }
            Keyframe hit = keyAt(shot, mx, my);
            int rulerBottom = top + TRANSPORT + RULER;
            if (hit != null) {
                if (!event.hasShiftDown() && !editor.selection().contains(hit)) {
                    editor.selection().clear();
                }
                if (event.hasShiftDown() && editor.selection().contains(hit)) {
                    editor.selection().remove(hit);
                } else {
                    editor.selection().add(hit);
                }
                if (doubleClick) {
                    gotoKey(hit);
                    return true;
                }
                startKeyDrag(shot, mx);
                rebuild();
                return true;
            }
            if (my < rulerBottom) {
                drag = Drag.PLAYHEAD;
                scrub(mx);
                return true;
            }
            String row = rowAt(my);
            editor.selectTrack(row);
            if (!event.hasShiftDown()) {
                editor.selection().clear();
            }
            drag = Drag.BOX;
            dragStartX = mx;
            dragStartY = my;
            rebuild();
            return true;
        }
        return false;
    }

    private void startKeyDrag(Shot shot, double mx) {
        drag = Drag.KEYS;
        dragStartX = mx;
        dragOriginalTimes.clear();
        editor.projects().begin();
        for (Keyframe k : selectedKeys(shot)) {
            dragOriginalTimes.add(new double[] {k.time});
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        Shot shot = editor.shot();
        switch (drag) {
            case PLAYHEAD -> {
                ReplaySession session = manager.session();
                if (shot == null && editor.view() == EditorState.View.SHOT) {
                    if (session != null) {
                        scrubReplay(session, event.x());
                    }
                } else {
                    scrub(event.x());
                }
                return true;
            }
            case KEYS -> {
                if (shot != null) {
                    double pps = (laneRight() - laneLeft()) / Math.max(0.05, shot.duration);
                    double delta = (event.x() - dragStartX) / pps;
                    // Snap to output frames (project frame rate).
                    double frame = 1.0 / editor.project().render.fps();
                    List<Keyframe> keys = selectedKeys(shot);
                    for (int i = 0; i < keys.size() && i < dragOriginalTimes.size(); i++) {
                        double t = dragOriginalTimes.get(i)[0] + delta;
                        if (!event.hasAltDown()) {
                            t = Math.round(t / frame) * frame;
                        }
                        keys.get(i).time = Math.max(0, Math.min(shot.duration, t));
                    }
                    shot.tracks.values().forEach(Track::changed);
                    editor.projects().touched();
                }
                return true;
            }
            case FLY -> {
                turn(dx, dy);
                return true;
            }
            case BOX -> {
                return true;
            }
            default -> {
                return super.mouseDragged(event, dx, dy);
            }
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        Drag was = drag;
        drag = Drag.NONE;
        if (was == Drag.KEYS) {
            editor.projects().commit();
            rebuild();
            return true;
        }
        if (was == Drag.FLY) {
            stopFly();
            return true;
        }
        if (was == Drag.BOX) {
            Shot shot = editor.shot();
            if (shot != null) {
                selectBox(shot, dragStartX, dragStartY, event.x(), event.y());
            }
            rebuild();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x >= inspectorLeft() && y >= inspectorTop() && y < inspectorBottom() && editor.shot() != null) {
            // Scroll the inspector when it holds more than fits.
            inspectorScroll = Math.max(0, Math.min(200, inspectorScroll - (int) Math.signum(scrollY) * 18));
            rebuild();
            return true;
        }
        if (x < laneLeft() && y > timelineTop() + TRANSPORT + RULER && allRows().size() > maxRows()) {
            // Over the track names: scroll the rows.
            rowScroll -= (int) Math.signum(scrollY);
            rebuild();
            return true;
        }
        if (drag == Drag.FLY || y < timelineTop()) {
            manager.camera().adjustSpeed(Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private void scrub(double mx) {
        double f = Math.max(0, Math.min(1, (mx - laneLeft()) / (double) (laneRight() - laneLeft())));
        if (editor.view() == EditorState.View.SEQUENCE) {
            editor.setSequenceTime(f * new SequenceTimeline(editor.project()).duration());
            return;
        }
        Shot shot = editor.shot();
        if (shot != null) {
            editor.setPlaying(false);
            editor.setPlayhead(f * shot.duration);
            editor.setPreviewCamera(true);
        }
    }

    private void scrubReplay(ReplaySession session, double mx) {
        double f = Math.max(0, Math.min(1, (mx - laneLeft()) / (double) (laneRight() - laneLeft())));
        double start = session.startTick();
        double end = Math.max(start + 1, session.endTick());
        session.setPaused(true);
        session.seek(start + f * (end - start));
    }

    private @Nullable Keyframe keyAt(Shot shot, double mx, double my) {
        String row = rowAt(my);
        if (row == null) {
            return null;
        }
        double pps = (laneRight() - laneLeft()) / Math.max(0.05, shot.duration);
        List<Keyframe> candidates = new ArrayList<>();
        Track track = shot.tracks.get(row);
        if (track != null) {
            candidates.addAll(track.keys);
        }
        if (row.equals(Tracks.POSITION) && shot.tracks.get(Tracks.ROTATION) != null) {
            candidates.addAll(shot.tracks.get(Tracks.ROTATION).keys);
        }
        Keyframe best = null;
        double bestDistance = 6;
        for (Keyframe k : candidates) {
            double d = Math.abs(laneLeft() + k.time * pps - mx);
            if (d < bestDistance) {
                bestDistance = d;
                best = k;
            }
        }
        if (best != null && row.equals(Tracks.POSITION)) {
            // Selecting a camera key selects the position and direction keys at that time together.
            double t = best.time;
            for (String id : List.of(Tracks.POSITION, Tracks.ROTATION)) {
                Track tr = shot.tracks.get(id);
                if (tr != null) {
                    for (Keyframe k : tr.keys) {
                        if (Math.abs(k.time - t) < 1e-6 && k != best) {
                            editor.selection().add(k);
                        }
                    }
                }
            }
        }
        return best;
    }

    private @Nullable String rowAt(double my) {
        int y = timelineTop() + TRANSPORT + RULER;
        for (String id : visibleRows()) {
            if (my >= y && my < y + ROW) {
                return id;
            }
            y += ROW;
        }
        return null;
    }

    private void selectBox(Shot shot, double ax, double ay, double bx, double by) {
        double pps = (laneRight() - laneLeft()) / Math.max(0.05, shot.duration);
        double t0 = (Math.min(ax, bx) - laneLeft()) / pps;
        double t1 = (Math.max(ax, bx) - laneLeft()) / pps;
        int y = timelineTop() + TRANSPORT + RULER;
        for (String id : visibleRows()) {
            boolean rowHit = y + ROW > Math.min(ay, by) && y < Math.max(ay, by);
            if (rowHit) {
                for (String trackId : id.equals(Tracks.POSITION) ? List.of(Tracks.POSITION, Tracks.ROTATION) : List.of(id)) {
                    Track track = shot.tracks.get(trackId);
                    if (track != null) {
                        for (Keyframe k : track.keys) {
                            if (k.time >= t0 && k.time <= t1) {
                                editor.selection().add(k);
                            }
                        }
                    }
                }
            }
            y += ROW;
        }
    }

    // ------------------------------------------------------------------ flying

    private void startFly() {
        drag = Drag.FLY;
        editor.setPreviewCamera(false);
        editor.setPlaying(false);
        Minecraft mc = Minecraft.getInstance();
        GLFW.glfwSetInputMode(mc.getWindow().handle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }

    private void stopFly() {
        Minecraft mc = Minecraft.getInstance();
        manager.camera().endScreenInput();
        GLFW.glfwSetInputMode(mc.getWindow().handle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        rebuild();
    }

    private void turn(double dx, double dy) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        double sensitivity = mc.options.sensitivity().get() * 0.6 + 0.2;
        double factor = sensitivity * sensitivity * sensitivity * 8.0 * 0.15;
        mc.player.setYRot((float) (mc.player.getYRot() + dx * factor * 2));
        mc.player.setXRot((float) Math.max(-90, Math.min(90, mc.player.getXRot() + dy * factor * 2)));
    }

    private void flyInput() {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        double forward = (key(window, mc.options.keyUp) ? 1 : 0) - (key(window, mc.options.keyDown) ? 1 : 0);
        double strafe = (key(window, mc.options.keyLeft) ? 1 : 0) - (key(window, mc.options.keyRight) ? 1 : 0);
        double up = (key(window, mc.options.keyJump) ? 1 : 0) - (key(window, mc.options.keyShift) ? 1 : 0);
        boolean boost = down(window, GLFW.GLFW_KEY_LEFT_CONTROL) || key(window, mc.options.keySprint);
        manager.camera().screenInput(forward, strafe, up, boost);
    }

    private static boolean key(com.mojang.blaze3d.platform.Window window, net.minecraft.client.KeyMapping mapping) {
        InputConstants.Key k = mapping.getKey();
        return k.getType() == InputConstants.Type.KEYSYM && down(window, k.getValue());
    }

    private static boolean down(com.mojang.blaze3d.platform.Window window, int key) {
        return InputConstants.isKeyDown(window, key) || dev.kinora.mc.dev.DevScript.held(key);
    }

    // ------------------------------------------------------------------ picking

    /** The entity under a screen position, by casting a ray through the current camera. */
    private @Nullable Entity pickAt(double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        CameraState camera = CameraHooks.currentFrame();
        if (mc.level == null || camera == null) {
            return null;
        }
        double tanV = Math.tan(Math.toRadians(camera.fov()) / 2);
        double aspect = this.width / (double) this.height;
        double nx = 2 * mx / this.width - 1;
        double ny = 1 - 2 * my / this.height;
        // Camera-local: +Z forward, +X to the left in Minecraft's convention.
        Vec3d local = new Vec3d(-nx * tanV * aspect, ny * tanV, 1).normalize();
        Vec3d dir = Quat.fromYawPitchRoll(camera.yaw(), camera.pitch(), 0).rotate(local);
        Vec3 from = new Vec3(camera.x(), camera.y(), camera.z());
        Vec3 to = from.add(dir.x() * 256, dir.y() * 256, dir.z() * 256);
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) {
                continue;
            }
            AABB box = e.getBoundingBox().inflate(0.15);
            Optional<Vec3> hit = box.clip(from, to);
            if (hit.isPresent()) {
                double d = hit.get().distanceToSqr(from);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = e;
                }
            }
        }
        return best;
    }

    private void applyPick(double mx, double my) {
        Shot shot = editor.shot();
        if (shot == null) {
            return;
        }
        Entity e = pickAt(mx, my);
        if ("rig".equals(pickMode) && e != null) {
            editor.projects().edit(() -> shot.rig.targetEntity = e.getId());
            editor.scene().track(e.getId());
        } else if ("look".equals(pickMode)) {
            if (e != null) {
                editor.projects().edit(() -> {
                    shot.lookAt.enabled = true;
                    shot.lookAt.targetEntity = e.getId();
                    shot.lookAt.point = null;
                });
                editor.scene().track(e.getId());
            } else {
                CameraState camera = CameraHooks.currentFrame();
                Vec3d point = camera == null ? null : lookedAtBlock(Minecraft.getInstance(), camera);
                if (point != null) {
                    editor.projects().edit(() -> {
                        shot.lookAt.enabled = true;
                        shot.lookAt.targetEntity = Integer.MIN_VALUE;
                        shot.lookAt.point = point;
                    });
                }
            }
        }
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (guideOpen) {
            closeGuide();
            return true;
        }
        if (palette != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                palette = null;
                return true;
            }
            CommandPalette.Action action = palette.key(event.key());
            if (action != null) {
                palette = null;
                action.run().run();
                rebuild();
            }
            return true;
        }
        boolean typing = fields.stream().anyMatch(EditBox::isFocused);
        if (typing) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER || event.key() == GLFW.GLFW_KEY_TAB) {
                commitFields();
                setFocused(null);
                rebuild();
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                setFocused(null);
                rebuild();
                return true;
            }
            return super.keyPressed(event);
        }
        if (drag == Drag.FLY) {
            return true;
        }
        Shot shot = editor.shot();
        boolean ctrl = event.hasControlDown();
        switch (event.key()) {
            case GLFW.GLFW_KEY_SPACE -> togglePlay();
            case GLFW.GLFW_KEY_I -> keyCamera();
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
                editor.deleteSelection();
                rebuild();
            }
            case GLFW.GLFW_KEY_Z -> {
                if (ctrl) {
                    if (event.hasShiftDown()) {
                        editor.projects().redo();
                    } else {
                        editor.projects().undo();
                    }
                    afterProjectChange();
                }
            }
            case GLFW.GLFW_KEY_Y -> {
                if (ctrl) {
                    editor.projects().redo();
                    afterProjectChange();
                }
            }
            case GLFW.GLFW_KEY_C -> {
                if (ctrl) {
                    editor.copySelection();
                }
            }
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) {
                    editor.paste();
                    rebuild();
                }
            }
            case GLFW.GLFW_KEY_S -> {
                if (ctrl) {
                    save();
                }
            }
            case GLFW.GLFW_KEY_N -> {
                if (ctrl) {
                    newShot();
                }
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                if (shot != null) {
                    double frame = event.hasShiftDown() ? 1.0 : 1.0 / editor.project().render.fps();
                    editor.setPlaying(false);
                    editor.setPlayhead(editor.playhead() + (event.key() == GLFW.GLFW_KEY_LEFT ? -frame : frame));
                    editor.setPreviewCamera(true);
                }
            }
            case GLFW.GLFW_KEY_HOME -> editor.setPlayhead(0);
            case GLFW.GLFW_KEY_END -> {
                if (shot != null) {
                    editor.setPlayhead(shot.duration);
                }
            }
            case GLFW.GLFW_KEY_F -> {
                editor.setPreviewCamera(!editor.previewCamera());
                rebuild();
            }
            case GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ESCAPE -> onClose();
            case GLFW.GLFW_KEY_K -> {
                if (ctrl) {
                    palette = new CommandPalette(paletteActions());
                }
            }
            case GLFW.GLFW_KEY_H -> PathGizmos.setVisible(!PathGizmos.visible());
            default -> {
                return super.keyPressed(event);
            }
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (palette != null) {
            palette.type((char) event.codepoint());
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public void removed() {
        commitFields();
        if (drag == Drag.FLY) {
            stopFly();
        }
        editor.setPlaying(false);
        manager.setEditorActive(false);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Used by templates and tests: the camera mode the editor implies. */
    public static boolean drivesCamera() {
        return ReplayManager.INSTANCE.camera().mode() == CameraDirector.Mode.PATH;
    }
}
