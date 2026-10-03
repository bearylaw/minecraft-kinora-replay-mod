package dev.kinora.sample;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;

import org.lwjgl.glfw.GLFW;

import java.util.random.RandomGenerator;
import java.util.SplittableRandom;

/**
 * "Sparkles": press V to throw a burst of sparkles where you look. A tiny client-only effect, written
 * the way any mod's client-side visuals should be written to work in Kinora replays:
 *
 * <ol>
 *   <li>The effect is a pure function of a few numbers (where, how many, a seed), so it can be stored
 *       and played back.</li>
 *   <li>Those numbers go into a Kinora data track while recording ({@link KinoraIntegration}).</li>
 *   <li>Randomness comes from {@code Kinora.random} in replays, so a render looks the same every time.</li>
 *   <li>State a seek must restore (the counter) is captured and restored through the track.</li>
 *   <li>Kinora is optional: every Kinora call is in {@link KinoraIntegration}, which is only loaded when
 *       Kinora is installed, so this mod runs fine without it.</li>
 * </ol>
 */
@Mod(value = SampleMod.MOD_ID, dist = Dist.CLIENT)
public final class SampleMod {
    public static final String MOD_ID = "kinora_sample";

    static final KeyMapping SPARKLES = new KeyMapping("key.kinora_sample.sparkles", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V, new KeyMapping.Category(Identifier.fromNamespaceAndPath(MOD_ID, "main")));

    /** Sparkle bursts made so far (in a replay: so far in the replay's timeline). */
    static int count;

    private static final boolean KINORA = ModList.get().isLoaded("kinora");

    public SampleMod(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            e.registerCategory(SPARKLES.getCategory());
            e.register(SPARKLES);
        });
        modBus.addListener((RegisterGuiLayersEvent e) -> e.registerAboveAll(Identifier.fromNamespaceAndPath(MOD_ID, "counter"), SampleMod::hud));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> onTick());
        if (KINORA) {
            KinoraIntegration.setUp();
        }
    }

    private static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        while (SPARKLES.consumeClick()) {
            if (mc.player == null || mc.level == null || KINORA && KinoraIntegration.replaying()) {
                // In a replay the camera is not a player who can make sparkles; recorded ones play back.
                continue;
            }
            Vec3 at = mc.player.getEyePosition().add(mc.player.getLookAngle().scale(3));
            int amount = 24;
            long seed = mc.level.getGameTime();
            burst(at.x, at.y, at.z, amount, new SplittableRandom(seed));
            if (KINORA) {
                KinoraIntegration.recordBurst(at.x, at.y, at.z, amount);
            }
        }
    }

    /** The effect: the same inputs always give the same sparkles. */
    static void burst(double x, double y, double z, int amount, RandomGenerator random) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        count++;
        for (int i = 0; i < amount; i++) {
            double vx = (random.nextDouble() - 0.5) * 0.4;
            double vy = random.nextDouble() * 0.3;
            double vz = (random.nextDouble() - 0.5) * 0.4;
            mc.level.addParticle(ParticleTypes.END_ROD, x, y, z, vx, vy, vz);
        }
    }

    private static void hud(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (count == 0 || KINORA && KinoraIntegration.counterHidden()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        g.text(mc.font, Component.translatable("kinora_sample.hud.count", count), 6, 6, 0xFFFFE680, true);
    }
}
