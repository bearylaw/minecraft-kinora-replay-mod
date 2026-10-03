package dev.kinora.mc.camera;

import dev.kinora.core.camera.SceneQuery;
import dev.kinora.core.camera.Vec3d;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * {@link SceneQuery} over the replay world. Entities only exist at the current replay time, so a
 * short history of positions per replay tick is kept for the entities rigs ask about; positions
 * between ticks are interpolated exactly as the renderer interpolates them (between the position
 * after the previous tick and after the current one).
 */
public final class LiveScene implements SceneQuery {
    private static final int HISTORY_TICKS = 400;

    private final Int2ObjectOpenHashMap<Long2ObjectLinkedOpenHashMap<double[]>> history = new Int2ObjectOpenHashMap<>();

    /** Forgets recorded movement (seek, new replay) but keeps tracking the same entities. */
    public void clear() {
        history.values().forEach(Long2ObjectLinkedOpenHashMap::clear);
    }

    /** Called after every replay tick with the number of completed ticks. Records tracked entities. */
    public void afterTick(long completedTicks) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        var it = history.int2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var entry = it.next();
            Entity e = level.getEntity(entry.getIntKey());
            if (e == null) {
                continue;
            }
            Long2ObjectLinkedOpenHashMap<double[]> h = entry.getValue();
            h.put(completedTicks, new double[] {e.getX(), e.getY(), e.getZ(), e.getYRot()});
            while (h.size() > HISTORY_TICKS) {
                h.removeFirst();
            }
        }
    }

    /** Starts keeping history for an entity (a rig or look-at target). */
    public void track(int entityId) {
        if (entityId != Integer.MIN_VALUE) {
            history.computeIfAbsent(entityId, id -> new Long2ObjectLinkedOpenHashMap<>());
        }
    }

    @Override
    public Vec3d entityPosition(int entityId, double replayTicks) {
        track(entityId);
        double[] s = sample(entityId, replayTicks);
        return s == null ? null : new Vec3d(s[0], s[1], s[2]);
    }

    @Override
    public double entityYaw(int entityId, double replayTicks) {
        double[] s = sample(entityId, replayTicks);
        return s == null ? Double.NaN : s[3];
    }

    @Override
    public double entityAimHeight(int entityId) {
        ClientLevel level = Minecraft.getInstance().level;
        Entity e = level == null ? null : level.getEntity(entityId);
        return e == null ? 1.0 : Math.max(0.3, e.getBbHeight() * 0.85);
    }

    private double[] sample(int entityId, double replayTicks) {
        long whole = (long) Math.floor(replayTicks);
        double frac = replayTicks - whole;
        Long2ObjectLinkedOpenHashMap<double[]> h = history.get(entityId);
        double[] a = h == null ? null : h.get(whole - 1);
        double[] b = h == null ? null : h.get(whole);
        if (a != null && b != null) {
            return new double[] {lerp(a[0], b[0], frac), lerp(a[1], b[1], frac), lerp(a[2], b[2], frac), b[3]};
        }
        if (b != null) {
            return b;
        }
        if (a != null) {
            return a;
        }
        if (h != null && !h.isEmpty()) {
            // Outside the recorded stretch: hold the nearest end, never jump to "now" (that made
            // smoothed follow cameras lurch until a second of history had built up).
            long first = h.firstLongKey();
            long last = h.lastLongKey();
            return replayTicks < first ? h.get(first) : replayTicks > last ? h.get(last) : nearest(h, whole);
        }
        // No history at all (just seeked): fall back to where the entity is drawn now.
        ClientLevel level = Minecraft.getInstance().level;
        Entity e = level == null ? null : level.getEntity(entityId);
        if (e == null) {
            return null;
        }
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        var p = e.getPosition(pt);
        return new double[] {p.x, p.y, p.z, e.getYRot()};
    }

    private static double[] nearest(Long2ObjectLinkedOpenHashMap<double[]> h, long tick) {
        for (long d = 1; d < HISTORY_TICKS; d++) {
            double[] s = h.get(tick - d);
            if (s != null) {
                return s;
            }
            s = h.get(tick + d);
            if (s != null) {
                return s;
            }
        }
        return h.get(h.lastLongKey());
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    /** Margin around the camera: the near plane is 0.05 blocks away, and a little more looks safe. */
    private static final double CAMERA_MARGIN = 0.15;

    @Override
    public boolean blocked(Vec3d p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        double m = CAMERA_MARGIN;
        var box = new net.minecraft.world.phys.AABB(p.x() - m, p.y() - m, p.z() - m, p.x() + m, p.y() + m, p.z() + m);
        for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
                net.minecraft.util.Mth.floor(box.minX), net.minecraft.util.Mth.floor(box.minY), net.minecraft.util.Mth.floor(box.minZ),
                net.minecraft.util.Mth.floor(box.maxX), net.minecraft.util.Mth.floor(box.maxY), net.minecraft.util.Mth.floor(box.maxZ))) {
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (!shape.isEmpty() && shape.move(pos.getX(), pos.getY(), pos.getZ()).toAabbs().stream().anyMatch(b -> b.intersects(box))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Vec3d clip(Vec3d from, Vec3d to) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return to;
        }
        BlockHitResult hit = level.clip(new ClipContext(new net.minecraft.world.phys.Vec3(from.x(), from.y(), from.z()),
                new net.minecraft.world.phys.Vec3(to.x(), to.y(), to.z()), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) {
            return to;
        }
        return new Vec3d(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z);
    }
}
