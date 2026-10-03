package dev.kinora.core.project;

import dev.kinora.core.timeline.Track;

/** The tracks a shot can have, by id. Ids are stored in project files: never rename. */
public final class Tracks {
    public static final String POSITION = "camera.position";
    public static final String ROTATION = "camera.rotation";
    public static final String ROLL = "camera.roll";
    public static final String FOV = "camera.fov";
    /** Time remap: replay time in ticks for each moment of the shot. */
    public static final String TIME = "time";
    public static final String LOOK_AT_WEIGHT = "lookat.weight";
    public static final String SHAKE = "shake.intensity";
    public static final String ORBIT_ANGLE = "orbit.angle";
    public static final String ORBIT_RADIUS = "orbit.radius";
    public static final String ORBIT_HEIGHT = "orbit.height";
    public static final String DOF_FOCUS = "dof.focus";
    public static final String DOF_APERTURE = "dof.aperture";
    public static final String EXPOSURE = "grade.exposure";
    public static final String CONTRAST = "grade.contrast";
    public static final String SATURATION = "grade.saturation";
    public static final String TEMPERATURE = "grade.temperature";
    public static final String TINT = "grade.tint";
    public static final String VIGNETTE = "fx.vignette";
    public static final String GRAIN = "fx.grain";
    public static final String CHROMATIC = "fx.chromatic";
    public static final String BLOOM = "fx.bloom";
    public static final String LETTERBOX = "fx.letterbox";
    /** Time of day override in ticks (0..24000), negative follows the recording. */
    public static final String WORLD_TIME = "world.time";
    /** Weather override: -1 recorded, 0 clear, 1 rain, 2 thunder. */
    public static final String WORLD_WEATHER = "world.weather";

    private Tracks() {}

    /** A new empty track of the right kind for an id. */
    public static Track create(String id) {
        Track track = switch (id) {
            case POSITION -> new Track(id, Track.Kind.PATH, 3);
            case ROTATION -> new Track(id, Track.Kind.ROTATION, 2);
            default -> new Track(id, Track.Kind.VALUE, 1);
        };
        track.color = colorOf(id);
        return track;
    }

    /** Default value of a value track with no keys. */
    public static double defaultValue(String id) {
        return switch (id) {
            case FOV -> 70;
            case LOOK_AT_WEIGHT, SHAKE -> 1;
            case ORBIT_RADIUS -> 6;
            case ORBIT_HEIGHT -> 2;
            case DOF_APERTURE -> 2.8;
            case SATURATION, CONTRAST -> 1;
            case WORLD_TIME, WORLD_WEATHER -> -1;
            default -> 0;
        };
    }

    /** Colour-blind-safe palette (Okabe-Ito) by track group. */
    public static int colorOf(String id) {
        if (id.startsWith("camera.position")) {
            return 0x0072B2;
        }
        if (id.startsWith("camera.")) {
            return 0x56B4E9;
        }
        if (id.equals(TIME)) {
            return 0xE69F00;
        }
        if (id.startsWith("lookat") || id.startsWith("orbit")) {
            return 0x009E73;
        }
        if (id.startsWith("shake") || id.startsWith("dof")) {
            return 0xCC79A7;
        }
        if (id.startsWith("grade") || id.startsWith("fx")) {
            return 0xF0E442;
        }
        return 0xD55E00;
    }
}
