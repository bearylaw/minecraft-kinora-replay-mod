package dev.kinora.mc;

import com.mojang.blaze3d.platform.InputConstants;

import dev.kinora.api.KinoraConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import org.lwjgl.glfw.GLFW;

/**
 * In-game key bindings (Options → Controls → Key Binds → Kinora). Only keys used while playing
 * live are vanilla key mappings; the replay editor has its own context-aware key layer (see
 * {@code dev.kinora.mc.ui.input}), so its keys never conflict with these or with other mods.
 *
 * <p>Defaults sit on function keys vanilla does not use.
 */
public final class KinoraKeys {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(KinoraConstants.MOD_ID, "main"));

    public static final KeyMapping TOGGLE_RECORDING = new KeyMapping("key.kinora.toggle_recording",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, CATEGORY);
    public static final KeyMapping MARKER = new KeyMapping("key.kinora.marker",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY);
    public static final KeyMapping SAVE_BUFFER = new KeyMapping("key.kinora.save_buffer",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);
    public static final KeyMapping OPEN_BROWSER = new KeyMapping("key.kinora.open_browser",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    private KinoraKeys() {}

    static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(TOGGLE_RECORDING);
        event.register(MARKER);
        event.register(SAVE_BUFFER);
        event.register(OPEN_BROWSER);
    }
}
