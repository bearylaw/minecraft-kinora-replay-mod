package dev.kinora.mc;

import dev.kinora.api.Kinora;
import dev.kinora.api.KinoraConstants;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kinora's entry point. Client only: Kinora is never needed on a server and registers nothing a
 * server would have to know about, so joining any server with or without it works.
 */
@Mod(value = KinoraConstants.MOD_ID, dist = Dist.CLIENT)
public final class KinoraMod {
    public static final Logger LOG = LoggerFactory.getLogger(KinoraConstants.MOD_NAME);

    private static String version = "dev";

    public KinoraMod(ModContainer container, IEventBus modBus) {
        version = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.CLIENT, KinoraConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        Kinora.installRuntime(KinoraRuntimeImpl.INSTANCE);
        KinoraClient.init(modBus);
        LOG.info("{} {} loaded", KinoraConstants.MOD_NAME, version);
    }

    public static String version() {
        return version;
    }
}
