package dev.kinora.mc.ui;

import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.util.KinoraPaths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Keys for watching a replay. Kept out of the game's Controls screen on purpose: they only act
 * inside a replay, where the gameplay keys they share (E, F, Tab, ...) do nothing, and listing them
 * there would mark them all as conflicts. Saved in {@code kinora/keys.json}.
 */
public final class ReplayKeys {
    public enum Action {
        PLAY_PAUSE(GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_P),
        BACK(GLFW.GLFW_KEY_J, -1),
        FORWARD(GLFW.GLFW_KEY_L, -1),
        TICK_BACK(GLFW.GLFW_KEY_COMMA, -1),
        TICK_FORWARD(GLFW.GLFW_KEY_PERIOD, -1),
        SLOWER(GLFW.GLFW_KEY_LEFT_BRACKET, -1),
        FASTER(GLFW.GLFW_KEY_RIGHT_BRACKET, -1),
        NORMAL_SPEED(GLFW.GLFW_KEY_BACKSLASH, -1),
        PREVIOUS_MARKER(GLFW.GLFW_KEY_N, -1),
        NEXT_MARKER(GLFW.GLFW_KEY_M, -1),
        FREE_CAMERA(GLFW.GLFW_KEY_F, -1),
        SPECTATE(GLFW.GLFW_KEY_G, -1),
        ORBIT(GLFW.GLFW_KEY_O, -1),
        ROLL_LEFT(GLFW.GLFW_KEY_Z, -1),
        ROLL_RIGHT(GLFW.GLFW_KEY_C, -1),
        ROLL_RESET(GLFW.GLFW_KEY_X, -1),
        EDITOR(GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_TAB),
        PHOTO(GLFW.GLFW_KEY_B, -1),
        LIVE_CUT(GLFW.GLFW_KEY_R, -1),
        NAMES(GLFW.GLFW_KEY_V, -1);

        final int primary;
        final int secondary;

        Action(int primary, int secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        public Component label() {
            return Component.translatable("kinora.keys." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Action, Integer> KEYS = new EnumMap<>(Action.class);
    private static boolean loaded;

    private ReplayKeys() {}

    private static Path file() {
        return KinoraPaths.root().resolve("keys.json");
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (Files.isRegularFile(file())) {
                JsonObject o = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
                for (Action a : Action.values()) {
                    if (o.has(a.name())) {
                        KEYS.put(a, InputConstants.getKey(o.get(a.name()).getAsString()).getValue());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            KinoraMod.LOG.warn("Kinora could not read {}; using the default replay keys", file(), e);
        }
    }

    /** The key bound to an action (its primary key unless changed). */
    public static int key(Action a) {
        ensureLoaded();
        return KEYS.getOrDefault(a, a.primary);
    }

    /** The action a pressed key triggers, or null. A rebound action keeps no second key. */
    public static @Nullable Action action(int key) {
        ensureLoaded();
        for (Action a : Action.values()) {
            if (key(a) == key || !KEYS.containsKey(a) && a.secondary == key) {
                return a;
            }
        }
        return null;
    }

    public static void bind(Action a, int key) {
        ensureLoaded();
        KEYS.put(a, key);
        save();
    }

    public static void reset() {
        ensureLoaded();
        KEYS.clear();
        save();
    }

    /** The key's name as shown on a keyboard, e.g. "K", "Tab", "[". */
    public static Component keyName(int key) {
        return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName();
    }

    public static Component keyName(Action a) {
        return keyName(key(a));
    }

    private static void save() {
        JsonObject o = new JsonObject();
        KEYS.forEach((a, k) -> o.addProperty(a.name(), InputConstants.Type.KEYSYM.getOrCreate(k).getName()));
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException e) {
            KinoraMod.LOG.warn("Kinora could not save {}", file(), e);
        }
    }
}
