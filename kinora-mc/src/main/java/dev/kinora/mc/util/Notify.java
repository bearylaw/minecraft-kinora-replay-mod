package dev.kinora.mc.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** User-facing notifications: a toast, and the same text in the log. */
public final class Notify {
    private static final SystemToast.SystemToastId INFO = new SystemToast.SystemToastId(4000L);
    private static final SystemToast.SystemToastId WARNING = new SystemToast.SystemToastId(8000L);

    private Notify() {}

    /** True for toasts Kinora itself shows (the only ones allowed during a replay). */
    public static boolean isKinora(net.minecraft.client.gui.components.toasts.Toast toast) {
        return toast instanceof SystemToast system && (system.getToken() == INFO || system.getToken() == WARNING);
    }

    public static void info(Component title, Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.add(mc.gui.toastManager(), INFO, title, message));
    }

    /** A problem the user should act on: what happened, and what to do, in the message. */
    public static void warn(Component title, Component message) {
        dev.kinora.mc.KinoraMod.LOG.warn("{}: {}", title.getString(), message.getString());
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.add(mc.gui.toastManager(), WARNING, title, message));
    }
}
