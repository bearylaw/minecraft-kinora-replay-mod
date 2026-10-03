package dev.kinora.mc.hooks;

import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;

import net.minecraft.world.entity.Entity;

/** What the scene mixins ask while a replay is open. Game thread only. */
public final class SceneHooks {
    private SceneHooks() {}

    public static boolean visible(Entity entity) {
        ReplaySession session = ReplayManager.INSTANCE.session();
        return session == null || ReplayManager.INSTANCE.scene().visible(entity, session.recordedPlayerId());
    }

    public static boolean glowing(Entity entity) {
        return ReplayManager.INSTANCE.active() && ReplayManager.INSTANCE.scene().highlighted(entity);
    }
}
