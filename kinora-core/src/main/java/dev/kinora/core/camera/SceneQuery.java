package dev.kinora.core.camera;

/**
 * What camera rigs need to know about the replayed world. Implemented by kinora-mc over the live
 * client level and a short history of entity positions; tests implement it with fixed data.
 */
public interface SceneQuery {
    /** Position (feet) of an entity at a replay time, or null if unknown. */
    Vec3d entityPosition(int entityId, double replayTicks);

    /** Height above the feet a camera should aim at (eye or body centre). */
    default double entityAimHeight(int entityId) {
        return 1.0;
    }

    /** Body yaw of an entity in degrees, or NaN if unknown. */
    default double entityYaw(int entityId, double replayTicks) {
        return Double.NaN;
    }

    /**
     * The point where a straight line from {@code from} to {@code to} first enters a solid block,
     * or {@code to} if it is clear. Used for camera collision avoidance.
     */
    default Vec3d clip(Vec3d from, Vec3d to) {
        return to;
    }

    /**
     * True if a camera at {@code p} would be inside a block (or so close to one that the view clips
     * into it). Used to keep camera paths clear.
     */
    default boolean blocked(Vec3d p) {
        return false;
    }

    SceneQuery EMPTY = (id, ticks) -> null;
}
