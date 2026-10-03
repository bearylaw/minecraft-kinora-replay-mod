package dev.kinora.mc.hooks;

import dev.kinora.mc.KinoraKeys;
import dev.kinora.mc.playback.ReplayManager;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Key mappings while a replay is open. The replay keys and the editor's shortcuts overlap most
 * gameplay bindings (E, Tab, F, the number keys...), so every key mapping except Kinora's own, the
 * movement keys that fly the camera, screenshot and fullscreen reads as not pressed: no other mod
 * and no vanilla action reacts to them. Outside replays nothing changes.
 */
public final class InputHooks {
    private InputHooks() {}

    /** True if this mapping must stay silent now. */
    public static boolean blocked(KeyMapping mapping) {
        if (!ReplayManager.INSTANCE.active()) {
            return false;
        }
        if (mapping.getCategory() == KinoraKeys.CATEGORY) {
            return false;
        }
        var o = Minecraft.getInstance().options;
        return mapping != o.keyUp && mapping != o.keyDown && mapping != o.keyLeft && mapping != o.keyRight && mapping != o.keyJump
                && mapping != o.keyShift && mapping != o.keySprint && mapping != o.keyScreenshot && mapping != o.keyFullscreen;
    }
}
