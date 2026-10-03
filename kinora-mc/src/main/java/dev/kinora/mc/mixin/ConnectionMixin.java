package dev.kinora.mc.mixin;

import dev.kinora.mc.capture.CaptureHooks;

import io.netty.channel.ChannelPipeline;

import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inserts Kinora's capture taps into every client connection's pipeline as it is built.
 *
 * <p>{@code configurePacketHandler} runs inside {@code initChannel} for remote, local
 * (singleplayer) and wrapped connections alike, before any packet is read, so configuration-phase
 * packets are seen. NeoForge fires no event at that point (the first client network event,
 * {@code ClientPlayerNetworkEvent.LoggingIn}, comes after configuration). See docs/mixins.md.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "configurePacketHandler", at = @At("TAIL"))
    private void kinora$attachCapture(ChannelPipeline pipeline, CallbackInfo ci) {
        CaptureHooks.attach((Connection) (Object) this, pipeline);
    }
}
