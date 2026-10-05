package dev.kinora.mc.capture;

import dev.kinora.core.format.ReplayMetadata;
import dev.kinora.mc.KinoraConfig;
import dev.kinora.mc.KinoraMod;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

/** Builds the metadata stored at the start of a recording. Game thread. */
final class MetadataFactory {
    private MetadataFactory() {}

    static ReplayMetadata create(Minecraft minecraft, ReplayMetadata.Kind kind) {
        ReplayMetadata m = new ReplayMetadata();
        m.kind = kind;
        m.kinoraVersion = KinoraMod.version();
        m.minecraftVersion = SharedConstants.getCurrentVersion().name();
        m.protocolVersion = SharedConstants.getProtocolVersion();
        m.loader = "neoforge";
        ModList.get().getModContainerById("neoforge").ifPresent(c -> m.loaderVersion = c.getModInfo().getVersion().toString());
        for (IModInfo mod : ModList.get().getMods()) {
            m.mods.add(new ReplayMetadata.ModInfo(mod.getModId(), mod.getVersion().toString()));
        }
        m.singleplayer = minecraft.hasSingleplayerServer();
        if (m.singleplayer && minecraft.getSingleplayerServer() != null) {
            m.worldName = minecraft.getSingleplayerServer().getWorldData().getLevelName();
            m.worldFolder = minecraft.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize().getFileName().toString();
        } else {
            ServerData server = minecraft.getCurrentServer();
            if (server != null) {
                m.serverName = server.name;
                if (KinoraConfig.recordServerAddress()) {
                    m.serverAddress = server.ip;
                }
            }
        }
        if (minecraft.level != null) {
            m.startDimension = minecraft.level.dimension().identifier().toString();
        }
        m.player = new ReplayMetadata.PlayerInfo(minecraft.getUser().getName(), minecraft.getUser().getProfileId().toString());
        m.resourcePacks.addAll(minecraft.getResourcePackRepository().getSelectedIds());
        m.startedAtMillis = System.currentTimeMillis();
        return m;
    }

    /** A short label for file names: the world or server name. */
    static String label(Minecraft minecraft) {
        if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
            return minecraft.getSingleplayerServer().getWorldData().getLevelName();
        }
        ServerData server = minecraft.getCurrentServer();
        return server != null ? server.name : "replay";
    }
}
