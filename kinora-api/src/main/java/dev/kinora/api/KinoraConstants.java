/*
 * Kinora API - LGPL-3.0-only. See LICENSE-API.
 */
package dev.kinora.api;

/**
 * Kinora's identity. To rename the mod, change these, {@code gradle.properties} and the lang files;
 * nothing else spells the name out.
 */
public final class KinoraConstants {
    /** Mod id: prefixes config files, key categories, lang keys and resources. */
    public static final String MOD_ID = "kinora";
    /** Display name. */
    public static final String MOD_NAME = "Kinora Replay";
    /** Version of this API. Bumped on incompatible API changes only. */
    public static final int API_VERSION = 1;

    private KinoraConstants() {}
}
