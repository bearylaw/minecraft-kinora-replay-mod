package dev.kinora.mc.ui;

import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.util.KinoraPaths;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small things the UI remembers between sessions (which guides were seen), in {@code kinora/ui.json}. */
public final class UiState {
    private static JsonObject state;

    private UiState() {}

    private static Path file() {
        return KinoraPaths.root().resolve("ui.json");
    }

    private static JsonObject state() {
        if (state == null) {
            state = new JsonObject();
            try {
                if (Files.isRegularFile(file())) {
                    state = new Gson().fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
                }
            } catch (IOException | RuntimeException e) {
                KinoraMod.LOG.warn("Kinora could not read {}", file(), e);
            }
        }
        return state;
    }

    public static boolean flag(String name) {
        return state().has(name) && state().get(name).getAsBoolean();
    }

    public static void setFlag(String name, boolean value) {
        state().addProperty(name, value);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new Gson().toJson(state()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            KinoraMod.LOG.warn("Kinora could not save {}", file(), e);
        }
    }
}
